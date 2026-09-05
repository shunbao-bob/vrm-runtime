package dev.vrm.runtime.demo

import dev.vrm.runtime.adapter.AvatarEngineController
import dev.vrm.runtime.adapter.AvatarRenderer
import dev.vrm.runtime.character.CharacterOutput
import dev.vrm.runtime.core.controller.AvatarCommand
import dev.vrm.runtime.core.controller.AvatarController

/**
 * Glue between the engine-agnostic [CharacterOutput] port (vrm-character) and
 * the Filament-backed [AvatarRenderer] / [AvatarController] (vrm-adapter).
 *
 * Commands issued by vrm-character flow through [AvatarController.execute] (the
 * existing command bus), which dispatches to the engine's AvatarBinding hooks
 * (the locomotion ones implemented on [AvatarEngineController]). State reads
 * come straight from the engine controller's live locomotion getters.
 */
class DemoCharacterOutput(
    /** Command bus; may be null until a model is loaded. */
    private val controllerRef: () -> AvatarController?,
    /** Engine controller exposing locomotion state; may be null. */
    private val engineRef: () -> AvatarEngineController?,
) : CharacterOutput {

    override fun execute(command: AvatarCommand) {
        controllerRef()?.execute(command)
    }

    override fun positionXZ(): Pair<Float, Float> =
        engineRef()?.let { it.getLocomotionXZ() } ?: (0f to 0f)

    override fun yawDegrees(): Float =
        engineRef()?.getLocomotionYaw() ?: 0f

    override fun speed(): Float =
        engineRef()?.getLocomotionSpeed() ?: 0f

    override fun isMoving(): Boolean =
        engineRef()?.isMoving() ?: false
}