package dev.vrm.runtime.core.springbone

import dev.vrm.runtime.core.math.Mat4
import dev.vrm.runtime.core.math.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * M4 collision-math tests. Values are ported from three-vrm's
 * `VRMSpringBoneColliderShapeSphere.test.ts` / `Capsule.test.ts` so the Kotlin
 * port is numerically identical to the reference implementation.
 */
class SpringBoneColliderTest {

    private fun assertVec3(expected: Vec3, actual: Vec3, eps: Float = 1e-4f) {
        assertEquals(expected.x, actual.x, eps, "x")
        assertEquals(expected.y, actual.y, eps, "y")
        assertEquals(expected.z, actual.z, eps, "z")
    }

    @Test
    fun `sphere collision calculates signed distance`() {
        val shape = SphereColliderShape(Vec3(), radius = 1f)

        // collider at (1,0,0), object at (2,1,0), objectRadius 1
        val colliderMatrix = Mat4.fromTranslation(1f, 0f, 0f)
        val objectPosition = Vec3(2f, 1f, 0f)
        val dir = Vec3()

        val dist = shape.calculateCollision(colliderMatrix, objectPosition, 1f, dir)

        assertEquals(-0.585786f, dist, 1e-4f) // sqrt(2) - 2
        assertVec3(Vec3(1f, 1f, 0f).normalized(), dir)
    }

    @Test
    fun `sphere collision does not modify inputs`() {
        val shape = SphereColliderShape(Vec3(), radius = 1f)
        val colliderMatrix = Mat4.fromTranslation(1f, 0f, 0f)
        val objectPosition = Vec3(2f, 1f, 0f)
        val beforeMatrix = colliderMatrix.copy()
        val beforeObject = objectPosition.copy()
        val dir = Vec3()

        shape.calculateCollision(colliderMatrix, objectPosition, 1f, dir)

        assertEquals(beforeMatrix.elements.toList(), colliderMatrix.elements.toList())
        assertVec3(beforeObject, objectPosition)
    }

    @Test
    fun `sphere collision no contact returns non-negative`() {
        val shape = SphereColliderShape(Vec3(), radius = 1f)
        // object 10 units away, objectRadius 1 -> no contact
        val dist = shape.calculateCollision(
            Mat4.fromTranslation(0f, 0f, 0f),
            Vec3(10f, 0f, 0f),
            1f,
            Vec3(),
        )
        assertEquals(8f, dist, 1e-4f) // 10 - 1 - 1
    }

    @Test
    fun `capsule collision calculates signed distance`() {
        // vertical capsule from (0,-1,0) to (0,1,0), radius 1, object at (2,1,0)
        val shape = CapsuleColliderShape(Vec3(0f, -1f, 0f), Vec3(0f, 1f, 0f), radius = 1f)
        val colliderMatrix = Mat4()
        val objectPosition = Vec3(2f, 1f, 0f)
        val dir = Vec3()

        val dist = shape.calculateCollision(colliderMatrix, objectPosition, 0f, dir)

        // object is at the tail end: distance from (2,1,0) to (0,1,0) = 2, minus radius 1 -> 1 (no hit)
        assertEquals(1f, dist, 1e-4f)
        // no hit -> the output dir is the raw (unnormalized) head->object delta (2,0,0)
        assertVec3(Vec3(2f, 0f, 0f), dir)
    }

    @Test
    fun `capsule collision hits the shaft`() {
        val shape = CapsuleColliderShape(Vec3(0f, -1f, 0f), Vec3(0f, 1f, 0f), radius = 1f)
        // object near the middle of the shaft, inside the radius
        val dist = shape.calculateCollision(Mat4(), Vec3(0.5f, 0f, 0f), 0f, Vec3())
        // nearest shaft point (0,0,0): distance 0.5 - radius 1 = -0.5 (hit)
        assertEquals(-0.5f, dist, 1e-4f)
    }

    @Test
    fun `sphere collider matrix applies offset`() {
        val collider = SpringBoneCollider(
            0,
            SphereColliderShape(Vec3(0f, 0f, 1f), radius = 1f),
        )
        // store stub: node 0 world = translation(10, 0, 0)
        val store = object : SpringBoneStore {
            override fun hasNode(nodeIndex: Int) = nodeIndex == 0
            override fun getLocalTranslation(nodeIndex: Int) = Vec3()
            override fun getLocalRotation(nodeIndex: Int) = dev.vrm.runtime.core.math.Quat()
            override fun getLocalScale(nodeIndex: Int) = Vec3(1f, 1f, 1f)
            override fun setLocalRotation(nodeIndex: Int, q: dev.vrm.runtime.core.math.Quat) {}
            override fun getWorldMatrix(nodeIndex: Int) = Mat4.fromTranslation(10f, 0f, 0f)
            override fun parentNodeIndex(nodeIndex: Int) = -1
            override fun localToWorld(nodeIndex: Int, local: Vec3) =
                Mat4.fromTranslation(10f, 0f, 0f).transformPoint(local)
        }

        val m = collider.colliderMatrix(store)
        assertEquals(10f, m.elements[12], 1e-4f)
        assertEquals(0f, m.elements[13], 1e-4f)
        assertEquals(1f, m.elements[14], 1e-4f, "offset applied to z")
    }
}
