package com.beachprotect

import com.beachprotect.guard.EngineConfig
import com.beachprotect.guard.EngineConfigCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "I changed a slider and nothing happened" is the hardest class of bug to see
 * from the outside, so the path a setting takes from the UI to the detector is
 * tested directly.
 */
class EngineConfigCodecTest {

    @Test
    fun `every tunable survives a round trip`() {
        val original = EngineConfig(
            dropThresholdDb = 14.5,
            sustainMs = 3_500,
            consensusRatio = 0.5,
            minObservers = 2,
            lostTimeoutMs = 20_000,
            pickupGraceMs = 7_000,
            settleMs = 15_000,
            motionScoreThreshold = 44,
            alarmOnPickupAlone = false,
        )

        val restored = EngineConfigCodec.apply(EngineConfig(), EngineConfigCodec.toMap(original))

        assertEquals(original.dropThresholdDb, restored.dropThresholdDb, 1e-9)
        assertEquals(original.sustainMs, restored.sustainMs)
        assertEquals(original.consensusRatio, restored.consensusRatio, 1e-9)
        assertEquals(original.minObservers, restored.minObservers)
        assertEquals(original.lostTimeoutMs, restored.lostTimeoutMs)
        assertEquals(original.pickupGraceMs, restored.pickupGraceMs)
        assertEquals(original.settleMs, restored.settleMs)
        assertEquals(original.motionScoreThreshold, restored.motionScoreThreshold)
        assertEquals(original.alarmOnPickupAlone, restored.alarmOnPickupAlone)
    }

    @Test
    fun `a partial patch leaves everything else alone`() {
        val base = EngineConfig()
        val patched = EngineConfigCodec.apply(base, mapOf("pickupGraceMs" to 2_000))

        assertEquals(2_000L, patched.pickupGraceMs)
        assertNotEquals(base.pickupGraceMs, patched.pickupGraceMs)
        assertEquals(base.sustainMs, patched.sustainMs)
        assertEquals(base.dropThresholdDb, patched.dropThresholdDb, 1e-9)
        assertEquals(base.consensusRatio, patched.consensusRatio, 1e-9)
    }

    @Test
    fun `values arriving as any numeric type are accepted`() {
        // The platform channel is free to hand us Int, Long or Double for the
        // same field depending on magnitude, so all of them must work.
        val fromInt = EngineConfigCodec.apply(EngineConfig(), mapOf("sustainMs" to 2_500))
        val fromLong = EngineConfigCodec.apply(EngineConfig(), mapOf("sustainMs" to 2_500L))
        val fromDouble = EngineConfigCodec.apply(EngineConfig(), mapOf("sustainMs" to 2_500.0))

        assertEquals(2_500L, fromInt.sustainMs)
        assertEquals(2_500L, fromLong.sustainMs)
        assertEquals(2_500L, fromDouble.sustainMs)
    }

    @Test
    fun `nonsense values are clamped rather than obeyed`() {
        val absurd = EngineConfigCodec.apply(
            EngineConfig(),
            mapOf(
                "sustainMs" to 10 * 60_000,
                "pickupGraceMs" to 0,
                "dropThresholdDb" to 900.0,
                "consensusRatio" to 4.0,
                "minObservers" to 0,
                "settleMs" to -5,
            ),
        )

        assertEquals(EngineConfigCodec.MAX_SUSTAIN_MS, absurd.sustainMs)
        assertEquals(EngineConfigCodec.MIN_GRACE_MS, absurd.pickupGraceMs)
        assertEquals(EngineConfigCodec.MAX_DROP_DB, absurd.dropThresholdDb, 1e-9)
        assertEquals(1.0, absurd.consensusRatio, 1e-9)
        assertEquals(1, absurd.minObservers)
        assertEquals(EngineConfigCodec.MIN_SETTLE_MS, absurd.settleMs)
    }

    @Test
    fun `unrelated keys in the patch are ignored`() {
        val patched = EngineConfigCodec.apply(
            EngineConfig(),
            mapOf("selfName" to "Jan", "boxEnabled" to true, "sustainMs" to 1_500),
        )
        assertEquals(1_500L, patched.sustainMs)
    }

    @Test
    fun `the defaults keep the detector inside its reaction budget`() {
        val defaults = EngineConfig()
        // A lifted phone must reach the siren quickly; the grace period is the
        // only deliberate delay on that path.
        assertTrue(
            "grace period should stay within a few seconds",
            defaults.pickupGraceMs <= 5_000,
        )
        assertTrue(
            "peer confirmation should not need more than a few seconds",
            defaults.sustainMs <= 3_000,
        )
    }
}
