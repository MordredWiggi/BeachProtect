package com.beachprotect.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Answers one question: *is this phone being moved, right now?*
 *
 * ## Why this is not built on TYPE_SIGNIFICANT_MOTION alone
 *
 * The obvious design is to wait on `TYPE_SIGNIFICANT_MOTION`, a hardware
 * wake-up sensor that costs essentially nothing while idle. That was the
 * original implementation, and it was wrong: that sensor is specified to fire
 * on *sustained* movement - it exists for step and activity detection - so in
 * practice it takes anywhere from five to fifteen seconds of walking before it
 * triggers. For theft detection that is far too late. The phone is twenty
 * metres away before it notices.
 *
 * So while the guard is armed, the accelerometer runs continuously instead, and
 * a lift is detected within a few hundred milliseconds.
 *
 * ## What that actually costs
 *
 * Much less than it sounds. The samples are collected by the sensor hub and
 * delivered through the hardware FIFO in batches ([MAX_REPORT_LATENCY_US]), so
 * the application processor wakes about four times a second rather than
 * twenty-five. A batched accelerometer draws on the order of 0.1-0.5 mA, which
 * is an order of magnitude below what the BLE scanner already costs. The radio,
 * not the accelerometer, is what drains the battery.
 *
 * `TYPE_SIGNIFICANT_MOTION` is still used while the guard is *disarmed*, where
 * latency does not matter and the phone may sit untouched for hours.
 */
