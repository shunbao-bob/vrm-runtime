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

    /** True only when playback reached its natural end or finished fading out. */
    var completed: Boolean = false
        private set

    /** True while a fade-out to idle is in progress (clip still sampling but
     *  influence ramping to 0). Lets the controller restore idle breathing
     *  during the transition instead of waiting for [completed]. */
    var fadingOut: Boolean = false
        private set
    
    /** Blend weight 0~1 for cross-fade. 1 = full clip, 0 = no influence. */
    var blendWeight: Float = 1f

    /** Duration of the fade-out-to-idle transition after a one-shot clip ends. */
    var idleFadeDuration: Float = 0.45f

    /** Fade-out: while > 0 the clip keeps sampling but its influence
     *  ramps to 0 over fadeOutRemaining seconds, then the pose fully
     *  resets. Prevents the "frozen half-step pose + springbone recoil
     *  wobble" when locomotion stops abruptly. */
    var fadeOutRemaining: Float = 0f
    private var fadeOutDuration: Float = 0.3f

    /** Pose snapshot taken on the FIRST update (i.e. whatever the rig held
     *  right before this clip started — the natural idle stance). Fade-outs
     *  relax toward THIS pose, never toward the normalized rest (T-pose):
     *  resetting to T-pose left the arms straight out because the idle
     *  layer only drives the spine. */
    private var restPose: Map<String, Quat>? = null
    private var restHipsPos: Vec3? = null

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
    internal val expressionTrackNames: Set<String> get() = weightTracks.keys

    internal fun clearOwnedExpressions(keep: Set<String> = emptySet()) {
        weightTracks.keys.filterNot { it in keep }
            .forEach { expressionManager?.setValue(it, 0f) }
    }
    private val lookAtTrack: KeyframeTrack? by lazy {
        clip.tracks.firstOrNull { it.name == "lookAt.quaternion" }
    }

    /** Begin a smooth fade-out (call instead of hard-stopping). */
    fun startFadeOut(seconds: Float = 0.3f) {
        if (!playing) return
        completed = false
        fadingOut = true
        fadeOutDuration = seconds
        fadeOutRemaining = seconds
    }

    /** Advance the clip and apply its current state. Main thread. */
    fun update(deltaSeconds: Float, applyExpressions: Boolean = true) {
        if (!playing || duration <= 0f) return
        if (fadeOutRemaining > 0f) {
            fadeOutRemaining -= deltaSeconds
            fadingOut = true
            val linear = (fadeOutRemaining / fadeOutDuration).coerceIn(0f, 1f)
            // Ease-in-out so the tail doesn't slam to zero: keep most of the
            // motion early, glide out gently at the end (natural come-down).
            blendWeight = linear * linear * (3f - 2f * linear)
            if (fadeOutRemaining <= 0f) {
                playing = false
                completed = true
                fadingOut = false
                if (applyExpressions) clearOwnedExpressions()
                // Settle on the pre-walk idle stance snapshot.
                // the normalized rest here: that is a T-pose, and the idle
                // layer only drives the spine — the arms stayed straight out.
                val settle = HashMap<String, PoseTransform>()
                restPose.orEmpty().forEach { (boneName, q) ->
                    if (humanoid.getNormalizedBoneNode(boneName) != null) {
                        val p = PoseTransform()
                        p.rotation = Quat(q.x, q.y, q.z, q.w)
                        settle[boneName] = p
                    }
                }
                restHipsPos?.let { rh ->
                    val p = settle.getOrPut(HumanBoneName.HIPS) { PoseTransform() }
                    p.position = Vec3(rh.x, rh.y, rh.z)
                }
                humanoid.setNormalizedPose(settle)
                humanoid.update()
                return
            }
        }
        var finishedThisFrame = false
        if (fadeOutRemaining > 0f) {
            // Already fading out (explicit stop): freeze at the final frame — the
            // fade-out block above is relaxing toward the rest (idle) pose.
        } else {
            time += deltaSeconds * speed
            if (loop) {
                time = time % duration
            } else if (time >= duration) {
                time = duration
                // Natural end of a one-shot clip: fade out smoothly to the rest
                // pose (the idle stance captured at clip start) instead of
                // freezing on the last frame — which could leave e.g. the arms
                // clipping into the body.
                fadeOutDuration = idleFadeDuration
                fadeOutRemaining = idleFadeDuration
            }
        }

        val t = time

        // One-time snapshot of the rig as it was BEFORE this clip's first
        // write (the pre-walk idle stance) — the fade-out target.
        if (restPose == null) {
            val cap = HashMap<String, Quat>()
            for (boneName in rotationTracks.keys) {
                val node = humanoid.getNormalizedBoneNode(boneName) ?: continue
                val q = node.quaternion
                cap[boneName] = Quat(q.x, q.y, q.z, q.w)
            }
            restPose = cap
            humanoid.getNormalizedBoneNode(HumanBoneName.HIPS)?.let { n ->
                val p = n.position
                restHipsPos = Vec3(p.x, p.y, p.z)
            }
        }

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

        // Hips position: keep ONLY the vertical (Y) component. These performer
        // clips are authored to be played in place, but many carry a horizontal
        // hips offset that walks the whole avatar forward/backward during the
        // clip — then the fade-out pulls it back, reading as a "shrink"/jump at
        // the end. Zeroing X/Z keeps jump/squat bobbing (Y) while the avatar
        // stays rooted in place.
        positionTracks.filterKeys { it == HumanBoneName.HIPS }.forEach { (boneName, track) ->
            val p = sampleVec3(track, t)
            if (humanoid.getNormalizedBoneNode(boneName) != null) {
                val poseIt = pose.getOrPut(boneName) { PoseTransform() }
                poseIt.position = Vec3(0f, p.y, 0f)
            }
        }

        // Fade-out: blend the SAMPLED pose toward the rest (identity)
        // rig, then apply absolutely. Never blend toward the nodes'
        // current values here — that self-reference would freeze the
        // frozen half-step pose instead of relaxing to rest.
        val fading = fadeOutRemaining > 0f
        if (fading) {
            val snap = restPose.orEmpty()
            val progress = 1f - blendWeight
            for ((boneName, p) in pose) {
                val r = p.rotation
                val restQ = snap[boneName] ?: Quat(0f, 0f, 0f, 1f)
                if (r != null) {
                    p.rotation = Quat().copy(r).slerp(restQ, progress).normalized()
                }
                if (boneName == HumanBoneName.HIPS && p.position != null) {
                    val pp = p.position!!
                    val rh = restHipsPos
                    p.position = if (rh != null) Vec3(
                        pp.x + (rh.x - pp.x) * progress,
                        pp.y + (rh.y - pp.y) * progress,
                        pp.z + (rh.z - pp.z) * progress,
                    ) else Vec3(pp.x * blendWeight, pp.y * blendWeight, pp.z * blendWeight)
                }
            }
            humanoid.setNormalizedPose(pose)
        } else if (blendWeight < 1f) {
            // Blend: interpolate toward the captured REST pose with blendWeight
            // (blending toward the nodes' current values would just freeze the
            // last clip frame — the fade target must be the rest pose).
            val currentPose = HashMap<String, PoseTransform>()
            for ((boneName, p) in pose) {
                val node = humanoid.getNormalizedBoneNode(boneName) ?: continue
                val pos = node.position
                val rot = node.quaternion
                val curPos = Vec3(pos.x, pos.y, pos.z)
                val curRot = Quat(rot.x, rot.y, rot.z, rot.w)
                val pPos = p.position
                val pRot = p.rotation
                val blended = PoseTransform()
                if (pPos != null) {
                    blended.position = Vec3(
                        curPos.x + (pPos.x - curPos.x) * blendWeight,
                        curPos.y + (pPos.y - curPos.y) * blendWeight,
                        curPos.z + (pPos.z - curPos.z) * blendWeight,
                    )
                }
                if (pRot != null) blended.rotation = curRot.copy().slerp(pRot, blendWeight)
                currentPose[boneName] = blended
            }
            humanoid.setNormalizedPose(currentPose)
        } else {
            humanoid.setNormalizedPose(pose)
        }
        humanoid.update()

        if (applyExpressions) {
            sampledExpressionWeights().forEach { (name, weight) ->
                expressionManager?.setValue(name, weight)
            }
        }
        if (finishedThisFrame && applyExpressions) clearOwnedExpressions()

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

    /** Current expression contribution for external two-player composition. */
    internal fun sampledExpressionWeights(): Map<String, Float> {
        if (completed || duration <= 0f) return emptyMap()
        return weightTracks.mapValues { (_, track) ->
            (sampleScalar(track, time) * blendWeight).coerceIn(0f, 1f)
        }
    }

    private fun sampleQuat(track: KeyframeTrack, t: Float): dev.vrm.runtime.core.math.Quat {
        if (track.times.size == 1) {
            return dev.vrm.runtime.core.math.Quat(track.values[0], track.values[1], track.values[2], track.values[3]).normalized()
        }
        val idx = findSegment(track, t)
        val t0 = track.times[idx]
        val t1 = track.times[idx + 1]
        val u = if (t1 > t0) ((t - t0) / (t1 - t0)).coerceIn(0f, 1f) else 0f
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
        val u = if (t1 > t0) ((t - t0) / (t1 - t0)).coerceIn(0f, 1f) else 0f
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
        val u = if (t1 > t0) ((t - t0) / (t1 - t0)).coerceIn(0f, 1f) else 0f
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
        val u = if (t1 > t0) ((t - t0) / (t1 - t0)).coerceIn(0f, 1f) else 0f
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
