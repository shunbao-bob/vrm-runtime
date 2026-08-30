package dev.vrm.runtime.core.vrma

import dev.vrm.runtime.core.humanoid.GltfNodeTransformStore
import dev.vrm.runtime.core.humanoid.PoseTransform
import dev.vrm.runtime.core.humanoid.VRMHumanoid
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3
import dev.vrm.runtime.core.vrm.VrmLoader
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Verify where twist's arms go while playing Spin.vrma. "U-shaped up" arms
 * (hands above shoulders) vs natural (hands near hips) tells us whether the
 * retarget puts the clip onto the rig correctly in the core.
 */
class TwistArmPoseTest {

    private val appAssets = File("../app/src/main/assets")

    private fun sampleRotation(track: KeyframeTrack, t: Float): Quat {
        val q = Quat()
        if (track.times.size == 1) { q.set(track.values[0], track.values[1], track.values[2], track.values[3]); return q }
        var idx = 0
        for (i in 1 until track.times.size) { if (track.times[i] <= t) idx = i }
        idx = idx.coerceAtMost(track.times.size - 2).coerceAtLeast(0)
        val u = if (track.times[idx + 1] > track.times[idx]) (t - track.times[idx]) / (track.times[idx + 1] - track.times[idx]) else 0f
        val k = 4
        val qa = Quat(track.values[idx*k], track.values[idx*k+1], track.values[idx*k+2], track.values[idx*k+3])
        val qb = Quat(track.values[(idx+1)*k], track.values[(idx+1)*k+1], track.values[(idx+1)*k+2], track.values[(idx+1)*k+3])
        q.copy(qa).slerp(qb, u)
        return q
    }

    private fun world(h: VRMHumanoid, store: GltfNodeTransformStore, bone: String): Vec3 {
        val idx = h.getRawBoneNodeIndex(bone) ?: return Vec3(Float.NaN, Float.NaN, Float.NaN)
        val m = store.getWorldMatrix(idx)
        val p = Vec3(); val q = Quat(); val s = Vec3()
        m.decompose(p, q, s)
        return p
    }

    @Test
    fun `twist spin arm world positions`() {
        val vrm = VrmLoader.load(File(appAssets, "avatars/VRM1_Constraint_Twist_Sample.vrm").readBytes())
        val store = GltfNodeTransformStore.fromGltfNodes(vrm.gltf.nodes)
        val h = VRMHumanoid.fromVrm(vrm.gltf, vrm.vrm!!, store)

        val bytes = File(appAssets, "animations/Spin.vrma").readBytes()
        val vrma = VrmLoader.load(bytes)
        val ext = vrma.vrmAnimation!!
        val bin = vrma.binary?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
        val anim = VrmAnimationLoader(vrma.gltf, bin, ext).loadAll().first()
        val clip = VRMAnimationClipBuilder(anim, h, null).build()

        val times = listOf(0f, clip.duration * 0.25f, clip.duration * 0.5f, clip.duration * 0.75f)
        for (t in times) {
            h.resetNormalizedPose()
            val pose = HashMap<String, PoseTransform>()
            for (track in clip.tracks) {
                if (!track.name.endsWith(".quaternion")) continue
                val bone = track.name.removeSuffix(".quaternion")
                if (h.getNormalizedBoneNode(bone) == null) continue
                val p = PoseTransform(); p.rotation = sampleRotation(track, t)
                pose[bone] = p
            }
            h.setNormalizedPose(pose)
            h.update()

            val hip = world(h, store, "hips")
            val la = world(h, store, "leftUpperArm")
            val lh = world(h, store, "leftHand")
            val lf = world(h, store, "leftFoot")
            println("twist t=${"%.2f".format(t)} hips=(%.2f,%.2f,%.2f) LUpperArm=(%.2f,%.2f,%.2f) LHand=(%.2f,%.2f,%.2f) LFoot=(%.2f,%.2f,%.2f)".format(
                hip.x,hip.y,hip.z, la.x,la.y,la.z, lh.x,lh.y,lh.z, lf.x,lf.y,lf.z))
        }
        // compare: is LHand above shoulders (y > 1.2 ~ head) = "U arms up", or near hip (y ~ 0.9) = natural
    }
}
