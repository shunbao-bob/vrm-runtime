package dev.vrm.runtime.core.springbone

import dev.vrm.runtime.core.math.Mat4
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3

/**
 * A single joint of a spring bone. Port of three-vrm's `VRMSpringBoneJoint`
 * (VRMSpringBoneJoint.ts), engine-agnostic — transforms go through
 * [SpringBoneStore] instead of THREE.Object3D.
 *
 * The joint drives its [boneNode]'s rotation so that the [childNode] tail
 * follows a Verlet-integrated position, pulled toward the parent bone's
 * orientation by [SpringBoneJointSettings.stiffness], pushed down by gravity,
 * damped by drag, and kept out of collider shapes.
 *
 * All positions are tracked in "center space" (the space of the spring's
 * [center] node, or world space when there is no center), which lets a hair /
 * skirt chain keep its shape while the avatar moves.
 */
class SpringBoneJoint(
    /** glTF node index of the bone this joint rotates. */
    val boneNode: Int,
    /** glTF node index of the child used as the tail, or `null` (VRM0 fixed 7cm). */
    val childNode: Int?,
    /** Physics settings. */
    val settings: SpringBoneJointSettings,
    /** Collider groups attached to this joint. */
    val colliderGroups: List<SpringBoneColliderGroup> = emptyList(),
    private val store: SpringBoneStore,
) {
    // ---- mutable simulation state ----

    /** Current tail position in center space (Verlet state). */
    private val _currentTail = Vec3()

    /** Previous tail position in center space (Verlet state). */
    private val _prevTail = Vec3()

    /** Initial axis of the bone, in local unit. */
    private val _boneAxis = Vec3()

    /** Length of the bone in world units, updated each frame. */
    private var _worldSpaceBoneLength = 0f

    /** Center node index, or -1 for world space. */
    var center: Int = -1

    /** Initial local matrix / rotation / child position snapshots. */
    private val _initialLocalMatrix = Mat4()
    private val _initialLocalRotation = Quat()
    private val _initialLocalChildPosition = Vec3()

    /** temp vectors reused across frames to avoid allocation */
    private val _v3A = Vec3()
    private val _v3B = Vec3()
    private val _nextTail = Vec3()
    private val _worldSpacePosition = Vec3()

    /** World matrix of the bone's parent, or identity when the bone is a root. */
    private fun parentMatrixWorld(): Mat4 {
        val parent = store.parentNodeIndex(boneNode)
        return if (parent >= 0) store.getWorldMatrix(parent) else Mat4.IDENTITY
    }

    /** Matrix converting center space to world space. */
    private fun matrixCenterToWorld(): Mat4 {
        return if (center >= 0) store.getWorldMatrix(center) else Mat4.IDENTITY
    }

    /** Matrix converting world space to center space. */
    private fun matrixWorldToCenter(): Mat4 {
        return if (center >= 0) store.getWorldMatrix(center).inverted() else Mat4.IDENTITY
    }

    /**
     * Set the initial state of this joint: capture the bone's rest transform,
     * the child's local position, and seed the tail positions. Call after the
     * model's rest pose is in the store (e.g. via [SpringBoneManager.setInitState]).
     */
    fun setInitState() {
        // remember initial local matrix and rotation of the bone
        _initialLocalMatrix.setFrom(storeLocalMatrix())
        _initialLocalRotation.copy(store.getLocalRotation(boneNode))

        // see initial position of its local child
        if (childNode != null) {
            _initialLocalChildPosition.copy(store.getLocalTranslation(childNode))
        } else {
            // VRM0 requires a fixed 7cm bone length for the final node in a chain
            _initialLocalChildPosition.copy(store.getLocalTranslation(boneNode)).normalize().scale(0.07f)
        }

        // copy the child position to tails (in center space)
        val matrixWorldToCenter = matrixWorldToCenter()
        _currentTail.copy(store.localToWorld(boneNode, _initialLocalChildPosition)).applyMatrix4(matrixWorldToCenter)
        _prevTail.copy(_currentTail)

        // bone axis in local unit
        _boneAxis.copy(_initialLocalChildPosition).normalize()
    }

    /**
     * Reset the joint to its initial rest rotation and re-seed the tails.
     */
    fun reset() {
        store.setLocalRotation(boneNode, _initialLocalRotation)

        // re-seed tails from the (rest) bone transform
        val matrixWorldToCenter = matrixWorldToCenter()
        _currentTail.copy(store.localToWorld(boneNode, _initialLocalChildPosition)).applyMatrix4(matrixWorldToCenter)
        _prevTail.copy(_currentTail)
    }

    /**
     * Advance the simulation by [delta] seconds. Call once per frame via
     * [SpringBoneManager.update]; the store's world matrices must be current.
     */
    fun update(delta: Float) {
        if (delta <= 0f) return

        // update the world-space bone length
        calcWorldSpaceBoneLength()

        // get bone axis in world space
        val worldSpaceBoneAxis = _v3B
            .copy(_boneAxis)
            .transformDirection(_initialLocalMatrix)
            .transformDirection(parentMatrixWorld())

        // verlet integration to find the next tail position
        _nextTail
            // determine inertia in center space
            .copy(_currentTail)
            .add(_v3A.copy(_currentTail).sub(_prevTail).scale(1f - settings.dragForce))
            // convert center space to world space
            .applyMatrix4(matrixCenterToWorld())
            // apply stiffness and gravity in world space
            .addScaledVector(worldSpaceBoneAxis, settings.stiffness * delta)
            .addScaledVector(settings.gravityDir, settings.gravityPower * delta)

        // normalize bone length
        _worldSpacePosition.copy(worldPositionOfBone())
        _nextTail.sub(_worldSpacePosition).normalize().scale(_worldSpaceBoneLength).add(_worldSpacePosition)

        // resolve collisions
        collision(_nextTail)

        // update prevTail and currentTail
        _prevTail.copy(_currentTail)
        _currentTail.copy(_nextTail).applyMatrix4(matrixWorldToCenter())

        // convert the tail direction into a bone rotation
        // worldSpaceInitialMatrixInv = (parentWorld * initialLocal)^-1
        _matA.setFrom(parentMatrixWorld()).multiply(_initialLocalMatrix).invert()

        _v3A.copy(_nextTail).applyMatrix4(_matA).normalize()
        _quatA.setFromUnitVectors(_boneAxis, _v3A)
        _quatA.premultiply(_initialLocalRotation)

        store.setLocalRotation(boneNode, _quatA)
    }

    /**
     * Do collision math against every collider attached to this joint.
     *
     * @param tail the tail position, possibly pushed out on hit.
     */
    private fun collision(tail: Vec3) {
        for (cg in colliderGroups) {
            for (collider in cg.colliders) {
                val dist = collider.shape.calculateCollision(
                    collider.colliderMatrix(store),
                    tail,
                    settings.hitRadius,
                    _v3A,
                )
                if (dist < 0f) {
                    // hit: push the tail out along the push direction
                    tail.addScaledVector(_v3A, -dist)

                    // re-normalize bone length
                    tail.sub(_worldSpacePosition)
                    val length = tail.length()
                    tail.scale(_worldSpaceBoneLength / length).add(_worldSpacePosition)
                }
            }
        }
    }

    /** Calculate the current world-space length of this bone segment. */
    private fun calcWorldSpaceBoneLength() {
        _v3A.copy(worldPositionOfBone())

        if (childNode != null) {
            _v3B.copy(worldPositionOfNode(childNode))
        } else {
            _v3B.copy(_initialLocalChildPosition).applyMatrix4(store.getWorldMatrix(boneNode))
        }

        _worldSpaceBoneLength = _v3A.sub(_v3B).length()
    }

    private fun worldPositionOfBone(): Vec3 = worldPositionOfNode(boneNode)

    private fun worldPositionOfNode(nodeIndex: Int): Vec3 =
        store.getWorldMatrix(nodeIndex).transformPoint(_tmpPos.set(0f, 0f, 0f))

    /** Compose the bone's local TRS into a matrix. */
    private fun storeLocalMatrix(): Mat4 {
        val t = store.getLocalTranslation(boneNode)
        val r = store.getLocalRotation(boneNode)
        val s = store.getLocalScale(boneNode)
        return Mat4.fromPositionRotationScale(t, r, s)
    }

    /** temp matrix + quaternion reused across frames */
    private val _matA = Mat4()
    private val _quatA = Quat()
    private val _tmpPos = Vec3()
}
