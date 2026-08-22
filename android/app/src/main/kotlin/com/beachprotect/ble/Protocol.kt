package com.beachprotect.ble

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * BeachProtect wire protocol.
 *
 * The entire group protocol is *connectionless*: every phone continuously
 * broadcasts a 20 byte BLE service-data payload, and every phone scans for that
 * payload. There are no GATT connections, no pairing and no central node, which
 * means the mesh scales to any group size, survives any single phone leaving,
 * and costs a fraction of the energy a connected topology would.
 *
 * RSSI comes for free with every scan result, so presence sensing and the
 * control channel (alarm / disarm / consensus votes) share one radio activity.
 *
 * Payload layout (20 bytes, all fields byte aligned, big-endian):
 *
 *   offset  size  field
 *   ------  ----  -----------------------------------------------------------
 *      0      1   version
 *      1      4   groupId          truncated hash of the group secret
 *      5      2   deviceId         stable per-device id inside the group
 *      7      1   flags            see FLAG_*
 *      8      1   txPowerRef       signed: expected RSSI at 1 m (calibration)
 *      9      1   battery          0..100 percent
 *     10      2   seq              monotonic, persisted; replay protection
 *     12      1   eventType        see EVENT_*
 *     13      2   subjectId        device this event refers to
 *     15      1   motionScore      0..255 recent motion energy of the sender,
 *                                  or a counter — see [carriesCounter]
 *     16      4   mac              HMAC-SHA256(groupKey, bytes[0..15])[0..3]
 *
 * The MAC means an outsider cannot inject a fake "disarm everyone" or a fake
 * alarm, and the monotonic [Beacon.seq] means a recorded packet cannot be
 * replayed later. Both are checked before a beacon influences any decision.
 *
 * ## Everything announced is *identified*, and everything identified is acked
 *
 * Alarms, group commands and departures all borrow the motion-score byte for a
 * counter, so each one is a nameable thing — `(origin device, counter)` — rather
 * than merely a type. That single idea does three separate jobs:
 *
 *  - a stale copy still circulating half a minute later is recognisably *the
 *    same* announcement and is dropped rather than re-applied;
 *  - a genuine second press is recognisably *different* and is obeyed;
 *  - and, because it can be named, it can be **acknowledged** ([EVENT_ACK]).
 *
 * The third is what lets an announcement stop. Without it the only safe policy
 * was to repeat everything blindly for tens of seconds and hope, which left the
 * air full of packets describing incidents that were already over — and a phone
 * that had stopped could not tell those apart from a phone that had not.
 */
object Protocol {

    /**
     * Wire version. Bumped to 2 when alarms gained incident counters and the
     * acknowledgement and departure events were added.
     *
     * Deliberately a hard break: the scan filter matches on this byte, so a v1
     * phone and a v2 phone simply do not see each other. A v1 phone would
     * otherwise read an alarm's incident counter as a motion score and ignore
     * departures entirely, and a mesh that half works is worse than one that
     * visibly does not.
     */
    const val VERSION = 2
    const val PAYLOAD_SIZE = 20
    private const val SIGNED_PREFIX = 16

    /** 16-bit service UUID, expanded to the Bluetooth base UUID. */
    const val SERVICE_UUID_STRING = "0000b3a7-0000-1000-8000-00805f9b34fb"

    // ---- flags ----------------------------------------------------------
    const val FLAG_ARMED = 1 shl 0
    const val FLAG_ALARMING = 1 shl 1
    const val FLAG_STATIONARY = 1 shl 2
    const val FLAG_BOX_GUARDIAN = 1 shl 3
    const val FLAG_LOW_BATTERY = 1 shl 4
    const val FLAG_CHARGING = 1 shl 5
    const val FLAG_PENDING = 1 shl 6
    const val FLAG_SIMULATED = 1 shl 7

    // ---- events ---------------------------------------------------------
    const val EVENT_NONE = 0

    /** Observer vote: "I see [Beacon.subjectId] moving away from me." */
    const val EVENT_SUSPECT = 1

