import 'package:flutter/foundation.dart';

/// Dart mirrors of the native guard model.
///
/// The native side is the single source of truth; everything here is a
/// read-only projection of one snapshot pushed over the event channel.

T _enumFrom<T>(List<T> values, String? name, T fallback) {
  if (name == null) return fallback;
  final normalised = name.replaceAll('_', '').toLowerCase();
  for (final value in values) {
    final candidate = value.toString().split('.').last.toLowerCase();
    if (candidate == normalised) return value;
  }
  return fallback;
}

enum GuardState { disarmed, calibrating, armed, suspicious, pending, alarm }

extension GuardStateX on GuardState {
  bool get isProtecting => this != GuardState.disarmed;

  String get label => switch (this) {
        GuardState.disarmed => 'Not guarding',
        GuardState.calibrating => 'Getting my bearings',
        GuardState.armed => 'Guarding',
        GuardState.suspicious => 'Checking something',
        GuardState.pending => 'Put the phone down',
        GuardState.alarm => 'Theft alarm',
      };

  String get detail => switch (this) {
        GuardState.disarmed => 'Tap the shield when your things are laid out.',
        GuardState.calibrating =>
          'Learning how strong each phone\'s signal is from here. '
              'Leave everything where it is for a moment.',
        GuardState.armed => 'Everything is where it should be.',
        GuardState.suspicious =>
          'Something moved. Listening harder for a few seconds.',
        GuardState.pending => 'Disarm now if this is you.',
        GuardState.alarm => 'The whole group has been alerted.',
      };
}

/// How well this phone can currently hear one group member.
///
/// Three states rather than a boolean, and decided natively. A boolean "is this
/// peer's telemetry current?" is a single hard edge, and a single hard edge on a
/// duty-cycled radio flaps: the calm scanner listens about a quarter of the
/// time, so a run of missed windows reaching the threshold is an ordinary event
/// — and every one of them repainted the card, green to grey and straight back.
/// That is what the group list "changing at random" actually was.
///
/// [missing] is the dead band. It is worth annotating a card with and worth
/// spending fast radio on, but it is not news: the card goes on saying exactly
/// what it said. Only [lost] changes anything.
enum PeerPresence { present, missing, lost }

enum RadioProfile { calm, alert, critical }

extension RadioProfileX on RadioProfile {
  String get label => switch (this) {
        RadioProfile.calm => 'Low power',
        RadioProfile.alert => 'Fast scan',
        RadioProfile.critical => 'Maximum',
      };
}

enum Proximity { unknown, here, close, nearby, far, veryFar }

extension ProximityX on Proximity {
  String get label => switch (this) {
        Proximity.unknown => 'Unknown',
        Proximity.here => 'Right here',
        Proximity.close => 'Close',
        Proximity.nearby => 'Nearby',
        Proximity.far => 'Far',
        Proximity.veryFar => 'Very far',
      };

  /// 0..1, used for the signal bars.
  double get strength => switch (this) {
        Proximity.unknown => 0,
        Proximity.here => 1.0,
        Proximity.close => 0.8,
        Proximity.nearby => 0.6,
        Proximity.far => 0.35,
        Proximity.veryFar => 0.15,
      };
}

enum AlarmReason {
  theftConsensus,
  peerLost,
  pickupUnconfirmed,
  relayed,
  boxTaken,
  panic,
  test,
}

extension AlarmReasonX on AlarmReason {
  String get label => switch (this) {
        AlarmReason.theftConsensus => 'Phone being carried away',
        AlarmReason.peerLost => 'Phone vanished from the group',
        AlarmReason.pickupUnconfirmed => 'This phone was picked up',
        AlarmReason.relayed => 'Alarm from another phone',
        AlarmReason.boxTaken => 'Speaker being taken',
        AlarmReason.panic => 'Panic alarm',
        AlarmReason.test => 'Test alarm',
      };
}

enum GuardWarning {
  peerBatteryLow,
  peerLostLikelyBattery,
  boxSignalWeak,
  boxLinkFlapping,
  noPeers,
  bluetoothOff,
  advertisingUnavailable,
}

