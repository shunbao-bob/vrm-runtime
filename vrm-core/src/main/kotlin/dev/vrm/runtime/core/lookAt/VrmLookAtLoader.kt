package dev.vrm.runtime.core.lookAt

import dev.vrm.runtime.core.expression.ExpressionManager
import dev.vrm.runtime.core.humanoid.MutableNodeTransformStore
import dev.vrm.runtime.core.humanoid.VRMHumanoid
import dev.vrm.runtime.core.math.Vec3
import dev.vrm.runtime.core.vrm.VRMCVrm
import dev.vrm.runtime.core.vrm.VrmLookAt as VrmLookAtSchema
import dev.vrm.runtime.core.vrm.VrmLookAtRangeMap as VrmLookAtRangeMapSchema

/**
 * Builds a [VrmLookAt] from a parsed VRMC_vrm extension, port of three-vrm's
 * `VRMLookAtLoaderPlugin._v1Import`. Dispatches to the expression or bone
 * applier based on `lookAt.type`.
 *
 * [VrmLookAtRangeMap.inputMaxValue] smaller than 0.01 is clamped up to 0.01 to
 * avoid NaN (a too-small value can make the head mesh disappear).
 */
class VrmLookAtLoader(
    private val humanoid: VRMHumanoid,
    private val expressionManager: ExpressionManager,
    private val store: MutableNodeTransformStore,
) {
    /**
     * @param vrm the parsed VRMC_vrm extension (only its [VRMCVrm.lookAt] is read)
     * @return a [VrmLookAt] or null when the VRM has no lookAt section
     */
    fun load(vrm: VRMCVrm): VrmLookAt? {
        val schemaLookAt = vrm.lookAt ?: return null

        val defaultOutputScale = if (schemaLookAt.type == "expression") 1f else 10f

        val mapHI = importRangeMap(schemaLookAt.rangeMapHorizontalInner, defaultOutputScale)
        val mapHO = importRangeMap(schemaLookAt.rangeMapHorizontalOuter, defaultOutputScale)
        val mapVD = importRangeMap(schemaLookAt.rangeMapVerticalDown, defaultOutputScale)
        val mapVU = importRangeMap(schemaLookAt.rangeMapVerticalUp, defaultOutputScale)

        val applier: VrmLookAtApplier = if (schemaLookAt.type == "expression") {
            VrmLookAtExpressionApplier(expressionManager, mapHI, mapHO, mapVD, mapVU)
        } else {
            VrmLookAtBoneApplier(humanoid, store, mapHI, mapHO, mapVD, mapVU)
        }

        val offset = schemaLookAt.offsetFromHeadBone
            ?.let { if (it.size >= 3) Vec3(it[0], it[1], it[2]) else null }
            ?: Vec3(0f, 0.06f, 0f)

        return VrmLookAt(humanoid, applier, offset)
    }

    private fun importRangeMap(
        schemaRangeMap: VrmLookAtRangeMapSchema?,
        defaultOutputScale: Float,
    ): VrmLookAtRangeMap {
        var inputMaxValue = schemaRangeMap?.inputMaxValue ?: 90f
        val outputScale = schemaRangeMap?.outputScale ?: defaultOutputScale

        // a too-small value would cause NaN and could make the head mesh disappear
        if (inputMaxValue < 0.01f) {
            inputMaxValue = 0.01f
        }

        return VrmLookAtRangeMap(inputMaxValue, outputScale)
    }
}