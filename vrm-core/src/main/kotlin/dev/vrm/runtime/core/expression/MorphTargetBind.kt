package dev.vrm.runtime.core.expression

/**
 * Abstraction over a single mesh primitive's morph target weights.
 * Implementations: a pure in-memory buffer (tests/headless) or a Filament
 * RenderableManager-backed channel (demo).
 */
interface MorphTargetChannel {
    /** Current weight of morph target at [index]. */
    fun getMorphWeight(index: Int): Float

    /** Set the weight of morph target at [index]. */
    fun setMorphWeight(index: Int, value: Float)
}

/**
 * A simple in-memory morph channel backed by a FloatArray. Used by tests and
 * by headless pose evaluation.
 */
class InMemoryMorphTargetChannel(private val weights: FloatArray) : MorphTargetChannel {
    override fun getMorphWeight(index: Int): Float {
        if (index < 0 || index >= weights.size) return 0f
        return weights[index]
    }

    override fun setMorphWeight(index: Int, value: Float) {
        if (index < 0 || index >= weights.size) return
        weights[index] = value
    }
}

/**
 * Morph target bind: adds `weight * expressionWeight` to the target morph weight,
 * exactly like three-vrm's VRMExpressionMorphTargetBind.applyWeight.
 */
class MorphTargetBind(
    private val channel: MorphTargetChannel,
    private val index: Int,
    private val weight: Float,
) : ExpressionBind {


    override fun applyWeight(expressionWeight: Float) {
        val current = channel.getMorphWeight(index)
        channel.setMorphWeight(index, current + this.weight * expressionWeight)
    }

    override fun clearAppliedWeight() {
        channel.setMorphWeight(index, 0f)
    }
}