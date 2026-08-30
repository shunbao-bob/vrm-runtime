package dev.vrm.runtime.core.vrma

import dev.vrm.runtime.core.expression.ExpressionLoader
import dev.vrm.runtime.core.expression.GltfExpressionBindProvider
import dev.vrm.runtime.core.humanoid.GltfNodeTransformStore
import dev.vrm.runtime.core.humanoid.VRMHumanoid
import dev.vrm.runtime.core.vrm.VrmLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * M3 acceptance: VRMA animation parsing + retargeting.
 *
 * Uses a synthetic in-memory VRMA (see [SyntheticVrma]) so the tests are fully
 * self-contained. The VRMA has one animation with a head rotation, a hips
 * translation, and a `happy` expression weight track, over a
 * hips->spine->head hierarchy with hips at [0,1,0].
 *
 *  - [loads head rotation humanoid track] / [loads hips translation...] /
 *    [loads happy...] verify the parsed [VrmAnimation] directly.
 *  - [retargets onto seed-san avatar] exercises [VRMAnimationClipBuilder]
 *    against the real Seed-san humanoid + expression manager.
 */
class VrmAnimationLoaderTest {

    private fun vrmaBytes(): ByteArray = SyntheticVrma.buildVrma()

    private fun parseVrma(bytes: ByteArray): LoadedVrma {
        val vrm = VrmLoader.load(bytes)
        val ext = vrm.vrmAnimation
            ?: throw AssertionError("synthetic VRMA must carry VRMC_vrm_animation")
        val bin = vrm.binary?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
        return LoadedVrma(vrm.gltf, bin, ext)
    }

    private class LoadedVrma(
        val gltf: dev.vrm.runtime.core.gltf.Gltf,
        val bin: ByteBuffer?,
        val ext: VrmcVrmAnimation,
    )

    private fun seedSanBytes(): ByteArray {
        val url = javaClass.classLoader.getResource("fixtures/Seed-san.vrm")
        assertNotNull(url, "fixture Seed-san.vrm not found")
        return File(url!!.toURI()).readBytes()
    }

    @Test
    fun `loads head rotation humanoid track`() {
        val vrma = parseVrma(vrmaBytes())
        val loader = VrmAnimationLoader(vrma.gltf, vrma.bin, vrma.ext)
        val animations = loader.loadAll()
        assertEquals(1, animations.size, "one animation")

        val anim = animations[0]
        assertEquals(1.0f, anim.duration, 1e-5f)
        assertEquals(1f, anim.restHipsPosition.y, 1e-5f, "hips rest at y=1")

        val headRot = anim.humanoidTracks.rotation["head"]
        assertNotNull(headRot, "head rotation track expected")
        assertEquals(3, headRot!!.keyCount)
        assertEquals(4, headRot.valueStride)
        // identity rest transforms for non-hips -> baked keyframes equal source
        assertEquals(0.70710677f, headRot.values[6], 1e-5f, "key1 z")
        assertEquals(0.70710677f, headRot.values[7], 1e-5f, "key1 w")
    }

    @Test
    fun `loads hips translation and bakes to parent space`() {
        val vrma = parseVrma(vrmaBytes())
        val loader = VrmAnimationLoader(vrma.gltf, vrma.bin, vrma.ext)
        val anim = loader.loadAll().first()

        val hipsTrans = anim.humanoidTracks.translation["hips"]
        assertNotNull(hipsTrans, "hips translation track expected")
        assertEquals(3, hipsTrans!!.keyCount)
        assertEquals(3, hipsTrans.valueStride)
        // hips has no parent -> parent world is identity -> values unchanged
        assertEquals(0.1f, hipsTrans.values[4], 1e-5f) // key1 y
        assertEquals(0.2f, hipsTrans.values[7], 1e-5f) // key2 y
    }

    @Test
    fun `loads happy as preset expression weight track`() {
        val vrma = parseVrma(vrmaBytes())
        val loader = VrmAnimationLoader(vrma.gltf, vrma.bin, vrma.ext)
        val anim = loader.loadAll().single()

        assertNotNull(anim.expressionTracks.preset["happy"], "happy is a preset expression")
        assertTrue(anim.expressionTracks.custom.isEmpty(), "no custom expressions")
        val track = anim.expressionTracks.preset["happy"]!!
        assertEquals(3, track.keyCount)
        assertEquals(1, track.valueStride, "weight is scalar")
        // weight = x component of each VEC3
        assertEquals(0f, track.values[0], 1e-5f)
        assertEquals(0.5f, track.values[1], 1e-5f)
        assertEquals(1f, track.values[2], 1e-5f)
    }

    @Test
    fun `retargets onto seed-san avatar humanoid and expression manager`() {
        // ---- target avatar ----
        val seed = VrmLoader.load(seedSanBytes())
        val seedVrm = seed.vrm ?: throw AssertionError("seed-san must carry VRMC_vrm")
        val humanoid = VRMHumanoid.fromVrm(
            seed.gltf,
            seedVrm,
            GltfNodeTransformStore.fromGltfNodes(seed.gltf.nodes),
        )
        val exprManager = ExpressionLoader(GltfExpressionBindProvider(seed.gltf))
            .load(seed.gltf, seedVrm)!!

        // ---- VRMA animation ----
        val vrma = parseVrma(vrmaBytes())
        val vrmaAnimation = VrmAnimationLoader(vrma.gltf, vrma.bin, vrma.ext).loadAll().single()

        // ---- retarget ----
        val clip = VRMAnimationClipBuilder(vrmaAnimation, humanoid, exprManager).build()

        assertEquals(1.0f, clip.duration, 1e-5f)
        val trackNames = clip.tracks.map { it.name }

        // head rotation remapped to the avatar's normalized head node
        assertTrue(
            trackNames.any { it.startsWith("head") && it.endsWith(".quaternion") },
            "expected a head.quaternion track, got $trackNames",
        )

        // hips position retargeted: scaled by avatar normalized hips y / animation y (=1)
        val animY = vrmaAnimation.restHipsPosition.y
        val humanoidY = humanoid.getNormalizedBoneNode("hips")!!.position.y
        val scale = humanoidY / animY
        val hipsTrack = clip.tracks.first { it.name.endsWith(".position") }
        // source key1 y = 0.1 -> scaled
        assertEquals(0.1f * scale, hipsTrack.values[4], 1e-5f)

        // happy expression weight remapped to the expression track name
        assertTrue(
            trackNames.any { it == "happy.weight" },
            "expected a happy.weight track, got $trackNames",
        )
    }
}
