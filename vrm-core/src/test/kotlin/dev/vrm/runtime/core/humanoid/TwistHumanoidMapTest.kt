package dev.vrm.runtime.core.humanoid

import dev.vrm.runtime.core.vrm.VrmLoader
import org.junit.jupiter.api.Test
import java.io.File

/** Quick check: why does twist's humanoid hips map to -1 at runtime? */
class TwistHumanoidMapTest {

    @Test
    fun `twist humanoid bone map`() {
        val vrm = VrmLoader.load(File("../app/src/main/assets/avatars/VRM1_Constraint_Twist_Sample.vrm").readBytes())
        println("vrm.vrm humanoid.humanBones size=${vrm.vrm?.humanoid?.humanBones?.size}")
        println("has hips in schema=${vrm.vrm?.humanoid?.humanBones?.containsKey("hips")}")
        val store = GltfNodeTransformStore.fromGltfNodes(vrm.gltf.nodes)
        val h = VRMHumanoid.fromVrm(vrm.gltf, vrm.vrm!!, store)
        println("rawHumanBones size=${h.rawHumanBones.size}")
        println("hips index=${h.getRawBoneNodeIndex("hips")}")
        println("head index=${h.getRawBoneNodeIndex("head")}")
    }
}
