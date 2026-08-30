package dev.vrm.runtime.core.gltf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Minimal glTF 2.0 JSON model, covering the fields needed for VRM semantics and
 * for later binding to Filament's gltfio.
 *
 * Everything is optional and nullable except where the spec mandates a value,
 * so that unusual-but-legal models still parse.
 */
@Serializable
data class Gltf(
    val asset: Asset = Asset(),
    val scene: Int? = null,
    val scenes: List<Scene>? = null,
    val nodes: List<Node>? = null,
    val meshes: List<Mesh>? = null,
    val skins: List<Skin>? = null,
    val animations: List<Animation>? = null,
    val materials: List<Material>? = null,
    val buffers: List<Buffer>? = null,
    val bufferViews: List<BufferView>? = null,
    val accessors: List<Accessor>? = null,
    val images: List<Image>? = null,
    val textures: List<Texture>? = null,
    val samplers: List<Sampler>? = null,
    val cameras: List<Camera>? = null,
    val extensionsUsed: List<String>? = null,
    val extensionsRequired: List<String>? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class Asset(
    val version: String = "2.0",
    val generator: String? = null,
    val copyright: String? = null,
    val minVersion: String? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class Scene(
    val name: String? = null,
    val nodes: List<Int>? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class Node(
    val name: String? = null,
    val mesh: Int? = null,
    val skin: Int? = null,
    val camera: Int? = null,
    val children: List<Int>? = null,
    val matrix: List<Float>? = null,
    val translation: List<Float>? = null,
    val rotation: List<Float>? = null,
    val scale: List<Float>? = null,
    val weights: List<Float>? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
) {
    val translationOrNull: FloatArray?
        get() = translation?.toFloatArray()
    val rotationOrNull: FloatArray?
        get() = rotation?.toFloatArray()
    val scaleOrNull: FloatArray?
        get() = scale?.toFloatArray()
    val matrixOrNull: FloatArray?
        get() = matrix?.toFloatArray()
}

@Serializable
data class Mesh(
    val name: String? = null,
    val primitives: List<Primitive> = emptyList(),
    val weights: List<Float>? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class Primitive(
    val attributes: Map<String, Int> = emptyMap(),
    val indices: Int? = null,
    val material: Int? = null,
    val mode: Int = 4, // 4 = TRIANGLES
    val targets: List<Map<String, Int>>? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class Skin(
    val name: String? = null,
    val inverseBindMatrices: Int? = null,
    val skeleton: Int? = null,
    val joints: List<Int> = emptyList(),
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class Material(
    val name: String? = null,
    val pbrMetallicRoughness: PbrMetallicRoughness? = null,
    val normalTexture: TextureInfo? = null,
    val occlusionTexture: TextureInfo? = null,
    val emissiveTexture: TextureInfo? = null,
    val emissiveFactor: List<Float>? = null,
    val alphaMode: String? = null,
    val alphaCutoff: Float? = null,
    val doubleSided: Boolean? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class PbrMetallicRoughness(
    val baseColorFactor: List<Float>? = null,
    val baseColorTexture: TextureInfo? = null,
    val metallicFactor: Float? = null,
    val roughnessFactor: Float? = null,
    val metallicRoughnessTexture: TextureInfo? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class TextureInfo(
    val index: Int? = null,
    val texCoord: Int = 0,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class Buffer(
    val uri: String? = null,
    val byteLength: Int,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class BufferView(
    val buffer: Int,
    val byteOffset: Int = 0,
    val byteLength: Int,
    val byteStride: Int? = null,
    val target: Int? = null,
    val name: String? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class Accessor(
    val bufferView: Int? = null,
    val byteOffset: Int = 0,
    val componentType: Int,
    val normalized: Boolean = false,
    val count: Int,
    val type: String,
    val max: List<Float>? = null,
    val min: List<Float>? = null,
    val sparse: Sparse? = null,
    val name: String? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class Sparse(
    val count: Int,
    val indices: SparseIndices,
    val values: SparseValues,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class SparseIndices(
    val bufferView: Int,
    val byteOffset: Int = 0,
    val componentType: Int,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class SparseValues(
    val bufferView: Int,
    val byteOffset: Int = 0,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class Image(
    val uri: String? = null,
    val mimeType: String? = null,
    val bufferView: Int? = null,
    val name: String? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class Texture(
    val sampler: Int? = null,
    val source: Int? = null,
    val name: String? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class Sampler(
    val magFilter: Int? = null,
    val minFilter: Int? = null,
    val wrapS: Int = 10497,
    val wrapT: Int = 10497,
    val name: String? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class Camera(
    val type: String,
    val name: String? = null,
    val perspective: CameraPerspective? = null,
    val orthographic: CameraOrthographic? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class CameraPerspective(
    val aspectRatio: Float? = null,
    val yfov: Float,
    val zfar: Float? = null,
    val znear: Float,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class CameraOrthographic(
    val xmag: Float,
    val ymag: Float,
    val zfar: Float,
    val znear: Float,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

/**
 * glTF 2.0 animation (used for VRMA playback).
 */
@Serializable
data class Animation(
    val name: String? = null,
    val channels: List<AnimationChannel>? = null,
    val samplers: List<AnimationSampler>? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class AnimationChannel(
    val sampler: Int,
    val target: AnimationChannelTarget,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class AnimationChannelTarget(
    val node: Int? = null,
    val path: String,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class AnimationSampler(
    val input: Int,
    val output: Int,
    val interpolation: String = "LINEAR",
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

// Common glTF semantic keys
object GltfSemantics {
    const val POSITION = "POSITION"
    const val NORMAL = "NORMAL"
    const val TANGENT = "TANGENT"
    const val TEXCOORD_0 = "TEXCOORD_0"
    const val TEXCOORD_1 = "TEXCOORD_1"
    const val COLOR_0 = "COLOR_0"
    const val JOINTS_0 = "JOINTS_0"
    const val WEIGHTS_0 = "WEIGHTS_0"
}

// Accessor component types
object ComponentType {
    const val BYTE = 5120
    const val UNSIGNED_BYTE = 5121
    const val SHORT = 5122
    const val UNSIGNED_SHORT = 5123
    const val UNSIGNED_INT = 5125
    const val FLOAT = 5126
}

// Animation interpolation modes
object Interpolation {
    const val LINEAR = "LINEAR"
    const val STEP = "STEP"
    const val CUBICSPLINE = "CUBICSPLINE"
}
