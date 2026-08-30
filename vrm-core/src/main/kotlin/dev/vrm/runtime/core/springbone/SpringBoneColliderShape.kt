package dev.vrm.runtime.core.springbone

import dev.vrm.runtime.core.math.Mat4
import dev.vrm.runtime.core.math.Vec3

/**
 * A collider shape for spring-bone collision, engine-agnostic.
 *
 * Port of three-vrm's `VRMSpringBoneColliderShape`. A shape answers the signed
 * distance from [objectPosition] (a joint tail) to the collider surface:
 *
 *  - returns a negative value when the object penetrates the shape
 *    (i.e. a hit), and writes the outward push direction into [target].
 *  - returns `>= 0` when there is no contact; [target] is left as the
 *    (possibly unnormalized) delta from the collider to the object.
 *
 * `colliderMatrix` is the shape's world matrix (node world * local offset).
 */
sealed class SpringBoneColliderShape {

    /** Local-space offset of the shape from its collider node. */
    abstract val offset: Vec3

    /**
     * Compute the signed distance to the surface and (on hit) the push
     * direction.
     *
     * @param colliderMatrix world matrix of the collider (offset applied)
     * @param objectPosition world position of the joint tail
     * @param objectRadius the joint's hit radius
     * @param target out-param: on a hit, the normalized push direction
     * @return signed distance; negative means penetrating
     */
    abstract fun calculateCollision(
        colliderMatrix: Mat4,
        objectPosition: Vec3,
        objectRadius: Float,
        target: Vec3,
    ): Float
}

/**
 * A sphere collider. Port of `VRMSpringBoneColliderShapeSphere`.
 *
 * @param offset local-space center offset from the collider node
 * @param radius sphere radius
 * @param inside when true, push inward instead of outward (keeps the joint
 *   inside the sphere). Standard VRM 1.0 colliders use `false`.
 */
class SphereColliderShape(
    override val offset: Vec3 = Vec3(),
    val radius: Float = 0f,
    val inside: Boolean = false,
) : SpringBoneColliderShape() {

    private val _center = Vec3()

    override fun calculateCollision(
        colliderMatrix: Mat4,
        objectPosition: Vec3,
        objectRadius: Float,
        target: Vec3,
    ): Float {
        val colliderPos = colliderMatrix.transformPoint(_center.set(0f, 0f, 0f))
        target.set(objectPosition.x - colliderPos.x, objectPosition.y - colliderPos.y, objectPosition.z - colliderPos.z)

        val length = target.length()
        val distance = if (inside) radius - objectRadius - length else length - objectRadius - radius

        if (distance < 0f) {
            target.scale(1f / length) // convert the delta to the direction
            if (inside) {
                target.negate() // if inside, reverse the direction
            }
        }

        return distance
    }
}

/**
 * A capsule collider. Port of `VRMSpringBoneColliderShapeCapsule`.
 *
 * @param offset local-space offset of the capsule head from the collider node
 * @param tail local-space offset of the capsule tail from the collider node
 * @param radius capsule radius
 * @param inside when true, push inward instead of outward.
 */
class CapsuleColliderShape(
    override val offset: Vec3 = Vec3(),
    val tail: Vec3 = Vec3(),
    val radius: Float = 0f,
    val inside: Boolean = false,
) : SpringBoneColliderShape() {

    private val _head = Vec3()
    private val _axis = Vec3()
    private val _toObject = Vec3()

    override fun calculateCollision(
        colliderMatrix: Mat4,
        objectPosition: Vec3,
        objectRadius: Float,
        target: Vec3,
    ): Float {
        // transformed head position = colliderMatrix.translation
        val e = colliderMatrix.elements
        _head.set(e[12], e[13], e[14])

        // transformed tail-to-head axis = rotate (tail - offset) by the matrix's
        // 3x3 part (translation ignored, length preserved — matching the TS
        // where (tail-offset) is applied as a point then head is subtracted).
        val te = colliderMatrix.elements
        val dx = tail.x - offset.x
        val dy = tail.y - offset.y
        val dz = tail.z - offset.z
        _axis.set(
            te[0] * dx + te[4] * dy + te[8] * dz,
            te[1] * dx + te[5] * dy + te[9] * dz,
            te[2] * dx + te[6] * dy + te[10] * dz,
        )
        val lengthSqCapsule = _axis.lengthSq()

        // from head to object
        _toObject.set(objectPosition.x - _head.x, objectPosition.y - _head.y, objectPosition.z - _head.z)
        val dot = _axis.dot(_toObject)

        if (dot <= 0.0f) {
            // object is near the head: keep _toObject as-is (head to object)
        } else if (lengthSqCapsule <= dot) {
            // object is near the tail: from tail to object
            _toObject.sub(_axis)
        } else {
            // object is between the two ends: nearest point on the shaft
            _axis.scale(dot / lengthSqCapsule)
            _toObject.sub(_axis)
        }

        val length = _toObject.length()
        val distance = if (inside) radius - objectRadius - length else length - objectRadius - radius

        if (distance < 0f) {
            _toObject.scale(1f / length)
            if (inside) {
                _toObject.negate()
            }
        }

        target.copy(_toObject)
        return distance
    }
}
