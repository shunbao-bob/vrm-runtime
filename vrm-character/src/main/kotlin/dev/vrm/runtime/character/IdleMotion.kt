package dev.vrm.runtime.character

import dev.vrm.runtime.core.motion.MotionSpec
import dev.vrm.runtime.core.motion.PosKey
import dev.vrm.runtime.core.motion.RotKey
import dev.vrm.runtime.core.motion.WeightKey

/**
 * Programmatic idle motion (port of text-to-vrma's `idleMotion.js`): a looping
 * 4-second clip of subtle breathing (chest), a gentle head sway, occasional
 * blinks, and a tiny hips bob — all generated as a [MotionSpec] so it goes
 * through the SAME playback path as LLM-generated motions.
 *
 * This means the character never stands frozen: it "breathes" even before any
 * real locomotion clips are available. Pure Kotlin, JVM-testable.
 */
object IdleMotion {

    private fun k(t: Float, vararg r: Float) = RotKey(t, r.toList())
    private fun p(t: Float, vararg v: Float) = PosKey(t, v.toList())
    private fun w(t: Float, weight: Float) = WeightKey(t, weight)

    /** Build the looping idle MotionSpec. */
    fun spec(): MotionSpec {
        val d = 4.0f
        return MotionSpec(
            name = "idle",
            duration = d,
            loop = true,
            tracks = mapOf(
                "leftUpperArm" to listOf(k(0f, 0f, 0f, -70f), k(2f, 0f, 0f, -68f), k(d, 0f, 0f, -70f)),
                "rightUpperArm" to listOf(k(0f, 0f, 0f, 70f), k(2f, 0f, 0f, 68f), k(d, 0f, 0f, 70f)),
                // breathing: chest tilts forward-back subtly
                "chest" to listOf(k(0f, 0f, 0f, 0f), k(2f, 2.5f, 0f, 0f), k(d, 0f, 0f, 0f)),
                // head: gentle look-around
                "head" to listOf(
                    k(0f, 0f, 0f, 0f), k(1.5f, 1.5f, 3f, 0f),
                    k(3.0f, 1.5f, -3f, 0f), k(d, 0f, 0f, 0f),
                ),
            ),
            hips = listOf(
                p(0f, 0f, 0f, 0f),
                p(2f, 0f, -0.008f, 0f),
                p(d, 0f, 0f, 0f),
            ),
            expressions = mapOf(
                "blink" to listOf(
                    w(0f, 0f),
                    w(1.2f, 0f), w(1.28f, 1f), w(1.38f, 0f),
                    w(3.1f, 0f), w(3.18f, 1f), w(3.28f, 0f),
                    w(d, 0f),
                ),
            ),
        )
    }
}
