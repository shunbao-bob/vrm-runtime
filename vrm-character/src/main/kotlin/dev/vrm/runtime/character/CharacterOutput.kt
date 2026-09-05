package dev.vrm.runtime.character

import dev.vrm.runtime.core.controller.AvatarCommand

/**
 * The narrow port vrm-character uses to talk to the engine. Kept engine-agnostic
 * so vrm-character stays pure Kotlin and JVM-testable: the host (Filament demo)
 * wires this to [dev.vrm.runtime.core.controller.AvatarController] +
 * the engine's locomotion getters.
 *
 * Read methods return the *current* locomotion state so the character layer can
 * make AI decisions (arrival detection, obstacle re-planning) without touching
 * Filament.
 */
interface CharacterOutput {
    /** Send one command to the engine (MoveTo / TurnTo / RawExpression / ...). */
    fun execute(command: AvatarCommand)

    /** Current world XZ of the model node. */
    fun positionXZ(): Pair<Float, Float>

    /** Current facing yaw in degrees (0 = facing +Z). */
    fun yawDegrees(): Float

    /** Current locomotion speed (units/s). */
    fun speed(): Float

    /** True while the engine is actively moving toward a MoveTo target. */
    fun isMoving(): Boolean
}

/** A simple engine-agnostic fake output for JVM tests. */
class FakeCharacterOutput : CharacterOutput {
    val sentCommands = mutableListOf<AvatarCommand>()
    private var x = 0f
    private var z = 0f
    private var yaw = 0f
    private var spd = 0f
    private var moving = false

    override fun positionXZ(): Pair<Float, Float> = x to z
    override fun yawDegrees(): Float = yaw
    override fun speed(): Float = spd
    override fun isMoving(): Boolean = moving

    override fun execute(command: AvatarCommand) {
        sentCommands += command
        when (command) {
            is AvatarCommand.SetWorldTransform -> {
                x = command.x; z = command.z; yaw = command.yawDegrees; spd = 0f; moving = false
            }
            is AvatarCommand.MoveTo -> { moving = true }
            is AvatarCommand.StopMove -> { moving = false; spd = 0f }
            else -> {}
        }
    }

    fun setXZ(nx: Float, nz: Float) { x = nx; z = nz }
}
