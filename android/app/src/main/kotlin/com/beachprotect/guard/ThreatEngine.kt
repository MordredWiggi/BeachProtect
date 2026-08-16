package com.beachprotect.guard

import com.beachprotect.ble.Beacon
import com.beachprotect.ble.Protocol
import kotlin.math.max

/**
 * The decision maker.
 *
 * Deliberately free of Android imports: every rule in here is exercised by
 * plain JUnit tests (`ThreatEngineTest`) with a fake clock, because "does it
 * alarm when it should and stay quiet when it shouldn't" is the one thing that
 * cannot be verified by staring at a phone on a towel.
 *
 * ## How a theft is distinguished from a passer-by
 *
 * Two independent signals must agree:
 *
 *  1. **The victim's own accelerometer.** Each phone broadcasts whether it is
 *     currently stationary. A phone lying on a towel says "stationary" once a
 *     second, for hours, at essentially no energy cost (the wait is done by the
 *     hardware significant-motion sensor).
 *
 *  2. **Radio distance, seen by the others.** Each phone tracks a filtered RSSI
 *     per peer against a learned baseline.
 *
 * A person walking between two phones drops RSSI hard - 10 to 20 dB - but the
 * victim is still lying on the towel reporting "stationary", and the notch is
 * symmetric and brief. Both facts independently veto the alarm: the gate at
 * [observePeers] requires the victim to report motion, and [EngineConfig.sustainMs]
 * requires the drop to persist with a negative trend.
 *
 * A phone actually being carried away reports motion *and* produces a sustained,
 * monotonic drop at every other phone in the group. To go from "one flaky radio"
 * to "this is real", [EngineConfig.requiredObservers] separate devices must agree
 * before anything makes noise.
 *
 * ## Failure modes that are handled explicitly
 *
 * - Thief switches the phone off or bags it: it vanishes from the mesh, and
 *   observers vote [VoteType.LOST] instead.
 * - Victim's battery died: the peer broadcast its battery level right up to the
 *   end, so a vanish at 3% is reported as a warning rather than a theft.
 * - *This* phone is the one being carried: observers can't help if the thief is
 *   fast, so the victim also self-detects and runs its own grace countdown.
 * - The observer itself is walking around: it stops voting entirely while it is
 *   moving, because it cannot tell "you left" from "I left".
 */
