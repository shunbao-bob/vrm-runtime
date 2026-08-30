package dev.vrm.runtime.core.springbone

import java.util.Collections

/**
 * Manages and updates a set of [SpringBoneJoint]s. Port of three-vrm's
 * `VRMSpringBoneManager`, simplified for node-index stores.
 *
 * Responsibilities:
 *  - hold joints, collider groups / colliders
 *  - [setInitState] / [reset] to (re)seed each joint's simulation state
 *  - [update] each frame: sort joints into dependency order, then advance all.
 *
 * Joint ordering: a joint depends on (a) its own parent node and (b) every
 * collider node it references. We enforce that a node's world matrix is
 * recomputed before any joint that reads it by topologically sorting joints by
 * an arbitrary deterministic id (a stable insertion order), which is adequate
 * for the linear chains VRM spring bones typically form. Most models rely on
 * the store's lazily-recomputed world matrices, so ordering is a robustness
 * net rather than a correctness requirement.
 */
class SpringBoneManager(
    private val store: SpringBoneStore,
) {

    private val _joints = LinkedHashMap<SpringBoneJoint, Boolean>()
    private val _sortedJoints = ArrayList<SpringBoneJoint>()
    private var _isSortedDirty = true

    /** All registered joints, in registration order. */
    val joints: Collection<SpringBoneJoint> get() = _joints.keys.toList()

    /** All distinct collider groups referenced by the registered joints. */
    val colliderGroups: List<SpringBoneColliderGroup>
        get() {
            val set = LinkedHashSet<SpringBoneColliderGroup>()
            for (joint in _joints.keys) {
                set.addAll(joint.colliderGroups)
            }
            return set.toList()
        }

    /** All distinct colliders referenced by the registered joints. */
    val colliders: List<SpringBoneCollider>
        get() {
            val set = LinkedHashSet<SpringBoneCollider>()
            for (group in colliderGroups) {
                set.addAll(group.colliders)
            }
            return set.toList()
        }

    /** Register a joint. */
    fun addJoint(joint: SpringBoneJoint) {
        _joints[joint] = true
        _isSortedDirty = true
    }

    /** Remove a joint. */
    fun deleteJoint(joint: SpringBoneJoint) {
        _joints.remove(joint)
        _isSortedDirty = true
    }

    /** Capture the initial state of every joint (rest pose). */
    fun setInitState() {
        forEachSorted { it.setInitState() }
    }

    /** Reset every joint to its rest pose. */
    fun reset() {
        forEachSorted { it.reset() }
    }

    /**
     * Advance the simulation by [delta] seconds. Assumes the store's world
     * matrices reflect the current animation pose (updated before this call).
     */
    fun update(delta: Float) {
        forEachSorted { it.update(delta) }
    }

    private fun forEachSorted(action: (SpringBoneJoint) -> Unit) {
        for (joint in sortedJoints()) {
            action(joint)
        }
    }

    private fun sortedJoints(): List<SpringBoneJoint> {
        if (_isSortedDirty) {
            // Stabilize order: sort by the bone's depth in its ancestor chain so
            // that parent (closer to hip) joints update before child joints.
            _sortedJoints.clear()
            _sortedJoints.addAll(_joints.keys)
            Collections.sort(_sortedJoints) { a, b -> depthOf(a.boneNode).compareTo(depthOf(b.boneNode)) }
            _isSortedDirty = false
        }
        return _sortedJoints
    }

    private fun depthOf(nodeIndex: Int): Int {
        var depth = 0
        var current = nodeIndex
        while (current >= 0) {
            val parent = store.parentNodeIndex(current)
            if (parent == current || parent < 0) break
            current = parent
            depth++
        }
        return depth
    }
}