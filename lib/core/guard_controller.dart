import 'dart:async';

import 'package:flutter/foundation.dart';

import 'guard_bridge.dart';
import 'models.dart';
import 'permissions.dart';

/// Holds the two things every screen needs: the live guard snapshot and the
/// persisted settings.
///
/// Both are owned natively. This class only mirrors them, so the UI can never
/// drift out of sync with the guard that is actually running.
class GuardController extends ChangeNotifier {
  GuardController({GuardBridge? bridge})
      : _bridge = bridge ?? GuardBridge.instance;

  final GuardBridge _bridge;

  StreamSubscription<GuardSnapshot>? _subscription;

  GuardSnapshot _snapshot = GuardSnapshot.empty;
  GuardSettings _settings = GuardSettings.empty;
  PermissionState _permissions = const PermissionState.unknown();
  bool _loading = true;
  bool _fullScreenAlarmAllowed = true;
  Object? _error;

  GuardSnapshot get snapshot => _snapshot;
  GuardSettings get settings => _settings;
  bool get loading => _loading;
  Object? get error => _error;

  /// What Android currently allows. Re-read whenever the app comes back to the
  /// foreground, because half of these are granted in another app entirely.
  PermissionState get permissions => _permissions;

  /// False on Android 14+ until the user grants the full-screen-intent
  /// permission. Without it the disarm screen cannot cover the lock screen and
  /// degrades to a heads-up notification, which is easy to miss entirely.
  bool get fullScreenAlarmAllowed => _fullScreenAlarmAllowed;

  Future<void> refreshCapabilities() async {
    try {
      _fullScreenAlarmAllowed = await _bridge.canUseFullScreenIntent();
      notifyListeners();
    } catch (_) {
      // Not fatal: the siren still works, only the lock-screen prompt suffers.
    }
  }

  Future<void> refreshPermissions() async {
    try {
      _permissions = await readPermissions(_bridge);
      _fullScreenAlarmAllowed = _permissions[Need.fullScreen];
      notifyListeners();
    } catch (_) {
      // Leave the previous answer in place rather than claiming everything is
      // missing because one platform call went wrong.
    }
  }

  GuardBridge get bridge => _bridge;

  /// Whether the one-off first run still has to happen.
  ///
  /// Deliberately says nothing about groups. Setting this phone up — who you
  /// are, and what Android has to allow — happens once in the life of the
  /// install. Belonging to a group is a different kind of thing entirely:
  /// people leave one group and join another all afternoon, and being marched
  /// back through a welcome screen to retype a name that is already set every
  /// time is nonsense. [needsGroup] handles that separately.
  ///
  /// Completion is recorded natively at the end of the last step rather than
  /// inferred from anything, so an interrupted first run resumes rather than
  /// counting as done.
  bool get needsOnboarding => !_settings.firstRunDone;

  /// No group yet — the app's default screen until one is created or joined.
  bool get needsGroup => !_settings.hasGroup;

  Future<void> completeOnboarding() => patch({'onboardingComplete': true});

  Future<void> initialise() async {
    _loading = true;
    notifyListeners();
    // Before anything that can throw: a failed settings read must not leave the
    // app with no way to hear from the guard.
    _listen();
    try {
      _settings = await _bridge.getSettings();
      if (_settings.hasGroup) {
        await _bridge.startService();
        final initial = await _bridge.getSnapshot();
        if (initial != null) _snapshot = initial;
      }
      await refreshPermissions();
      _error = null;
    } catch (e) {
      _error = e;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  /// Subscribes once and stays subscribed.
  ///
  /// Deliberately idempotent. Re-subscribing tears the platform stream down and
  /// builds it back up, and the native side takes the teardown as "the UI has
  /// gone away" and drops its snapshot listener — so a badly timed round trip
  /// leaves a live subscription wired to nothing and a screen that never
  /// updates again. The subscription survives groups being left and joined; it
  /// belongs to the app being open, not to the guard being configured.
  void _listen() {
    if (_subscription != null) return;
    _subscription = _bridge.snapshots.listen(
      (snapshot) {
        _snapshot = snapshot;
        notifyListeners();
      },
      onError: (Object e) {
        _error = e;
        notifyListeners();
      },
    );
  }

  Future<void> refreshSettings() async {
    _settings = await _bridge.getSettings();
    notifyListeners();
  }

  Future<void> patch(Map<String, Object?> values) async {
    _settings = await _bridge.updateSettings(values);
    notifyListeners();
  }

  // ---- commands ---------------------------------------------------------

  Future<void> arm() async {
    await _bridge.arm();
    await refreshSettings();
  }

  Future<void> disarm() async {
    await _bridge.disarm();
    await refreshSettings();
  }

  Future<void> armGroup() async {
    await _bridge.armGroup();
    await refreshSettings();
  }

  Future<void> disarmGroup() async {
    await _bridge.disarmGroup();
    await refreshSettings();
  }

  Future<void> clearAlarm() => _bridge.clearAlarm();
  Future<void> panic() => _bridge.panic();
  Future<void> testAlarm() => _bridge.testAlarm();

  Future<String> createGroup({
    required String groupName,
    required String selfName,
  }) async {
    final code = await _bridge.createGroup(
      groupName: groupName,
      selfName: selfName,
    );
    await _bridge.startService();
    await refreshSettings();
    return code;
  }

  Future<bool> joinGroup({
    required String code,
    required String groupName,
    required String selfName,
  }) async {
    final ok = await _bridge.joinGroup(
      code: code,
      groupName: groupName,
      selfName: selfName,
    );
    if (ok) {
      await _bridge.startService();
      await refreshSettings();
    }
    return ok;
  }

  Future<void> leaveGroup() async {
    await _bridge.leaveGroup();
    _snapshot = GuardSnapshot.empty;
    await refreshSettings();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }
}
