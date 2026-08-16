import 'package:flutter/services.dart';

import 'models.dart';

/// Thin typed wrapper around the two platform channels.
///
/// Deliberately dumb: it does no caching and holds no state, so there is never
/// a stale copy of the guard's status floating around in Dart. Everything the
/// UI renders comes from [snapshots].
class GuardBridge {
  GuardBridge._();

  static final GuardBridge instance = GuardBridge._();

  static const MethodChannel _methods = MethodChannel('com.beachprotect/guard');
  static const EventChannel _events =
      EventChannel('com.beachprotect/guard_events');

  Stream<GuardSnapshot>? _snapshots;

  /// Live guard status, pushed by the native service on every tick.
  Stream<GuardSnapshot> get snapshots => _snapshots ??= _events
      .receiveBroadcastStream()
      .where((event) => event is Map)
      .map((event) => GuardSnapshot.fromMap(event as Map<Object?, Object?>))
      .asBroadcastStream();

  // ---- settings ---------------------------------------------------------

  Future<GuardSettings> getSettings() async {
    final result = await _methods.invokeMapMethod<String, Object?>('getSettings');
    return GuardSettings.fromMap(result ?? <String, Object?>{});
  }

  Future<GuardSettings> updateSettings(Map<String, Object?> patch) async {
    final result =
        await _methods.invokeMapMethod<String, Object?>('updateSettings', patch);
    return GuardSettings.fromMap(result ?? <String, Object?>{});
  }

  // ---- group ------------------------------------------------------------

  Future<String> createGroup({
    required String groupName,
    required String selfName,
  }) async {
    final code = await _methods.invokeMethod<String>('createGroup', {
      'groupName': groupName,
      'selfName': selfName,
    });
    return code ?? '';
  }

  Future<bool> joinGroup({
    required String code,
    required String groupName,
    required String selfName,
  }) async {
    final ok = await _methods.invokeMethod<bool>('joinGroup', {
      'code': code,
      'groupName': groupName,
      'selfName': selfName,
    });
    return ok ?? false;
  }

  Future<void> leaveGroup() => _methods.invokeMethod('leaveGroup');

  Future<void> renamePeer(int deviceId, String? name) =>
      _methods.invokeMethod('renamePeer', {'deviceId': deviceId, 'name': name});

  Future<bool> setPin(String pin) async =>
      await _methods.invokeMethod<bool>('setPin', {'pin': pin}) ?? false;

  Future<bool> checkPin(String pin) async =>
      await _methods.invokeMethod<bool>('checkPin', {'pin': pin}) ?? false;

  // ---- guard control ----------------------------------------------------

  Future<void> startService() => _methods.invokeMethod('startService');
  Future<void> stopService() => _methods.invokeMethod('stopService');
  Future<void> arm() => _methods.invokeMethod('arm');
  Future<void> disarm() => _methods.invokeMethod('disarm');
  Future<void> armGroup() => _methods.invokeMethod('armGroup');
  Future<void> disarmGroup() => _methods.invokeMethod('disarmGroup');
  Future<void> clearAlarm() => _methods.invokeMethod('clearAlarm');
  Future<void> panic() => _methods.invokeMethod('panic');
  Future<void> testAlarm() => _methods.invokeMethod('testAlarm');

  Future<GuardSnapshot?> getSnapshot() async {
    final result = await _methods.invokeMapMethod<String, Object?>('getSnapshot');
    return result == null ? null : GuardSnapshot.fromMap(result);
  }

  // ---- box --------------------------------------------------------------

  Future<List<BtDevice>> pairedAudioDevices() async {
    final result = await _methods.invokeListMethod<Object?>('pairedAudioDevices');
    return (result ?? [])
        .whereType<Map<Object?, Object?>>()
        .map(BtDevice.fromMap)
        .toList();
  }

  Future<List<BtDevice>> discoverBleDevices({int durationMs = 6000}) async {
    final result = await _methods
        .invokeListMethod<Object?>('discoverBleDevices', {'durationMs': durationMs});
    return (result ?? [])
        .whereType<Map<Object?, Object?>>()
        .map(BtDevice.fromMap)
        .toList();
  }

  // ---- simulator --------------------------------------------------------

  Future<List<SimScenario>> simulationCatalogue() async {
    final result = await _methods.invokeListMethod<Object?>('simulationCatalogue');
    return (result ?? [])
        .whereType<Map<Object?, Object?>>()
        .map(SimScenario.fromMap)
        .toList();
  }

  Future<void> startSimulation(String scenario) =>
      _methods.invokeMethod('startSimulation', {'scenario': scenario});

  Future<void> stopSimulation() => _methods.invokeMethod('stopSimulation');

  /// Raises the real disarm surface after a few seconds, so the user can lock
  /// the phone and check whether it actually appears over the lock screen.
  Future<void> lockScreenTest() => _methods.invokeMethod('lockScreenTest');

  // ---- system -----------------------------------------------------------

  Future<bool> bluetoothEnabled() async =>
      await _methods.invokeMethod<bool>('bluetoothEnabled') ?? false;

  Future<bool> advertisingSupported() async =>
      await _methods.invokeMethod<bool>('advertisingSupported') ?? false;

  Future<void> requestEnableBluetooth() =>
      _methods.invokeMethod('requestEnableBluetooth');

  Future<bool> isIgnoringBatteryOptimizations() async =>
      await _methods.invokeMethod<bool>('isIgnoringBatteryOptimizations') ?? false;

  Future<void> requestIgnoreBatteryOptimizations() =>
      _methods.invokeMethod('requestIgnoreBatteryOptimizations');

  Future<bool> canUseFullScreenIntent() async =>
      await _methods.invokeMethod<bool>('canUseFullScreenIntent') ?? true;

  Future<void> openFullScreenIntentSettings() =>
      _methods.invokeMethod('openFullScreenIntentSettings');

  Future<void> openAppSettings() => _methods.invokeMethod('openAppSettings');
}
