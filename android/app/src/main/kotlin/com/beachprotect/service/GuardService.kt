package com.beachprotect.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.beachprotect.MainActivity
import com.beachprotect.R
import com.beachprotect.alarm.AlarmPlayer
import com.beachprotect.ble.BeaconComposer
import com.beachprotect.ble.BleAdvertiser
import com.beachprotect.ble.BleScanner
import com.beachprotect.ble.Protocol
import com.beachprotect.bridge.Codec
import com.beachprotect.guard.AlarmReason
import com.beachprotect.guard.AlarmTarget
import com.beachprotect.guard.BoxGuard
import com.beachprotect.guard.BoxSignal
import com.beachprotect.guard.EngineListener
import com.beachprotect.guard.GuardState
import com.beachprotect.guard.GuardWarning
import com.beachprotect.guard.MotionSignal
import com.beachprotect.guard.RadioProfile
import com.beachprotect.guard.ThreatEngine
import com.beachprotect.sensors.MotionMonitor
import com.beachprotect.sim.Simulator
import com.beachprotect.store.GuardStore
import com.beachprotect.ui.AlarmActivity

/**
 * The always-on guard.
 *
 * Everything that has to keep working while the phone lies face down on a towel
 * lives in here, natively. The Flutter engine is only a window onto this
 * service: it can be torn down at any moment to save memory and battery without
 * the guard noticing.
 *
 * ## Keeping the CPU asleep
 *
 * The tick loop is deliberately *not* backed by a permanent wake lock. In the
 * calm state the loop is driven by events that wake the CPU anyway - BLE scan
 * results arrive through the Bluetooth stack, and significant-motion triggers
 * come from a hardware wake-up sensor - so the processor is allowed to sleep in
 * between. A wake lock is taken only once something is actually happening
 * ([RadioProfile.ALERT] and above), where precise timing starts to matter.
 *
 * An inexact [AlarmManager] heartbeat covers the single case where nothing at
 * all would wake us: a two-phone group whose only peer is switched off.
 */
