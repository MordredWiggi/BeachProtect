package com.beachprotect.guard

import kotlin.math.abs
import kotlin.math.pow

/**
 * One dimensional Kalman filter for RSSI.
 *
 * Raw BLE RSSI on a beach jitters by roughly +/- 5 dB even between two phones
 * lying perfectly still, mostly from antenna orientation and multipath off the
 * sand and water. A plain average is too laggy to catch someone walking off,
 * and a raw sample is far too noisy to threshold on. A Kalman filter gives us
 * both: it settles tightly when the signal is stable, and opens up quickly when
 * the signal genuinely starts to move.
 */
class RssiKalman(
    /**
     * How much genuine movement we expect between samples. Raising this makes
     * the filter trust new measurements more.
     */
    private val processNoise: Double = 0.5,
    /**
     * Variance of the RSSI noise itself. Real phone-to-phone RSSI on sand sits
     * around sigma = 2.5 dB, hence ~6.
     */
    private val measurementNoise: Double = 6.0,
) {
    var value: Double = Double.NaN
        private set
    private var covariance: Double = 1.0

    val initialised: Boolean get() = !value.isNaN()

    fun update(measurement: Double): Double {
        if (!initialised) {
            value = measurement
            covariance = measurementNoise
            return value
        }
        covariance += processNoise
        val gain = covariance / (covariance + measurementNoise)
        value += gain * (measurement - value)
        covariance *= (1 - gain)
        return value
    }

    fun reset() {
        value = Double.NaN
        covariance = 1.0
    }
}

/**
 * Rolling median over a fixed window.
 *
 * Used for the per-peer RSSI *baseline*. A median is the right choice here
 * because the baseline must survive exactly the events we are trying to detect:
 * a few seconds of someone standing in the way must not drag the reference
 * level down, or the detector would slowly go blind.
 */
class RollingMedian(private val capacity: Int) {
    private val samples = DoubleArray(capacity)
    private var count = 0
    private var writeIndex = 0

    val size: Int get() = count

    fun add(sample: Double) {
        samples[writeIndex] = sample
        writeIndex = (writeIndex + 1) % capacity
        if (count < capacity) count++
    }

    fun median(): Double {
        if (count == 0) return Double.NaN
        val copy = DoubleArray(count) { samples[it] }
        copy.sort()
        return if (count % 2 == 1) {
            copy[count / 2]
        } else {
            (copy[count / 2 - 1] + copy[count / 2]) / 2.0
        }
    }

    fun clear() {
        count = 0
        writeIndex = 0
    }
}

/**
 * Least-squares slope of the recent RSSI history, in dB per second.
 *
 * Direction matters as much as magnitude. Someone walking between two phones
 * produces a deep but *symmetric* notch — down and straight back up. A phone
 * actually being carried away produces a sustained negative slope. Requiring
 * the slope to be negative removes a whole class of false alarms that a pure
 * threshold would trip on.
 */
class TrendEstimator(private val capacity: Int = 16) {
    private val values = DoubleArray(capacity)
    private val times = DoubleArray(capacity)
    private var count = 0
    private var writeIndex = 0

    fun add(timestampMs: Long, value: Double) {
        values[writeIndex] = value
        times[writeIndex] = timestampMs / 1000.0
        writeIndex = (writeIndex + 1) % capacity
        if (count < capacity) count++
    }

    /** dB per second; negative means the peer is getting further away. */
    fun slopePerSecond(): Double {
        if (count < 4) return 0.0
        var sumT = 0.0
        var sumV = 0.0
        for (i in 0 until count) {
            sumT += times[i]
            sumV += values[i]
        }
        val meanT = sumT / count
        val meanV = sumV / count
        var num = 0.0
        var den = 0.0
        for (i in 0 until count) {
            val dt = times[i] - meanT
            num += dt * (values[i] - meanV)
            den += dt * dt
        }
        return if (abs(den) < 1e-9) 0.0 else num / den
    }

    fun clear() {
        count = 0
        writeIndex = 0
    }
}

/**
 * Very rough log-distance path-loss model, used only to label the UI.
 *
 * We deliberately never show metres. RSSI to distance conversion is accurate to
 * maybe a factor of two in the open and much worse behind a body or a cool box,
 * so the UI shows coarse buckets and the detector works on *changes* in RSSI
 * rather than on any absolute distance.
 */
object DistanceModel {
    /** Beach-ish path loss exponent: open sand, but bodies absorb 2.4 GHz well. */
    private const val PATH_LOSS_EXPONENT = 2.5

    fun estimateMetres(rssi: Double, txPowerRefAtOneMetre: Int): Double {
        if (rssi.isNaN()) return Double.NaN
        val ratio = (txPowerRefAtOneMetre - rssi) / (10.0 * PATH_LOSS_EXPONENT)
        return 10.0.pow(ratio)
    }

    fun bucketOf(metres: Double): Proximity = when {
        metres.isNaN() -> Proximity.UNKNOWN
        metres < 2.0 -> Proximity.HERE
        metres < 6.0 -> Proximity.CLOSE
        metres < 15.0 -> Proximity.NEARBY
        metres < 35.0 -> Proximity.FAR
        else -> Proximity.VERY_FAR
    }
}

enum class Proximity { UNKNOWN, HERE, CLOSE, NEARBY, FAR, VERY_FAR }
