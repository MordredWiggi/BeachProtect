import 'package:beachprotect/core/models.dart';
import 'package:flutter_test/flutter_test.dart';

/// Decoding tests for the snapshot bridge.
///
/// The native service is the source of truth, so the thing worth testing on the
/// Dart side is that a payload from it always decodes into something the UI can
/// render - including the awkward cases: NaN doubles arriving as null before a
/// filter has converged, and unknown enum names from a newer native build.
void main() {
  group('GuardSnapshot decoding', () {
    test('decodes a full payload', () {
      final snapshot = GuardSnapshot.fromMap(<Object?, Object?>{
        'state': 'ARMED',
        'radioProfile': 'CALM',
        'selfDeviceId': 4660,
        'selfName': 'Jan',
        'selfStationary': true,
        'selfMotionScore': 3,
        'pendingRemainingMs': 0,
        'alarmReason': null,
        'alarmSubjectId': 0,
        'alarmSubjectName': null,
        'peers': [
          <Object?, Object?>{
            'deviceId': 161,
            'name': 'Lisa',
            'rssi': -61.5,
            'baseline': -59.0,
            'dropDb': 2.5,
            'proximity': 'CLOSE',
            'battery': 74,
            'armed': true,
            'stationary': true,
            'votesRequired': 2,
          },
        ],
        'box': <Object?, Object?>{
          'configured': true,
          'name': 'JBL Flip',
          'audioLinkConnected': true,
          'bleProximity': 'NEARBY',
        },
        'warnings': ['PEER_BATTERY_LOW'],
        'diagnostics': <Object?, Object?>{
          'bluetoothOn': true,
          'scanning': true,
          'advertising': true,
          'batteryPercent': 88,
        },
      });

      expect(snapshot.state, GuardState.armed);
      expect(snapshot.radioProfile, RadioProfile.calm);
      expect(snapshot.selfName, 'Jan');
      expect(snapshot.peers, hasLength(1));
      expect(snapshot.peers.single.displayName, 'Lisa');
      expect(snapshot.peers.single.proximity, Proximity.close);
      expect(snapshot.armedPeerCount, 1);
      expect(snapshot.box.displayName, 'JBL Flip');
      expect(snapshot.box.bleProximity, Proximity.nearby);
      expect(snapshot.warnings, contains(GuardWarning.peerBatteryLow));
      expect(snapshot.diagnostics.batteryPercent, 88);
    });

    test('survives a completely empty payload', () {
      final snapshot = GuardSnapshot.fromMap(<Object?, Object?>{});
      expect(snapshot.state, GuardState.disarmed);
      expect(snapshot.peers, isEmpty);
      expect(snapshot.box.configured, isFalse);
    });

    test('null RSSI before the filter converges does not crash', () {
      final snapshot = GuardSnapshot.fromMap(<Object?, Object?>{
        'peers': [
          <Object?, Object?>{'deviceId': 1, 'rssi': null, 'baseline': null},
        ],
      });
      expect(snapshot.peers.single.rssi, isNull);
      expect(snapshot.peers.single.proximity, Proximity.unknown);
    });

    test('unrecognised enum names fall back instead of throwing', () {
      final snapshot = GuardSnapshot.fromMap(<Object?, Object?>{
        'state': 'SOMETHING_FROM_THE_FUTURE',
        'radioProfile': 'WARP',
      });
      expect(snapshot.state, GuardState.disarmed);
      expect(snapshot.radioProfile, RadioProfile.calm);
    });

    test('peers without a broadcast name get a readable fallback', () {
      final peer = PeerInfo.fromMap(<Object?, Object?>{'deviceId': 0x0A1F});
      expect(peer.displayName, 'Phone 0A1F');
    });
  });

  group('Consensus rule', () {
    // Must stay identical to EngineConfig.observersRequiredFor in Kotlin,
    // because the settings screen uses this to tell the user how many of their
    // friends' phones have to agree.
    test('matches the native rule at the default third', () {
      const s = GuardSettings.empty;
      expect(s.observersRequiredFor(0), 0);
      expect(s.observersRequiredFor(1), 1);
      expect(s.observersRequiredFor(2), 1);
      expect(s.observersRequiredFor(3), 1);
      expect(s.observersRequiredFor(4), 2);
      expect(s.observersRequiredFor(6), 2);
      expect(s.observersRequiredFor(7), 3);
      expect(s.observersRequiredFor(10), 4);
    });

    test('never demands more witnesses than the group can supply', () {
      final strict = GuardSettings.fromMap(<Object?, Object?>{
        'consensusRatio': 1.0,
        'minObservers': 4,
      });
      expect(strict.observersRequiredFor(2), 2);
      expect(strict.observersRequiredFor(5), 5);
    });
  });

  group('Settings decoding', () {
    test('maps wire enum names both ways', () {
      final settings = GuardSettings.fromMap(<Object?, Object?>{
        'disarmMode': 'PIN_ONLY',
        'powerProfile': 'ULTRA_SAVER',
        'alarmTarget': 'BOX_ONLY',
        'peerNames': {'161': 'Lisa'},
      });

      expect(settings.disarmMode, DisarmMode.pinOnly);
      expect(settings.powerProfile, PowerProfile.ultraSaver);
      expect(settings.alarmTarget, AlarmTarget.boxOnly);
      expect(settings.peerNames[161], 'Lisa');

      // Every enum must round-trip back to the exact name Kotlin expects.
      expect(DisarmMode.pinOnly.wireName, 'PIN_ONLY');
      expect(PowerProfile.ultraSaver.wireName, 'ULTRA_SAVER');
      expect(AlarmTarget.boxOnly.wireName, 'BOX_ONLY');
      for (final mode in DisarmMode.values) {
        expect(GuardSettings.fromMap({'disarmMode': mode.wireName}).disarmMode, mode);
      }
      for (final profile in PowerProfile.values) {
        expect(
          GuardSettings.fromMap({'powerProfile': profile.wireName}).powerProfile,
          profile,
        );
      }
      for (final target in AlarmTarget.values) {
        expect(
          GuardSettings.fromMap({'alarmTarget': target.wireName}).alarmTarget,
          target,
        );
      }
    });

    /// The first run is only over when the walkthrough says so.
    ///
    /// Inferring it from "a group exists" cut the wizard off at step two of
    /// four - the group is created there - so the PIN and the whole
    /// permissions walkthrough were never shown at all.
    test('an unfinished first run is not mistaken for a finished one', () {
      final midway = GuardSettings.fromMap(<Object?, Object?>{
        'hasGroup': true,
        'selfName': 'Jan',
      });
      expect(midway.onboardingComplete, isFalse);

      final done = GuardSettings.fromMap(<Object?, Object?>{
        'hasGroup': true,
        'selfName': 'Jan',
        'onboardingComplete': true,
      });
      expect(done.onboardingComplete, isTrue);
    });
  });
}
