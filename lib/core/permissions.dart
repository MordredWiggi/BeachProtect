import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import 'guard_bridge.dart';

/// Everything the app needs Android to allow, in one place.
///
/// Shared rather than private to the permissions screen, because the home
/// screen has to be able to say "you are still missing two of these" without
/// duplicating the list — and a duplicated list is a list that drifts.

enum Need { bluetooth, bluetoothOn, notifications, battery, fullScreen, camera }

@immutable
class NeedInfo {
  const NeedInfo({
    required this.title,
    required this.icon,
    required this.why,
    required this.consequence,
    required this.required,
  });

  final String title;
  final IconData icon;

  /// Plain-language reason, written for someone who has never heard of BLE.
  final String why;

  /// What concretely stops working without it.
  final String consequence;

  /// Whether the guard is fundamentally broken without it.
  final bool required;
}

const Map<Need, NeedInfo> needs = {
  Need.bluetooth: NeedInfo(
    title: 'Bluetooth',
    icon: Icons.bluetooth_rounded,
    why: 'Your phones find and watch each other over Bluetooth. There is no '
        'internet, no account and no server involved - the phones talk only to '
        'each other, and nothing ever leaves the beach.',
    consequence: 'Without it the app cannot see the rest of the group at all.',
    required: true,
  ),
  Need.bluetoothOn: NeedInfo(
    title: 'Bluetooth switched on',
    icon: Icons.power_settings_new_rounded,
    why: 'The permission alone is not enough - the radio has to be running.',
    consequence: 'Nothing works while the radio is off.',
    required: true,
  ),
  Need.notifications: NeedInfo(
    title: 'Notifications',
    icon: Icons.notifications_active_rounded,
    why: 'Android only lets an app keep working in the background if it shows '
        'an ongoing notification. That notification is also how the alarm '
        'reaches you when the screen is off.',
    consequence: 'Without it Android will not let the guard run at all.',
    required: true,
  ),
  Need.battery: NeedInfo(
    title: 'Run without battery limits',
    icon: Icons.battery_charging_full_rounded,
    why: 'By default Android suspends background apps after a while to save '
        'power. That is exactly the wrong moment for a theft alarm to go to '
        'sleep - you are in the sea and your phone is on a towel.',
    consequence: 'Without it the guard may quietly stop after 15 to 30 minutes.',
    required: false,
  ),
  Need.fullScreen: NeedInfo(
    title: 'Full screen alarms',
    icon: Icons.screen_lock_portrait_rounded,
    why: 'Lets the disarm screen appear on top of the lock screen, so you can '
        'stop a false alarm in one tap instead of unlocking and hunting for '
        'the app.',
    consequence: 'Without it the alarm still sounds, but you have to unlock '
        'the phone first to stop it.',
    required: false,
  ),
  Need.camera: NeedInfo(
    title: 'Camera',
    icon: Icons.qr_code_scanner_rounded,
    why: 'Only used to scan a group QR code when joining. Nothing is recorded '
        'or stored.',
    consequence: 'Without it you can still join by typing the 16-character code.',
    required: false,
  ),
};

/// What is granted right now, as one immutable answer.
@immutable
class PermissionState {
  const PermissionState(this._granted);

  const PermissionState.unknown() : _granted = const {};

  final Map<Need, bool> _granted;

  bool operator [](Need need) => _granted[need] ?? false;

  List<Need> get missing =>
      needs.keys.where((n) => _granted[n] != true).toList();

  List<Need> get missingRequired =>
      missing.where((n) => needs[n]!.required).toList();

  bool get allRequiredGranted => missingRequired.isEmpty;
  bool get everythingGranted => missing.isEmpty;

  /// True before the first read has come back, so the UI can stay quiet rather
  /// than flashing a warning it is about to withdraw.
  bool get unread => _granted.isEmpty;
}

/// Reads every status, including the three that live in Android's own settings.
Future<PermissionState> readPermissions(GuardBridge bridge) async {
  final scan = await Permission.bluetoothScan.status;
  final advertise = await Permission.bluetoothAdvertise.status;
  final connect = await Permission.bluetoothConnect.status;
  final location = await Permission.locationWhenInUse.status;
  final notifications = await Permission.notification.status;
  final camera = await Permission.camera.status;

  // Android 12 split Bluetooth out of the location permission. On anything
  // older, a BLE scan is still legally a location request.
  final bluetoothOk =
      (scan.isGranted && advertise.isGranted && connect.isGranted) ||
          location.isGranted;

  return PermissionState({
    Need.bluetooth: bluetoothOk,
    Need.bluetoothOn: await bridge.bluetoothEnabled(),
    Need.notifications: notifications.isGranted,
    Need.battery: await bridge.isIgnoringBatteryOptimizations(),
    Need.fullScreen: await bridge.canUseFullScreenIntent(),
    Need.camera: camera.isGranted,
  });
}

/// Asks for one of them, through whichever route Android provides.
Future<void> requestNeed(Need need, GuardBridge bridge) async {
  switch (need) {
    case Need.bluetooth:
      await [
        Permission.bluetoothScan,
        Permission.bluetoothAdvertise,
        Permission.bluetoothConnect,
        Permission.locationWhenInUse,
      ].request();

    case Need.bluetoothOn:
      await bridge.requestEnableBluetooth();
      await Future<void>.delayed(const Duration(seconds: 2));

    case Need.notifications:
      final result = await Permission.notification.request();
      // Twice-denied permissions can only be restored from Android's own
      // settings, so send the user straight there rather than to a dialog
      // that will never appear again.
      if (result.isPermanentlyDenied) await bridge.openAppSettings();

    case Need.battery:
      await bridge.requestIgnoreBatteryOptimizations();
      await Future<void>.delayed(const Duration(seconds: 1));

    case Need.fullScreen:
      await bridge.openFullScreenIntentSettings();
      await Future<void>.delayed(const Duration(seconds: 1));

    case Need.camera:
      final result = await Permission.camera.request();
      if (result.isPermanentlyDenied) await bridge.openAppSettings();
  }
}
