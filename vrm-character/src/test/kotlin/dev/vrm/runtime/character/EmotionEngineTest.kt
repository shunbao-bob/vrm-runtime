package dev.vrm.runtime.character

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EmotionEngineTest {

    @Test
    fun `setting an emotion makes it dominant`() {
        val e = EmotionEngine()
        assertNull(e.dominant)
        e.emote("happy")
        assertEquals("happy", e.dominant)
        assertEquals("happy", e.dominantExpression())
        assertEquals("cheerful", e.dominantTone())
    }

    @Test
    fun `intensity decays over time and reaches neutral`() {
        val e = EmotionEngine(EmotionProfile(decayPerSecond = 0.5f))
        e.emote("happy", 1f)
        e.update(2f) // 1.0 - 0.5*2 = 0
        assertNull(e.dominant)
        assertEquals(0f, e.dominantIntensity)
    }

    @Test
    fun `stronger emotion wins`() {
        val e = EmotionEngine()
        e.emote("happy", 0.4f)
        e.emote("angry", 0.9f)
        assertEquals("angry", e.dominant)
        assertEquals("firm", e.dominantTone())
    }

    @Test
    fun `unknown emotion is ignored`() {
        val e = EmotionEngine()
        e.set("nonexistent", 1f)
        assertNull(e.dominant)
    }

    @Test
    fun `clear resets to neutral`() {
        val e = EmotionEngine()
        e.emote("happy")
        e.clear()
        assertNull(e.dominant)
    }
}