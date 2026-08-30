package dev.vrm.runtime.core.vrma

import dev.vrm.runtime.core.gltf.Accessor
import dev.vrm.runtime.core.gltf.BufferView
import dev.vrm.runtime.core.gltf.ComponentType
import dev.vrm.runtime.core.gltf.Gltf
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads glTF accessor data out of the GLB BIN chunk.
 *
 * Needed for animation playback: decode an animation sampler's input (scalar
 * times) and output (VEC3/VEC4 values) into plain FloatArrays. Supports the
 * component types and scalar types used by animation samplers, plus normalised
 * integer components and sparse accessors (decompressed into the dense array).
 *
 * The returned array is dense (no padding / no stride gaps) — index `i` of a
 * VECn accessor is component `i % n`, keyframe `i / n`.
 */
object AccessorReader {

    /**
     * Read the accessor at [accessorIndex] as a flat FloatArray.
     *
     * @param gltf the parsed glTF
     * @param bin the GLB BIN chunk (nullable for JSON-only glTF)
     * @param accessorIndex index into `gltf.accessors`
     * @throws VrmaAccessorException when the accessor is missing or unsupported
     */
    fun read(gltf: Gltf, bin: ByteBuffer?, accessorIndex: Int): FloatArray {
        val accessor = gltf.accessors?.getOrNull(accessorIndex)
            ?: throw VrmaAccessorException("Accessor #$accessorIndex not found")

        val componentCount = componentCount(accessor.type)

        // sparse accessors: build dense buffer from base + sparse overrides
        val sparse = accessor.sparse
        if (sparse != null) {
            return readSparse(gltf, bin, accessor, sparse, componentCount)
        }

        val bufferView = accessor.bufferView
            ?: throw VrmaAccessorException("Accessor #$accessorIndex has no bufferView (and is not sparse)")

        val view = gltf.bufferViews?.getOrNull(bufferView)
            ?: throw VrmaAccessorException("BufferView #$bufferView not found for accessor #$accessorIndex")

        val bytes = sliceBufferView(gltf, bin, view)
        return readComponents(
            bytes,
            accessor = accessor,
            offset = accessor.byteOffset,
            count = accessor.count,
            componentCount = componentCount,
            stride = view.byteStride ?: componentSize(accessor.componentType) * componentCount,
        )
    }

    private fun readSparse(
        gltf: Gltf,
        bin: ByteBuffer?,
        accessor: Accessor,
        sparse: dev.vrm.runtime.core.gltf.Sparse,
        componentCount: Int,
    ): FloatArray {
        val componentSize = componentSize(accessor.componentType)
        val bytesPerElement = componentSize * componentCount

        val dense = FloatArray(accessor.count * componentCount)

        // base values
        if (accessor.bufferView != null) {
            val view = gltf.bufferViews?.getOrNull(accessor.bufferView)
                ?: throw VrmaAccessorException("BufferView #${accessor.bufferView} not found")
            val bytes = sliceBufferView(gltf, bin, view)
            readComponentsInto(
                bytes, dense, accessor, accessor.byteOffset, accessor.count, componentCount,
                view.byteStride ?: bytesPerElement,
            )
        }

        // indices
        val indexView = gltf.bufferViews?.getOrNull(sparse.indices.bufferView)
            ?: throw VrmaAccessorException("Sparse indices bufferView #${sparse.indices.bufferView} not found")
        val indexBytes = sliceBufferView(gltf, bin, indexView)
        val indexStride = indexView.byteStride ?: componentSize(sparse.indices.componentType)
        val indices = IntArray(sparse.count)
        for (i in 0 until sparse.count) {
            indices[i] = readIndex(
                indexBytes,
                sparse.indices.byteOffset + i * indexStride,
                sparse.indices.componentType,
            )
        }

        // replacement values
        val valueView = gltf.bufferViews?.getOrNull(sparse.values.bufferView)
            ?: throw VrmaAccessorException("Sparse values bufferView #${sparse.values.bufferView} not found")
        val valueBytes = sliceBufferView(gltf, bin, valueView)
        val valueStride = valueView.byteStride ?: bytesPerElement

        for (i in 0 until sparse.count) {
            val target = indices[i]
            if (target < 0 || target >= accessor.count) continue
            val srcBase = sparse.values.byteOffset + i * valueStride
            val dstBase = target * componentCount
            for (c in 0 until componentCount) {
                dense[dstBase + c] = readComponent(valueBytes, srcBase + c * componentSize, accessor.componentType, accessor.normalized)
            }
        }

        return dense
    }

