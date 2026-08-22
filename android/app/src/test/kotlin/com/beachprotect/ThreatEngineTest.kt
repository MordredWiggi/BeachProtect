package com.beachprotect

import com.beachprotect.ble.Protocol
import com.beachprotect.guard.AlarmReason
import com.beachprotect.guard.BoxSignal
import com.beachprotect.guard.EngineConfig
import com.beachprotect.guard.GuardState
import com.beachprotect.guard.GuardWarning
import com.beachprotect.guard.MotionSignal
import com.beachprotect.guard.PeerPresence
import com.beachprotect.guard.RadioProfile
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

    /**
     * The peer path against the reaction-time budget.
     *
     * The fade here is not a hand-picked ramp: it is what the log-distance path
     * loss model says a phone walking away at 1.3 m/s actually looks like, and
     * it is the same curve the in-app simulator now plays. The peer path used
     * to need nearly nine seconds for this, because the filter lagged by three
     * and the confirmation window only started once the drop was already
     * complete.
     */
    @Test
    fun `a peer walking away is caught within the reaction budget`() {
        val h = Harness()
        h.armAndCalibrate(rssi = -58)
        val startedAt = h.now

        h.advance(12_000, stepMs = 1_000) { t ->
            val seconds = (t - startedAt) / 1000.0
            val metres = 1.5 + 1.3 * seconds
            val rssi = -58.0 - 25.0 * kotlin.math.log10(metres / 1.5)
            h.engine.onPeerBeacon(
                t, rssi.toInt(),
                beacon(PEER_A, stationary = false, motionScore = 190),
            )
        }

        assertTrue(h.recorder.alarmed)
        val elapsed = h.recorder.alarmAtMs - startedAt
        assertTrue(
            "expected the group to know inside 5s, took ${elapsed}ms",
            elapsed <= 5_000,
        )
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

    /**
     * Regression test, second round.
     *
     * A lift does not always start with a bang. The readiness test reads "has
     * been lying still since", and the very first sample that reports movement
     * has to clear it - so a lift that began below the decisive threshold (a
     * careful hand, a slow slide off the towel) disarmed the detector for good:
     * every stronger sample that followed found a phone that had not been lying
     * still, and did nothing at all. The phone could then be carried away in
     * silence, and all the owner ever saw was the app asking them to put it
     * down so it could start guarding again.
     */
    @Test
    fun `a lift that starts gently still trips the pickup detector`() {
        val h = Harness()
        h.armAndSettle()

        // Below motionScoreThreshold: not enough on its own.
        h.engine.onSelfMotion(h.now, MotionSignal.Level(18, stationary = false))
        assertNotEquals(GuardState.PENDING, h.engine.state)

        // ...and now it is properly in the air.
        h.advance(400, stepMs = 200) { t ->
            h.engine.onSelfMotion(t, MotionSignal.Level(140, stationary = false))
        }

        assertEquals(
            "a gentle first sample must not disarm the detector",
            GuardState.PENDING, h.engine.state,
        )
    }

    @Test
    fun `movement that never stops counts as a pickup even without a hard sample`() {
        val h = Harness()
        h.armAndSettle()

        // Nothing decisive, but it simply does not stop - a phone being slid
        // off a towel into a bag rather than snatched.
        h.advance(3_500, stepMs = 200) { t ->
            h.engine.onSelfMotion(t, MotionSignal.Level(20, stationary = false))
        }
        assertEquals(
            "sustained handling should start the countdown",
            GuardState.PENDING, h.engine.state,
        )

        h.advance(4_000, stepMs = 200) { t ->
            h.engine.onSelfMotion(t, MotionSignal.Level(20, stationary = false))
        }

        assertTrue(h.recorder.alarmed)
        assertEquals(AlarmReason.PICKUP_UNCONFIRMED, h.recorder.firstReason)
    }

    @Test
    fun `a knock to the towel does not trip the pickup detector`() {
        val h = Harness()
        h.armAndSettle()

        // Somebody drops a bag next to the phone: a short shove, then the
        // motion monitor's settling delay keeps saying "moving" for a moment.
        h.advance(2_000, stepMs = 200) { t ->
            h.engine.onSelfMotion(t, MotionSignal.Level(20, stationary = false))
        }
        h.advance(6_000, stepMs = 200) { t ->
            h.engine.onSelfMotion(t, MotionSignal.Level(0, stationary = true))
        }

        assertFalse("a knock is not a theft", h.recorder.alarmed)
        assertNotEquals(GuardState.PENDING, h.engine.state)
    }

    /**
     * The lone-phone case.
     *
     * With nobody else in the group there is no radio evidence to be had at
     * all, so the accelerometer is the entire detector. It still has to work:
     * plenty of people will install this, arm it, and only later persuade a
     * friend to join.
     */
    @Test
    fun `a single phone with no peers still alarms when lifted`() {
        val h = Harness()
        h.settle()
        h.engine.arm(h.now)

        // Nothing to calibrate against, so it should not sit in CALIBRATING.
        h.advance(4_000, stepMs = 500)
        assertEquals(GuardState.ARMED, h.engine.state)

        // Leave it alone long enough for the pickup detector to arm.
        h.advance(10_000, stepMs = 500)

        h.engine.onSelfMotion(h.now, MotionSignal.Level(170, stationary = false))
        assertEquals(GuardState.PENDING, h.engine.state)

        h.advance(5_000, stepMs = 250) { t ->
            h.engine.onSelfMotion(t, MotionSignal.Level(170, stationary = false))
        }

        assertTrue("a lone phone must still defend itself", h.recorder.alarmed)
        assertEquals(AlarmReason.PICKUP_UNCONFIRMED, h.recorder.firstReason)
    }

    @Test
    fun `a lone phone reports when its pickup detector is ready`() {
        val h = Harness()
        h.settle()
        h.engine.arm(h.now)
        h.advance(4_000, stepMs = 500)

        assertFalse(
            "should not claim to be ready during the settle window",
            h.engine.snapshot(h.now).pickupArmed,
        )

        h.advance(10_000, stepMs = 500)
        assertTrue(
            "should be ready once it has lain still",
            h.engine.snapshot(h.now).pickupArmed,
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
    // The speaker
    // =====================================================================

    @Test
    fun `a guarded speaker dropping its link raises the alarm`() {
        val h = Harness()
        h.engine.configureBox(
            configured = true,
            name = "Test speaker",
            address = "F0:00:00:00:BE:EF",
            guardedHere = true,
        )
        h.armAndCalibrate()

        h.engine.onBoxSignal(h.now, BoxSignal.Connected)
        h.advance(2_000, stepMs = 500) { t -> h.engine.onPeerBeacon(t, -60, beacon(PEER_A)) }
        h.engine.onBoxSignal(h.now, BoxSignal.Disconnected)
        h.advance(5_000, stepMs = 500) { t -> h.engine.onPeerBeacon(t, -60, beacon(PEER_A)) }

        assertTrue(h.recorder.alarmed)
        assertEquals(AlarmReason.BOX_TAKEN, h.recorder.firstReason)
    }

    @Test
    fun `a speaker that was never connected cannot raise a disconnect alarm`() {
        val h = Harness()
        h.engine.configureBox(
            configured = true,
            name = "Test speaker",
            address = "F0:00:00:00:BE:EF",
            guardedHere = true,
        )
        h.armAndCalibrate()

        // No Connected first, so there is no link to lose. Firing here would
        // mean alarming about a speaker nobody ever switched on.
        h.engine.onBoxSignal(h.now, BoxSignal.Disconnected)
        h.advance(8_000, stepMs = 500) { t -> h.engine.onPeerBeacon(t, -60, beacon(PEER_A)) }

        assertFalse(h.recorder.alarmed)
    }

    @Test
    fun `pointing at a different speaker clears a previous box alarm`() {
        val h = Harness()
        h.engine.configureBox(true, "One", "AA:AA:AA:AA:AA:AA", guardedHere = true)
        h.armAndCalibrate()
        h.engine.onBoxSignal(h.now, BoxSignal.Connected)
        h.advance(1_000, stepMs = 500)
        h.engine.onBoxSignal(h.now, BoxSignal.Disconnected)
        h.advance(5_000, stepMs = 500)
        assertTrue(h.recorder.alarmed)

        h.engine.clearAlarm(h.now)
        // A different speaker must be a clean slate, not "already alarmed".
        h.engine.configureBox(true, "Two", "BB:BB:BB:BB:BB:BB", guardedHere = true)
        h.advance(10_000, stepMs = 500)
        h.engine.onBoxSignal(h.now, BoxSignal.Connected)
        h.advance(1_000, stepMs = 500)
        h.engine.onBoxSignal(h.now, BoxSignal.Disconnected)
        h.advance(5_000, stepMs = 500)

        assertEquals(2, h.recorder.alarms.size)
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
            command(PEER_A, Protocol.EVENT_DISARM_ALL, counter = 9, seq = 40),
        )
        assertEquals(GuardState.DISARMED, h.engine.state)

        // Next afternoon: the group re-arms and an attacker replays the recording.
        h.engine.arm(h.now)
        h.engine.onPeerBeacon(
            h.now, -60,
            command(PEER_A, Protocol.EVENT_DISARM_ALL, counter = 9, seq = 40),
        )

        assertNotEquals(
            "a replayed disarm must not stand the guard down",
            GuardState.DISARMED, h.engine.state,
        )
    }

    /**
     * The two-phone deadlock, in the smallest form that reproduces it.
     *
     * Both phones alarmed, and every alarming phone repeated `EVENT_ALARM`
     * continuously. Silencing one made it fall quiet for a fraction of a
     * second, hear the other still repeating, and start again - which the other
     * then heard. Neither phone could be stood down at all: closing the app did
     * not help, because an alarm in progress defers the shutdown, and the only
     * way out of it was for everybody to leave the group.
     */
    @Test
    fun `a cleared alarm is not restarted by the phone still alarming`() {
        val h = Harness()
        h.armAndCalibrate()

        var seq = 10
        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(PEER_A, seq = seq, eventType = Protocol.EVENT_ALARM, subjectId = PEER_B),
        )
        assertEquals(GuardState.ALARM, h.engine.state)

        // The owner silences this phone. The other one has not heard yet and
        // keeps shouting about the same incident.
        h.engine.clearAlarm(h.now)
        assertNotEquals(GuardState.ALARM, h.engine.state)

        h.advance(6_000, stepMs = 500) { t ->
            h.engine.onPeerBeacon(
                t, -60,
                beacon(
                    PEER_A, seq = ++seq,
                    eventType = Protocol.EVENT_ALARM, subjectId = PEER_B,
                ),
            )
        }

        assertNotEquals(
            "packets from the incident just called off must not restart it",
            GuardState.ALARM, h.engine.state,
        )
        assertEquals("and the siren must have run exactly once", 1, h.recorder.alarms.size)
    }

    /**
     * Switching your own phone off guard must not stop it hearing that
     * somebody else's is being taken. Only a group-wide stop ignores the
     * incident's remaining packets, because only a group-wide stop is about
     * an incident.
     */
    @Test
    fun `an ordinary local disarm does not deafen this phone to the group`() {
        val h = Harness()
        h.armAndCalibrate()

        h.engine.disarm(h.now)
        h.advance(1_000)

        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(PEER_A, seq = 50, eventType = Protocol.EVENT_ALARM, subjectId = PEER_B),
        )
        assertEquals(GuardState.ALARM, h.engine.state)
    }

    /** ...but the silence is a bounded window, not a permanent deafness. */
    @Test
    fun `a genuinely new alarm is heard once the echo window has passed`() {
        val h = Harness()
        h.armAndCalibrate()

        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(PEER_A, seq = 10, eventType = Protocol.EVENT_ALARM, subjectId = PEER_B),
        )
        h.engine.clearAlarm(h.now)
        h.advance(ThreatEngine.ALARM_ECHO_WINDOW_MS + 2_000, stepMs = 1_000) { t ->
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A))
        }

        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(PEER_A, seq = 200, eventType = Protocol.EVENT_ALARM, subjectId = PEER_B),
        )
        assertEquals(GuardState.ALARM, h.engine.state)
    }

    /**
     * Exactly one phone speaks for each incident.
     *
     * A phone joining in makes just as much noise, but putting the alarm back
     * on the air is what gave every incident as many sources as there were
     * phones - and every one of them had to be silenced separately before any
     * of them would stay silenced.
     */
    @Test
    fun `joining somebody else's alarm does not put it back on the air`() {
        val h = Harness()
        h.armAndCalibrate()

        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(PEER_A, seq = 7, eventType = Protocol.EVENT_ALARM, subjectId = PEER_B),
        )

        assertTrue("it must still sound off", h.recorder.alarmed)
        assertEquals(AlarmReason.RELAYED, h.recorder.firstReason)
        assertFalse("but it must not become a second source", h.engine.alarmOriginatedHere)
        assertTrue(
            "and must broadcast nothing at all",
            h.recorder.broadcasts.none { it.first == Protocol.EVENT_ALARM },
        )
    }

    @Test
    fun `a phone that decides on the alarm itself does broadcast it`() {
        val h = Harness()
        h.armAndSettle()

        h.engine.onSelfMotion(h.now, MotionSignal.Level(200, stationary = false))
        h.advance(6_000, stepMs = 500) { t ->
            h.engine.onSelfMotion(t, MotionSignal.Level(200, stationary = false))
        }

        assertTrue(h.recorder.alarmed)
        assertTrue(h.engine.alarmOriginatedHere)
        assertTrue(
            h.recorder.broadcasts.any { it.first == Protocol.EVENT_ALARM },
        )
    }

    @Test
    fun `disarm all stands the phone down and keeps it down`() {
        val h = Harness()
        h.armAndCalibrate()

        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(PEER_A, seq = 10, eventType = Protocol.EVENT_ALARM, subjectId = PEER_B),
        )
        assertEquals(GuardState.ALARM, h.engine.state)

        h.engine.onPeerBeacon(
            h.now, -60,
            command(PEER_A, Protocol.EVENT_DISARM_ALL, counter = 4, seq = 11),
        )
        assertEquals(GuardState.DISARMED, h.engine.state)

        // The phone that has not caught up yet is still shouting.
        var seq = 11
        h.advance(6_000, stepMs = 500) { t ->
            h.engine.onPeerBeacon(
                t, -60,
                beacon(
                    PEER_A, seq = ++seq,
                    eventType = Protocol.EVENT_ALARM, subjectId = PEER_B,
                ),
            )
        }
        assertEquals(GuardState.DISARMED, h.engine.state)
    }

    /**
     * A thumb on the panic button is news, never an echo - and since a relaying
     * phone no longer repeats alarms, nothing else can put this event on the
     * air. It is therefore deliberately exempt from the echo window.
     */
    @Test
    fun `a panic still gets through immediately after a group disarm`() {
        val h = Harness()
        h.armAndCalibrate()

        h.engine.onPeerBeacon(
            h.now, -60,
            command(PEER_A, Protocol.EVENT_DISARM_ALL, counter = 2, seq = 30),
        )
        assertEquals(GuardState.DISARMED, h.engine.state)

        h.advance(1_000)
        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(PEER_A, seq = 31, eventType = Protocol.EVENT_PANIC, subjectId = PEER_A),
        )

        assertEquals(GuardState.ALARM, h.engine.state)
        assertEquals(AlarmReason.PANIC, h.recorder.alarms.last().first)
    }

    /**
     * Rejoining a group must not look like a replay attack.
     *
     * The replay reference used to be updated only when a control event
     * arrived, so it could sit thousands of packets behind the sender's real
     * position - far enough for the wraparound comparison to read a genuinely
     * newer number as older, and throw the command away.
     */
    @Test
    fun `a sender that has run far ahead is still obeyed`() {
        val h = Harness()
        h.armAndCalibrate()

        h.engine.onPeerBeacon(
            h.now, -60,
            command(PEER_A, Protocol.EVENT_ALARM_CLEAR, counter = 1, seq = 100),
        )

        // Hours of ordinary beacons: twenty thousand sequence numbers, which is
        // past the half range where the wraparound comparison flips over and
        // starts reading newer as older.
        var seq = 100
        h.advance(20_000, stepMs = 1_000) { t ->
            seq = (seq + 2_000) and 0xFFFF
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A, seq = seq))
        }

        seq = (seq + 1) and 0xFFFF
        h.engine.onPeerBeacon(
            h.now, -60,
            command(PEER_A, Protocol.EVENT_DISARM_ALL, counter = 2, seq = seq),
        )
        assertEquals(GuardState.DISARMED, h.engine.state)
    }

    // =====================================================================
    // Group commands actually reaching everybody
    // =====================================================================

    /**
     * A command has to survive a listener that is awake a fraction of the time,
     * with nothing acknowledging it. The issuer repeating itself is not enough:
     * in a three-phone group the far phone may be in range of nobody but the
     * middle one. Every phone therefore passes a command on the first time it
     * sees it.
     */
    @Test
    fun `a group command is passed on to the rest of the group`() {
        val h = Harness()
        h.settle()

        // PEER_B decided this; we heard it from PEER_A, who was relaying.
        h.engine.onPeerBeacon(
            h.now, -60,
            command(PEER_A, Protocol.EVENT_ARM_ALL, origin = PEER_B, counter = 3, seq = 5,
                armed = false),
        )

        assertEquals(GuardState.CALIBRATING, h.engine.state)
        assertEquals(
            "the origin has to travel unchanged, or the copies stop being one command",
            listOf(Protocol.EVENT_ARM_ALL to PEER_B), h.recorder.relays,
        )

        // A third copy, from yet another phone. Passing it on again would be a
        // flood that never terminates.
        h.engine.onPeerBeacon(
            h.now, -60,
            command(PEER_C, Protocol.EVENT_ARM_ALL, origin = PEER_B, counter = 3, seq = 6),
        )
        assertEquals("relayed exactly once", 1, h.recorder.relays.size)
    }

    @Test
    fun `the phone that issued a command ignores it coming back`() {
        val h = Harness()
        h.armAndCalibrate()

        // This phone told everybody to stand down, and stood itself down...
        h.engine.noteOwnGroupCommand(Protocol.EVENT_DISARM_ALL, 6, h.now)
        h.engine.disarm(h.now, groupWide = true)
        // ...and then thought better of it.
        h.engine.arm(h.now)

        // Meanwhile the group is still passing the command around.
        var seq = 50
        h.advance(20_000, stepMs = 1_000) { t ->
            h.engine.onPeerBeacon(
                t, -60,
                command(
                    PEER_A, Protocol.EVENT_DISARM_ALL,
                    origin = SELF_ID, counter = 6, seq = ++seq,
                ),
            )
        }

        assertNotEquals(
            "an echo of your own command is not a second decision",
            GuardState.DISARMED, h.engine.state,
        )
        assertTrue(h.recorder.relays.isEmpty())
    }

    /**
     * The cost of relaying, if it were not paid attention to: a command stays in
     * the air far longer than the issuer's own burst, so re-applying every copy
     * would quietly undo a phone the user has just armed again.
     */
    @Test
    fun `a relay arriving after the user re-armed does not stand them down again`() {
        val h = Harness()
        h.armAndCalibrate()

        h.engine.onPeerBeacon(
            h.now, -60,
            command(PEER_A, Protocol.EVENT_DISARM_ALL, counter = 5, seq = 20),
        )
        assertEquals(GuardState.DISARMED, h.engine.state)

        h.engine.arm(h.now)

        var seq = 20
        h.advance(20_000, stepMs = 1_000) { t ->
            h.engine.onPeerBeacon(
                t, -60,
                command(
                    PEER_B, Protocol.EVENT_DISARM_ALL,
                    origin = PEER_A, counter = 5, seq = ++seq,
                ),
            )
        }

        assertNotEquals(GuardState.DISARMED, h.engine.state)
    }

    /**
     * Regression test for the sequence anybody testing the app performs within
     * the first minute: arm everyone, watch it work, stand everyone down, arm
     * everyone again.
     *
     * Remembering commands by *type* for a while — which is what the first
     * version of the relay did, to stop a circulating copy re-applying itself —
     * made the third press a no-op on every phone that had heard the first. The
     * counter is what separates "the same message again" from "the same button
     * again".
     */
    @Test
    fun `pressing the same button again is obeyed`() {
        val h = Harness()
        h.settle()

        h.engine.onPeerBeacon(
            h.now, -60, command(PEER_A, Protocol.EVENT_ARM_ALL, counter = 1, seq = 1),
        )
        assertNotEquals(GuardState.DISARMED, h.engine.state)

        h.engine.onPeerBeacon(
            h.now, -60, command(PEER_A, Protocol.EVENT_DISARM_ALL, counter = 2, seq = 2),
        )
        assertEquals(GuardState.DISARMED, h.engine.state)

        h.engine.onPeerBeacon(
            h.now, -60, command(PEER_A, Protocol.EVENT_ARM_ALL, counter = 3, seq = 3),
        )
        assertNotEquals(
            "the third press is a new decision, not an echo of the first",
            GuardState.DISARMED, h.engine.state,
        )
    }

    /**
     * ...and the other half of the same coin: a copy of an *older* press, which
     * is what a relay still circulating half a minute later is, must not undo
     * what has happened since.
     */
    @Test
    fun `a stale copy of an older command is ignored`() {
        val h = Harness()
        h.settle()

        h.engine.onPeerBeacon(
            h.now, -60, command(PEER_A, Protocol.EVENT_DISARM_ALL, counter = 7, seq = 1),
        )
        h.engine.onPeerBeacon(
            h.now, -60, command(PEER_A, Protocol.EVENT_ARM_ALL, counter = 8, seq = 2),
        )
        assertNotEquals(GuardState.DISARMED, h.engine.state)

        // A phone that only now got round to passing the older one on.
        h.engine.onPeerBeacon(
            h.now, -60,
            command(PEER_B, Protocol.EVENT_DISARM_ALL, origin = PEER_A, counter = 7, seq = 3),
        )

        assertNotEquals(GuardState.DISARMED, h.engine.state)
        assertEquals(
            "and it must not be passed on a second time either",
            1, h.recorder.relays.count { it.first == Protocol.EVENT_DISARM_ALL },
        )
    }

    @Test
    fun `command counters wrap around`() {
        assertTrue(Protocol.isNewerCommand(5, 4))
        assertTrue("the counter is one byte", Protocol.isNewerCommand(0, 255))
        assertFalse(Protocol.isNewerCommand(4, 5))
        assertFalse(Protocol.isNewerCommand(255, 0))
        assertFalse("the same press is not a new one", Protocol.isNewerCommand(9, 9))
    }

    // =====================================================================
    // Peer presence at a low scan duty cycle
    // =====================================================================

    /**
     * Regression test for the noisiest failure of the whole field test.
     *
     * How long a gap between two beacons is *normal* depends on the scan duty
     * cycle the user chose, and the loss test used to be a flat ten seconds. On
     * the saver profile a twelve second gap is completely ordinary - so an
     * observer voted LOST about a phone lying on the same towel, and with two
     * phones in the group one vote is the entire consensus. The result was a
     * siren, followed by a group list flickering between arbitrary states.
     */
    @Test
    fun `an ordinary gap between scan results is not a vanished phone`() {
        val h = Harness()
        h.settle()
        h.engine.arm(h.now)

        h.advance(120_000, stepMs = 1_000) { t ->
            if ((t / 1_000) % 12 == 0L) h.engine.onPeerBeacon(t, -60, beacon(PEER_A))
        }

        assertFalse("a sparse radio is not a theft", h.recorder.alarmed)
        assertTrue(
            "and the peer is still there",
            h.engine.snapshot(h.now).peers.single().armed,
        )
    }

    @Test
    fun `a peer missed for a moment is never voted lost`() {
        val h = Harness()
        h.armAndCalibrate()

        // Silence for a shade over the timeout: enough to be noticed and to
        // escalate the radios, not enough to conclude anything.
        h.advance(11_000, stepMs = 500)
        assertFalse("noticing silence is not the same as concluding theft", h.recorder.alarmed)

        // ...and there it is again, which is what escalating was for.
        h.advance(10_000, stepMs = 500) { t -> h.engine.onPeerBeacon(t, -60, beacon(PEER_A)) }

        assertFalse(h.recorder.alarmed)
        assertTrue(h.engine.activeVotes(h.now).isEmpty())
    }

    /**
     * An accusation has to be withdrawn the moment it is disproved.
     *
     * A LOST vote used to survive the peer's return for the whole eight second
     * vote lifetime, because the code path that noticed the peer was back
     * skipped straight past the retraction whenever its baseline had been reset.
     * A second observer agreeing with a withdrawn accusation is a siren about a
     * phone lying on the towel.
     */
    @Test
    fun `a lost vote is withdrawn as soon as the peer is heard again`() {
        val h = Harness()
        // Enough phones that one voice is not consensus, so the accusation can
        // be inspected instead of instantly becoming a siren.
        h.armAndCalibrate(peerIds = listOf(PEER_B, PEER_C, PEER_D))
        val others = listOf(PEER_B, PEER_C, PEER_D)

        // PEER_A turns up late and is never lying still, so it never earns a
        // baseline - which is precisely the state the retraction was skipped in.
        h.advance(2_000, stepMs = 500) { t ->
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A, stationary = false, motionScore = 5))
            others.forEach { h.engine.onPeerBeacon(t, -60, beacon(it)) }
        }

        // ...and then goes quiet.
        h.advance(16_000, stepMs = 500) { t ->
            others.forEach { h.engine.onPeerBeacon(t, -60, beacon(it)) }
        }
        assertTrue(
            "it should have been reported missing by now",
            h.engine.activeVotes(h.now)
                .any { it.first == Protocol.EVENT_LOST && it.second == PEER_A },
        )
        assertFalse("but one voice is not consensus here", h.recorder.alarmed)

        // It was behind a cool box, not in a thief's pocket.
        h.advance(2_000, stepMs = 500) { t ->
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A, stationary = false, motionScore = 5))
            others.forEach { h.engine.onPeerBeacon(t, -60, beacon(it)) }
        }

        assertTrue(
            "the accusation has to be taken back the moment it is disproved",
            h.engine.activeVotes(h.now).none { it.second == PEER_A },
        )
    }

    @Test
    fun `a peer that has gone quiet is reported as unheard rather than as stale news`() {
        val h = Harness()
        h.armAndCalibrate()
        assertEquals(
            PeerPresence.PRESENT, h.engine.snapshot(h.now).peers.single().presence,
        )

        h.advance(60_000, stepMs = 1_000)
        assertEquals(
            PeerPresence.LOST, h.engine.snapshot(h.now).peers.single().presence,
        )
    }

    /**
     * Regression test, and the one that pins down why the group list flickered.
     *
     * A duty-cycled scanner drops packets in *runs*, not independently: a phone
     * behind a body, or a radio having a bad minute, produces several long gaps
     * one after another. With a single threshold evaluated fresh every tick,
     * every one of those runs repainted the card — green "still, watched" to grey
     * and straight back on the next packet — which from the outside is a group
     * list changing at random between two states the phone was never actually in.
     *
     * What the user is shown must therefore change on a *blunter* rule than the
     * one the detector votes on. A gap long enough to notice moves the peer to
     * MISSING, which is worth listening harder about and worth annotating the
     * card with, but changes nothing about what it says.
     */
    @Test
    fun `a run of missed scan windows never repaints a peer`() {
        val h = Harness()
        h.armAndCalibrate()

        val states = mutableListOf<PeerPresence>()
        // Four separate silences of thirteen seconds - comfortably past the ten
        // second threshold - each ended by a single beacon getting through.
        repeat(4) {
            h.advance(13_000, stepMs = 1_000) {
                states += h.engine.snapshot(h.now).peers.single().presence
            }
            h.engine.onPeerBeacon(h.now, -60, beacon(PEER_A))
            states += h.engine.snapshot(h.now).peers.single().presence
        }

        assertFalse(
            "silence a duty cycle explains must never reach the state that repaints the card",
            states.contains(PeerPresence.LOST),
        )
    }

    // =====================================================================
    // Getting out of a group alarm
    // =====================================================================

    /**
     * Regression test.
     *
     * The phone that *decided* on an incident keeps `EVENT_ALARM` in every beacon
     * for as long as it is alarming, which can be minutes. A fixed echo window
     * therefore only postponed the problem: the phone whose owner had silenced it
     * waited the window out, heard the originator still shouting about the very
     * same incident, and started screaming again.
     */
    @Test
    fun `an incident the user has silenced cannot restart itself later`() {
        val h = Harness()
        h.armAndCalibrate()

        var seq = 10
        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(PEER_A, seq = seq, eventType = Protocol.EVENT_ALARM, subjectId = PEER_B),
        )
        assertEquals(GuardState.ALARM, h.engine.state)

        h.engine.disarm(h.now)
        h.advance(60_000, stepMs = 500) { t ->
            h.engine.onPeerBeacon(
                t, -60,
                beacon(
                    PEER_A, seq = ++seq, alarming = true,
                    eventType = Protocol.EVENT_ALARM, subjectId = PEER_B,
                ),
            )
        }

        assertNotEquals(GuardState.ALARM, h.engine.state)
        assertEquals("the siren must have run exactly once", 1, h.recorder.alarms.size)
    }

    /**
     * ...and the other half of that: this phone being out of the incident must
     * not hide the fact that the rest of the group is still in it. Losing that
     * is how somebody ended up with a screaming towel and an "Arm all" button.
     */
    @Test
    fun `a phone that has stood itself down still knows the group is alarming`() {
        val h = Harness()
        h.armAndCalibrate()

        var seq = 10
        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(PEER_A, seq = seq, eventType = Protocol.EVENT_ALARM, subjectId = PEER_B),
        )
        h.engine.disarm(h.now)
        assertEquals(GuardState.DISARMED, h.engine.state)

        h.advance(6_000, stepMs = 500) { t ->
            h.engine.onPeerBeacon(
                t, -60,
                beacon(
                    PEER_A, seq = ++seq, alarming = true,
                    eventType = Protocol.EVENT_ALARM, subjectId = PEER_B,
                ),
            )
        }

        assertTrue(
            "the controls that reach the others have to stay reachable",
            h.engine.snapshot(h.now).groupAlarmActive,
        )
    }

    @Test
    fun `stopping a false alarm leaves the phone guarding`() {
        val h = Harness()
        h.armAndCalibrate()

        h.engine.onPeerBeacon(
            h.now, -60,
            beacon(PEER_A, seq = 10, eventType = Protocol.EVENT_ALARM, subjectId = PEER_B),
        )
        assertEquals(GuardState.ALARM, h.engine.state)

        h.engine.stopGroupAlarm(h.now)
        h.advance(12_000, stepMs = 500) { t -> h.engine.onPeerBeacon(t, -60, beacon(PEER_A)) }

        assertEquals(
            "a false alarm must not cost the afternoon's guarding",
            GuardState.ARMED, h.engine.state,
        )
        assertEquals(1, h.recorder.clearedCount)
    }

    @Test
    fun `disarming forgets what it was suspicious about`() {
        val h = Harness()
        h.armAndCalibrate()

        // A moving peer whose signal recedes eight decibels and then holds: an
        // episode is running, but it is short of what it takes to vote.
        var rssi = -60.0
        h.advance(2_000, stepMs = 250) { t ->
            rssi -= 1.0
            h.engine.onPeerBeacon(
                t, rssi.toInt(),
                beacon(PEER_A, stationary = false, motionScore = 180),
            )
        }
        h.advance(3_000, stepMs = 250) { t ->
            h.engine.onPeerBeacon(
                t, rssi.toInt(),
                beacon(PEER_A, stationary = false, motionScore = 180),
            )
        }
        assertFalse("not enough to alarm on", h.recorder.alarmed)
        assertTrue(
            "the episode should be running by now",
            h.engine.snapshot(h.now).peers.single().suspected,
        )

        h.engine.disarm(h.now)
        h.advance(1_000, stepMs = 250)

        assertFalse(
            "a disarmed phone is not watching anybody",
            h.engine.snapshot(h.now).peers.single().suspected,
        )
    }

    // =====================================================================
    // Names
    // =====================================================================

    /**
     * Names travel two characters at a time in the beacon's idle event slot, so
     * six separate packets have to be caught before one can be read. At the
     * calm duty cycle that is well over a minute of both phones lying still,
     * and in the field the group list simply showed hexadecimal ids forever.
     * Meeting somebody new is therefore worth a few seconds of fast radio.
     */
    @Test
    fun `meeting a phone with no name yet speeds the radio up`() {
        val h = Harness()
        h.settle()

        h.engine.onPeerBeacon(h.now, -60, beacon(PEER_A, armed = false))
        h.advance(2_000, stepMs = 500) { t ->
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A, armed = false))
        }
        assertEquals(RadioProfile.ALERT, h.recorder.profile)

        sendName(h, PEER_A, "Lisa", armed = false)
        // Past the dwell: coming back down is deliberately not instant, because
        // a profile that flaps takes the advertiser off the air on every change
        // and spends the scan-start allowance Android rations. Escalating still
        // is instant - that is the half that has to be.
        h.advance(ThreatEngine.RADIO_DWELL_MS + 1_000)

        assertEquals("Lisa", h.engine.snapshot(h.now).peers.single().name)
        assertEquals(
            "and drops back once we know who they are",
            RadioProfile.CALM, h.recorder.profile,
        )
    }

    @Test
    fun `a phone that never sends a name does not hold the radio up forever`() {
        val h = Harness()
        h.settle()

        h.advance(ThreatEngine.PEER_INTRODUCTION_MS + 3_000, stepMs = 1_000) { t ->
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A, armed = false))
        }

        assertEquals(RadioProfile.CALM, h.recorder.profile)
    }

    /**
     * People are holding their phones when they set a group up. Requiring
     * stillness to send a name meant neither phone said a word about who it was
     * during the one minute both of them were listening hardest.
     */
    @Test
    fun `a disarmed phone introduces itself even while it is being handled`() {
        val h = Harness()
        h.engine.onSelfMotion(h.now, MotionSignal.Level(200, stationary = false))

        assertTrue(
            "nobody is guarding a disarmed phone, so the slot is free",
            h.engine.canBroadcastName(h.now),
        )

        // Armed and still being nudged about, but too gently to be a pickup:
        // the phone is guarding, so its watchers are entitled to a fresh motion
        // score in every packet and the slot is not ours to spend.
        h.settle()
        h.engine.arm(h.now)
        h.advance(12_000, stepMs = 500)
        h.engine.onSelfMotion(h.now, MotionSignal.Level(10, stationary = false))

        assertEquals(GuardState.ARMED, h.engine.state)
        assertFalse(
            "an armed phone still owes its watchers a fresh motion score",
            h.engine.canBroadcastName(h.now),
        )
    }

    /**
     * A name costs six separate packets to learn, so it must not be thrown away
     * because the peer went quiet or the service restarted. The engine reports it
     * once, and the store keeps it.
     */
    @Test
    fun `a reassembled name is reported once so it can be kept`() {
        val h = Harness()
        h.settle()
        h.engine.onPeerBeacon(h.now, -60, beacon(PEER_A, armed = false))

        sendName(h, PEER_A, "Lisa", armed = false)
        assertEquals(listOf(PEER_A to "Lisa"), h.recorder.learnedNames)

        // The peer keeps introducing itself; that is not news.
        sendName(h, PEER_A, "Lisa", armed = false)
        assertEquals(1, h.recorder.learnedNames.size)
    }

    /**
     * The introduction window used to be a single roll of the dice, anchored to
     * when a peer was first heard. A phone that spent those twenty-five seconds in
     * somebody's pocket stayed "Phone A31F" for the rest of the afternoon.
     */
    @Test
    fun `a phone that stayed anonymous gets another chance later`() {
        val h = Harness()
        h.settle()

        h.advance(ThreatEngine.PEER_INTRODUCTION_MS + 3_000, stepMs = 1_000) { t ->
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A, armed = false))
        }
        assertEquals("the first window is bounded", RadioProfile.CALM, h.recorder.profile)

        h.advance(ThreatEngine.NAME_HUNT_COOLDOWN_MS + 2_000, stepMs = 1_000) { t ->
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A, armed = false))
        }
        assertEquals(
            "but a nameless phone is worth trying again for",
            RadioProfile.ALERT, h.recorder.profile,
        )
    }

    /**
     * The eight seconds after arming are when two phones that have just met are
     * both listening hardest, and excluding them threw that away. Nothing is being
     * voted on yet, so the event slot is genuinely free.
     */
    @Test
    fun `a phone still calibrating introduces itself`() {
        val h = Harness()
        h.settle()
        h.engine.arm(h.now)
        h.advance(2_000, stepMs = 500) { t -> h.engine.onPeerBeacon(t, -60, beacon(PEER_A)) }

        assertEquals(GuardState.CALIBRATING, h.engine.state)
        assertTrue(h.engine.canBroadcastName(h.now))
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

    // =====================================================================
    // Announcements that finish - the acknowledgement protocol
    // =====================================================================

    /**
     * The core of it: an announcer stops when it has been heard.
     *
     * Everything used to be repeated blindly and for a long time, because
     * nothing could tell the sender whether it had landed. An alarm stayed in
     * the event slot for as long as the siren ran and a group command for
     * twenty-five seconds flat, so at any moment the air was full of packets
     * describing decisions that had already been acted on everywhere - and a
     * phone receiving one had no way to tell it apart from news.
     */
    @Test
    fun `an incident leaves the air once everybody has confirmed it`() {
        val h = Harness()
        h.armAndSettle(listOf(PEER_A, PEER_B))
        h.engine.panic(h.now)

        val counter = h.engine.alarmCounter
        assertTrue("somebody has to hear it first", h.engine.alarmNeedsAir(h.now))

        h.advance(ThreatEngine.ANNOUNCE_MIN_MS + 500, stepMs = 250) { t ->
            listOf(PEER_A, PEER_B).forEach { h.engine.onPeerBeacon(t, -60, beacon(it)) }
        }
        assertTrue("nobody has confirmed yet", h.engine.alarmNeedsAir(h.now))

        h.engine.onPeerBeacon(h.now, -60, ack(PEER_A, origin = SELF_ID, counter = counter, seq = 40))
        assertTrue("one of two is not everybody", h.engine.alarmNeedsAir(h.now))

        h.engine.onPeerBeacon(h.now, -60, ack(PEER_B, origin = SELF_ID, counter = counter, seq = 41))
        assertFalse(
            "heard by everyone in earshot, so there is nothing left to say",
            h.engine.alarmNeedsAir(h.now),
        )
        assertEquals(
            "and the siren is untouched by any of it",
            GuardState.ALARM, h.engine.state,
        )
    }

    /**
     * ...and it is safe to stop, because stopping is not a decision, it is a
     * continuously re-evaluated fact.
     */
    @Test
    fun `an incident goes back on the air for a phone that turns up late`() {
        val h = Harness()
        h.armAndSettle(listOf(PEER_A))
        h.engine.panic(h.now)
        val counter = h.engine.alarmCounter

        h.advance(ThreatEngine.ANNOUNCE_MIN_MS + 500, stepMs = 250) { t ->
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A))
        }
        h.engine.onPeerBeacon(h.now, -60, ack(PEER_A, origin = SELF_ID, counter = counter, seq = 40))
        assertFalse(h.engine.alarmNeedsAir(h.now))

        // Somebody's phone comes out of a bag, having missed the whole thing.
        h.engine.onPeerBeacon(h.now, -60, beacon(PEER_C, seq = 1))
        assertTrue(
            "a phone that has not confirmed is a phone that has not heard",
            h.engine.alarmNeedsAir(h.now),
        )
    }

    @Test
    fun `a group command stops being repeated once everyone confirms`() {
        val h = Harness()
        h.armAndSettle(listOf(PEER_A))

        h.engine.noteOwnGroupCommand(Protocol.EVENT_ALARM_CLEAR, counter = 9, now = h.now)
        h.advance(ThreatEngine.ANNOUNCE_MIN_MS + 500, stepMs = 250) { t ->
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A))
        }
        assertTrue(h.engine.announcementNeedsAir(h.now))
        assertEquals(0 to 1, h.engine.announcementProgress())

        h.engine.onPeerBeacon(h.now, -60, ack(PEER_A, origin = SELF_ID, counter = 9, seq = 40))
        assertEquals(1 to 1, h.engine.announcementProgress())
        assertFalse(h.engine.announcementNeedsAir(h.now))
    }

    /**
     * Regression test for the message that kept popping up after everything had
     * already been silenced.
     *
     * A stop cannot land on every phone in the same millisecond, so for seconds
     * afterwards the air still carries beacons from the incident that was just
     * called off. The banner used to be driven by "did any alarming beacon
     * arrive recently", refreshed by every one of those - so it appeared, aged
     * out after twelve seconds, and came back on the next straggler, about an
     * incident nobody was in any more.
     */
    @Test
    fun `echoes of a stopped incident never claim the group is still alarming`() {
        val h = Harness()
        h.armAndCalibrate()

        // PEER_A decides on an incident and this phone joins in.
        var seq = 20
        h.engine.onPeerBeacon(h.now, -60, alarm(PEER_A, subject = PEER_B, counter = 5, seq = ++seq))
        assertEquals(GuardState.ALARM, h.engine.state)

        // The user stops everyone from here.
        h.engine.stopGroupAlarm(h.now)
        assertFalse(h.engine.groupAlarmActive())

        // Nobody has caught up yet, so for a few seconds the air still carries
        // the incident: PEER_A still announcing it, and a phone that had merely
        // joined in - which says which incident it is in only through the
        // confirmation it broadcasts.
        h.advance(8_000, stepMs = 500) { t ->
            h.engine.onPeerBeacon(t, -60, alarm(PEER_A, subject = PEER_B, counter = 5, seq = ++seq))
            h.engine.onPeerBeacon(
                t, -60,
                ack(PEER_C, origin = PEER_A, counter = 5, seq = ++seq, alarming = true),
            )
            assertFalse(
                "an incident the group has called off is over, echoes included",
                h.engine.groupAlarmActive(),
            )
        }
        assertNotEquals(
            "and none of it can restart the siren either",
            GuardState.ALARM, h.engine.state,
        )
    }

    /**
     * The limit of the above, and it is a feature rather than a leak.
     *
     * A phone that is genuinely out of range never hears "stop" and never stops.
     * The user has to find that out - so the call-off is deliberately allowed to
     * expire rather than being refreshed by every echo it silences. What that
     * buys is that the banner comes back *once*, after the attempt to reach the
     * phone has run its course, instead of flickering on and off throughout it.
     */
    @Test
    fun `a phone that never hears the stop is eventually reported again`() {
        val h = Harness()
        h.armAndCalibrate()

        var seq = 20
        h.engine.onPeerBeacon(h.now, -60, alarm(PEER_A, subject = PEER_B, counter = 5, seq = ++seq))
        h.engine.stopGroupAlarm(h.now)

        h.advance(ThreatEngine.DECLINED_FORGET_MS + 4_000, stepMs = 500) { t ->
            h.engine.onPeerBeacon(t, -60, alarm(PEER_A, subject = PEER_B, counter = 5, seq = ++seq))
        }

        assertTrue(
            "somebody really is still screaming, and that has to be reachable",
            h.engine.groupAlarmActive(),
        )
        assertEquals(
            "...without this phone being dragged back into it",
            GuardState.ARMED, h.engine.state,
        )
    }

    /**
     * The other half, which must survive the fix: silencing *your own* handset
     * says nothing about anybody else's. Losing that is how somebody ended up
     * with a screaming towel and an "Arm all" button.
     */
    @Test
    fun `a local disarm does not pretend the group has stopped`() {
        val h = Harness()
        h.armAndCalibrate()

        var seq = 20
        h.engine.onPeerBeacon(h.now, -60, alarm(PEER_A, subject = PEER_B, counter = 5, seq = ++seq))
        h.engine.disarm(h.now)

        h.advance(6_000, stepMs = 500) { t ->
            h.engine.onPeerBeacon(t, -60, alarm(PEER_A, subject = PEER_B, counter = 5, seq = ++seq))
        }
        assertTrue(
            "the controls that reach the others have to stay reachable",
            h.engine.groupAlarmActive(),
        )
    }

    // =====================================================================
    // Leaving a group
    // =====================================================================

    /**
     * Leaving used to be silent - the phone simply stopped advertising - and to
     * everybody else that is indistinguishable from a phone being carried away
     * while armed. In a two-phone group one LOST vote is the whole consensus, so
     * walking out of a group could set off a siren on the friend left behind.
     */
    @Test
    fun `a phone that says goodbye is dropped rather than mourned`() {
        val h = Harness()
        h.armAndSettle(listOf(PEER_A))
        assertEquals(1, h.engine.snapshot(h.now).peers.size)

        h.engine.onPeerBeacon(
            h.now, -60,
            command(PEER_A, Protocol.EVENT_LEAVE, counter = 3, seq = 40),
        )

        assertTrue("dropped at once", h.engine.snapshot(h.now).peers.isEmpty())
        assertEquals(listOf(PEER_A), h.recorder.departed)
        assertEquals(
            "and the news is passed on, like any other group command",
            listOf(Protocol.EVENT_LEAVE to PEER_A), h.recorder.relays,
        )

        // Long past every timeout that could have accused it of vanishing.
        h.advance(60_000, stepMs = 1_000)
        assertFalse("leaving is not theft", h.recorder.alarmed)
        assertTrue(h.engine.snapshot(h.now).peers.isEmpty())
    }

    /**
     * The departing phone's last beacons are still in the air when its farewell
     * lands, and so are the relays. Without a memory of who has left, the peer
     * is dropped and immediately recreated by its own echo — and the relay, no
     * longer recognised as one already seen, is passed on again by everybody.
     */
    @Test
    fun `a farewell cannot be undone by what is still in the air`() {
        val h = Harness()
        h.armAndSettle(listOf(PEER_A))

        var seq = 40
        h.engine.onPeerBeacon(
            h.now, -60, command(PEER_A, Protocol.EVENT_LEAVE, counter = 3, seq = ++seq),
        )

        h.advance(10_000, stepMs = 500) { t ->
            // Straggling telemetry from the phone that left...
            h.engine.onPeerBeacon(t, -60, beacon(PEER_A, seq = ++seq))
            // ...and somebody else still relaying its farewell.
            h.engine.onPeerBeacon(
                t, -60,
                command(PEER_B, Protocol.EVENT_LEAVE, origin = PEER_A, counter = 3, seq = ++seq),
            )
        }

        assertTrue(
            "a phone that has left stays left",
            h.engine.snapshot(h.now).peers.none { it.deviceId == PEER_A },
        )
        assertEquals(
            "and the relay terminates rather than circulating forever",
            1, h.recorder.relays.count { it.first == Protocol.EVENT_LEAVE },
        )
    }
}
