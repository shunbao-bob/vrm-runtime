package dev.vrm.runtime.core.controller

import dev.vrm.runtime.core.motion.MotionSpec

/**
 * Engine-specific hooks that [AvatarController] uses to actually drive the
 * avatar. The core is engine-agnostic; a host (Filament demo, headless test)
 * implements this interface.
 *
 * Port of xlunar's `ControllerRefs` (AvatarRenderer -> AvatarController bridge).
 */
interface AvatarBinding {
    /** Set the avatar to a named T-pose / preset pose, or null to clear. */
    fun setPose(id: String?)

    /** Set a named hand gesture, or null to clear. */
    fun setHandGesture(id: String?)

    /** Set a named body gesture, or null to clear. */
    fun setBodyGesture(id: String?)

    /** Set a named body-motion loop, or null to clear. */
    fun setBodyMotion(id: String?)

    /** Set a named expression, or null to clear. */
    fun setExpression(id: String?)

    /** Play a VRMA clip by source (url / resource key), or null to stop. */
    fun playVrma(source: String?, loop: Boolean)

    /** Play a choreography sequence by id, or null to stop. */
    fun setSequence(id: String?)

    /** Set raw bone rotations by bone name. */
    fun setRawPose(bones: Map<String, RawBoneRotation>)

    /** Set raw expression weights (name -> 0..1). */
    fun setRawExpression(values: Map<String, Float>)

    // ── Locomotion (whole-body movement) hooks ──
    // Optional by default so lightweight bindings / test stubs don't have to
    // implement them; the Filament engine overrides these to drive the model
    // node's world transform. vrm-character issues these via AvatarCommand.

    /** Move toward an absolute world target at [speed]; stop within [arrivalRadius]. */
    fun moveTo(x: Float, z: Float, y: Float, speed: Float, arrivalRadius: Float) = Unit

    /** Turn the facing to an absolute yaw (degrees) at [turnSpeedDegPerSec]. */
    fun turnTo(yawDegrees: Float, turnSpeedDegPerSec: Float) = Unit

    /** Immediately place the model node at a world transform (teleport). */
    fun setWorldTransform(x: Float, y: Float, z: Float, yawDegrees: Float) = Unit

    /** Brake to zero speed over [deceleration] seconds. */
    fun stopMove(deceleration: Float) = Unit

    /** Configure the locomotion state machine clip mapping. */
    fun setLocomotion(
        idle: String?, walk: String?, run: String?,
        maxWalkSpeed: Float, maxRunSpeed: Float,
    ) = Unit

    /** Play an LLM-generated keyframe animation ([MotionSpec]). */
    fun playMotionSpec(spec: MotionSpec, loop: Boolean = false) = Unit

    /** Reset everything to neutral. */
    fun reset()
}

/**
 * Event types emitted by [AvatarController]. Port of xlunar's
 * `AvatarEventType` (ready / command / state-change / error / queue-start /
 * queue-complete / sequence-complete / vrma-complete).
 */
enum class AvatarEventType {
    READY,
    COMMAND,
    STATE_CHANGE,
    ERROR,
    QUEUE_START,
    QUEUE_COMPLETE,
    SEQUENCE_COMPLETE,
    VRMA_COMPLETE,
}

/** A single event: type + timestamp + optional payload. */
data class AvatarEvent(
    val type: AvatarEventType,
    val timestamp: Long = System.currentTimeMillis(),
    val data: Map<String, Any?> = emptyMap(),
)

/**
 * Snapshot of the avatar's current control state.
 * Mirrors xlunar's `AvatarState`.
 */
data class AvatarState(
    val ready: Boolean = false,
    val pose: String? = null,
    val handGesture: String? = null,
    val bodyGesture: String? = null,
    val bodyMotion: String? = null,
    val expression: String? = null,
    val vrmaSource: String? = null,
    val vrmaPlaying: Boolean = false,
    val sequenceId: String? = null,
    val sequencePlaying: Boolean = false,
    val queueLength: Int = 0,
    val queueRunning: Boolean = false,
) {
    /** True when nothing is active. */
    val isIdle: Boolean
        get() = pose == null && expression == null && !vrmaPlaying && !sequencePlaying
}
