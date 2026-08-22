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

/**
 * How well we can currently hear one group member.
 *
 * Deliberately three states and not a boolean, and deliberately decided in the
 * engine rather than in the UI. A boolean "is this peer's telemetry current?"
 * is a single hard edge, and a single hard edge on a duty-cycled radio flaps:
 * the calm scanner listens about a quarter of the time, so a run of missed
 * windows adding up to the threshold happens regularly, and every one of them
 * repainted the whole card — green "still, watched" to grey and straight back.
 * That is what the group list flickering "seemingly at random" actually was.
 *
 * [MISSING] is the dead band. It says "we have not heard from this phone for
 * longer than usual", which is worth *annotating* the card with and worth
 * spending fast radio on, but it is not news and must not repaint anything.
 * Only [LOST] — a silence far longer than any duty cycle explains — changes what
 * the card says.
 */
enum class PeerPresence {
    /** Heard recently enough that everything else we know about it is current. */
    PRESENT,

    /** Quiet for longer than usual. Probably a missed scan window; watch harder. */
    MISSING,

    /** Quiet for long enough that silence is the story, not the telemetry. */
    LOST,
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

    /**
     * Silence from an armed peer that counts as "vanished" — a *floor*, not the
     * whole rule.
     *
     * This used to be the entire test, and it was wrong in exactly the way that
     * matters: how long a gap between two beacons is *normal* depends on the
     * scan duty cycle, which the user chooses. At the old calm profile a ten
     * second gap between two scan results was completely ordinary, so an
     * observer voted LOST about a phone lying right next to it — and with two
     * phones in the group one vote is the whole consensus, so it alarmed on the
     * spot. The engine now also requires the silence to be several times the
     * gap it has actually been seeing from that peer, and to persist while the
     * radios are escalated (`ThreatEngine.LOST_CONFIRM_MS`).
     */
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
     * Where an *episode* begins — which is not the same thing as where a vote
     * becomes justified.
     *
     * [sustainMs] used to start counting only once the signal had already
     * fallen the full [dropThresholdDb], so the two costs were paid one after
     * the other: seconds of fading, and only then seconds of confirming. On a
     * phone being walked away with, that was three seconds of pure latency.
     *
     * The episode now starts as soon as a *moving* peer's signal begins to
     * recede, so the confirmation window runs alongside the fade. Voting still
     * requires the full drop, so nothing is voted on that would not have been
     * before — and for a step change, which is what occlusion looks like, both
     * clocks still start on the very same sample.
     */
    val episodeStartDropDb: Double get() = dropThresholdDb * 0.55

    /** An episode ends when the signal comes comfortably back. Hysteresis. */
    val episodeEndDropDb: Double get() = episodeStartDropDb * 0.75

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

    /**
     * How well this peer is currently being heard.
     *
     * Decided here rather than in the UI on purpose, and as three states rather
     * than one boolean — see [PeerPresence] for why the boolean was the bug.
     */
    val presence: PeerPresence,

