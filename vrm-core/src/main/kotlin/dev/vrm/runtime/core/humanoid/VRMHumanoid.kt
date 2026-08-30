package dev.vrm.runtime.core.humanoid

import dev.vrm.runtime.core.gltf.Gltf
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3
import dev.vrm.runtime.core.vrm.VRMCVrm
import dev.vrm.runtime.core.vrm.VrmHumanoid

/**
 * A humanoid of a VRM: exposes bone lookup, pose get/set, and the normalized
 * rig used for animation retargeting. Port of three-vrm's VRMHumanoid.
 */
class VRMHumanoid private constructor(
    private val rawRig: VRMRig,
    private val normalizedRig: VRMHumanoidRig,
    var autoUpdateHumanBones: Boolean = true,
) {
    /** bone name -> glTF node index for the raw rig. */
    val rawHumanBones: Map<String, Int> get() = rawRig.humanBones

    /** The underlying transform store bound to the raw (engine) bones. */
    val rawStore: MutableNodeTransformStore get() = rawRig.storeFor

    /** The normalized rig (pure math nodes). */
    val normalizedHumanBones: Map<String, RigNode> get() = normalizedRig.normalizedBones

    val normalizedRoot: RigNode get() = normalizedRig.root

    /** Absolute (model-specific) rest pose. */
    val rawRestPose: Map<String, PoseTransform> get() = rawRig.restPose

    fun getRawBoneNodeIndex(name: String): Int? = rawRig.getBoneNodeIndex(name)
    fun getNormalizedBoneNode(name: String): RigNode? = normalizedRig.getBoneNode(name)

    /** Raw local pose relative to rest pose. */
    fun getRawPose(): Map<String, PoseTransform> = rawRig.getPose()

    fun setRawPose(pose: Map<String, PoseTransform>) = rawRig.setPose(pose)
    fun resetRawPose() = rawRig.resetPose()

    fun getRawAbsolutePose(): Map<String, PoseTransform> = rawRig.getAbsolutePose()

    /** Normalized local pose relative to rest pose. */
    fun getNormalizedPose(): Map<String, PoseTransform> {
        val pose = HashMap<String, PoseTransform>()
        normalizedRig.normalizedBones.forEach { (name, node) ->
            val t = PoseTransform()
            t.position = node.position.copy()
            t.rotation = node.quaternion.copy()
            pose[name] = t
        }
        return pose
    }

    fun setNormalizedPose(pose: Map<String, PoseTransform>) {
        pose.forEach { (name, t) ->
            val node = normalizedRig.normalizedBones[name] ?: return@forEach
            t.position?.let { node.position.copy(it) }
            t.rotation?.let { node.quaternion.copy(it) }
        }
    }

    fun resetNormalizedPose() {
        // rest pose of normalized rig = world-relative offsets baked into positions;
        // reset rotations to identity (T-pose), keep positions
        normalizedRig.normalizedBones.forEach { (_, node) ->
            node.quaternion.set(0f, 0f, 0f, 1f)
        }
    }

    /**
     * Transfer normalized pose to raw bones (if [autoUpdateHumanBones]).
     */
    fun update() {
        if (autoUpdateHumanBones) {
            normalizedRig.update()
        }
    }

    companion object {
        /**
         * Build a [VRMHumanoid] from parsed glTF + VRMC_vrm extension.
         *
         * @throws MissingHumanoidBoneException when required bones are absent.
         */
        fun fromVrm(
            gltf: Gltf,
            vrm: VRMCVrm,
            store: MutableNodeTransformStore = GltfNodeTransformStore.fromGltfNodes(gltf.nodes),
        ): VRMHumanoid {
            val humanBones = buildBoneMap(gltf, vrm.humanoid)
            ensureRequiredBones(humanBones)
            // rest store: engine-agnostic, built from GLB TRS (true rest pose).
            val restStore = GltfNodeTransformStore.fromGltfNodes(gltf.nodes)
            val rawRig = VRMRig(humanBones, store, restStore)
            val normalizedRig = VRMHumanoidRig.create(rawRig)
            return VRMHumanoid(rawRig, normalizedRig)
        }

        /**
         * Build the bone map: human bone name -> glTF node index, verifying each
         * referenced node exists.
         */
        fun buildBoneMap(gltf: Gltf, schemaHumanoid: VrmHumanoid): Map<String, Int> {
            val nodeCount = gltf.nodes?.size ?: 0
            val result = LinkedHashMap<String, Int>()
            schemaHumanoid.humanBones.forEach { (name, bone) ->
                val index = bone.node
                if (index in 0 until nodeCount) {
                    result[name] = index
                } else {
                    // warn-and-skip like three-vrm; missing bone will be caught by required check if needed
                }
            }
            return result
        }

        /**
         * Validate that all 17 required bones exist. Throws [MissingHumanoidBoneException].
         */
        fun ensureRequiredBones(humanBones: Map<String, Int>) {
            val missing = REQUIRED_HUMAN_BONES.filter { !humanBones.containsKey(it) }
            if (missing.isNotEmpty()) {
                throw MissingHumanoidBoneException(missing)
            }
        }
    }
}
