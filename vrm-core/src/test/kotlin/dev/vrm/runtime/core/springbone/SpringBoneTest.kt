package dev.vrm.runtime.core.springbone

import dev.vrm.runtime.core.math.Mat4
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M4: spring-bone loading from the real Seed-san VRM, collider math, and
 * Verlet integration on a tiny synthetic chain.
 */
class SpringBoneTest {

    private fun seedBytes(): ByteArray {
        val url = javaClass.classLoader.getResource("fixtures/Seed-san.vrm")
        assertNotNull(url, "fixture Seed-san.vrm not found")
        return File(url!!.toURI()).readBytes()
    }

    @Test
    fun `loads seed-san spring bones into a manager`() {
        val vrm = dev.vrm.runtime.core.vrm.VrmLoader.load(seedBytes())
        val ext = vrm.springBone ?: throw AssertionError("seed-san must carry VRMC_springBone")

        val manager = SpringBoneLoader(vrm.gltf, ext).load()

        // 8 colliders, 2 groups, 9 springs
        assertEquals(8, manager.colliders.size)
        assertEquals(2, manager.colliderGroups.size)

        val joints = manager.joints
        // springs have joint counts [7,2,2,2,2,2,2,2,7] -> pairs = 6+1*7+6 = 19
        assertEquals(19, joints.size, "seed-san should produce 19 spring-bone joints")
    }

    @Test
    fun `joint settings parsed from seed-san`() {
        val vrm = dev.vrm.runtime.core.vrm.VrmLoader.load(seedBytes())
        val ext = vrm.springBone ?: throw AssertionError("seed-san must carry VRMC_springBone")
        val manager = SpringBoneLoader(vrm.gltf, ext).load()

        // TailHair first joint has stiffness 4 -> a high-stiffness joint exists
        val maxStiffness = manager.joints.maxOf { it.settings.stiffness }
        assertEquals(4f, maxStiffness, 1e-5f)

        // gravityDir defaults to (0,-1,0)
        val first = manager.joints.first()
        assertEquals(0f, first.settings.gravityDir.x, 1e-5f)
        assertEquals(-1f, first.settings.gravityDir.y, 1e-5f)
        assertEquals(0f, first.settings.gravityDir.z, 1e-5f)
    }

    @Test
    fun `verlet joint with gravity droops the tail`() {
        // chain: node0 (root, world origin) -> node1 at (0,1,0) -> node2 at (0,1,1)
        // joint: bone=node1, child=node2; gravity pulls the tail down (-y).
        val store = TestSpringBoneStore.of(
            listOf(
                Triple(-1, Vec3(0f, 0f, 0f), Quat()),
                Triple(0, Vec3(0f, 1f, 0f), Quat()),
                Triple(1, Vec3(0f, 0f, 1f), Quat()),
            )
        )

        val settings = SpringBoneJointSettings(
            hitRadius = 0f,
            stiffness = 0.1f,
            gravityPower = 2.0f,
            gravityDir = Vec3(0f, -1f, 0f),
            dragForce = 0.0f,
        )
        val joint = SpringBoneJoint(boneNode = 1, childNode = 2, settings = settings, store = store)
        joint.setInitState()

        // tail starts at (0,1,1)
        val before = store.localToWorld(2, Vec3())
        assertEquals(1f, before.y, 1e-5f)

        // simulate many frames
        val manager = SpringBoneManager(store)
        manager.addJoint(joint)
        manager.update(1f / 60f)
        // initial update with delta: gravity applied
        manager.update(1f / 60f)

        val after = store.localToWorld(2, Vec3())
        assertTrue(after.y < before.y - 0.01f, "tail should droop under gravity, got y=${after.y}")
    }

    @Test
    fun `manager update preserves bone length`() {
        // node0 root at origin, node1 at (0,1,0), node2 at (0,1,1): bone length 1
        val store = TestSpringBoneStore.of(
            listOf(
                Triple(-1, Vec3(0f, 0f, 0f), Quat()),
                Triple(0, Vec3(0f, 1f, 0f), Quat()),
                Triple(1, Vec3(0f, 0f, 1f), Quat()),
            )
        )
        val settings = SpringBoneJointSettings(
            hitRadius = 0f,
            stiffness = 0.2f,
            gravityPower = 1.0f,
            gravityDir = Vec3(0f, -1f, 0f),
            dragForce = 0.2f,
        )
        val joint = SpringBoneJoint(boneNode = 1, childNode = 2, settings = settings, store = store)
        joint.setInitState()

        val manager = SpringBoneManager(store)
        manager.addJoint(joint)
        repeat(30) { manager.update(1f / 60f) }

        // bone length (distance node1->node2 world) must stay ~1
        val bonePos = store.localToWorld(1, Vec3())
        val tailPos = store.localToWorld(2, Vec3())
        val length = bonePos.distanceTo(tailPos)
        assertEquals(1f, length, 1e-3f, "bone length must be preserved")
    }
}

/**
 * A configurable in-memory [SpringBoneStore] for simulating tiny chains without
 * building a full GLB. Nodes described as (parentIndex, localTranslation,
 * localRotation); world matrices recomputed lazily by walking parents.
 */
class TestSpringBoneStore private constructor(
    private val parentOf: IntArray,
    private val translations: MutableList<Vec3>,
    private val rotations: MutableList<Quat>,
) : SpringBoneStore {

    companion object {
        fun of(nodes: List<Triple<Int, Vec3, Quat>>): TestSpringBoneStore {
            val parent = IntArray(nodes.size) { nodes[it].first }
            val t = nodes.map { it.second.copy() }.toMutableList()
            val r = nodes.map { it.third.copy() }.toMutableList()
            return TestSpringBoneStore(parent, t, r)
        }
    }

    private val cache = HashMap<Int, Mat4>()

    override fun hasNode(nodeIndex: Int) = nodeIndex in 0 until parentOf.size
    override fun getLocalTranslation(nodeIndex: Int) = translations[nodeIndex].copy()
    override fun getLocalRotation(nodeIndex: Int) = rotations[nodeIndex].copy()
    override fun getLocalScale(nodeIndex: Int) = Vec3(1f, 1f, 1f)
    override fun setLocalRotation(nodeIndex: Int, q: Quat) {
        rotations[nodeIndex].copy(q)
        cache.clear()
    }
    override fun parentNodeIndex(nodeIndex: Int) = parentOf[nodeIndex]
    override fun getWorldMatrix(nodeIndex: Int): Mat4 = cache.getOrPut(nodeIndex) { computeWorld(nodeIndex) }
    override fun localToWorld(nodeIndex: Int, local: Vec3) = getWorldMatrix(nodeIndex).transformPoint(local)

    private fun computeWorld(nodeIndex: Int): Mat4 {
        var m = Mat4()
        val path = mutableListOf<Int>()
        var cur = nodeIndex
        while (cur != -1) {
            path.add(cur)
            cur = parentOf[cur]
        }
        path.reverse()
        for (idx in path) {
            m = m.multipliedBy(
                Mat4.fromPositionRotationScale(getLocalTranslation(idx), getLocalRotation(idx), Vec3(1f, 1f, 1f))
            )
        }
        return m
    }
}
