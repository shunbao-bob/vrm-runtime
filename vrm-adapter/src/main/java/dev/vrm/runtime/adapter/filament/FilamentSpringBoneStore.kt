package dev.vrm.runtime.adapter.filament

import com.google.android.filament.Engine
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import dev.vrm.runtime.core.gltf.Node
import dev.vrm.runtime.core.gltf.Skin
import dev.vrm.runtime.core.math.Mat4
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3
import dev.vrm.runtime.core.springbone.SpringBoneStore

/**
 * A [SpringBoneStore] backed by a Filament gltfio instance's TransformManager.
 *
 * Uses the same skin.joints ↔ getJointsAt entity mapping as
 * [FilamentNodeTransformStore] so that bone writes affect the correct gltfio
 * entities (the ones the skinning animation reads from).
 *
 * World-matrix reads and parent queries come from a [GltfNodeTransformStore]
 * (authoritative math), while local-rotation writes go to the Filament
 * TransformManager. This is the same architecture as [FilamentNodeTransformStore].
 *
 * Main thread only.
 */
class FilamentSpringBoneStore(
    private val engine: Engine,
    asset: FilamentAsset,
    private val instance: FilamentInstance,
    gltfNodes: List<Node>? = null,
    gltfSkins: List<Skin>? = null,
    /** Optional shared MUTABLE world-math store. Must be the SAME instance the
     *  avatar's node store uses. When provided, world-matrix reads here return
     *  LIVE values that track the animation + other spring-joint writes (this
     *  is what satisfies `SpringBoneManager.update`'s "world matrices must
     *  reflect the current animation pose" contract). When null a private
     *  static-rest store is built (previous behaviour). */
    private val liveMathStore: dev.vrm.runtime.core.humanoid.GltfNodeTransformStore? = null,
) : SpringBoneStore {

    private val transformManager = engine.transformManager

    /** World-math store. If [liveMathStore] was supplied it is shared/live;
     *  otherwise a private headless store (rest pose only). */
    private val mathStore: dev.vrm.runtime.core.humanoid.GltfNodeTransformStore =
        liveMathStore ?: dev.vrm.runtime.core.humanoid.GltfNodeTransformStore.fromGltfNodes(gltfNodes)

    /** nodeIndex -> gltfio joint entity (from skin.joints <-> getJointsAt). */
    private val entityByNode: IntArray = buildJointEntityMap(gltfSkins, gltfNodes)

    private fun buildJointEntityMap(
        skins: List<Skin>?,
        gltfNodes: List<Node>?,
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

    override fun parentNodeIndex(nodeIndex: Int): Int = mathStore.parentNodeIndex(nodeIndex)

    override fun getLocalTranslation(nodeIndex: Int): Vec3 =
        mathStore.getLocalTranslation(nodeIndex).copy()

    override fun getLocalRotation(nodeIndex: Int): Quat =
        mathStore.getLocalRotation(nodeIndex).copy()

    override fun getLocalScale(nodeIndex: Int): Vec3 =
        mathStore.getLocalScale(nodeIndex).copy()

    override fun getWorldMatrix(nodeIndex: Int): Mat4 =
        mathStore.getWorldMatrix(nodeIndex)

    override fun setLocalRotation(nodeIndex: Int, q: Quat) {
        val entity = entityOf(nodeIndex)
        if (entity == 0) return
        val i = transformManager.getInstance(entity)
        if (i == 0) return
        // Read the current local matrix, replace the rotation part, write back
        transformManager.getTransform(entity, tmpMat)
        val old = Mat4(tmpMat.copyOf())
        val p = Vec3(); val oldQ = Quat(); val s = Vec3()
        old.decompose(p, oldQ, s)
        transformManager.setTransform(entity, Mat4.fromPositionRotationScale(p, q, s).elements)
        // Mirror into the shared world-math store so later joints / the next
        // frame see this bone's new rotation (live world matrices).
        mathStore.setLocalRotation(nodeIndex, q)
    }

    override fun localToWorld(nodeIndex: Int, local: Vec3): Vec3 =
        getWorldMatrix(nodeIndex).transformPoint(local)

    companion object {
        private val tmpMat = FloatArray(16)
    }
}