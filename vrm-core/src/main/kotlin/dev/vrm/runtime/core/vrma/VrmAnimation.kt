package dev.vrm.runtime.core.vrma

import dev.vrm.runtime.core.math.Vec3

/**
 * A single keyframe track: a name plus parallel arrays of times and values.
 *
 * Engine-agnostic counterpart of THREE.KeyframeTrack. The interpretation of
 * `values` depends on the track kind:
 *  - rotation: 4 floats per key (x, y, z, w quaternion)
 *  - translation: 3 floats per key (x, y, z)
 *  - expression weight: 1 float per key
 *
 * `name` is either the source glTF track name (loader output) or the
 * retargeted `nodeName.quaternion` / `nodeName.position` / `exprName.weight`
 * style name produced by the clip builder.
 */
class KeyframeTrack(
    val name: String,
    val times: FloatArray,
    val values: FloatArray,
) {
    /** Number of keyframes. */
    val keyCount: Int get() = times.size

    /** Number of floats per key (3 for translation, 4 for rotation, 1 for weight). */
    val valueStride: Int get() = if (values.isEmpty()) 0 else values.size / keyCount.coerceAtLeast(1)

    fun copyWithName(newName: String): KeyframeTrack = KeyframeTrack(newName, times.copyOf(), values.copyOf())

    override fun toString(): String = "KeyframeTrack('$name', $keyCount keys)"
}

/**
 * Represents a single VRM Animation (a parsed `.vrma` clip).
 *
 * Port of three-vrm's `VRMAnimation` (VRMAnimation.ts), engine-agnostic.
 * The loader produces one of these per glTF `animation` in the VRMA file.
 */
class VrmAnimation {
    /** Duration of the clip in seconds. */
    var duration: Float = 0f

    /** World-space position of the hips node in the animation's rest pose. */
    var restHipsPosition: Vec3 = Vec3()

    /** Humanoid bone tracks keyed by bone name. */
    val humanoidTracks = HumanoidTracks()

    /** Expression weight tracks, split into preset and custom. */
    val expressionTracks = ExpressionTracks()

    /** LookAt rotation track (node-relative quaternion). */
    var lookAtTrack: KeyframeTrack? = null

    class HumanoidTracks {
        /** Only `hips` translation is permitted by the VRMC_vrm_animation spec. */
        val translation = HashMap<String, KeyframeTrack>()

        /** Bone name -> rotation track. */
        val rotation = HashMap<String, KeyframeTrack>()
    }

    class ExpressionTracks {
        /** Preset expression name -> weight track. */
        val preset = HashMap<String, KeyframeTrack>()

        /** Custom expression name -> weight track. */
        val custom = HashMap<String, KeyframeTrack>()
    }
}
