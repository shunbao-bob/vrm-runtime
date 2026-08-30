package dev.vrm.runtime.core.math

import kotlin.math.sqrt

/**
 * Minimal 3D vector, three.js Vector3 semantics, mutable. Zero-dependency.
 */
class Vec3(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
) {
    fun set(nx: Float, ny: Float, nz: Float): Vec3 {
        x = nx; y = ny; z = nz
        return this
    }

    fun copy(o: Vec3): Vec3 = set(o.x, o.y, o.z)

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)

    fun add(o: Vec3): Vec3 {
        x += o.x; y += o.y; z += o.z
        return this
    }

    fun addScaledVector(o: Vec3, s: Float): Vec3 {
        x += o.x * s; y += o.y * s; z += o.z * s
        return this
    }

    fun sub(o: Vec3): Vec3 {
        x -= o.x; y -= o.y; z -= o.z
        return this
    }

    fun negate(): Vec3 {
        x = -x; y = -y; z = -z
        return this
    }

    fun scale(s: Float): Vec3 {
        x *= s; y *= s; z *= s
        return this
    }

    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3): Vec3 = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    fun lengthSq() = x * x + y * y + z * z
    fun length() = sqrt(lengthSq())

    fun normalize(): Vec3 {
        val len = length()
        if (len > 0f) scale(1f / len)
        return this
    }

    fun normalized(): Vec3 {
        val len = length()
        return if (len > 0f) Vec3(x / len, y / len, z / len) else Vec3()
    }

    fun distanceTo(o: Vec3) = (this - o).length()
    fun distanceToSquared(o: Vec3) = (this - o).lengthSq()

    fun lerp(o: Vec3, t: Float): Vec3 {
        x += (o.x - x) * t
        y += (o.y - y) * t
        z += (o.z - z) * t
        return this
    }

    fun lerped(o: Vec3, t: Float): Vec3 = Vec3(
        x + (o.x - x) * t,
        y + (o.y - y) * t,
        z + (o.z - z) * t,
    )

    /** Apply a matrix (three.js applyMatrix4, point semantics). */
    fun applyMatrix4(m: Mat4): Vec3 {
        val e = m.elements
        val px = x; val py = y; val pz = z
        val w = e[3] * px + e[7] * py + e[11] * pz + e[15]
        val invW = 1f / w
        x = (e[0] * px + e[4] * py + e[8] * pz + e[12]) * invW
        y = (e[1] * px + e[5] * py + e[9] * pz + e[13]) * invW
        z = (e[2] * px + e[6] * py + e[10] * pz + e[14]) * invW
        return this
    }

    fun toArray(): FloatArray = floatArrayOf(x, y, z)

    /**
     * Transform a direction (w=0, translation ignored) by [m] and normalize
     * (three.js Vector3.transformDirection). Used by spring-bone to bring a
     * local bone axis into world space.
     */
    fun transformDirection(m: Mat4): Vec3 {
        val e = m.elements
        val nx = e[0] * x + e[4] * y + e[8] * z
        val ny = e[1] * x + e[5] * y + e[9] * z
        val nz = e[2] * x + e[6] * y + e[10] * z
        x = nx; y = ny; z = nz
        return normalize()
    }

    fun copy(): Vec3 = Vec3(x, y, z)

    override fun equals(other: Any?): Boolean =
        other is Vec3 && other.x == x && other.y == y && other.z == z

    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + z.hashCode()
        return result
    }

    override fun toString(): String = "Vec3($x, $y, $z)"

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UNIT_X = Vec3(1f, 0f, 0f)
        val UNIT_Y = Vec3(0f, 1f, 0f)
        val UNIT_Z = Vec3(0f, 0f, 1f)

        fun fromArray(a: FloatArray?, fallback: Vec3 = ZERO): Vec3 =
            if (a != null && a.size >= 3) Vec3(a[0], a[1], a[2]) else Vec3(fallback.x, fallback.y, fallback.z)

        fun fromList(a: List<Float>?, fallback: Vec3 = ZERO): Vec3 =
            if (a != null && a.size >= 3) Vec3(a[0], a[1], a[2]) else Vec3(fallback.x, fallback.y, fallback.z)
    }
}