package dev.vrm.runtime.core.mtoon

import dev.vrm.runtime.core.vrm.VrmLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M5 acceptance: MToon material parameters parsed from the real Seed-san VRM.
 */
class MtoonTest {

    private fun seedBytes(): ByteArray {
        val url = javaClass.classLoader.getResource("fixtures/Seed-san.vrm")
        assertNotNull(url, "fixture Seed-san.vrm not found")
        return File(url!!.toURI()).readBytes()
    }

    @Test
    fun `loads 10 mtoon materials from seed-san`() {
        val vrm = VrmLoader.load(seedBytes())
        assertEquals(10, vrm.mtoon.size, "seed-san declares 10 mtoon materials")

        val loader = MtoonLoader(vrm.gltf, vrm.mtoon)
        val all = loader.loadAll()
        assertEquals(10, all.size)
        // material indices with mtoon: 0,1,2,3,4,5,6,8,10,11
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 8, 10, 11), all.keys.sorted())
    }

    @Test
    fun `non-mtoon material returns null`() {
        val vrm = VrmLoader.load(seedBytes())
        val loader = MtoonLoader(vrm.gltf, vrm.mtoon)
        // material #16 has no mtoon extension
        assertNull(loader.load(16), "material 16 should not be MToon")
    }

    @Test
    fun `hair material parameters parsed correctly`() {
        val vrm = VrmLoader.load(seedBytes())
        val loader = MtoonLoader(vrm.gltf, vrm.mtoon)
        assertNotNull(loader.load(0), "material 0 is hair (mtoon)")
        val p = loader.load(0)!!

        // transparentWithZWrite=false, renderQueueOffset=0
        assertEquals(false, p.transparentWithZWrite)
        assertEquals(0, p.renderQueueOffsetNumber)

        // shadeColorFactor [0.301, 0.301, 0.301] kept as-is (three-vrm doesn't convert)
        assertEquals(0.3012f, p.shadeColorFactor[0], 1e-3f, "raw value preserved")
        // shading shift / toony
        assertEquals(-0.05f, p.shadingShiftFactor, 1e-4f)
        assertEquals(0.95f, p.shadingToonyFactor, 1e-4f)
        assertEquals(0.9f, p.giEqualizationFactor, 1e-4f)
        // outline
        assertEquals(OutlineWidthMode.WORLD_COORDINATES, p.outlineWidthMode)
        assertEquals(0.0005f, p.outlineWidthFactor, 1e-6f)
        assertEquals(0f, p.outlineColorFactor[0], 1e-4f)
        assertTrue(p.shouldGenerateOutline, "worldCoordinates outline with width>0 should generate outline")
    }

    @Test
    fun `eye material has no outline`() {
        val vrm = VrmLoader.load(seedBytes())
        val loader = MtoonLoader(vrm.gltf, vrm.mtoon)
        assertNotNull(loader.load(3), "material 3 is eye (mtoon)")
        val p = loader.load(3)!!

        assertEquals(OutlineWidthMode.NONE, p.outlineWidthMode)
        // even though outlineWidthFactor is 0.5, mode none -> no outline
        assertTrue(!p.shouldGenerateOutline, "outlineWidthMode none should not generate outline")
    }

    @Test
    fun `render order computed from zwrite and offset`() {
        val vrm = VrmLoader.load(seedBytes())
        val loader = MtoonLoader(vrm.gltf, vrm.mtoon)

        // transparentWithZWrite=false -> 19 + 0
        val hair = loader.load(0)!!
        assertEquals(19, hair.renderOrder)

        // a hypothetical zwrite-on material -> 0 + offset
        val p = MtoonMaterialParameters()
        p.transparentWithZWrite = true
        p.renderQueueOffsetNumber = 3
        assertEquals(3, p.renderOrder)

        p.transparentWithZWrite = false
        p.renderQueueOffsetNumber = -2
        assertEquals(17, p.renderOrder)
    }

    @Test
    fun `sRGB to linear conversion is opt-in`() {
        val vrm = VrmLoader.load(seedBytes())
        val loader = MtoonLoader(vrm.gltf, vrm.mtoon)

        // load with conversion ON
        val converted = loader.load(0, convertSRGBToLinear = true)!!
        val raw = loader.load(0, convertSRGBToLinear = false)!!

        val s = raw.shadeColorFactor[0] // sRGB 0.3012
        assertEquals(0.3012f, s, 1e-3f)
        // linearized value should be lower than sRGB
        assertTrue(converted.shadeColorFactor[0] < s, "linear value should be smaller for midtones")
        // verify the math: ((0.3012+0.055)/1.055)^2.4
        val expected = Math.pow(((0.3012 + 0.055) / 1.055).toDouble(), 2.4).toFloat()
        assertEquals(expected, converted.shadeColorFactor[0], 1e-4f)
    }
}
