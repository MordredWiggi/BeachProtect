package com.beachprotect.ble

/**
 * The identity and sequence state a beacon needs.
 *
 * Narrowed to an interface purely so [BeaconComposer] can be exercised by plain
 * JUnit: what goes into the event slot, and — much more to the point — what
 * *stops* going into it when an incident ends, is a rule worth a test rather
 * than a field trip. `GuardStore` is the real implementation.
 */
interface BeaconSource {
    val groupId: Int
    val deviceId: Int
    val groupKey: ByteArray
    val txPowerRef: Int
    val sequenceBlockSize: Int
    fun beginSequenceBlock(): Int
}

/**
 * Decides what goes into the single event slot of each outgoing beacon.
 *
 * There are more things worth saying than there is room to say them - alarms,
 * consensus votes, group commands and name fragments all compete for three
 * bytes - so this class arbitrates by urgency and round-robins within each
 * tier. Nothing is ever sent "extra": the beacon goes out anyway, this only
 * chooses its contents.
 */
class BeaconComposer(private val store: BeaconSource) {

    private var sequence = 0
    private var sequenceLimit = 0

    private var controlEvent: Int = Protocol.EVENT_NONE
    private var controlSubject: Int = Protocol.DEVICE_ID_NONE
    private var controlCounter: Int = 0
    private var controlUntil: Long = 0

    private var secondaryCursor = 0
    private var nameCursor = 0
    private var controlAlternates = false

    /**
     * Queues a group command, repeated for [durationMs] so that a peer whose scan
     * window happened to be closed still hears it.
     *
     * [durationMs] is now an upper bound rather than the plan: the caller clears
     * the slot the moment every phone it can hear has confirmed the command, so
     * this is only what happens when somebody does not answer.
     *
     * @param durationMs [CONTROL_REPEAT_MS] when this phone is the one deciding,
     *        [CONTROL_RELAY_MS] when it is passing somebody else's decision on.
     */
    fun queueControl(
        now: Long,
        eventType: Int,
        subjectId: Int,
        commandCounter: Int,
        durationMs: Long = CONTROL_REPEAT_MS,
    ) {
        controlEvent = eventType
        controlSubject = subjectId
        controlCounter = commandCounter
        controlUntil = now + durationMs
        controlAlternates = false
    }

    /** True while a group command is still being repeated. */
    fun controlPending(now: Long): Boolean =
        now < controlUntil && controlEvent != Protocol.EVENT_NONE

    /** What is queued, so a caller can tell whether it has been superseded. */
    val pendingControlEvent: Int get() = controlEvent

    /**
     * Which device *issued* what is queued.
     *
     * Lets the caller tell its own announcement, which it may stop as soon as
     * everybody has confirmed, from a relay of somebody else's, which runs its
     * own short window out.
     */
    val pendingControlSubject: Int get() = controlSubject

