package dev.vrm.runtime.adapter

import dev.vrm.runtime.core.expression.ExpressionManager
import dev.vrm.runtime.core.humanoid.HumanBoneName
import dev.vrm.runtime.core.humanoid.PoseTransform
import dev.vrm.runtime.core.humanoid.VRMHumanoid
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3
import dev.vrm.runtime.core.vrma.KeyframeTrack
import dev.vrm.runtime.core.vrma.VRMAnimationClip

/**
 * Plays a retargeted [VRMAnimationClip] onto a [VRMHumanoid]'s *normalized* rig
 * plus an [ExpressionManager], once per frame.
 *
 * The core library intentionally keeps the clip engine-agnostic: track names
 * follow the `Normalized_<bone>.quaternion|position` and `<expr>.weight`
 * conventions produced by VRMAnimationClipBuilder. This player is the
 * thin binding that turns those tracks into bone rotations / expression
 * weights at the current playback time, then calls [VRMHumanoid.update] so the
 * pose is pushed to the engine-bound raw bones.
 */
class VrmAnimationPlayer(
    private val humanoid: VRMHumanoid,
    private val expressionManager: ExpressionManager?,
    val clip: VRMAnimationClip,
    /** Optional look-at controller; when set, the clip's `lookAt.quaternion`
     *  track drives its yaw/pitch (world-space gaze direction). */
    private val lookAt: dev.vrm.runtime.core.lookAt.VrmLookAt? = null,
) {
    /** Playback clock, seconds. */
    var time: Float = 0f

    /** Whether time advances in [update]. */
    var playing: Boolean = true

    /** Wrap at the clip end instead of stopping. */
    var loop: Boolean = true

    /** Playback speed multiplier. */
    var speed: Float = 1f
    
    /** Blend weight 0~1 for cross-fade. 1 = full clip, 0 = no influence. */
    var blendWeight: Float = 1f

    val duration: Float get() = clip.duration

    private val rotationTracks: Map<String, KeyframeTrack> by lazy {
        clip.tracks.filter { it.name.endsWith(".quaternion") && !it.name.startsWith("lookAt.") }
            .associateBy { it.name.removeSuffix(".quaternion") }
    }
    private val positionTracks: Map<String, KeyframeTrack> by lazy {
        clip.tracks.filter { it.name.endsWith(".position") }
            .associateBy { it.name.removeSuffix(".position") }
    }
    private val weightTracks: Map<String, KeyframeTrack> by lazy {
        clip.tracks.filter { it.name.endsWith(".weight") }
            .associateBy { it.name.removeSuffix(".weight") }
    }
    private val lookAtTrack: KeyframeTrack? by lazy {
        clip.tracks.firstOrNull { it.name == "lookAt.quaternion" }
    }

    /** Advance the clip and apply its current state. Main thread. */
    fun update(deltaSeconds: Float) {
        if (!playing || duration <= 0f) return
        time += deltaSeconds * speed
        if (loop) {
            time = time % duration
        } else if (time >= duration) {
            time = duration - 0.0001f
            playing = false
        }

        val t = time

        val pose = HashMap<String, PoseTransform>()
        for (boneName in rotationTracks.keys) {
            val track = rotationTracks[boneName] ?: continue
            val q = Quat()
            sampleRotation(track, t, q)
            if (humanoid.getNormalizedBoneNode(boneName) != null) {
                val p = PoseTransform()
                p.rotation = q
                pose[boneName] = p
            }
        }

        positionTracks.filterKeys { it == HumanBoneName.HIPS }.forEach { (boneName, track) ->
            val p = sampleVec3(track, t)
            if (humanoid.getNormalizedBoneNode(boneName) != null) {
                val poseIt = pose.getOrPut(boneName) { PoseTransform() }
                poseIt.position = p
            }
        }

        if (blendWeight < 1f) {
            // Blend: read from the current humanoid pose and interpolate with blendWeight
            val currentPose = HashMap<String, PoseTransform>()
            for ((boneName, p) in pose) {
                val node = humanoid.getNormalizedBoneNode(boneName) ?: continue
                val pos = node.position
                val rot = node.quaternion
                val curPos = Vec3(pos.x, pos.y, pos.z)
                val curRot = Quat(rot.x, rot.y, rot.z, rot.w)
                val pPos = p.position ?: continue
                val pRot = p.rotation ?: Quat(0f, 0f, 0f, 1f)
                val blended = PoseTransform()
                blended.position = Vec3(
                    curPos.x + (pPos.x - curPos.x) * blendWeight,
                    curPos.y + (pPos.y - curPos.y) * blendWeight,
                    curPos.z + (pPos.z - curPos.z) * blendWeight,
                )
                blended.rotation = curRot.copy(curRot).slerp(pRot, blendWeight)
                currentPose[boneName] = blended
            }
            humanoid.setNormalizedPose(currentPose)
        } else {
            humanoid.setNormalizedPose(pose)
        }
        humanoid.update()

        for ((name, track) in weightTracks) {
            val w = sampleScalar(track, t)
            expressionManager?.setValue(name, w)
        }

        // lookAt track: world-space gaze-direction quaternion -> yaw/pitch applied to the lookAt controller
        val la = lookAt
        val lt = lookAtTrack
        if (la != null && lt != null) {
            val q = sampleQuat(lt, t)
            val (yawDeg, pitchDeg) = dev.vrm.runtime.core.lookAt.quatToLookAtDegrees(q)
            la.yaw = yawDeg
            la.pitch = pitchDeg
            la.update(0f)
        }
    }

    private fun sampleQuat(track: KeyframeTrack, t: Float): dev.vrm.runtime.core.math.Quat {
        if (track.times.size == 1) {
            return dev.vrm.runtime.core.math.Quat(track.values[0], track.values[1], track.values[2], track.values[3]).normalized()
        }
        val idx = findSegment(track, t)
        val t0 = track.times[idx]
        val t1 = track.times[idx + 1]
        val u = if (t1 > t0) (t - t0) / (t1 - t0) else 0f
        val k = 4
        val a = idx * k
        val b = (idx + 1) * k
        val qa = dev.vrm.runtime.core.math.Quat(track.values[a], track.values[a + 1], track.values[a + 2], track.values[a + 3])
        val qb = dev.vrm.runtime.core.math.Quat(track.values[b], track.values[b + 1], track.values[b + 2], track.values[b + 3])
        return dev.vrm.runtime.core.math.Quat().copy(qa).slerp(qb, u).normalized()
    }

    private fun sampleRotation(track: KeyframeTrack, t: Float, out: Quat): Quat {
        // glTF permits a sampler with a single keyframe (common for static
        // expression/pose tracks).  There is no segment to interpolate in
        // that case; keep the keyframe value for the whole clip.
        if (track.times.size == 1) {
            out.set(track.values[0], track.values[1], track.values[2], track.values[3])
            return out
        }
        val idx = findSegment(track, t)
        val t0 = track.times[idx]
        val t1 = track.times[idx + 1]
        val u = if (t1 > t0) (t - t0) / (t1 - t0) else 0f
        val k = 4
        val a = idx * k
        val b = (idx + 1) * k
        val qa = Quat(track.values[a], track.values[a + 1], track.values[a + 2], track.values[a + 3])
        val qb = Quat(track.values[b], track.values[b + 1], track.values[b + 2], track.values[b + 3])
        return out.copy(qa).slerp(qb, u)
    }

    private fun sampleVec3(track: KeyframeTrack, t: Float): Vec3 {
        if (track.times.size == 1) {
            return Vec3(track.values[0], track.values[1], track.values[2])
        }
        val idx = findSegment(track, t)
        val t0 = track.times[idx]
        val t1 = track.times[idx + 1]
        val u = if (t1 > t0) (t - t0) / (t1 - t0) else 0f
        val k = 3
        val a = idx * k
        val b = (idx + 1) * k
        return Vec3(
            lerp(track.values[a], track.values[b], u),
            lerp(track.values[a + 1], track.values[b + 1], u),
            lerp(track.values[a + 2], track.values[b + 2], u),
        )
    }

    private fun sampleScalar(track: KeyframeTrack, t: Float): Float {
        if (track.times.size == 1) return track.values[0]
        val idx = findSegment(track, t)
        val t0 = track.times[idx]
        val t1 = track.times[idx + 1]
        val u = if (t1 > t0) (t - t0) / (t1 - t0) else 0f
        return lerp(track.values[idx], track.values[idx + 1], u)
    }

    private fun findSegment(track: KeyframeTrack, t: Float): Int {
        val times = track.times
        if (times.size < 2) return 0
        var lo = 0
        var hi = times.size - 2
        var best = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best.coerceAtMost(times.size - 2).coerceAtLeast(0)
    }

    private fun lerp(a: Float, b: Float, u: Float): Float = a + (b - a) * u
}
