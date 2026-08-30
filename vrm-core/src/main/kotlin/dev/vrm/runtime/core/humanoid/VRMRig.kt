package dev.vrm.runtime.core.humanoid

import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3

/**
 * Represents the raw (engine-bound) rig of a VRM: bone name -> glTF node index,
 * backed by a [NodeTransformStore] for reading/writing actual transforms.
 *
 * Port of three-vrm's VRMRig, adapted to an engine-agnostic store.
 *
 * The rest pose (and the world rest rotations used by the normalized rig's
 * spec-compliant pose conversion) is captured from a SEPARATE, engine-agnostic
 * [restStore] built directly from glTF node TRS. This is important: a render-
 * engine store (e.g. Filament) may not have committed its transforms yet when
 * a model is first loaded, so reading rest rotations from it can yield wrong
 * W/L values. That would break pose conversion on models with a non-trivial
 * rest pose (all 54 human bones rotated, e.g. VRoid_Sample) while leaving
 * models with an identity rest pose looking fine (e.g. Constraint_Twist).
 */
class VRMRig(
    /** bone name -> node index */
    val humanBones: Map<String, Int>,
    private val store: MutableNodeTransformStore,
    private val restStore: NodeTransformStore,
) {
    /** The underlying transform store, exposed for the normalized rig's world math. */
    val storeFor: MutableNodeTransformStore get() = store

    /** The static, engine-agnostic rest store (true model rest pose). */
    val restStoreFor: NodeTransformStore get() = restStore

    /**
     * Rest pose captured at construction: bone name -> absolute local transform.
     * This is the T-pose / initial pose of the model.
     */
    val restPose: Map<String, PoseTransform> = captureAbsolutePose()

    private fun captureAbsolutePose(): Map<String, PoseTransform> {
        val pose = HashMap<String, PoseTransform>()
        humanBones.forEach { (name, nodeIndex) ->
            if (!store.hasNode(nodeIndex)) return@forEach
            val t = PoseTransform()
            t.position = store.getLocalTranslation(nodeIndex).copy()
            t.rotation = store.getLocalRotation(nodeIndex).copy()
            pose[name] = t
        }
        return pose
    }

    /**
     * Current absolute pose (contains the initial state; not compatible between models).
     */
    fun getAbsolutePose(): Map<String, PoseTransform> {
        val pose = HashMap<String, PoseTransform>()
        humanBones.forEach { (name, nodeIndex) ->
            if (!store.hasNode(nodeIndex)) return@forEach
            val t = PoseTransform()
            t.position = store.getLocalTranslation(nodeIndex).copy()
            t.rotation = store.getLocalRotation(nodeIndex).copy()
            pose[name] = t
        }
        return pose
    }

    /**
     * Current pose relative to rest pose (T-pose). Each transform is a local
     * transform relative to the rest pose.
     */
    fun getPose(): Map<String, PoseTransform> {
        val pose = HashMap<String, PoseTransform>()
        humanBones.forEach { (name, nodeIndex) ->
            if (!store.hasNode(nodeIndex)) return@forEach
            val current = PoseTransform()

            val rawPos = store.getLocalTranslation(nodeIndex)
            val rawRot = store.getLocalRotation(nodeIndex)
            val restState = restPose[name]

            val restPos = restState?.position
            val restRot = restState?.rotation

            val newPos = rawPos.copy()
            if (restPos != null) newPos.sub(restPos)
            current.position = newPos

            val newRot = rawRot.copy()
            if (restRot != null) newRot.multiply(restRot.invertedCopy())
            current.rotation = newRot

            pose[name] = current
        }
        return pose
    }

    /**
     * Apply a pose (local transforms relative to rest pose).
     */
    fun setPose(pose: Map<String, PoseTransform>) {
        pose.forEach { (name, state) ->
            val nodeIndex = humanBones[name] ?: return@forEach
            if (!store.hasNode(nodeIndex)) return@forEach
            val restState = restPose[name] ?: return@forEach

            val stPos = state.position
            if (stPos != null) {
                val final = stPos.copy()
                val restPos = restState.position
                if (restPos != null) final.add(restPos)
                store.setLocalTranslation(nodeIndex, final)
            }
            val stRot = state.rotation
            if (stRot != null) {
                val final = stRot.copy()
                val restRot = restState.rotation
                if (restRot != null) final.multiply(restRot)
                store.setLocalRotation(nodeIndex, final)
            }
        }
    }

    /**
     * Reset the humanoid to its rest pose.
     */
    fun resetPose() {
        restPose.forEach { (name, rest) ->
            val nodeIndex = humanBones[name] ?: return@forEach
            if (!store.hasNode(nodeIndex)) return@forEach
            val pos = rest.position
            if (pos != null) store.setLocalTranslation(nodeIndex, pos.copy())
            val rot = rest.rotation
            if (rot != null) store.setLocalRotation(nodeIndex, rot.copy())
        }
    }

    fun getBoneNodeIndex(name: String): Int? = humanBones[name]

    fun hasBone(name: String): Boolean = humanBones.containsKey(name)
}