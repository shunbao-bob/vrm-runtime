package dev.vrm.runtime.core.mtoon

import dev.vrm.runtime.core.gltf.Gltf
import dev.vrm.runtime.core.vrm.VrmcMaterialsMtoon
import kotlin.math.pow

/**
 * Resolves an MToon material from the `VRMC_materials_mtoon` extension attached
 * to a glTF material.
 *
 * Port of three-vrm's `MToonMaterialLoaderPlugin._extendMaterialParams`,
 * engine-agnostic: maps every schema field onto an [MtoonMaterialParameters]
 * instance, merging the base glTF material's color/texture when present.
 * Textures stay as glTF texture indices; the demo resolves real textures.
 *
 * The schema is already decoded by [dev.vrm.runtime.core.vrm.VrmLoader] into
 * `Vrm.mtoon: Map<Int, VrmcMaterialsMtoon>`; [MtoonLoader] consumes that map
 * plus the raw glTF (for base material fields).
 *
 * @param gltf the parsed glTF (for base material color / texture / normal map)
 * @param mtoonSchemas material index -> decoded VRMC_materials_mtoon
 */
class MtoonLoader(
    private val gltf: Gltf,
    private val mtoonSchemas: Map<Int, VrmcMaterialsMtoon> = emptyMap(),
) {

    /**
     * Load MToon parameters for the glTF material at [materialIndex], or
     * `null` when that material is not MToon.
     *
     * @param convertSRGBToLinear whether color factors are converted sRGB→linear.
     *   three-vrm does NOT convert MToon extension color factors (the flag is
     *   left unset in `assignColor`), so the default is `false` to match.
     */
    fun load(materialIndex: Int, convertSRGBToLinear: Boolean = false): MtoonMaterialParameters? {
        val extension = mtoonSchemas[materialIndex] ?: return null

        val p = MtoonMaterialParameters()

        // merge base color / normal from the underlying glTF material
        val material = gltf.materials?.getOrNull(materialIndex)
        val base = material?.pbrMetallicRoughness
        if (base != null) {
            base.baseColorFactor?.let { if (it.size >= 4) p.colorFactor = it.take(4).toFloatArray() }
            base.baseColorTexture?.index?.let { p.baseColorTextureIndex = it }
        }
        material?.normalTexture?.index?.let { p.normalMapIndex = it }

        // ---- MToon schema fields ----
        extension.transparentWithZWrite?.let { p.transparentWithZWrite = it }
        extension.renderQueueOffsetNumber?.let { p.renderQueueOffsetNumber = it }

        extension.shadeColorFactor?.let { p.shadeColorFactor = colorFrom(it, convertSRGBToLinear) }
        extension.shadeMultiplyTexture?.index?.let { p.shadeMultiplyTextureIndex = it }

        extension.shadingShiftFactor?.let { p.shadingShiftFactor = it }
        extension.shadingShiftTexture?.let { tex ->
            p.shadingShiftTextureIndex = tex.index
            p.shadingShiftTextureScale = tex.scale
        }
        extension.shadingToonyFactor?.let { p.shadingToonyFactor = it }
        extension.giEqualizationFactor?.let { p.giEqualizationFactor = it }

        extension.matcapFactor?.let { p.matcapFactor = colorFrom(it, convertSRGBToLinear) }
        extension.matcapTexture?.index?.let { p.matcapTextureIndex = it }

        extension.rimFactor?.let { p.parametricRimColorFactor = colorFrom(it, convertSRGBToLinear) }
        extension.rimTexture?.index?.let { p.rimMultiplyTextureIndex = it }
        extension.rimLightingMixFactor?.let { p.rimLightingMixFactor = it }
        extension.rimFresnelPowerFactor?.let { p.parametricRimFresnelPowerFactor = it }
        extension.rimLiftFactor?.let { p.parametricRimLiftFactor = it }

        extension.outlineWidthMode?.let { p.outlineWidthMode = OutlineWidthMode.fromJson(it) }
        extension.outlineWidthFactor?.let { p.outlineWidthFactor = it }
        extension.outlineWidthMultiplyTexture?.index?.let { p.outlineWidthMultiplyTextureIndex = it }
        extension.outlineColorFactor?.let { p.outlineColorFactor = colorFrom(it, convertSRGBToLinear) }
        extension.outlineLightingMixFactor?.let { p.outlineLightingMixFactor = it }

        extension.uvAnimationMaskTexture?.index?.let { p.uvAnimationMaskTextureIndex = it }
        extension.uvAnimationScrollXSpeedFactor?.let { p.uvAnimationScrollXSpeedFactor = it }
        extension.uvAnimationScrollYSpeedFactor?.let { p.uvAnimationScrollYSpeedFactor = it }
        extension.uvAnimationRotationSpeedFactor?.let { p.uvAnimationRotationSpeedFactor = it }

        return p
    }

    /**
     * Load MToon parameters for every material that carries the extension.
     *
     * @return material index -> parameters, in ascending index order.
     */
    fun loadAll(convertSRGBToLinear: Boolean = false): Map<Int, MtoonMaterialParameters> {
        val result = LinkedHashMap<Int, MtoonMaterialParameters>()
        for (i in mtoonSchemas.keys.sorted()) {
            load(i, convertSRGBToLinear)?.let { result[i] = it }
        }
        return result
    }

    private fun colorFrom(list: List<Float>, convertSRGBToLinear: Boolean): FloatArray {
        val out = list.take(3).toFloatArray()
        if (convertSRGBToLinear) {
            for (i in out.indices) {
                out[i] = sRGBToLinear(out[i])
            }
        }
        return out
    }

    private fun sRGBToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
}