    private fun sliceBufferView(gltf: Gltf, bin: ByteBuffer?, view: BufferView): ByteBuffer {
        val buffer = gltf.buffers?.getOrNull(view.buffer)
            ?: throw VrmaAccessorException("Buffer #${view.buffer} not found")
        val whole = if (buffer.uri == null) {
            bin ?: throw VrmaAccessorException("GLB has no BIN chunk to read buffer #${view.buffer}")
        } else {
            throw VrmaAccessorException("External buffer URIs are not supported (buffer #${view.buffer})")
        }
        val start = view.byteOffset
        val end = start + view.byteLength
        if (start < 0 || end > whole.capacity()) {
            throw VrmaAccessorException("BufferView #${gltf.bufferViews!!.indexOf(view)} out of range [$start, $end) of buffer size ${whole.capacity()}")
        }
        // duplicate so we don't move the caller's position
        val dup = whole.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        dup.position(start)
        dup.limit(end)
        return dup.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun readComponents(
        bytes: ByteBuffer,
        accessor: Accessor,
        offset: Int,
        count: Int,
        componentCount: Int,
        stride: Int,
    ): FloatArray {
        val out = FloatArray(count * componentCount)
        readComponentsInto(bytes, out, accessor, offset, count, componentCount, stride)
        return out
    }

    private fun readComponentsInto(
        bytes: ByteBuffer,
        out: FloatArray,
        accessor: Accessor,
        offset: Int,
        count: Int,
        componentCount: Int,
        stride: Int,
    ) {
        val componentSize = componentSize(accessor.componentType)
        for (i in 0 until count) {
            val src = offset + i * stride
            val dst = i * componentCount
            for (c in 0 until componentCount) {
                out[dst + c] = readComponent(bytes, src + c * componentSize, accessor.componentType, accessor.normalized)
            }
        }
    }

    private fun readComponent(bytes: ByteBuffer, offset: Int, componentType: Int, normalized: Boolean): Float {
        return when (componentType) {
            ComponentType.BYTE -> {
                val v = bytes.get(offset).toInt()
                if (normalized) (v / 127f) else v.toFloat()
            }
            ComponentType.UNSIGNED_BYTE -> {
                val v = bytes.get(offset).toInt() and 0xFF
                if (normalized) (v / 255f) else v.toFloat()
            }
            ComponentType.SHORT -> {
                val v = bytes.getShort(offset).toInt()
                if (normalized) (v / 32767f) else v.toFloat()
            }
            ComponentType.UNSIGNED_SHORT -> {
                val v = bytes.getShort(offset).toInt() and 0xFFFF
                if (normalized) (v / 65535f) else v.toFloat()
            }
            ComponentType.UNSIGNED_INT -> {
                // glTF normalized unsigned int is rare; treat the raw bits as a float value.
                // For animation samplers the component type is always FLOAT anyway.
                (bytes.getInt(offset).toLong() and 0xFFFFFFFFL).toFloat()
            }
            ComponentType.FLOAT -> bytes.getFloat(offset)
            else -> throw VrmaAccessorException("Unsupported component type $componentType")
        }
    }

    private fun readIndex(bytes: ByteBuffer, offset: Int, componentType: Int): Int {
        return when (componentType) {
            ComponentType.UNSIGNED_BYTE -> bytes.get(offset).toInt() and 0xFF
            ComponentType.UNSIGNED_SHORT -> bytes.getShort(offset).toInt() and 0xFFFF
            ComponentType.UNSIGNED_INT -> bytes.getInt(offset)
            ComponentType.BYTE -> bytes.get(offset).toInt()
            ComponentType.SHORT -> bytes.getShort(offset).toInt()
            else -> throw VrmaAccessorException("Unsupported sparse index component type $componentType")
        }
    }

    private fun componentSize(componentType: Int): Int = when (componentType) {
        ComponentType.BYTE, ComponentType.UNSIGNED_BYTE -> 1
        ComponentType.SHORT, ComponentType.UNSIGNED_SHORT -> 2
        ComponentType.UNSIGNED_INT, ComponentType.FLOAT -> 4
        else -> throw VrmaAccessorException("Unsupported component type $componentType")
    }

    private fun componentCount(type: String): Int = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        else -> throw VrmaAccessorException("Unsupported accessor type '$type'")
    }

}

/** Thrown when a VRMA animation sampler's accessor data cannot be read. */
class VrmaAccessorException(message: String) : RuntimeException(message)
