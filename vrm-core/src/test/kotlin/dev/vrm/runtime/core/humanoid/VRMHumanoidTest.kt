package dev.vrm.runtime.core.humanoid

import dev.vrm.runtime.core.gltf.Gltf
import dev.vrm.runtime.core.vrm.VRMCVrm
import dev.vrm.runtime.core.vrm.VrmHumanoid
import dev.vrm.runtime.core.vrm.VrmLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M1 acceptance: humanoid bone mapping, required-bone validation and normalized rig.
 */
class VRMHumanoidTest {

    private fun loadFixture(): Pair<Gltf, VRMCVrm> {
        val url = javaClass.classLoader.getResource("fixtures/Seed-san.vrm")
        val file = File(url!!.toURI())
        val vrm = VrmLoader.load(file.readBytes())
        return vrm.gltf to vrm.vrm!!
    }

    @Test
    fun `humanoid maps seed-san bones`() {
        val (gltf, vrmSchema) = loadFixture()
        val humanoid = VRMHumanoid.fromVrm(gltf, vrmSchema)

        assertTrue(humanoid.rawHumanBones.size >= 17, "should include at least required bones")
        assertEquals(3, humanoid.getRawBoneNodeIndex("hips"))
        assertEquals(45, humanoid.getRawBoneNodeIndex("head"))
    }

    @Test
    fun `required bones validated - missing hips throws`() {
        val (gltf, vrmSchema) = loadFixture()
        val schemaHumanoid = vrmSchema.humanoid
        val broken = VrmHumanoid(
            humanBones = schemaHumanoid.humanBones.toMutableMap().apply { remove("hips") },
        )
        val brokenVrm = VRMCVrm(
            specVersion = "1.0",
            meta = vrmSchema.meta,
            humanoid = broken,
            firstPerson = vrmSchema.firstPerson,
            lookAt = vrmSchema.lookAt,
            expressions = vrmSchema.expressions,
        )
        val ex = assertThrows(MissingHumanoidBoneException::class.java) {
            VRMHumanoid.fromVrm(gltf, brokenVrm)
        }
        // only 'hips' was removed -> it should be the sole missing required bone
        assertEquals(listOf("hips"), ex.missingBones)
    }

    @Test
    fun `getRawBoneNodeIndex returns null for unknown bone`() {
        val (gltf, vrmSchema) = loadFixture()
        val humanoid = VRMHumanoid.fromVrm(gltf, vrmSchema)
        assertNull(humanoid.getRawBoneNodeIndex("nonexistent"))
    }

    @Test
    fun `fromVrm builds normalized rig`() {
        val (gltf, vrmSchema) = loadFixture()
        val humanoid = VRMHumanoid.fromVrm(gltf, vrmSchema)

        assertNotNull(humanoid.normalizedRoot)
        assertNotNull(humanoid.getNormalizedBoneNode("hips"))
        assertNotNull(humanoid.getNormalizedBoneNode("head"))
        assertNotNull(humanoid.getNormalizedBoneNode("spine"))
    }
}