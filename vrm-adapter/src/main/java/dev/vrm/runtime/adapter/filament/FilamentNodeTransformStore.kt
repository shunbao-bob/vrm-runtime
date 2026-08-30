package dev.vrm.runtime.adapter.filament

import com.google.android.filament.Engine
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import dev.vrm.runtime.core.gltf.Skin
import dev.vrm.runtime.core.humanoid.MutableNodeTransformStore
import dev.vrm.runtime.core.math.Mat4
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3

/**
 * A [MutableNodeTransformStore] backed by a Filament gltfio [FilamentInstance].
 *
 * LIVE-BONE DRIVING (08-24): this store writes bone transforms directly to
 * Filament's TransformManager, and gltfio's `Animator.updateBoneMatrices()`
 * recomputes the skin from those transforms each frame (see
 * `AnimatorImpl::updateBoneMatrices` in gltfio: boneMatrix =
 * inverseGlobalTransform * transformManager.getWorldTransformAccurate(joint)
 * * inverseBindMatrix). This is the official live channel — the previous
 * "gltfio ignores TransformManager writes / only bake works" conclusion was a
 * FALSE wall caused by this store being a NO-OP and an earlier wrong entity
 * index. See PROGRESS 08-24.
 *
 * Because `TransformManager.setTransform` replaces the WHOLE local matrix, we
 * track each node's current local TRS (initialized from the headless rest
 * store) and re-compose the full matrix on every `setLocal*` call.
 *
 * CORRECT SKIN-BONE ENTITY MAPPING:
 * gltfio's mesh skinning is driven by the entities returned by
 * [FilamentInstance.getJointsAt], NOT by `instance.entities[nodeIndex]` and NOT
 * by `asset.getEntitiesByName(name)`. Those can point at unrelated entities and
 * writing them "crumples" the mesh. The authoritative mapping is:
 *
 *     glTF skins[k].joints  (node indices)  <->  instance.getJointsAt(k) (entities)
 *
 * both in the same order, so node j -> entity getJointsAt(k)[idx]. We build that
 * nodeIndex -> entity map once at construction.
 *
 * Reads (world/local rest) come from a headless GLB store (authoritative).
 */
class FilamentNodeTransformStore(
    private val engine: Engine,
    asset: FilamentAsset,
    private val instance: FilamentInstance,
    gltfNodes: List<dev.vrm.runtime.core.gltf.Node>? = null,
    gltfSkins: List<Skin>? = null,
    /** Optional shared MUTABLE world-math store. When provided it is used for
     *  all reads AND every setLocal* mirrors into it, so callers (e.g. spring
     *  bones) that share the same instance see LIVE world matrices that track
     *  the animation. When null, a private static-rest store is built from
     *  [gltfNodes] (previous behaviour). The humanoid normalized-rig rest
     *  capture is unaffected: it reads from its own separate static rest
     *  store, not this one. */
    private val liveMathStore: dev.vrm.runtime.core.humanoid.GltfNodeTransformStore? = null,
) : MutableNodeTransformStore {

    private val transformManager = engine.transformManager

    /** World-math store. If [liveMathStore] was supplied it is shared/live;
     *  otherwise a private headless store (rest pose only). */
    private val mathStore: dev.vrm.runtime.core.humanoid.GltfNodeTransformStore =
        liveMathStore ?: dev.vrm.runtime.core.humanoid.GltfNodeTransformStore.fromGltfNodes(gltfNodes)

    /** nodeIndex -> gltfio joint entity (from skin.joints <-> getJointsAt). */
    private val entityByNode: IntArray = buildJointEntityMap(gltfSkins, gltfNodes)

    /** Current live local TRS per node, so each setLocal* composes the full matrix. */
    private val currentTrans: MutableList<Vec3>
    private val currentRot: MutableList<Quat>
    private val currentScale: MutableList<Vec3>

    init {
        val count = gltfNodes?.size ?: 0
        currentTrans = ArrayList(count)
        currentRot = ArrayList(count)
        currentScale = ArrayList(count)
        repeat(count) { i ->
            currentTrans.add(mathStore.getLocalTranslation(i).copy())
            currentRot.add(mathStore.getLocalRotation(i).copy())
            currentScale.add(mathStore.getLocalScale(i).copy())
        }
    }

    private fun buildJointEntityMap(
        skins: List<Skin>?,
        gltfNodes: List<dev.vrm.runtime.core.gltf.Node>?,
    ): IntArray {
        val count = gltfNodes?.size ?: 0
        val map = IntArray(count) { 0 }
        if (skins != null) {
            for (k in skins.indices) {
                val gltfJoints = skins[k].joints
                val instanceJoints = instance.getJointsAt(k)
                if (instanceJoints == null) continue
                for (i in gltfJoints.indices) {
                    if (i < instanceJoints.size) {
                        val nodeIdx = gltfJoints[i]
                        if (nodeIdx in map.indices && map[nodeIdx] == 0) {
                            map[nodeIdx] = instanceJoints[i]
                        }
                    }
                }
            }
        }
        return map
    }

    private fun entityOf(nodeIndex: Int): Int {
        if (nodeIndex in entityByNode.indices && entityByNode[nodeIndex] != 0) {
            return entityByNode[nodeIndex]
        }
        return 0
    }

    override fun hasNode(nodeIndex: Int): Boolean = entityOf(nodeIndex) != 0

    /** DIAGNOSTIC: expose the resolved gltfio entity for a node index. */
    fun debugEntity(nodeIndex: Int): Int = entityOf(nodeIndex)

    override fun parentNodeIndex(nodeIndex: Int): Int = mathStore.parentNodeIndex(nodeIndex)
    override fun getLocalTranslation(nodeIndex: Int): Vec3 = mathStore.getLocalTranslation(nodeIndex)
    override fun getLocalRotation(nodeIndex: Int): Quat = mathStore.getLocalRotation(nodeIndex)
    override fun getLocalScale(nodeIndex: Int): Vec3 = mathStore.getLocalScale(nodeIndex)
    override fun getWorldMatrix(nodeIndex: Int): Mat4 = mathStore.getWorldMatrix(nodeIndex)

    /** Compose the full local matrix from tracked TRS and push it to TransformManager. */
    private fun pushLocal(nodeIndex: Int) {
        val entity = entityOf(nodeIndex)
        if (entity == 0) return
        val i = transformManager.getInstance(entity)
        if (i == 0) return
        val m = Mat4.fromPositionRotationScale(
            currentTrans[nodeIndex],
            currentRot[nodeIndex],
            currentScale[nodeIndex],
        )
        transformManager.setTransform(i, m.elements)
    }

    override fun setLocalTranslation(nodeIndex: Int, v: Vec3) {
        if (nodeIndex !in currentTrans.indices) return
        currentTrans[nodeIndex].copy(v)
        pushLocal(nodeIndex)
        mathStore.setLocalTranslation(nodeIndex, v)
    }

    override fun setLocalRotation(nodeIndex: Int, q: Quat) {
        if (nodeIndex !in currentRot.indices) return
        currentRot[nodeIndex].copy(q)
        pushLocal(nodeIndex)
        mathStore.setLocalRotation(nodeIndex, q)
    }

    override fun setLocalScale(nodeIndex: Int, s: Vec3) {
        if (nodeIndex !in currentScale.indices) return
        currentScale[nodeIndex].copy(s)
        pushLocal(nodeIndex)
        mathStore.setLocalScale(nodeIndex, s)
    }
}