extension GuardWarningX on GuardWarning {
  String get label => switch (this) {
        GuardWarning.peerBatteryLow => 'A phone in the group is low on battery',
        GuardWarning.peerLostLikelyBattery =>
          'A phone went quiet, but its battery was nearly flat',
        GuardWarning.boxSignalWeak => 'The speaker\'s signal is getting weak',
        GuardWarning.boxLinkFlapping =>
          'The speaker keeps disconnecting and reconnecting',
        GuardWarning.noPeers => 'No other phones found yet',
        GuardWarning.bluetoothOff => 'Bluetooth is switched off',
        GuardWarning.advertisingUnavailable =>
          'This phone cannot broadcast over Bluetooth, so others cannot watch it',
      };

  bool get isSevere =>
      this == GuardWarning.bluetoothOff ||
      this == GuardWarning.advertisingUnavailable;
}

@immutable
class PeerInfo {
  const PeerInfo({
    required this.deviceId,
    required this.name,
    required this.rssi,
    required this.baseline,
    required this.dropDb,
    required this.slopeDbPerSecond,
    required this.proximity,
    required this.estimatedMetres,
    required this.battery,
    required this.armed,
    required this.alarming,
    required this.stationary,
    required this.motionScore,
    required this.boxGuardian,
    required this.simulated,
    required this.lastSeenMsAgo,
    required this.presence,
    required this.staleAfterMs,
    required this.suspected,
    required this.votesAgainst,
    required this.votesRequired,
  });

  final int deviceId;
  final String? name;
  final double? rssi;
  final double? baseline;
  final double? dropDb;
  final double? slopeDbPerSecond;
  final Proximity proximity;
  final double? estimatedMetres;
  final int battery;
  final bool armed;
  final bool alarming;
  final bool stationary;
  final int motionScore;
  final bool boxGuardian;
  final bool simulated;
  final int lastSeenMsAgo;

  /// How well this peer is currently being heard; see [PeerPresence].
  ///
  /// Decided natively, where the arrival times and the scan duty cycle are
  /// known. The UI used to apply its own flat rule, which at the calm profile is
  /// shorter than an ordinary gap between two scan results: a phone on the same
  /// towel flickered between "still, watched" and "no signal" once a second.
  final PeerPresence presence;

  /// How long silence has to run before [presence] leaves [PeerPresence.present].
  final int staleAfterMs;
  final bool suspected;
  final int votesAgainst;
  final int votesRequired;

  String get displayName =>
      name?.trim().isNotEmpty == true
          ? name!.trim()
          : 'Phone ${deviceId.toRadixString(16).toUpperCase().padLeft(4, '0')}';

  /// Whether the telemetry on this record is current enough to reason about.
  ///
  /// Deliberately not the same question as what to *show*: a [PeerPresence.missing]
  /// peer keeps saying whatever it last said, because the alternative is a list
  /// that flickers. Only [lost] replaces it.
  bool get current => presence == PeerPresence.present;

  /// Silent for long enough that silence is the story, not the telemetry.
  bool get lost => presence == PeerPresence.lost;

  /// Quiet for longer than usual, but well inside what a duty cycle explains.
  bool get faint => presence == PeerPresence.missing;

  static PeerInfo fromMap(Map<Object?, Object?> map) => PeerInfo(
        deviceId: (map['deviceId'] as num?)?.toInt() ?? 0,
        name: map['name'] as String?,
        rssi: (map['rssi'] as num?)?.toDouble(),
        baseline: (map['baseline'] as num?)?.toDouble(),
        dropDb: (map['dropDb'] as num?)?.toDouble(),
        slopeDbPerSecond: (map['slopeDbPerSecond'] as num?)?.toDouble(),
        proximity: _enumFrom(
            Proximity.values, map['proximity'] as String?, Proximity.unknown),
        estimatedMetres: (map['estimatedMetres'] as num?)?.toDouble(),
        battery: (map['battery'] as num?)?.toInt() ?? 0,
        armed: map['armed'] as bool? ?? false,
        alarming: map['alarming'] as bool? ?? false,
        stationary: map['stationary'] as bool? ?? true,
        motionScore: (map['motionScore'] as num?)?.toInt() ?? 0,
        boxGuardian: map['boxGuardian'] as bool? ?? false,
        simulated: map['simulated'] as bool? ?? false,
        lastSeenMsAgo: (map['lastSeenMsAgo'] as num?)?.toInt() ?? 0,
        presence: _enumFrom(PeerPresence.values, map['presence'] as String?,
            PeerPresence.present),
        staleAfterMs: (map['staleAfterMs'] as num?)?.toInt() ?? 12000,
        suspected: map['suspected'] as bool? ?? false,
        votesAgainst: (map['votesAgainst'] as num?)?.toInt() ?? 0,
        votesRequired: (map['votesRequired'] as num?)?.toInt() ?? 1,
      );
}

