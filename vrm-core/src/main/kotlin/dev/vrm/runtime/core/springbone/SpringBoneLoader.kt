package dev.vrm.runtime.core.springbone

import dev.vrm.runtime.core.gltf.Gltf
import dev.vrm.runtime.core.math.Vec3
import dev.vrm.runtime.core.vrm.VrmcSpringBone
import dev.vrm.runtime.core.vrm.VrmcSpringBoneCollider
import dev.vrm.runtime.core.vrm.VrmcSpringBoneJoint
import dev.vrm.runtime.core.vrm.VrmcSpringBoneSpring

/**
 * Builds a [SpringBoneManager] from a VRM 1.0 `VRMC_springBone` extension.
 *
 * Port of three-vrm's `VRMSpringBoneLoaderPlugin._v1Import` (VRMSpringBoneLoaderPlugin.ts):
 *  - colliders are created per schema entry and attached to their node;
 *  - collider groups resolve collider indices (skipping missing ones);
 *  - each spring's consecutive joint pairs form [SpringBoneJoint]s: joint *i*
 *    uses schema joint *i* as its bone and schema joint *i+1* as its child tail,
 *    with settings taken from schema joint *i*.
 *
 * Node-index bounds are validated (matching three-vrm's warn-and-skip).
 *
 * @param gltf parsed glTF of the VRM
 * @param extension the parsed VRMC_springBone extension
 * @param store a [SpringBoneStore] over the same glTF nodes
 */
class SpringBoneLoader(
    private val gltf: Gltf,
    private val extension: VrmcSpringBone,
    private val store: SpringBoneStore = GltfSpringBoneStore.fromGltf(gltf),
) {

    private val nodeCount: Int = gltf.nodes?.size ?: 0

    /**
     * Import spring bones and return a [SpringBoneManager].
     */
    fun load(): SpringBoneManager {
        val manager = SpringBoneManager(store)

        // ---- colliders ----
        val colliders = (extension.colliders ?: emptyList()).mapIndexed { i, schemaCollider ->
            val node = schemaCollider.node
            if (node == null || node !in 0 until nodeCount) {
                // three-vrm warns and skips; some models put -1
                return@mapIndexed null
            }
            val shape = shapeOf(schemaCollider)
                ?: return@mapIndexed null // no valid shape
            SpringBoneCollider(node, shape)
        }

        // ---- collider groups ----
        val colliderGroups = (extension.colliderGroups ?: emptyList()).map { schemaGroup ->
            val cols = (schemaGroup.colliders ?: emptyList())
                .mapNotNull { i -> colliders.getOrNull(i) }
            SpringBoneColliderGroup(cols, schemaGroup.name)
        }

        // ---- springs ----
        (extension.springs ?: emptyList()).forEach { schemaSpring ->
            val schemaJoints = schemaSpring.joints
            if (schemaJoints.isEmpty()) return@forEach

            // resolve collider groups referenced by this spring
            val groupsForSpring = (schemaSpring.colliderGroups ?: emptyList())
                .mapNotNull { i -> colliderGroups.getOrNull(i) }

            val center = schemaSpring.center
                ?.takeIf { it in 0 until nodeCount }
                ?: -1

            var prevSchemaJoint: VrmcSpringBoneJoint? = null
            for (schemaJoint in schemaJoints) {
                if (prevSchemaJoint != null) {
                    val boneNode = prevSchemaJoint.node
                    val childNode = schemaJoint.node
                    if (boneNode !in 0 until nodeCount || childNode !in 0 until nodeCount) {
                        prevSchemaJoint = schemaJoint
                        continue
                    }

                    val joint = SpringBoneJoint(
                        boneNode = boneNode,
                        childNode = childNode,
                        settings = settingsOf(prevSchemaJoint),
                        colliderGroups = groupsForSpring,
                        store = store,
                    )
                    joint.center = center
                    manager.addJoint(joint)
                }
                prevSchemaJoint = schemaJoint
            }
        }

        manager.setInitState()
        return manager
    }

    private fun settingsOf(joint: VrmcSpringBoneJoint): SpringBoneJointSettings = SpringBoneJointSettings(
        hitRadius = joint.hitRadius,
        stiffness = joint.stiffness,
        gravityPower = joint.gravityPower,
        gravityDir = joint.gravityDir?.let { if (it.size >= 3) Vec3(it[0], it[1], it[2]) else Vec3(0f, -1f, 0f) }
            ?: Vec3(0f, -1f, 0f),
        dragForce = joint.dragForce,
    )

    private fun shapeOf(collider: VrmcSpringBoneCollider): SpringBoneColliderShape? {
        val shape = collider.shape ?: return null

        shape.sphere?.let { s ->
            val offset = if (s.offset.size >= 3) Vec3(s.offset[0], s.offset[1], s.offset[2]) else Vec3()
            return SphereColliderShape(offset, s.radius)
        }

        shape.capsule?.let { c ->
            val offset = if (c.offset.size >= 3) Vec3(c.offset[0], c.offset[1], c.offset[2]) else Vec3()
            val tail = if (c.tail.size >= 3) Vec3(c.tail[0], c.tail[1], c.tail[2]) else Vec3()
            return CapsuleColliderShape(offset, tail, c.radius)
        }

        return null
    }
}
