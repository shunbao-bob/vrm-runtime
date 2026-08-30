package dev.vrm.runtime.core.humanoid

import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3

/**
 * A lightweight scene-tree node used inside vrm-core for the normalized rig.
 * Three.js Object3D analog: holds local position/quaternion and a parent.
 */
class RigNode(
    var name: String = "",
    var parent: RigNode? = null,
) {
    var position: Vec3 = Vec3()
    var quaternion: Quat = Quat()

    val children = ArrayList<RigNode>()

    fun add(child: RigNode) {
        child.parent?.let { it.children.remove(child) }
        child.parent = this
        children.add(child)
    }

    /** World (model-space) transform by walking up the parent chain (no scale). */
    fun getWorldPosition(): Vec3 {
        var result = position.copy()
        var node = parent
        while (node != null) {
            result = node.quaternion.rotate(result) + node.position
            node = node.parent
        }
        return result
    }

    fun getWorldQuaternion(): Quat {
        var q = quaternion.copy()
        var node = parent
        while (node != null) {
            q = q.premultiply(node.quaternion.copy())
            node = node.parent
        }
        return q
    }
}