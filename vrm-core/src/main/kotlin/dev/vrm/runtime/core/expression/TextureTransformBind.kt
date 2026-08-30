package dev.vrm.runtime.core.expression

/**
 * Abstraction over a material texture's UV transform (offset + scale) that an
 * expression can drive.
 */
interface TextureTransformAccess {
    /** Resolve the transform channels for the material at [materialIndex]. */
    fun resolve(materialIndex: Int): ResolvedTextureTransform?
}

interface ResolvedTextureTransform {
    val offsetX: ScalarProperty
    val offsetY: ScalarProperty
    val scaleX: ScalarProperty
    val scaleY: ScalarProperty
    val initialOffsetX: Float
    val initialOffsetY: Float
    val initialScaleX: Float
    val initialScaleY: Float
}

/**
 * Texture transform bind: drives a material's UV offset/scale. Port of
 * three-vrm's VRMExpressionTextureTransformBind, engine-agnostic.
 */
class TextureTransformBind(
    private val access: TextureTransformAccess,
    materialIndex: Int,
    scaleX: Float = 1f,
    scaleY: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
) : ExpressionBind {

    private val resolved: ResolvedTextureTransform? = access.resolve(materialIndex)

    private val deltaOffsetX: Float
    private val deltaOffsetY: Float
    private val deltaScaleX: Float
    private val deltaScaleY: Float

    init {
        val r = resolved
        if (r != null) {
            deltaOffsetX = offsetX - r.initialOffsetX
            deltaOffsetY = offsetY - r.initialOffsetY
            deltaScaleX = scaleX - r.initialScaleX
            deltaScaleY = scaleY - r.initialScaleY
        } else {
            deltaOffsetX = 0f; deltaOffsetY = 0f
            deltaScaleX = 0f; deltaScaleY = 0f
        }
    }

    override fun applyWeight(expressionWeight: Float) {
        val r = resolved ?: return
        r.offsetX.set(r.offsetX.get() + deltaOffsetX * expressionWeight)
        r.offsetY.set(r.offsetY.get() + deltaOffsetY * expressionWeight)
        r.scaleX.set(r.scaleX.get() + deltaScaleX * expressionWeight)
        r.scaleY.set(r.scaleY.get() + deltaScaleY * expressionWeight)
    }

    override fun clearAppliedWeight() {
        val r = resolved ?: return
        r.offsetX.set(r.initialOffsetX)
        r.offsetY.set(r.initialOffsetY)
        r.scaleX.set(r.initialScaleX)
        r.scaleY.set(r.initialScaleY)
    }
}