@immutable
class BoxInfo {
  const BoxInfo({
    required this.configured,
    required this.name,
    required this.address,
    required this.audioLinkConnected,
    required this.bleRssi,
    required this.bleProximity,
    required this.bleTracked,
    required this.guardedByThisPhone,
  });

  final bool configured;
  final String? name;
  final String? address;
  final bool audioLinkConnected;
  final double? bleRssi;
  final Proximity bleProximity;
  final bool bleTracked;
  final bool guardedByThisPhone;

  String get displayName => name?.trim().isNotEmpty == true ? name!.trim() : 'Speaker';

  static const empty = BoxInfo(
    configured: false,
    name: null,
    address: null,
    audioLinkConnected: false,
    bleRssi: null,
    bleProximity: Proximity.unknown,
    bleTracked: false,
    guardedByThisPhone: false,
  );

  static BoxInfo fromMap(Map<Object?, Object?>? map) {
    if (map == null) return empty;
    return BoxInfo(
      configured: map['configured'] as bool? ?? false,
      name: map['name'] as String?,
      address: map['address'] as String?,
      audioLinkConnected: map['audioLinkConnected'] as bool? ?? false,
      bleRssi: (map['bleRssi'] as num?)?.toDouble(),
      bleProximity: _enumFrom(
          Proximity.values, map['bleProximity'] as String?, Proximity.unknown),
      bleTracked: map['bleTracked'] as bool? ?? false,
      guardedByThisPhone: map['guardedByThisPhone'] as bool? ?? false,
    );
  }
}

@immutable
class Diagnostics {
  const Diagnostics({
    required this.bluetoothOn,
    required this.advertisingSupported,
    required this.advertising,
    required this.scanning,
    required this.hasSignificantMotion,
    required this.batteryPercent,
    required this.powerProfile,
    required this.wakeLockHeld,
    required this.serviceRunning,
    required this.packetsHeard,
    required this.beaconsHeard,
    required this.sirenAudible,
    required this.simulationRunning,
    required this.simulationScenario,
    required this.simulationNote,
    required this.simulationElapsedMs,
    required this.simulationResults,
    required this.simulationTimings,
    required this.boxLinkConnected,
  });

  final bool bluetoothOn;
  final bool advertisingSupported;
  final bool advertising;
  final bool scanning;
  final bool hasSignificantMotion;
  final int batteryPercent;
  final String powerProfile;
  final bool wakeLockHeld;
  final bool serviceRunning;

  /// Advertisements that got past the hardware scan filter, ours or not.
  ///
  /// Zero while scanning means the radio is hearing nothing at all; a healthy
  /// number here with [beaconsHeard] at zero means packets are arriving and
  /// being rejected — a different group, or a different build.
  final int packetsHeard;

  /// ...and how many of those authenticated as members of this group.
  final int beaconsHeard;

  /// Whether the siren genuinely opened an audio output. False during an alarm
  /// means the phone believes it is screaming while sitting there mutely.
  final bool sirenAudible;
  final bool simulationRunning;
  final String? simulationScenario;
  final String simulationNote;
  final int simulationElapsedMs;
  final Map<String, String> simulationResults;

  /// Scenario id -> milliseconds from the incident starting to the alarm.
  final Map<String, int> simulationTimings;
  final bool boxLinkConnected;

