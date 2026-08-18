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

    /**
     * Highest sequence number heard from each sender, control event or not.
     *
     * Tracked on every beacon rather than only on control ones. Sequence
     * numbers are handed out in blocks of a few thousand and a fresh block is
     * burned on every app start, so a device that had been quiet for a while
     * could come back with a number far enough ahead that the wraparound
     * comparison read it as *older* — and its "everybody stop" would be
     * discarded as a replay. Following every beacon keeps the reference close
     * to the sender's real position.
     */
    private val lastSeq = HashMap<Int, Int>()

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

    /**
     * Whether the alarm currently sounding was decided *here*.
     *
     * Only the phone that made the decision keeps `EVENT_ALARM` on the air. A
     * phone that is merely joining in does not re-broadcast it, and that is not
     * a detail: while every alarming phone repeated the event, two phones
     * sustained each other indefinitely. Clearing one made it fall silent for a
     * fraction of a second, hear the other's still-repeating alarm, and start
     * again — which the other then heard, and so on. Neither phone could ever
     * be stood down, closing the app did not help (an alarm in progress defers
     * the shutdown, §6b), and the only way out was for everyone to leave the
     * group. With a single source per incident, silencing that source ends it.
     */
    var alarmOriginatedHere: Boolean = false
        private set

    /**
     * Which device put the alarm currently sounding here on the air.
     *
     * [selfDeviceId] when we decided ourselves. Needed to identify the
     * *incident* rather than merely the moment, see [declinedIncidents].
     */
    var alarmSourceDevice = Protocol.DEVICE_ID_NONE
        private set

    /**
     * Until when alarm events arriving from peers are ignored.
     *
     * A group-wide "stop" cannot land on every phone in the same millisecond,
     * so for a few seconds afterwards the air still carries alarm packets from
     * the incident that was just called off. Without this window the first
     * phone to obey the command would immediately be re-triggered by the last
     * phone to hear it. Local detection is untouched: a phone that is picked up
     * during the window still alarms, because that is new evidence rather than
     * an echo.
     */
    private var alarmSuppressedUntil = 0L

    /**
     * Incidents this phone has been taken out of by hand, and when each was last
     * heard on the air. Keyed by (source device, subject device).
     *
     * A fixed echo window is not enough on its own, and the field test showed
     * exactly why: the phone that *decided* on an incident keeps `EVENT_ALARM` in
     * every beacon for as long as it is alarming, which may be minutes. So a
     * phone whose owner silenced it went quiet, waited out the fifteen second
     * window, heard the originator still shouting about the very same incident —
     * and started screaming again. Declining an incident is remembered for as
     * long as that incident is still audible, and forgotten
     * ([DECLINED_FORGET_MS]) once the source genuinely stops, so a *second*
     * theft moments later is still heard.
     */
    private val declinedIncidents = HashMap<Int, Long>()

    /**
     * When an alarm was last heard from *a peer*.
     *
     * Deliberately not touched by this phone's own alarm: that is already visible
     * as [state], and folding it in here would leave the group controls up for
     * twelve seconds after a lone phone - or a rehearsal - had finished, claiming
     * a group was shouting when nobody was.
     *
     * Together with [state] this drives [GuardSnapshot.groupAlarmActive], which
     * is what keeps "stop everyone" reachable after this phone has stood itself
     * down.
     */
    private var groupAlarmHeardAt = 0L

    /**
     * Group commands already seen, keyed by (event type, issuing device), with
     * when each was last heard.
     *
     * Two jobs. It stops a relayed command coming back and being applied twice —
     * which matters because "everybody disarm" stays in the air for the best part
     * of a minute once several phones are repeating it, and re-applying it would
     * undo a phone the user has just armed again. And it terminates the relay:
     * each phone passes a command on exactly once.
     */
    private val commandsSeen = HashMap<Int, Long>()

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

    /**
     * Whether the run of movement this phone is currently in began from a
     * properly settled, guarded state.
     *
     * This latch is what makes the pickup detector survive a gentle start. The
     * readiness test reads [selfStillSince], and the very first sample that
     * reports movement has to clear it — so without a latch, a lift that begins
     * below [EngineConfig.motionScoreThreshold] (a slow slide off the towel, a
     * phone lifted carefully) disarmed the detector permanently: every stronger
     * sample that followed found "has not been lying still" and did nothing.
     * The phone could then be carried away in total silence, and the only thing
     * the user ever saw was the app asking them to put it down again.
     */
    private var motionEpisodeArmed = false
    private var motionEpisodeSince = NEVER
    private var motionEpisodePeak = 0

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
        // Arming is a deliberate fresh start, so whatever was being ignored
        // from the last incident stops being ignored.
        alarmSuppressedUntil = 0L
        declinedIncidents.clear()
        groupAlarmHeardAt = 0L
        endMotionEpisode()
        transition(now, GuardState.CALIBRATING)
    }

    /**
     * @param groupWide true when this is standing the *whole group* down, which
     *        also has to ignore the incident's remaining packets for a moment.
     *        A plain local disarm does not: switching your own phone off guard
     *        must not stop it hearing that somebody else's is being taken.
     */
    fun disarm(now: Long, groupWide: Boolean = false) {
        val wasAnnouncing = state == GuardState.ALARM || state == GuardState.PENDING
        if (state == GuardState.ALARM) {
            listener.onAlarmCleared()
            declineCurrentIncident(now)
        }
        if (groupWide || wasAnnouncing) suppressRelayedAlarms(now)
        clearAlarmState()
        armedSince = 0L
        votes.clear()
        boxAlarmFired = false
        significantMotionPendingAt = 0L
        // Every peer's in-flight suspicion goes with it. Leaving these set is
        // what left the group list reading "moving away" about phones nobody was
        // watching any more, for as long as the app stayed open.
        peers.values.forEach { it.resetEpisode() }
        endMotionEpisode()
        transition(now, GuardState.DISARMED)
    }

    /** Stop the siren but keep protecting; baselines are relearned. */
    fun clearAlarm(now: Long) {
        if (state != GuardState.ALARM && state != GuardState.PENDING) return
        if (state == GuardState.ALARM) declineCurrentIncident(now)
        listener.onAlarmCleared()
        suppressRelayedAlarms(now)
        clearAlarmState()
        votes.clear()
        boxAlarmFired = false
        peers.values.forEach { it.resetBaseline() }
        boxBaseline.clear()
        armedSince = now
        // The owner has just said "that was me". Holding the latch would put
        // the phone straight back into PENDING the moment calibration ends.
        endMotionEpisode()
        transition(now, GuardState.CALIBRATING)
    }

    /**
     * "That was a false alarm — everybody stop, but keep guarding."
     *
     * Separate from [clearAlarm] because it has to work on a phone that is *not*
     * itself alarming: the one thing the field test made unmistakable is that the
     * person holding the phone that has already been silenced is exactly the
     * person who needs to reach the ones that have not. So this arms the echo
     * defences and declines the incident whether or not there is a local siren to
     * stop, and the caller broadcasts `EVENT_ALARM_CLEAR` alongside it.
     */
    fun stopGroupAlarm(now: Long) {
        suppressRelayedAlarms(now)
        if (state == GuardState.ALARM || state == GuardState.PENDING) {
            clearAlarm(now)
            return
        }
        // Nothing local to stop, but a peer that is still shouting must not be
        // allowed to drag this phone back into the incident it just called off.
        declineHeardIncidents(now)
    }

    fun panic(now: Long) {
        // A human pressing the button is never an echo of anything.
        alarmSuppressedUntil = 0L
        declinedIncidents.clear()
        raiseAlarm(now, AlarmReason.PANIC, selfDeviceId)
    }

    /** Resets everything that describes "an alarm is happening here". */
    private fun clearAlarmState() {
        alarmReason = null
        alarmOriginatedHere = false
        alarmSubject = Protocol.DEVICE_ID_NONE
        alarmSourceDevice = Protocol.DEVICE_ID_NONE
        alarmSince = 0L
        pendingSince = 0L
    }

    /**
     * Records the incident currently sounding here as one the user has said no
     * to, so its remaining packets - which may go on for minutes - cannot
     * restart it.
     */
    private fun declineCurrentIncident(now: Long) {
        val source = if (alarmSourceDevice == Protocol.DEVICE_ID_NONE) {
            selfDeviceId
        } else {
            alarmSourceDevice
        }
        declinedIncidents[incidentKey(source, alarmSubject)] = now
    }

    /**
     * As above, for every incident a peer is currently putting on the air.
     *
     * Only incidents heard as *events* count: those are the only ones that can
     * drag this phone back in, and a peer that is merely joining in does not
     * broadcast one.
     */
    private fun declineHeardIncidents(now: Long) {
        for (peer in peers.values) {
            if (peer.lastAlarmEventAt == 0L) continue
            if (now - peer.lastAlarmEventAt > GROUP_ALARM_HOLD_MS) continue
            declinedIncidents[incidentKey(peer.deviceId, peer.lastAlarmSubject)] = now
        }
    }

    /**
     * Starts the window in which alarm events from peers are treated as echoes
     * of the incident that has just been called off rather than as news.
     *
     * Long enough to cover the group command being repeated to every phone (see
     * `BeaconComposer.CONTROL_REPEAT_MS`) plus the slowest scan duty cycle, and
     * no longer: it is deliberately bounded, so a real theft moments after a
     * false alarm is still heard.
     */
    private fun suppressRelayedAlarms(now: Long) {
        alarmSuppressedUntil = now + ALARM_ECHO_WINDOW_MS
    }

    /** Used by the simulator and the in-app self test. */
    fun triggerTestAlarm(now: Long) {
        raiseAlarm(now, AlarmReason.TEST, selfDeviceId)
    }

    fun configureBox(configured: Boolean, name: String?, address: String?, guardedHere: Boolean) {
        // Pointing at a different speaker is a clean slate: any alarm already
        // fired was about the old one. Without this a scenario that swaps in a
        // virtual speaker inherits the previous run's "already alarmed" flag
        // and can never fire again.
        val changedDevice = !address.equals(boxAddress, ignoreCase = true)
        boxConfigured = configured
        boxName = name
        boxAddress = address
        boxGuardedHere = guardedHere
        if (changedDevice || !configured) {
            boxLinkConnected = false
            boxDisconnectedAt = 0L
            boxAlarmFired = false
            boxRssi.reset()
            boxBaseline.clear()
        }
    }

    fun forgetPeer(deviceId: Int) {
        peers.remove(deviceId)
        votes.remove(deviceId)
        votes.values.forEach { it.remove(deviceId) }
        lastSeq.remove(deviceId)
        nameAssembler.forget(deviceId)
    }

    // =====================================================================
    // Inputs
    // =====================================================================

    /** One authenticated advertisement from a group member. */
    fun onPeerBeacon(now: Long, rssi: Int, beacon: Beacon) {
        if (beacon.deviceId == selfDeviceId) return

        val peer = peers.getOrPut(beacon.deviceId) {
            PeerRecord(beacon.deviceId, now).also {
                it.nameHuntUntil = now + PEER_INTRODUCTION_MS
            }
        }
        // How long the filter has been flying blind. Scan results arrive every
        // five seconds or so while calm and several times a second once the
        // radios escalate, and the filter has to be told which it is getting.
        val sinceLastSample = if (peer.lastSeenAt == 0L) {
            RssiKalman.NOMINAL_INTERVAL_MS
        } else {
            now - peer.lastSeenAt
        }
        if (peer.lastSeenAt != 0L) peer.noteArrivalGap(sinceLastSample)
        peer.lastSeenAt = now
        peer.silenceNoticedAt = 0L
        peer.flags = beacon.flags
        peer.battery = beacon.battery
        peer.txPowerRef = beacon.txPowerRef
        peer.simulated = beacon.simulated

        // Name packets reuse the telemetry bytes, so only take a motion score
        // from packets that actually carry one.
        if (!beacon.carriesName) {
            peer.motionScore = beacon.motionScore
        } else {
            // A name packet is only ever sent by a phone that is lying still, so
            // the honest reading of the missing telemetry is zero rather than
            // whatever the peer's score was last time it moved. Keeping the old
            // value made a stationary phone look like a moving one to the
            // occlusion gate, which is the one gate that stops a passer-by
            // setting off the siren.
            if (beacon.stationary) peer.motionScore = 0
            nameAssembler.accept(beacon)?.let { name ->
                if (peer.name != name) {
                    peer.name = name
                    listener.onPeerNameLearned(peer.deviceId, name)
                }
            }
        }

        // The flag alone says "somebody in the group is making noise", which is
        // what keeps the stop-everyone controls on screen. It deliberately does
        // *not* record an incident: a phone that merely joined in carries the
        // flag while its event slot is busy with something else entirely, so its
        // subject field says nothing about what is being stolen.
        if (beacon.alarming) groupAlarmHeardAt = now

        val filtered = peer.kalman.update(rssi.toDouble(), sinceLastSample)
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
        rememberSeq(beacon)
    }

    fun onSelfMotion(now: Long, signal: MotionSignal) {
        when (signal) {
            is MotionSignal.SignificantMotion -> {
                significantMotionPendingAt = now
                if (pickupDetectorReady(now)) {
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
                    endMotionEpisode()
                    return
                }

                // Order matters: the pickup detector's readiness is derived
                // from selfStillSince, so it has to be evaluated *before* the
                // phone is marked as moving. Clearing it first made this whole
                // branch dead code, and left the slow hardware
                // significant-motion sensor as the only way to ever trigger.
                if (selfStillSince != NEVER) {
                    // First sample of a new run of movement. Whether the
                    // detector was ready is decided *here*, once, and then held
                    // for as long as the phone keeps moving.
                    motionEpisodeArmed = isPickupDetectorArmed(now)
                    motionEpisodeSince = now
                    motionEpisodePeak = 0
                    selfStillSince = NEVER
                }
                motionEpisodePeak = max(motionEpisodePeak, signal.score)
                evaluatePickup(now)
            }
        }
    }

    /**
     * Decides whether the movement this phone is in amounts to being picked up.
     *
     * Two ways in, because a lift does not always announce itself with one big
     * sample: either a single decisive one, or a weaker one that simply does
     * not stop. Also driven from [tick], so a phone whose sensor batches its
     * reports - or stops sending them entirely - is still caught.
     */
    private fun evaluatePickup(now: Long) {
        if (!motionEpisodeArmed || selfStationary || motionEpisodeSince == NEVER) return

        if (motionEpisodePeak >= config.motionScoreThreshold) {
            beginPending(now)
            return
        }
        // Gentler than a snatch, but a phone that has been in motion for
        // several seconds straight is not lying on a towel any more. The floor
        // on the peak keeps a single knock to the towel out of it: that decays
        // within a second, and only the motion monitor's settling delay keeps
        // reporting movement afterwards.
        val persistent = now - motionEpisodeSince >= SUSTAINED_MOTION_MS
        if (persistent && motionEpisodePeak >= config.motionScoreThreshold / 2) {
            beginPending(now)
        }
    }

    private fun endMotionEpisode() {
        motionEpisodeArmed = false
        motionEpisodeSince = NEVER
        motionEpisodePeak = 0
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
        pruneDeclinedIncidents(now)
        pruneSeenCommands(now)
        renewNameHunts(now)

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
                evaluatePickup(now)
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
        updateRadioProfile(now)
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
            peers.values.forEach { it.resetEpisode() }
            retractOwnVotes()
            return
        }

        for (peer in peers.values) {
            if (!peer.armed) {
                peer.resetEpisode()
                clearOwnVote(peer.deviceId)
                continue
            }

            // ---- has it vanished? ------------------------------------------
            // Two conditions, not one. "Silent for longer than usual" is
            // measured against the gaps this peer has actually been producing,
            // because at a low scan duty cycle a ten second gap between two
            // results is completely ordinary - and a fixed ten second rule
            // therefore accused a phone lying on the same towel. And silence has
            // to survive a few seconds of *escalated* radio: noticing it lifts
            // the scanner to low latency, so a peer that was merely missed comes
            // back within a second and the vote is never cast.
            val silentFor = now - peer.lastSeenAt
            if (silentFor >= staleAfterMs(peer)) {
                if (peer.silenceNoticedAt == 0L) peer.silenceNoticedAt = now
                noteEvidence(now)
                if (now - peer.silenceNoticedAt >= LOST_CONFIRM_MS) {
                    castOwnVote(now, peer.deviceId, VoteType.LOST)
                }
                continue
            }
            peer.silenceNoticedAt = 0L

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
                peer.resetEpisode()
                clearOwnVote(peer.deviceId)
                continue
            }

            // The negative-slope test gates the *start* of an episode, not its
            // continuation. A thief who walks twenty metres and then stops
            // produces a flat slope again, but the signal stays down - and that
            // must still count. Once an episode has begun it ends only when the
            // signal genuinely comes back (the reset below).
            //
            // The episode starts at a *fraction* of the full threshold, so the
            // sustain window runs while the phone is still receding instead of
            // only afterwards. Voting still needs the whole drop, so this
            // changes nothing about what counts as theft - only about how long
            // it takes to say so. For occlusion, which is a step rather than a
            // fade, both clocks still start on the same sample.
            val episodeRunning = peer.dropStartedAt != 0L
            val starting = drop >= config.episodeStartDropDb && slope <= config.minNegativeSlope
            val continuing = episodeRunning && drop >= config.episodeEndDropDb

            if (starting || continuing) {
                if (!episodeRunning) peer.dropStartedAt = now
                noteEvidence(now)
                val needed = if (drop >= config.fastPathDropDb) config.sustainMs / 2 else config.sustainMs
                if (dropping && now - peer.dropStartedAt >= needed) {
                    castOwnVote(now, peer.deviceId, VoteType.SUSPECT)
                }
            } else {
                // Comfortably back to normal - forget the whole episode.
                peer.resetEpisode()
                clearOwnVote(peer.deviceId)
            }
        }
    }

    /**
     * How long a peer has to be silent before its telemetry stops counting as
     * current, and before its absence is worth voting on.
     *
     * [EngineConfig.lostTimeoutMs] is a floor rather than the answer. The real
     * question is how long a gap is *unusual for this peer*, which depends
     * entirely on the scan duty cycle the user has chosen - and the whole class
     * of "the group list flickers between arbitrary states" came from answering
     * it with a constant.
     */
    private fun staleAfterMs(peer: PeerRecord): Long {
        val fromObservedRate = (peer.worstRecentGapMs * GAP_TOLERANCE).toLong() + GAP_MARGIN_MS
        return maxOf(config.lostTimeoutMs, fromObservedRate).coerceAtMost(MAX_STALE_MS)
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

    /**
     * A declined incident is forgotten once it stops being audible — not on a
     * timer from when it was declined. See [declinedIncidents].
     */
    private fun pruneDeclinedIncidents(now: Long) {
        declinedIncidents.entries.removeAll { now - it.value > DECLINED_FORGET_MS }
    }

    private fun pruneSeenCommands(now: Long) {
        commandsSeen.entries.removeAll { now - it.value > COMMAND_MEMORY_MS }
    }

    /**
     * Gives a still-anonymous peer another spell of fast radio, now and then.
     *
     * A name takes six separate packets, so the one-shot window from when a peer
     * was first heard is a single roll of the dice: if the phone was in somebody's
     * pocket for those twenty-five seconds, it stayed "Phone A31F" for the rest of
     * the afternoon. Retrying costs a few seconds of radio a couple of times a
     * minute, and only until the name arrives.
     */
    private fun renewNameHunts(now: Long) {
        if (state == GuardState.ALARM || state == GuardState.PENDING) return
        for (peer in peers.values) {
            if (peer.name != null) continue
            // Nothing to hunt for while the peer is not even being heard.
            if (now - peer.lastSeenAt > staleAfterMs(peer)) continue
            if (now >= peer.nameHuntUntil + NAME_HUNT_COOLDOWN_MS) {
                peer.nameHuntUntil = now + NAME_HUNT_RETRY_MS
            }
        }
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

    /**
     * As above, but true throughout a run of movement that began while it was.
     *
     * This is the one the trigger and the UI both use: a phone that is in the
     * air right now is still protected, and must not report otherwise.
     */
    private fun pickupDetectorReady(now: Long): Boolean =
        isPickupDetectorArmed(now) || motionEpisodeArmed

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
                noteGroupAlarm(now, peers[beacon.deviceId], beacon.subjectId)
                if (declined(now, beacon.deviceId, beacon.subjectId)) return
                if (now < alarmSuppressedUntil) return
                if (state != GuardState.ALARM) {
                    raiseAlarm(
                        now, AlarmReason.RELAYED, beacon.subjectId,
                        originatedHere = false, sourceDevice = beacon.deviceId,
                    )
                }
            }

            Protocol.EVENT_BOX_ALARM -> {
                if (!acceptControl(beacon)) return
                noteGroupAlarm(now, peers[beacon.deviceId], Protocol.DEVICE_ID_NONE)
                if (declined(now, beacon.deviceId, Protocol.DEVICE_ID_NONE)) return
                if (now < alarmSuppressedUntil) return
                if (state != GuardState.ALARM) {
                    raiseAlarm(
                        now, AlarmReason.BOX_TAKEN, Protocol.DEVICE_ID_NONE,
                        originatedHere = false, sourceDevice = beacon.deviceId,
                    )
                }
            }

            // Deliberately not gated on the echo window. A panic is somebody's
            // thumb on a button, so it is news by definition - and since a
            // relaying phone no longer repeats alarms, the only thing that can
            // put this event on the air is the phone whose button was pressed.
            Protocol.EVENT_PANIC -> {
                if (!acceptControl(beacon)) return
                noteGroupAlarm(now, peers[beacon.deviceId], beacon.subjectId)
                if (state != GuardState.ALARM) {
                    raiseAlarm(
                        now, AlarmReason.PANIC, beacon.subjectId,
                        originatedHere = false, sourceDevice = beacon.deviceId,
                    )
                }
            }

            // All three group commands go through the same door: applied once,
            // passed on once, and remembered for a while afterwards so the relays
            // cannot re-apply them. See [EngineListener.onRelayGroupCommand] for
            // why relaying is what finally made these dependable.
            Protocol.EVENT_ALARM_CLEAR -> {
                if (!acceptControl(beacon)) return
                // The echo defences are refreshed by *every* copy, not only the
                // first: the incident is still being called off, and every extra
                // second of protection against its own trailing packets is free.
                suppressRelayedAlarms(now)
                if (!firstSightOfCommand(now, beacon)) return
                if (state == GuardState.ALARM || state == GuardState.PENDING) {
                    clearAlarm(now)
                } else {
                    // Nothing local to stop, but a peer that has not caught up
                    // must not be able to drag this phone into an incident the
                    // group has just called off.
                    declineHeardIncidents(now)
                }
                relayCommand(beacon)
            }

            Protocol.EVENT_DISARM_ALL -> {
                if (!acceptControl(beacon)) return
                suppressRelayedAlarms(now)
                if (!firstSightOfCommand(now, beacon)) return
                if (state != GuardState.DISARMED) disarm(now, groupWide = true)
                relayCommand(beacon)
            }

            Protocol.EVENT_ARM_ALL -> {
                if (!acceptControl(beacon)) return
                if (!firstSightOfCommand(now, beacon)) return
                if (state == GuardState.DISARMED) arm(now)
                relayCommand(beacon)
            }
        }
    }

    /**
     * Which device *issued* a group command, as opposed to which one this copy
     * arrived from.
     *
     * The issuer puts its own id in the subject field and every relay carries it
     * through unchanged, so all copies of one command share an origin and can be
     * recognised as the same decision. Falling back to the sender keeps commands
     * that carry no subject working rather than treating each copy as new.
     */
    private fun commandOrigin(beacon: Beacon): Int =
        if (beacon.subjectId == Protocol.DEVICE_ID_NONE) beacon.deviceId else beacon.subjectId

    /**
     * @return true when this is the first sight of this command, which is the
     *   only time it is applied and the only time it is passed on.
     */
    private fun firstSightOfCommand(now: Long, beacon: Beacon): Boolean =
        commandsSeen.put(commandKey(beacon.eventType, commandOrigin(beacon)), now) == null

    private fun relayCommand(beacon: Beacon) {
        listener.onRelayGroupCommand(beacon.eventType, commandOrigin(beacon))
    }

    /**
     * Records that a command this phone issued itself is already accounted for,
     * so the relays coming back from everybody else are not mistaken for a
     * second decision.
     */
    fun noteOwnGroupCommand(now: Long, eventType: Int) {
        commandsSeen[commandKey(eventType, selfDeviceId)] = now
    }

    /** True when the user has already said no to this exact incident. */
    private fun declined(now: Long, sourceDevice: Int, subject: Int): Boolean {
        val key = incidentKey(sourceDevice, subject)
        if (!declinedIncidents.containsKey(key)) return false
        // Still audible, so keep declining it. The entry expires only once the
        // source genuinely stops, which is what keeps a *second* theft moments
        // later audible.
        declinedIncidents[key] = now
        return true
    }

    private fun noteGroupAlarm(now: Long, peer: PeerRecord?, subject: Int) {
        groupAlarmHeardAt = now
        if (peer != null) {
            peer.lastAlarmEventAt = now
            peer.lastAlarmSubject = subject
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
        val last = lastSeq[beacon.deviceId] ?: return true
        return isNewerSeq(beacon.seq, last)
    }

    /** Advances the replay reference. Called for every authenticated beacon. */
    private fun rememberSeq(beacon: Beacon) {
        val last = lastSeq[beacon.deviceId]
        if (last == null || isNewerSeq(beacon.seq, last)) {
            lastSeq[beacon.deviceId] = beacon.seq
        }
    }

    // ---- transitions ------------------------------------------------------

    /**
     * @param originatedHere false when we are only joining in an alarm another
     *        phone decided on. Such an alarm makes just as much noise, but it
     *        is not put back on the air - exactly one phone speaks for each
     *        incident, so silencing that phone ends it for everybody.
     */
    private fun raiseAlarm(
        now: Long,
        reason: AlarmReason,
        subjectId: Int,
        originatedHere: Boolean = true,
        sourceDevice: Int = selfDeviceId,
    ) {
        if (state == GuardState.ALARM) return
        alarmReason = reason
        alarmOriginatedHere = originatedHere
        alarmSubject = subjectId
        alarmSourceDevice = sourceDevice
        alarmSince = now
        pendingSince = 0L
        transition(now, GuardState.ALARM)
        listener.onAlarmRaised(reason, subjectId)
        if (!originatedHere) return
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

    private fun updateRadioProfile(now: Long) {
        var next = when (state) {
            GuardState.ALARM -> RadioProfile.CRITICAL
            GuardState.SUSPICIOUS, GuardState.PENDING -> RadioProfile.ALERT
            GuardState.CALIBRATING -> RadioProfile.ALERT
            GuardState.ARMED, GuardState.DISARMED -> RadioProfile.CALM
        }
        if (next == RadioProfile.CALM && awaitingIntroduction(now)) next = RadioProfile.ALERT
        if (next != radioProfile) {
            radioProfile = next
            listener.onRadioProfileChanged(next)
        }
    }

    /**
     * True for a short while after meeting a phone whose name we do not know.
     *
     * Names travel two characters at a time in the beacon's idle event slot, so
     * six different packets have to be caught before one can be read. At the
     * calm duty cycle - a 512 ms scan window every 5.12 s against a beacon sent
     * once a second - a phone catches roughly one packet every five seconds, of
     * a rotation of six: a full name takes well over a minute of both phones
     * lying perfectly still, and in practice the group list simply showed
     * hexadecimal ids forever.
     *
     * So meeting somebody new is worth a few seconds of fast radio. Both ends
     * do this simultaneously - each is new to the other - which also speeds the
     * advertisement up, and the name arrives in a couple of seconds. The window
     * is anchored to when the peer was first heard, so a phone whose owner
     * never set a name costs this once and not repeatedly.
     */
    private fun awaitingIntroduction(now: Long): Boolean = peers.values.any {
        it.name == null && now < it.nameHuntUntil
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
            val staleAfter = staleAfterMs(peer)
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
                linkStale = now - peer.lastSeenAt >= staleAfter,
                staleAfterMs = staleAfter,
                // Reported only once an episode has had a moment to prove itself.
                // Every brief dip used to repaint the card as "moving away", which
                // on a busy beach is most of the time.
                suspected = peer.dropStartedAt != 0L &&
                    now - peer.dropStartedAt >= SUSPICION_VISIBLE_AFTER_MS,
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
            pickupArmed = pickupDetectorReady(now),
            pickupArmsInMs = when {
                state == GuardState.DISARMED -> 0L
                selfStillSince == NEVER -> config.settleMs
                else -> (config.settleMs - (now - selfStillSince)).coerceAtLeast(0)
            },
            pendingRemainingMs = if (state == GuardState.PENDING) {
                (config.pickupGraceMs - (now - pendingSince)).coerceAtLeast(0)
            } else 0L,
            alarmReason = alarmReason,
            alarmSubjectId = alarmSubject,
            alarmSinceMs = alarmSince,
            groupAlarmActive = groupAlarmActive(now),
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

    /**
     * True while it is safe to spend an event slot on a name chunk.
     *
     * A name packet reuses the telemetry bytes, so it carries no fresh motion
     * score - which is why an armed phone only sends one while it is lying
     * still. A *disarmed* phone has no such duty: nobody is guarding it, so
     * nothing is reading its motion score at all.
     *
     * That distinction is the difference between names working and not. People
     * are holding their phones when they set a group up - reading the screen,
     * typing a code - and requiring stillness meant neither phone said a word
     * about who it was during the one minute both of them were listening
     * hardest. Everyone put their phone down on the towel already anonymous.
     */
    fun canBroadcastName(now: Long): Boolean {
        val slotFree = when (state) {
            GuardState.DISARMED -> true
            // CALIBRATING is included deliberately. It is eight seconds during
            // which nobody is voting on anything yet, and it lands squarely in the
            // middle of the window when two phones have just met and are both
            // listening hardest - so excluding it threw away the best chance a
            // name ever gets.
            GuardState.CALIBRATING, GuardState.ARMED, GuardState.SUSPICIOUS -> selfStationary
            else -> false
        }
        return slotFree && activeVotes(now).isEmpty()
    }

    /**
     * True while the *group* is in an incident, whether or not this phone is.
     *
     * Held for a few seconds past the last thing we heard, because that is how
     * long it takes to tell "everybody has stopped" from "the gap between two
     * beacons".
     */
    fun groupAlarmActive(now: Long): Boolean =
        state == GuardState.ALARM ||
            now - groupAlarmHeardAt < GROUP_ALARM_HOLD_MS ||
            peers.values.any { it.alarming && now - it.lastSeenAt < GROUP_ALARM_HOLD_MS }

    companion object {
        /** Sentinel for "this has not happened yet". */
        private const val NEVER = Long.MIN_VALUE

        /** Calibration shortcut when no peers exist to calibrate against. */
        const val SOLO_CALIBRATION_MS = 2_000L

        /**
         * Continuous movement that counts as a pickup even without a decisive
         * sample. Comfortably longer than the motion monitor's settling delay,
         * so a single knock cannot reach it.
         */
        const val SUSTAINED_MOTION_MS = 3_000L

        /** Peers unheard for this long are dropped from the UI entirely. */
        const val PEER_FORGET_MS = 5 * 60_000L

        /** How long a newly met phone gets fast radio to introduce itself. */
        const val PEER_INTRODUCTION_MS = 25_000L

        /** A later, shorter attempt at a name that never arrived first time. */
        const val NAME_HUNT_RETRY_MS = 12_000L

        /** ...spaced out by this much, so an unnamed phone is not a battery leak. */
        const val NAME_HUNT_COOLDOWN_MS = 75_000L

        /**
         * How long silence has to persist *after* it was first noticed - and
         * therefore after the radios were escalated - before a peer counts as
         * gone.
         *
         * Noticing raises the scanner to low latency, so a peer that was simply
         * missed by a slow scan window reappears within a second and no vote is
         * ever cast. This is what stops a low duty cycle from being read as a
         * theft, which in a two-phone group meant an immediate siren.
         */
        const val LOST_CONFIRM_MS = 3_000L

        /** Multiple of the worst recent gap that still counts as normal. */
        private const val GAP_TOLERANCE = 2.5

        /** Absolute slack on top, for the first gaps after a profile change. */
        private const val GAP_MARGIN_MS = 2_000L

        /** However sparse the radio gets, silence this long is always suspicious. */
        private const val MAX_STALE_MS = 30_000L

        /** An episode has to last this long before the UI calls a peer suspect. */
        private const val SUSPICION_VISIBLE_AFTER_MS = 1_000L

        /**
         * How long after the last alarm packet the group still counts as being in
         * an incident, so the "stop everyone" controls stay reachable.
         */
        const val GROUP_ALARM_HOLD_MS = 12_000L

        /** How long a declined incident stays declined after it was last heard. */
        const val DECLINED_FORGET_MS = 12_000L

        /**
         * How long a group command is remembered, so its relays cannot re-apply
         * it.
         *
         * Comfortably longer than the command stays in the air: the issuer repeats
         * it for `BeaconComposer.CONTROL_REPEAT_MS` and each phone that hears it
         * passes it on for `CONTROL_RELAY_MS`, which can chain.
         */
        const val COMMAND_MEMORY_MS = 90_000L

        /**
         * How long alarm events from peers are treated as echoes after a
         * group-wide stop. Comfortably longer than the command is repeated for,
         * so the last phone to obey cannot re-trigger the first.
         */
        const val ALARM_ECHO_WINDOW_MS = 15_000L

        /** Box BLE beacons older than this stop counting as "tracked". */
        const val BOX_BLE_STALE_MS = 20_000L

        /** Typical BLE RSSI at 1 m; speakers do not tell us theirs. */
        const val BOX_TX_POWER_REF = -59

        /** 16-bit sequence comparison with wraparound. */
        fun isNewerSeq(candidate: Int, last: Int): Boolean {
            val delta = (candidate - last) and 0xFFFF
            return delta in 1..0x7FFF
        }

        /** Identity of one incident: who put it on the air, and about whom. */
        internal fun incidentKey(sourceDevice: Int, subject: Int): Int =
            ((sourceDevice and 0xFFFF) shl 16) or (subject and 0xFFFF)

        /** Identity of one group command: what it was, and who decided it. */
        internal fun commandKey(eventType: Int, originId: Int): Int =
            ((eventType and 0xFF) shl 16) or (originId and 0xFFFF)
    }
}

private data class VoteRecord(val type: VoteType, val atMs: Long)

private class PeerRecord(val deviceId: Int, val firstSeenAt: Long) {
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

    /** When silence from this peer was first noticed; see `LOST_CONFIRM_MS`. */
    var silenceNoticedAt: Long = 0

    /** Until when this peer is worth spending fast radio on to learn its name. */
    var nameHuntUntil: Long = 0

    /** When this peer last put an alarm on the air, and about whom. */
    var lastAlarmEventAt: Long = 0
    var lastAlarmSubject: Int = Protocol.DEVICE_ID_NONE

    /**
     * The worst gap between two beacons seen from this peer recently.
     *
     * Rises immediately and decays slowly, on purpose: the question it answers is
     * "how long a silence is normal here", and a mean would be dragged to
     * milliseconds by one burst of fast scanning and then read an ordinary calm
     * gap as a vanished phone.
     */
    var worstRecentGapMs: Double = 0.0
        private set

    fun noteArrivalGap(gapMs: Long) {
        val gap = gapMs.toDouble().coerceAtMost(MAX_TRACKED_GAP_MS)
        worstRecentGapMs = if (gap > worstRecentGapMs) {
            gap
        } else {
            worstRecentGapMs * 0.9 + gap * 0.1
        }
    }

    val armed: Boolean get() = flags and Protocol.FLAG_ARMED != 0
    val alarming: Boolean get() = flags and Protocol.FLAG_ALARMING != 0
    val stationary: Boolean get() = flags and Protocol.FLAG_STATIONARY != 0
    val boxGuardian: Boolean get() = flags and Protocol.FLAG_BOX_GUARDIAN != 0

    fun resetBaseline() {
        baselineWindow.clear()
        trend.clear()
        baseline = Double.NaN
        resetEpisode()
    }

    /** Forgets any in-flight suspicion without touching the learned baseline. */
    fun resetEpisode() {
        dropStartedAt = 0
        silenceNoticedAt = 0
    }

    private companion object {
        /** Beyond this a gap says "gone", not "slow", so it must not widen the bar. */
        const val MAX_TRACKED_GAP_MS = 8_000.0
    }
}
