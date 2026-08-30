package dev.vrm.runtime.core

import dev.vrm.runtime.core.gltf.GlbParser
import dev.vrm.runtime.core.vrm.VrmLoader
import dev.vrm.runtime.core.vrm.VrmcSpringBone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M0 acceptance: parse the official Seed-san VRM sample and assert the
 * humanoid bone mapping and springBone extension are present.
 */
class VrmLoadingTest {

    private fun loadFixture(): ByteArray {
        val url = javaClass.classLoader.getResource("fixtures/Seed-san.vrm")
        assertNotNull(url, "fixture Seed-san.vrm not found on test classpath")
        val file = File(url!!.toURI())
        return file.readBytes()
    }

    @Test
    fun `glb parser reads header and json chunk`() {
        val bytes = loadFixture()
        val container = GlbParser.parse(bytes)

        assertTrue(container.json.contains("\"asset\""), "JSON chunk should contain glTF asset")
        assertNotNull(container.bin, "GLB should have a BIN chunk")
    }

    @Test
    fun `vrm loader reads humanoid humanBones non-empty`() {
        val bytes = loadFixture()
        val vrm = VrmLoader.load(bytes)

        assertNotNull(vrm.vrm, "Seed-san must carry VRMC_vrm")
        val humanBones = vrm.vrm!!.humanoid.humanBones
        assertTrue(humanBones.isNotEmpty(), "humanBones must be non-empty")

        // The 3 required core bones per VRM 1.0 spec
        assertTrue(humanBones.containsKey("hips"), "hips bone missing")
        assertTrue(humanBones.containsKey("spine"), "spine bone missing")
        assertTrue(humanBones.containsKey("head"), "head bone missing")
    }

    @Test
    fun `vrm loader reads springbone extension`() {
        val bytes = loadFixture()
        val vrm = VrmLoader.load(bytes)

        val springBone: VrmcSpringBone? = vrm.springBone
        assertNotNull(springBone, "Seed-san should carry VRMC_springBone")
        assertTrue(springBone!!.springs.isNullOrEmpty().not(), "springBone should declare springs")
    }

    @Test
    fun `vrm loader reads expressions preset`() {
        val bytes = loadFixture()
        val vrm = VrmLoader.load(bytes)

        val expressions = vrm.vrm!!.expressions
        assertNotNull(expressions, "Seed-san should carry expressions")
        val preset = expressions!!.preset
        assertTrue(preset.isNullOrEmpty().not(), "preset expressions should exist")
        assertTrue(preset!!.containsKey("happy"), "happy preset expected")
    }
}
