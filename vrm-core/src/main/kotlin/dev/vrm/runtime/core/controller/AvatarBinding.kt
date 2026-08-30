package dev.vrm.runtime.core.controller

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
