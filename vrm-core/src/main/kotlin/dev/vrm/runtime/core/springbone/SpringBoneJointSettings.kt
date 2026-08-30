package dev.vrm.runtime.core.springbone

import dev.vrm.runtime.core.math.Vec3

/**
 * Tunable physics parameters of a single spring-bone joint. Port of three-vrm's
 * `VRMSpringBoneJointSettings`.
 *
 * @param hitRadius radius of the joint used for collision (world units)
 * @param stiffness how strongly the joint is pulled back toward its parent's
 *   orientation (0..1 typically; higher = stiffer)
 * @param gravityPower strength of the gravity force applied to the joint
 * @param gravityDir direction of the gravity force (unit vector)
 * @param dragForce velocity damping per frame (0 = no damping, 1 = full stop)
 */
data class SpringBoneJointSettings(
    val hitRadius: Float = 0f,
    val stiffness: Float = 1f,
    val gravityPower: Float = 0f,
    val gravityDir: Vec3 = Vec3(0f, -1f, 0f),
    val dragForce: Float = 0.4f,
)
