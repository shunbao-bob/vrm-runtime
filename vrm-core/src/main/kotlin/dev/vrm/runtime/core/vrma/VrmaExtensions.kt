package dev.vrm.runtime.core.vrma

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * VRMC_vrm_animation extension (VRMA): a reusable humanoid animation.
 *
 * glTF JSON shape:
 * { "extensions": { "VRMC_vrm_animation": {
 *     "specVersion": "1.0",
 *     "humanoid": { "humanBones": { "hips": {"node": 0, "translation": {...}}, ... } },
 *     "expressions": { "preset": { "happy": {"node": 1, "morphTargetBinds": [...]} } },
 *     "lookAt": {...}
 * }}}
 */
@Serializable
data class VrmcVrmAnimation(
    val specVersion: String,
    val humanoid: VrmaHumanoid? = null,
    val expressions: VrmaExpressions? = null,
    val lookAt: VrmaLookAt? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmaHumanoid(
    val humanBones: Map<String, VrmaHumanBone> = emptyMap(),
)

@Serializable
data class VrmaHumanBone(
    val node: Int,
    val translation: VrmaNodeValue? = null,
    val rotation: VrmaNodeValue? = null,
)

/**
 * A reference to a glTF animation sampler that drives this property.
 */
@Serializable
data class VrmaNodeValue(
    val sampler: Int? = null,
    val node: Int? = null,
    val index: Int? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmaExpressions(
    val preset: Map<String, VrmaExpression>? = null,
    val custom: Map<String, VrmaExpression>? = null,
)

@Serializable
data class VrmaExpression(
    val node: Int,
    val morphTargetBinds: List<VrmaValue> = emptyList(),
)

@Serializable
data class VrmaValue(
    val sampler: Int? = null,
    val index: Int? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmaLookAt(
    val node: Int,
    val lookAtType: VrmaLookAtType = VrmaLookAtType.BONE,
    val rangeMapHorizontalInner: VrmaLookAtRangeMap? = null,
    val rangeMapHorizontalOuter: VrmaLookAtRangeMap? = null,
    val rangeMapVerticalDown: VrmaLookAtRangeMap? = null,
    val rangeMapVerticalUp: VrmaLookAtRangeMap? = null,
)

@Serializable
data class VrmaLookAtRangeMap(
    val curve: List<Float>? = null,
    val type: String = "bounce",
)

enum class VrmaLookAtType { BONE, EXPRESSION }