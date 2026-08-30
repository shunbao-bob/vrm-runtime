package dev.vrm.runtime.core.vrma

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Diagnose why xlunar's real test.vrma only produces 1-3 tracks on Seed-san.
 * Parses channel-by-channel and reports which step drops each channel.
 */
class RealVrmaDiagnosticTest {

    private fun loadVrma(path: String): dev.vrm.runtime.core.vrm.Vrm {
        val bytes = File(path).readBytes()
        return dev.vrm.runtime.core.vrm.VrmLoader.load(bytes)
    }

    @Test
    fun `diagnose test vrma channel parsing`() {
        val path = "../app/src/main/assets/animations/test.vrma"
        val f = File(path)
        assertTrue(f.exists(), "test.vrma must exist at $path")
        val vrm = loadVrma(path)
        val ext = requireNotNull(vrm.vrmAnimation) { "no VRMC_vrm_animation" }

        val gltf = vrm.gltf
        val bin = vrm.binary?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
        val loader = VrmAnimationLoader(gltf, bin, ext)
        val anim = loader.loadAll().firstOrNull()
        println("animations=${gltf.animations?.size}")
        println("rotationTracks=${anim?.humanoidTracks?.rotation?.size}")
        println("translationTracks=${anim?.humanoidTracks?.translation?.size}")
        println("expressionPreset=${anim?.expressionTracks?.preset?.keys}")
        println("expressionCustom=${anim?.expressionTracks?.custom?.keys}")
        println("lookAtTrack=${anim?.lookAtTrack != null}")
        println("duration=${anim?.duration}")

        // channel-by-channel: count how many reach each parse branch
        val humanChs = ArrayList<Pair<Int, String>>()
        gltf.animations?.forEach { a ->
            a.channels?.forEach { c ->
                val node = c.target.node ?: 0
                val path2 = c.target.path
                gltf.nodes?.getOrNull(node)?.let { n ->
                    humanChs += node to (n.name ?: node.toString())
                }
            }
        }
        println("total channels=${humanChs.size}")
        // check the node -> name mapping of humanBones in the extension
        val extHum = ext.humanoid?.humanBones ?: emptyMap()
        println("extension humanBones=${extHum.size}")
        extHum.forEach { (name, b) -> }
        val indexMap = HashMap<Int, String>()
        extHum.forEach { (name, b) -> indexMap[b.node] = name }
        println("indexMap entries=${indexMap.size}")
    }
}
