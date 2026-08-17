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
    private var tickScheduled = false
    private var lastNotificationAt = 0L
    private var lastBatteryReadAt = 0L
    private var batteryPercent = 100
    private var started = false

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

        when (intent?.action) {
            GuardIntents.ACTION_START, null -> ensureStarted()
            GuardIntents.ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            GuardIntents.ACTION_ARM -> {
                ensureStarted()
                store.armed = true
                engine.arm(now())
            }

            GuardIntents.ACTION_DISARM -> {
                ensureStarted()
                // If we were making noise, this is the owner saying "that was
                // me" - so the rest of the group is told to stand down too.
                if (engine.state == GuardState.ALARM) {
                    composer.queueControl(now(), Protocol.EVENT_ALARM_CLEAR, store.deviceId)
                }
                store.armed = false
                engine.disarm(now())
            }

            GuardIntents.ACTION_CLEAR_ALARM -> {
                composer.queueControl(now(), Protocol.EVENT_ALARM_CLEAR, store.deviceId)
                engine.clearAlarm(now())
            }

            GuardIntents.ACTION_DISARM_GROUP -> {
                composer.queueControl(now(), Protocol.EVENT_DISARM_ALL, store.deviceId)
                store.armed = false
                engine.disarm(now())
            }

            GuardIntents.ACTION_ARM_GROUP -> {
                ensureStarted()
                composer.queueControl(now(), Protocol.EVENT_ARM_ALL, store.deviceId)
                store.armed = true
                engine.arm(now())
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
                    // Arm the engine but deliberately not the persisted flag:
                    // a rehearsal must not leave the phone armed afterwards,
                    // and stopSimulation() uses store.armed to know that.
                    if (engine.state == GuardState.DISARMED) engine.arm(now())
                    simulator.start(scenario, now())
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

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

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
        instance = null
        stopForegroundCompat()
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
        if (advertiserDown || !scanner.running) {
            Log.w(TAG, "radio health: advertising=${advertiser.running} scanning=${scanner.running}")
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
        engine.setExternalWarning(GuardWarning.ADVERTISING_UNAVAILABLE, !advertiser.supported)

        scanner.apply(radioProfile, store.powerProfile, store.boxBleAddress.takeIf { store.boxEnabled })
        advertiser.start(radioProfile, composeBeacon())
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
        val alarming = engine.state == GuardState.ALARM && !rehearsing
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

            GuardState.ARMED, GuardState.DISARMED -> {
                motion.relax()
                notificationManager().cancel(NOTIFICATION_ID_ALARM)
                broadcastUpdate(current, null, Protocol.DEVICE_ID_NONE, null, 0)
            }

            else -> Unit
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

        val name = engine.snapshot(now()).peers
            .firstOrNull { it.deviceId == subjectId }?.name
            ?: store.peerNames[subjectId]
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
        val beacon = Protocol.decode(payload, store.groupId, store.groupKey) ?: return
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
        simulator.stop()
        if (engine.state == GuardState.ALARM) engine.clearAlarm(now())
        if (!store.armed && engine.state != GuardState.DISARMED) {
            engine.disarm(now())
        }
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
            .addAction(0, "Disarm", servicePendingIntent(GuardIntents.ACTION_DISARM, REQUEST_DISARM))
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
            store.peerNames[subjectId]
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

        /** How long a rehearsal alarm stays up before standing itself down. */
        private const val REHEARSAL_CLEAR_MS = 1_400L

        /** Time to lock the phone before the lock-screen test fires. */
        private const val LOCKSCREEN_TEST_DELAY_MS = 6_000L
        private const val LOCKSCREEN_TEST_HOLD_MS = 20_000L

        private const val NOTIFICATION_THROTTLE_MS = 5_000L
        private const val BATTERY_POLL_MS = 60_000L
        private const val RADIO_CHECK_MS = 15_000L
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