    fun clearControl() {
        controlEvent = Protocol.EVENT_NONE
        controlSubject = Protocol.DEVICE_ID_NONE
        controlCounter = 0
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
        //
        //    Note what this is *not*: a queued control message. The alarm is
        //    derived from the guard's live state on every single beacon, so it
        //    leaves the air the instant the alarm does. Queuing it - which is
        //    what used to happen alongside this, through the same path as a
        //    group command - meant a phone went on shouting "theft!" for the
        //    full repeat window after its siren had been silenced. Every other
        //    phone heard an incident that no longer existed: the group-alarm
        //    banner came back by itself, and once the echo window lapsed a phone
        //    could be dragged into a real siren by a packet describing something
        //    that had ended half a minute earlier.
        //    The counter rides in the motion byte, exactly as it does for a
        //    group command, and for the same reason: it turns "an alarm" into
        //    *this* alarm, which is what everybody else acknowledges and what
        //    lets a phone tell an echo of an incident it has already stood down
        //    from a fresh one about the same phone.
        if (input.alarming) {
            return Triple(input.alarmEvent, input.alarmSubject, input.alarmCounter)
        }

        // 2. Group commands - "arm everyone", "everybody stop" - repeated for
        //    long enough that a phone scanning at the calm duty cycle cannot
        //    miss the whole burst. That is not a comfort margin: LOW_POWER is a
        //    512 ms window every 5.12 s, so a four second announcement could
        //    fall entirely between two windows, and "arm all" simply did not
        //    reach the other phone about half the time.
        //
        //    Acknowledgements and votes are interleaved rather than starved: a
        //    suspicion in flight when somebody presses a button still gets aired
        //    every other packet, and so does a confirmation somebody else is
        //    waiting on before *they* can stop transmitting.
        val secondary = secondaryEvents(input)
        if (controlPending(input.now)) {
            controlAlternates = !controlAlternates
            if (controlAlternates || secondary.isEmpty()) {
                // The counter rides in the motion-score byte, which is what
                // makes this copy identifiable as one particular press of one
                // particular button rather than merely "a disarm-all".
                return Triple(controlEvent, controlSubject, controlCounter)
            }
        }

        // 3. Acknowledgements and consensus votes, rotating so that several of
        //    each all get aired rather than the first one starving the rest.
        if (secondary.isNotEmpty()) {
            if (secondaryCursor >= secondary.size) secondaryCursor = 0
            val next = secondary[secondaryCursor]
            secondaryCursor = (secondaryCursor + 1) % secondary.size
            return next
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

    /**
     * The middle tier: things that are urgent but not decisions of our own.
     *
     * Acknowledgements come first within the tier. A vote is one observer's
     * opinion, repeated for as long as it holds; an acknowledgement is what
     * another phone is *waiting on* before it can stop transmitting at all, so
     * delivering it promptly quietens the whole group.
     */
    private fun secondaryEvents(input: Input): List<Triple<Int, Int, Int?>> {
        if (input.acks.isEmpty() && input.votes.isEmpty()) return emptyList()
        val out = ArrayList<Triple<Int, Int, Int?>>(input.acks.size + input.votes.size)
        input.acks.forEach { (origin, counter) ->
            out.add(Triple(Protocol.EVENT_ACK, origin, counter))
        }
        input.votes.forEach { (event, subject) -> out.add(Triple(event, subject, null)) }
        return out
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

        /** Which incident of ours the alarm is; see [Protocol.EVENT_ALARM]. */
        val alarmCounter: Int = 0,

        val votes: List<Pair<Int, Int>> = emptyList(),

        /** Announcements heard from others and owed a confirmation, `origin to counter`. */
        val acks: List<Pair<Int, Int>> = emptyList(),

        val allowName: Boolean = false,
        val name: String = "",
    )

    companion object {
        /**
         * The longest a queued group command keeps being repeated *unanswered*.
         *
         * This used to be the plan rather than the ceiling, and the whole
         * twenty-five seconds was paid every single time: sized against the
         * slowest imaginable listener, because nothing could tell the sender
         * whether anybody had actually heard. The cost was not battery, it was
         * *staleness* — half a minute of packets describing a decision that had
         * already landed everywhere, arriving at phones that had long since acted
         * on it and could not tell the difference between a repeat and news.
         *
         * With confirmations (`Protocol.EVENT_ACK`) the caller clears the slot as
         * soon as every phone it can hear has answered, normally inside two
         * seconds. This is now only what happens when somebody does not answer at
         * all, which is exactly the case it was designed for.
         *
         * Two other things carry the same load: the sender lifts its advertising
         * rate for the duration (see `GuardService.announcing`), because the
         * listener's duty cycle is the one thing it cannot change, and every
         * phone that hears a command passes it on (see
         * `EngineListener.onRelayGroupCommand`).
         */
        const val CONTROL_REPEAT_MS = 25_000L

        /**
         * How long a phone repeats somebody *else's* command.
         *
         * Shorter than the issuer's own window: the point of a relay is to reach
         * phones the issuer cannot, which happens in the first few seconds, and a
         * whole group holding its radios up for half a minute each would turn one
         * button press into a measurable dent in the afternoon.
         */
        const val CONTROL_RELAY_MS = 6_000L
    }
}
