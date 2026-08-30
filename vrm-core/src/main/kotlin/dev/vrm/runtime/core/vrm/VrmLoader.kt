package dev.vrm.runtime.core.vrm

import dev.vrm.runtime.core.gltf.GlbParseException
import dev.vrm.runtime.core.gltf.GlbParser
import dev.vrm.runtime.core.gltf.Gltf
import dev.vrm.runtime.core.vrma.VrmcVrmAnimation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * A fully loaded VRM: parsed glTF plus the VRMC extensions.
 *
 * This is the primary entry type consumers hold. `binary` is the raw BIN chunk
 * so that Filament's gltfio and manual accessor reads can share the same bytes.
 */
class Vrm(
    val gltf: Gltf,
    val json: String,
    val binary: ByteArray?,
    val vrm: VRMCVrm? = null,
    val vrmAnimation: VrmcVrmAnimation? = null,
    val springBone: VrmcSpringBone? = null,
    val mtoon: Map<Int, VrmcMaterialsMtoon> = emptyMap(),
    val extensionsUsed: Set<String> = emptySet(),
) {
    /** True if this file carries a VRMC_vrm (i.e. is a VRM avatar, not just springbone). */
    val hasHumanoid: Boolean get() = vrm != null

    /** True if this file is a VRM Animation (.vrma) carrying VRMC_vrm_animation. */
    val isVrma: Boolean get() = vrmAnimation != null
}

/**
 * Thrown when a file is not a valid VRM 1.0 (or can't be parsed).
 */
class VrmParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object VrmLoader {

    const val VRMC_VRM = "VRMC_vrm"
    const val VRMC_SPRING_BONE = "VRMC_springBone"
    const val VRMC_MTOON = "VRMC_materials_mtoon"
    const val VRMC_VRM_ANIMATION = "VRMC_vrm_animation"
    const val VRMC_MATERIALS_MTOON = "VRMC_materials_mtoon"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * Parse a VRM from raw GLB bytes.
     */
    fun load(data: ByteArray): Vrm {
        val container = try {
            GlbParser.parse(data)
        } catch (e: GlbParseException) {
            throw VrmParseException("Failed to parse GLB container: ${e.message}", e)
        }

        val root = try {
            json.decodeFromString<Gltf>(container.json)
        } catch (e: Exception) {
            throw VrmParseException("Failed to decode glTF JSON: ${e.message}", e)
        }

        val extensionsUsed = (root.extensionsUsed ?: emptyList()).toSet() + (root.extensionsRequired ?: emptyList()).toSet()

        val extensions = root.extensions ?: emptyMap()

        val vrm: VRMCVrm? = extensions[VRMC_VRM]?.let { el ->
            if (el is JsonNull) null else json.decodeFromJsonElement(VRMCVrm.serializer(), el)
        }

        // Explicitly reject VRM 0.x files instead of silently mis-parsing the
        // different schema. Only VRM 1.0 / 1.0-beta is supported.
        if (vrm != null && !vrm.specVersion.startsWith("1")) {
            throw VrmParseException(
                "Unsupported VRM specVersion '${vrm.specVersion}': this library supports VRM 1.x only"
            )
        }

        val springBone: VrmcSpringBone? = extensions[VRMC_SPRING_BONE]?.let { el ->
            if (el is JsonNull) null else json.decodeFromJsonElement(VrmcSpringBone.serializer(), el)
        }

        val vrmAnimation: VrmcVrmAnimation? = extensions[VRMC_VRM_ANIMATION]?.let { el ->
            if (el is JsonNull) null else json.decodeFromJsonElement(VrmcVrmAnimation.serializer(), el)
        }

        // MToon lives per-material inside material.extensions
        val mtoonMap = mutableMapOf<Int, VrmcMaterialsMtoon>()
        root.materials?.forEachIndexed { index, mat ->
            val mt = mat.extensions?.get(VRMC_MATERIALS_MTOON)
            if (mt != null && mt !is JsonNull) {
                mtoonMap[index] = json.decodeFromJsonElement(VrmcMaterialsMtoon.serializer(), mt)
            }
        }

        val binBytes = container.bin?.let { buf ->
            val out = ByteArray(buf.remaining())
            buf.get(out)
            out
        }

        return Vrm(
            gltf = root,
            json = container.json,
            binary = binBytes,
            vrm = vrm,
            vrmAnimation = vrmAnimation,
            springBone = springBone,
            mtoon = mtoonMap,
            extensionsUsed = extensionsUsed,
        )
    }
}
