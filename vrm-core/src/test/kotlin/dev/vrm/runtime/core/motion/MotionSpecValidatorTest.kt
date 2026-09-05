package dev.vrm.runtime.core.motion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MotionSpecValidatorTest {

    @Test
    fun `valid spec passes through unchanged`() {
        val spec = MotionSpec(
            name = "bow", duration = 2f,
            tracks = mapOf(
                "spine" to listOf(RotKey(0f, listOf(0f, 0f, 0f)), RotKey(1f, listOf(20f, 0f, 0f)), RotKey(2f, listOf(0f, 0f, 0f))),
            ),
            hips = listOf(PosKey(0f, listOf(0f, 0f, 0f)), PosKey(2f, listOf(0f, 0f, 0f))),
        )
        val out = MotionSpecValidator.validate(spec)
        assertEquals(1, out.tracks.size)
        assertEquals(3, out.tracks["spine"]!!.size)
    }

    @Test
    fun `unknown bones are dropped`() {
        val spec = MotionSpec(
            name = "x", duration = 1f,
            tracks = mapOf(
                "notABone" to listOf(RotKey(0f, listOf(0f, 0f, 0f))),
                "leftUpperArm" to listOf(RotKey(0f, listOf(0f, 0f, -70f)), RotKey(1f, listOf(0f, 0f, -70f))),
            ),
        )
        val out = MotionSpecValidator.validate(spec)
        assertEquals(setOf("leftUpperArm"), out.tracks.keys)
    }

    @Test
    fun `shoulders are dropped for cloth safety`() {
        val spec = MotionSpec(
            name = "x", duration = 1f,
            tracks = mapOf(
                "leftShoulder" to listOf(RotKey(0f, listOf(10f, 0f, 0f)), RotKey(1f, listOf(10f, 0f, 0f))),
                "spine" to listOf(RotKey(0f, listOf(0f, 0f, 0f)), RotKey(1f, listOf(0f, 0f, 0f))),
            ),
        )
        val out = MotionSpecValidator.validate(spec)
        assertEquals(setOf("spine"), out.tracks.keys)
    }

    @Test
    fun `out of range angles are clamped`() {
        val spec = MotionSpec(
            name = "x", duration = 1f,
            tracks = mapOf(
                "head" to listOf(RotKey(0f, listOf(0f, 0f, 0f)), RotKey(1f, listOf(0f, 200f, 0f))), // head Y limit 70
            ),
        )
        val out = MotionSpecValidator.validate(spec)
        assertEquals(70f, out.tracks["head"]!![1].r[1], 0.001f)
    }

    @Test
    fun `knee hinge joint is protected`() {
        val spec = MotionSpec(
            name = "x", duration = 1f,
            tracks = mapOf(
                "leftLowerLeg" to listOf(
                    RotKey(0f, listOf(-50f, 0f, 0f)),  // reverse-bend → clamped to -3
                    RotKey(1f, listOf(200f, 0f, 0f)),  // over-bend → clamped to 140
                ),
            ),
        )
        val out = MotionSpecValidator.validate(spec)
        assertEquals(-3f, out.tracks["leftLowerLeg"]!![0].r[0], 0.001f)
        assertEquals(140f, out.tracks["leftLowerLeg"]!![1].r[0], 0.001f)
    }

    @Test
    fun `duration over max is truncated`() {
        val spec = MotionSpec(
            name = "x", duration = 100f,
            tracks = mapOf(
                "spine" to listOf(RotKey(0f, listOf(0f, 0f, 0f)), RotKey(50f, listOf(10f, 0f, 0f))),
            ),
        )
        val out = MotionSpecValidator.validate(spec)
        assertEquals(MotionSpecValidator.MAX_DURATION, out.duration)
        assertTrue(out.tracks["spine"]!!.all { it.t <= MotionSpecValidator.MAX_DURATION })
    }

    @Test
    fun `empty result throws`() {
        val spec = MotionSpec(name = "empty", duration = 1f, tracks = emptyMap())
        assertThrows(IllegalArgumentException::class.java) { MotionSpecValidator.validate(spec) }
    }

    @Test
    fun `non positive duration throws`() {
        val spec = MotionSpec(name = "x", duration = 0f, tracks = mapOf("spine" to listOf(RotKey(0f, listOf(0f,0f,0f)))))
        assertThrows(IllegalArgumentException::class.java) { MotionSpecValidator.validate(spec) }
    }

    @Test
    fun `cross bone guard limits raised arm with deeply bent elbow`() {
        val out = MotionSpecValidator.validate(
            MotionSpec(
                duration = 1f,
                tracks = mapOf(
                    "leftUpperArm" to listOf(RotKey(0f, listOf(0f, 0f, 80f))),
                    "leftLowerArm" to listOf(RotKey(0f, listOf(0f, 80f, -80f))),
                ),
            ),
        )

        assertEquals(58f, out.tracks.getValue("leftUpperArm").single().r[2], 0.001f)
        assertEquals(-15f, out.tracks.getValue("leftLowerArm").single().r[2], 0.001f)
    }

    @Test
    fun `spec json round trips`() {
        val json = """{"name":"wave","duration":2.0,"loop":false,
            "tracks":{"leftUpperArm":[{"t":0,"r":[0,0,-70]},{"t":1,"r":[0,0,-50]},{"t":2,"r":[0,0,-70]}]},
            "hips":[{"t":0,"p":[0,0,0]},{"t":2,"p":[0,0,0]}],
            "expressions":{"happy":[{"t":0,"w":0},{"t":1,"w":0.7},{"t":2,"w":0}]}}"""
        val spec = MotionSpec.fromJson(json)
        assertEquals("wave", spec.name)
        assertEquals(3, spec.tracks["leftUpperArm"]!!.size)
        assertEquals(1, spec.expressions.size)
    }
}