    /** How long silence has to run before [presence] leaves [PeerPresence.PRESENT]. */
    val staleAfterMs: Long,
    val suspected: Boolean,
    val votesAgainst: Int,
    val votesRequired: Int,
) {
    /**
     * Whether the telemetry above is current enough to reason about.
     *
     * Note that this is *not* the same question as what to show the user: a
     * [PeerPresence.MISSING] peer is not current, but its card must keep saying
     * what it last said, because the alternative is a list that flickers.
     */
    val current: Boolean get() = presence == PeerPresence.PRESENT
}

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

    /**
     * Whether lifting this phone would actually trigger anything yet.
     *
     * The pickup detector only arms once the phone has been left alone for
     * `settleMs`. Without surfacing that, a user who arms the app and
     * immediately waves the phone about sees "Guarding" and nothing else
     * happening, and concludes the app is broken.
     */
    val pickupArmed: Boolean,

    /** Milliseconds until [pickupArmed] becomes true; 0 when it already is. */
    val pickupArmsInMs: Long,
    val pendingRemainingMs: Long,
    val alarmReason: AlarmReason?,
    val alarmSubjectId: Int,
    val alarmSinceMs: Long,

    /**
     * True while *the group* is in an incident, whether or not this phone is
     * still part of it.
     *
     * The alarm controls used to be gated on this phone's own state, which meant
     * the moment somebody disarmed their own handset the buttons for "stop
     * everyone's siren" disappeared and were replaced by "Arm all" — while every
     * other phone in the group carried on screaming with no way left to reach
     * them. Whether the group is shouting is not the same question as whether
     * this phone is.
     */
    val groupAlarmActive: Boolean,

    /**
     * True while this phone is still telling the group to stop, or to stand
     * down, and somebody has not confirmed yet.
     *
     * The banner used to be binary — "the group is still alarming" — and it was
     * driven by a twelve second memory of the last alarm packet heard, refreshed
     * by *any* alarming beacon including echoes of the very incident the user had
     * just called off. So it appeared, aged out, and reappeared on the next
     * straggler, over and over, about an incident that was finished. With
     * acknowledgements the phone knows the actual answer, so it says the actual
     * answer instead.
     */
    val stopPending: Boolean,

    /** How many of [stopExpected] phones have confirmed the outstanding stop. */
    val stopConfirmed: Int,

    /** How many phones this one can currently hear, and is therefore waiting on. */
    val stopExpected: Int,
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

    /**
     * An alarm has just been decided *here*; put it on the air immediately.
     *
     * Deliberately not a queued, repeating message. The alarm event is derived
     * from the guard's live state on every beacon (`BeaconComposer.chooseEvent`),
     * so it leaves the air the moment the alarm does. Queuing it as well — which
     * is what used to happen, through the same path as a group command — meant a
     * phone kept broadcasting "theft!" for the whole repeat window *after* its
     * siren had been silenced, so every other phone heard an incident that no
     * longer existed. This callback only asks for the next beacon to go out now
     * rather than at the next tick.
     */
    fun onAlarmAnnounced(eventType: Int, subjectId: Int)

    /**
     * Pass a group command on to the rest of the group.
     *
     * Group commands travel to phones that are listening a fraction of the time,
     * so the phone that issued one is not a good enough sole source: in a
     * three-phone group the far phone may hear nobody but the middle one. Every
     * phone therefore re-broadcasts a command the first time it sees it, which
     * turns a single burst into an epidemic that reaches everyone in range of
     * *anyone*. It terminates because a copy that is not newer is not passed on.
     *
     * @param originId the device that *issued* the command, carried unchanged
     *        through every relay so all copies are recognisable as one command.
     * @param counter which press of that device's button this is, likewise
     *        carried unchanged. Together with [originId] it is what lets a stale
     *        copy be told from a genuine second press — and what the receiving
     *        phone acknowledges.
     */
    fun onRelayGroupCommand(eventType: Int, originId: Int, counter: Int)

    /**
     * A peer has announced that it is leaving the group, and has been dropped.
     *
     * Worth telling the host about so anything keyed by device id — nicknames,
     * learned names — goes with it. Device ids are derived from the group secret,
     * so a departed id means nothing afterwards and would only be waiting to be
     * attached to the wrong phone later.
     */
    fun onPeerLeft(deviceId: Int)

    /**
     * A peer's display name has been fully reassembled from its beacons.
     *
     * Worth persisting: names arrive two characters at a time over six separate
     * packets, so they are expensive to learn and must not be thrown away
     * because the peer went quiet for five minutes or the service restarted.
     */
    fun onPeerNameLearned(deviceId: Int, name: String)

    /** Re-tune the radios. */
    fun onRadioProfileChanged(profile: RadioProfile)

    /** This phone is in its grace period; [msRemaining] until the siren. */
    fun onPendingCountdown(msRemaining: Long)

    fun onWarningsChanged(warnings: Set<GuardWarning>)
}
