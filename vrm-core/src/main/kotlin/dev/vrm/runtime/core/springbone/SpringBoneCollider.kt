package dev.vrm.runtime.core.springbone

import dev.vrm.runtime.core.math.Mat4
import dev.vrm.runtime.core.math.Vec3

/**
 * A spring-bone collider attached to a glTF node. Port of three-vrm's
 * `VRMSpringBoneCollider` (without the THREE.Object3D layer).
 *
 * The collider's world matrix is the node's world matrix with the shape's
 * local-space [offset] applied (matching `updateColliderMatrix`).
 */
class SpringBoneCollider(
    /** glTF node index the collider is attached to. */
    val nodeIndex: Int,
    val shape: SpringBoneColliderShape,
) {
    /** World matrix of the collider shape: node world * local offset. */
    fun colliderMatrix(store: SpringBoneStore): Mat4 {
        val world = store.getWorldMatrix(nodeIndex)
        return applyOffset(world, shape.offset)
    }

    /**
     * Compute the collider matrix for a world matrix and a local offset, the
     * same math as three-vrm's `updateColliderMatrix`: the offset is rotated by
     * the matrix and added to its translation.
     */
    private fun applyOffset(matrixWorld: Mat4, offset: Vec3): Mat4 {
        val me = matrixWorld.elements
        val out = Mat4(me.copyOf())
        if (offset.x != 0f || offset.y != 0f || offset.z != 0f) {
            out.elements[12] = me[0] * offset.x + me[4] * offset.y + me[8] * offset.z + me[12]
            out.elements[13] = me[1] * offset.x + me[5] * offset.y + me[9] * offset.z + me[13]
            out.elements[14] = me[2] * offset.x + me[6] * offset.y + me[10] * offset.z + me[14]
        }
        return out
    }
}

/**
 * A named group of colliders referenced by a spring. Port of three-vrm's
 * `VRMSpringBoneColliderGroup`.
 */
class SpringBoneColliderGroup(
    val colliders: List<SpringBoneCollider> = emptyList(),
    val name: String? = null,
)
