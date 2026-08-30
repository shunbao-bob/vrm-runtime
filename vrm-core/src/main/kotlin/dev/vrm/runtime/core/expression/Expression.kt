package dev.vrm.runtime.core.expression

/**
 * Standard preset expression names (VRM 1.0).
 */
object ExpressionPresetName {
    const val AA = "aa"
    const val IH = "ih"
    const val OU = "ou"
    const val EE = "ee"
    const val OH = "oh"
    const val BLINK = "blink"
    const val HAPPY = "happy"
    const val ANGRY = "angry"
    const val SAD = "sad"
    const val RELAXED = "relaxed"
    const val LOOK_UP = "lookUp"
    const val SURPRISED = "surprised"
    const val LOOK_DOWN = "lookDown"
    const val LOOK_LEFT = "lookLeft"
    const val LOOK_RIGHT = "lookRight"
    const val BLINK_LEFT = "blinkLeft"
    const val BLINK_RIGHT = "blinkRight"
    const val NEUTRAL = "neutral"

    val ALL: Set<String> = setOf(
        AA, IH, OU, EE, OH, BLINK, HAPPY, ANGRY, SAD, RELAXED,
        LOOK_UP, SURPRISED, LOOK_DOWN, LOOK_LEFT, LOOK_RIGHT,
        BLINK_LEFT, BLINK_RIGHT, NEUTRAL,
    )
}

/**
 * Override types for blink/lookAt/mouth groups.
 */
enum class ExpressionOverrideType {
    NONE,
    BLOCK,
    BLEND,
}

/**
 * The material color channel that a material color bind targets.
 */
enum class MaterialColorType(val jsonValue: String) {
    COLOR("color"),
    EMISSION_COLOR("emissionColor"),
    SHADE_COLOR("shadeColor"),
    MATCAP_COLOR("matcapColor"),
    RIM_COLOR("rimColor"),
    OUTLINE_COLOR("outlineColor");

    companion object {
        fun fromJson(v: String): MaterialColorType =
            entries.firstOrNull { it.jsonValue == v } ?: COLOR
    }
}

/**
 * An interface representing a single expression bind (a target that the
 * expression influences). Implementations are engine-specific but defined here
 * so that [ExpressionManager] can drive them uniformly.
 */
interface ExpressionBind {
    /** Apply the given effective weight to the target. */
    fun applyWeight(weight: Float)

    /** Reset the target to its neutral (weight 0) state. */
    fun clearAppliedWeight()
}

/**
 * A port of three-vrm's VRMExpression. Holds the weight and the binds.
 */
class VrmExpression(
    val name: String,
    var weight: Float = 0f,
    var isBinary: Boolean = false,
    var overrideBlink: ExpressionOverrideType = ExpressionOverrideType.NONE,
    var overrideLookAt: ExpressionOverrideType = ExpressionOverrideType.NONE,
    var overrideMouth: ExpressionOverrideType = ExpressionOverrideType.NONE,
) {
    private val _binds = ArrayList<ExpressionBind>()

    /** The binds this expression influences. */
    val binds: List<ExpressionBind> get() = _binds

    fun addBind(bind: ExpressionBind) {
        _binds.add(bind)
    }

    /** Output weight, considering isBinary. */
    val outputWeight: Float
        get() = if (isBinary) (if (weight > 0.5f) 1.0f else 0.0f) else weight

    val overrideBlinkAmount: Float
        get() = when (overrideBlink) {
            ExpressionOverrideType.BLOCK -> if (outputWeight > 0f) 1f else 0f
            ExpressionOverrideType.BLEND -> outputWeight
            ExpressionOverrideType.NONE -> 0f
        }

    val overrideLookAtAmount: Float
        get() = when (overrideLookAt) {
            ExpressionOverrideType.BLOCK -> if (outputWeight > 0f) 1f else 0f
            ExpressionOverrideType.BLEND -> outputWeight
            ExpressionOverrideType.NONE -> 0f
        }

    val overrideMouthAmount: Float
        get() = when (overrideMouth) {
            ExpressionOverrideType.BLOCK -> if (outputWeight > 0f) 1f else 0f
            ExpressionOverrideType.BLEND -> outputWeight
            ExpressionOverrideType.NONE -> 0f
        }

    /** Apply weight to all binds. */
    fun applyWeight(multiplier: Float = 1f) {
        var actualWeight = outputWeight * multiplier
        if (isBinary && actualWeight < 1.0f) actualWeight = 0f
        _binds.forEach { it.applyWeight(actualWeight) }
    }

    /** Reset all binds to neutral. */
    fun clearAppliedWeight() {
        _binds.forEach { it.clearAppliedWeight() }
    }
}