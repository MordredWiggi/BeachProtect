package com.beachprotect

import com.beachprotect.ble.Protocol
import com.beachprotect.guard.AlarmReason
import com.beachprotect.guard.EngineConfig
import com.beachprotect.guard.GuardState
import com.beachprotect.guard.GuardWarning
import com.beachprotect.guard.MotionSignal
import com.beachprotect.guard.ThreatEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that matter. Every test here corresponds to a situation that
 * actually happens on a beach.
 */
class ThreatEngineTest {

    // =====================================================================
    // False alarm suppression - the whole reason the accelerometer is fused in
    // =====================================================================

    @Test
    fun `person walking between phones does not alarm`() {
        val h = Harness()
        h.armAndCalibrate()

        // A body blocks the line of sight: 20 dB down for two seconds. The
        // victim phone is lying on the towel and says so in every beacon.
        h.advance(2_000, stepMs = 250) { t ->
            h.engine.onPeerBeacon(t, -80, beacon(PEER_A, stationary = true, motionScore = 0))
        }
        // ...and back again.
        h.advance(6_000, stepMs = 250) { t ->
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A, stationary = true, motionScore = 0))
        }

        assertFalse("occlusion must never alarm", h.recorder.alarmed)
        assertNotEquals(GuardState.ALARM, h.engine.state)
    }

    @Test
    fun `sustained drop from a stationary peer never alarms`() {
        val h = Harness()
        h.armAndCalibrate()

        // Even a permanent 25 dB drop is not theft if the phone insists it is
        // lying still - that is a cool box being put down in the way.
        h.advance(30_000, stepMs = 250) { t ->
            h.engine.onPeerBeacon(t, -85, beacon(PEER_A, stationary = true, motionScore = 0))
        }

        assertFalse(h.recorder.alarmed)
    }

    @Test
    fun `observer that is itself moving casts no votes`() {
        val h = Harness()
        h.armAndCalibrate()

        // This phone is being carried around, so it cannot tell "you walked
        // away" from "I walked away" and must abstain.
        h.engine.onSelfMotion(h.now, MotionSignal.Level(200, stationary = false))
        h.advance(20_000, stepMs = 250) { t ->
            h.engine.onSelfMotion(t, MotionSignal.Level(200, stationary = false))
            h.engine.onPeerBeacon(t, -85, beacon(PEER_A, stationary = false, motionScore = 200))
        }

        assertTrue("a moving observer must not vote", h.engine.activeVotes(h.now).isEmpty())
        // This phone will quite correctly raise its *own* pickup alarm - it is
        // being carried, after all. What must not happen is it accusing PEER_A.
        assertTrue(
            "and must not accuse a peer",
            h.recorder.alarms.none { it.second == PEER_A },
        )
    }

    // =====================================================================
    // Real theft
    // =====================================================================

    @Test
    fun `peer carried away while reporting motion raises the alarm`() {
        val h = Harness()
        h.armAndCalibrate()

        // Somebody picks the phone up and walks: it reports motion, and its
        // signal recedes steadily.
        var rssi = -60.0
        h.advance(12_000, stepMs = 250) { t ->
            rssi -= 0.6
            h.engine.onPeerBeacon(
                t, rssi.toInt(),
                beacon(PEER_A, stationary = false, motionScore = 180),
            )
        }

        assertTrue("a receding, moving phone must alarm", h.recorder.alarmed)
        assertEquals(AlarmReason.THEFT_CONSENSUS, h.recorder.firstReason)
        assertEquals(PEER_A, h.recorder.alarms.first().second)
    }

    @Test
    fun `thief who walks off and then stops is still caught`() {
        val h = Harness()
        h.armAndCalibrate()

        // Fast recession...
        var rssi = -60.0
        h.advance(3_000, stepMs = 250) { t ->
            rssi -= 1.5
            h.engine.onPeerBeacon(t, rssi.toInt(), beacon(PEER_A, stationary = false, motionScore = 180))
        }
        // ...then they stand still with the phone in their pocket. The slope
        // flattens out, but the signal never comes back.
        h.advance(10_000, stepMs = 250) { t ->
            h.engine.onPeerBeacon(t, rssi.toInt(), beacon(PEER_A, stationary = false, motionScore = 90))
        }

        assertTrue(h.recorder.alarmed)
    }

    @Test
    fun `with three phones a single witness is enough`() {
        val h = Harness()
        h.armAndCalibrate(peerIds = listOf(PEER_A, PEER_B))

        // Only this phone sees PEER_A receding. With a third of two other
        // phones required, one witness clears the bar - which is the whole
        // point of scaling the requirement to the size of the group.
        var rssi = -60.0
        h.advance(12_000, stepMs = 250) { t ->
            rssi -= 1.0
            h.engine.onPeerBeacon(t, rssi.toInt(), beacon(PEER_A, stationary = false, motionScore = 180))
            h.engine.onPeerBeacon(t, -60, beacon(PEER_B))
        }

        assertTrue(h.recorder.alarmed)
        assertEquals(PEER_A, h.recorder.alarms.first().second)
    }

    @Test
    fun `in a big group one witness is not enough on its own`() {
        val h = Harness()
        val everyone = listOf(PEER_A, PEER_B, PEER_C, PEER_D)
        h.armAndCalibrate(peerIds = everyone)

        // Five phones: four could witness PEER_A, so a third of them rounds up
        // to two. This phone alone must not be able to start a siren.
        var rssi = -60.0
        h.advance(12_000, stepMs = 250) { t ->
            rssi -= 1.0
            h.engine.onPeerBeacon(t, rssi.toInt(), beacon(PEER_A, stationary = false, motionScore = 180))
            listOf(PEER_B, PEER_C, PEER_D).forEach {
                h.engine.onPeerBeacon(t, -60, beacon(it))
            }
        }

        assertFalse("a lone witness in a big group must not alarm", h.recorder.alarmed)
        assertTrue(
            "but this phone should be voting",
            h.engine.activeVotes(h.now).any { it.second == PEER_A },
        )
    }

    @Test
    fun `second observer agreeing completes the consensus in a big group`() {
        val h = Harness()
        val everyone = listOf(PEER_A, PEER_B, PEER_C, PEER_D)
        h.armAndCalibrate(peerIds = everyone)

        var rssi = -60.0
        h.advance(12_000, stepMs = 250) { t ->
            rssi -= 1.0
            h.engine.onPeerBeacon(t, rssi.toInt(), beacon(PEER_A, stationary = false, motionScore = 180))
            // PEER_B broadcasts its own independent suspicion about PEER_A.
            h.engine.onPeerBeacon(
                t, -60,
                beacon(PEER_B, eventType = Protocol.EVENT_SUSPECT, subjectId = PEER_A),
            )
            listOf(PEER_C, PEER_D).forEach { h.engine.onPeerBeacon(t, -60, beacon(it)) }
        }

        assertTrue(h.recorder.alarmed)
        assertEquals(PEER_A, h.recorder.alarms.first().second)
    }

    @Test
    fun `consensus requirement scales with the size of the group`() {
        val config = EngineConfig()
        // Nobody else around: nothing to corroborate with.
        assertEquals(0, config.observersRequiredFor(0))
        // Two phones - one possible witness, so it has to be enough.
        assertEquals(1, config.observersRequiredFor(1))
        assertEquals(1, config.observersRequiredFor(2))
        assertEquals(1, config.observersRequiredFor(3))
        assertEquals(2, config.observersRequiredFor(4))
        assertEquals(2, config.observersRequiredFor(6))
        assertEquals(3, config.observersRequiredFor(7))
        assertEquals(4, config.observersRequiredFor(10))

        // A stricter group can demand near-unanimity.
        val strict = EngineConfig(consensusRatio = 1.0)
        assertEquals(6, strict.observersRequiredFor(6))

        // And the floor is always honoured where it is achievable.
        val paranoid = EngineConfig(consensusRatio = 0.05, minObservers = 3)
        assertEquals(3, paranoid.observersRequiredFor(9))
        assertEquals(2, paranoid.observersRequiredFor(2))
    }

    // =====================================================================
    // Disappearing phones
    // =====================================================================

    @Test
    fun `armed peer that vanishes raises a lost alarm`() {
        val h = Harness()
        h.armAndCalibrate()

        // Phone switched off or dropped in a bag: total silence.
        h.advance(15_000, stepMs = 1_000)

        assertTrue(h.recorder.alarmed)
        assertEquals(AlarmReason.PEER_LOST, h.recorder.firstReason)
    }

    @Test
    fun `peer that vanishes on a flat battery warns instead of alarming`() {
        val h = Harness()
        h.settle()
        h.engine.arm(h.now)
        h.advance(14_000, stepMs = 500) { t ->
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A, battery = 3))
        }

        h.advance(15_000, stepMs = 1_000)

        assertFalse("a dying battery is not a thief", h.recorder.alarmed)
        assertTrue(h.recorder.warnings.contains(GuardWarning.PEER_LOST_LIKELY_BATTERY))
    }

    @Test
    fun `disarmed peers are not guarded`() {
        val h = Harness()
        h.settle()
        h.engine.arm(h.now)
        h.advance(14_000, stepMs = 500) { t ->
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A, armed = false))
        }

        h.advance(20_000, stepMs = 1_000)

        assertFalse(h.recorder.alarmed)
    }

    // =====================================================================
    // This phone being the victim
    // =====================================================================

    @Test
    fun `pickup after settling starts a grace countdown then alarms`() {
        val h = Harness()
        h.armAndSettle()

        h.engine.onSelfMotion(h.now, MotionSignal.SignificantMotion)
        assertEquals(GuardState.PENDING, h.engine.state)
        assertFalse("grace period must come first", h.recorder.alarmed)

        h.advance(5_000, stepMs = 500) { t ->
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A))
        }

        assertTrue(h.recorder.alarmed)
        assertEquals(AlarmReason.PICKUP_UNCONFIRMED, h.recorder.firstReason)
    }

    /**
     * Regression test.
     *
     * The accelerometer path used to be dead code: the handler cleared the
     * "has been lying still since" marker *before* testing whether the pickup
     * detector was ready, and that test reads the very marker it had just
     * cleared. The only surviving trigger was the hardware significant-motion
     * sensor, which needs many seconds of sustained movement, so a phone being
     * carried off took roughly half a minute to raise anything at all.
     */
    @Test
    fun `a lift detected by the accelerometer alone starts the countdown`() {
        val h = Harness()
        h.armAndSettle()

        h.engine.onSelfMotion(h.now, MotionSignal.Level(160, stationary = false))

        assertEquals(
            "the accelerometer must be able to trigger without the wake-up sensor",
            GuardState.PENDING, h.engine.state,
        )
    }

    @Test
    fun `a lifted phone alarms well inside the detection budget`() {
        val h = Harness()
        h.armAndSettle()
        val liftedAt = h.now

        // The phone keeps moving, as it would in somebody's hand.
        h.engine.onSelfMotion(h.now, MotionSignal.Level(170, stationary = false))
        h.advance(6_000, stepMs = 200) { t ->
            h.engine.onSelfMotion(t, MotionSignal.Level(170, stationary = false))
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A))
        }

        assertTrue("must alarm", h.recorder.alarmed)
        val elapsed = h.recorder.alarmAtMs - liftedAt
        assertTrue(
            "expected an alarm within 4s of the lift, took ${elapsed}ms",
            elapsed <= 4_000,
        )
    }

    @Test
    fun `a shortened grace period really does shorten the wait`() {
        val quick = Harness(EngineConfig(pickupGraceMs = 1_000))
        quick.armAndSettle()
        val quickLift = quick.now
        quick.engine.onSelfMotion(quick.now, MotionSignal.Level(170, stationary = false))
        quick.advance(8_000, stepMs = 200) { t ->
            quick.engine.onSelfMotion(t, MotionSignal.Level(170, stationary = false))
        }

        val slow = Harness(EngineConfig(pickupGraceMs = 6_000))
        slow.armAndSettle()
        val slowLift = slow.now
        slow.engine.onSelfMotion(slow.now, MotionSignal.Level(170, stationary = false))
        slow.advance(12_000, stepMs = 200) { t ->
            slow.engine.onSelfMotion(t, MotionSignal.Level(170, stationary = false))
        }

        assertTrue(quick.recorder.alarmed)
        assertTrue(slow.recorder.alarmed)

        val quickDelay = quick.recorder.alarmAtMs - quickLift
        val slowDelay = slow.recorder.alarmAtMs - slowLift
        assertTrue(
            "grace setting must be honoured: $quickDelay vs $slowDelay",
            slowDelay > quickDelay + 3_000,
        )
    }

    @Test
    fun `a phone still in the owner's hand never trips the pickup detector`() {
        val h = Harness()

        // Armed while being held, and never put down.
        h.engine.onSelfMotion(h.now, MotionSignal.Level(150, stationary = false))
        h.engine.arm(h.now)
        h.advance(20_000, stepMs = 500) { t ->
            h.engine.onSelfMotion(t, MotionSignal.Level(150, stationary = false))
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A))
        }

        assertNotEquals(GuardState.PENDING, h.engine.state)
        assertFalse(h.recorder.alarmed)
    }

    @Test
    fun `picking the phone up again before it has settled does not trigger`() {
        val h = Harness()
        h.engine.onSelfMotion(h.now, MotionSignal.Level(150, stationary = false))
        h.engine.arm(h.now)
        h.advance(12_000, stepMs = 500) { t ->
            h.engine.onSelfMotion(t, MotionSignal.Level(150, stationary = false))
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A))
        }

        // Set down, then picked straight back up - the owner rearranging their
        // towel, not a thief.
        h.engine.onSelfMotion(h.now, MotionSignal.Level(0, stationary = true))
        h.advance(3_000, stepMs = 500) { t -> h.engine.onPeerBeacon(t, -60, beacon(PEER_A)) }
        h.engine.onSelfMotion(h.now, MotionSignal.Level(160, stationary = false))

        assertNotEquals(
            "settleMs must not have elapsed yet",
            GuardState.PENDING, h.engine.state,
        )
        assertFalse(h.recorder.alarmed)
    }

    @Test
    fun `disarming during the grace period cancels the alarm`() {
        val h = Harness()
        h.armAndSettle()

        h.engine.onSelfMotion(h.now, MotionSignal.SignificantMotion)
        assertEquals(GuardState.PENDING, h.engine.state)

        // Well inside the grace period.
        h.advance(1_000, stepMs = 250)
        h.engine.disarm(h.now)
        h.advance(20_000, stepMs = 1_000)

        assertFalse(h.recorder.alarmed)
        assertEquals(GuardState.DISARMED, h.engine.state)
    }

    @Test
    fun `corroboration from the group cuts the grace period short`() {
        val h = Harness()
        h.armAndCalibrate()
        h.advance(25_000, stepMs = 1_000) { t -> h.engine.onPeerBeacon(t, -60, beacon(PEER_A)) }

        h.engine.onSelfMotion(h.now, MotionSignal.SignificantMotion)
        assertEquals(GuardState.PENDING, h.engine.state)

        // PEER_A can already see us receding. No point waiting out the grace.
        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(PEER_A, eventType = Protocol.EVENT_SUSPECT, subjectId = SELF_ID),
        )
        h.advance(1_000, stepMs = 500)

        assertTrue(h.recorder.alarmed)
        assertEquals(AlarmReason.THEFT_CONSENSUS, h.recorder.firstReason)
    }

    @Test
    fun `with alarmOnPickupAlone disabled a put-back-down phone re-arms`() {
        val h = Harness(EngineConfig(alarmOnPickupAlone = false))
        h.armAndCalibrate()
        h.advance(25_000, stepMs = 1_000) { t -> h.engine.onPeerBeacon(t, -60, beacon(PEER_A)) }

        h.engine.onSelfMotion(h.now, MotionSignal.SignificantMotion)
        assertEquals(GuardState.PENDING, h.engine.state)

        // Put back down before the grace expires, nobody else saw anything.
        h.engine.onSelfMotion(h.now, MotionSignal.Level(0, stationary = true))
        h.advance(12_000, stepMs = 1_000) { t -> h.engine.onPeerBeacon(t, -60, beacon(PEER_A)) }

        assertFalse(h.recorder.alarmed)
        assertNotEquals(GuardState.ALARM, h.engine.state)
    }

    // =====================================================================
    // Group control messages
    // =====================================================================

    @Test
    fun `relayed alarm from a peer makes this phone sound off too`() {
        val h = Harness()
        h.armAndCalibrate()

        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(PEER_A, seq = 7, eventType = Protocol.EVENT_ALARM, subjectId = PEER_B),
        )

        assertTrue(h.recorder.alarmed)
        assertEquals(AlarmReason.RELAYED, h.recorder.firstReason)
    }

    @Test
    fun `replayed control packets are rejected`() {
        val h = Harness()
        h.armAndCalibrate()

        // A genuine "everybody disarm" from packing-up time.
        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(PEER_A, seq = 40, eventType = Protocol.EVENT_DISARM_ALL),
        )
        assertEquals(GuardState.DISARMED, h.engine.state)

        // Next afternoon: the group re-arms and an attacker replays the recording.
        h.engine.arm(h.now)
        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(PEER_A, seq = 40, eventType = Protocol.EVENT_DISARM_ALL),
        )

        assertNotEquals(
            "a replayed disarm must not stand the guard down",
            GuardState.DISARMED, h.engine.state,
        )
    }

    @Test
    fun `sequence comparison handles wraparound`() {
        assertTrue(ThreatEngine.isNewerSeq(5, 4))
        assertTrue(ThreatEngine.isNewerSeq(0, 65535))
        assertFalse(ThreatEngine.isNewerSeq(4, 5))
        assertFalse(ThreatEngine.isNewerSeq(65535, 0))
        assertFalse("identical is not newer", ThreatEngine.isNewerSeq(9, 9))
    }

    // =====================================================================
    // Energy behaviour
    // =====================================================================

    @Test
    fun `a calm armed guard stays on the low power radio profile`() {
        val h = Harness()
        h.armAndCalibrate()
        h.advance(30_000, stepMs = 1_000) { t -> h.engine.onPeerBeacon(t, -60, beacon(PEER_A)) }

        assertEquals(com.beachprotect.guard.RadioProfile.CALM, h.recorder.profile)
        assertEquals(GuardState.ARMED, h.engine.state)
    }

    @Test
    fun `people walking past do not escalate the radios`() {
        val h = Harness()
        h.armAndCalibrate()
        repeat(4) {
            h.advance(2_000, stepMs = 250) { t ->
                h.engine.onPeerBeacon(t, -82, beacon(PEER_A, stationary = true))
            }
            h.advance(4_000, stepMs = 250) { t ->
                h.engine.onPeerBeacon(t, -60, beacon(PEER_A, stationary = true))
            }
        }

        assertEquals(
            "occlusion must not hold the scanner at high duty",
            com.beachprotect.guard.RadioProfile.CALM, h.recorder.profile,
        )
    }

    @Test
    fun `suspicion escalates the radio and then relaxes again`() {
        val h = Harness()
        h.armAndCalibrate()

        // A softening but sub-threshold signal from a moving peer: worth
        // listening harder, not worth waking anyone.
        h.advance(3_000, stepMs = 250) { t ->
            h.engine.onPeerBeacon(t, -69, beacon(PEER_A, stationary = false, motionScore = 120))
        }
        assertEquals(com.beachprotect.guard.RadioProfile.ALERT, h.recorder.profile)
        assertFalse("not enough to alarm on", h.recorder.alarmed)

        // Peer settles back down where it belongs.
        h.advance(20_000, stepMs = 500) { t ->
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A, stationary = true))
        }
        assertEquals(com.beachprotect.guard.RadioProfile.CALM, h.recorder.profile)
    }
}
