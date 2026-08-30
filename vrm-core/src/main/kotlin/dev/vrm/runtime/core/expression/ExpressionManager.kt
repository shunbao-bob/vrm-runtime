package dev.vrm.runtime.core.expression

/**
 * A manager that holds and drives a set of [VrmExpression]s.
 *
 * Port of three-vrm's `VRMExpressionManager` (VRMExpressionManager.ts),
 * stripped of the THREE.Object3D layer. Responsibilities:
 *  - register / unregister / look-up expressions by name or preset name
 *  - read and write each expression's weight (clamped to [0, 1])
 *  - per-frame [update]: compute override multipliers for the blink / lookAt /
 *    mouth groups (expressions with `overrideBlink` etc. suppress the preset
 *    group expressions), then apply every expression's binds.
 *
 * Expressions are stored in registration order; later-registered expressions
 * win over earlier ones when their override amounts compete.
 */
class ExpressionManager {

    /**
     * Names of expressions that participate in the blink group.
     * An expression listed here is suppressed by any expression whose
     * [VrmExpression.overrideBlink] is active.
     */
    var blinkExpressionNames: List<String> = listOf("blink", "blinkLeft", "blinkRight")

    /** Names of expressions in the lookAt group. */
    var lookAtExpressionNames: List<String> = listOf("lookLeft", "lookRight", "lookUp", "lookDown")

    /** Names of expressions in the mouth group. */
    var mouthExpressionNames: List<String> = listOf("aa", "ee", "ih", "oh", "ou")

    private val _expressions = ArrayList<VrmExpression>()
    private val _expressionMap = HashMap<String, VrmExpression>()

    /** A snapshot of all registered expressions, in registration order. */
    val expressions: List<VrmExpression> get() = _expressions.toList()

    /** A copy of the name -> expression map. */
    val expressionMap: Map<String, VrmExpression> get() = HashMap(_expressionMap)

    /**
     * A map from name to expression, but excluding custom expressions.
     * (Only entries whose name is a standard [ExpressionPresetName].)
     */
    val presetExpressionMap: Map<String, VrmExpression>
        get() {
            val result = LinkedHashMap<String, VrmExpression>()
            for ((name, expression) in _expressionMap) {
                if (name in ExpressionPresetName.ALL) {
                    result[name] = expression
                }
            }
            return result
        }

    /**
     * A map from name to expression, but excluding preset expressions.
     */
    val customExpressionMap: Map<String, VrmExpression>
        get() {
            val result = LinkedHashMap<String, VrmExpression>()
            for ((name, expression) in _expressionMap) {
                if (name !in ExpressionPresetName.ALL) {
                    result[name] = expression
                }
            }
            return result
        }

    /**
     * Copy all expressions and config from [source] into this manager.
     * Any previously registered expressions are unregistered first.
     *
     * Note: like three-vrm, the copied expressions are the *same* [VrmExpression]
     * instances as in [source], not deep copies — weights written through one
     * manager are visible through the other.
     */
    fun copy(source: ExpressionManager): ExpressionManager {
        // first unregister all the expressions it has
        for (expression in _expressions.toList()) {
            unregisterExpression(expression)
        }
        // then register all expressions of the source
        for (expression in source._expressions) {
            registerExpression(expression)
        }
        // copy remaining members
        blinkExpressionNames = source.blinkExpressionNames.toList()
        lookAtExpressionNames = source.lookAtExpressionNames.toList()
        mouthExpressionNames = source.mouthExpressionNames.toList()
        return this
    }

    /** A new manager holding this one's expressions and config (see [copy]). */
    fun clone(): ExpressionManager = ExpressionManager().copy(this)

    /**
     * Return a registered expression, or `null` if not found.
     *
     * @param name name or preset name of the expression
     */
    fun getExpression(name: String): VrmExpression? = _expressionMap[name]

    /**
     * Register an expression.
     */
    fun registerExpression(expression: VrmExpression) {
        _expressions.add(expression)
        _expressionMap[expression.name] = expression
    }

    /**
     * Unregister an expression.
     */
    fun unregisterExpression(expression: VrmExpression) {
        val index = _expressions.indexOf(expression)
        if (index == -1) {
            // three-vrm logs a warning here; we keep it silent-but-safe
            return
        }
        _expressions.removeAt(index)
        _expressionMap.remove(expression.name)
    }

    /**
     * Get the current weight of the specified expression.
     * Returns `null` if no expression of that name is registered.
     */
    fun getValue(name: String): Float? = getExpression(name)?.weight

    /**
     * Set a weight to the specified expression, clamped to [0, 1].
     * Does nothing if the expression is not registered.
     */
    fun setValue(name: String, weight: Float) {
        getExpression(name)?.let { it.weight = weight.coerceIn(0f, 1f) }
    }

    /**
     * Reset the weights of all expressions to `0.0`.
     */
    fun resetValues() {
        for (expression in _expressions) {
            expression.weight = 0f
        }
    }

    /**
     * Get the track name of the specified expression, used by keyframe
     * animation to drive it. Returns `null` if not registered.
     *
     * @param name name of the expression
     */
    fun getExpressionTrackName(name: String): String? {
        return getExpression(name)?.let { "${it.name}.weight" }
    }

    /**
     * Update every expression: compute group override multipliers, then apply
     * each expression's binds. Call once per frame.
     */
    fun update() {
        // see how much we should override certain expressions
        val multipliers = calculateWeightMultipliers()

        // reset expression binds first
        for (expression in _expressions) {
            expression.clearAppliedWeight()
        }

        // then apply binds
        for (expression in _expressions) {
            var multiplier = 1.0f
            val name = expression.name

            if (name in blinkExpressionNames) {
                multiplier *= multipliers.blink
            }
            if (name in lookAtExpressionNames) {
                multiplier *= multipliers.lookAt
            }
            if (name in mouthExpressionNames) {
                multiplier *= multipliers.mouth
            }

            expression.applyWeight(multiplier)
        }
    }

    /**
     * Calculate the sum of override amounts to see how much we should multiply
     * the weights of certain expressions. Each group starts at `1.0` and is
     * reduced by the sum of every expression's override amount for that group,
     * floored at `0.0`.
     */
    private fun calculateWeightMultipliers(): WeightMultipliers {
        var blink = 1.0f
        var lookAt = 1.0f
        var mouth = 1.0f

        for (expression in _expressions) {
            blink -= expression.overrideBlinkAmount
            lookAt -= expression.overrideLookAtAmount
            mouth -= expression.overrideMouthAmount
        }

        return WeightMultipliers(
            blink = blink.coerceAtLeast(0f),
            lookAt = lookAt.coerceAtLeast(0f),
            mouth = mouth.coerceAtLeast(0f),
        )
    }

    /** The three group-override multipliers computed by [update]. */
    data class WeightMultipliers(
        val blink: Float,
        val lookAt: Float,
        val mouth: Float,
    )
}
