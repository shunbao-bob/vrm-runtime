package dev.vrm.runtime.character

import dev.vrm.runtime.core.controller.AvatarCommand

/**
 * Drives the character's whole-body movement.
 *
 * The ENGINE (vrm-adapter) integrates a MoveTo each frame (turn + move toward
 * target at speed, stop at arrivalRadius). This controller decides WHAT to
 * move toward: an explicit target ([moveTo]), a scene anchor ([moveToAnchor]),
 * or a wander target from [WanderAI]. It also derives the locomotion state
 * (IDLE / WALK / RUN) from the engine's current speed, which the host maps to
 * the locomotion animation clips.
 *
 * Pure Kotlin + [CharacterOutput] + [CollisionWorld]: JVM-testable.
 */
class MotionController(
    private val output: CharacterOutput,
    private val world: CollisionWorld,
) {

    enum class LocomotionState { IDLE, WALK, RUN }

    var state: LocomotionState = LocomotionState.IDLE
        private set

    // 0.6 u/s matches WalkMotion's 2s two-step cycle step frequency;
    // higher speeds make the body slide faster than the feet step.
    var walkSpeed = 0.6f
        set(v) { field = v; if (v > 0f) engineMoveSpeed = v }
    var runSpeed = 3.2f
    var runThreshold = 2.2f
    var arrivalRadius = 0.15f

    /** When true, the character autonomously wanders (WanderAI). */
    var wanderEnabled = false
    var wander: WanderAI? = null

    /** The currently active MoveTo target, if the character is committed to it. */
    var activeTarget: Pair<Float, Float>? = null
        private set

    private var engineMoveSpeed = 1.5f

    /** Explicitly move to an absolute world XZ. */
    fun moveTo(x: Float, z: Float, speed: Float = walkSpeed): Boolean {
        if (!x.isFinite() || !z.isFinite() || !speed.isFinite() || speed <= 0f) return false
        val (targetX, targetZ) = world.clampToBounds(x, z)
        if (world.isBlocked(targetX, targetZ)) return false
        val (originX, originZ) = output.positionXZ()
        if (!world.isPathClear(originX, originZ, targetX, targetZ)) return false
        engineMoveSpeed = speed
        activeTarget = targetX to targetZ
        output.execute(AvatarCommand.MoveTo(targetX, targetZ, speed = speed, arrivalRadius = arrivalRadius))
        return true
    }

    /** Move to a named anchor defined in [SceneConfig]. */
    fun moveToAnchor(anchor: SceneAnchor, speed: Float = walkSpeed): Boolean =
        moveTo(anchor.x, anchor.z, speed)

    /** Stop all locomotion. */
    fun stop() {
        activeTarget = null
        output.execute(AvatarCommand.StopMove())
    }

    /** Advance one frame: derive state, and drive the wander loop. */
    fun update(dt: Float) {
        val s = output.speed()
        state = when {
            s <= 0.02f -> LocomotionState.IDLE
            s >= runThreshold -> LocomotionState.RUN
            else -> LocomotionState.WALK
        }

        // If the engine stopped (arrived / aborted), clear our committed target.
        if (activeTarget != null && !output.isMoving()) {
            activeTarget = null
        }

        // Autonomous wander: pick a new target when idle.
        if (wanderEnabled && activeTarget == null) {
            val (x, z) = output.positionXZ()
            val target = wander?.update(x, z, output.isMoving(), dt)
            if (target != null) {
                moveTo(target.first, target.second)
            }
        }
    }
}
