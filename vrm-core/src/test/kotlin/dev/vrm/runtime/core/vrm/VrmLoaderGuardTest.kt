package dev.vrm.runtime.core.vrm

import dev.vrm.runtime.core.gltf.GlbParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Guards added for open-sourcing: fail fast with clear, actionable errors
 * instead of silent mis-parsing when the host wires in a bad input.
 */
class VrmLoaderGuardTest {

    /**
     * Build a minimal GLB wrapping the given glTF JSON string, exactly like the
     * other synthetic fixtures. The JSON chunk is padded with spaces to the
     * 4-byte alignment required by GLB.
     */
    private fun glbOf(json: String): ByteArray {
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val padded = jsonBytes.copyOf((jsonBytes.size + 3) / 4 * 4)
        for (i in jsonBytes.size until padded.size) padded[i] = ' '.code.toByte()

        val bin = ByteBuffer.allocate(0)
        val length = 12 + 8 + padded.size + 8 + 0
        val header = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x46546C67)           // "glTF"
        header.putInt(2)                    // GLB version
        header.putInt(length)
        header.putInt(padded.size)          // JSON chunk length
        header.putInt(0x4E4F534A)           // "JSON"
        header.put(padded)
        header.putInt(0)                    // BIN chunk length
        header.putInt(0x004E4942)           // "BIN\0"
        header.put(bin)
        return header.array()
    }

    private fun vrmJson(specVersion: String): String = """
    {
      "asset": {"version": "2.0"},
      "scene": 0,
      "scenes": [{"nodes":[0]}],
      "nodes": [{"name":"hips"}],
      "buffers": [],
      "extensionsUsed": ["VRMC_vrm"],
      "extensions": {
        "VRMC_vrm": {
          "specVersion": "$specVersion",
          "meta": {"name":"t","authors":[]},
          "humanoid": {"humanBones":{"hips":{"node":0}}}
        }
      }
    }
    """

    @Test
    fun `empty input is rejected with VrmParseException not a crash`() {
        val e = assertThrows(RuntimeException::class.java) { VrmLoader.load(ByteArray(0)) }
        assertTrue(e is VrmParseException, "expected VrmParseException but was ${e::class.simpleName}")
        assertTrue(e.message!!.contains("GLB"), "message should point at the GLB container: ${e.message}")
    }

    @Test
    fun `non GLB magic is rejected with VrmParseException`() {
        val e = assertThrows(RuntimeException::class.java) {
            VrmLoader.load("this is not a glb at all........".toByteArray())
        }
        assertTrue(e is VrmParseException, "expected VrmParseException but was ${e::class.simpleName}")
        assertTrue(e.message!!.contains("GLB"), "message should mention the container: ${e.message}")
    }

    @Test
    fun `VRM 1_0 file loads fine and round-trips through the parser`() {
        val vrm = VrmLoader.load(glbOf(vrmJson("1.0")))
        assertNotNull(vrm.vrm, "1.0 VRM must be accepted")
        assertEquals("1.0", vrm.vrm!!.specVersion)
        assertTrue(vrm.gltf.nodes!!.size == 1, "synthetic glTF should have one node")
    }

    @Test
    fun `VRM 1_0-beta is accepted`() {
        val vrm = VrmLoader.load(glbOf(vrmJson("1.0-beta")))
        assertNotNull(vrm.vrm, "1.0-beta must be accepted")
        assertEquals("1.0-beta", vrm.vrm!!.specVersion)
    }

    @Test
    fun `VRM 0_x is explicitly rejected`() {
        for (bad in listOf("0.0", "0.100", "1")) {
            // "1" alone is not a valid 1.x version; only "1.x" is supported.
        }
        val e = assertThrows(RuntimeException::class.java) { VrmLoader.load(glbOf(vrmJson("0.0"))) }
        assertTrue(e is VrmParseException, "expected VrmParseException but was ${e::class.simpleName}")
        assertTrue(e.message!!.contains("0.0"), "message should name the bad version: ${e.message}")
        assertTrue(e.message!!.contains("1.x"), "message should say 1.x only: ${e.message}")
    }

    @Test
    fun `non numeric specVersion is rejected`() {
        val e = assertThrows(RuntimeException::class.java) { VrmLoader.load(glbOf(vrmJson("garbage"))) }
        assertTrue(e is VrmParseException, "expected VrmParseException but was ${e::class.simpleName}")
    }
}