  static const empty = Diagnostics(
    bluetoothOn: false,
    advertisingSupported: true,
    advertising: false,
    scanning: false,
    hasSignificantMotion: true,
    batteryPercent: 100,
    powerProfile: 'BALANCED',
    wakeLockHeld: false,
    serviceRunning: false,
    packetsHeard: 0,
    beaconsHeard: 0,
    sirenAudible: false,
    simulationRunning: false,
    simulationScenario: null,
    simulationNote: '',
    simulationElapsedMs: 0,
    simulationResults: <String, String>{},
    simulationTimings: <String, int>{},
    boxLinkConnected: false,
  );

  static Diagnostics fromMap(Map<Object?, Object?>? map) {
    if (map == null) return empty;
    final results = <String, String>{};
    final raw = map['simulationResults'];
    if (raw is Map) {
      raw.forEach((key, value) => results['$key'] = '$value');
    }
    final timings = <String, int>{};
    final rawTimings = map['simulationTimings'];
    if (rawTimings is Map) {
      rawTimings.forEach((key, value) {
        if (value is num) timings['$key'] = value.toInt();
      });
    }
    return Diagnostics(
      bluetoothOn: map['bluetoothOn'] as bool? ?? false,
      advertisingSupported: map['advertisingSupported'] as bool? ?? true,
      advertising: map['advertising'] as bool? ?? false,
      scanning: map['scanning'] as bool? ?? false,
      hasSignificantMotion: map['hasSignificantMotion'] as bool? ?? true,
      batteryPercent: (map['batteryPercent'] as num?)?.toInt() ?? 100,
      powerProfile: map['powerProfile'] as String? ?? 'BALANCED',
      wakeLockHeld: map['wakeLockHeld'] as bool? ?? false,
      serviceRunning: map['serviceRunning'] as bool? ?? false,
      packetsHeard: (map['packetsHeard'] as num?)?.toInt() ?? 0,
      beaconsHeard: (map['beaconsHeard'] as num?)?.toInt() ?? 0,
      sirenAudible: map['sirenAudible'] as bool? ?? false,
      simulationRunning: map['simulationRunning'] as bool? ?? false,
      simulationScenario: map['simulationScenario'] as String?,
      simulationNote: map['simulationNote'] as String? ?? '',
      simulationElapsedMs: (map['simulationElapsedMs'] as num?)?.toInt() ?? 0,
      simulationResults: results,
      simulationTimings: timings,
      boxLinkConnected: map['boxLinkConnected'] as bool? ?? false,
    );
  }
}

@immutable
class GuardSnapshot {
  const GuardSnapshot({
    required this.state,
    required this.radioProfile,
    required this.selfDeviceId,
    required this.selfName,
    required this.selfStationary,
    required this.selfMotionScore,
    required this.pickupArmed,
    required this.pickupArmsInMs,
    required this.pendingRemainingMs,
    required this.alarmReason,
    required this.alarmSubjectId,
    required this.alarmSubjectName,
    required this.groupAlarmActive,
    required this.stopPending,
    required this.stopConfirmed,
    required this.stopExpected,
    required this.peers,
    required this.box,
    required this.warnings,
    required this.diagnostics,
  });

  final GuardState state;
  final RadioProfile radioProfile;
  final int selfDeviceId;
  final String selfName;
  final bool selfStationary;
  final int selfMotionScore;

  /// Whether lifting this phone would actually trigger anything yet.
  final bool pickupArmed;

  /// Milliseconds until [pickupArmed] becomes true.
  final int pickupArmsInMs;
  final int pendingRemainingMs;
  final AlarmReason? alarmReason;
  final int alarmSubjectId;
  final String? alarmSubjectName;

  /// Whether *the group* is in an incident, which is not the same question as
  /// whether this phone is.
  ///
  /// The alarm controls used to be gated on the local state, so the instant
  /// somebody silenced their own handset the buttons that could reach everyone
  /// else disappeared — replaced, absurdly, by "Arm all" — while the rest of the
  /// group carried on screaming.
  final bool groupAlarmActive;

