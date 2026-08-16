package com.beachprotect.sim

import com.beachprotect.ble.Beacon
import com.beachprotect.ble.Protocol
import com.beachprotect.guard.AlarmReason
import com.beachprotect.guard.BoxSignal
import com.beachprotect.guard.MotionSignal
import com.beachprotect.guard.ThreatEngine
import kotlin.math.max
import kotlin.random.Random

/**
 * Fabricates virtual group members.
 *
 * The detector's whole value is in the situations it is *supposed* to ignore,
 * and those are miserable to reproduce on a real beach on demand - you cannot
 * ask a stranger to walk between two phones on cue, and you certainly cannot
 * ask someone to steal one. So every scenario below feeds synthetic beacons
 * into the exact same [ThreatEngine] entry points that the real radio uses.
 * Nothing is stubbed or bypassed: the engine cannot tell the difference.
 *
 * ## Rehearsal, not a real incident
 *
 * A test that sets off the actual siren is worse than useless: silencing it
 * disarms the phone, which invalidates every scenario after it, and the alarm
 * event would be broadcast to everyone else's phone as well. So while a
 * scenario is running the service treats alarms as *rehearsals* - it records
 * that the alarm fired and how long it took, plays a short confirmation blip,
 * and stands the guard straight back up. See `GuardService.rehearsing`.
 *
 * Scenarios also finish the moment their verdict is decided rather than running
 * out the clock, so the whole suite takes a couple of minutes instead of ten.
 */
