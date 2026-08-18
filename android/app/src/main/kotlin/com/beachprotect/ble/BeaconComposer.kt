package com.beachprotect.ble

import com.beachprotect.store.GuardStore

/**
 * Decides what goes into the single event slot of each outgoing beacon.
 *
 * There are more things worth saying than there is room to say them - alarms,
 * consensus votes, group commands and name fragments all compete for three
 * bytes - so this class arbitrates by urgency and round-robins within each
 * tier. Nothing is ever sent "extra": the beacon goes out anyway, this only
 * chooses its contents.
 */
class BeaconComposer(private val store: GuardStore) {

    private var sequence = 0
    private var sequenceLimit = 0

    private var controlEvent: Int = Protocol.EVENT_NONE
    private var controlSubject: Int = Protocol.DEVICE_ID_NONE
    private var controlUntil: Long = 0

    private var voteCursor = 0
    private var nameCursor = 0
    private var controlAlternates = false

    /**
     * Queues a group command. Repeated for [CONTROL_REPEAT_MS] so that a peer
     * whose scan window happened to be closed still hears it.
     */
    fun queueControl(now: Long, eventType: Int, subjectId: Int) {
        controlEvent = eventType
        controlSubject = subjectId
        controlUntil = now + CONTROL_REPEAT_MS
        controlAlternates = false
    }

    /** True while a group command is still being repeated. */
    fun controlPending(now: Long): Boolean =
        now < controlUntil && controlEvent != Protocol.EVENT_NONE

    fun clearControl() {
        controlEvent = Protocol.EVENT_NONE
        controlSubject = Protocol.DEVICE_ID_NONE
        controlUntil = 0
    }

    fun compose(input: Input): ByteArray {
        val (eventType, subjectId, motionOverride) = chooseEvent(input)
        val beacon = Beacon(
            version = Protocol.VERSION,
            groupId = store.groupId,
            deviceId = store.deviceId,
            flags = input.flags,
            txPowerRef = store.txPowerRef,
            battery = input.battery,
            seq = nextSequence(),
            eventType = eventType,
            subjectId = subjectId,
            motionScore = motionOverride ?: input.motionScore,
        )
        return Protocol.encode(beacon, store.groupKey)
    }

    /** @return event type, subject field, and an override for the motion byte. */
    private fun chooseEvent(input: Input): Triple<Int, Int, Int?> {
        // 1. An active alarm outranks everything and is repeated continuously,
        //    because a phone that joins late still has to start screaming.
        if (input.alarming) {
            return Triple(input.alarmEvent, input.alarmSubject, null)
        }

        // 2. Group commands - "arm everyone", "everybody stop" - repeated for
        //    long enough that a phone scanning at the calm duty cycle cannot
        //    miss the whole burst. That is not a comfort margin: LOW_POWER is a
        //    512 ms window every 5.12 s, so a four second announcement could
        //    fall entirely between two windows, and "arm all" simply did not
        //    reach the other phone about half the time.
        //
        //    Votes are interleaved rather than starved: a suspicion in flight
        //    when somebody presses a button still gets aired every other packet.
        if (controlPending(input.now)) {
            controlAlternates = !controlAlternates
            if (controlAlternates || input.votes.isEmpty()) {
                return Triple(controlEvent, controlSubject, null)
            }
        }

        // 3. Consensus votes, rotating so several suspects all get aired.
        if (input.votes.isNotEmpty()) {
            if (voteCursor >= input.votes.size) voteCursor = 0
            val vote = input.votes[voteCursor]
            voteCursor = (voteCursor + 1) % input.votes.size
            return Triple(vote.first, vote.second, null)
        }

        // 4. Otherwise spend the idle slot publishing our name, two characters
        //    at a time. Only while calm and still: these packets carry no
        //    motion score, so they must never displace real telemetry.
        if (input.allowName && input.name.isNotEmpty()) {
            val chunk = nameCursor % Protocol.NAME_CHUNKS
            nameCursor = (nameCursor + 1) % Protocol.NAME_CHUNKS
            val (subjectField, charField) = Protocol.encodeNameChunk(input.name, chunk)
            return Triple(Protocol.EVENT_NAME, subjectField, charField)
        }

        return Triple(Protocol.EVENT_NONE, Protocol.DEVICE_ID_NONE, null)
    }

    private fun nextSequence(): Int {
        if (sequence >= sequenceLimit) {
            sequence = store.beginSequenceBlock()
            sequenceLimit = sequence + store.sequenceBlockSize
        }
        return (sequence++) and 0xFFFF
    }

    data class Input(
        val now: Long,
        val flags: Int,
        val motionScore: Int,
        val battery: Int,
        val alarming: Boolean,
        val alarmEvent: Int,
        val alarmSubject: Int,
        val votes: List<Pair<Int, Int>>,
        val allowName: Boolean,
        val name: String,
    )

    companion object {
        /**
         * How long a queued group command keeps being repeated.
         *
         * Sized against the slowest listener rather than against how long the
         * command "feels" like it should take: two full LOW_POWER scan cycles
         * plus margin, so every phone in the group gets several chances to hear
         * it. The sender also lifts its advertising rate for the duration (see
         * `GuardService.announcing`), which is the other half of making these
         * commands dependable.
         */
        const val CONTROL_REPEAT_MS = 12_000L
    }
}