  /// Whether this phone is still telling the group to stop, or to stand down,
  /// and somebody has not confirmed yet.
  ///
  /// The banner used to be binary — "the group is still alarming" — driven by a
  /// twelve second memory of the last alarming beacon heard, refreshed by *any*
  /// of them including echoes of the very incident the user had just called off.
  /// So it appeared, aged out, and reappeared on the next straggler, about an
  /// incident that was finished. Now that announcements are acknowledged, the
  /// phone knows the actual answer and says it.
  final bool stopPending;

  /// How many of [stopExpected] phones have confirmed the outstanding stop.
  final int stopConfirmed;

  /// How many phones this one can currently hear, and is therefore waiting on.
  final int stopExpected;
  final List<PeerInfo> peers;
  final BoxInfo box;
  final Set<GuardWarning> warnings;
  final Diagnostics diagnostics;

  static const empty = GuardSnapshot(
    state: GuardState.disarmed,
    radioProfile: RadioProfile.calm,
    selfDeviceId: 0,
    selfName: '',
    selfStationary: true,
    selfMotionScore: 0,
    pickupArmed: false,
    pickupArmsInMs: 0,
    pendingRemainingMs: 0,
    alarmReason: null,
    alarmSubjectId: 0,
    alarmSubjectName: null,
    groupAlarmActive: false,
    stopPending: false,
    stopConfirmed: 0,
    stopExpected: 0,
    peers: <PeerInfo>[],
    box: BoxInfo.empty,
    warnings: <GuardWarning>{},
    diagnostics: Diagnostics.empty,
  );

  /// Peers we can currently *see* guarding. A phone whose last beacon said
  /// "armed" a minute ago is not evidence of anything.
  int get armedPeerCount =>
      peers.where((p) => p.armed && p.presence != PeerPresence.lost).length;

  /// Whether anything in the group is being watched, this phone included.
  ///
  /// What "Arm all" / "Disarm all" should offer depends on the group, not on
  /// this handset: a phone that has stood itself down is still the one holding
  /// the button that stands everybody else down.
  bool get anyoneGuarding => state.isProtecting || armedPeerCount > 0;

  static GuardSnapshot fromMap(Map<Object?, Object?> map) {
    final peerList = (map['peers'] as List?)
            ?.whereType<Map<Object?, Object?>>()
            .map(PeerInfo.fromMap)
            .toList() ??
        <PeerInfo>[];
    peerList.sort((a, b) => a.displayName.compareTo(b.displayName));

    final warnings = (map['warnings'] as List?)
            ?.whereType<String>()
            .map((w) => _enumFrom(
                GuardWarning.values, w, GuardWarning.noPeers))
            .toSet() ??
        <GuardWarning>{};

    return GuardSnapshot(
      state: _enumFrom(
          GuardState.values, map['state'] as String?, GuardState.disarmed),
      radioProfile: _enumFrom(RadioProfile.values,
          map['radioProfile'] as String?, RadioProfile.calm),
      selfDeviceId: (map['selfDeviceId'] as num?)?.toInt() ?? 0,
      selfName: map['selfName'] as String? ?? '',
      selfStationary: map['selfStationary'] as bool? ?? true,
      selfMotionScore: (map['selfMotionScore'] as num?)?.toInt() ?? 0,
      pickupArmed: map['pickupArmed'] as bool? ?? false,
      pickupArmsInMs: (map['pickupArmsInMs'] as num?)?.toInt() ?? 0,
      pendingRemainingMs: (map['pendingRemainingMs'] as num?)?.toInt() ?? 0,
      alarmReason: map['alarmReason'] == null
          ? null
          : _enumFrom(AlarmReason.values, map['alarmReason'] as String?,
              AlarmReason.theftConsensus),
      alarmSubjectId: (map['alarmSubjectId'] as num?)?.toInt() ?? 0,
      alarmSubjectName: map['alarmSubjectName'] as String?,
      groupAlarmActive: map['groupAlarmActive'] as bool? ?? false,
      stopPending: map['stopPending'] as bool? ?? false,
      stopConfirmed: (map['stopConfirmed'] as num?)?.toInt() ?? 0,
      stopExpected: (map['stopExpected'] as num?)?.toInt() ?? 0,
      peers: peerList,
      box: BoxInfo.fromMap(map['box'] as Map<Object?, Object?>?),
      warnings: warnings,
      diagnostics:
          Diagnostics.fromMap(map['diagnostics'] as Map<Object?, Object?>?),
    );
  }
}