class MotionMonitor(
    context: Context,
    private val listener: Listener,
) {

    interface Listener {
        /** The hardware wake-up sensor fired (disarmed path only). */
        fun onSignificantMotion()

        /** Motion energy update, 0..255, plus the derived stationary verdict. */
        fun onMotionLevel(score: Int, stationary: Boolean)
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())

    private val significantMotion: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val hasSignificantMotion: Boolean get() = significantMotion != null

    private var running = false
    private var guardActive = false
    private var accelRunning = false
    private var triggerArmed = false

    /** Current FIFO latency, so we only re-register when it actually changes. */
    private var activeLatencyUs = -1
    private var burstUntil = 0L

    private var magnitudeMean = Double.NaN
    private var energy = 0.0
    private var quietSince = 0L
    private var lastReportAt = 0L
    private var lastStationary = true

    // =====================================================================
    // Lifecycle
    // =====================================================================

    fun start() {
        if (running) return
        running = true
        resetFilters()
        applyMode()
    }

    fun stop() {
        running = false
        guardActive = false
        burstUntil = 0
        disarmSignificantMotion()
        stopAccelerometer()
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Switches between the two strategies.
     *
     * Armed: continuous batched accelerometer, sub-second detection.
     * Disarmed: hardware wake-up sensor, near-zero cost.
     */
    fun setGuardActive(active: Boolean) {
        if (guardActive == active) return
        guardActive = active
        resetFilters()
        applyMode()
    }

    /** Drops FIFO latency to zero for [durationMs] when precision matters. */
    fun requestBurst(durationMs: Long = DEFAULT_BURST_MS) {
        if (!running) return
        burstUntil = SystemClock.elapsedRealtime() + durationMs
        applyMode()
        handler.removeCallbacks(burstExpiry)
        handler.postDelayed(burstExpiry, durationMs + 100)
    }

    /** Ends any burst early. */
    fun relax() {
        if (!running) return
        if (burstUntil == 0L) return
        burstUntil = 0
        applyMode()
    }

    private val burstExpiry = Runnable {
        if (SystemClock.elapsedRealtime() >= burstUntil) {
            burstUntil = 0
            applyMode()
        }
    }

    // =====================================================================
    // Mode selection
    // =====================================================================

    private fun applyMode() {
        if (!running) return

        val bursting = SystemClock.elapsedRealtime() < burstUntil
        val wantAccelerometer = guardActive || bursting

        if (wantAccelerometer) {
            disarmSignificantMotion()
            val latency = if (bursting) 0 else MAX_REPORT_LATENCY_US
            startAccelerometer(latency)
        } else {
            stopAccelerometer()
            if (hasSignificantMotion) {
                armSignificantMotion()
            } else {
                // No wake-up sensor: a slow, heavily batched accelerometer is
                // the only option. Costs more, so we say so in diagnostics.
                startAccelerometer(IDLE_REPORT_LATENCY_US)
            }
            // Nothing is sampling, so by construction the phone is at rest.
            if (!lastStationary) {
                lastStationary = true
                listener.onMotionLevel(0, true)
            }
        }
    }

    private fun resetFilters() {
        magnitudeMean = Double.NaN
        energy = 0.0
        quietSince = 0L
        lastReportAt = 0L
    }

    // =====================================================================
    // Significant motion (disarmed path)
    // =====================================================================

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // One-shot by contract: it has already unregistered itself.
            triggerArmed = false
            if (!running) return
            listener.onSignificantMotion()
            // Measure what is actually happening for a few seconds.
            requestBurst()
        }
    }

    private fun armSignificantMotion() {
        val sensor = significantMotion ?: return
        if (triggerArmed) return
        val ok = runCatching { sensorManager.requestTriggerSensor(triggerListener, sensor) }
            .getOrDefault(false)
        triggerArmed = ok
        if (!ok) Log.w(TAG, "could not arm significant motion")
    }

    private fun disarmSignificantMotion() {
        val sensor = significantMotion ?: return
        if (!triggerArmed) return
        runCatching { sensorManager.cancelTriggerSensor(triggerListener, sensor) }
        triggerArmed = false
    }

    // =====================================================================
    // Accelerometer
    // =====================================================================

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            consume(event.values[0], event.values[1], event.values[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun startAccelerometer(latencyUs: Int) {
        val sensor = accelerometer ?: return
        if (accelRunning && activeLatencyUs == latencyUs) return
        if (accelRunning) stopAccelerometer()

        val ok = runCatching {
            sensorManager.registerListener(
                accelListener,
                sensor,
                SAMPLING_PERIOD_US,
                latencyUs,
                handler,
            )
        }.getOrDefault(false)

        if (ok) {
            accelRunning = true
            activeLatencyUs = latencyUs
        } else {
            Log.w(TAG, "accelerometer unavailable")
        }
    }

    private fun stopAccelerometer() {
        if (!accelRunning) return
        accelRunning = false
        activeLatencyUs = -1
        runCatching { sensorManager.unregisterListener(accelListener) }
    }

    private fun consume(x: Float, y: Float, z: Float) {
        val magnitude = sqrt((x * x + y * y + z * z).toDouble())

        // Track the resting magnitude rather than assuming 9.81: it drifts with
        // temperature and calibration, and a fixed constant would leave a
        // permanent bias that looks like motion.
        magnitudeMean = if (magnitudeMean.isNaN()) {
            magnitude
        } else {
            magnitudeMean * 0.985 + magnitude * 0.015
        }

        val deviation = abs(magnitude - magnitudeMean)
        // Fast attack, slower release: we want to notice a lift immediately but
        // not declare the phone settled the instant it is set down.
        energy = if (deviation > energy) {
            energy * 0.55 + deviation * 0.45
        } else {
            energy * 0.88 + deviation * 0.12
        }

        val now = SystemClock.elapsedRealtime()
        val score = (energy * SCORE_SCALE).toInt().coerceIn(0, 255)
        val quiet = score < QUIET_SCORE
        if (!quiet) {
            quietSince = 0L
        } else if (quietSince == 0L) {
            quietSince = now
        }

        val stationary = quiet && quietSince != 0L && now - quietSince >= QUIET_HOLD_MS

        // Report on a fixed cadence, but never sit on a transition into motion:
        // that is the event the whole alarm chain hangs off.
        val becameMoving = lastStationary && !stationary
        if (becameMoving || now - lastReportAt >= REPORT_INTERVAL_MS) {
            lastReportAt = now
            lastStationary = stationary
            listener.onMotionLevel(score, stationary)
        }
    }

    companion object {
        private const val TAG = "BpMotion"

        /** 25 Hz. Plenty to characterise a phone being picked up. */
        private const val SAMPLING_PERIOD_US = 40_000

        /**
         * Buffer a quarter second in the hardware FIFO before waking the CPU.
         * Four wake-ups a second, against a detection budget of ~3 s.
         */
        private const val MAX_REPORT_LATENCY_US = 250_000

        /** Much lazier batching on devices with no wake-up sensor. */
        private const val IDLE_REPORT_LATENCY_US = 2_000_000

        private const val REPORT_INTERVAL_MS = 200L
        private const val DEFAULT_BURST_MS = 8_000L

        /** Motion energy below this counts as "not moving". */
        private const val QUIET_SCORE = 12

        /** How long it must stay quiet before we believe it. */
        private const val QUIET_HOLD_MS = 1_500L

        /** Maps m/s^2 of jitter onto the 0..255 wire field. */
        private const val SCORE_SCALE = 55.0
    }
}
