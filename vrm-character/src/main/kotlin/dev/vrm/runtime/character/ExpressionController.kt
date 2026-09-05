package dev.vrm.runtime.character

import dev.vrm.runtime.core.controller.AvatarCommand
import kotlin.math.max

/**
 * Smooths expression weights toward targets with fade-in / fade-out, and pushes
 * them to the engine via [CharacterOutput] (RawExpression).
 *
 * This is the "semantic" layer on top of the engine's ExpressionManager: the
 * host / LLM says "make it happy", the controller lerps the weight to the
 * target over [fadeTime] instead of snapping, so the face eases into and out of
 * the expression.
 *
 * Pure Kotlin: JVM-testable.
 */
class ExpressionController(
    private val output: CharacterOutput,
    private val fadeTime: Float = 0.3f,
) {

    /** name → current (smoothed) weight 0..1. */
    private val current = HashMap<String, Float>()

    /** name → target weight 0..1. */
    private val target = HashMap<String, Float>()

    /** Set an expression to a target weight (clamped 0..1). */
    fun setExpression(name: String, weight: Float) {
        val w = weight.coerceIn(0f, 1f)
        target[name] = w
    }

    /** Reset all expressions back to 0 (neutral face). */
    fun clear() {
        target.clear()
    }

    /** Advance the fade. Call once per frame, then read [weights]. */
    fun update(dt: Float) {
        if (dt <= 0f) return
        // Fade current toward target for all known names.
        val names = (current.keys + target.keys).toSet()
        for (name in names) {
            val cur = current[name] ?: 0f
            val tgt = target[name] ?: 0f
            if (cur == tgt) {
                current[name] = tgt
                continue
            }
            val step = (1f / max(fadeTime, 0.01f)) * dt
            val next = if (tgt > cur) (cur + step).coerceAtMost(tgt) else (cur - step).coerceAtLeast(tgt)
            current[name] = next
        }
        // Drop names that fully faded to zero.
        current.entries.removeAll { it.value <= 0f && (target[it.key] ?: 0f) <= 0f }
    }

    /** Current smoothed weights as an immutable map. */
    fun weights(): Map<String, Float> = current.toMap()

    /** Push the current weights to the engine. Call after [update]. */
    fun commit() {
        output.execute(AvatarCommand.RawExpression(current))
    }
}
