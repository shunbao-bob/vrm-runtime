package dev.vrm.runtime.demo

import dev.vrm.runtime.core.motion.MotionSpec
import dev.vrm.runtime.core.motion.PosKey
import dev.vrm.runtime.core.motion.RotKey
import dev.vrm.runtime.core.motion.WeightKey

/**
 * Built-in keyframe motions for the demo, driven via `-e motion <id>`.
 * These are hand-written MotionSpecs that go through the FULL MotionSpec →
 * validation → clip → playback path, so they double as a live test of the
 * LLM-motion pipeline (the LLM would emit the same JSON shape).
 */
object DemoMotions {

    private fun k(t: Float, x: Float, y: Float, z: Float) = RotKey(t, listOf(x, y, z))
    private fun p(t: Float, x: Float, y: Float, z: Float) = PosKey(t, listOf(x, y, z))
    private fun w(t: Float, weight: Float) = WeightKey(t, weight)

    val all: Map<String, MotionSpec> = mapOf(
        "bow" to MotionSpec(
            name = "bow", duration = 2.4f, loop = false,
            tracks = mapOf(
                "leftUpperArm" to listOf(k(0f, 0f, 0f, -70f), k(2.4f, 0f, 0f, -70f)),
                "rightUpperArm" to listOf(k(0f, 0f, 0f, 70f), k(2.4f, 0f, 0f, 70f)),
                "spine" to listOf(k(0f, 0f, 0f, 0f), k(0.7f, 22f, 0f, 0f), k(1.6f, 22f, 0f, 0f), k(2.4f, 0f, 0f, 0f)),
                "chest" to listOf(k(0f, 0f, 0f, 0f), k(0.7f, 18f, 0f, 0f), k(1.6f, 18f, 0f, 0f), k(2.4f, 0f, 0f, 0f)),
                "neck" to listOf(k(0f, 0f, 0f, 0f), k(0.7f, 12f, 0f, 0f), k(1.6f, 12f, 0f, 0f), k(2.4f, 0f, 0f, 0f)),
            ),
        ),
        "wave" to MotionSpec(
            name = "wave", duration = 1.6f, loop = false,
            tracks = mapOf(
                "leftUpperArm" to listOf(k(0f, 0f, 0f, -70f), k(1.6f, 0f, 0f, -70f)),
                "rightUpperArm" to listOf(k(0f, 0f, 0f, 70f), k(0.4f, 0f, 0f, -50f), k(1.6f, 0f, 0f, 70f)),
                "rightLowerArm" to listOf(
                    k(0f, 0f, 0f, 0f), k(0.4f, 0f, 0f, -40f),
                    k(0.7f, 0f, 0f, -55f), k(0.9f, 0f, 0f, -40f),
                    k(1.1f, 0f, 0f, -55f), k(1.3f, 0f, 0f, -40f), k(1.6f, 0f, 0f, 0f),
                ),
            ),
        ),
        "jump" to MotionSpec(
            name = "jump", duration = 1.8f, loop = false,
            tracks = mapOf(
                "leftUpperLeg" to listOf(k(0f, 0f, 0f, 0f), k(0.35f, -40f, 0f, 0f), k(0.55f, 0f, 0f, 0f), k(1.1f, -25f, 0f, 0f), k(1.4f, 0f, 0f, 0f), k(1.8f, 0f, 0f, 0f)),
                "rightUpperLeg" to listOf(k(0f, 0f, 0f, 0f), k(0.35f, -40f, 0f, 0f), k(0.55f, 0f, 0f, 0f), k(1.1f, -25f, 0f, 0f), k(1.4f, 0f, 0f, 0f), k(1.8f, 0f, 0f, 0f)),
                "leftLowerLeg" to listOf(k(0f, 0f, 0f, 0f), k(0.35f, 70f, 0f, 0f), k(0.55f, 0f, 0f, 0f), k(1.1f, 45f, 0f, 0f), k(1.4f, 0f, 0f, 0f), k(1.8f, 0f, 0f, 0f)),
                "rightLowerLeg" to listOf(k(0f, 0f, 0f, 0f), k(0.35f, 70f, 0f, 0f), k(0.55f, 0f, 0f, 0f), k(1.1f, 45f, 0f, 0f), k(1.4f, 0f, 0f, 0f), k(1.8f, 0f, 0f, 0f)),
            ),
            hips = listOf(
                p(0f, 0f, 0f, 0f), p(0.35f, 0f, -0.18f, 0f), p(0.65f, 0f, 0.28f, 0f),
                p(1.0f, 0f, -0.1f, 0f), p(1.4f, 0f, 0f, 0f), p(1.8f, 0f, 0f, 0f),
            ),
        ),
        "happy_jump" to MotionSpec(
            name = "happy_jump", duration = 2.4f, loop = false,
            tracks = mapOf(
                "leftUpperArm" to listOf(k(0f, 0f, 0f, -70f), k(0.3f, 0f, 0f, 60f), k(0.7f, 0f, 0f, 80f), k(1.1f, 0f, 0f, 60f), k(1.5f, 0f, 0f, 80f), k(1.9f, 0f, 0f, 60f), k(2.4f, 0f, 0f, -70f)),
                "rightUpperArm" to listOf(k(0f, 0f, 0f, 70f), k(0.3f, 0f, 0f, -60f), k(0.7f, 0f, 0f, -80f), k(1.1f, 0f, 0f, -60f), k(1.5f, 0f, 0f, -80f), k(1.9f, 0f, 0f, -60f), k(2.4f, 0f, 0f, 70f)),
                "head" to listOf(k(0f, 0f, 0f, 0f), k(0.4f, -12f, 0f, 0f), k(1.9f, -12f, 0f, 0f), k(2.4f, 0f, 0f, 0f)),
            ),
            hips = listOf(p(0f, 0f, 0f, 0f), p(0.6f, 0f, 0.12f, 0f), p(1.8f, 0f, 0.1f, 0f), p(2.4f, 0f, 0f, 0f)),
            expressions = mapOf(
                "happy" to listOf(w(0f, 0f), w(0.4f, 0.7f), w(1.6f, 0.7f), w(2.4f, 0.35f)),
                "blink" to listOf(w(0.3f, 0f), w(0.38f, 1f), w(0.48f, 0f)),
            ),
        ),
        "idle" to dev.vrm.runtime.character.IdleMotion.spec(),
    )
}