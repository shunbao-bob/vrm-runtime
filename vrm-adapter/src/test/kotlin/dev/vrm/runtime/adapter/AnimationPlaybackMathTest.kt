package dev.vrm.runtime.adapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnimationPlaybackMathTest {
    @Test
    fun `installing third player cleans oldest and retains current as previous`() {
        class Player(val name: String)
        val oldest = Player("oldest")
        val current = Player("current")
        val next = Player("next")
        val cleared = mutableListOf<Player>()

        val transition = AnimationPlayerOwnership.install(
            previous = oldest,
            current = current,
            currentPlaying = true,
            next = next,
            clearOwnedExpressions = cleared::add,
        )

        assertEquals(listOf(oldest), cleared)
        assertEquals(current, transition.previous)
        assertEquals(next, transition.current)
    }

    @Test
    fun `same expression contributions are composed instead of overwritten`() {
        val result = ExpressionBlendMath.merge(
            names = setOf("happy"),
            previous = mapOf("happy" to 0.75f),
            next = mapOf("happy" to 0.125f),
        )
        assertEquals(0.875f, result.getValue("happy"), 0.0001f)
    }

    @Test
    fun `expressions present on only one side fade independently`() {
        val result = ExpressionBlendMath.merge(
            names = setOf("sad", "happy"),
            previous = mapOf("sad" to 0.6f),
            next = mapOf("happy" to 0.25f),
        )
        assertEquals(0.6f, result.getValue("sad"), 0.0001f)
        assertEquals(0.25f, result.getValue("happy"), 0.0001f)
    }

    @Test
    fun `sampling holds first and last values outside keyframe interval`() {
        val times = floatArrayOf(1f, 2f)
        assertEquals(10f, AnimationPlaybackMath.sampleScalar(times, floatArrayOf(10f, 20f), 0f), 0.0001f)
        assertEquals(20f, AnimationPlaybackMath.sampleScalar(times, floatArrayOf(10f, 20f), 3f), 0.0001f)
    }

    @Test
    fun `expression influence follows blend and reaches zero`() {
        assertEquals(0.25f, AnimationPlaybackMath.expressionWeight(0.5f, 0.5f), 0.0001f)
        assertEquals(0f, AnimationPlaybackMath.expressionWeight(0.8f, 0f), 0.0001f)
    }

    @Test
    fun `rotation-only tracks retain a blended rotation`() {
        assertEquals(0.5f, AnimationPlaybackMath.blendFactor(hasPosition = false, hasRotation = true, weight = 0.5f), 0.0001f)
    }
}