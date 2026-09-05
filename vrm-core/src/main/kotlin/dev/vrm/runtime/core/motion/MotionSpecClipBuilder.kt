package dev.vrm.runtime.core.motion

import dev.vrm.runtime.core.expression.ExpressionManager
import dev.vrm.runtime.core.humanoid.HumanBoneName
import dev.vrm.runtime.core.humanoid.VRMHumanoid
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.vrma.KeyframeTrack
import dev.vrm.runtime.core.vrma.VRMAnimationClip
import kotlin.math.max

/**
 * Builds a [VRMAnimationClip] from an LLM-generated [MotionSpec] for a target
 * [VRMHumanoid] (+ [ExpressionManager]). The produced clip is engine-agnostic
 * and can be played by the SAME player that plays .vrma files — no new player
 * is required, because MotionSpec bone names (leftUpperArm, spine, ...) and
 * euler-angle conventions (XYZ, degrees) map 1:1 onto the normalized rig that
 * [dev.vrm.runtime.adapter.VrmAnimationPlayer] drives.
 *
 * Track name conventions (matching [dev.vrm.runtime.core.vrma.VRMAnimationClipBuilder]):
 *  - `<bone>.quaternion` -> normalized rig bone local rotation
 *  - `<bone>.position`   -> normalized rig bone local translation (hips only)
 *  - `<expressionName>.weight` -> expression weight
 *
 * The caller is expected to run [MotionSpecValidator.validate] on the spec
 * first; this builder also defensively drops any track whose target bone /
 * expression is missing on the avatar (mirroring three-vrm's null checks).
 */
class MotionSpecClipBuilder(
    private val humanoid: VRMHumanoid,
    private val expressionManager: ExpressionManager? = null,
) {

    /** Build the clip for a (preferably validated) [MotionSpec]. */
    fun build(spec: MotionSpec): VRMAnimationClip {
        val tracks = ArrayList<KeyframeTrack>()
        val rotationTracks = LinkedHashMap(spec.tracks)

        // When an arm rises past 55 degrees, gently lift the clavicle. Explicit
        // shoulder animation wins; absent target shoulder bones are never emitted.
        for (side in listOf("left", "right")) {
            val shoulder = "${side}Shoulder"
            val upperArm = rotationTracks["${side}UpperArm"] ?: continue
            if (shoulder in rotationTracks || humanoid.getNormalizedBoneNode(shoulder) == null) continue
            val raiseSign = if (side == "left") 1f else -1f
            val keys = upperArm.map { key ->
                val raised = (raiseSign * key.r.getOrElse(2) { 0f } - 55f).coerceAtLeast(0f)
                RotKey(key.t, listOf(0f, 0f, raiseSign * (raised * 0.4f).coerceAtMost(14f)))
            }
            if (keys.any { it.r[2] != 0f }) rotationTracks[shoulder] = keys
        }

        // Match text-to-vrma's relaxed hand pose: absent finger tracks receive
        // a small constant curl so generated gestures do not render as rigid
        // T-pose paddles. Model-authored finger tracks always take precedence.
        val fingerCurl = linkedMapOf("Proximal" to 14f, "Intermediate" to 17f, "Distal" to 10f)
        for (side in listOf("left", "right")) {
            val sign = if (side == "left") -1f else 1f
            for (finger in listOf("Index", "Middle", "Ring", "Little")) {
                for ((segment, degrees) in fingerCurl) {
                    val bone = "$side$finger$segment"
                    if (bone in rotationTracks || humanoid.getNormalizedBoneNode(bone) == null) continue
                    val rotation = listOf(0f, 0f, sign * degrees)
                    rotationTracks[bone] = listOf(
                        RotKey(0f, rotation),
                        RotKey(spec.duration.coerceAtLeast(0.01f), rotation),
                    )
                }
            }
        }

        // Bone rotations: euler degrees (XYZ) -> quaternion keyframes.
        for ((boneName, keys) in rotationTracks) {
            if (humanoid.getNormalizedBoneNode(boneName) == null) continue
            val sorted = keys.sortedBy { it.t }
            if (sorted.isEmpty()) continue
            val times = FloatArray(sorted.size)
            val values = FloatArray(sorted.size * 4)
            sorted.forEachIndexed { i, k ->
                times[i] = k.t
                val q = Quat()
                // MotionSpec uses XYZ euler order (text-to-vrma convention).
                q.setFromEuler(
                    Math.toRadians(k.r.getOrElse(0) { 0f }.toDouble()).toFloat(),
                    Math.toRadians(k.r.getOrElse(1) { 0f }.toDouble()).toFloat(),
                    Math.toRadians(k.r.getOrElse(2) { 0f }.toDouble()).toFloat(),
                    "XYZ",
                )
                values[i * 4 + 0] = q.x
                values[i * 4 + 1] = q.y
                values[i * 4 + 2] = q.z
                values[i * 4 + 3] = q.w
            }
            tracks.add(KeyframeTrack("$boneName.quaternion", times, values))
        }

        // Hips translation offset (metres from rest).
        val hipsRestPosition = humanoid.getNormalizedRestPosition(HumanBoneName.HIPS)
        if (spec.hips.isNotEmpty() && hipsRestPosition != null) {
            val sorted = spec.hips.sortedBy { it.t }
            val times = FloatArray(sorted.size)
            val values = FloatArray(sorted.size * 3)
            sorted.forEachIndexed { i, k ->
                times[i] = k.t
                values[i * 3 + 0] = hipsRestPosition.x + k.p.getOrElse(0) { 0f }
                values[i * 3 + 1] = hipsRestPosition.y + k.p.getOrElse(1) { 0f }
                values[i * 3 + 2] = hipsRestPosition.z + k.p.getOrElse(2) { 0f }
            }
            tracks.add(KeyframeTrack("${HumanBoneName.HIPS}.position", times, values))
        }

        // Expression weights (preset + custom), mapped through the manager.
        if (expressionManager != null) {
            for ((name, keys) in spec.expressions) {
                val trackName = expressionManager.getExpressionTrackName(name) ?: continue
                val sorted = keys.sortedBy { it.t }
                if (sorted.isEmpty()) continue
                val times = FloatArray(sorted.size)
                val values = FloatArray(sorted.size)
                sorted.forEachIndexed { i, k ->
                    times[i] = k.t
                    values[i] = k.w.coerceIn(0f, 1f)
                }
                tracks.add(KeyframeTrack(trackName, times, values))
            }
        }

        return VRMAnimationClip(
            name = spec.name,
            duration = max(spec.duration, 0.01f),
            tracks = tracks,
        )
    }
}
