package dev.vrm.runtime.core.vrm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * VRMC_materials_mtoon extension: shading parameters for a toon (anime) material.
 *
 * Graph is referenced by a material's extensions: {"extensions":{"VRMC_materials_mtoon": {...}}}.
 */
@Serializable
data class VrmcMaterialsMtoon(
    val version: String? = null,
    val transparentWithZWrite: Boolean? = null,
    val renderQueueOffsetNumber: Int? = null,
    val shadeColorFactor: List<Float>? = null,
    val shadeMultiplyTexture: VrmcMtoonTextureInfo? = null,
    val shadingShiftFactor: Float? = null,
    val shadingShiftTexture: VrmcMtoonShadingShiftTexture? = null,
    val shadingToonyFactor: Float? = null,
    val giEqualizationFactor: Float? = null,
    val matcapFactor: List<Float>? = null,
    val matcapTexture: VrmcMtoonTextureInfo? = null,
    val rimFactor: List<Float>? = null,
    val rimLightingMixFactor: Float? = null,
    val rimFresnelPowerFactor: Float? = null,
    val rimLiftFactor: Float? = null,
    val rimTexture: VrmcMtoonTextureInfo? = null,
    val outlineWidthMode: String? = null, // "none" | "worldCoordinates" | "screenCoordinates"
    val outlineWidthFactor: Float? = null,
    val outlineWidthMultiplyTexture: VrmcMtoonTextureInfo? = null,
    val outlineColorFactor: List<Float>? = null,
    val outlineLightingMixFactor: Float? = null,
    val uvAnimationMaskTexture: VrmcMtoonTextureInfo? = null,
    val uvAnimationScrollXSpeedFactor: Float? = null,
    val uvAnimationScrollYSpeedFactor: Float? = null,
    val uvAnimationRotationSpeedFactor: Float? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmcMtoonTextureInfo(
    val index: Int? = null,
    val texCoord: Int = 0,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmcMtoonShadingShiftTexture(
    val index: Int? = null,
    val texCoord: Int = 0,
    val scale: Float = 1f,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)