enum DisarmMode { biometricWithPin, pinOnly, confirmTap }

extension DisarmModeX on DisarmMode {
  String get wireName => switch (this) {
        DisarmMode.biometricWithPin => 'BIOMETRIC_WITH_PIN',
        DisarmMode.pinOnly => 'PIN_ONLY',
        DisarmMode.confirmTap => 'CONFIRM_TAP',
      };

  String get label => switch (this) {
        DisarmMode.biometricWithPin => 'Fingerprint, PIN as backup',
        DisarmMode.pinOnly => 'Group PIN only',
        DisarmMode.confirmTap => 'Single tap',
      };

  String get detail => switch (this) {
        DisarmMode.biometricWithPin =>
          'Fastest for you, useless to a thief. Falls back to the group PIN if '
              'the fingerprint reader will not cooperate.',
        DisarmMode.pinOnly =>
          'Works on any phone, and lets any member of the group disarm any '
              'phone using the shared PIN.',
        DisarmMode.confirmTap =>
          'Convenient, but anyone holding the phone can silence it. Only use '
              'this where theft is not really the concern.',
      };
}

enum PowerProfile { maxProtection, balanced, ultraSaver }

extension PowerProfileX on PowerProfile {
  String get wireName => switch (this) {
        PowerProfile.maxProtection => 'MAX_PROTECTION',
        PowerProfile.balanced => 'BALANCED',
        PowerProfile.ultraSaver => 'ULTRA_SAVER',
      };

  String get label => switch (this) {
        PowerProfile.maxProtection => 'Maximum',
        PowerProfile.balanced => 'Balanced',
        PowerProfile.ultraSaver => 'Saver',
      };

  String get detail => switch (this) {
        PowerProfile.maxProtection =>
          'Listens continuously. Reacts fastest, sees every phone in the group '
              'update several times a second, and is the only setting that will '
              'noticeably shorten your day.',
        PowerProfile.balanced =>
          'Listens about a quarter of the time and goes flat out the instant '
              'anything looks wrong. This is the right choice for almost '
              'everyone.',
        PowerProfile.ultraSaver =>
          'Listens about a tenth of the time, with hardware scan batching where '
              'the chipset supports it. Cheapest by a good margin, but the group '
              'list updates every few seconds rather than continuously, and '
              '"arm all" can take a few seconds to reach everybody.',
      };
}

enum AlarmTarget { boxAndPhones, boxOnly, phonesOnly }

extension AlarmTargetX on AlarmTarget {
  String get wireName => switch (this) {
        AlarmTarget.boxAndPhones => 'BOX_AND_PHONES',
        AlarmTarget.boxOnly => 'BOX_ONLY',
        AlarmTarget.phonesOnly => 'PHONES_ONLY',
      };

  String get label => switch (this) {
        AlarmTarget.boxAndPhones => 'Speaker and phones',
        AlarmTarget.boxOnly => 'Speaker only',
        AlarmTarget.phonesOnly => 'Phones only',
      };

  String get detail => switch (this) {
        AlarmTarget.boxAndPhones =>
          'The speaker alerts the group back at the towel while the stolen '
              'phone screams in the thief\'s hand. Recommended.',
        AlarmTarget.boxOnly =>
          'Only the speaker makes noise. Quieter, but the thief hears nothing.',
        AlarmTarget.phonesOnly =>
          'Every phone sounds off and the speaker stays silent. Used '
              'automatically when no speaker is set up.',
      };
}