class ThreatEngine(
    private val selfDeviceId: Int,
    private val listener: EngineListener,
    config: EngineConfig = EngineConfig(),
) {

    var config: EngineConfig = config
        set(value) {
            field = value
            // Thresholds changed underneath us; drop in-flight suspicion so the
            // new values are applied from a clean slate rather than half-way
            // through an evaluation that used the old ones.
            peers.values.forEach { it.dropStartedAt = 0L }
        }

    var selfName: String = ""

    // ---- state ----------------------------------------------------------

    var state: GuardState = GuardState.DISARMED
        private set

    private val peers = LinkedHashMap<Int, PeerRecord>()
    private val nameAssembler = Protocol.NameAssembler()

    /** subject id -> observer id -> vote. Our own votes live in here too. */
    private val votes = HashMap<Int, HashMap<Int, VoteRecord>>()

    /** Last control-event sequence number accepted from each sender. */
    private val lastControlSeq = HashMap<Int, Int>()

    private var armedSince = 0L
    private var pendingSince = 0L
    private var alarmSince = 0L

    /**
     * Exposed read-only so the service can compose a beacon without paying for
     * a full [snapshot] allocation, which it would otherwise do once a second
     * for the radio and again for the UI.
     */
    var alarmReason: AlarmReason? = null
        private set

    var alarmSubject = Protocol.DEVICE_ID_NONE
        private set
    private var lastEvidenceAt = 0L
    private var radioProfile = RadioProfile.CALM
    private var warnings = emptySet<GuardWarning>()

    private var selfStationary = true
    private var selfMotionScore = 0

    /**
     * When this phone last came to rest, or [NEVER] while it is moving.
     *
     * Deliberately not zero-as-sentinel: `elapsedRealtime` is genuinely zero
     * for the first millisecond after boot, and more importantly the unit tests
     * start their clock at zero, so a magic 0 would silently mean "never".
     */
    private var selfStillSince = NEVER
    private var significantMotionPendingAt = 0L

    // ---- box ------------------------------------------------------------

    private var boxConfigured = false
    private var boxName: String? = null
    private var boxAddress: String? = null
    private var boxGuardedHere = false
    private var boxLinkConnected = false
    private var boxDisconnectedAt = 0L
    private var boxAlarmFired = false
    private val boxRssi = RssiKalman()
    private var boxLastSeenAt = 0L
    private var boxBaseline = RollingMedian(48)

    // =====================================================================
    // Commands
    // =====================================================================

    fun arm(now: Long) {
        if (state != GuardState.DISARMED) return
        armedSince = now
        peers.values.forEach { it.resetBaseline() }
        boxBaseline.clear()
        votes.clear()
        boxAlarmFired = false
        transition(now, GuardState.CALIBRATING)
    }

    fun disarm(now: Long) {
        if (state == GuardState.ALARM) {
            listener.onAlarmCleared()
        }
        alarmReason = null
        alarmSubject = Protocol.DEVICE_ID_NONE
        alarmSince = 0L
        pendingSince = 0L
        armedSince = 0L
        votes.clear()
        boxAlarmFired = false
        significantMotionPendingAt = 0L
        transition(now, GuardState.DISARMED)
    }

    /** Stop the siren but keep protecting; baselines are relearned. */
    fun clearAlarm(now: Long) {
        if (state != GuardState.ALARM && state != GuardState.PENDING) return
        listener.onAlarmCleared()
        alarmReason = null
        alarmSubject = Protocol.DEVICE_ID_NONE
        alarmSince = 0L
        pendingSince = 0L
        votes.clear()
        boxAlarmFired = false
        peers.values.forEach { it.resetBaseline() }
        boxBaseline.clear()
        armedSince = now
        transition(now, GuardState.CALIBRATING)
    }

    fun panic(now: Long) {
        raiseAlarm(now, AlarmReason.PANIC, selfDeviceId)
    }

    /** Used by the simulator and the in-app self test. */
    fun triggerTestAlarm(now: Long) {
        raiseAlarm(now, AlarmReason.TEST, selfDeviceId)
    }

    fun configureBox(configured: Boolean, name: String?, address: String?, guardedHere: Boolean) {
        boxConfigured = configured
        boxName = name
        boxAddress = address
        boxGuardedHere = guardedHere
        if (!configured) {
            boxLinkConnected = false
            boxAlarmFired = false
            boxRssi.reset()
            boxBaseline.clear()
        }
    }

    fun forgetPeer(deviceId: Int) {
        peers.remove(deviceId)
        votes.remove(deviceId)
        votes.values.forEach { it.remove(deviceId) }
        lastControlSeq.remove(deviceId)
        nameAssembler.forget(deviceId)
    }

    // =====================================================================
    // Inputs
    // =====================================================================

    /** One authenticated advertisement from a group member. */
    fun onPeerBeacon(now: Long, rssi: Int, beacon: Beacon) {
        if (beacon.deviceId == selfDeviceId) return

        val peer = peers.getOrPut(beacon.deviceId) { PeerRecord(beacon.deviceId) }
        peer.lastSeenAt = now
        peer.flags = beacon.flags
        peer.battery = beacon.battery
        peer.txPowerRef = beacon.txPowerRef
        peer.simulated = beacon.simulated

        // Name packets reuse the telemetry bytes, so only take a motion score
        // from packets that actually carry one.
        if (!beacon.carriesName) {
            peer.motionScore = beacon.motionScore
        } else {
            nameAssembler.accept(beacon)?.let { peer.name = it }
        }

        val filtered = peer.kalman.update(rssi.toDouble())
        peer.trend.add(now, filtered)

        // The baseline is only allowed to learn while everything is calm and
        // both ends are still. Learning during an incident would let the
        // detector talk itself out of a real theft.
        val calmForLearning = (state == GuardState.CALIBRATING || state == GuardState.ARMED) &&
            selfStationary && beacon.stationary && peer.dropStartedAt == 0L
        if (calmForLearning) {
            peer.baselineWindow.add(filtered)
            peer.baseline = peer.baselineWindow.median()
        }

        handleIncomingEvent(now, beacon)
    }

    fun onSelfMotion(now: Long, signal: MotionSignal) {
        when (signal) {
            is MotionSignal.SignificantMotion -> {
                significantMotionPendingAt = now
                if (isPickupDetectorArmed(now)) {
                    beginPending(now)
                } else if (state == GuardState.ARMED) {
                    noteEvidence(now)
                }
            }

            is MotionSignal.Level -> {
                selfMotionScore = signal.score
                val wasStationary = selfStationary
                selfStationary = signal.stationary
                if (signal.stationary) {
                    if (!wasStationary || selfStillSince == NEVER) selfStillSince = now
                } else {
                    // Order matters: the pickup detector's readiness is derived
                    // from selfStillSince, so it has to be evaluated *before*
                    // the phone is marked as moving. Clearing it first made this
                    // whole branch dead code, and left the slow hardware
                    // significant-motion sensor as the only way to ever trigger.
                    val ready = isPickupDetectorArmed(now)
                    selfStillSince = NEVER
                    if (ready && signal.score >= config.motionScoreThreshold) {
                        beginPending(now)
                    }
                }
            }
        }
    }

    fun onBoxSignal(now: Long, signal: BoxSignal) {
        if (!boxConfigured) return
        when (signal) {
            is BoxSignal.Connected -> {
                boxLinkConnected = true
                boxDisconnectedAt = 0L
            }

            is BoxSignal.Disconnected -> {
                if (boxLinkConnected) boxDisconnectedAt = now
                boxLinkConnected = false
            }

            is BoxSignal.Rssi -> {
                boxLastSeenAt = now
                val filtered = boxRssi.update(signal.rssi.toDouble())
                if (state == GuardState.CALIBRATING || state == GuardState.ARMED) {
                    boxBaseline.add(filtered)
                }
            }
        }
    }

    // =====================================================================
    // Periodic evaluation
    // =====================================================================

    fun tick(now: Long) {
        pruneVotes(now)
        prunePeers(now)

        when (state) {
            GuardState.DISARMED -> Unit

            GuardState.CALIBRATING -> {
                // Calibration exists to let per-peer baselines settle. With
                // nobody else around there is nothing to settle, so waiting the
                // full window would just leave the phone unprotected for no
                // reason - which is exactly what happens on a lone phone.
                val nothingToLearn = peers.isEmpty() &&
                    now - armedSince >= SOLO_CALIBRATION_MS
                if (nothingToLearn || now - armedSince >= config.calibrationMs) {
                    transition(now, GuardState.ARMED)
                }
            }

            GuardState.ARMED, GuardState.SUSPICIOUS -> {
                observePeers(now)
                observeBox(now)
                evaluateConsensus(now)
                relaxSuspicionIfQuiet(now)
            }

            GuardState.PENDING -> {
                observePeers(now)
                observeBox(now)
                evaluateConsensus(now)
                evaluatePending(now)
            }

            GuardState.ALARM -> {
                observePeers(now)
                observeBox(now)
            }
        }

        recomputeWarnings(now)
        updateRadioProfile()
    }

    // ---- observer side ---------------------------------------------------

    /**
     * Looks at every peer and decides whether to cast a vote about it.
     *
     * This is where occlusion is separated from theft.
     */
    private fun observePeers(now: Long) {
        // A moving observer cannot tell "you walked away" from "I walked away",
        // so it abstains entirely rather than voting badly.
        if (!selfStationary) {
            peers.values.forEach { it.dropStartedAt = 0L }
            retractOwnVotes()
            return
        }

        for (peer in peers.values) {
            if (!peer.armed) {
                peer.dropStartedAt = 0L
                clearOwnVote(peer.deviceId)
                continue
            }

            val silentFor = now - peer.lastSeenAt
            if (silentFor >= config.lostTimeoutMs) {
                castOwnVote(now, peer.deviceId, VoteType.LOST)
                noteEvidence(now)
                continue
            }

            if (!peer.kalman.initialised || peer.baseline.isNaN()) continue

            val drop = peer.baseline - peer.kalman.value
            peer.lastDrop = drop
            val slope = peer.trend.slopePerSecond()
            peer.lastSlope = slope

            // ---- the fusion gate -------------------------------------
            // The peer's own accelerometer has to agree that it is moving.
            // Without this single condition, every person walking past the
            // towel would set off the siren.
            val peerMoving = !peer.stationary || peer.motionScore >= config.motionScoreThreshold
            val dropping = drop >= config.dropThresholdDb

            if (!peerMoving) {
                // The victim is demonstrably lying still, so a drop can only be
                // occlusion or fading - never theft. Note that we deliberately
                // do *not* escalate the radios here: on a busy beach people
                // walk past constantly, and reacting to every one of them would
                // hold the scanner at high duty all afternoon for nothing.
                if (dropping) peer.occlusionSuppressions++
                peer.dropStartedAt = 0L
                clearOwnVote(peer.deviceId)
                continue
            }

            // The negative-slope test gates the *start* of an episode, not its
            // continuation. A thief who walks twenty metres and then stops
            // produces a flat slope again, but the signal stays down - and that
            // must still count. Once an episode has begun it ends only when the
            // signal genuinely comes back (the reset below).
            val episodeRunning = peer.dropStartedAt != 0L
            if (dropping && (episodeRunning || slope <= config.minNegativeSlope)) {
                if (peer.dropStartedAt == 0L) peer.dropStartedAt = now
                noteEvidence(now)
                val needed = if (drop >= config.fastPathDropDb) config.sustainMs / 2 else config.sustainMs
                if (now - peer.dropStartedAt >= needed) {
                    castOwnVote(now, peer.deviceId, VoteType.SUSPECT)
                }
            } else {
                if (drop < config.dropThresholdDb * 0.6) {
                    // Comfortably back to normal - forget the whole episode.
                    peer.dropStartedAt = 0L
                    clearOwnVote(peer.deviceId)
                } else {
                    // Moving peer, signal softening but not yet conclusive.
                    noteEvidence(now)
                }
            }
        }
    }

    private fun observeBox(now: Long) {
        if (!boxConfigured || !boxGuardedHere || boxAlarmFired) return

        if (!boxLinkConnected && boxDisconnectedAt != 0L &&
            now - boxDisconnectedAt >= config.boxDisconnectDebounceMs
        ) {
            boxAlarmFired = true
            raiseAlarm(now, AlarmReason.BOX_TAKEN, Protocol.DEVICE_ID_NONE)
            return
        }

        // BLE tracking, when the box also advertises, gives a graded warning
        // well before the audio link actually gives up.
        if (boxRssi.initialised && boxBaseline.size >= 8) {
            val drop = boxBaseline.median() - boxRssi.value
            if (drop >= config.dropThresholdDb) noteEvidence(now)
        }
    }

    // ---- consensus --------------------------------------------------------

    private fun evaluateConsensus(now: Long) {
        if (state == GuardState.ALARM) return

        for ((subject, observers) in votes) {
            if (observers.isEmpty()) continue
            val required = requiredObserversFor(subject)
            if (observers.size < required) continue

            val lostVotes = observers.values.count { it.type == VoteType.LOST }
            val reason = if (lostVotes > observers.size / 2) AlarmReason.PEER_LOST
            else AlarmReason.THEFT_CONSENSUS

            // A peer that told us it was nearly flat before going quiet gets the
            // benefit of the doubt: that is a dead battery, not a thief.
            if (reason == AlarmReason.PEER_LOST) {
                val peer = peers[subject]
                if (peer != null && peer.battery in 1..config.lowBatteryPercent) {
                    addWarning(GuardWarning.PEER_LOST_LIKELY_BATTERY)
                    continue
                }
            }

            raiseAlarm(now, reason, subject)
            return
        }
    }

    /**
     * How many separate devices must agree, given how many could possibly see
     * the subject at all.
     *
     * The requirement scales with the size of the group rather than being a
     * fixed count: one agreeing phone out of two is convincing, one out of nine
     * probably is not. See [EngineConfig.observersRequiredFor].
     */
    private fun requiredObserversFor(subject: Int): Int {
        val potential = peers.keys.count { it != subject && peers[it]?.armed == true } +
            if (subject != selfDeviceId) 1 else 0
        return config.observersRequiredFor(max(1, potential))
    }

    private fun castOwnVote(now: Long, subject: Int, type: VoteType) {
        val observers = votes.getOrPut(subject) { HashMap() }
        val existing = observers[selfDeviceId]
        observers[selfDeviceId] = VoteRecord(type, now)
        if (existing == null || existing.type != type) {
            noteEvidence(now)
        }
    }

    private fun clearOwnVote(subject: Int) {
        votes[subject]?.remove(selfDeviceId)
    }

    private fun retractOwnVotes() {
        votes.values.forEach { it.remove(selfDeviceId) }
    }

    private fun pruneVotes(now: Long) {
        val subjectIterator = votes.entries.iterator()
        while (subjectIterator.hasNext()) {
            val entry = subjectIterator.next()
            entry.value.entries.removeAll { now - it.value.atMs > config.voteTtlMs }
            if (entry.value.isEmpty()) subjectIterator.remove()
        }
    }

    private fun prunePeers(now: Long) {
        val gone = peers.values.filter { now - it.lastSeenAt > PEER_FORGET_MS }
        gone.forEach { forgetPeer(it.deviceId) }
    }

    /** Votes this device currently wants to broadcast, newest subject first. */
    fun activeVotes(now: Long): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for ((subject, observers) in votes) {
            val mine = observers[selfDeviceId] ?: continue
            if (now - mine.atMs > config.voteTtlMs) continue
            val eventType = when (mine.type) {
                VoteType.SUSPECT -> Protocol.EVENT_SUSPECT
                VoteType.LOST -> Protocol.EVENT_LOST
            }
            out.add(eventType to subject)
        }
        return out
    }

    // ---- victim side ------------------------------------------------------

    /**
     * The pickup detector only counts once the phone has actually been put down
     * and left alone, so that arming while still holding the phone cannot
     * immediately trip it.
     */
    private fun isPickupDetectorArmed(now: Long): Boolean {
        if (state != GuardState.ARMED && state != GuardState.SUSPICIOUS) return false
        if (selfStillSince == NEVER) return false
        return now - selfStillSince >= config.settleMs
    }

    private fun beginPending(now: Long) {
        if (state == GuardState.PENDING || state == GuardState.ALARM) return
        pendingSince = now
        transition(now, GuardState.PENDING)
        listener.onPendingCountdown(config.pickupGraceMs)
    }

    private fun evaluatePending(now: Long) {
        val elapsed = now - pendingSince
        val remaining = config.pickupGraceMs - elapsed

        // Corroboration from the group cuts the countdown short: if the others
        // can already see this phone receding, waiting is pointless.
        val corroborated = (votes[selfDeviceId]?.size ?: 0) >= requiredObserversFor(selfDeviceId)
        if (corroborated) {
            raiseAlarm(now, AlarmReason.THEFT_CONSENSUS, selfDeviceId)
            return
        }

        if (remaining > 0) {
            listener.onPendingCountdown(remaining)
            return
        }

        if (config.alarmOnPickupAlone) {
            raiseAlarm(now, AlarmReason.PICKUP_UNCONFIRMED, selfDeviceId)
        } else if (selfStationary) {
            // Put back down, nobody corroborated: treat it as a false start and
            // relearn the baselines from wherever the phone now lies.
            pendingSince = 0L
            armedSince = now
            peers.values.forEach { it.resetBaseline() }
            transition(now, GuardState.CALIBRATING)
        } else {
            raiseAlarm(now, AlarmReason.PICKUP_UNCONFIRMED, selfDeviceId)
        }
    }

    // ---- incoming control events -----------------------------------------

    private fun handleIncomingEvent(now: Long, beacon: Beacon) {
        when (beacon.eventType) {
            Protocol.EVENT_SUSPECT, Protocol.EVENT_LOST -> {
                if (beacon.subjectId == Protocol.DEVICE_ID_NONE) return
                val type = if (beacon.eventType == Protocol.EVENT_SUSPECT) {
                    VoteType.SUSPECT
                } else {
                    VoteType.LOST
                }
                votes.getOrPut(beacon.subjectId) { HashMap() }[beacon.deviceId] =
                    VoteRecord(type, now)
                noteEvidence(now)
            }

            Protocol.EVENT_ALARM -> {
                if (!acceptControl(beacon)) return
                if (state != GuardState.ALARM) {
                    raiseAlarm(now, AlarmReason.RELAYED, beacon.subjectId)
                }
            }

            Protocol.EVENT_BOX_ALARM -> {
                if (!acceptControl(beacon)) return
                if (state != GuardState.ALARM) {
                    raiseAlarm(now, AlarmReason.BOX_TAKEN, Protocol.DEVICE_ID_NONE)
                }
            }

            Protocol.EVENT_PANIC -> {
                if (!acceptControl(beacon)) return
                if (state != GuardState.ALARM) {
                    raiseAlarm(now, AlarmReason.PANIC, beacon.subjectId)
                }
            }

            Protocol.EVENT_ALARM_CLEAR -> {
                if (!acceptControl(beacon)) return
                if (state == GuardState.ALARM || state == GuardState.PENDING) clearAlarm(now)
            }

            Protocol.EVENT_DISARM_ALL -> {
                if (!acceptControl(beacon)) return
                if (state != GuardState.DISARMED) disarm(now)
            }

            Protocol.EVENT_ARM_ALL -> {
                if (!acceptControl(beacon)) return
                if (state == GuardState.DISARMED) arm(now)
            }
        }
    }

    /**
     * Replay protection for state-changing events.
     *
     * The HMAC proves a packet came from the group; the monotonic sequence
     * number proves it is not a recording of an older one. Without this, an
     * attacker could capture a "disarm everyone" from packing-up time and
     * replay it the next afternoon.
     */
    private fun acceptControl(beacon: Beacon): Boolean {
        val last = lastControlSeq[beacon.deviceId]
        if (last != null && !isNewerSeq(beacon.seq, last)) return false
        lastControlSeq[beacon.deviceId] = beacon.seq
        return true
    }

    // ---- transitions ------------------------------------------------------

    private fun raiseAlarm(now: Long, reason: AlarmReason, subjectId: Int) {
        if (state == GuardState.ALARM) return
        alarmReason = reason
        alarmSubject = subjectId
        alarmSince = now
        pendingSince = 0L
        transition(now, GuardState.ALARM)
        listener.onAlarmRaised(reason, subjectId)
        val event = if (reason == AlarmReason.BOX_TAKEN) {
            Protocol.EVENT_BOX_ALARM
        } else {
            Protocol.EVENT_ALARM
        }
        listener.onBroadcastEvent(event, subjectId)
    }

    private fun noteEvidence(now: Long) {
        lastEvidenceAt = now
        if (state == GuardState.ARMED) transition(now, GuardState.SUSPICIOUS)
    }

    private fun relaxSuspicionIfQuiet(now: Long) {
        if (state == GuardState.SUSPICIOUS && now - lastEvidenceAt >= config.suspicionHoldMs) {
            transition(now, GuardState.ARMED)
        }
    }

    private fun transition(now: Long, next: GuardState) {
        if (state == next) return
        val previous = state
        state = next
        listener.onStateChanged(previous, next)
    }

    private fun updateRadioProfile() {
        val next = when (state) {
            GuardState.ALARM -> RadioProfile.CRITICAL
            GuardState.SUSPICIOUS, GuardState.PENDING -> RadioProfile.ALERT
            GuardState.CALIBRATING -> RadioProfile.ALERT
            GuardState.ARMED, GuardState.DISARMED -> RadioProfile.CALM
        }
        if (next != radioProfile) {
            radioProfile = next
            listener.onRadioProfileChanged(next)
        }
    }

    // ---- warnings ---------------------------------------------------------

    private val transientWarnings = HashSet<GuardWarning>()

    private fun addWarning(warning: GuardWarning) {
        transientWarnings.add(warning)
    }

    private fun recomputeWarnings(now: Long) {
        val next = HashSet<GuardWarning>(transientWarnings)
        transientWarnings.clear()

        if (state != GuardState.DISARMED && peers.isEmpty()) next.add(GuardWarning.NO_PEERS)
        if (peers.values.any { it.battery in 1..config.lowBatteryPercent }) {
            next.add(GuardWarning.PEER_BATTERY_LOW)
        }
        if (boxConfigured && boxGuardedHere && boxRssi.initialised && boxBaseline.size >= 8) {
            if (boxBaseline.median() - boxRssi.value >= config.dropThresholdDb) {
                next.add(GuardWarning.BOX_SIGNAL_WEAK)
            }
        }
        if (next != warnings) {
            warnings = next
            listener.onWarningsChanged(next)
        }
    }

    /** Warnings that the host (service) contributes rather than the engine. */
    fun setExternalWarning(warning: GuardWarning, active: Boolean) {
        val next = HashSet(warnings)
        val changed = if (active) next.add(warning) else next.remove(warning)
        if (changed) {
            warnings = next
            listener.onWarningsChanged(next)
        }
    }

    // ---- snapshot ---------------------------------------------------------

    fun snapshot(now: Long): GuardSnapshot {
        val peerSnapshots = peers.values.map { peer ->
            val metres = DistanceModel.estimateMetres(peer.kalman.value, peer.txPowerRef)
            val against = votes[peer.deviceId]?.size ?: 0
            PeerSnapshot(
                deviceId = peer.deviceId,
                name = peer.name,
                rssi = peer.kalman.value,
                baseline = peer.baseline,
                dropDb = if (peer.baseline.isNaN() || !peer.kalman.initialised) 0.0
                else peer.baseline - peer.kalman.value,
                slopeDbPerSecond = peer.lastSlope,
                proximity = DistanceModel.bucketOf(metres),
                estimatedMetres = metres,
                battery = peer.battery,
                armed = peer.armed,
                alarming = peer.alarming,
                stationary = peer.stationary,
                motionScore = peer.motionScore,
                boxGuardian = peer.boxGuardian,
                simulated = peer.simulated,
                lastSeenMsAgo = now - peer.lastSeenAt,
                suspected = peer.dropStartedAt != 0L,
                votesAgainst = against,
                votesRequired = requiredObserversFor(peer.deviceId),
            )
        }

        val boxMetres = DistanceModel.estimateMetres(boxRssi.value, BOX_TX_POWER_REF)
        return GuardSnapshot(
            state = state,
            radioProfile = radioProfile,
            selfDeviceId = selfDeviceId,
            selfName = selfName,
            selfStationary = selfStationary,
            selfMotionScore = selfMotionScore,
            armedSinceMs = armedSince,
            pendingRemainingMs = if (state == GuardState.PENDING) {
                (config.pickupGraceMs - (now - pendingSince)).coerceAtLeast(0)
            } else 0L,
            alarmReason = alarmReason,
            alarmSubjectId = alarmSubject,
            alarmSinceMs = alarmSince,
            peers = peerSnapshots,
            box = BoxSnapshot(
                configured = boxConfigured,
                name = boxName,
                address = boxAddress,
                audioLinkConnected = boxLinkConnected,
                bleRssi = boxRssi.value,
                bleProximity = DistanceModel.bucketOf(boxMetres),
                bleTracked = boxRssi.initialised && now - boxLastSeenAt < BOX_BLE_STALE_MS,
                guardedByThisPhone = boxGuardedHere,
                lastSeenMsAgo = if (boxLastSeenAt == 0L) Long.MAX_VALUE else now - boxLastSeenAt,
            ),
            warnings = warnings,
        )
    }

    /** Flag bits describing this device, for the outgoing beacon. */
    fun selfFlags(pendingOrAlarming: Boolean = false): Int {
        var flags = 0
        if (state != GuardState.DISARMED) flags = flags or Protocol.FLAG_ARMED
        if (state == GuardState.ALARM) flags = flags or Protocol.FLAG_ALARMING
        if (state == GuardState.PENDING || pendingOrAlarming) flags = flags or Protocol.FLAG_PENDING
        if (selfStationary) flags = flags or Protocol.FLAG_STATIONARY
        if (boxConfigured && boxGuardedHere) flags = flags or Protocol.FLAG_BOX_GUARDIAN
        return flags
    }

    fun selfMotionScoreForBeacon(): Int = selfMotionScore

    /** True while it is safe to spend an event slot on a name chunk. */
    fun canBroadcastName(now: Long): Boolean =
        (state == GuardState.DISARMED || state == GuardState.ARMED) &&
            selfStationary && activeVotes(now).isEmpty()

    companion object {
        /** Sentinel for "this has not happened yet". */
        private const val NEVER = Long.MIN_VALUE

        /** Calibration shortcut when no peers exist to calibrate against. */
        const val SOLO_CALIBRATION_MS = 2_000L

        /** Peers unheard for this long are dropped from the UI entirely. */
        const val PEER_FORGET_MS = 5 * 60_000L

        /** Box BLE beacons older than this stop counting as "tracked". */
        const val BOX_BLE_STALE_MS = 20_000L

        /** Typical BLE RSSI at 1 m; speakers do not tell us theirs. */
        const val BOX_TX_POWER_REF = -59

        /** 16-bit sequence comparison with wraparound. */
        fun isNewerSeq(candidate: Int, last: Int): Boolean {
            val delta = (candidate - last) and 0xFFFF
            return delta in 1..0x7FFF
        }
    }
}

private data class VoteRecord(val type: VoteType, val atMs: Long)

private class PeerRecord(val deviceId: Int) {
    val kalman = RssiKalman()
    val baselineWindow = RollingMedian(48)
    val trend = TrendEstimator(16)

    var baseline: Double = Double.NaN
    var lastSeenAt: Long = 0
    var flags: Int = 0
    var battery: Int = 0
    var txPowerRef: Int = -59
    var motionScore: Int = 0
    var name: String? = null
    var simulated: Boolean = false

    var dropStartedAt: Long = 0
    var lastDrop: Double = 0.0
    var lastSlope: Double = 0.0
    var occlusionSuppressions: Int = 0

    val armed: Boolean get() = flags and Protocol.FLAG_ARMED != 0
    val alarming: Boolean get() = flags and Protocol.FLAG_ALARMING != 0
    val stationary: Boolean get() = flags and Protocol.FLAG_STATIONARY != 0
    val boxGuardian: Boolean get() = flags and Protocol.FLAG_BOX_GUARDIAN != 0

    fun resetBaseline() {
        baselineWindow.clear()
        trend.clear()
        baseline = Double.NaN
        dropStartedAt = 0
    }
}
