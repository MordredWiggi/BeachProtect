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
    /**
     * Where announcement counters come from.
     *
     * The host passes a persisted, monotonic supplier, because a counter that
     * came back at zero after a restart would have this phone's next hundred
     * announcements read as stale by everybody who still remembered the old
     * number. Tests get a plain in-memory one.
     */
    private val counterSource: (() -> Int)? = null,
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
     * [selfDeviceId] when we decided ourselves. Together with [alarmCounter] it
     * names the *incident* rather than merely the moment; see [declinedIncidents].
     */
    var alarmSourceDevice = Protocol.DEVICE_ID_NONE
        private set

    /**
     * Which announcement of [alarmSourceDevice] the alarm sounding here is.
     *
     * The other half of an incident's name. Without it an incident could only be
     * described as "an alarm from that phone about this phone", which is not
     * enough to tell a second theft apart from an echo of the first, and — much
     * more to the point — not enough to acknowledge.
     */
    var alarmCounter = 0
        private set

    /** When this phone first put its own incident on the air; see [alarmNeedsAir]. */
    private var alarmAnnouncedAt = 0L

    /** Which peers have confirmed the incident this phone is announcing. */
    private val alarmAckedBy = HashSet<Int>()

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
     * heard on the air. Keyed by `(origin device, incident counter)`.
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
     *
     * Note what this is *not*: a claim that the incident is over. Silencing your
     * own handset says nothing about anybody else's, and must not — that is
     * exactly how somebody ended up with a screaming towel and an "Arm all"
     * button. See [calledOffIncidents] for the other half.
     */
    private val declinedIncidents = HashMap<Int, Long>()

    /**
     * Incidents the *group* has been told to stop, and when.
     *
     * The distinction from [declinedIncidents] is the whole of the post-alarm
     * fix. "Do not restart my siren for this" and "this incident is finished for
     * everybody" are different claims, made by different gestures, and only the
     * second one may take the banner down.
     *
     * [groupAlarmActive] used to consult neither. It was driven by an unfiltered
     * "when did we last hear any alarming beacon", refreshed by every straggler
     * — including echoes of the very incident the user had just called off — so
     * after everybody had stopped it appeared for twelve seconds, aged out, and
     * came back on the next stale packet. That is the message that kept popping
     * up at random about an incident that was over.
     *
     * Deliberately allowed to expire ([DECLINED_FORGET_MS]) rather than being
     * refreshed on every echo: if a phone genuinely never heard the stop and is
     * genuinely still screaming, the banner is supposed to come back — once, and
     * after the attempt to reach it has run its course, rather than flickering
     * throughout.
     */
    private val calledOffIncidents = HashMap<Int, Long>()

    /**
     * The latest announcement counter applied from each issuing device.
     *
     * Announcements are identified, not merely typed. That does three jobs at
     * once, and the first version of the relay got two of them wrong by trying
     * to do it with a timer:
     *
     * - **A stale copy is dropped.** Once several phones are repeating a
     *   command it stays in the air for the best part of a minute, and
     *   re-applying every copy meant a lingering "disarm all" quietly undid a
     *   phone somebody had just armed again.
     * - **Every genuine press is obeyed.** Remembering only the *type* for a
     *   while fixed the first problem and broke this one outright: "arm all,
     *   disarm all, arm all" ignored the third press entirely, on every phone
     *   that had heard the first — which is exactly the sequence anybody
     *   testing the app performs.
     * - **The relay terminates.** A copy that is not newer is not passed on, so
     *   each phone repeats each command at most once.
     */
    private val commandCounters = HashMap<Int, Int>()

    /**
     * The group command this phone is currently announcing, if any.
     *
     * Kept so the announcement can *finish*: a command used to be repeated for a
     * flat twenty-five seconds because nothing could tell it whether anybody had
     * heard. With acknowledgements it normally stops inside two, which is the
     * single biggest reduction in stale packets on the air — and the reason the
     * post-alarm banner stops flickering.
     */
    private var announcement: Announcement? = null

    /**
     * Announcements this phone has heard and owes a confirmation for, keyed by
     * `(origin, counter)`.
     *
     * Refreshed every time the same announcement is heard again, so a phone
     * keeps confirming for exactly as long as somebody keeps asking, and stops
     * on its own afterwards.
     */
    private val pendingAcks = LinkedHashMap<Int, PendingAck>()

    /**
     * Devices that have announced they are leaving, and until when their
     * remaining packets are ignored.
     *
     * A departure has to be sticky for a moment. The leaving phone's last few
     * beacons are still in the air, and so are the relays of its farewell, so
     * without this the peer is dropped and immediately recreated by its own
     * echo — and the relay, no longer recognising the command as one it had
     * already seen, would be passed on again by everybody, forever.
     */
    private val departedUntil = HashMap<Int, Long>()

    private var lastEvidenceAt = 0L
    private var radioProfile = RadioProfile.CALM

    /**
     * When [radioProfile] last changed, so it cannot flap.
     *
     * Re-tuning is not free and it is not silent: the advertising set has to be
     * torn down and rebuilt, which takes this phone off the air for a moment,
     * and Android blocks an app that restarts scanning more than five times in
     * thirty seconds. Both of those cost *packets*, which is how a phone that
     * escalated because it missed a peer went on to make its peers miss it —
     * two handsets feeding each other's flicker all afternoon.
     *
     * Escalation is still immediate, because that is the whole point of it.
     * Coming back down waits out [RADIO_DWELL_MS].
     */
    private var radioProfileSince = 0L

    /** Announcement counters when no persisted supplier was given; see [counterSource]. */
    private var fallbackCounter = 0

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
        calledOffIncidents.clear()
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
        // A group-wide stand-down calls off every incident this phone can hear,
        // not merely its own. A plain local disarm deliberately does not: it must
        // not hide from the user that somebody else's phone is still being taken.
        if (groupWide) standDownIncidents(now)
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
        // Every incident anybody within earshot is announcing is now one the user
        // has said no to - including, in a moment, this phone's own. A peer that
        // has not caught up yet must not be able to drag this phone back into an
        // incident it has just called off, and its echoes must not put the
        // "the group is still alarming" banner back on screen either.
        standDownIncidents(now)
        if (state == GuardState.ALARM || state == GuardState.PENDING) {
            clearAlarm(now)
        }
    }

    fun panic(now: Long) {
        // A human pressing the button is never an echo of anything.
        alarmSuppressedUntil = 0L
        declinedIncidents.clear()
        calledOffIncidents.clear()
        raiseAlarm(now, AlarmReason.PANIC, selfDeviceId)
    }

    /** Resets everything that describes "an alarm is happening here". */
    private fun clearAlarmState() {
        alarmReason = null
        alarmOriginatedHere = false
        alarmSubject = Protocol.DEVICE_ID_NONE
        alarmSourceDevice = Protocol.DEVICE_ID_NONE
        alarmCounter = 0
        alarmAnnouncedAt = 0L
        alarmAckedBy.clear()
        alarmSince = 0L
        pendingSince = 0L
    }

    /**
     * Records the incident currently sounding here as one the user has said no
     * to, so its remaining packets - which may go on for minutes - cannot
     * restart it, and cannot go on claiming the group is in an incident.
     */
    private fun declineCurrentIncident(now: Long) {
        val source = if (alarmSourceDevice == Protocol.DEVICE_ID_NONE) {
            selfDeviceId
        } else {
            alarmSourceDevice
        }
        declinedIncidents[incidentKey(source, alarmCounter)] = now
    }

    /**
     * "Everybody stop": every incident within earshot is finished, including
     * this phone's own.
     *
     * Both flavours of peer count, and that is the fix rather than a detail. A
     * peer that *decided* on an incident announces it as an event; a peer that
     * merely joined in carries only the alarming flag, and learning which
     * incident that is takes the acknowledgement it broadcasts anyway
     * ([PeerRecord.incident]). Recognising only the first kind left the second
     * kind able to keep the banner alive about an incident everybody had
     * finished with.
     */
    private fun standDownIncidents(now: Long) {
        if (state == GuardState.ALARM) {
            val source = if (alarmSourceDevice == Protocol.DEVICE_ID_NONE) {
                selfDeviceId
            } else {
                alarmSourceDevice
            }
            val own = incidentKey(source, alarmCounter)
            declinedIncidents[own] = now
            calledOffIncidents[own] = now
        }
        for (peer in peers.values) {
            val incident = peer.incident ?: continue
            if (peer.presence == PeerPresence.LOST) continue
            declinedIncidents[incident] = now
            calledOffIncidents[incident] = now
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
        commandCounters.remove(deviceId)
        alarmAckedBy.remove(deviceId)
        announcement?.ackedBy?.remove(deviceId)
        nameAssembler.forget(deviceId)
    }

    /**
     * Handles a peer's farewell: it is gone, and it stays gone.
     *
     * Everything about it goes at once, votes included. A phone that walks away
     * from the group having said so is not a phone that vanished, and treating
     * the two the same is what made leaving a group able to raise a siren on the
     * friends left behind.
     */
    private fun departPeer(now: Long, deviceId: Int) {
        if (deviceId == selfDeviceId || deviceId == Protocol.DEVICE_ID_NONE) return
        departedUntil[deviceId] = now + DEPARTED_IGNORE_MS
        val known = peers.containsKey(deviceId)
        forgetPeer(deviceId)
        if (known) listener.onPeerLeft(deviceId)
    }

    // =====================================================================
    // Inputs
    // =====================================================================

    /** One authenticated advertisement from a group member. */
    fun onPeerBeacon(now: Long, rssi: Int, beacon: Beacon) {
        if (beacon.deviceId == selfDeviceId) return
        // A phone that has said goodbye stays gone, whatever is still in the air
        // from it. Its last few beacons are still travelling when the farewell
        // lands, and without this the peer is dropped and immediately recreated
        // by its own echo.
        departedUntil[beacon.deviceId]?.let { if (now < it) return }

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
        // Presence is a state machine rather than a comparison, and coming back
        // buys a wider threshold for a moment ([PeerRecord.recoveredAt]). That
        // asymmetry is the dead band: without it, the one gap that crossed the
        // line was immediately followed by another, and the card flickered.
        if (peer.presence != PeerPresence.PRESENT) {
            peer.recoveredAt = now
            peer.presence = PeerPresence.PRESENT
        }
        peer.flags = beacon.flags
        peer.battery = beacon.battery
        peer.txPowerRef = beacon.txPowerRef
        peer.simulated = beacon.simulated

        // Name chunks, commands, alarms and acks all borrow the telemetry bytes,
        // so only take a motion score from packets that actually carry one. For
        // the rest, the honest reading is zero when the sender says it is still:
        // keeping the old value made a stationary phone look like a moving one
        // to the occlusion gate, which is the one gate that stops a passer-by
        // setting off the siren.
        if (beacon.carriesTelemetry) {
            peer.motionScore = beacon.motionScore
        } else if (beacon.stationary) {
            peer.motionScore = 0
        }
        if (beacon.carriesName) {
            nameAssembler.accept(beacon)?.let { name ->
                if (peer.name != name) {
                    peer.name = name
                    listener.onPeerNameLearned(peer.deviceId, name)
                }
            }
        }

        // A phone that is no longer making noise is no longer in any incident,
        // so whatever we had learned about which one goes with it. While it *is*
        // alarming the association is sticky: a phone that merely joined in
        // stops mentioning the incident once the originator falls silent, but it
        // is still in it until somebody stands it down.
        if (!beacon.alarming) peer.incident = null

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
        updatePresence(now)
        pruneVotes(now)
        prunePeers(now)
        pruneDeclinedIncidents(now)
        pruneDepartures(now)
        pruneAcks(now)
        settleAnnouncement(now)
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

    // ---- presence --------------------------------------------------------

    /**
     * Moves every peer along the presence state machine.
     *
     * This is where the group list stopped flickering. It used to be one
     * comparison evaluated fresh every tick — "has this peer been quiet for
     * longer than usual?" — and a single hard edge on a duty-cycled radio flaps
     * by construction: the calm scanner listens roughly a quarter of the time,
     * so a run of missed windows reaching the threshold is an ordinary event,
     * and each one flipped a card from "still, watched" to grey and straight
     * back on the next packet.
     *
     * Two things fix it, and both are about *asymmetry*:
     *
     * - Coming back is instant, but going away again is not. A peer that has
     *   just recovered gets twice the threshold for [PRESENCE_RECOVERY_MS], so
     *   the gap that follows a bad patch does not immediately cross the line
     *   too. That is the dead band.
     * - There is a middle state. [PeerPresence.MISSING] is worth listening
     *   harder about and worth annotating the card with, but it is not news and
     *   does not repaint anything. Only [PeerPresence.LOST] — silence no duty
     *   cycle explains — changes what the card says.
     *
     * Voting is deliberately *not* driven from here. Deciding a phone has been
     * stolen has to happen on the sharp threshold, in seconds; deciding what a
     * card should say has to happen on a blunt one. Making one number do both
     * jobs was the underlying mistake.
     */
    private fun updatePresence(now: Long) {
        for (peer in peers.values) {
            val silentFor = now - peer.lastSeenAt
            val missingAfter = missingAfterMs(peer, now)
            val next = when {
                silentFor >= missingAfter + PRESENCE_LOST_EXTRA_MS -> PeerPresence.LOST
                silentFor >= missingAfter -> PeerPresence.MISSING
                else -> PeerPresence.PRESENT
            }
            if (next == peer.presence) continue
            peer.presence = next
        }
    }

    /** How long silence has to run before a peer stops counting as present. */
    private fun missingAfterMs(peer: PeerRecord, now: Long): Long {
        val base = staleAfterMs(peer)
        return if (now - peer.recoveredAt < PRESENCE_RECOVERY_MS) base * 2 else base
    }

    private fun pruneDepartures(now: Long) {
        departedUntil.entries.removeAll { now >= it.value }
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
                // Said once, when it happens. Repeating it every tick pinned the
                // guard in SUSPICIOUS - and therefore the radios at ALERT - for
                // as long as the peer stayed quiet, and re-tuning the radios is
                // exactly what makes peers go quiet.
                if (peer.silenceNoticedAt == 0L) {
                    peer.silenceNoticedAt = now
                    noteEvidence(now)
                }
                if (now - peer.silenceNoticedAt >= LOST_CONFIRM_MS) {
                    castOwnVote(now, peer.deviceId, VoteType.LOST)
                }
                continue
            }
            peer.silenceNoticedAt = 0L
            // It is back, so anything we told the group about it having vanished
            // has to be taken back *here*, before any other test can skip past
            // this point. A LOST vote used to survive the peer's return for the
            // whole vote lifetime, and a second observer agreeing with a
            // withdrawn accusation is a siren about a phone lying on the towel.
            withdrawLostVote(peer.deviceId)

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

    /** Retracts only a "this phone has vanished" vote, leaving suspicion alone. */
    private fun withdrawLostVote(subject: Int) {
        val observers = votes[subject] ?: return
        if (observers[selfDeviceId]?.type == VoteType.LOST) observers.remove(selfDeviceId)
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
        calledOffIncidents.entries.removeAll { now - it.value > DECLINED_FORGET_MS }
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

            Protocol.EVENT_ALARM, Protocol.EVENT_BOX_ALARM, Protocol.EVENT_PANIC ->
                onAlarmEvent(now, beacon)

            Protocol.EVENT_ACK -> onAck(beacon)

            // Every group command goes through the same door: confirmed, obeyed
            // once per press, passed on once per press, and dropped when it is
            // stale. See [EngineListener.onRelayGroupCommand] for why relaying is
            // what finally made these dependable, and [Protocol.EVENT_ACK] for
            // why confirming them is what finally made them *stop*.
            Protocol.EVENT_ALARM_CLEAR, Protocol.EVENT_DISARM_ALL,
            Protocol.EVENT_ARM_ALL, Protocol.EVENT_LEAVE,
            -> onGroupCommand(now, beacon)
        }
    }

    /**
     * One phone's announcement that somebody is being robbed.
     *
     * Exactly one phone puts an incident on the air — the one that decided on it
     * — so the sender's id and the counter it carries name the incident between
     * them, with no extra bytes. Everything downstream keys off that name: what
     * has been declined, what is still worth making noise about, and what the
     * group list is allowed to claim.
     */
    private fun onAlarmEvent(now: Long, beacon: Beacon) {
        if (!acceptControl(beacon)) return
        val origin = beacon.deviceId
        val incident = incidentKey(origin, beacon.counter)
        peers[origin]?.incident = incident

        // Confirmed whatever we go on to decide about it. An announcer that is
        // being ignored still deserves to know it has been heard: that is what
        // takes the packet off the air, and a packet that is off the air cannot
        // come back thirty seconds later claiming an incident that is over.
        queueAck(now, origin, beacon.counter)

        if (declined(now, incident)) return
        // A panic is somebody's thumb on a button, so it is news by definition
        // and skips the echo window - but not a decision the user has already
        // made about this very incident, or nobody could ever silence it.
        if (beacon.eventType != Protocol.EVENT_PANIC && now < alarmSuppressedUntil) return
        if (state == GuardState.ALARM) return

        val reason = when (beacon.eventType) {
            Protocol.EVENT_BOX_ALARM -> AlarmReason.BOX_TAKEN
            Protocol.EVENT_PANIC -> AlarmReason.PANIC
            else -> AlarmReason.RELAYED
        }
        val subject = if (beacon.eventType == Protocol.EVENT_BOX_ALARM) {
            Protocol.DEVICE_ID_NONE
        } else {
            beacon.subjectId
        }
        raiseAlarm(
            now, reason, subject,
            originatedHere = false, sourceDevice = origin, counter = beacon.counter,
        )
    }

    /**
     * "I have received `(subjectId, counter)`."
     *
     * Two jobs. For our own announcements it is the confirmation that lets them
     * stop. For anybody else's it is how we learn which incident a phone that
     * merely *joined in* is part of: such a phone announces nothing itself, so
     * before acknowledgements existed its alarming flag was an anonymous claim
     * that the group was in trouble — impossible to match against an incident
     * the user had already called off, and therefore impossible to stop
     * believing.
     */
    private fun onAck(beacon: Beacon) {
        val origin = beacon.subjectId
        val counter = beacon.counter
        if (beacon.alarming) {
            peers[beacon.deviceId]?.incident = incidentKey(origin, counter)
        }
        if (origin != selfDeviceId) return
        if (state == GuardState.ALARM && alarmOriginatedHere && counter == alarmCounter) {
            alarmAckedBy.add(beacon.deviceId)
        }
        announcement?.let { if (it.counter == counter) it.ackedBy.add(beacon.deviceId) }
    }

    private fun onGroupCommand(now: Long, beacon: Beacon) {
        if (!acceptControl(beacon)) return
        val origin = commandOrigin(beacon)
        // Checked before the command is accepted, not after. A departure clears
        // everything remembered about the phone that left, its command counter
        // included, so a relay still circulating would otherwise look brand new
        // to every phone that had already handled it - and be passed on again,
        // and again.
        departedUntil[origin]?.let { if (now < it) return }
        // Confirmed on every copy heard, not only the first. Obeying a command
        // happens once; being *heard* has to keep being true for as long as the
        // announcer is still asking, or a confirmation lost to a closed scan
        // window would leave it repeating for its whole window anyway - which is
        // precisely the twenty-five seconds of stale packets this is here to
        // remove.
        queueAck(now, origin, beacon.counter)
        if (!acceptGroupCommand(beacon)) return

        when (beacon.eventType) {
            Protocol.EVENT_ALARM_CLEAR -> {
                suppressRelayedAlarms(now)
                // Every incident in earshot is now one the group has called off,
                // whether or not there is a siren here to stop. A peer that has
                // not caught up must not be able to drag this phone back in, and
                // its echoes must not keep claiming the group is alarming.
                standDownIncidents(now)
                if (state == GuardState.ALARM || state == GuardState.PENDING) {
                    clearAlarm(now)
                }
            }

            Protocol.EVENT_DISARM_ALL -> {
                suppressRelayedAlarms(now)
                if (state != GuardState.DISARMED) {
                    disarm(now, groupWide = true)
                } else {
                    standDownIncidents(now)
                }
            }

            Protocol.EVENT_ARM_ALL -> if (state == GuardState.DISARMED) arm(now)

            Protocol.EVENT_LEAVE -> departPeer(now, origin)
        }
        relayCommand(beacon)
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
     * @return true when this copy is a *later press* than anything already
     *   obeyed from the same device — the only time a command is applied, and
     *   the only time it is passed on.
     */
    private fun acceptGroupCommand(beacon: Beacon): Boolean {
        val origin = commandOrigin(beacon)
        val counter = beacon.counter
        val last = commandCounters[origin]
        if (last != null && !Protocol.isNewerCommand(counter, last)) return false
        commandCounters[origin] = counter
        return true
    }

    private fun relayCommand(beacon: Beacon) {
        listener.onRelayGroupCommand(
            beacon.eventType, commandOrigin(beacon), beacon.counter,
        )
    }

    /** True when the user has already said no to this exact incident. */
    private fun declined(now: Long, incident: Int): Boolean {
        if (!declinedIncidents.containsKey(incident)) return false
        // Still audible, so keep declining it. The entry expires only once the
        // source genuinely stops, which is what keeps a *second* theft moments
        // later audible.
        declinedIncidents[incident] = now
        return true
    }

    // =====================================================================
    // Announcements and acknowledgements
    // =====================================================================

    /**
     * Notes an announcement this phone has heard, so it can be confirmed.
     *
     * Refreshed on every repeat, so a phone answers for exactly as long as
     * somebody keeps asking and then stops of its own accord.
     */
    private fun queueAck(now: Long, origin: Int, counter: Int) {
        if (origin == selfDeviceId || origin == Protocol.DEVICE_ID_NONE) return
        val key = incidentKey(origin, counter)
        val existing = pendingAcks[key]
        if (existing == null) {
            pendingAcks[key] = PendingAck(origin, counter, now + ACK_REPEAT_MS)
        } else {
            existing.until = now + ACK_REPEAT_MS
        }
    }

    private fun pruneAcks(now: Long) {
        pendingAcks.entries.removeAll { now >= it.value.until }
    }

    /** Confirmations this phone currently owes, as `origin to counter`. */
    fun acksToSend(now: Long): List<Pair<Int, Int>> =
        pendingAcks.values.filter { now < it.until }.map { it.origin to it.counter }

    /**
     * Records a group command this phone has just issued.
     *
     * Two jobs. The counter goes into [commandCounters] so the relays coming
     * back from everybody else are recognised as the same press rather than a
     * new one, and the announcement is remembered so it can *finish* — see
     * [announcementNeedsAir].
     */
    fun noteOwnGroupCommand(eventType: Int, counter: Int, now: Long) {
        commandCounters[selfDeviceId] = counter
        announcement = Announcement(eventType, counter, now)
    }

    /**
     * Whether the group command this phone issued still has to be on the air.
     *
     * A command used to be repeated for a flat twenty-five seconds, because
     * nothing could tell it whether anybody had heard — sized against the
     * slowest imaginable listener and paid in full every single time. With
     * confirmations it normally stops inside two seconds, and that is the single
     * biggest reduction in stale packets in the whole protocol: almost
     * everything that used to arrive describing a state of the world that had
     * already changed was a repeat nobody needed.
     *
     * Re-evaluated continuously rather than decided once, which is what makes
     * stopping safe. A phone that was out of range, or asleep, or had not
     * finished booting has not confirmed — so the moment it appears the
     * announcement goes back on the air by itself.
     */
    fun announcementNeedsAir(now: Long): Boolean {
        val current = announcement ?: return false
        return !current.complete && needsAir(current.ackedBy, current.startedAt, now)
    }

    /** Whether this phone's own incident still has to occupy the event slot. */
    fun alarmNeedsAir(now: Long): Boolean {
        if (state != GuardState.ALARM || !alarmOriginatedHere) return false
        return needsAir(alarmAckedBy, alarmAnnouncedAt, now)
    }

    /**
     * @param acked who has confirmed so far.
     * @param since when the announcement began; every announcement gets a short
     *        unconditional burst, so a phone that is alone - or whose peers have
     *        not been heard from yet - still says its piece rather than falling
     *        silent because nobody happened to be listed.
     */
    private fun needsAir(acked: Set<Int>, since: Long, now: Long): Boolean {
        if (now - since < ANNOUNCE_MIN_MS) return true
        // A peer that is merely MISSING still counts: it is almost always a
        // missed scan window rather than a phone that has gone, and giving up on
        // it would mean "disarm all" quietly skipping somebody.
        return peers.values.any { it.presence != PeerPresence.LOST && it.deviceId !in acked }
    }

    private fun settleAnnouncement(now: Long) {
        val current = announcement ?: return
        if (!current.complete && !needsAir(current.ackedBy, current.startedAt, now)) {
            current.complete = true
        }
    }

    /** The group command this phone is announcing, or [Protocol.EVENT_NONE]. */
    val announcedEvent: Int
        get() = announcement?.takeIf { !it.complete }?.eventType ?: Protocol.EVENT_NONE

    /** `confirmed to expected` for the announcement in flight; both zero if none. */
    fun announcementProgress(): Pair<Int, Int> {
        val current = announcement?.takeIf { !it.complete } ?: return 0 to 0
        val reachable = peers.values.filter { it.presence != PeerPresence.LOST }
        val confirmed = reachable.count { it.deviceId in current.ackedBy }
        return confirmed to reachable.size
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
        counter: Int = 0,
    ) {
        if (state == GuardState.ALARM) return
        alarmReason = reason
        alarmOriginatedHere = originatedHere
        alarmSubject = subjectId
        alarmSourceDevice = sourceDevice
        // An incident decided here is named here, from the same monotonic
        // counter every other announcement uses. One that is only being joined
        // keeps the name it arrived with, so both ends agree on what to
        // acknowledge, and on what has been called off.
        alarmCounter = if (originatedHere) nextCounter() else counter
        alarmAckedBy.clear()
        alarmAnnouncedAt = now
        alarmSince = now
        pendingSince = 0L
        transition(now, GuardState.ALARM)
        listener.onAlarmRaised(reason, subjectId)
        if (!originatedHere) return
        listener.onAlarmAnnounced(wireEventFor(reason), subjectId)
    }

    private fun nextCounter(): Int = counterSource?.invoke() ?: run {
        fallbackCounter = (fallbackCounter + 1) and 0xFF
        fallbackCounter
    }

    /**
     * Which event carries an alarm of this kind on the wire.
     *
     * A panic used to go out as a plain `EVENT_ALARM`, which quietly made the
     * one thing the panic button is documented to do - get through immediately,
     * even in the seconds after a group stop - impossible: nothing ever put
     * `EVENT_PANIC` on the air, so the code that exempts it from the echo window
     * could never run, and a panic pressed just after somebody called an
     * incident off was swallowed like any other echo.
     */
    private fun wireEventFor(reason: AlarmReason): Int = when (reason) {
        AlarmReason.BOX_TAKEN -> Protocol.EVENT_BOX_ALARM
        AlarmReason.PANIC -> Protocol.EVENT_PANIC
        else -> Protocol.EVENT_ALARM
    }

    /** The event this phone should be repeating while its own siren runs. */
    fun selfAlarmEvent(): Int = alarmReason?.let { wireEventFor(it) } ?: Protocol.EVENT_ALARM

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
        // A peer we have temporarily lost track of is worth a few seconds of
        // fast radio: it is nearly always a missed scan window, and hearing it
        // again within a second is what stops a duty cycle being read as a
        // theft. A peer that is properly LOST is not coming back on that
        // timescale, so the radios drop again rather than spending the afternoon
        // on a phone that has gone home.
        if (next == RadioProfile.CALM &&
            peers.values.any { it.presence == PeerPresence.MISSING }
        ) {
            next = RadioProfile.ALERT
        }
        if (next == radioProfile) return
        // Escalation is immediate - that is the entire point of it. Coming back
        // down waits out the dwell, so the profile cannot flap; see
        // [radioProfileSince] for what flapping costs.
        if (next.ordinal < radioProfile.ordinal && now - radioProfileSince < RADIO_DWELL_MS) {
            return
        }
        radioProfile = next
        radioProfileSince = now
        listener.onRadioProfileChanged(next)
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
                presence = peer.presence,
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
        val (confirmed, expected) = announcementProgress()
        val stopping = announcedEvent == Protocol.EVENT_ALARM_CLEAR ||
            announcedEvent == Protocol.EVENT_DISARM_ALL
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
            groupAlarmActive = groupAlarmActive(),
            stopPending = stopping,
            stopConfirmed = confirmed,
            stopExpected = expected,
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
        return slotFree && activeVotes(now).isEmpty() && acksToSend(now).isEmpty()
    }

    /**
     * True while the *group* is in an incident, whether or not this phone is.
     *
     * Answered from evidence rather than from memory, which is the whole fix.
     * It used to be "did any alarming beacon arrive in the last twelve seconds",
     * refreshed by *every* such beacon including echoes of the very incident the
     * user had just called off — so after everybody had stopped, each straggler
     * put "the group is still alarming" back on screen for another twelve
     * seconds, it aged out, and the next straggler brought it back. That is the
     * message that kept popping up at random about an incident that was over.
     *
     * Now a peer counts only while all three hold: we can currently hear it, its
     * latest beacon says it is making noise, and the incident it is in is not one
     * that has been stood down here. There is nothing left to age out, so there
     * is nothing left to flicker.
     */
    fun groupAlarmActive(): Boolean =
        state == GuardState.ALARM || peers.values.any { peerAlarming(it) }

    private fun peerAlarming(peer: PeerRecord): Boolean {
        if (peer.presence != PeerPresence.PRESENT) return false
        if (!peer.alarming) return false
        // An incident we have not managed to name yet is taken at face value:
        // better a banner that is a second early than one that never appears.
        val incident = peer.incident ?: return true
        return !calledOffIncidents.containsKey(incident)
    }

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
         * How much longer than [PeerPresence.MISSING] silence has to run before a
         * peer counts as [PeerPresence.LOST].
         *
         * Wide on purpose. MISSING is reached at ten seconds, which a bad patch
         * of radio produces regularly; this is the point at which silence is no
         * longer explicable by any duty cycle, and it is the only point at which
         * a card is allowed to change what it says.
         */
        const val PRESENCE_LOST_EXTRA_MS = 15_000L

        /**
         * How long after coming back a peer gets twice the usual patience.
         *
         * The dead band. Gaps are not independent - a phone behind a body, or a
         * radio having a bad minute, produces several in a row - so the threshold
         * that has just been crossed is exactly the one most likely to be crossed
         * again a moment later. Widening it briefly turns a burst of gaps into
         * one event instead of four.
         */
        const val PRESENCE_RECOVERY_MS = 20_000L

        /**
         * Minimum time a profile change has to hold before it can be undone.
         *
         * Only ever delays coming *down*. Re-tuning takes the advertiser off the
         * air while the set is rebuilt and spends one of the five scan starts
         * Android allows per thirty seconds, so a profile that flaps costs
         * exactly the thing it is trying to protect - and two phones flapping in
         * step keep each other there.
         */
        const val RADIO_DWELL_MS = 6_000L

        /**
         * How long an announcement is repeated before confirmations can stop it.
         *
         * A phone with no peers listed yet - or none it has heard from since
         * booting - still has to say its piece, so every announcement gets this
         * much unconditionally.
         */
        const val ANNOUNCE_MIN_MS = 2_000L

        /** How long a confirmation keeps being repeated after it was last asked for. */
        const val ACK_REPEAT_MS = 5_000L

        /** How long a departed phone's remaining packets stay ignored. */
        const val DEPARTED_IGNORE_MS = 60_000L

        /** How long a declined incident stays declined after it was last heard. */
        const val DECLINED_FORGET_MS = 12_000L

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

        /**
         * The name of one announcement: who made it, and which one of theirs it
         * is.
         *
         * Used for incidents and for group commands alike, because they are the
         * same kind of thing — a decision by one phone that everybody else has to
         * agree about. Naming them is what makes them declinable, supersedable
         * and acknowledgeable; before that an incident could only be described
         * by its symptoms, and two incidents with the same symptoms were
         * indistinguishable.
         */
        internal fun incidentKey(origin: Int, counter: Int): Int =
            ((origin and 0xFFFF) shl 16) or (counter and 0xFFFF)
    }
}