    /** Observer vote: "[Beacon.subjectId] vanished from the mesh while armed." */
    const val EVENT_LOST = 2

    /**
     * Decision: "[Beacon.subjectId] is being stolen - everybody sound off."
     *
     * One of the three **alarm events**. For these, bytes 13..15 read
     * `subjectId` (the device being taken) and, in place of the motion score, an
     * incident counter. Exactly one phone puts an alarm on the air — the one
     * that decided on it — so `(deviceId, counter)` names the incident with no
     * extra bytes, and that name is what everyone else acknowledges.
     */
    const val EVENT_ALARM = 3

    /**
     * Stop all sirens but stay armed (recalibrates baselines).
     *
     * One of the **group commands**. For these, bytes 13..15 read `originId`
     * (the device that decided it, unchanged through every relay) and a
     * [Beacon.counter] in place of the motion score.
     */
    const val EVENT_ALARM_CLEAR = 4

    /** Disarm the whole group ("we are packing up"). A group command. */
    const val EVENT_DISARM_ALL = 5

    /** Arm the whole group. A group command. */
    const val EVENT_ARM_ALL = 6

    /** The guarded speaker box is being taken. */
    const val EVENT_BOX_ALARM = 7

    /** Manual panic button pressed on [Beacon.subjectId]. */
    const val EVENT_PANIC = 8

    /** Loopback test used by the simulator and the self-test screen. */
    const val EVENT_TEST = 9

    /**
     * Two characters of the sender's display name.
     *
     * There is no room for a name in a 20 byte packet, so names are dripped out
     * two characters at a time in the event slot that would otherwise be idle.
     * This costs zero extra radio time: we are advertising anyway.
     *
     * For this event only, bytes 13..15 are reinterpreted as
     * `chunkIndex, char0, char1`, which means the packet carries no fresh
     * motionScore. That is safe because a device only sends name chunks while
     * it is stationary and calm, and because FLAG_STATIONARY - which is what
     * the occlusion gate actually keys on - still travels in the untouched
     * flags byte. Decoders keep the previous motionScore for these packets.
     */
    const val EVENT_NAME = 10

    /**
     * "I have received the announcement `(subjectId, counter)`."
     *
     * The one addition that lets anything ever stop being repeated. Before it,
     * an alarm stayed in the event slot for as long as the siren ran and a group
     * command was repeated blindly for twenty-five seconds, because there was no
     * way to find out whether it had landed. Every phone in range was therefore
     * still hearing an incident several seconds after the last person had
     * silenced it, and a phone that had stopped had no way to tell that echo
     * apart from a phone that genuinely had not stopped — which is exactly what
     * "the group is still alarming" kept popping up about.
     *
     * An announcer now stops as soon as every phone it can hear has confirmed,
     * typically inside two seconds, and starts again by itself if a phone it has
     * not heard from appears. `subjectId` carries the announcement's origin and
     * the motion-score byte its counter, so one event acknowledges incidents,
     * commands and departures alike.
     */
    const val EVENT_ACK = 11

    /**
     * "I am leaving this group." A group command, relayed and acknowledged.
     *
     * Departure used to be silent: the phone simply stopped advertising, which
     * to everyone else is indistinguishable from a phone that has been switched
     * off, bagged, or carried away while armed — so leaving a group could raise
     * a `PEER_LOST` siren on the friends left behind, and at best left a card
     * reading "no signal" for the five minutes of `PEER_FORGET_MS`.
     */
    const val EVENT_LEAVE = 12

    const val NAME_CHUNKS = 6
    const val NAME_MAX_LENGTH = NAME_CHUNKS * 2

    /** The events that carry a group decision rather than telemetry. */
    fun isGroupCommand(eventType: Int): Boolean = when (eventType) {
        EVENT_ALARM_CLEAR, EVENT_DISARM_ALL, EVENT_ARM_ALL, EVENT_LEAVE -> true
        else -> false
    }

