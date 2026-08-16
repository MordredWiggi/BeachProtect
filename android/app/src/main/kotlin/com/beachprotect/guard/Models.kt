package com.beachprotect.guard

/** Lifecycle of the guard on *this* device. */
enum class GuardState {
    /** Not protecting anything. Radio still runs slowly so the UI can show the group. */
    DISARMED,

    /** Just armed: learning per-peer RSSI baselines. No alarms yet. */
    CALIBRATING,

    /** Protecting, everything calm. This is where the app spends 99% of its life. */
    ARMED,

    /** Something might be happening. Radios speed up; still no siren. */
    SUSPICIOUS,

    /** *This* phone believes it is being taken. Grace countdown is running. */
    PENDING,

    /** Siren. */
    ALARM,
}

/** How hard the radios are being driven. Drives the whole energy story. */
enum class RadioProfile {
    /** Idle patrol: slow scan, slow advertising. */
    CALM,

    /** Something is off: fast scan so we can confirm or dismiss within seconds. */
    ALERT,

    /** Alarming: fastest advertising so the whole group reacts immediately. */
    CRITICAL,
}

/** User-selectable trade-off between reaction speed and battery drain. */
enum class PowerProfile {
    /** Always-fast scanning. Reacts hardest, costs the most. */
    MAX_PROTECTION,

    /** Slow patrol that escalates the instant anything looks wrong. The default. */
    BALANCED,

    /** Slow patrol plus hardware scan batching where the chipset supports it. */
    ULTRA_SAVER,
}

/** How the owner proves it is really them before the guard stands down. */
enum class DisarmMode {
    /** Fingerprint or face, with the device PIN/pattern as fallback. */
    BIOMETRIC_WITH_PIN,

    /** A group PIN chosen in the app. Works on devices without biometrics. */
    PIN_ONLY,

    /** A single confirm tap. Convenient, but a thief can tap too. */
    CONFIRM_TAP,
}

/** Where a siren is played when the group has a speaker box. */
enum class AlarmTarget {
    /**
     * The default. The box is loud enough to be heard across the beach and
     * alerts the group, while the stolen phone screaming in the thief's hand is
     * what actually makes them drop it.
     */
    BOX_AND_PHONES,

    /** Only the box. Quietest for the phones, but the thief hears nothing. */
    BOX_ONLY,

    /** Every phone, ignoring the box. Also the automatic fallback with no box. */
    PHONES_ONLY,
}

/** Why a siren is going off. Shown verbatim to the user. */
enum class AlarmReason {
    /** Peers agreed a device is moving away while its own sensors report motion. */
    THEFT_CONSENSUS,

    /** A device vanished from the mesh while armed. */
    PEER_LOST,

    /** This phone was picked up and nobody disarmed it in time. */
    PICKUP_UNCONFIRMED,

    /** Another group member's device decided to alarm; we are joining in. */
    RELAYED,

    /** The speaker box left the group. */
    BOX_TAKEN,

    /** Somebody hit the panic button. */
    PANIC,

    /** Raised by the simulator / self test. */
    TEST,
}

/** Non-fatal conditions worth surfacing in the UI without screaming. */
enum class GuardWarning {
    PEER_BATTERY_LOW,
    PEER_LOST_LIKELY_BATTERY,
    BOX_SIGNAL_WEAK,
    BOX_LINK_FLAPPING,
    NO_PEERS,
    BLUETOOTH_OFF,
    ADVERTISING_UNAVAILABLE,
}

/** What the motion subsystem tells the engine. */
sealed class MotionSignal {
    /** Hardware significant-motion trigger fired. Costs ~zero energy to wait on. */
    object SignificantMotion : MotionSignal()

    /** Periodic motion energy update, 0..255, plus the derived stationary verdict. */
    data class Level(val score: Int, val stationary: Boolean) : MotionSignal()
}

/** What the box guard tells the engine. */
sealed class BoxSignal {
    object Connected : BoxSignal()
    object Disconnected : BoxSignal()
    data class Rssi(val rssi: Int) : BoxSignal()
}

/** Observer votes shared over the beacon channel. */
enum class VoteType { SUSPECT, LOST }

/**
 * Tunables for the detector.
 *
 * Defaults are chosen for a towel-sized cluster of phones on open sand. Every
 * one of them is exposed in the app's advanced settings, and every one of them
 * is exercised by the unit tests in `ThreatEngineTest`.
 */
