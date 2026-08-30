package dev.vrm.runtime.core.controller

import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3

/**
 * A single imperative command for an avatar, port of xlunar-ai-avatar's
 * `AvatarCommand` (AvatarController.ts). Engine-agnostic: the host binds
 * these to its renderer via [AvatarBinding].
 *
 * Command types mirror xlunar:
 *  - [SetPose] / [SetExpression] / [PlayVrma] / [SetSequence] / [Wait] / [Reset]
 *  - raw bone / expression control for LLM- or runtime-generated input.
 */
sealed interface AvatarCommand {
    /** A T-pose / preset pose by config id. */
    data class SetPose(val id: String) : AvatarCommand

    /** A hand gesture by config id (VRM A-pose style). */
    data class SetHandGesture(val id: String) : AvatarCommand

    /** A body gesture (nod / wave) by config id. */
    data class SetBodyGesture(val id: String) : AvatarCommand

    /** A body motion loop by config id. */
    data class SetBodyMotion(val id: String) : AvatarCommand

    /** An expression by config id (happy / sad / angry / ...). */
    data class SetExpression(val id: String) : AvatarCommand

    /** Play a VRMA clip: by config id, or a raw URL/buffer source. */
    data class PlayVrma(
        val id: String? = null,
        val source: String? = null,
        val loop: Boolean = true,
    ) : AvatarCommand

    /** Play a choreography sequence by config id. */
    data class SetSequence(val id: String) : AvatarCommand

    /** Block the queue for [durationMillis]. */
    data class Wait(val durationMillis: Long) : AvatarCommand

    /** Reset pose/expression/vrma/sequence to neutral. */
    data object Reset : AvatarCommand

    /** Reset only pose + hand gesture. */
    data object ResetPose : AvatarCommand

    /** Reset only expression. */
    data object ResetExpression : AvatarCommand

    /** Stop the currently playing VRMA. */
    data object StopVrma : AvatarCommand

    /** Stop the currently playing sequence. */
    data object StopSequence : AvatarCommand

    /** Set raw bone rotations directly: bone name -> [RawBoneRotation]. */
    data class RawPose(val bones: Map<String, RawBoneRotation>) : AvatarCommand

    /** Set raw expression blend-shape weights: name -> 0..1. */
    data class RawExpression(val values: Map<String, Float>) : AvatarCommand
}

/**
 * A raw bone rotation expressed as [Vec3] euler angles in degrees (matching
 * xlunar's `[x, y, z]` degree tuples), or directly as a [Quat].
 */
data class RawBoneRotation(
    val degrees: Vec3? = null,
    val quaternion: Quat? = null,
)