    /** The events that say "somebody is being robbed", in their three flavours. */
    fun isAlarmEvent(eventType: Int): Boolean = when (eventType) {
        EVENT_ALARM, EVENT_BOX_ALARM, EVENT_PANIC -> true
        else -> false
    }

    /**
     * Whether this event borrows the motion-score byte for a counter.
     *
     * Safe for the same reason it has always been safe for name chunks: the
     * occlusion gate keys on `FLAG_STATIONARY`, which still travels untouched in
     * the flags byte, and a receiver simply keeps the motion score it already
     * had. What it buys is that every announcement has an identity, and can
     * therefore be recognised, superseded and acknowledged.
     */
    fun carriesCounter(eventType: Int): Boolean =
        isGroupCommand(eventType) || isAlarmEvent(eventType) || eventType == EVENT_ACK

    /**
     * Is [candidate] a later command from the same device than [last]?
     *
     * Eight bit comparison with wraparound, exactly as [Beacon.seq] does with
     * sixteen. Half the range counts as newer, which is far more than the two or
     * three commands that can ever be in the air at once.
     *
     * This is what gives a group command an *identity* rather than only a type,
     * and identity is what the mesh actually needs. Without it, "arm all, then
     * disarm all, then arm all again" is three copies of two indistinguishable
     * messages: a receiver either re-applies every copy — so a relay still
     * circulating half a minute later quietly undoes a phone somebody has just
     * armed — or it remembers the type and ignores the second press entirely.
     * There is no way to be right about both, and the field test hit each of
     * them in turn. With a counter, a stale copy is simply older and is dropped,
     * and every genuine press is newer and is obeyed.
     */
    fun isNewerCommand(candidate: Int, last: Int): Boolean {
        val delta = (candidate - last) and 0xFF
        return delta in 1..0x7F
    }

    /** Device ids reserved as "none" / "broadcast". */
    const val DEVICE_ID_NONE = 0
    const val DEVICE_ID_BROADCAST = 0xFFFF

    // ---- key derivation -------------------------------------------------

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    /** Fresh 10 byte (80 bit) group secret; rendered to the user as 16 chars. */
    fun newGroupSecret(): ByteArray = ByteArray(10).also { SecureRandom().nextBytes(it) }

    /** Public group identifier carried in every beacon. */
    fun groupIdOf(secret: ByteArray): Int =
        readInt32(sha256("BP-GROUP".toByteArray(), secret), 0)

    /** HMAC key; never leaves the device and is never broadcast. */
    fun groupKeyOf(secret: ByteArray): ByteArray =
        sha256("BP-KEY".toByteArray(), secret)

    /**
     * Stable 16-bit device id derived from the group secret and a per-install
     * random id. Values 0x0000 and 0xFFFF are reserved, so they are folded away.
     */
    fun deviceIdOf(secret: ByteArray, installId: String): Int {
        val h = sha256("BP-DEVICE".toByteArray(), secret, installId.toByteArray())
        var id = ((h[0].toInt() and 0xFF) shl 8) or (h[1].toInt() and 0xFF)
        if (id == DEVICE_ID_NONE || id == DEVICE_ID_BROADCAST) id = 0x1234
        return id
    }

    // ---- group code (human transferable) --------------------------------

    /** Crockford base32: no I, L, O or U, so it cannot be misread aloud. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Encodes a 10 byte secret as 16 characters, grouped as XXXX-XXXX-XXXX-XXXX. */
    fun encodeGroupCode(secret: ByteArray): String {
        require(secret.size == 10) { "group secret must be 10 bytes" }
        val sb = StringBuilder(19)
        var buffer = 0L
        var bits = 0
        var emitted = 0
        for (b in secret) {
            buffer = (buffer shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                val idx = ((buffer shr bits) and 0x1FL).toInt()
                if (emitted > 0 && emitted % 4 == 0) sb.append('-')
                sb.append(ALPHABET[idx])
                emitted++
            }
        }
        return sb.toString()
    }

