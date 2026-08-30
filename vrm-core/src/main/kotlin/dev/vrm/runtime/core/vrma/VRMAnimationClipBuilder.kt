package dev.vrm.runtime.core.vrma

import dev.vrm.runtime.core.expression.ExpressionManager
import dev.vrm.runtime.core.humanoid.VRMHumanoid

/**
 * A retargeted animation clip: a named list of keyframe tracks bound to a
 * target avatar. Engine-agnostic — the host (e.g. the Filament demo) maps
 * track names onto its scene graph / morph channels.
 *
 * Track name conventions (mirroring three-vrm's `createVRMAnimationClip`):
 *  - `<bone>.quaternion`  -> normalized rig bone local rotation
 *  - `<bone>.position`    -> normalized rig bone local translation (hips only)
 *  - `<expressionName>.weight`       -> expression weight (morph / material binds)
 *  - `lookAt.quaternion`             -> lookAt node rotation (optional)
 */
class VRMAnimationClip(
    val name: String,
    val duration: Float,
    val tracks: List<KeyframeTrack>,
)

/**
 * Builds a [VRMAnimationClip] from a [VrmAnimation] and a target [VRMHumanoid]
 * (+ [ExpressionManager]), retargeting the VRMA's tracks onto the avatar's
 * normalized rig and expression names. Port of three-vrm's
 * `createVRMAnimationClip.ts` / `createVRMAnimationHumanoidTracks` /
 * `createVRMAnimationExpressionTracks`, with the VRM 0.x axis flip omitted
 * (this library only supports VRM 1.0).
 *
 * @param vrmAnimation the parsed VRMA animation
 * @param humanoid the target avatar's humanoid (normalized rig is required)
 * @param expressionManager the target avatar's expression manager, or null
 */
class VRMAnimationClipBuilder(
    private val vrmAnimation: VrmAnimation,
    private val humanoid: VRMHumanoid,
    private val expressionManager: ExpressionManager? = null,
) {
    /**
     * Build the clip. Tracks whose target bone/expression is missing on the
     * avatar are dropped (mirroring three-vrm's null checks).
     */
    fun build(): VRMAnimationClip {
        val tracks = ArrayList<KeyframeTrack>()

        // humanoid rotation
        for ((boneName, track) in vrmAnimation.humanoidTracks.rotation) {
            val node = humanoid.getNormalizedBoneNode(boneName) ?: continue
            // track names use the BARE human-bone name (e.g. "hips.quaternion"),
            // matching how players look up bones on the normalized rig — NOT the
            // prefixed node name ("Normalized_hips"), which would never resolve.
            tracks.add(track.copyWithName("$boneName.quaternion"))
        }

        // humanoid translation (hips only, vertical-scale normalized)
        for ((boneName, track) in vrmAnimation.humanoidTracks.translation) {
            val node = humanoid.getNormalizedBoneNode(boneName) ?: continue
            val animationY = vrmAnimation.restHipsPosition.y
            val humanoidY = node.position.y
            val scale = if (animationY != 0f) humanoidY / animationY else 1f

            val scaled = FloatArray(track.values.size)
            for (i in track.values.indices) scaled[i] = track.values[i] * scale
            tracks.add(KeyframeTrack("$boneName.position", track.times.copyOf(), scaled))
        }

        // expression weights
        if (expressionManager != null) {
            for ((name, track) in vrmAnimation.expressionTracks.preset) {
                val trackName = expressionManager.getExpressionTrackName(name) ?: continue
                tracks.add(track.copyWithName(trackName))
            }
            for ((name, track) in vrmAnimation.expressionTracks.custom) {
                val trackName = expressionManager.getExpressionTrackName(name) ?: continue
                tracks.add(track.copyWithName(trackName))
            }
        }

        // lookAt (optional)
        vrmAnimation.lookAtTrack?.let { tracks.add(it.copyWithName("lookAt.quaternion")) }

        return VRMAnimationClip("Clip", vrmAnimation.duration, tracks)
    }
}
