package dev.vrm.runtime.core.expression

/**
 * Abstraction over a material's mutable channels that expressions drive.
 * Engine-specific implementation resolves a [MaterialColorType] to concrete
 * [ChannelProperty] / [ScalarProperty] targets on a Filament material.
 */
interface MaterialColorAccess {
    /**
     * Resolve a color property for the given type.
     * @return the resolved channels, or null if unsupported.
     */
    fun resolve(type: MaterialColorType): ResolvedMaterialColor?
}

/**
 * The resolved channels for a material color bind.
 */
interface ResolvedMaterialColor {
    val colorProp: ChannelProperty?
    val alphaProp: ScalarProperty?
    val initialColor: FloatArray
    val initialAlpha: Float
}

/** A settable color channel of a material (3 floats). */
interface ChannelProperty {
    fun get(): FloatArray
    fun set(r: Float, g: Float, b: Float)
}

/** A settable scalar property (e.g. alpha/opacity). */
interface ScalarProperty {
    fun get(): Float
    fun set(v: Float)
}

/**
 * Material color bind: lerps a material color property from its initial value
 * toward `targetValue` by the expression weight. Port of three-vrm's
 * VRMExpressionMaterialColorBind, engine-agnostic.
 *
 * Delta is precomputed once at construction (target - initial).
 */
class MaterialColorBind(
    private val access: MaterialColorAccess,
    type: MaterialColorType,
    targetValue: FloatArray, // RGBA
) : ExpressionBind {

    private val resolved: ResolvedMaterialColor? = access.resolve(type)

    private val colorDelta: FloatArray = floatArrayOf(0f, 0f, 0f)
    private val alphaDelta: Float

    init {
        val r = resolved
        if (r != null) {
            colorDelta[0] = (if (targetValue.size > 0) targetValue[0] else 0f) - r.initialColor[0]
            colorDelta[1] = (if (targetValue.size > 1) targetValue[1] else 0f) - r.initialColor[1]
            colorDelta[2] = (if (targetValue.size > 2) targetValue[2] else 0f) - r.initialColor[2]
            alphaDelta = (if (targetValue.size > 3) targetValue[3] else 1f) - r.initialAlpha
        } else {
            alphaDelta = 0f
        }
    }

    override fun applyWeight(expressionWeight: Float) {
        val r = resolved ?: return
        r.colorProp?.let { cp ->
            val current = cp.get()
            cp.set(
                current[0] + colorDelta[0] * expressionWeight,
                current[1] + colorDelta[1] * expressionWeight,
                current[2] + colorDelta[2] * expressionWeight,
            )
        }
        r.alphaProp?.let { ap ->
            ap.set(ap.get() + alphaDelta * expressionWeight)
        }
    }

    override fun clearAppliedWeight() {
        val r = resolved ?: return
        r.colorProp?.let { it.set(r.initialColor[0], r.initialColor[1], r.initialColor[2]) }
        r.alphaProp?.let { it.set(r.initialAlpha) }
    }
}