package dev.vrm.runtime.core.gltf

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A parsed GLB (binary glTF) file: the JSON chunk as text plus the binary buffer.
 *
 * GLB layout:
 *  - 12-byte header: magic(0x46546C67="glTF") + version(uint32, must be 2) + length(uint32)
 *  - chunks, each: chunkLength(uint32) + chunkType(uint32) + chunkData
 *    - 0x4E4F534A ("JSON") : glTF JSON text
 *    - 0x004E4942 ("BIN\0") : binary buffer
 */
class GlbContainer(
    val json: String,
    val bin: ByteBuffer?,
)

object GlbParser {
    private const val MAGIC_GLTF: Int = 0x46546C67 // "glTF"
    private const val CHUNK_TYPE_JSON: Int = 0x4E4F534A // "JSON"
    private const val CHUNK_TYPE_BIN: Int = 0x004E4942 // "BIN\0"

    /**
     * Parse a GLB byte array.
     *
     * @throws GlbParseException if the header is invalid or the container is not a GLB 2.0.
     */
    fun parse(data: ByteArray): GlbContainer {
        // A GLB needs at least the 12-byte header; fail with a clear message
        // instead of an opaque BufferUnderflowException on a truncated/empty buffer.
        if (data.size < 12) {
            throw GlbParseException(
                "Not a valid GLB: only ${data.size} byte(s), expected at least a 12-byte header"
            )
        }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buffer.int
        if (magic != MAGIC_GLTF) {
            throw GlbParseException("Not a GLB file: magic=0x${magic.toString(16)} (expected 0x46546C67)")
        }
        val version = buffer.int
        if (version != 2) {
            throw GlbParseException("Unsupported GLB version: $version (expected 2)")
        }
        val declaredLength = buffer.int
        if (declaredLength != data.size) {
            throw GlbParseException("GLB length mismatch: header says $declaredLength, actual ${data.size}")
        }

        var json: String? = null
        var bin: ByteBuffer? = null

        while (buffer.remaining() >= 8) {
            val chunkLength = buffer.int
            val chunkType = buffer.int
            if (chunkLength < 0 || buffer.remaining() < chunkLength) {
                throw GlbParseException("GLB chunk length $chunkLength exceeds remaining bytes ${buffer.remaining()}")
            }
            val chunkBytes = ByteArray(chunkLength)
            buffer.get(chunkBytes)
            when (chunkType) {
                CHUNK_TYPE_JSON -> json = chunkBytes.toString(Charsets.UTF_8)
                CHUNK_TYPE_BIN -> bin = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
                else -> { /* ignore unknown chunks */ }
            }
        }

        val jsonText = json ?: throw GlbParseException("GLB has no JSON chunk")
        return GlbContainer(jsonText, bin)
    }
}

class GlbParseException(message: String) : RuntimeException(message)
