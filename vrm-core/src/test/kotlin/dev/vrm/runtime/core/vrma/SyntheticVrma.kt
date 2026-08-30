package dev.vrm.runtime.core.vrma

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal GLB writers used only by tests to synthesize a VRMA byte array and a
 * tiny avatar VRM with known joint structure. Keeps the tests self-contained so
 * we don't depend on a sample `.vrma` file on disk.
 *
 * The VRMA carries one animation:
 *  - head (node 2) rotation, 3 quaternion keyframes
 *  - hips (node 0) translation, 3 VEC3 keyframes
 *  - preset expression `happy` (node 3) weight track (x of a VEC3)
 * Rest transforms are identity except hips (translation [0,1,0]) so
 * restHipsPosition.y = 1 exercises the vertical-scale retarget path.
 */
object SyntheticVrma {

    // ---- VRMA data ----
    private val times = floatArrayOf(0f, 0.5f, 1f)                                            // 3
    private val headRot = floatArrayOf(
        0f, 0f, 0f, 1f,
        0f, 0f, 0.70710677f, 0.70710677f,
        0f, 0f, 0f, 1f,
    )                                                                                          // 12
    private val hipsTrans = floatArrayOf(
        0f, 0f, 0f,
        0f, 0.1f, 0f,
        0f, 0.2f, 0f,
    )                                                                                          // 9
    private val happyWeights = floatArrayOf(
        0f, 0f, 0f,
        0.5f, 0f, 0f,
        1f, 0f, 0f,
    )                                                                                          // 9

    /** Build the synthetic VRMA as a GLB byte array. */
    fun buildVrma(): ByteArray {
        // buffer layout (floats -> bytes)
        val tOff = 0
        val hrOff = times.size * 4
        val htOff = hrOff + headRot.size * 4
        val hwOff = htOff + hipsTrans.size * 4
        val bin = floatBuffer(*times, *headRot, *hipsTrans, *happyWeights)

        val accessors = """[
          {"bufferView":0,"componentType":5126,"count":3,"type":"SCALAR"},
          {"bufferView":1,"componentType":5126,"count":3,"type":"VEC4"},
          {"bufferView":2,"componentType":5126,"count":3,"type":"VEC3"},
          {"bufferView":3,"componentType":5126,"count":3,"type":"VEC3"}
        ]"""

        val gltf = """{
          "asset": {"version": "2.0"},
          "extensionsUsed": ["VRMC_vrm_animation"],
          "scene": 0,
          "scenes": [{"nodes":[0,1,2]}],
          "nodes": [
            {"name":"hips","translation":[0,1,0],"children":[1]},
            {"name":"spine","children":[2]},
            {"name":"head"},
            {"name":"happy"}
          ],
          "buffers": [{"byteLength": ${bin.size}}],
          "bufferViews": [
            {"buffer":0,"byteOffset":$tOff,"byteLength":${times.size*4}},
            {"buffer":0,"byteOffset":$hrOff,"byteLength":${headRot.size*4}},
            {"buffer":0,"byteOffset":$htOff,"byteLength":${hipsTrans.size*4}},
            {"buffer":0,"byteOffset":$hwOff,"byteLength":${happyWeights.size*4}}
          ],
          "accessors": $accessors,
          "animations": [{
            "channels": [
              {"sampler":0,"target":{"node":2,"path":"rotation"}},
              {"sampler":1,"target":{"node":0,"path":"translation"}},
              {"sampler":2,"target":{"node":3,"path":"translation"}}
            ],
            "samplers": [
              {"input":0,"output":1,"interpolation":"LINEAR"},
              {"input":0,"output":2,"interpolation":"LINEAR"},
              {"input":0,"output":3,"interpolation":"LINEAR"}
            ]
          }],
          "extensions": {
            "VRMC_vrm_animation": {
              "specVersion": "1.0",
              "humanoid": {"humanBones": {
                "hips": {"node":0},
                "spine": {"node":1},
                "head": {"node":2}
              }},
              "expressions": {"preset": {"happy": {"node":3}}}
            }
          }
        }"""
        return writeGlb(gltf, bin)
    }


    // ---- helpers ----

    private fun floatBuffer(vararg data: Float): ByteArray {
        val b = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        data.forEach { b.putFloat(it) }
        return b.array()
    }

    private fun writeGlb(jsonText: String, bin: ByteArray): ByteArray {
        val jsonBytes = jsonText.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        val jsonLen = pad4(jsonBytes.size)
        val binLen = pad4(bin.size)
        out.write(int32(0x46546C67)) // glTF
        out.write(int32(2))
        out.write(int32(12 + 8 + jsonLen + 8 + binLen))
        out.write(int32(jsonLen))
        out.write(int32(0x4E4F534A)) // JSON
        out.write(jsonBytes)
        // GLB spec: JSON chunk padding must be spaces (0x20), not nulls
        writePad(out, jsonBytes.size, space = true)
        out.write(int32(binLen))
        out.write(int32(0x004E4942)) // BIN\0
        out.write(bin)
        writePad(out, bin.size, space = false)
        return out.toByteArray()
    }

    private fun pad4(n: Int): Int = (n + 3) and 0x7FFFFFFC

    private fun writePad(out: ByteArrayOutputStream, len: Int, space: Boolean) {
        val rem = (4 - (len % 4)) % 4
        repeat(rem) { out.write(if (space) 0x20 else 0) }
    }

    private fun int32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}
