package com.beachprotect

import com.beachprotect.ble.Beacon
import com.beachprotect.ble.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    private val secret = ByteArray(10) { (it * 17 + 3).toByte() }
    private val groupId = Protocol.groupIdOf(secret)
    private val key = Protocol.groupKeyOf(secret)

    private fun sample(
        eventType: Int = Protocol.EVENT_NONE,
        subjectId: Int = 0,
        motionScore: Int = 42,
    ) = Beacon(
        version = Protocol.VERSION,
        groupId = groupId,
        deviceId = 0xBEEF,
        flags = Protocol.FLAG_ARMED or Protocol.FLAG_STATIONARY,
        txPowerRef = -59,
        battery = 77,
        seq = 4321,
        eventType = eventType,
        subjectId = subjectId,
        motionScore = motionScore,
    )

    @Test
    fun `payload fits the legacy advertising budget`() {
        assertEquals(20, Protocol.encode(sample(), key).size)
        assertTrue("must leave room for the AD headers", Protocol.PAYLOAD_SIZE <= 24)
    }

    @Test
    fun `round trip preserves every field`() {
        val original = sample(eventType = Protocol.EVENT_SUSPECT, subjectId = 0x1357)
        val decoded = Protocol.decode(Protocol.encode(original, key), groupId, key)
        assertEquals(original, decoded)
    }

    @Test
    fun `negative tx power survives the round trip`() {
        val original = sample().copy(txPowerRef = -95)
        val decoded = Protocol.decode(Protocol.encode(original, key), groupId, key)
        assertEquals(-95, decoded!!.txPowerRef)
    }

    @Test
    fun `a tampered payload is rejected`() {
        val bytes = Protocol.encode(sample(), key)
        bytes[7] = (bytes[7].toInt() xor 0x01).toByte()   // flip the "armed" bit
        assertNull(Protocol.decode(bytes, groupId, key))
    }

    @Test
    fun `a foreign group is ignored`() {
        val otherSecret = Protocol.newGroupSecret()
        val otherKey = Protocol.groupKeyOf(otherSecret)
        val otherId = Protocol.groupIdOf(otherSecret)
        val bytes = Protocol.encode(sample(), key)
        assertNull(Protocol.decode(bytes, otherId, otherKey))
    }

    @Test
    fun `a beacon signed with the wrong key is rejected`() {
        val impostor = Protocol.groupKeyOf(Protocol.newGroupSecret())
        val bytes = Protocol.encode(sample(), impostor)
        assertNull("only the group secret can authorise a beacon", Protocol.decode(bytes, groupId, key))
    }

    @Test
    fun `truncated payloads are rejected`() {
        val bytes = Protocol.encode(sample(), key).copyOf(12)
        assertNull(Protocol.decode(bytes, groupId, key))
    }

    // ---- group codes -----------------------------------------------------

    @Test
    fun `group code round trips`() {
        val code = Protocol.encodeGroupCode(secret)
        assertEquals("XXXX-XXXX-XXXX-XXXX", code.replace(Regex("[^-]"), "X"))
        assertArrayEquals(secret, Protocol.decodeGroupCode(code))
    }

    @Test
    fun `group codes survive being read out loud badly`() {
        val code = Protocol.encodeGroupCode(secret)
        val mangled = code.lowercase().replace("-", " ")
        assertArrayEquals(secret, Protocol.decodeGroupCode(mangled))
    }

    @Test
    fun `letter O typed for zero still works`() {
        // "0" is in the alphabet, "O" is not - typing O must map back to 0.
        val decoded = Protocol.decodeGroupCode("0123-4567-89AB-CDEF")
        val alsoDecoded = Protocol.decodeGroupCode("O123-4567-89AB-CDEF")
        assertNotNull(decoded)
        assertArrayEquals(decoded, alsoDecoded)
    }

    @Test
    fun `malformed group codes are rejected`() {
        assertNull(Protocol.decodeGroupCode("too-short"))
        assertNull(Protocol.decodeGroupCode("0123-4567-89AB-CDE"))
        assertNull(Protocol.decodeGroupCode("0123-4567-89AB-CDE!"))
    }

    @Test
    fun `random secrets always round trip`() {
        repeat(200) {
            val s = Protocol.newGroupSecret()
            assertArrayEquals(s, Protocol.decodeGroupCode(Protocol.encodeGroupCode(s)))
        }
    }

    // ---- derivation ------------------------------------------------------

    @Test
    fun `device ids are stable and differ per install`() {
        val a = Protocol.deviceIdOf(secret, "install-a")
        val b = Protocol.deviceIdOf(secret, "install-b")
        assertEquals(a, Protocol.deviceIdOf(secret, "install-a"))
        assertTrue(a != b)
        assertTrue(a in 1..0xFFFE)
    }

    // ---- name chunking ---------------------------------------------------

    @Test
    fun `a name is reassembled from its chunks`() {
        val assembler = Protocol.NameAssembler()
        val name = "Jan"
        var result: String? = null
        for (i in 0 until Protocol.NAME_CHUNKS) {
            val (subjectId, motionScore) = Protocol.encodeNameChunk(name, i)
            result = assembler.accept(
                sample(
                    eventType = Protocol.EVENT_NAME,
                    subjectId = subjectId,
                    motionScore = motionScore,
                ),
            ) ?: result
        }
        assertEquals("Jan", result)
    }

    @Test
    fun `a partially received name yields nothing`() {
        val assembler = Protocol.NameAssembler()
        val (subjectId, motionScore) = Protocol.encodeNameChunk("Charlotte", 0)
        val out = assembler.accept(
            sample(eventType = Protocol.EVENT_NAME, subjectId = subjectId, motionScore = motionScore),
        )
        assertNull(out)
    }

    @Test
    fun `long names are reassembled up to the wire limit`() {
        val assembler = Protocol.NameAssembler()
        val name = Protocol.normaliseName("Wolfgang-Amadeus")
        assertEquals(Protocol.NAME_MAX_LENGTH, name.length)
        var result: String? = null
        for (i in 0 until Protocol.NAME_CHUNKS) {
            val (subjectId, motionScore) = Protocol.encodeNameChunk(name, i)
            result = assembler.accept(
                sample(
                    eventType = Protocol.EVENT_NAME,
                    subjectId = subjectId,
                    motionScore = motionScore,
                ),
            ) ?: result
        }
        assertEquals(name, result)
    }
}