@immutable
class GuardSettings {
  const GuardSettings({
    required this.hasGroup,
    required this.groupName,
    required this.groupCode,
    required this.selfName,
    required this.deviceId,
    required this.disarmMode,
    required this.hasPin,
    required this.powerProfile,
    required this.alarmTarget,
    required this.sirenVolume,
    required this.vibrateOnAlarm,
    required this.speakReason,
    required this.armed,
    required this.simulationEnabled,
    required this.onboardingComplete,
    required this.txPowerRef,
    required this.boxEnabled,
    required this.boxAddress,
    required this.boxName,
    required this.boxBleAddress,
    required this.peerNames,
    required this.dropThresholdDb,
    required this.sustainMs,
    required this.consensusRatio,
    required this.minObservers,
    required this.lostTimeoutMs,
    required this.pickupGraceMs,
    required this.settleMs,
    required this.motionScoreThreshold,
    required this.alarmOnPickupAlone,
  });

  final bool hasGroup;
  final String groupName;
  final String? groupCode;
  final String selfName;
  final int deviceId;
  final DisarmMode disarmMode;
  final bool hasPin;
  final PowerProfile powerProfile;
  final AlarmTarget alarmTarget;
  final double sirenVolume;
  final bool vibrateOnAlarm;
  final bool speakReason;
  final bool armed;
  final bool simulationEnabled;

  /// Whether the first-run walkthrough has been seen through to the end.
  /// Tracked separately from [hasGroup], because the group is created at step
  /// two of four and the rest of the setup matters just as much.
  final bool onboardingComplete;
  final int txPowerRef;
  final bool boxEnabled;
  final String? boxAddress;
  final String? boxName;
  final String? boxBleAddress;
  final Map<int, String> peerNames;
  final double dropThresholdDb;
  final int sustainMs;

  /// Fraction of the *other* phones that must agree before a siren starts.
  final double consensusRatio;
  final int minObservers;
  final int lostTimeoutMs;
  final int pickupGraceMs;
  final int settleMs;
  final int motionScoreThreshold;
  final bool alarmOnPickupAlone;

  static const empty = GuardSettings(
    hasGroup: false,
    groupName: '',
    groupCode: null,
    selfName: '',
    deviceId: 0,
    disarmMode: DisarmMode.biometricWithPin,
    hasPin: false,
    powerProfile: PowerProfile.balanced,
    alarmTarget: AlarmTarget.boxAndPhones,
    sirenVolume: 1.0,
    vibrateOnAlarm: true,
    speakReason: true,
    armed: false,
    simulationEnabled: false,
    onboardingComplete: false,
    txPowerRef: -59,
    boxEnabled: false,
    boxAddress: null,
    boxName: null,
    boxBleAddress: null,
    peerNames: <int, String>{},
    dropThresholdDb: 11,
    sustainMs: 2000,
    consensusRatio: 1 / 3,
    minObservers: 1,
    lostTimeoutMs: 10000,
    pickupGraceMs: 3000,
    settleMs: 8000,
    motionScoreThreshold: 28,
    alarmOnPickupAlone: true,
  );

  /// Whether the one-off setup of *this phone* is finished.
  ///
  /// Deliberately says nothing about groups. Setting the phone up — who you
  /// are, and what Android has to allow — happens once in the life of the
  /// install; belonging to a group is a state the app moves in and out of all
  /// afternoon. Conflating the two sent someone who left a group back through
  /// a welcome screen asking for a name that was already set.
  bool get firstRunDone => onboardingComplete && selfName.trim().isNotEmpty;

  /// Mirrors `EngineConfig.observersRequiredFor` so the UI can explain the rule
  /// with the group's actual size rather than an abstract percentage.
  int observersRequiredFor(int otherPhones) {
    if (otherPhones <= 0) return 0;
    final byRatio = (otherPhones * consensusRatio - 1e-9).ceil();
    // Raise to the floor first, then clamp - mirroring Kotlin's
    // coerceAtLeast().coerceIn(). A single clamp() would throw whenever
    // minObservers exceeds the number of phones actually available.
    final raised = byRatio > minObservers ? byRatio : minObservers;
    return raised.clamp(1, otherPhones);
  }

