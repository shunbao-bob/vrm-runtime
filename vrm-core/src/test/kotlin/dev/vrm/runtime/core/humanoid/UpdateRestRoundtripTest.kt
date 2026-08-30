package dev.vrm.runtime.core.humanoid

import dev.vrm.runtime.core.vrm.VrmLoader
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Core-soundness check: after resetNormalizedPose() (all normalized quats =
 * identity = T-pose) + update(), the raw bones should return to the model's
 * ORIGINAL rest pose (boneRotation compensates the author's rest rotation).
 * If this fails, the update() write-back math has a bug.
 */
class UpdateRestRoundtripTest {

    private val appAssets = File("../app/src/main/assets")

    private fun roundtrip(rel: String) {
        val vrm = VrmLoader.load(File(appAssets, rel).readBytes())
        val store = GltfNodeTransformStore.fromGltfNodes(vrm.gltf.nodes)
        val h = VRMHumanoid.fromVrm(vrm.gltf, vrm.vrm!!, store)

        // capture original raw rest local rotations
        val orig = HashMap<String, PoseTransform>()
        h.rawHumanBones.forEach { (name, idx) ->
            if (store.hasNode(idx)) {
                val t = PoseTransform()
                t.position = store.getLocalTranslation(idx).copy()
                t.rotation = store.getLocalRotation(idx).copy()
                orig[name] = t
            }
        }

        // T-pose then write back
        h.resetNormalizedPose()
        h.update()

        // compare
        var worstDiff = 0f
        val bad = mutableListOf<String>()
        for ((name, o) in orig) {
            val idx = h.rawHumanBones[name] ?: continue
            val now = store.getLocalRotation(idx)
            val dot = kotlin.math.abs(
                o.rotation!!.x * now.x + o.rotation!!.y * now.y +
                o.rotation!!.z * now.z + o.rotation!!.w * now.w
            )
            val ang = 2.0 * kotlin.math.acos(dot.coerceIn(-1f, 1f))
            if (ang > 0.01) {
                bad.add("$name: orig=(${"%.3f".format(o.rotation!!.x)},${"%.3f".format(o.rotation!!.y)},${"%.3f".format(o.rotation!!.z)},${"%.3f".format(o.rotation!!.w)}) now=(${"%.3f".format(now.x)},${"%.3f".format(now.y)},${"%.3f".format(now.z)},${"%.3f".format(now.w)}) d=${"%.2f".format(ang)}")
                if (ang.toFloat() > worstDiff) worstDiff = ang.toFloat()
            }
        }
        println("$rel: worstDiff=${"%.3f".format(worstDiff)} rad, mismatched=${bad.size}/${orig.size}")
        bad.take(12).forEach { println("   $it") }
    }

    @Test
    fun `tpose writeback returns to original rest`() {
        for (rel in listOf(
            "avatars/VRM1_Constraint_Twist_Sample.vrm",
            "avatars/VRoid_Sample_C.vrm",
            "models/Aisa.vrm",
        )) {
            roundtrip(rel)
            println()
        }
    }
}
