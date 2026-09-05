package dev.vrm.runtime.character

import dev.vrm.runtime.core.motion.MotionSpec
import dev.vrm.runtime.core.motion.PosKey
import dev.vrm.runtime.core.motion.RotKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MotionSpecPostProcessorTest {
    @Test
    fun `non-loop motion gets neutral ending and automatic expression`() {
        val input = MotionSpec(
            name = "wave",
            duration = 2f,
            tracks = mapOf(
                "leftUpperArm" to listOf(
                    RotKey(0f, listOf(0f, 0f, -70f)),
                    RotKey(2f, listOf(0f, 0f, 40f)),
                ),
            ),
        )

        val out = MotionSpecPostProcessor.process(input, "开心地挥手")

        assertEquals(2.8f, out.duration, 0.001f)
        assertEquals(listOf(0f, 0f, -70f), out.tracks.getValue("leftUpperArm").last().r)
        assertTrue("happy" in out.expressions)
        assertEquals(0f, out.expressions.getValue("happy").last().w, 0.001f)
    }

    @Test
    fun `loop motion keeps duration and does not append neutral ending`() {
        val input = MotionSpec(
            duration = 2f,
            loop = true,
            tracks = mapOf("spine" to listOf(RotKey(0f, listOf(0f, 0f, 0f)))),
        )

        val out = MotionSpecPostProcessor.process(input)

        assertEquals(2f, out.duration, 0.001f)
        assertEquals(1, out.tracks.getValue("spine").size)
    }

    @Test
    fun `rescale changes every key time consistently`() {
        val input = MotionSpec(
            duration = 2f,
            tracks = mapOf("spine" to listOf(RotKey(1f, listOf(0f, 0f, 0f)))),
            hips = listOf(PosKey(2f, listOf(0f, 0f, 1f))),
        )

        val out = MotionSpecPostProcessor.rescale(input, 4f)

        assertEquals(2f, out.tracks.getValue("spine").single().t, 0.001f)
        assertEquals(4f, out.hips.single().t, 0.001f)
    }

    @Test
    fun `loop friendliness rejects large root drift`() {
        val input = MotionSpec(
            duration = 2f,
            hips = listOf(
                PosKey(0f, listOf(0f, 0f, 0f)),
                PosKey(2f, listOf(0f, 0f, 2f)),
            ),
        )

        assertFalse(MotionSpecPostProcessor.isLoopFriendly(input))
    }
}
