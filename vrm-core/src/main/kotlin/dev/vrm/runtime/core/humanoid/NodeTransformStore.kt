package dev.vrm.runtime.core.humanoid

import dev.vrm.runtime.core.math.Mat4
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3

/**
 * Abstraction over the render-engine's node graph, so that vrm-core stays
 * engine-agnostic. Implementations:
 *  - A pure in-memory store backed by parsed glTF nodes (tests / headless).
 *  - A Filament-backed store that reads/writes TransformManager.
 *
 * A `nodeIndex` is the glTF node index as referenced by the VRM extension.
 */
interface NodeTransformStore {
    /** Local translation of the node. */
    fun getLocalTranslation(nodeIndex: Int): Vec3

    /** Local rotation (quaternion) of the node. */
    fun getLocalRotation(nodeIndex: Int): Quat

    /** Local scale of the node. */
    fun getLocalScale(nodeIndex: Int): Vec3

    /** World matrix of the node (parent transforms already applied). */
    fun getWorldMatrix(nodeIndex: Int): Mat4

    /** Whether the given node index exists in the model. */
    fun hasNode(nodeIndex: Int): Boolean

    /** Parent node index, or -1 if the node is a root. */
    fun parentNodeIndex(nodeIndex: Int): Int
}

/**
 * A read-write store. Only needed when the pose is written back to the model.
 */
interface MutableNodeTransformStore : NodeTransformStore {
    fun setLocalTranslation(nodeIndex: Int, v: Vec3)
    fun setLocalRotation(nodeIndex: Int, q: Quat)
    fun setLocalScale(nodeIndex: Int, s: Vec3)
}

/**
 * An in-memory store built from parsed glTF nodes. Used for headless tests and
 * for pre-computing normalized rigs without a render engine.
 *
 * Node transforms are local TRS; world matrices are computed by BFS over the
 * node graph. Non-mutating reads; set* updates both local TRS and recomputes
 * world matrices lazily.
 */
class GltfNodeTransformStore(
    private val nodeCount: Int,
    private val childrenOf: List<IntArray>,
    private val localTranslations: MutableList<Vec3>,
    private val localRotations: MutableList<Quat>,
    private val localScales: MutableList<Vec3>,
) : MutableNodeTransformStore {

    private val worldCache = HashMap<Int, Mat4>()

    companion object {
        /**
         * Build a store from parsed glTF nodes. `nodes` is the glTF nodes array;
         * each element holds optional matrix/translation/rotation/scale + children.
         */
        fun fromGltfNodes(
            nodes: List<dev.vrm.runtime.core.gltf.Node>?,
        ): GltfNodeTransformStore {
            val count = nodes?.size ?: 0
            val children = ArrayList<IntArray>(count)
            val translations = ArrayList<Vec3>(count)
            val rotations = ArrayList<Quat>(count)
            val scales = ArrayList<Vec3>(count)

            repeat(count) { i ->
                val n = nodes!![i]
                // glTF: if matrix is present it wins over TRS
                val m = n.matrixOrNull
                if (m != null) {
                    val mat = Mat4.fromGltfNode(m, null, null, null)
                    val p = Vec3()
                    val q = Quat()
                    val s = Vec3()
                    mat.decompose(p, q, s)
                    translations.add(p)
                    rotations.add(q)
                    scales.add(s)
                } else {
                    translations.add(Vec3.fromArray(n.translationOrNull))
                    rotations.add(Quat.fromArray(n.rotationOrNull))
                    scales.add(Vec3.fromArray(n.scaleOrNull).let { v ->
                        if (v == Vec3(0f, 0f, 0f)) Vec3(1f, 1f, 1f) else v
                    })
                }
                children.add(n.children?.toIntArray() ?: IntArray(0))
            }

            return GltfNodeTransformStore(count, children, translations, rotations, scales)
        }
    }

    override fun hasNode(nodeIndex: Int): Boolean = nodeIndex in 0 until nodeCount

    /** Validate a node index and fail fast with a clear message instead of an
     *  opaque array-out-of-bounds crash when the host wires a wrong index. */
    private fun requireNodeIndex(nodeIndex: Int) {
        require(nodeIndex in 0 until nodeCount) {
            "GltfNodeTransformStore: node index $nodeIndex out of range (0..${nodeCount - 1}); " +
                "check that the bone/node index comes from this model's glTF nodes"
        }
    }

    override fun parentNodeIndex(nodeIndex: Int): Int {
        for (parent in 0 until nodeCount) {
            if (childrenOf[parent].contains(nodeIndex)) return parent
        }
        return -1
    }

    override fun getLocalTranslation(nodeIndex: Int): Vec3 {
        requireNodeIndex(nodeIndex)
        return localTranslations[nodeIndex]
    }

    override fun getLocalRotation(nodeIndex: Int): Quat {
        requireNodeIndex(nodeIndex)
        return localRotations[nodeIndex]
    }

    override fun getLocalScale(nodeIndex: Int): Vec3 {
        requireNodeIndex(nodeIndex)
        return localScales[nodeIndex]
    }

    override fun getWorldMatrix(nodeIndex: Int): Mat4 {
        requireNodeIndex(nodeIndex)
        worldCache[nodeIndex]?.let { return it }
        val m = computeWorldMatrix(nodeIndex)
        worldCache[nodeIndex] = m
        return m
    }

    override fun setLocalTranslation(nodeIndex: Int, v: Vec3) {
        requireNodeIndex(nodeIndex)
        localTranslations[nodeIndex] = v.copy()
        invalidateWorld(nodeIndex)
    }

    override fun setLocalRotation(nodeIndex: Int, q: Quat) {
        requireNodeIndex(nodeIndex)
        localRotations[nodeIndex] = q.copy()
        invalidateWorld(nodeIndex)
    }

    override fun setLocalScale(nodeIndex: Int, s: Vec3) {
        requireNodeIndex(nodeIndex)
        localScales[nodeIndex] = s.copy()
        invalidateWorld(nodeIndex)
    }

    private fun invalidateWorld(nodeIndex: Int) {
        worldCache.remove(nodeIndex)
        // simple approach: drop the whole cache; world matrices are cheap to recompute
        worldCache.clear()
    }

    private fun computeWorldMatrix(nodeIndex: Int): Mat4 {
        // build path from root down to node
        val path = mutableListOf<Int>()
        var current = nodeIndex
        while (current != -1) {
            path.add(current)
            current = parentNodeIndex(current)
        }
        path.reverse()
        var result = Mat4.fromPositionRotationScale(Vec3(), Quat(), Vec3(1f, 1f, 1f))
        for (idx in path) {
            result = result.multipliedBy(
                Mat4.fromPositionRotationScale(localTranslations[idx], localRotations[idx], localScales[idx])
            )
        }
        return result
    }
}
