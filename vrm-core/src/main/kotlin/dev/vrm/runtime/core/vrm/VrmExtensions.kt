package dev.vrm.runtime.core.vrm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Root of VRMC_vrm extension (VRM 1.0 humanoid avatar).
 *
 * glTF JSON shape:
 * {
 *   "extensions": {
 *     "VRMC_vrm": {
 *       "specVersion": "1.0",
 *       "meta": {...},
 *       "humanoid": {...},
 *       "firstPerson": {...},
 *       "lookAt": {...},
 *       "expressions": {...}
 *     }
 *   }
 * }
 */
@Serializable
data class VRMCVrm(
    val specVersion: String,
    val meta: VrmMeta,
    val humanoid: VrmHumanoid,
    val firstPerson: VrmFirstPerson? = null,
    val lookAt: VrmLookAt? = null,
    val expressions: VrmExpressions? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmHumanoid(
    val humanBones: Map<String, VrmHumanBone> = emptyMap(),
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

/**
 * A single human bone: the index of the glTF node bound to it.
 */
@Serializable
data class VrmHumanBone(
    val node: Int,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmMeta(
    val name: String,
    val version: String? = null,
    val authors: List<String> = emptyList(),
    val copyrightInformation: String? = null,
    val contactInformation: String? = null,
    val references: List<String>? = null,
    val thirdPartyLicenses: String? = null,
    val thumbnailImage: Int? = null,
    val licenseUrl: String = "",
    val avatarPermission: String? = null,
    val allowExcessivelyViolentUsage: Boolean? = null,
    val allowExcessivelySexualUsage: Boolean? = null,
    val commercialUsage: String? = null,
    val allowPoliticalOrReligiousUsage: Boolean? = null,
    val allowAntisocialOrHateUsage: Boolean? = null,
    val creditNotation: String? = null,
    val allowRedistribution: Boolean? = null,
    val modification: String? = null,
    val otherLicenseUrl: String? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmFirstPerson(
    val meshAnnotations: List<VrmFirstPersonMeshAnnotation>? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmFirstPersonMeshAnnotation(
    val node: Int,
    val type: String, // "auto" | "both" | "thirdPersonOnly" | "firstPersonOnly"
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmLookAt(
    val offsetFromHeadBone: List<Float>? = null,
    val type: String? = null, // "bone" | "expression"
    val rangeMapHorizontalInner: VrmLookAtRangeMap? = null,
    val rangeMapHorizontalOuter: VrmLookAtRangeMap? = null,
    val rangeMapVerticalDown: VrmLookAtRangeMap? = null,
    val rangeMapVerticalUp: VrmLookAtRangeMap? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmLookAtRangeMap(
    val inputMaxValue: Float,
    val outputScale: Float = 1f,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmExpressions(
    val preset: Map<String, VrmExpression>? = null,
    val custom: Map<String, VrmExpression>? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmExpression(
    val name: String? = null,
    val preset: String? = null,
    val morphTargetBinds: List<VrmExpressionMorphTargetBind>? = null,
    val materialColorBinds: List<VrmExpressionMaterialColorBind>? = null,
    val textureTransformBinds: List<VrmExpressionTextureTransformBind>? = null,
    val isBinary: Boolean? = null,
    val overrideBlink: String? = null, // "block" | "none"
    val overrideLookAt: String? = null,
    val overrideMouth: String? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmExpressionMorphTargetBind(
    val node: Int,
    val index: Int,
    val weight: Float,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmExpressionMaterialColorBind(
    val material: Int,
    val type: String, // "color" | "emissionColor" | "shadeColor" | "matcapColor" | "rimColor" | "outlineColor"
    val targetValue: List<Float>,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmExpressionTextureTransformBind(
    val material: Int,
    val scale: List<Float>? = null,
    val offset: List<Float>? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)