    /**
     * Parses a group code back into the 10 byte secret. Tolerant of case,
     * separators, and the classic O/0, I/1 and L/1 confusions.
     *
     * @return the secret, or null when the code is malformed.
     */
    fun decodeGroupCode(code: String): ByteArray? {
        val cleaned = StringBuilder()
        for (raw in code.uppercase()) {
            if (raw == '-' || raw == ' ' || raw == '\n' || raw == '\r' || raw == '\t') continue
            val c = when (raw) {
                'O' -> '0'
                'I', 'L' -> '1'
                'U' -> 'V'
                else -> raw
            }
            if (ALPHABET.indexOf(c) < 0) return null
            cleaned.append(c)
        }
        if (cleaned.length != 16) return null

        val out = ByteArray(10)
        var buffer = 0L
        var bits = 0
        var pos = 0
        for (c in cleaned) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(c).toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[pos++] = ((buffer shr bits) and 0xFFL).toByte()
            }
        }
        return if (pos == 10) out else null
    }

    // ---- encode / decode -------------------------------------------------

    fun encode(beacon: Beacon, groupKey: ByteArray): ByteArray {
        val out = ByteArray(PAYLOAD_SIZE)
        out[0] = beacon.version.toByte()
        writeInt32(out, 1, beacon.groupId)
        writeInt16(out, 5, beacon.deviceId)
        out[7] = beacon.flags.toByte()
        out[8] = beacon.txPowerRef.toByte()
        out[9] = beacon.battery.coerceIn(0, 100).toByte()
        writeInt16(out, 10, beacon.seq and 0xFFFF)
        out[12] = beacon.eventType.toByte()
        writeInt16(out, 13, beacon.subjectId)
        out[15] = beacon.motionScore.coerceIn(0, 255).toByte()
        val mac = hmac(groupKey, out, SIGNED_PREFIX)
        System.arraycopy(mac, 0, out, 16, 4)
        return out
    }

    /**
     * Decodes and authenticates a payload.
     *
     * @return the beacon when the payload is well formed, belongs to
     *         [expectedGroupId] and carries a valid MAC; null otherwise.
     */
    fun decode(data: ByteArray?, expectedGroupId: Int, groupKey: ByteArray): Beacon? {
        if (data == null || data.size < PAYLOAD_SIZE) return null
        if (data[0].toInt() != VERSION) return null
        val groupId = readInt32(data, 1)
        if (groupId != expectedGroupId) return null

        val expected = hmac(groupKey, data, SIGNED_PREFIX)
        var diff = 0
        for (i in 0 until 4) diff = diff or (expected[i].toInt() xor data[16 + i].toInt())
        if (diff != 0) return null

        return Beacon(
            version = data[0].toInt(),
            groupId = groupId,
            deviceId = readInt16(data, 5),
            flags = data[7].toInt() and 0xFF,
            txPowerRef = data[8].toInt(),          // signed on purpose
            battery = data[9].toInt() and 0xFF,
            seq = readInt16(data, 10),
            eventType = data[12].toInt() and 0xFF,
            subjectId = readInt16(data, 13),
            motionScore = data[15].toInt() and 0xFF,
        )
    }

    // ---- name chunking ---------------------------------------------------

    /** Trims a display name down to what the wire format can carry. */
    fun normaliseName(name: String): String =
        name.filter { it.code in 32..126 }.trim().take(NAME_MAX_LENGTH)

    /**
     * Builds the `subjectId` / `motionScore` field values that carry
     * [chunkIndex] of [name]. Returns `subjectId to motionScore`.
     */
    fun encodeNameChunk(name: String, chunkIndex: Int): Pair<Int, Int> {
        val padded = normaliseName(name).padEnd(NAME_MAX_LENGTH, ' ')
        val c0 = padded[chunkIndex * 2].code and 0xFF
        val c1 = padded[chunkIndex * 2 + 1].code and 0xFF
        return (((chunkIndex and 0xFF) shl 8) or c0) to c1
    }

    /** Reassembles names from [EVENT_NAME] beacons, one sender at a time. */
    class NameAssembler {
        private val buffers = HashMap<Int, CharArray>()

        /** @return the name once every chunk has arrived, else null. */
        fun accept(beacon: Beacon): String? {
            if (beacon.eventType != EVENT_NAME) return null
            val chunkIndex = (beacon.subjectId ushr 8) and 0xFF
            if (chunkIndex >= NAME_CHUNKS) return null
            val buffer = buffers.getOrPut(beacon.deviceId) { CharArray(NAME_MAX_LENGTH) }
            buffer[chunkIndex * 2] = (beacon.subjectId and 0xFF).toChar()
            buffer[chunkIndex * 2 + 1] = (beacon.motionScore and 0xFF).toChar()
            // A freshly allocated CharArray is all NUL, and real chunks are
            // filtered to printable ASCII, so a remaining NUL means "still
            // waiting for that chunk".
            if (buffer.any { it.code == 0 }) return null
            val name = String(buffer).trim()
            return name.ifEmpty { null }
        }

        fun forget(deviceId: Int) {
            buffers.remove(deviceId)
        }
    }

    // ---- little helpers --------------------------------------------------

    private fun hmac(key: ByteArray, data: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(data, 0, length)
        return mac.doFinal()
    }

    private fun writeInt32(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value ushr 24).toByte()
        dst[offset + 1] = (value ushr 16).toByte()
        dst[offset + 2] = (value ushr 8).toByte()
        dst[offset + 3] = value.toByte()
    }

    private fun readInt32(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 24) or
            ((src[offset + 1].toInt() and 0xFF) shl 16) or
            ((src[offset + 2].toInt() and 0xFF) shl 8) or
            (src[offset + 3].toInt() and 0xFF)

    private fun writeInt16(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value ushr 8).toByte()
        dst[offset + 1] = value.toByte()
    }

    private fun readInt16(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 8) or (src[offset + 1].toInt() and 0xFF)
}

