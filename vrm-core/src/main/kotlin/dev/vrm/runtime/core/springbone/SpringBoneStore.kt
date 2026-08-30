package dev.vrm.runtime.core.springbone

import dev.vrm.runtime.core.gltf.Gltf
import dev.vrm.runtime.core.humanoid.GltfNodeTransformStore
import dev.vrm.runtime.core.math.Mat4
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3

/**
 * Abstraction over the render-engine's node graph for spring-bone physics.
 *
 * Engine-agnostic: an in-memory implementation backed by parsed glTF nodes is
 * provided ([GltfSpringBoneStore]) for tests/headless use; a Filament-backed
 * implementation will drive TransformManager in the demo.
 *
 * A `nodeIndex` is the glTF node index referenced by the VRMC_springBone
 * extension. World matrices must be fresh after a [setLocalRotation] call, so
 * joints can read each other's updated transforms in dependency order.
 */
interface SpringBoneStore {
    fun hasNode(nodeIndex: Int): Boolean

    /** Local translation of the node (a fresh copy). */
    fun getLocalTranslation(nodeIndex: Int): Vec3

    /** Local rotation (quaternion) of the node (a fresh copy). */
    fun getLocalRotation(nodeIndex: Int): Quat

    /** Local scale of the node (a fresh copy). */
    fun getLocalScale(nodeIndex: Int): Vec3

    /** Write the node's local rotation, invalidating its world transform. */
    fun setLocalRotation(nodeIndex: Int, q: Quat)

    /** World matrix of the node, recomputed from its parent chain. */
    fun getWorldMatrix(nodeIndex: Int): Mat4

    /** Parent node index, or -1 if the node is a root. */
    fun parentNodeIndex(nodeIndex: Int): Int

    /** World-space position of a local point of the node (point semantics). */
    fun localToWorld(nodeIndex: Int, local: Vec3): Vec3
}

/**
 * An in-memory [SpringBoneStore] built from parsed glTF nodes, sharing the same
 * world-matrix math as [GltfNodeTransformStore].
 */
class GltfSpringBoneStore(
    private val nodes: List<dev.vrm.runtime.core.gltf.Node>?,
) : SpringBoneStore {

    private val delegate = GltfNodeTransformStore.fromGltfNodes(nodes)
    private val childMap = HashMap<Int, MutableList<Int>>()

    init {
        nodes?.forEachIndexed { index, node ->
            node.children?.forEach { child ->
                childMap.getOrPut(index) { mutableListOf() }.add(child)
            }
        }
    }

    override fun hasNode(nodeIndex: Int): Boolean = delegate.hasNode(nodeIndex)
    override fun getLocalTranslation(nodeIndex: Int): Vec3 = delegate.getLocalTranslation(nodeIndex).copy()
    override fun getLocalRotation(nodeIndex: Int): Quat = delegate.getLocalRotation(nodeIndex).copy()
    override fun getLocalScale(nodeIndex: Int): Vec3 = delegate.getLocalScale(nodeIndex).copy()
    override fun setLocalRotation(nodeIndex: Int, q: Quat) = delegate.setLocalRotation(nodeIndex, q)
    override fun getWorldMatrix(nodeIndex: Int): Mat4 = delegate.getWorldMatrix(nodeIndex)
    override fun parentNodeIndex(nodeIndex: Int): Int = delegate.parentNodeIndex(nodeIndex)

    override fun localToWorld(nodeIndex: Int, local: Vec3): Vec3 =
        delegate.getWorldMatrix(nodeIndex).transformPoint(local)

    /** Direct children of a node (used by the manager's ancestor traversal). */
    fun childrenOf(nodeIndex: Int): List<Int> = childMap[nodeIndex] ?: emptyList()

    companion object {
        fun fromGltf(gltf: Gltf): GltfSpringBoneStore = GltfSpringBoneStore(gltf.nodes)
    }
}
