package dev.vrm.runtime.core.math

import kotlin.math.sqrt

/**
 * Minimal 4x4 matrix, three.js Matrix4 semantics (column-major, elements array).
 * Zero-dependency. Implemented to keep the three-vrm port numerically faithful.
 */
class Mat4(
    /** Column-major 16 elements, same layout as three.js `elements`. */
    val elements: FloatArray = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f },
) {

    /** Copy the contents of [other] into this matrix (mutating). */
    fun setFrom(other: Mat4): Mat4 {
        System.arraycopy(other.elements, 0, elements, 0, 16)
        return this
    }

    companion object {
        val IDENTITY = Mat4()

        fun fromTranslation(x: Float, y: Float, z: Float): Mat4 {
            val m = Mat4()
            m.elements[12] = x
            m.elements[13] = y
            m.elements[14] = z
            return m
        }

        /**
         * Compose from position + quaternion + scale, matching three.js Matrix4.compose.
         */
        fun fromPositionRotationScale(position: Vec3, quaternion: Quat, scale: Vec3): Mat4 {
            val te = FloatArray(16)
            val x = quaternion.x
            val y = quaternion.y
            val z = quaternion.z
            val w = quaternion.w
            val x2 = x + x
            val y2 = y + y
            val z2 = z + z
            val xx = x * x2
            val xy = x * y2
            val xz = x * z2
            val yy = y * y2
            val yz = y * z2
            val zz = z * z2
            val wx = w * x2
            val wy = w * y2
            val wz = w * z2

            te[0] = (1 - (yy + zz)) * scale.x
            te[1] = (xy + wz) * scale.x
            te[2] = (xz - wy) * scale.x
            te[3] = 0f

            te[4] = (xy - wz) * scale.y
            te[5] = (1 - (xx + zz)) * scale.y
            te[6] = (yz + wx) * scale.y
            te[7] = 0f

            te[8] = (xz + wy) * scale.z
            te[9] = (yz - wx) * scale.z
            te[10] = (1 - (xx + yy)) * scale.z
            te[11] = 0f

            te[12] = position.x
            te[13] = position.y
            te[14] = position.z
            te[15] = 1f

            return Mat4(te)
        }

        /**
         * Set a matrix from a glTF node's matrix (column-major 16 floats) or TRS.
         */
        fun fromGltfNode(
            matrix: FloatArray?,
            translation: FloatArray?,
            rotation: FloatArray?,
            scale: FloatArray?,
        ): Mat4 {
            if (matrix != null && matrix.size >= 16) {
                return Mat4(matrix.copyOf(16))
            }
            val pos = if (translation != null && translation.size >= 3) Vec3(translation[0], translation[1], translation[2]) else Vec3()
            val rot = if (rotation != null && rotation.size >= 4) Quat(rotation[0], rotation[1], rotation[2], rotation[3]).normalized() else Quat()
            val scl = if (scale != null && scale.size >= 3) Vec3(scale[0], scale[1], scale[2]) else Vec3(1f, 1f, 1f)
            return fromPositionRotationScale(pos, rot, scl)
        }
    }

    operator fun get(i: Int) = elements[i]

    /** Multiply this (a) by b: result = a * b. Mutates and returns this. */
    fun multiply(b: Mat4): Mat4 {
        val a = elements
        val be = b.elements
        val ae = FloatArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                ae[j * 4 + i] =
                    a[0 * 4 + i] * be[j * 4 + 0] +
                        a[1 * 4 + i] * be[j * 4 + 1] +
                        a[2 * 4 + i] * be[j * 4 + 2] +
                        a[3 * 4 + i] * be[j * 4 + 3]
            }
        }
        System.arraycopy(ae, 0, elements, 0, 16)
        return this
    }

    /** Returns a new matrix = this * b (three.js multiplyMatrices(a, b)). */
    fun multipliedBy(b: Mat4): Mat4 = Mat4(elements.copyOf()).multiply(b)

    /** Invert in place. Returns this. Throws if singular. */
    fun invert(): Mat4 {
        val te = elements
        val n11 = te[0]; val n21 = te[1]; val n31 = te[2]; val n41 = te[3]
        val n12 = te[4]; val n22 = te[5]; val n32 = te[6]; val n42 = te[7]
        val n13 = te[8]; val n23 = te[9]; val n33 = te[10]; val n43 = te[11]
        val n14 = te[12]; val n24 = te[13]; val n34 = te[14]; val n44 = te[15]

        val t11 = n23 * n34 * n42 - n24 * n33 * n42 + n24 * n32 * n43 - n22 * n34 * n43 - n23 * n32 * n44 + n22 * n33 * n44
        val t12 = n14 * n33 * n42 - n13 * n34 * n42 - n14 * n32 * n43 + n12 * n34 * n43 + n13 * n32 * n44 - n12 * n33 * n44
        val t13 = n13 * n24 * n42 - n14 * n23 * n42 + n14 * n22 * n43 - n12 * n24 * n43 - n13 * n22 * n44 + n12 * n23 * n44
        val t14 = n14 * n23 * n32 - n13 * n24 * n32 - n14 * n22 * n33 + n12 * n24 * n33 + n13 * n22 * n34 - n12 * n23 * n34

        val det = n11 * t11 + n21 * t12 + n31 * t13 + n41 * t14
        if (det == 0f) {
            throw IllegalStateException("Cannot invert singular matrix")
        }
        val detInv = 1f / det

        te[0] = t11 * detInv
        te[1] = (n24 * n33 * n41 - n23 * n34 * n41 - n24 * n31 * n43 + n21 * n34 * n43 + n23 * n31 * n44 - n21 * n33 * n44) * detInv
        te[2] = (n22 * n34 * n41 - n24 * n32 * n41 + n24 * n31 * n42 - n21 * n34 * n42 - n22 * n31 * n44 + n21 * n32 * n44) * detInv
        te[3] = (n23 * n32 * n41 - n22 * n33 * n41 - n23 * n31 * n42 + n21 * n33 * n42 + n22 * n31 * n43 - n21 * n32 * n43) * detInv

        te[4] = t12 * detInv
        te[5] = (n13 * n34 * n41 - n14 * n33 * n41 + n14 * n31 * n43 - n11 * n34 * n43 - n13 * n31 * n44 + n11 * n33 * n44) * detInv
        te[6] = (n14 * n32 * n41 - n12 * n34 * n41 - n14 * n31 * n42 + n11 * n34 * n42 + n12 * n31 * n44 - n11 * n32 * n44) * detInv
        te[7] = (n12 * n33 * n41 - n13 * n32 * n41 + n13 * n31 * n42 - n11 * n33 * n42 - n12 * n31 * n43 + n11 * n32 * n43) * detInv

        te[8] = t13 * detInv
        te[9] = (n14 * n23 * n41 - n13 * n24 * n41 - n14 * n21 * n43 + n11 * n24 * n43 + n13 * n21 * n44 - n11 * n23 * n44) * detInv
        te[10] = (n12 * n24 * n41 - n14 * n22 * n41 + n14 * n21 * n42 - n11 * n24 * n42 - n12 * n21 * n44 + n11 * n22 * n44) * detInv
        te[11] = (n13 * n22 * n41 - n12 * n23 * n41 - n13 * n21 * n42 + n11 * n23 * n42 + n12 * n21 * n43 - n11 * n22 * n43) * detInv

        te[12] = t14 * detInv
        te[13] = (n13 * n24 * n31 - n14 * n23 * n31 + n14 * n21 * n33 - n11 * n24 * n33 - n13 * n21 * n34 + n11 * n23 * n34) * detInv
        te[14] = (n14 * n22 * n31 - n12 * n24 * n31 - n14 * n21 * n32 + n11 * n24 * n32 + n12 * n21 * n34 - n11 * n22 * n34) * detInv
        te[15] = (n12 * n23 * n31 - n13 * n22 * n31 + n13 * n21 * n32 - n11 * n23 * n32 - n12 * n21 * n33 + n11 * n22 * n33) * detInv

        return this
    }

    fun inverted(): Mat4 = Mat4(elements.copyOf()).invert()

    /** Transform a point (w=1) by this matrix. */
    fun transformPoint(v: Vec3): Vec3 {
        val te = elements
        val x = v.x
        val y = v.y
        val z = v.z
        val w = te[3] * x + te[7] * y + te[11] * z + te[15]
        val invW = 1f / w
        return Vec3(
            (te[0] * x + te[4] * y + te[8] * z + te[12]) * invW,
            (te[1] * x + te[5] * y + te[9] * z + te[13]) * invW,
            (te[2] * x + te[6] * y + te[10] * z + te[14]) * invW,
        )
    }

    /** Transform a direction (w=0) by this matrix. */
    fun transformDirection(v: Vec3): Vec3 {
        val te = elements
        val x = v.x
        val y = v.y
        val z = v.z
        return Vec3(
            te[0] * x + te[4] * y + te[8] * z,
            te[1] * x + te[5] * y + te[9] * z,
            te[2] * x + te[6] * y + te[10] * z,
        )
    }

    fun determinant(): Float {
        val te = elements
        val n11 = te[0]; val n21 = te[1]; val n31 = te[2]; val n41 = te[3]
        val n12 = te[4]; val n22 = te[5]; val n32 = te[6]; val n42 = te[7]
        val n13 = te[8]; val n23 = te[9]; val n33 = te[10]; val n43 = te[11]
        val n14 = te[12]; val n24 = te[13]; val n34 = te[14]; val n44 = te[15]
        return (
            n41 * (n14 * n23 * n32 - n13 * n24 * n32 - n14 * n22 * n33 + n12 * n24 * n33 + n13 * n22 * n34 - n12 * n23 * n34) +
                n42 * (n11 * n23 * n34 - n11 * n24 * n33 + n14 * n21 * n33 - n13 * n21 * n34 + n13 * n24 * n31 - n14 * n23 * n31) +
                n43 * (n11 * n24 * n32 - n11 * n22 * n34 - n14 * n21 * n32 + n12 * n21 * n34 + n14 * n22 * n31 - n12 * n24 * n31) +
                n44 * (-n13 * n22 * n31 - n11 * n23 * n32 + n11 * n22 * n33 + n13 * n21 * n32 - n12 * n21 * n33 + n12 * n23 * n31)
            )
    }

    /**
     * Decompose into position / quaternion / scale, matching three.js Matrix4.decompose.
     */
    fun decompose(position: Vec3, quaternion: Quat, scale: Vec3) {
        val te = elements

        var sx = Vec3(te[0], te[1], te[2]).length()
        val sy = Vec3(te[4], te[5], te[6]).length()
        val sz = Vec3(te[8], te[9], te[10]).length()

        // if determinant is negative, we need to invert one scale
        val det = determinant()
        if (det < 0) sx = -sx

        position.x = te[12]
        position.y = te[13]
        position.z = te[14]

        // scale the rotation part
        val invSX = 1 / sx
        val invSY = 1 / sy
        val invSZ = 1 / sz

        val m = FloatArray(16)
        m[0] = te[0] * invSX
        m[1] = te[1] * invSX
        m[2] = te[2] * invSX
        m[4] = te[4] * invSY
        m[5] = te[5] * invSY
        m[6] = te[6] * invSY
        m[8] = te[8] * invSZ
        m[9] = te[9] * invSZ
        m[10] = te[10] * invSZ
        m[3] = 0f; m[7] = 0f; m[11] = 0f
        m[12] = 0f; m[13] = 0f; m[14] = 0f; m[15] = 1f

        setFromRotationMatrix(m, quaternion)

        scale.x = sx
        scale.y = sy
        scale.z = sz
    }

    private fun setFromRotationMatrix(m: FloatArray, quat: Quat) {
        val m11 = m[0]; val m12 = m[4]; val m13 = m[8]
        val m21 = m[1]; val m22 = m[5]; val m23 = m[9]
        val m31 = m[2]; val m32 = m[6]; val m33 = m[10]
        val trace = m11 + m22 + m33

        var x: Float
        var y: Float
        var z: Float
        var w: Float
        var s: Float

        if (trace > 0f) {
            s = 0.5f / sqrt(trace + 1.0f)
            w = 0.25f / s
            x = (m32 - m23) * s
            y = (m13 - m31) * s
            z = (m21 - m12) * s
        } else if (m11 > m22 && m11 > m33) {
            s = 2.0f * sqrt(1.0f + m11 - m22 - m33)
            w = (m32 - m23) / s
            x = 0.25f * s
            y = (m12 + m21) / s
            z = (m13 + m31) / s
        } else if (m22 > m33) {
            s = 2.0f * sqrt(1.0f + m22 - m11 - m33)
            w = (m13 - m31) / s
            x = (m12 + m21) / s
            y = 0.25f * s
            z = (m23 + m32) / s
        } else {
            s = 2.0f * sqrt(1.0f + m33 - m11 - m22)
            w = (m21 - m12) / s
            x = (m13 + m31) / s
            y = (m23 + m32) / s
            z = 0.25f * s
        }

        quat.x = x
        quat.y = y
        quat.z = z
        quat.w = w
    }

    fun copy(): Mat4 = Mat4(elements.copyOf())
}
