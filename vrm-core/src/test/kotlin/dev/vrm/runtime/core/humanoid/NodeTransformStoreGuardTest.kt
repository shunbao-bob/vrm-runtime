package dev.vrm.runtime.core.humanoid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.vrm.runtime.core.math.Vec3

/**
 * Guards for the node-transform store: a wrong node index must produce a
 * clear, actionable exception instead of an opaque IndexOutOfBounds crash or
 * silently-wrong data when the host wires an index from another model.
 */
class NodeTransformStoreGuardTest {

    private fun store(nodeCount: Int = 3): GltfNodeTransformStore =
        // nodes with no explicit transform -> all identity
        GltfNodeTransformStore.fromGltfNodes(
            List(nodeCount) { dev.vrm.runtime.core.gltf.Node(name = "n$it") }
        )

    @Test
    fun `hasNode false for out of range`() {
        val s = store(3)
        assertTrue(s.hasNode(0))
        assertTrue(s.hasNode(2))
        assertEquals(false, s.hasNode(3))
        assertEquals(false, s.hasNode(-1))
    }

    @Test
    fun `negative index is rejected by getters`() {
        val s = store(3)
        val e = assertThrows(IllegalArgumentException::class.java) { s.getLocalTranslation(-1) }
        assertTrue(e.message!!.contains("-1"), "message should name the bad index: ${e.message}")
    }

    @Test
    fun `out of range index is rejected by getters`() {
        val s = store(3)
        val e = assertThrows(IllegalArgumentException::class.java) { s.getLocalRotation(10) }
        assertTrue(e.message!!.contains("10"), "message should name the bad index: ${e.message}")
        assertTrue(e.message!!.contains("out of range"), "should be a clear range error: ${e.message}")
    }

    @Test
    fun `out of range world matrix is rejected`() {
        val s = store(3)
        assertThrows(IllegalArgumentException::class.java) { s.getWorldMatrix(99) }
    }

    @Test
    fun `setters reject out of range index too`() {
        val s = store(3)
        assertThrows(IllegalArgumentException::class.java) { s.setLocalTranslation(5, Vec3(0f, 0f, 0f)) }
        assertThrows(IllegalArgumentException::class.java) {
            s.setLocalRotation(5, dev.vrm.runtime.core.math.Quat())
        }
        assertThrows(IllegalArgumentException::class.java) {
            s.setLocalScale(5, Vec3(1f, 1f, 1f))
        }
    }

    @Test
    fun `in range writes and reads still work`() {
        val s = store(2)
        s.setLocalTranslation(1, Vec3(1f, 2f, 3f))
        val v = s.getLocalTranslation(1)
        assertEquals(1f, v.x, 1e-5f)
        assertEquals(2f, v.y, 1e-5f)
        assertEquals(3f, v.z, 1e-5f)
    }
}