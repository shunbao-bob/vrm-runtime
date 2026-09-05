package dev.vrm.runtime.character

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmotionFadeTest {

    private fun engine(decay: Float = 0f): EmotionEngine {
        // Keep the default emotions mapping; only override the decay rate.
        val e = EmotionEngine(
            EmotionProfile(
                emotions = EmotionProfile.defaults().emotions,
                decayPerSecond = decay,
            )
        )
        e.fadeInTime = 0.3f
        e.fadeOutTime = 0.6f
        return e
    }

    @Test
    fun `expressionWeight fades in toward dominant intensity`() {
        val e = engine(0f)
        e.emote("happy", 1f)
        // After a single 0.1s step: fade-in 0.3s → step = 1/0.3*0.1 = 0.333
        e.update(0.1f)
        val w = e.expressionWeight
        assertTrue(w > 0f && w < 1f, "expected mid-fade weight, got $w")
        // Enough time to reach target
        repeat(10) { e.update(0.1f) }
        assertEquals(1f, e.expressionWeight, 0.001f)
    }

    @Test
    fun `expressionWeight eases out when emotion clears`() {
        val e = engine(0f)
        e.emote("sad", 1f)
        repeat(10) { e.update(0.1f) }
        assertEquals(1f, e.expressionWeight, 0.001f)
        e.clear()
        e.update(0.1f)
        assertTrue(e.expressionWeight < 1f, "expected fade-out start, got ${e.expressionWeight}")
        repeat(10) { e.update(0.1f) }
        assertEquals(0f, e.expressionWeight, 0.001f)
    }

    @Test
    fun `fade-in is faster than fade-out`() {
        val e = engine(0f)
        e.fadeOutTime = 0.9f
        e.emote("happy", 1f)
        e.update(0.3f) // fade-in 0.3s → reaches ~1.0
        assertEquals(1f, e.expressionWeight, 0.05f)
        e.clear()
        e.update(0.3f) // fade-out 0.9s → only 1/3 down
        assertTrue(e.expressionWeight in 0.3f..0.9f, "expected partial fade-out, got ${e.expressionWeight}")
    }
}