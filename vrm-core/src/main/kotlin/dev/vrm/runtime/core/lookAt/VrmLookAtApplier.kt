package dev.vrm.runtime.core.lookAt

/**
 * Applies an eye-gaze direction (yaw / pitch in degrees) to a VRM model.
 * Port of three-vrm's `VRMLookAtApplier` interface. Two concrete implementations
 * are provided: [VrmLookAtExpressionApplier] (drives look* expressions) and
 * [VrmLookAtBoneApplier] (rotates the left/right eye bones directly).
 */
interface VrmLookAtApplier {
    /**
     * Apply the given angles to the model.
     *
     * @param yaw   rotation around the Y axis, in degrees
     * @param pitch rotation around the X axis, in degrees
     */
    fun applyYawPitch(yaw: Float, pitch: Float)
}