/** One decoded advertisement from a group member. */
data class Beacon(
    val version: Int,
    val groupId: Int,
    val deviceId: Int,
    val flags: Int,
    val txPowerRef: Int,
    val battery: Int,
    val seq: Int,
    val eventType: Int,
    val subjectId: Int,
    val motionScore: Int,
) {
    val armed: Boolean get() = flags and Protocol.FLAG_ARMED != 0
    val alarming: Boolean get() = flags and Protocol.FLAG_ALARMING != 0
    val stationary: Boolean get() = flags and Protocol.FLAG_STATIONARY != 0
    val boxGuardian: Boolean get() = flags and Protocol.FLAG_BOX_GUARDIAN != 0
    val lowBattery: Boolean get() = flags and Protocol.FLAG_LOW_BATTERY != 0
    val charging: Boolean get() = flags and Protocol.FLAG_CHARGING != 0
    val pending: Boolean get() = flags and Protocol.FLAG_PENDING != 0
    val simulated: Boolean get() = flags and Protocol.FLAG_SIMULATED != 0

    /** True when this packet's bytes 13..15 are name characters, not telemetry. */
    val carriesName: Boolean get() = eventType == Protocol.EVENT_NAME

    /** True when [motionScore] holds a counter rather than telemetry. */
    val carriesCounter: Boolean get() = Protocol.carriesCounter(eventType)

    /** True when [motionScore] means what it says. */
    val carriesTelemetry: Boolean get() = !carriesName && !carriesCounter

    /**
     * Which announcement this is: the counter half of `(origin, counter)`.
     *
     * There is no room for a field of its own in twenty bytes, so the motion
     * score is borrowed — exactly as it is for name chunks, and safe for the
     * same reason: `FLAG_STATIONARY`, which is what the occlusion gate actually
     * keys on, still travels untouched in the flags byte, and the receiver keeps
     * the motion score it already had. Only meaningful when [carriesCounter].
     */
    val counter: Int get() = motionScore
}