  static GuardSettings fromMap(Map<Object?, Object?> map) {
    final names = <int, String>{};
    final raw = map['peerNames'];
    if (raw is Map) {
      raw.forEach((key, value) {
        final id = int.tryParse('$key');
        if (id != null) names[id] = '$value';
      });
    }
    return GuardSettings(
      hasGroup: map['hasGroup'] as bool? ?? false,
      groupName: map['groupName'] as String? ?? '',
      groupCode: map['groupCode'] as String?,
      selfName: map['selfName'] as String? ?? '',
      deviceId: (map['deviceId'] as num?)?.toInt() ?? 0,
      disarmMode: _enumFrom(DisarmMode.values, map['disarmMode'] as String?,
          DisarmMode.biometricWithPin),
      hasPin: map['hasPin'] as bool? ?? false,
      powerProfile: _enumFrom(PowerProfile.values,
          map['powerProfile'] as String?, PowerProfile.balanced),
      alarmTarget: _enumFrom(AlarmTarget.values, map['alarmTarget'] as String?,
          AlarmTarget.boxAndPhones),
      sirenVolume: (map['sirenVolume'] as num?)?.toDouble() ?? 1.0,
      vibrateOnAlarm: map['vibrateOnAlarm'] as bool? ?? true,
      speakReason: map['speakReason'] as bool? ?? true,
      armed: map['armed'] as bool? ?? false,
      simulationEnabled: map['simulationEnabled'] as bool? ?? false,
      onboardingComplete: map['onboardingComplete'] as bool? ?? false,
      txPowerRef: (map['txPowerRef'] as num?)?.toInt() ?? -59,
      boxEnabled: map['boxEnabled'] as bool? ?? false,
      boxAddress: map['boxAddress'] as String?,
      boxName: map['boxName'] as String?,
      boxBleAddress: map['boxBleAddress'] as String?,
      peerNames: names,
      dropThresholdDb: (map['dropThresholdDb'] as num?)?.toDouble() ?? 11,
      sustainMs: (map['sustainMs'] as num?)?.toInt() ?? 2000,
      consensusRatio: (map['consensusRatio'] as num?)?.toDouble() ?? 1 / 3,
      minObservers: (map['minObservers'] as num?)?.toInt() ?? 1,
      lostTimeoutMs: (map['lostTimeoutMs'] as num?)?.toInt() ?? 10000,
      pickupGraceMs: (map['pickupGraceMs'] as num?)?.toInt() ?? 3000,
      settleMs: (map['settleMs'] as num?)?.toInt() ?? 8000,
      motionScoreThreshold: (map['motionScoreThreshold'] as num?)?.toInt() ?? 28,
      alarmOnPickupAlone: map['alarmOnPickupAlone'] as bool? ?? true,
    );
  }
}

/// One entry in the simulator's scenario catalogue.
@immutable
class SimScenario {
  const SimScenario({
    required this.id,
    required this.title,
    required this.description,
    required this.durationMs,
    required this.shouldAlarm,
    required this.budgetMs,
  });

  final String id;
  final String title;
  final String description;
  final int durationMs;
  final bool shouldAlarm;

  /// How quickly this scenario ought to be caught once the incident starts.
  final int budgetMs;

  static SimScenario fromMap(Map<Object?, Object?> map) => SimScenario(
        id: map['id'] as String? ?? '',
        title: map['title'] as String? ?? '',
        description: map['description'] as String? ?? '',
        durationMs: (map['durationMs'] as num?)?.toInt() ?? 0,
        shouldAlarm: map['shouldAlarm'] as bool? ?? false,
        budgetMs: (map['budgetMs'] as num?)?.toInt() ?? 0,
      );
}

@immutable
class BtDevice {
  const BtDevice({required this.name, required this.address, required this.connected, this.rssi});

  final String? name;
  final String address;
  final bool connected;
  final int? rssi;

  String get displayName => name?.trim().isNotEmpty == true ? name!.trim() : address;

  static BtDevice fromMap(Map<Object?, Object?> map) => BtDevice(
        name: map['name'] as String?,
        address: map['address'] as String? ?? '',
        connected: map['connected'] as bool? ?? false,
        rssi: (map['rssi'] as num?)?.toInt(),
      );
}
