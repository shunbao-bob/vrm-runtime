package dev.vrm.runtime.core.lookAt

import dev.vrm.runtime.core.expression.ExpressionManager

/**
 * Applies eye-gaze directions through the VRM's *look* expressions
 * (`lookLeft` / `lookRight` / `lookUp` / `lookDown`), port of three-vrm's
 * `VRMLookAtExpressionApplier`.
 *
 * Both horizontal directions map through [rangeMapHorizontalOuter]; the sign
 * of the yaw picks which eye-direction expression gets the (positive) weight.
 */
class VrmLookAtExpressionApplier(
    private val expressions: ExpressionManager,

    /** Not used by the expression applier; kept for interface parity. */
    @Suppress("unused")
    val rangeMapHorizontalInner: VrmLookAtRangeMap,

    /** Maps horizontal movement for both eyes (left / right). */
    val rangeMapHorizontalOuter: VrmLookAtRangeMap,

    /** Maps downward gaze (both eyes). */
    val rangeMapVerticalDown: VrmLookAtRangeMap,

    /** Maps upward gaze (both eyes). */
    val rangeMapVerticalUp: VrmLookAtRangeMap,
) : VrmLookAtApplier {

    override fun applyYawPitch(yaw: Float, pitch: Float) {
        if (pitch < 0f) {
            expressions.setValue("lookDown", 0f)
            expressions.setValue("lookUp", rangeMapVerticalUp.map(-pitch))
        } else {
            expressions.setValue("lookUp", 0f)
            expressions.setValue("lookDown", rangeMapVerticalDown.map(pitch))
        }

        if (yaw < 0f) {
            expressions.setValue("lookLeft", 0f)
            expressions.setValue("lookRight", rangeMapHorizontalOuter.map(-yaw))
        } else {
            expressions.setValue("lookRight", 0f)
            expressions.setValue("lookLeft", rangeMapHorizontalOuter.map(yaw))
        }
    }
}