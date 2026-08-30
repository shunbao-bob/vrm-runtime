package dev.vrm.runtime.core.math

import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal quaternion, three.js style (x, y, z, w). Zero-dependency.
 * Mutable, like three.js Quaternion, to keep the three-vrm port line-for-line faithful.
 */
class Quat(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var w: Float = 1f,
) {
    fun set(nx: Float, ny: Float, nz: Float, nw: Float): Quat {
        x = nx; y = ny; z = nz; w = nw
        return this
    }

    fun copy(o: Quat): Quat = set(o.x, o.y, o.z, o.w)

    fun lengthSq() = x * x + y * y + z * z + w * w
    fun length() = sqrt(lengthSq())

    fun normalize(): Quat {
        val len = length()
        if (len > 1e-6f) {
            val inv = 1f / len
            x *= inv; y *= inv; z *= inv; w *= inv
        }
        return this
    }

    fun normalized(): Quat = Quat(x, y, z, w).normalize()

    fun conjugate(): Quat {
        x = -x; y = -y; z = -z
        return this
    }

    /** Inverse: conjugate / norm^2 (for unit quats this is just the conjugate). */
    fun invert(): Quat {
        val n = lengthSq()
        if (n > 1e-8f) {
            val inv = 1f / n
            x = -x * inv; y = -y * inv; z = -z * inv; w = w * inv
        }
        return this
    }

    fun invertedCopy(): Quat = Quat(x, y, z, w).invert()

    /** Negate. */
    fun negate(): Quat {
        x = -x; y = -y; z = -z; w = -w
        return this
    }

    /** quaternion multiplication: this = this * q */
    fun multiply(q: Quat): Quat {
        val ax = x; val ay = y; val az = z; val aw = w
        val bx = q.x; val by = q.y; val bz = q.z; val bw = q.w
        x = aw * bx + ax * bw + ay * bz - az * by
        y = aw * by - ax * bz + ay * bw + az * bx
        z = aw * bz + ax * by - ay * bx + az * bw
        w = aw * bw - ax * bx - ay * by - az * bz
        return this
    }

    /** new = this * q (non-mutating) */
    operator fun times(q: Quat): Quat = Quat(x, y, z, w).multiply(q)

    /** this = a * this  (three.js premultiply) */
    fun premultiply(a: Quat): Quat {
        val ax = a.x; val ay = a.y; val az = a.z; val aw = a.w
        val bx = x; val by = y; val bz = z; val bw = w
        x = aw * bx + ax * bw + ay * bz - az * by
        y = aw * by - ax * bz + ay * bw + az * bx
        z = aw * bz + ax * by - ay * bx + az * bw
        w = aw * bw - ax * bx - ay * by - az * bz
        return this
    }

    fun dot(o: Quat) = x * o.x + y * o.y + z * o.z + w * o.w

    /** Rotate a vector by this quaternion (three.js Vector3.applyQuaternion). */
    fun rotate(v: Vec3): Vec3 {
        val qx = x
        val qy = y
        val qz = z
        val qw = w
        val ix = qw * v.x + qy * v.z - qz * v.y
        val iy = qw * v.y + qz * v.x - qx * v.z
        val iz = qw * v.z + qx * v.y - qy * v.x
        val iw = -qx * v.x - qy * v.y - qz * v.z
        return Vec3(
            ix * qw + iw * -qx + iy * -qz - iz * -qy,
            iy * qw + iw * -qy + iz * -qx - ix * -qz,
            iz * qw + iw * -qz + ix * -qy - iy * -qx,
        )
    }

    /** Spherical linear interpolation between this and o. */
    fun slerp(o: Quat, t: Float): Quat {
        if (t <= 0f) return this
        if (t >= 1f) { copy(o); return this }
        var cosHalfTheta = dot(o)
        var qb = o
        if (cosHalfTheta < 0f) {
            qb = Quat(-o.x, -o.y, -o.z, -o.w)
            cosHalfTheta = -cosHalfTheta
        }
        if (cosHalfTheta > 0.9995f) {
            x = x + (qb.x - x) * t
            y = y + (qb.y - y) * t
            z = z + (qb.z - z) * t
            w = w + (qb.w - w) * t
            return normalize()
        }
        val halfTheta = acos(cosHalfTheta)
        val sinHalfTheta = sqrt(1f - cosHalfTheta * cosHalfTheta)
        val ratioA = sin((1f - t) * halfTheta) / sinHalfTheta
        val ratioB = sin(t * halfTheta) / sinHalfTheta
        x = x * ratioA + qb.x * ratioB
        y = y * ratioA + qb.y * ratioB
        z = z * ratioA + qb.z * ratioB
        w = w * ratioA + qb.w * ratioB
        return this
    }

    fun toArray(): FloatArray = floatArrayOf(x, y, z, w)

    /**
     * Set this quaternion to the rotation from unit vector [from] to unit vector
     * [to] (three.js Quaternion.setFromUnitVectors). Both vectors must be
     * normalized. Used by spring-bone to convert a rotated tail direction back
     * into a bone rotation.
     */
    fun setFromUnitVectors(from: Vec3, to: Vec3): Quat {
        val EPS = 1e-6f
        var r = from.dot(to) + 1f
        if (r < EPS) {
            // vFrom and vTo point in opposite directions
            r = 0f
            if (kotlin.math.abs(from.x) > kotlin.math.abs(from.z)) {
                x = -from.y; y = from.x; z = 0f; w = r
            } else {
                x = 0f; y = -from.z; z = from.y; w = r
            }
        } else {
            x = from.y * to.z - from.z * to.y
            y = from.z * to.x - from.x * to.z
            z = from.x * to.y - from.y * to.x
            w = r
        }
        return normalize()
    }

    /**
     * Set this quaternion from Euler angles in the given order
     * (three.js Quaternion.setFromEuler). [angleX] rotates around X, [angleY]
     * around Y, [angleZ] around Z — all in radians.
     *
     * Supported orders: "YXZ" (used by VRM LookAt: yaw around Y, pitch around X)
     * and "XYZ" (three.js default).
     */
    fun setFromEuler(angleX: Float, angleY: Float, angleZ: Float, order: String = "YXZ"): Quat {
        val c1 = kotlin.math.cos(angleX * 0.5f)
        val c2 = kotlin.math.cos(angleY * 0.5f)
        val c3 = kotlin.math.cos(angleZ * 0.5f)
        val s1 = kotlin.math.sin(angleX * 0.5f)
        val s2 = kotlin.math.sin(angleY * 0.5f)
        val s3 = kotlin.math.sin(angleZ * 0.5f)
        when (order) {
            "XYZ" -> {
                x = s1 * c2 * c3 + c1 * s2 * s3
                y = c1 * s2 * c3 - s1 * c2 * s3
                z = c1 * c2 * s3 + s1 * s2 * c3
                w = c1 * c2 * c3 - s1 * s2 * s3
            }
            else -> { // "YXZ"
                x = s1 * c2 * c3 + c1 * s2 * s3
                y = c1 * s2 * c3 - s1 * c2 * s3
                z = c1 * c2 * s3 - s1 * s2 * c3
                w = c1 * c2 * c3 + s1 * s2 * s3
            }
        }
        return this
    }

    /** copy into a new immutable-style snapshot */
    fun copy(): Quat = Quat(x, y, z, w)

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        fun fromArray(a: FloatArray?, fallback: Quat = IDENTITY): Quat {
            val q = if (a != null && a.size >= 4) Quat(a[0], a[1], a[2], a[3]) else Quat(fallback.x, fallback.y, fallback.z, fallback.w)
            return q.normalize()
        }

        fun fromList(a: List<Float>?, fallback: Quat = IDENTITY): Quat {
            val q = if (a != null && a.size >= 4) Quat(a[0], a[1], a[2], a[3]) else Quat(fallback.x, fallback.y, fallback.z, fallback.w)
            return q.normalize()
        }
    }
}