data class EngineConfig(
    /** How far RSSI must fall below baseline to count as "moving away", in dB. */
    val dropThresholdDb: Double = 11.0,

    /**
     * How long the drop must persist before we vote.
     *
     * This is a *second* line of defence, not the main one: occlusion is
     * already rejected outright by the motion gate, because a phone lying on a
     * towel keeps broadcasting "I am stationary" the whole time somebody walks
     * past it. Two seconds is therefore enough, and keeps the peer path inside
     * a few seconds end to end.
     */
    val sustainMs: Long = 2_000,

    /**
     * Fraction of the *other* phones that must independently agree before a
     * siren starts.
     *
     * A ratio rather than a fixed count, because what "enough witnesses" means
     * depends entirely on how big the group is. One agreeing phone out of two
     * is convincing; one out of nine is probably a flaky radio. The requirement
     * is `ceil(otherPhones * ratio)`, floored at [minObservers].
     *
     * At the default third, counting the *other* phones: 1 -> 1, 2 -> 1,
     * 3 -> 1, 4 -> 2, 6 -> 2, 7 -> 3, 10 -> 4.
     */
    val consensusRatio: Double = 1.0 / 3.0,

    /** Absolute floor for the above. Never fewer than this many witnesses. */
    val minObservers: Int = 1,

    /** Silence from an armed peer that counts as "vanished". */
    val lostTimeoutMs: Long = 10_000,

    /**
     * Grace period after this phone is picked up, to let the owner disarm.
     *
     * Short on purpose. The phone starts chirping and shows the disarm screen
     * the instant it is lifted, so this is only the delay before the *group*
     * alarm joins in - and a fingerprint takes well under a second.
     */
    val pickupGraceMs: Long = 3_000,

    /** Baseline learning window right after arming. */
    val calibrationMs: Long = 8_000,

    /** How long this phone must lie still before the pickup detector arms. */
    val settleMs: Long = 8_000,

    /** Motion score above which a device is considered to be moving. */
    val motionScoreThreshold: Int = 28,

    /** Alarm when a phone is picked up even without any RSSI corroboration. */
    val alarmOnPickupAlone: Boolean = true,

    /** How long a received vote stays valid. */
    val voteTtlMs: Long = 8_000,

    /** How long SUSPICIOUS persists with no new evidence before relaxing. */
    val suspicionHoldMs: Long = 12_000,

    /** A2DP can blip; require the box to stay gone this long. */
    val boxDisconnectDebounceMs: Long = 3_000,

    /** Peers reporting at or below this battery level get the benefit of the doubt. */
    val lowBatteryPercent: Int = 8,

    /** Slope, in dB/s, that a sustained drop must be steeper than. */
    val minNegativeSlope: Double = -0.7,
) {
    /**
     * A drop this large is unambiguous, so it only needs half the sustain time.
     * 11 dB is "someone stepped in front"; 20 dB is "the phone left".
     */
    val fastPathDropDb: Double get() = dropThresholdDb * 1.8

    /**
     * How many of [otherPhones] must agree before an alarm is justified.
     *
     * Clamped to what is actually achievable: with two phones in the group
     * there is exactly one possible witness, so demanding more would mean
     * never alarming at all.
     */
    fun observersRequiredFor(otherPhones: Int): Int {
        if (otherPhones <= 0) return 0
        // The epsilon keeps exact thirds honest: 3 * (1/3) lands a hair either
        // side of 1.0 in binary floating point, and without it "a third of
        // three phones" would sometimes round up to two.
        val byRatio = kotlin.math.ceil(otherPhones * consensusRatio - 1e-9).toInt()
        return byRatio.coerceAtLeast(minObservers).coerceIn(1, otherPhones)
    }
}

/** Immutable view of one group member, as this device currently understands it. */
data class PeerSnapshot(
    val deviceId: Int,
    val name: String?,
    val rssi: Double,
    val baseline: Double,
    val dropDb: Double,
    val slopeDbPerSecond: Double,
    val proximity: Proximity,
    val estimatedMetres: Double,
    val battery: Int,
    val armed: Boolean,
    val alarming: Boolean,
    val stationary: Boolean,
    val motionScore: Int,
    val boxGuardian: Boolean,
    val simulated: Boolean,
    val lastSeenMsAgo: Long,
    val suspected: Boolean,
    val votesAgainst: Int,
    val votesRequired: Int,
)

/** Immutable view of the guarded speaker box. */
data class BoxSnapshot(
    val configured: Boolean,
    val name: String?,
    val address: String?,
    val audioLinkConnected: Boolean,
    val bleRssi: Double,
    val bleProximity: Proximity,
    val bleTracked: Boolean,
    val guardedByThisPhone: Boolean,
    val lastSeenMsAgo: Long,
)

/** Everything the UI needs, produced once per engine tick. */
data class GuardSnapshot(
    val state: GuardState,
    val radioProfile: RadioProfile,
    val selfDeviceId: Int,
    val selfName: String,
    val selfStationary: Boolean,
    val selfMotionScore: Int,
    val armedSinceMs: Long,
    val pendingRemainingMs: Long,
    val alarmReason: AlarmReason?,
    val alarmSubjectId: Int,
    val alarmSinceMs: Long,
    val peers: List<PeerSnapshot>,
    val box: BoxSnapshot,
    val warnings: Set<GuardWarning>,
)

/** Everything the engine wants the rest of the app to do. */
interface EngineListener {
    fun onStateChanged(previous: GuardState, current: GuardState)

    /** Start the siren locally and tell the group. */
    fun onAlarmRaised(reason: AlarmReason, subjectId: Int)

    /** Stop the siren locally. */
    fun onAlarmCleared()

    /** Put [eventType] into the outgoing beacon's event slot. */
    fun onBroadcastEvent(eventType: Int, subjectId: Int)

    /** Re-tune the radios. */
    fun onRadioProfileChanged(profile: RadioProfile)

    /** This phone is in its grace period; [msRemaining] until the siren. */
    fun onPendingCountdown(msRemaining: Long)

    fun onWarningsChanged(warnings: Set<GuardWarning>)
}
