package dev.vrm.runtime.core.humanoid

import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3

/**
 * The normalized rig of a VRM: a rebuilt, T-pose normalized skeleton used for
 * pose retargeting and animation. Port of three-vrm's VRMHumanoidRig, with the
 * rest-pose conversion following the VRM 1.0 spec:
 * https://github.com/vrm-c/vrm-specification (how_to_transform_human_pose)
 *
 * The normalized bones are pure [RigNode]s (engine-independent). On [update],
 * the normalized rotation (a NormalizedLocalRotation) is transferred back to
 * the raw (engine-bound) bones using each bone's rest pose:
 *
 *   PoseForB = L · W⁻¹ · NormalizedLocalRotation · W
 *
 * where W = the bone's World rest rotation and L = its Local rest rotation.
 * This makes pose data compatible between models with different T-poses.
 */
class VRMHumanoidRig private constructor(
    val original: VRMRig,
    val root: RigNode,
    val normalizedBones: Map<String, RigNode>,
    /** bone name -> the bone's OWN world rest rotation (W in the spec). */
    private val worldRestRotations: Map<String, Quat>,
    /** bone name -> the bone's OWN local rest rotation (L in the spec). */
    private val boneRestRotations: Map<String, Quat>,
) {
    /** The set of normalized bones, name -> RigNode. */
    val humanBones: Map<String, RigNode> get() = normalizedBones

    fun getBoneNode(name: String): RigNode? = normalizedBones[name]

    companion object {
        fun create(modelRig: VRMRig): VRMHumanoidRig {
            val root = RigNode(name = "VRMHumanoidRig")
            // rest pose must be captured from the STATIC rest store so W/L are
            // the model's true rest data (see VRMRig docs).
            val store = modelRig.restStoreFor

            // capture world positions / rotations / local rotations
            val boneWorldPositions = HashMap<String, Vec3>()
            val worldRestRotations = HashMap<String, Quat>()
            val boneRestRotations = HashMap<String, Quat>()

            HUMAN_BONE_LIST.forEach { boneName ->
                val nodeIndex = modelRig.getBoneNodeIndex(boneName)
                if (nodeIndex != null && store.hasNode(nodeIndex)) {
                    val world = store.getWorldMatrix(nodeIndex)
                    val wp = Vec3()
                    val wq = Quat()
                    val ws = Vec3()
                    world.decompose(wp, wq, ws)
                    boneWorldPositions[boneName] = wp
                    // W: this bone's own world rest rotation (spec)
                    worldRestRotations[boneName] = wq
                    // L: this bone's own local rest rotation (spec)
                    boneRestRotations[boneName] = store.getLocalRotation(nodeIndex).copy()
                }
            }

            // build rig hierarchy + store parentWorldRotations
            val rigBones = LinkedHashMap<String, RigNode>()
            HUMAN_BONE_LIST.forEach { boneName ->
                val nodeIndex = modelRig.getBoneNodeIndex(boneName)
                if (nodeIndex != null && store.hasNode(nodeIndex)) {
                    val boneWorldPosition = boneWorldPositions[boneName] ?: return@forEach

                    // see the nearest parent position
                    var currentBoneName: String? = boneName
                    var parentBoneWorldPosition: Vec3? = null
                    while (parentBoneWorldPosition == null) {
                        currentBoneName = HUMAN_BONE_PARENT_MAP[currentBoneName]
                        if (currentBoneName == null) break
                        parentBoneWorldPosition = boneWorldPositions[currentBoneName]
                    }

                    // add to hierarchy
                    val rigBoneNode = RigNode(name = "Normalized_$boneName")
                    val parentRigBoneNode = if (currentBoneName != null) (rigBones[currentBoneName] ?: root) else root
                    parentRigBoneNode.add(rigBoneNode)
                    rigBoneNode.position = boneWorldPosition.copy()
                    parentBoneWorldPosition?.let { rigBoneNode.position.sub(it) }

                    rigBones[boneName] = rigBoneNode
                }
            }

            return VRMHumanoidRig(modelRig, root, rigBones, worldRestRotations, boneRestRotations)
        }
    }

    /**
     * Transfer the normalized rig pose to the raw bones.
     *
     * Per the VRM 1.0 spec the raw bone local rotation is
     *   PoseForB = L · W⁻¹ · NormalizedLocalRotation · W
     * where L and W are this bone's local/world rest rotations and
     * NormalizedLocalRotation is the rig bone's quaternion.
     */
    fun update() {
        val store = original.storeFor
        HUMAN_BONE_LIST.forEach { boneName ->
            val rawNodeIndex = original.getBoneNodeIndex(boneName)
            if (rawNodeIndex != null && store.hasNode(rawNodeIndex)) {
                val rigBoneNode = normalizedBones[boneName] ?: return@forEach

                val worldRest = worldRestRotations[boneName] ?: return@forEach
                val localRest = boneRestRotations[boneName] ?: return@forEach
                val invWorldRest = worldRest.invertedCopy()

                // B.LocalRotation = L * W^-1 * NLR * W
                val q = localRest.copy()
                    .multiply(invWorldRest)
                    .multiply(rigBoneNode.quaternion)
                    .multiply(worldRest)
                store.setLocalRotation(rawNodeIndex, q)

                // Move the mass center of the VRM (hips translation, scaled to
                // the model's rest pose height like the spec's translation scaling).
                if (boneName == HumanBoneName.HIPS) {
                    val boneWorldPosition = rigBoneNode.getWorldPosition()
                    val parentRawIndex = store.parentNodeIndex(rawNodeIndex)
                    if (parentRawIndex != -1) {
                        val parentWorldMatrix = store.getWorldMatrix(parentRawIndex)
                        val localPosition = boneWorldPosition.copy().applyMatrix4(parentWorldMatrix.inverted())
                        store.setLocalTranslation(rawNodeIndex, localPosition)
                    }
                }
            }
        }
    }
}
