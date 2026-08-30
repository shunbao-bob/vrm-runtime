package dev.vrm.runtime.core.lookAt

import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Calculate azimuth / altitude angles from a direction vector,
 * port of three-vrm's `calcAzimuthAltitude`.
 *
 * Returns a pair of `(azimuth, altitude)` in radians. Azimuth is the angle
 * around the Y axis, altitude the angle around the Z axis, both measured from
 * the +X axis, rotated in intrinsic Y-Z order.
 *
 * `azimuth = atan2(-z, x)`
 * `altitude = atan2(y, sqrt(x^2 + z^2))`
 */
fun calcAzimuthAltitude(x: Float, y: Float, z: Float): Pair<Float, Float> {
    val azimuth = atan2(-z, x)
    val altitude = atan2(y, sqrt(x * x + z * z))
    return azimuth to altitude
}

/**
 * Make sure an angle is within [-PI, PI], mirroring three-vrm's `sanitizeAngle`.
 *
 * ```kotlin
 * sanitizeAngle(1.5 * PI) // == -0.5 * PI
 * ```
 *
 * @param angle an angle in radians
 */
fun sanitizeAngle(angle: Float): Float {
    val roundTurn = kotlin.math.round(angle / 2.0f / PI.toFloat())
    return angle - 2.0f * PI.toFloat() * roundTurn
}

/**
 * Convert a world-space gaze-direction quaternion into yaw/pitch degrees,
 * matching the convention of [dev.vrm.runtime.core.lookAt.VrmLookAt.lookAt]:
 * base direction is +Z (VRM faceFront), yaw is the horizontal rotation around
 * Y, pitch the vertical rotation around X (positive = up).
 *
 * Used by VRMA lookAt-track playback: the VRMC_vrm_animation `lookAt` node's
 * quaternion defines the model's world-space gaze orientation; this converts it
 * to the [VrmLookAt]-consumable angles.
 *
 * @param q the lookAt node's normalized world-space quaternion
 * @return Pair(yaw, pitch) in degrees
 */
fun quatToLookAtDegrees(q: Quat): Pair<Float, Float> {
    // faceFront = +Z (VRM 1.0) — the forward gaze direction is q rotated (0,0,1).
    val front = q.rotate(Vec3(0f, 0f, 1f)).normalize()
    val (azimuth, altitude) = calcAzimuthAltitude(front.x, front.y, front.z)
    // Same basis as VrmLookAt.lookAt(): the azimuth, altitude of faceFront(0,0,1)
    val azimuthFrom = calcAzimuthAltitude(0f, 0f, 1f).first
    val yawRad = sanitizeAngle(azimuth - azimuthFrom)
    val pitchRad = sanitizeAngle(-altitude)
    return (java.lang.Math.toDegrees(yawRad.toDouble()).toFloat()) to
        (java.lang.Math.toDegrees(pitchRad.toDouble()).toFloat())
}