private data class VoteRecord(val type: VoteType, val atMs: Long)

/** A group command this phone has issued and is waiting to have confirmed. */
private class Announcement(
    val eventType: Int,
    val counter: Int,
    val startedAt: Long,
) {
    val ackedBy = HashSet<Int>()

    /** Set once everybody reachable has confirmed, so it is never restarted. */
    var complete = false
}

/** An announcement heard from somebody else, still owed a confirmation. */
private class PendingAck(val origin: Int, val counter: Int, var until: Long)

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

    /** How well this peer is currently being heard; see [PeerPresence]. */
    var presence: PeerPresence = PeerPresence.PRESENT

    /**
     * When this peer was last heard after a spell of silence.
     *
     * Starts at first sight, because a phone we have only just met has no gap
     * history at all and deserves the same patience as one recovering from a bad
     * minute of radio.
     */
    var recoveredAt: Long = firstSeenAt

    /**
     * Which incident this peer is currently part of, as `(origin, counter)`.
     *
     * Learned from the alarm it announces, or - for a phone that merely joined
     * in and therefore announces nothing itself - from the confirmation it
     * broadcasts. Sticky while the peer keeps saying it is making noise, because
     * a joiner stops mentioning the incident the moment the originator falls
     * silent but is still very much in it until somebody stands it down.
     */
    var incident: Int? = null

    /**
     * The worst gap between two beacons seen from this peer recently.
     *
     * A plain maximum over a window of recent arrivals, deliberately not a
     * decaying average. The question it answers is "how long a silence is normal
     * here", and an average is the wrong shape twice over: it is dragged to
     * milliseconds by one burst of fast scanning, and it *decays back* — so a few
     * quiet seconds after an unusually long gap would return the threshold to its
     * floor, and the next perfectly ordinary long gap would flip the peer to
     * "no signal" for a tick. That flicker is precisely what this is here to stop.
     */
    val worstRecentGapMs: Double get() = gaps.max()

    private val gaps = RollingMax(GAP_WINDOW)

    fun noteArrivalGap(gapMs: Long) {
        gaps.add(gapMs.toDouble().coerceAtMost(MAX_TRACKED_GAP_MS))
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

        /** Arrivals remembered. About a minute at the default duty cycle. */
        const val GAP_WINDOW = 32
    }
}