class GuardService : Service(),
    EngineListener,
    BleScanner.Listener,
    MotionMonitor.Listener,
    BoxGuard.Listener,
    Simulator.Listener {

    private lateinit var store: GuardStore
    private lateinit var engine: ThreatEngine

    /** Identity the current [engine] was built for; see [applyConfig]. */
    private var engineDeviceId = 0
    private var lastRadioCheckAt = 0L
    private lateinit var composer: BeaconComposer
    private lateinit var advertiser: BleAdvertiser
    private lateinit var scanner: BleScanner
    private lateinit var motion: MotionMonitor
    private lateinit var boxGuard: BoxGuard
    private lateinit var alarmPlayer: AlarmPlayer
    private lateinit var simulator: Simulator

    private val handler = Handler(Looper.getMainLooper())
    private var adapter: BluetoothAdapter? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var radioProfile = RadioProfile.CALM

    /**
     * True while a group command is being repeated onto the air.
     *
     * The radios are lifted out of the calm profile for the duration, because
     * the phone that has to *hear* the command is the one thing the sender
     * cannot speed up: at the calm advertising interval of one second, a
     * listener sampling for half a second every five would catch roughly one
     * packet of the whole announcement.
     */
    private var announcing = false
    private var tickScheduled = false
    private var lastNotificationAt = 0L
    private var lastBatteryReadAt = 0L
    private var batteryPercent = 100
    private var started = false

    /** Advertisements that got past the hardware filter, ours or not. */
    private var packetsHeard = 0L

    /** ...and how many of those authenticated as members of this group. */
    private var beaconsHeard = 0L

    /**
     * Set when the app was swiped away mid-incident.
     *
     * Swiping the app out of Recents stops the guard - but not while it is
     * actually screaming, because that would hand anyone holding the phone a
     * one-gesture way to silence it. The shutdown is deferred to the moment the
     * incident is over instead.
     */
    private var stopWhenIdle = false

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate() {
        super.onCreate()
        store = GuardStore(this)
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        engineDeviceId = store.deviceId
        engine = ThreatEngine(engineDeviceId, this, store.engineConfig).also {
            it.selfName = store.selfName
        }
        composer = BeaconComposer(store)
        advertiser = BleAdvertiser(adapter)
        scanner = BleScanner(adapter, this)
        motion = MotionMonitor(this, this)
        boxGuard = BoxGuard(this, adapter, this)
        alarmPlayer = AlarmPlayer(this)
        simulator = Simulator(engine, this)

        createChannels()
        registerReceiver()
        alarmPlayer.prepare()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always get into the foreground first: Android gives us only a few
        // seconds after startForegroundService before it kills us.
        startForegroundSafely()

        // A null intent means START_STICKY resurrected us after the process was
        // killed. That is wanted while the app is still sitting in Recents - a
        // guard should survive being paged out - but several OEMs kill the
        // process on a swipe *without* delivering onTaskRemoved, and there the
        // resurrection would put the notification straight back up for an app
        // the user has closed. If the task is gone, so are we.
        if (intent == null && !hasLiveTask()) {
            Log.i(TAG, "sticky restart with no task; standing down")
            shutdown()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            GuardIntents.ACTION_START, null -> ensureStarted()
            GuardIntents.ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            GuardIntents.ACTION_ARM -> {
                ensureStarted()
                engine.arm(now())
                store.armed = true
            }

            GuardIntents.ACTION_DISARM -> {
                ensureStarted()
                // If we were making noise, this is the owner saying "that was
                // me" - so the rest of the group is told to stand down too.
                //
                // Only from ALARM. A phone still in its grace period has told
                // the group nothing yet, so there is nothing to call off - and
                // calling off an incident that never happened would start the
                // echo window on every phone in the group for no reason.
                if (engine.state == GuardState.ALARM) {
                    announceGroupCommand(Protocol.EVENT_ALARM_CLEAR)
                }
                engine.disarm(now())
                store.armed = false
            }

            // "That was a false alarm": silence the whole group and leave every
            // phone, including this one, still guarding. This is deliberately not
            // gated on this phone still alarming - the person who has already
            // silenced their own handset is exactly the person who needs to reach
            // the ones that are still screaming.
            GuardIntents.ACTION_CLEAR_ALARM -> {
                ensureStarted()
                announceGroupCommand(Protocol.EVENT_ALARM_CLEAR)
                engine.stopGroupAlarm(now())
            }

            GuardIntents.ACTION_DISARM_GROUP -> {
                ensureStarted()
                announceGroupCommand(Protocol.EVENT_DISARM_ALL)
                engine.disarm(now(), groupWide = true)
                store.armed = false
            }

            GuardIntents.ACTION_ARM_GROUP -> {
                ensureStarted()
                announceGroupCommand(Protocol.EVENT_ARM_ALL)
                engine.arm(now())
                store.armed = true
            }

            GuardIntents.ACTION_PANIC -> {
                ensureStarted()
                engine.panic(now())
            }

            GuardIntents.ACTION_TEST_ALARM -> {
                ensureStarted()
                engine.triggerTestAlarm(now())
            }

            GuardIntents.ACTION_CONFIG_CHANGED -> applyConfig()

            GuardIntents.ACTION_SIM_START -> {
                ensureStarted()
                val name = intent.getStringExtra(GuardIntents.EXTRA_SCENARIO)
                val scenario = runCatching { Simulator.Scenario.valueOf(name ?: "") }.getOrNull()
                if (scenario != null) {
                    // Simulator first, engine second. Arming the engine raises
                    // a state change, and the handler for that syncs the
                    // persisted armed flag unless a rehearsal is in progress -
                    // so the rehearsal has to be in progress by then. A
                    // rehearsal must not leave the phone armed afterwards, and
                    // stopSimulation() reads store.armed to know that.
                    simulator.start(scenario, now())
                    if (engine.state == GuardState.DISARMED) engine.arm(now())
                }
            }

            GuardIntents.ACTION_SIM_STOP -> stopSimulation()

            GuardIntents.ACTION_LOCKSCREEN_TEST -> {
                // Gives the user time to lock the phone, then raises the real
                // disarm surface so they can verify it actually appears.
                handler.postDelayed({
                    notifyPending(store.engineConfig.pickupGraceMs)
                    raiseAlarmScreen(
                        GuardState.PENDING, null, store.deviceId,
                        store.engineConfig.pickupGraceMs,
                    )
                    alarmPlayer.playWarningChirp(store.sirenVolume)
                    handler.postDelayed({
                        notificationManager().cancel(NOTIFICATION_ID_ALARM)
                        broadcastUpdate(
                            engine.state, null, Protocol.DEVICE_ID_NONE, null, 0,
                        )
                    }, LOCKSCREEN_TEST_HOLD_MS)
                }, LOCKSCREEN_TEST_DELAY_MS)
            }

            GuardIntents.ACTION_HEARTBEAT -> {
                // Nothing to do beyond having been woken; the tick below does
                // the work.
            }
        }

        scheduleTick(immediate = true)
        scheduleHeartbeat()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Puts a group command this phone has decided on into the air, and tells the
     * engine it has already accounted for it.
     *
     * That second half matters: every phone that hears a command passes it on, so
     * within a second or two the issuer is hearing its own decision echoed back
     * from everybody else. Without recording it as already seen, the issuer would
     * treat each echo as a fresh command and start relaying it again.
     */
    private fun announceGroupCommand(eventType: Int) {
        val now = now()
        engine.noteOwnGroupCommand(now, eventType)
        composer.queueControl(now, eventType, store.deviceId)
        advertiser.updatePayload(composeBeacon())
        restartRadios()
    }

    /**
     * The user swiped BeachProtect out of Recents.
     *
     * That is treated as "stop", not as "keep going quietly". The guard used to
     * outlive the app entirely, which is defensible for a theft alarm but is
     * not what people expect from a closed app: an ongoing notification for
     * something with no window anywhere is indistinguishable from an app that
     * will not go away.
     *
     * The one exception is an incident in progress. Silencing a live siren by
     * swiping a card off the Recents screen would be a gift to whoever is
     * holding the phone, so the shutdown waits for the alarm to be resolved.
     *
     * Requires `android:stopWithTask="false"` in the manifest: with `true` the
     * system kills the service outright and this is never called, so there
     * would be nowhere to make that distinction - or to shut the radios, the
     * wake lock and the heartbeat alarm down in an orderly way.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed; state=${engine.state}")
        if (engine.state == GuardState.ALARM || engine.state == GuardState.PENDING) {
            stopWhenIdle = true
        } else {
            shutdown()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** Whether BeachProtect still has a task of its own in Recents. */
    private fun hasLiveTask(): Boolean = runCatching {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        manager.appTasks.isNotEmpty()
        // Optimistic on failure: a guard that stops because a system call threw
        // is worse than a notification that lingers.
    }.getOrDefault(true)

    private fun ensureStarted() {
        if (started) return
        started = true
        applyConfig()
        motion.start()
        motion.setGuardActive(engine.state != GuardState.DISARMED)
        restartRadios()
        // Restore the armed state after a reboot or a process kill.
        if (store.armed && engine.state == GuardState.DISARMED) {
            engine.arm(now())
        }
    }

    private fun shutdown() {
        started = false
        stopWhenIdle = false
        handler.removeCallbacksAndMessages(null)
        tickScheduled = false
        cancelHeartbeat()
        stopSimulation()
        motion.stop()
        scanner.stop()
        advertiser.stop()
        boxGuard.stop()
        alarmPlayer.release()
        releaseWakeLock()
        runCatching { unregisterReceiver(systemReceiver) }
        // One last snapshot, so an app that is still on screen shows a stopped
        // guard rather than freezing on the last thing it happened to see.
        publishSnapshot()
        instance = null
        // Deliberately *not* clearing snapshotListener. It belongs to the
        // Flutter bridge, which sets it when the UI subscribes and drops it
        // when the UI goes away. Clearing it here left a live subscription
        // wired to nothing: leaving a group stops the service, and every
        // screen the user opened afterwards - including the new group's -
        // showed stale, empty state until the app was restarted. That is what
        // "it said Bluetooth was off until I restarted" was.
        stopForegroundCompat()
        // Belt and braces. STOP_FOREGROUND_REMOVE takes the ongoing one away,
        // but an alarm notification posted on the high-importance channel is a
        // separate one and would otherwise sit there after the app is gone -
        // which is the exact thing that makes a closed app feel not closed.
        runCatching {
            notificationManager().cancel(NOTIFICATION_ID_GUARD)
            notificationManager().cancel(NOTIFICATION_ID_ALARM)
        }
        stopSelf()
    }

    // =====================================================================
    // Configuration
    // =====================================================================

    private fun applyConfig() {
        // Creating or joining a group changes this device's derived id. The
        // engine keys everything - votes, consensus, "is this about me?" - off
        // that id, so a stale one would quietly break the whole detector. It is
        // immutable by design, so the engine is rebuilt instead.
        if (store.deviceId != engineDeviceId) {
            rebuildEngine()
            return
        }
        engine.config = store.engineConfig
        engine.selfName = store.selfName
        val boxAddress = store.boxAddress.takeIf { store.boxEnabled }
        boxGuard.start(boxAddress)
        engine.configureBox(
            configured = store.boxEnabled && boxAddress != null,
            name = store.boxName,
            address = boxAddress,
            guardedHere = boxGuard.guardingHere,
        )
        restartRadios()
    }

    /** Builds a fresh engine for the current identity, preserving armed state. */
    private fun rebuildEngine() {
        val wasArmed = ::engine.isInitialized && engine.state != GuardState.DISARMED
        engineDeviceId = store.deviceId
        engine = ThreatEngine(engineDeviceId, this, store.engineConfig).also {
            it.selfName = store.selfName
        }
        simulator = Simulator(engine, this)
        engine.configureBox(
            configured = store.boxEnabled && store.boxAddress != null,
            name = store.boxName,
            address = store.boxAddress,
            guardedHere = boxGuard.guardingHere,
        )
        boxGuard.start(store.boxAddress.takeIf { store.boxEnabled })
        if (wasArmed || store.armed) engine.arm(now())
        restartRadios()
    }

    /**
     * Recovers radios that failed to come up.
     *
     * A phone whose advertiser silently died is invisible to the rest of the
     * group - it looks protected but nobody can watch it - so this is checked
     * periodically rather than only when something else happens to change.
     */
    private fun ensureRadiosHealthy(now: Long) {
        if (!started || !store.hasGroup) return
        if (now - lastRadioCheckAt < RADIO_CHECK_MS) return
        lastRadioCheckAt = now
        if (adapter?.state != BluetoothAdapter.STATE_ON) return

        val advertiserDown = advertiser.supported && !advertiser.running && !advertiser.starting
        // ...and "up, but at the wrong rate" is just as broken while a group
        // command is going out: the whole point of the lift is that the listener's
        // duty cycle is the one thing the sender cannot change.
        val wrongRate = advertiser.running &&
            advertiser.activeProfile != null &&
            advertiser.activeProfile != effectiveRadioProfile()
        if (advertiserDown || wrongRate || !scanner.running) {
            Log.w(
                TAG,
                "radio health: advertising=${advertiser.running}@${advertiser.activeProfile} " +
                    "wanted=${effectiveRadioProfile()} scanning=${scanner.running}",
            )
            restartRadios()
        }
    }

    private fun restartRadios() {
        if (!started) return
        if (!store.hasGroup) return
        if (adapter?.state != BluetoothAdapter.STATE_ON) {
            engine.setExternalWarning(GuardWarning.BLUETOOTH_OFF, true)
            scanner.stop()
            advertiser.stop()
            return
        }
        engine.setExternalWarning(GuardWarning.BLUETOOTH_OFF, false)
        // Either the chipset cannot advertise, or it accepted the request and
        // then rejected it. Both mean the same thing to the user: the rest of
        // the group cannot see this phone.
        engine.setExternalWarning(
            GuardWarning.ADVERTISING_UNAVAILABLE,
            !advertiser.supported || advertiser.startRejected,
        )

        val profile = effectiveRadioProfile()
        scanner.apply(profile, store.powerProfile, store.boxBleAddress.takeIf { store.boxEnabled })
        advertiser.start(profile, composeBeacon())
    }

    /** The engine's profile, lifted while a group command is going out. */
    private fun effectiveRadioProfile(): RadioProfile =
        if (radioProfile == RadioProfile.CALM && composer.controlPending(now())) {
            RadioProfile.ALERT
        } else {
            radioProfile
        }

    // =====================================================================
    // Tick loop
    // =====================================================================

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun scheduleTick(immediate: Boolean = false) {
        if (!started) return
        if (immediate) {
            handler.removeCallbacks(tickRunnable)
            handler.post(tickRunnable)
            tickScheduled = true
            return
        }
        if (tickScheduled) return
        tickScheduled = true
        handler.postDelayed(tickRunnable, tickIntervalMs())
    }

    private fun tickIntervalMs(): Long = when (radioProfile) {
        RadioProfile.CALM -> 1_000L
        RadioProfile.ALERT, RadioProfile.CRITICAL -> 500L
    }

    private val tickRunnable = Runnable {
        tickScheduled = false
        // The tick loop reschedules itself, so an exception escaping here would
        // stop the guard permanently and silently - armed on screen, dead in
        // fact. Nothing is worth that, so failures are logged and the loop
        // carries on.
        try {
            doTick()
        } catch (e: Exception) {
            Log.e(TAG, "guard tick failed", e)
        }
        scheduleTick()
    }

    private fun doTick() {
        val now = now()
        readBatteryOccasionally(now)
        ensureRadiosHealthy(now)
        // Entering or leaving an announcement changes how hard the radios are
        // driven, and nothing else would notice: the engine's own profile has
        // not moved.
        val nowAnnouncing = composer.controlPending(now)
        if (nowAnnouncing != announcing) {
            announcing = nowAnnouncing
            restartRadios()
        }
        simulator.tick(now)
        engine.tick(now)
        advertiser.updatePayload(composeBeacon())
        publishSnapshot()
        updateNotification(now)
    }

    private fun composeBeacon(): ByteArray {
        // The alarm event is sticky while alarming, so it has to be suppressed
        // here too and not only in onBroadcastEvent - otherwise a rehearsal
        // would quietly put EVENT_ALARM on the air for a second and set off
        // everyone else's phone.
        //
        // Only the phone that decided on the incident repeats it. A phone that
        // is merely joining in stays quiet on the wire, so there is exactly one
        // thing to silence; see ThreatEngine.alarmOriginatedHere.
        val alarming = engine.state == GuardState.ALARM &&
            engine.alarmOriginatedHere && !rehearsing
        val alarmEvent = if (engine.alarmReason == AlarmReason.BOX_TAKEN) {
            Protocol.EVENT_BOX_ALARM
        } else {
            Protocol.EVENT_ALARM
        }
        var flags = engine.selfFlags()
        // Likewise for the flag: a rehearsing phone must not show up as
        // "alarming" on everybody else's group list.
        if (rehearsing) flags = flags and Protocol.FLAG_ALARMING.inv()
        if (batteryPercent <= store.engineConfig.lowBatteryPercent) {
            flags = flags or Protocol.FLAG_LOW_BATTERY
        }
        if (simulator.running) flags = flags or Protocol.FLAG_SIMULATED

        return composer.compose(
            BeaconComposer.Input(
                now = now(),
                flags = flags,
                motionScore = engine.selfMotionScoreForBeacon(),
                battery = batteryPercent,
                alarming = alarming,
                alarmEvent = alarmEvent,
                alarmSubject = engine.alarmSubject,
                votes = engine.activeVotes(now()),
                allowName = engine.canBroadcastName(now()),
                name = store.selfName,
            ),
        )
    }

    private fun readBatteryOccasionally(now: Long) {
        if (now - lastBatteryReadAt < BATTERY_POLL_MS) return
        lastBatteryReadAt = now
        val manager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0..100) batteryPercent = level
    }

    // =====================================================================
    // Engine callbacks
    // =====================================================================

    override fun onStateChanged(previous: GuardState, current: GuardState) {
        Log.i(TAG, "state $previous -> $current")

        // Armed means "detect a lift within a fraction of a second", which
        // needs the accelerometer running rather than the slow hardware
        // wake-up trigger. Disarmed goes back to the near-free trigger.
        motion.setGuardActive(current != GuardState.DISARMED)

        // Plenty of things arm and disarm this phone without the UI being
        // involved at all: "arm all" from somebody else's phone, "disarm all",
        // the Disarm action on the notification, the lock-screen alarm surface.
        // The persisted flag has to follow every one of them, or the guard and
        // the app disagree about whether anything is being watched - and a
        // restart quietly undoes what the group asked for. Rehearsals are
        // excluded: they arm the engine on purpose without arming the phone,
        // and stopSimulation() reads this flag to put things back.
        if (!rehearsing) {
            val protecting = current != GuardState.DISARMED
            if (store.armed != protecting) store.armed = protecting
        }

        when (current) {
            GuardState.PENDING -> {
                val remaining = engine.snapshot(now()).pendingRemainingMs
                if (!rehearsing) {
                    // Immediate, local, unmistakable. The full group alarm is
                    // still a grace period away, but the person holding the
                    // phone finds out straight away.
                    alarmPlayer.playWarningChirp(store.sirenVolume)
                    notifyPending(remaining)
                    raiseAlarmScreen(current, null, store.deviceId, remaining)
                }
                motion.requestBurst()
            }

            GuardState.ALARM -> motion.requestBurst(ALARM_BURST_MS)

            // CALIBRATING belongs here too, and leaving it out was a visible
            // bug: "stop the alarm, keep guarding" lands in CALIBRATING, so the
            // alarm notification and the full-screen disarm surface both stayed
            // up for the whole eight second calibration window after the user
            // had already dealt with them.
            GuardState.ARMED, GuardState.DISARMED, GuardState.CALIBRATING -> {
                motion.relax()
                notificationManager().cancel(NOTIFICATION_ID_ALARM)
                broadcastUpdate(current, null, Protocol.DEVICE_ID_NONE, null, 0)
            }

            else -> Unit
        }

        // The app was swiped away during an incident and the incident is now
        // over. Posted rather than called inline: we are several frames deep
        // inside the engine, and tearing the service down underneath it would
        // re-enter the very code that is still running.
        if (stopWhenIdle && current != GuardState.ALARM && current != GuardState.PENDING) {
            handler.post { if (stopWhenIdle) shutdown() }
            return
        }

        scheduleTick(immediate = true)
        updateNotification(now(), force = true)
    }

    /**
     * True while a test scenario is running.
     *
     * Rehearsals must not behave like real incidents: a full siren would have
     * to be disarmed by hand, which stands the guard down and makes the rest of
     * the suite meaningless - and broadcasting a real alarm event would set off
     * everybody else's phone for a test.
     */
    private val rehearsing: Boolean get() = simulator.running

    override fun onAlarmRaised(reason: AlarmReason, subjectId: Int) {
        Log.w(TAG, "ALARM $reason subject=$subjectId")

        if (rehearsing) {
            // Record the verdict, make a short confirmation blip so the tester
            // can hear that it fired, then stand down so the next scenario can
            // start from a clean state.
            simulator.onAlarmObserved(now(), reason)
            alarmPlayer.playWarningChirp(store.sirenVolume * 0.5f)
            handler.postDelayed({
                if (engine.state == GuardState.ALARM) engine.clearAlarm(now())
            }, REHEARSAL_CLEAR_MS)
            return
        }

        acquireWakeLock()

        val boxAddress = store.boxAddress.takeIf { store.boxEnabled && boxGuard.guardingHere }
        alarmPlayer.start(
            reason = reason,
            target = if (boxAddress == null) AlarmTarget.PHONES_ONLY else store.alarmTarget,
            boxAddress = boxAddress,
            volume = store.sirenVolume,
            vibrate = store.vibrateOnAlarm,
            speak = store.speakReason,
        )

        val name = store.peerNames[subjectId]
            ?: engine.snapshot(now()).peers.firstOrNull { it.deviceId == subjectId }?.name
            ?: store.learnedPeerNames[subjectId]
            ?: if (subjectId == store.deviceId) store.selfName else null

        raiseAlarmScreen(GuardState.ALARM, reason, subjectId, 0)
        notifyAlarm(reason, name)
    }

    override fun onAlarmCleared() {
        alarmPlayer.stop()
        notificationManager().cancel(NOTIFICATION_ID_ALARM)
        releaseWakeLock()
        broadcastUpdate(engine.state, null, Protocol.DEVICE_ID_NONE, null, 0)
    }

    override fun onBroadcastEvent(eventType: Int, subjectId: Int) {
        // A rehearsal stays on this phone. Telling the group would set off
        // everybody else's siren for a test.
        if (rehearsing) return
        composer.queueControl(now(), eventType, subjectId)
        advertiser.updatePayload(composeBeacon())
    }

    override fun onRelayGroupCommand(eventType: Int, originId: Int) {
        if (rehearsing) return
        // The origin id travels unchanged, so every copy of the command stays
        // recognisable as one decision and the flood terminates.
        composer.queueControl(now(), eventType, originId, BeaconComposer.CONTROL_RELAY_MS)
        advertiser.updatePayload(composeBeacon())
        scheduleTick(immediate = true)
    }

    override fun onPeerNameLearned(deviceId: Int, name: String) {
        store.rememberLearnedName(deviceId, name)
    }

    override fun onRadioProfileChanged(profile: RadioProfile) {
        radioProfile = profile
        if (profile == RadioProfile.CALM) releaseWakeLock() else acquireWakeLock()
        if (profile == RadioProfile.CALM) motion.relax() else motion.requestBurst()
        restartRadios()
        scheduleTick(immediate = true)
    }

    override fun onPendingCountdown(msRemaining: Long) {
        broadcastUpdate(GuardState.PENDING, null, store.deviceId, store.selfName, msRemaining)
    }

    override fun onWarningsChanged(warnings: Set<GuardWarning>) {
        updateNotification(now(), force = true)
    }

    // =====================================================================
    // Radio callbacks
    // =====================================================================

    override fun onServiceData(rssi: Int, payload: ByteArray, elapsedNanos: Long) {
        // Counted before anything can reject it. "The radios are on but nobody
        // appears" has two completely different causes - nothing arriving at
        // all, or arriving and being thrown away - and from the outside they
        // look identical. Both numbers are surfaced in the app.
        packetsHeard++
        val beacon = Protocol.decode(payload, store.groupId, store.groupKey) ?: return
        beaconsHeard++
        engine.onPeerBeacon(now(), rssi, beacon)
        // Scan results are what keeps the loop alive while the CPU would
        // otherwise be asleep, so opportunistically drive a tick from here.
        scheduleTick()
    }

    override fun onBoxAdvertisement(address: String, rssi: Int) {
        boxGuard.onBleRssi(rssi)
    }

    override fun onScanFailed(errorCode: Int) {
        Log.w(TAG, "scan failed $errorCode")
    }

    override fun onSignificantMotion() {
        engine.onSelfMotion(now(), MotionSignal.SignificantMotion)
        scheduleTick(immediate = true)
    }

    override fun onMotionLevel(score: Int, stationary: Boolean) {
        engine.onSelfMotion(now(), MotionSignal.Level(score, stationary))
    }

    override fun onBoxSignal(signal: BoxSignal) {
        engine.onBoxSignal(now(), signal)
        if (signal is BoxSignal.Connected || signal is BoxSignal.Disconnected) {
            engine.configureBox(
                configured = store.boxEnabled && store.boxAddress != null,
                name = store.boxName,
                address = store.boxAddress,
                guardedHere = boxGuard.guardingHere,
            )
            engine.setExternalWarning(GuardWarning.BOX_LINK_FLAPPING, boxGuard.flapping)
            scheduleTick(immediate = true)
        }
    }

    override fun onSimulationFinished(
        scenario: Simulator.Scenario,
        verdict: Simulator.Verdict,
        timeToAlarmMs: Long,
    ) {
        simulationResults[scenario.name] = verdict.name
        if (timeToAlarmMs >= 0) simulationTimings[scenario.name] = timeToAlarmMs
        // Leave no virtual peers, virtual speaker or half-finished alarm behind.
        stopSimulation()
        publishSnapshot()
    }

    /**
     * Stops a rehearsal and puts the guard back exactly as it was.
     *
     * Deliberately not gated on `simulator.running`: a scenario that ends by
     * itself has already cleared that flag, and the tidy-up below still has to
     * happen or the phone is left armed and holding a simulated speaker.
     */
    private fun stopSimulation() {
        // Read first, and put the engine back *before* the rehearsal flag is
        // cleared: the state changes below would otherwise be mistaken for real
        // ones and write the rehearsal's armed state to disk.
        val restoreArmed = store.armed
        if (engine.state == GuardState.ALARM) engine.clearAlarm(now())
        if (!restoreArmed && engine.state != GuardState.DISARMED) {
            engine.disarm(now())
        }
        simulator.stop()
        // Restore the real speaker configuration, which a scenario may have
        // replaced with a virtual one.
        engine.configureBox(
            configured = store.boxEnabled && store.boxAddress != null,
            name = store.boxName,
            address = store.boxAddress,
            guardedHere = boxGuard.guardingHere,
        )
    }

    override fun onSimulationProgress(
        scenario: Simulator.Scenario,
        elapsedMs: Long,
        note: String,
    ) {
        simulationNote = note
        simulationElapsedMs = elapsedMs
    }

    // =====================================================================
    // Wake lock
    // =====================================================================

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // =====================================================================
    // Heartbeat
    // =====================================================================

    private fun heartbeatIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_HEARTBEAT,
        Intent(this, GuardService::class.java).setAction(GuardIntents.ACTION_HEARTBEAT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleHeartbeat() {
        val alarms = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Inexact on purpose: an exact alarm would need SCHEDULE_EXACT_ALARM,
        // and this is only a backstop, not the primary timing source.
        runCatching {
            alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS,
                heartbeatIntent(),
            )
        }
    }

    private fun cancelHeartbeat() {
        val alarms = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarms.cancel(heartbeatIntent()) }
    }

    // =====================================================================
    // System events
    // =====================================================================

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR,
                    )
                    if (state == BluetoothAdapter.STATE_ON) {
                        restartRadios()
                    } else if (state == BluetoothAdapter.STATE_OFF) {
                        scanner.stop()
                        advertiser.stop()
                        engine.setExternalWarning(GuardWarning.BLUETOOTH_OFF, true)
                    }
                    updateNotification(now(), force = true)
                }
            }
        }
    }

    private fun registerReceiver() {
        ContextCompat.registerReceiver(
            this,
            systemReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    // =====================================================================
    // Notifications
    // =====================================================================

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        val manager = notificationManager()
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GUARD,
                getString(R.string.notif_channel_guard),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_guard_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                getString(R.string.notif_channel_alarm),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.notif_channel_alarm_desc)
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun startForegroundSafely() {
        val notification = buildGuardNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_GUARD,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID_GUARD, notification)
            }
        }.onFailure { Log.e(TAG, "cannot enter foreground", it) }
        instance = this
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun buildGuardNotification(): Notification {
        val snapshot = if (::engine.isInitialized) engine.snapshot(now()) else null
        val state = snapshot?.state ?: GuardState.DISARMED
        val peerCount = snapshot?.peers?.count { it.armed } ?: 0

        val title = when (state) {
            GuardState.DISARMED -> "Not guarding"
            GuardState.CALIBRATING -> "Getting my bearings..."
            GuardState.ARMED -> "Guarding"
            GuardState.SUSPICIOUS -> "Checking something"
            GuardState.PENDING -> "Put the phone down"
            GuardState.ALARM -> "THEFT ALARM"
        }
        val text = buildString {
            append(if (peerCount == 1) "1 phone watched" else "$peerCount phones watched")
            if (store.boxEnabled && boxGuard.guardingHere) append(" - speaker guarded")
            val warnings = snapshot?.warnings.orEmpty()
            if (warnings.contains(GuardWarning.BLUETOOTH_OFF)) append(" - Bluetooth is off!")
            else if (warnings.contains(GuardWarning.NO_PEERS)) append(" - no phones found yet")
        }

        val content = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_GUARD)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(content)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state == GuardState.DISARMED) {
            builder.addAction(
                0, "Arm",
                servicePendingIntent(GuardIntents.ACTION_ARM, REQUEST_ARM),
            )
        } else {
            builder.addAction(
                0, "Disarm",
                servicePendingIntent(GuardIntents.ACTION_DISARM, REQUEST_DISARM),
            )
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, GuardService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun updateNotification(now: Long, force: Boolean = false) {
        if (!force && now - lastNotificationAt < NOTIFICATION_THROTTLE_MS) return
        lastNotificationAt = now
        runCatching {
            notificationManager().notify(NOTIFICATION_ID_GUARD, buildGuardNotification())
        }
    }

    /**
     * Raises the disarm prompt for the grace period.
     *
     * This is what was missing before: a bare `startActivity` from a service is
     * blocked in the background on Android 10 and up, so on a locked phone
     * nothing appeared at all and the only way to disarm was to unlock normally
     * and open the app. A full-screen-intent notification on a high-importance
     * alarm channel is the supported route onto a locked screen, and it is
     * posted *first* so it works even when the direct launch is refused.
     */
    private fun notifyPending(remainingMs: Long) {
        val fullScreen = PendingIntent.getActivity(
            this,
            REQUEST_PENDING_SCREEN,
            AlarmActivity.intentFor(
                this, GuardState.PENDING, null, store.deviceId, store.selfName, remainingMs,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Put this phone down")
            .setContentText("Disarm now if this is you")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, "Disarm", servicePendingIntent(GuardIntents.ACTION_DISARM, REQUEST_DISARM))
            .build()
        runCatching { notificationManager().notify(NOTIFICATION_ID_ALARM, notification) }
    }

    private fun notifyAlarm(reason: AlarmReason, subjectName: String?) {
        val title = when (reason) {
            AlarmReason.BOX_TAKEN -> "Speaker being taken!"
            AlarmReason.PEER_LOST -> "A phone has vanished!"
            AlarmReason.PICKUP_UNCONFIRMED -> "This phone was picked up!"
            AlarmReason.PANIC -> "Panic alarm"
            AlarmReason.TEST -> "Test alarm"
            else -> "Theft detected!"
        }
        val fullScreen = PendingIntent.getActivity(
            this,
            REQUEST_ALARM_SCREEN,
            AlarmActivity.intentFor(this, GuardState.ALARM, reason, 0, subjectName, 0),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(subjectName?.let { "Device: $it" } ?: "Tap to disarm")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            // The same two decisions as the full-screen surface, for the same
            // reason: a plain "Disarm" here silenced this phone, stood it down,
            // and left everybody else screaming with no way back to them.
            .addAction(
                0, "Stop alarm",
                servicePendingIntent(GuardIntents.ACTION_CLEAR_ALARM, REQUEST_STOP_ALARM),
            )
            .addAction(
                0, "Disarm all",
                servicePendingIntent(GuardIntents.ACTION_DISARM_GROUP, REQUEST_DISARM_GROUP),
            )
            .build()
        runCatching { notificationManager().notify(NOTIFICATION_ID_ALARM, notification) }
    }

    /**
     * Raises the full screen disarm surface.
     *
     * Both routes are used on purpose: a direct `startActivity` works when the
     * app is allowed to launch from the background, and the full-screen-intent
     * notification posted alongside it covers the case where it is not.
     */
    private fun raiseAlarmScreen(
        state: GuardState,
        reason: AlarmReason?,
        subjectId: Int,
        pendingRemainingMs: Long,
    ) {
        val name = if (subjectId == store.deviceId) {
            store.selfName
        } else {
            store.peerNames[subjectId] ?: store.learnedPeerNames[subjectId]
        }
        broadcastUpdate(state, reason, subjectId, name, pendingRemainingMs)
        runCatching {
            startActivity(
                AlarmActivity.intentFor(this, state, reason, subjectId, name, pendingRemainingMs),
            )
        }
    }

    private fun broadcastUpdate(
        state: GuardState,
        reason: AlarmReason?,
        subjectId: Int,
        subjectName: String?,
        pendingRemainingMs: Long,
    ) {
        val intent = Intent(GuardIntents.ACTION_GUARD_UPDATE).apply {
            setPackage(packageName)
            putExtra(GuardIntents.EXTRA_STATE, state.name)
            putExtra(GuardIntents.EXTRA_REASON, reason?.name)
            putExtra(GuardIntents.EXTRA_SUBJECT_ID, subjectId)
            putExtra(GuardIntents.EXTRA_SUBJECT_NAME, subjectName)
            putExtra(GuardIntents.EXTRA_PENDING_REMAINING_MS, pendingRemainingMs)
        }
        sendBroadcast(intent)
    }

    // =====================================================================
    // Bridge surface
    // =====================================================================

    private fun publishSnapshot() {
        val listener = snapshotListener ?: return
        listener(currentSnapshotMap())
    }

    fun currentSnapshotMap(): Map<String, Any?> = Codec.snapshot(
        engine.snapshot(now()),
        store.peerNames,
        store.learnedPeerNames,
        diagnostics(),
    )

    private fun diagnostics(): Map<String, Any?> = mapOf(
        "bluetoothOn" to (adapter?.state == BluetoothAdapter.STATE_ON),
        "advertisingSupported" to advertiser.supported,
        "advertising" to advertiser.running,
        "scanning" to scanner.running,
        "hasSignificantMotion" to motion.hasSignificantMotion,
        "batteryPercent" to batteryPercent,
        "powerProfile" to store.powerProfile.name,
        "wakeLockHeld" to (wakeLock?.isHeld == true),
        "serviceRunning" to started,
        "packetsHeard" to packetsHeard,
        "beaconsHeard" to beaconsHeard,
        "sirenAudible" to alarmPlayer.sirenAudible,
        "simulationRunning" to simulator.running,
        "simulationScenario" to simulator.activeScenario?.name,
        "simulationNote" to simulationNote,
        "simulationElapsedMs" to simulationElapsedMs,
        "simulationResults" to simulationResults,
        "simulationTimings" to simulationTimings,
        "boxLinkConnected" to boxGuard.guardingHere,
    )

    fun pairedAudioDevices(): List<Map<String, Any?>> = boxGuard.pairedAudioDevices()

    companion object {
        private const val TAG = "BpGuardService"

        const val CHANNEL_GUARD = "guard"
        const val CHANNEL_ALARM = "alarm"
        const val NOTIFICATION_ID_GUARD = 1001
        const val NOTIFICATION_ID_ALARM = 1002

        private const val REQUEST_OPEN_APP = 10
        private const val REQUEST_ARM = 11
        private const val REQUEST_DISARM = 12
        private const val REQUEST_ALARM_SCREEN = 13
        private const val REQUEST_HEARTBEAT = 14
        private const val REQUEST_PENDING_SCREEN = 15
        private const val REQUEST_STOP_ALARM = 16
        private const val REQUEST_DISARM_GROUP = 17

        /** How long a rehearsal alarm stays up before standing itself down. */
        private const val REHEARSAL_CLEAR_MS = 1_400L

        /** Time to lock the phone before the lock-screen test fires. */
        private const val LOCKSCREEN_TEST_DELAY_MS = 6_000L
        private const val LOCKSCREEN_TEST_HOLD_MS = 20_000L

        private const val NOTIFICATION_THROTTLE_MS = 5_000L
        private const val BATTERY_POLL_MS = 60_000L

        /**
         * How often the radios are checked against what they are supposed to be
         * doing.
         *
         * Was fifteen seconds, which is longer than a whole group announcement:
         * an advertiser that failed to re-tune stayed wrong for the entire window
         * it mattered in. The check itself costs a couple of field reads.
         */
        private const val RADIO_CHECK_MS = 4_000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val ALARM_BURST_MS = 60_000L
        private const val WAKE_LOCK_TAG = "beachprotect:guard"

        /** Safety valve: never hold the CPU awake for more than ten minutes. */
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L

        /** Set by the Flutter bridge while the UI is on screen. */
        @Volatile
        var snapshotListener: ((Map<String, Any?>) -> Unit)? = null

        @Volatile
        var instance: GuardService? = null
            private set

        private var simulationNote: String = ""
        private var simulationElapsedMs: Long = 0
        private val simulationResults = HashMap<String, String>()

        /** Scenario id -> milliseconds from the incident starting to the alarm. */
        private val simulationTimings = HashMap<String, Long>()

        fun send(context: Context, action: String, configure: (Intent) -> Unit = {}) {
            val intent = Intent(context, GuardService::class.java).setAction(action)
            configure(intent)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
