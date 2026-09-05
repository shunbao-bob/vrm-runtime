package dev.vrm.runtime.character

import dev.vrm.runtime.core.motion.MotionSpec
import dev.vrm.runtime.core.motion.PosKey
import dev.vrm.runtime.core.motion.RotKey

/**
 * Programmatic gentle walking motion, generated as a [MotionSpec] so it goes
 * through the SAME validated playback path as LLM-generated motions (and can
 * be hand-tuned without an animation tool).
 *
 * A looping 2s two-step cycle with deliberately SMALL amplitudes ("柔和"):
 *  - legs alternate a modest forward/back swing (not exaggerated steps)
 *  - knees keep a slight natural bend (hinge-safe, X only)
 *  - arms counter-swing opposite to the legs (relaxed, around the natural
 *    hang pose Z = ∓70°)
 *  - spine leans forward a touch and the hips bob subtly with each step
 *
 * Coordinate conventions (matching MotionSpec / three-vrm): model faces +Z,
 * +X is the left side, +Y up; rotations are Euler degrees from the T-pose.
 * upperLeg X negative = leg lifted forward; lowerLeg X is the knee bend.
 * All values are within MotionSpecValidator's per-bone angle limits.
 */
object WalkMotion {

    private fun k(t: Float, vararg r: Float) = RotKey(t, r.toList())
    private fun p(t: Float, vararg v: Float) = PosKey(t, v.toList())

    /** Build the looping gentle-walk MotionSpec (two steps per 2s cycle). */
    fun spec(): MotionSpec {
        val d = 2.0f
        return MotionSpec(
            name = "walk",
            duration = d,
            loop = true,
            tracks = mapOf(
                // Legs: left leads forward at t=0, right at t=1 (opposite phase).
                "leftUpperLeg" to listOf(k(0f, -25f, 0f, 0f), k(1f, 8f, 0f, 0f), k(d, -25f, 0f, 0f)),
                "rightUpperLeg" to listOf(k(0f, 8f, 0f, 0f), k(1f, -25f, 0f, 0f), k(d, 8f, 0f, 0f)),
                // Knees: slight bend on the swinging leg, near-straight on stance.
                "leftLowerLeg" to listOf(k(0f, 20f, 0f, 0f), k(1f, 5f, 0f, 0f), k(d, 20f, 0f, 0f)),
                "rightLowerLeg" to listOf(k(0f, 5f, 0f, 0f), k(1f, 20f, 0f, 0f), k(d, 5f, 0f, 0f)),
                // Arms: counter-swing around the natural hang (Z = ∓70°), Y = fore/aft.
                // Reduced to ±7 deg: larger swings made the whole torso rock.
                "leftUpperArm" to listOf(k(0f, 0f, -7f, -70f), k(1f, 0f, 7f, -70f), k(d, 0f, -7f, -70f)),
                "rightUpperArm" to listOf(k(0f, 0f, 7f, 70f), k(1f, 0f, -7f, 70f), k(d, 0f, 7f, 70f)),
                // Torso: CONSTANT gentle forward lean (no per-step oscillation —
                // the 3->5->3 sway read as body wobble).
                "spine" to listOf(k(0f, 4f, 0f, 0f), k(1f, 4f, 0f, 0f), k(d, 4f, 0f, 0f)),
            ),
            hips = listOf(
                // Halved bob (0.02 -> 0.01): less vertical rocking.
                p(0f, 0f, 0f, 0f),
                p(0.5f, 0f, -0.01f, 0f),
                p(1f, 0f, 0f, 0f),
                p(1.5f, 0f, -0.01f, 0f),
                p(d, 0f, 0f, 0f),
            ),
        )
    }
}
