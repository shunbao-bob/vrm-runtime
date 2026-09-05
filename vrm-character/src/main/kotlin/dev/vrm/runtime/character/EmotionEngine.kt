package dev.vrm.runtime.character

import kotlin.math.max
import kotlin.math.min

/**
 * Emotion state machine (task 7).
 *
 * Maintains a set of concurrent emotions, each with an intensity 0..1 that
 * decays over time. Multiple emotions can be active at once; the engine exposes
 * the dominant one plus the full weighted map so the host (LLM driving) can map
 * emotion → expression weights, idle motion, and speech tone.
 *
 * Pure Kotlin: JVM-testable.
 */
class EmotionEngine(
    /** Emotion definitions: name → expression id + speech tone + decay. */
    private val profile: EmotionProfile = EmotionProfile.defaults(),
) {

    /** emotion name → current intensity 0..1. */
    private val intensities = LinkedHashMap<String, Float>()

    /** Current dominant emotion name, or null when neutral. */
    var dominant: String? = null
        private set

    /** Dominant emotion intensity 0..1 (0 when neutral). */
    var dominantIntensity: Float = 0f
        private set

    /** Trigger an emotion. [intensity] 0..1, [boost] adds to the current value
     *  instead of replacing (for repeated LLM hints reinforcing the mood). */
    fun set(name: String, intensity: Float, boost: Boolean = false) {
        val def = profile.emotions[name] ?: return
        val clamped = intensity.coerceIn(0f, 1f)
        val next = if (boost) min(1f, (intensities[name] ?: 0f) + clamped) else clamped
        intensities[name] = next
        recomputeDominant()
    }

    /** Convenience: set by a standard emotion name with default intensity. */
    fun emote(name: String, intensity: Float = 1f) = set(name, intensity, boost = false)

    /** Reset all emotions to neutral. */
    fun clear() {
        intensities.clear()
        dominant = null
        dominantIntensity = 0f
    }

    /** Advance decay. Call once per frame. */
    fun update(dt: Float) {
        if (intensities.isEmpty()) {
            updateExprWeight(dt)
            return
        }
        val decay = profile.decayPerSecond
        val it = intensities.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val next = max(0f, e.value - decay * dt)
            if (next <= 0f) it.remove() else e.setValue(next)
        }
        recomputeDominant()
        updateExprWeight(dt)
    }

    /** The expression id the dominant emotion maps to, or null when neutral. */
    fun dominantExpression(): String? {
        val d = dominant ?: return null
        return profile.emotions[d]?.expression
    }

    /** The speech tone the dominant emotion maps to (for LLM voice), or default. */
    fun dominantTone(): String {
        val d = dominant ?: return profile.defaultTone
        return profile.emotions[d]?.tone ?: profile.defaultTone
    }

    /** Full weighted map name → intensity (for hosts that blend all emotions). */
    fun intensities(): Map<String, Float> = intensities.toMap()

    private fun recomputeDominant() {
        var best: String? = null
        var bestV = 0f
        for ((name, v) in intensities) {
            if (v > bestV) { best = name; bestV = v }
        }
        dominant = best
        dominantIntensity = bestV
    }

    // ── Fade curve (port of text-to-vrma autoExpressions) ──
    // Smooths expression weight toward the dominant emotion's intensity so the
    // face eases in/out instead of snapping. Fade-in is fast (0.3s), fade-out
    // slower (0.6s), giving a natural emotional attack/decay.

    /** Current smoothed expression weight for the dominant emotion (0..1). */
    var expressionWeight: Float = 0f
        private set

    private var exprWeightCur = 0f

    /** Fade-in seconds when an emotion strengthens. */
    var fadeInTime = 0.3f

    /** Fade-out seconds when an emotion weakens / clears. */
    var fadeOutTime = 0.6f

    /** Advance the expression fade toward [dominantIntensity]. Call from update. */
    private fun updateExprWeight(dt: Float) {
        val target = dominantIntensity
        val step = if (target > exprWeightCur) (1f / fadeInTime) * dt else (1f / fadeOutTime) * dt
        exprWeightCur = when {
            target > exprWeightCur -> (exprWeightCur + step).coerceAtMost(target)
            target < exprWeightCur -> (exprWeightCur - step).coerceAtLeast(target)
            else -> target
        }
        expressionWeight = exprWeightCur
    }
}

/**
 * Static emotion → expression/tone mapping. Built-in defaults use the VRM 1.0
 * preset expression ids (happy/sad/angry/surprised/worried). Hosts can supply
 * their own profile (e.g. from config) to bind emotion names to their models'
 * actual expression ids.
 */
class EmotionProfile(
    val emotions: Map<String, EmotionDef> = emptyMap(),
    val decayPerSecond: Float = 0.35f,
    val defaultTone: String = "neutral",
) {
    class EmotionDef(
        val expression: String,
        val tone: String = "neutral",
    )

    companion object {
        fun defaults(): EmotionProfile = EmotionProfile(
            emotions = mapOf(
                "happy" to EmotionDef("happy", "cheerful"),
                "sad" to EmotionDef("sad", "gentle"),
                "angry" to EmotionDef("angry", "firm"),
                "surprised" to EmotionDef("surprised", "bright"),
                // VRM 1.0 has no standard "worried" preset; sad is the portable fallback.
                "worried" to EmotionDef("sad", "soft"),
                "neutral" to EmotionDef("neutral", "neutral"),
            ),
        )
    }
}