class Simulator(
    private val engine: ThreatEngine,
    private val listener: Listener,
) {

    interface Listener {
        fun onSimulationFinished(
            scenario: Scenario,
            verdict: Verdict,
            timeToAlarmMs: Long,
        )

        fun onSimulationProgress(scenario: Scenario, elapsedMs: Long, note: String)
    }

    enum class Verdict { PASSED, FAILED, INCONCLUSIVE }

    /** Everything a scenario needs to describe itself to the UI. */
    data class ScenarioInfo(
        val id: String,
        val title: String,
        val description: String,
        val durationMs: Long,
        val shouldAlarm: Boolean,
        val budgetMs: Long,
    )

    enum class Scenario(
        val title: String,
        val description: String,
        val durationMs: Long,
        val shouldAlarm: Boolean,
        /** How quickly this scenario ought to be caught, once it starts. */
        val budgetMs: Long,
    ) {
        CALM_GROUP(
            "Calm group",
            "Two phones lying still nearby. Nothing should ever happen.",
            20_000, false, 0,
        ),
        PASSER_BY(
            "People walking past",
            "A phone's signal drops 25 dB for two seconds at a time while it " +
                "keeps reporting that it is stationary. This is the classic " +
                "false alarm, and it must stay silent.",
            32_000, false, 0,
        ),
        THEFT_WALK(
            "Phone carried away",
            "A phone starts reporting motion and its signal fades steadily, " +
                "as if someone picked it up and walked off.",
            30_000, true, 8_000,
        ),
        THEFT_RUN(
            "Phone grabbed and run with",
            "Same as the walk, but three times faster. Should trip the " +
                "large-drop fast path.",
            25_000, true, 5_000,
        ),
        POCKETED(
            "Pocketed then stopped",
            "The signal falls hard, then goes flat because the thief stopped " +
                "moving. The drop must still count.",
            30_000, true, 8_000,
        ),
        THEFT_CONSENSUS(
            "Theft confirmed by a second phone",
            "Three phones. One recedes while another independently votes that " +
                "it is receding too.",
            30_000, true, 8_000,
        ),
        VANISH(
            "Phone switched off",
            "A phone simply stops broadcasting while armed, as if it were " +
                "powered down or dropped in a bag.",
            30_000, true, 14_000,
        ),
        VANISH_LOW_BATTERY(
            "Phone dies at 3 percent",
            "The same disappearance, but the phone was reporting a nearly flat " +
                "battery first. That is not a thief, and must not alarm.",
            30_000, false, 0,
        ),
        SELF_PICKUP(
            "This phone picked up",
            "Simulates the accelerometer noticing this device being lifted " +
                "after it has settled. Should chirp and alarm within seconds.",
            35_000, true, 6_000,
        ),
        BOX_TAKEN(
            "Speaker unplugged",
            "The guarded speaker's audio link drops.",
            25_000, true, 6_000,
        ),
        ;

        val info: ScenarioInfo
            get() = ScenarioInfo(name, title, description, durationMs, shouldAlarm, budgetMs)
    }

    private class VirtualPeer(
        val deviceId: Int,
        var rssi: Double,
        var moving: Boolean = false,
        var battery: Int = 85,
        var alive: Boolean = true,
        var event: Int = Protocol.EVENT_NONE,
        var subject: Int = Protocol.DEVICE_ID_NONE,
    )

    private var scenario: Scenario? = null
    private var startedAt = 0L
    private var incidentAt = -1L
    private var peers = mutableListOf<VirtualPeer>()
    private var alarmAt = -1L
    private var selfMotionSent = false
    private var boxSignalSent = false

    // Fixed seed: two runs of the same scenario should behave identically.
    private val random = Random(0xBEAC4)

    val running: Boolean get() = scenario != null
    val activeScenario: Scenario? get() = scenario

    // =====================================================================

    fun start(scenario: Scenario, now: Long) {
        stop()
        this.scenario = scenario
        startedAt = now
        incidentAt = -1L
        alarmAt = -1L
        selfMotionSent = false
        boxSignalSent = false

        peers = when (scenario) {
            Scenario.THEFT_CONSENSUS -> mutableListOf(
                VirtualPeer(SIM_PEER_A, -58.0),
                VirtualPeer(SIM_PEER_B, -64.0),
            )
            Scenario.SELF_PICKUP, Scenario.BOX_TAKEN -> mutableListOf(
                VirtualPeer(SIM_PEER_A, -58.0),
            )
            else -> mutableListOf(
                VirtualPeer(SIM_PEER_A, -58.0),
                VirtualPeer(SIM_PEER_B, -66.0),
            )
        }
    }

    fun stop() {
        peers.forEach { engine.forgetPeer(it.deviceId) }
        peers.clear()
        scenario = null
        incidentAt = -1L
    }

    /** Called by the service when a rehearsal alarm fires. */
    fun onAlarmObserved(now: Long, reason: AlarmReason) {
        if (scenario == null || alarmAt >= 0) return
        alarmAt = now
    }

    /** Called by the service on every guard tick. */
    fun tick(now: Long) {
        val scenario = this.scenario ?: return
        val elapsed = now - startedAt

        script(scenario, elapsed, now)

        peers.filter { it.alive }.forEach { peer ->
            // A little noise, because a perfectly clean signal would be a
            // dishonest test of a filter whose entire job is noise rejection.
            val noisy = peer.rssi + random.nextDouble(-2.5, 2.5)
            engine.onPeerBeacon(now, noisy.toInt(), toBeacon(peer))
            peer.event = Protocol.EVENT_NONE
            peer.subject = Protocol.DEVICE_ID_NONE
        }

        // Decide as soon as the answer is known, rather than running the clock
        // out. A wrong alarm is just as conclusive as a right one.
        val alarmed = alarmAt >= 0
        if (alarmed) {
            finish(
                scenario,
                if (scenario.shouldAlarm) Verdict.PASSED else Verdict.FAILED,
                if (incidentAt >= 0) alarmAt - incidentAt else 0,
            )
            return
        }

        if (elapsed >= scenario.durationMs) {
            finish(
                scenario,
                if (scenario.shouldAlarm) Verdict.FAILED else Verdict.PASSED,
                -1,
            )
        }
    }

    private fun finish(scenario: Scenario, verdict: Verdict, timeToAlarmMs: Long) {
        listener.onSimulationFinished(scenario, verdict, timeToAlarmMs)
        stop()
    }

    // =====================================================================
    // The scripts
    // =====================================================================

    private fun script(scenario: Scenario, elapsed: Long, now: Long) {
        // Give the engine a moment to leave CALIBRATING before anything
        // interesting happens, otherwise we would only be testing the
        // calibrator. Kept short: with no real peers the engine now leaves
        // calibration in a couple of seconds.
        val t = elapsed - WARMUP_MS
        if (t >= 0 && incidentAt < 0 && scenario != Scenario.CALM_GROUP) {
            incidentAt = now
        }

        when (scenario) {
            Scenario.CALM_GROUP -> {
                // Deliberately boring.
                note(scenario, elapsed, "Everything still")
            }

            Scenario.PASSER_BY -> {
                if (t < 0) return
                // Someone crosses the line of sight every six seconds.
                val phase = t % 6_000
                val blocked = phase in 0..2_000
                peers.getOrNull(0)?.let {
                    it.rssi = if (blocked) -85.0 else -58.0
                    it.moving = false      // the victim is lying perfectly still
                }
                note(scenario, elapsed, if (blocked) "Body blocking the path" else "Clear")
            }

            Scenario.THEFT_WALK -> {
                if (t < 0) return
                // ~2.4 dB/s: a person walking away at a normal pace.
                peers.getOrNull(0)?.let {
                    it.moving = true
                    it.rssi = max(-98.0, -58.0 - (t / 1000.0) * 2.4)
                }
                note(scenario, elapsed, "Walking away")
            }

            Scenario.THEFT_RUN -> {
                if (t < 0) return
                peers.getOrNull(0)?.let {
                    it.moving = true
                    it.rssi = max(-99.0, -58.0 - (t / 1000.0) * 7.0)
                }
                note(scenario, elapsed, "Running away")
            }

            Scenario.POCKETED -> {
                if (t < 0) return
                peers.getOrNull(0)?.let {
                    it.moving = t < 5_000
                    it.rssi = if (t < 4_000) {
                        max(-84.0, -58.0 - (t / 1000.0) * 6.5)
                    } else {
                        -84.0
                    }
                }
                note(scenario, elapsed, if (t < 4_000) "Being pocketed" else "Thief standing still")
            }

            Scenario.THEFT_CONSENSUS -> {
                if (t < 0) return
                peers.getOrNull(0)?.let {
                    it.moving = true
                    it.rssi = max(-98.0, -58.0 - (t / 1000.0) * 2.4)
                }
                // The third phone reaches the same conclusion independently.
                if (t > 3_000) {
                    peers.getOrNull(1)?.let {
                        it.event = Protocol.EVENT_SUSPECT
                        it.subject = SIM_PEER_A
                    }
                }
                note(scenario, elapsed, if (t > 3_000) "Second observer agrees" else "Walking away")
            }

            Scenario.VANISH -> {
                if (t < 0) return
                peers.getOrNull(0)?.alive = false
                note(scenario, elapsed, "Phone off the air")
            }

            Scenario.VANISH_LOW_BATTERY -> {
                peers.getOrNull(0)?.battery = if (t < 0) 6 else 3
                if (t >= 0) {
                    peers.getOrNull(0)?.alive = false
                    note(scenario, elapsed, "Battery died")
                }
            }

            Scenario.SELF_PICKUP -> {
                // The pickup detector only arms once this phone has been left
                // alone for settleMs, so keep telling the engine it is still.
                if (!selfMotionSent) {
                    engine.onSelfMotion(now, MotionSignal.Level(0, stationary = true))
                }
                if (t > SELF_PICKUP_DELAY_MS && !selfMotionSent) {
                    selfMotionSent = true
                    incidentAt = now
                    engine.onSelfMotion(now, MotionSignal.Level(200, stationary = false))
                    note(scenario, elapsed, "Picked up")
                } else if (selfMotionSent) {
                    // Keep it moving so the grace period plays out honestly.
                    engine.onSelfMotion(now, MotionSignal.Level(200, stationary = false))
                    note(scenario, elapsed, "Still being moved")
                } else {
                    note(scenario, elapsed, "Settling")
                }
            }

            Scenario.BOX_TAKEN -> {
                if (t < 0) return
                if (!boxSignalSent) {
                    boxSignalSent = true
                    incidentAt = now
                    engine.onBoxSignal(now, BoxSignal.Disconnected)
                    note(scenario, elapsed, "Speaker link lost")
                }
            }
        }
    }

    private fun note(scenario: Scenario, elapsed: Long, text: String) {
        listener.onSimulationProgress(scenario, elapsed, text)
    }

    private fun toBeacon(peer: VirtualPeer): Beacon {
        var flags = Protocol.FLAG_ARMED or Protocol.FLAG_SIMULATED
        if (!peer.moving) flags = flags or Protocol.FLAG_STATIONARY
        if (peer.battery <= 8) flags = flags or Protocol.FLAG_LOW_BATTERY
        return Beacon(
            version = Protocol.VERSION,
            groupId = 0,
            deviceId = peer.deviceId,
            flags = flags,
            txPowerRef = -59,
            battery = peer.battery,
            seq = ((System.currentTimeMillis() / 500) and 0xFFFF).toInt(),
            eventType = peer.event,
            subjectId = peer.subject,
            motionScore = if (peer.moving) 190 else 2,
        )
    }

    companion object {
        /** Virtual device ids, kept well away from anything a real hash produces. */
        const val SIM_PEER_A = 0xF001
        const val SIM_PEER_B = 0xF002

        /** Let the engine leave CALIBRATING before anything happens. */
        private const val WARMUP_MS = 6_000L

        /** Long enough that the pickup detector has genuinely armed. */
        private const val SELF_PICKUP_DELAY_MS = 10_000L

        fun catalogue(): List<ScenarioInfo> = Scenario.entries.map { it.info }
    }
}
