package com.beachprotect

import com.beachprotect.ble.Beacon
import com.beachprotect.ble.BeaconComposer
import com.beachprotect.ble.BeaconSource
import com.beachprotect.ble.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What goes into the one event slot each beacon has - and, far more to the
 * point, what *stops* going into it.
 *
 * The second half is what earned this file. An alarm used to be queued as a
 * repeating group command *as well as* riding on the guard's live state, and
 * nothing ever cancelled the queue. So a phone went on broadcasting "theft!"
 * for the whole repeat window after its siren had been silenced. Every other
 * phone heard an incident that no longer existed: the group-alarm banner came
 * back by itself long after everybody had stood down, and once the echo window
 * lapsed a phone could be dragged into a fresh, real siren by a packet
 * describing something that had ended half a minute earlier.
 */
class BeaconComposerTest {

    private class FakeSource : BeaconSource {
        override val groupId = 0x1234_5678
        override val deviceId = 0x00AA
        override val groupKey = ByteArray(32) { (it * 7).toByte() }
        override val txPowerRef = -59
        override val sequenceBlockSize = 4096
        private var next = 0

        override fun beginSequenceBlock(): Int {
            val base = next
            next += sequenceBlockSize
            return base
        }
    }

    private val source = FakeSource()
    private val composer = BeaconComposer(source)

    private fun compose(
        now: Long = 0,
        alarming: Boolean = false,
        alarmEvent: Int = Protocol.EVENT_ALARM,
        alarmSubject: Int = Protocol.DEVICE_ID_NONE,
        votes: List<Pair<Int, Int>> = emptyList(),
        allowName: Boolean = false,
        name: String = "",
    ): Beacon {
        val bytes = composer.compose(
            BeaconComposer.Input(
                now = now,
                flags = if (alarming) Protocol.FLAG_ARMED or Protocol.FLAG_ALARMING else Protocol.FLAG_ARMED,
                motionScore = 3,
                battery = 80,
                alarming = alarming,
                alarmEvent = alarmEvent,
                alarmSubject = alarmSubject,
                votes = votes,
                allowName = allowName,
                name = name,
            ),
        )
        return requireNotNull(Protocol.decode(bytes, source.groupId, source.groupKey)) {
            "the composer produced something that does not authenticate"
        }
    }

    @Test
    fun `an alarm is on the air only while it is actually sounding`() {
        val screaming = compose(now = 0, alarming = true, alarmSubject = 0x00BB)
        assertEquals(Protocol.EVENT_ALARM, screaming.eventType)
        assertEquals(0x00BB, screaming.subjectId)

        // The siren stops. The very next beacon must stop saying otherwise -
        // not in twelve seconds, not in thirty, but now.
        val silenced = compose(now = 200, alarming = false)
        assertNotEquals(
            "an incident that is over must leave the air immediately",
            Protocol.EVENT_ALARM, silenced.eventType,
        )
        assertEquals(Protocol.EVENT_NONE, silenced.eventType)
    }

    @Test
    fun `a panic goes out as a panic`() {
        val beacon = compose(
            alarming = true,
            alarmEvent = Protocol.EVENT_PANIC,
            alarmSubject = source.deviceId,
        )
        assertEquals(Protocol.EVENT_PANIC, beacon.eventType)
    }

    @Test
    fun `a group command carries the origin and the counter of one press`() {
        composer.queueControl(0, Protocol.EVENT_DISARM_ALL, subjectId = 0x00CC, commandCounter = 42)

        val beacon = compose(now = 1_000)
        assertEquals(Protocol.EVENT_DISARM_ALL, beacon.eventType)
        assertEquals("the issuer travels with the command", 0x00CC, beacon.subjectId)
        assertEquals("...and so does which press it was", 42, beacon.commandCounter)
        assertTrue(beacon.carriesCommand)
    }

    @Test
    fun `a group command stops when its window closes`() {
        composer.queueControl(
            0, Protocol.EVENT_ARM_ALL, subjectId = 0x00CC, commandCounter = 1,
            durationMs = 5_000,
        )
        assertEquals(Protocol.EVENT_ARM_ALL, compose(now = 4_000).eventType)
        assertEquals(Protocol.EVENT_NONE, compose(now = 6_000).eventType)
    }

    @Test
    fun `an alarm outranks a queued command, and the command survives it`() {
        composer.queueControl(0, Protocol.EVENT_ARM_ALL, subjectId = 0x00CC, commandCounter = 1)

        assertEquals(
            "a siren is more urgent than anything else in the slot",
            Protocol.EVENT_ALARM, compose(now = 500, alarming = true).eventType,
        )
        assertEquals(
            "and the announcement resumes once it is over",
            Protocol.EVENT_ARM_ALL, compose(now = 900).eventType,
        )
    }

    /** A suspicion in flight when somebody presses a button still gets aired. */
    @Test
    fun `votes are interleaved with a command rather than starved by it`() {
        composer.queueControl(0, Protocol.EVENT_DISARM_ALL, subjectId = 0x00CC, commandCounter = 1)
        val votes = listOf(Protocol.EVENT_SUSPECT to 0x00DD)

        val seen = (1..6).map { compose(now = it * 100L, votes = votes).eventType }.toSet()

        assertTrue(seen.contains(Protocol.EVENT_DISARM_ALL))
        assertTrue(seen.contains(Protocol.EVENT_SUSPECT))
    }

    @Test
    fun `clearing a command takes it off the air at once`() {
        composer.queueControl(0, Protocol.EVENT_ALARM_CLEAR, subjectId = 0x00CC, commandCounter = 1)
        assertEquals(Protocol.EVENT_ALARM_CLEAR, composer.pendingControlEvent)

        composer.clearControl()

        assertEquals(Protocol.EVENT_NONE, compose(now = 500).eventType)
    }

    @Test
    fun `the idle slot is spent on a name, two characters at a time`() {
        val chunks = (0 until Protocol.NAME_CHUNKS).map {
            compose(now = it * 100L, allowName = true, name = "Lisa")
        }
        val assembler = Protocol.NameAssembler()
        val assembled = chunks.mapNotNull { assembler.accept(it) }.lastOrNull()
        assertEquals("Lisa", assembled)
    }
}
