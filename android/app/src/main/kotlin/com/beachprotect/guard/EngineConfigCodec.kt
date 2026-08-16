package com.beachprotect.guard

/**
 * Converts detector settings to and from the plain maps used by the UI bridge
 * and by persistence.
 *
 * Split out of `GuardStore` on purpose: "did my settings change actually reach
 * the detector?" is a question that deserves a real test, and `GuardStore`
 * needs an Android `Context` so it cannot be unit tested on the JVM. This has
 * no Android dependency at all, so `EngineConfigCodecTest` can check every
 * field round-trips.
 *
 * Values are clamped rather than trusted. A slider that let someone set the
 * sustain window to ten minutes would silently turn the guard off, and there is
 * no sensible reason to allow it.
 */
object EngineConfigCodec {

    fun toMap(config: EngineConfig): Map<String, Any?> = mapOf(
        "dropThresholdDb" to config.dropThresholdDb,
        "sustainMs" to config.sustainMs,
        "consensusRatio" to config.consensusRatio,
        "minObservers" to config.minObservers,
        "lostTimeoutMs" to config.lostTimeoutMs,
        "pickupGraceMs" to config.pickupGraceMs,
        "settleMs" to config.settleMs,
        "motionScoreThreshold" to config.motionScoreThreshold,
        "alarmOnPickupAlone" to config.alarmOnPickupAlone,
    )

    /** Applies a partial patch, leaving absent keys untouched. */
    fun apply(config: EngineConfig, patch: Map<String, Any?>): EngineConfig {
        fun number(key: String): Double? = (patch[key] as? Number)?.toDouble()

        return config.copy(
            dropThresholdDb = number("dropThresholdDb")
                ?.coerceIn(MIN_DROP_DB, MAX_DROP_DB) ?: config.dropThresholdDb,
            sustainMs = number("sustainMs")?.toLong()
                ?.coerceIn(MIN_SUSTAIN_MS, MAX_SUSTAIN_MS) ?: config.sustainMs,
            consensusRatio = number("consensusRatio")
                ?.coerceIn(MIN_RATIO, 1.0) ?: config.consensusRatio,
            minObservers = number("minObservers")?.toInt()
                ?.coerceIn(1, 5) ?: config.minObservers,
            lostTimeoutMs = number("lostTimeoutMs")?.toLong()
                ?.coerceIn(MIN_LOST_MS, MAX_LOST_MS) ?: config.lostTimeoutMs,
            pickupGraceMs = number("pickupGraceMs")?.toLong()
                ?.coerceIn(MIN_GRACE_MS, MAX_GRACE_MS) ?: config.pickupGraceMs,
            settleMs = number("settleMs")?.toLong()
                ?.coerceIn(MIN_SETTLE_MS, MAX_SETTLE_MS) ?: config.settleMs,
            motionScoreThreshold = number("motionScoreThreshold")?.toInt()
                ?.coerceIn(5, 200) ?: config.motionScoreThreshold,
            alarmOnPickupAlone = (patch["alarmOnPickupAlone"] as? Boolean)
                ?: config.alarmOnPickupAlone,
        )
    }

    // Bounds are deliberately generous at the fast end and firm at the slow
    // end: a user who wants a hair trigger should get one, but nobody should be
    // able to configure the guard into uselessness by dragging a slider.
    const val MIN_DROP_DB = 5.0
    const val MAX_DROP_DB = 30.0
    const val MIN_SUSTAIN_MS = 500L
    const val MAX_SUSTAIN_MS = 15_000L
    const val MIN_RATIO = 0.05
    const val MIN_LOST_MS = 4_000L
    const val MAX_LOST_MS = 120_000L
    const val MIN_GRACE_MS = 1_000L
    const val MAX_GRACE_MS = 60_000L
    const val MIN_SETTLE_MS = 2_000L
    const val MAX_SETTLE_MS = 120_000L
}
