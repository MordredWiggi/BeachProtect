package com.beachprotect

import com.beachprotect.ble.Beacon
import com.beachprotect.ble.Protocol
import com.beachprotect.guard.AlarmReason
import com.beachprotect.guard.EngineConfig
import com.beachprotect.guard.EngineListener
import com.beachprotect.guard.GuardState
import com.beachprotect.guard.GuardWarning
import com.beachprotect.guard.MotionSignal
import com.beachprotect.guard.RadioProfile
import com.beachprotect.guard.ThreatEngine

/** Records everything the engine asks the outside world to do. */
class Recorder : EngineListener {
    val alarms = mutableListOf<Pair<AlarmReason, Int>>()
    val states = mutableListOf<GuardState>()
    val broadcasts = mutableListOf<Pair<Int, Int>>()

    /** Group commands this phone was asked to pass on: event type to origin. */
    val relays = mutableListOf<Pair<Int, Int>>()

    /** ...and the command counter each of those carried. */
    val relayCounters = mutableListOf<Int>()

    /** Peer names fully reassembled from beacons: device id to name. */
    val learnedNames = mutableListOf<Pair<Int, String>>()
    var clearedCount = 0
    var profile = RadioProfile.CALM
    var warnings: Set<GuardWarning> = emptySet()
    var lastCountdownMs = -1L

    /** Fake-clock reading at the moment the first alarm was raised. */
    var alarmAtMs = -1L
        private set

    /** Wired up by [Harness] so alarm timings can be asserted on. */
    var clock: () -> Long = { 0L }

    override fun onStateChanged(previous: GuardState, current: GuardState) {
        states += current
    }

    override fun onAlarmRaised(reason: AlarmReason, subjectId: Int) {
        if (alarms.isEmpty()) alarmAtMs = clock()
        alarms += reason to subjectId
    }

    override fun onAlarmCleared() {
        clearedCount++
    }

    override fun onAlarmAnnounced(eventType: Int, subjectId: Int) {
        broadcasts += eventType to subjectId
    }

    override fun onRelayGroupCommand(eventType: Int, originId: Int, counter: Int) {
        relays += eventType to originId
        relayCounters += counter
    }

    override fun onPeerNameLearned(deviceId: Int, name: String) {
        learnedNames += deviceId to name
    }

    override fun onRadioProfileChanged(profile: RadioProfile) {
        this.profile = profile
    }

    override fun onPendingCountdown(msRemaining: Long) {
        lastCountdownMs = msRemaining
    }

    override fun onWarningsChanged(warnings: Set<GuardWarning>) {
        this.warnings = warnings
    }

    val alarmed: Boolean get() = alarms.isNotEmpty()
    val firstReason: AlarmReason? get() = alarms.firstOrNull()?.first
}

const val SELF_ID = 0x0001
const val PEER_A = 0x00A1
const val PEER_B = 0x00B2
const val PEER_C = 0x00C3
const val PEER_D = 0x00D4

/** Drives a [ThreatEngine] against a fake clock. */
class Harness(
    config: EngineConfig = EngineConfig(),
    selfId: Int = SELF_ID,
) {
    val recorder = Recorder()
    val engine = ThreatEngine(selfId, recorder, config)
    var now = 0L
        private set

    init {
        recorder.clock = { now }
    }

    /** Advances the clock in [stepMs] increments, ticking the engine each step. */
    fun advance(totalMs: Long, stepMs: Long = 1_000, onStep: (Long) -> Unit = {}) {
        var elapsed = 0L
        while (elapsed < totalMs) {
            val step = minOf(stepMs, totalMs - elapsed)
            now += step
            elapsed += step
            onStep(now)
            engine.tick(now)
        }
    }

    /** Tells the engine this phone is lying still, which most tests assume. */
    fun settle() {
        engine.onSelfMotion(now, MotionSignal.Level(0, stationary = true))
    }

    fun armAndCalibrate(peerIds: List<Int> = listOf(PEER_A), rssi: Int = -60) {
        settle()
        engine.arm(now)
        // Feed a steady signal through the whole calibration window so every
        // peer ends up with a solid baseline.
        advance(14_000, stepMs = 500) { t ->
            peerIds.forEach { engine.onPeerBeacon(t, rssi, beacon(it)) }
        }
    }

    /**
     * Arms, calibrates, and then leaves the phone alone long enough for the
     * pickup detector to become active.
     */
    fun armAndSettle(peerIds: List<Int> = listOf(PEER_A), rssi: Int = -60) {
        armAndCalibrate(peerIds, rssi)
        advance(12_000, stepMs = 1_000) { t ->
            peerIds.forEach { engine.onPeerBeacon(t, rssi, beacon(it)) }
        }
    }
}

/**
 * Feeds every chunk of [name] in, as the sender would dribble them out.
 *
 * Names are carried two characters at a time in the event slot, reusing the
 * bytes that would otherwise hold telemetry, so this is what a peer introducing
 * itself actually looks like on the wire.
 */
fun sendName(h: Harness, deviceId: Int, name: String, armed: Boolean = true) {
    for (chunk in 0 until Protocol.NAME_CHUNKS) {
        val (subjectField, charField) = Protocol.encodeNameChunk(name, chunk)
        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(
                deviceId,
                armed = armed,
                eventType = Protocol.EVENT_NAME,
                subjectId = subjectField,
                motionScore = charField,
            ),
        )
    }
}

/**
 * A group command as it actually appears on the wire.
 *
 * The counter is the whole point: it identifies one *press*, so a stale relay
 * and a genuine second press of the same button can be told apart. It rides in
 * the motion-score byte, exactly as name chunks ride in the subject field.
 */
fun command(
    from: Int,
    eventType: Int,
    origin: Int = from,
    counter: Int = 1,
    seq: Int = 1,
    armed: Boolean = true,
): Beacon = beacon(
    from,
    armed = armed,
    seq = seq,
    eventType = eventType,
    subjectId = origin,
    motionScore = counter,
)

/** Builds a decoded beacon without going through crypto. */
fun beacon(
    deviceId: Int,
    armed: Boolean = true,
    stationary: Boolean = true,
    motionScore: Int = 0,
    seq: Int = 1,
    eventType: Int = Protocol.EVENT_NONE,
    subjectId: Int = Protocol.DEVICE_ID_NONE,
    battery: Int = 80,
    alarming: Boolean = false,
): Beacon {
    var flags = 0
    if (armed) flags = flags or Protocol.FLAG_ARMED
    if (stationary) flags = flags or Protocol.FLAG_STATIONARY
    if (alarming) flags = flags or Protocol.FLAG_ALARMING
    return Beacon(
        version = Protocol.VERSION,
        groupId = 0x1234_5678,
        deviceId = deviceId,
        flags = flags,
        txPowerRef = -59,
        battery = battery,
        seq = seq,
        eventType = eventType,
        subjectId = subjectId,
        motionScore = motionScore,
    )
}
