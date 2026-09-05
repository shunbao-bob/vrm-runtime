package dev.vrm.runtime.character

import dev.vrm.runtime.core.motion.MotionSpec
import dev.vrm.runtime.core.motion.MotionSpecValidator
import dev.vrm.runtime.core.motion.PosKey
import dev.vrm.runtime.core.motion.RotKey
import dev.vrm.runtime.core.motion.WeightKey
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Deterministic quality stages applied after LLM generation and before playback. */
object MotionSpecPostProcessor {
    private const val GAP = 0.15f
    private const val SETTLE = 0.8f

    fun process(spec: MotionSpec, originalText: String = ""): MotionSpec {
        var out = MotionSpecValidator.validate(spec)
        out = withAutomaticExpressions(out, originalText)
        if (!out.loop) out = appendNeutralEnding(out)
        return MotionSpecValidator.validate(out)
    }

    fun rescale(spec: MotionSpec, targetDuration: Float): MotionSpec {
        require(targetDuration.isFinite() && targetDuration > 0f)
        val current = spec.duration
        if (current <= 0f) return spec.copy(duration = targetDuration)
        val factor = targetDuration / current
        return spec.copy(
            duration = targetDuration,
            tracks = spec.tracks.mapValues { (_, keys) -> keys.map { it.copy(t = it.t * factor) } },
            hips = spec.hips.map { it.copy(t = it.t * factor) },
            expressions = spec.expressions.mapValues { (_, keys) -> keys.map { it.copy(t = it.t * factor) } },
        )
    }

    fun isLoopFriendly(spec: MotionSpec): Boolean {
        if (spec.hips.isEmpty()) return true
        val first = spec.hips.first().p
        val last = spec.hips.last().p
        if (first.size < 3 || last.size < 3) return false
        val endOffset = hypot(last[0] - first[0], last[2] - first[2])
        val maxOffset = spec.hips.maxOf { key ->
            if (key.p.size < 3) Float.POSITIVE_INFINITY
            else hypot(key.p[0] - first[0], key.p[2] - first[2])
        }
        return endOffset < 0.35f && maxOffset < 1.5f
    }

    fun mergeSequential(parts: List<MotionSpec>): MotionSpec {
        require(parts.isNotEmpty())
        val tracks = linkedMapOf<String, MutableList<RotKey>>()
        val hips = mutableListOf<PosKey>()
        val expressions = linkedMapOf<String, MutableList<WeightKey>>()
        val names = mutableListOf<String>()
        var offset = 0f
        var originX = 0f
        var originZ = 0f
        var yawDegrees = 0f

        parts.forEachIndexed { index, part ->
            val base = offset + if (index == 0) 0f else GAP
            part.tracks.forEach { (bone, keys) ->
                val out = tracks.getOrPut(bone) { mutableListOf() }
                keys.forEach { key ->
                    val rotation = if (bone == "hips" && key.r.size >= 3) {
                        listOf(key.r[0], key.r[1] + yawDegrees, key.r[2])
                    } else key.r
                    out += RotKey(base + key.t, rotation)
                }
            }
            part.expressions.forEach { (name, keys) ->
                expressions.getOrPut(name) { mutableListOf() }
                    .addAll(keys.map { it.copy(t = base + it.t) })
            }
            val radians = Math.toRadians(yawDegrees.toDouble()).toFloat()
            val c = cos(radians)
            val s = sin(radians)
            val partHips = part.hips.ifEmpty { listOf(PosKey(0f, listOf(0f, 0f, 0f))) }
            partHips.forEach { key ->
                val x = key.p.getOrElse(0) { 0f }
                val y = key.p.getOrElse(1) { 0f }
                val z = key.p.getOrElse(2) { 0f }
                hips += PosKey(base + key.t, listOf(x * c + z * s + originX, y, -x * s + z * c + originZ))
            }
            hips.lastOrNull()?.p?.let { originX = it[0]; originZ = it[2] }
            part.tracks["hips"]?.lastOrNull()?.r?.getOrNull(1)?.let { yawDegrees += it }
            offset = base + part.duration
            if (part.name.isNotBlank()) names += part.name
        }
        return MotionSpec(
            name = names.joinToString(" / ").take(60).ifBlank { "motion" },
            duration = offset,
            loop = false,
            tracks = tracks,
            hips = hips,
            expressions = expressions,
        )
    }

    private fun appendNeutralEnding(spec: MotionSpec): MotionSpec {
        val settle = minOf(SETTLE, (MotionSpecValidator.MAX_DURATION - spec.duration).coerceAtLeast(0f))
        if (settle <= 0f) return spec
        val end = spec.duration + settle
        val tracks = spec.tracks.mapValues { (bone, keys) ->
            if (keys.isEmpty()) keys else keys + RotKey(
                end,
                when (bone) {
                    "leftUpperArm" -> listOf(0f, 0f, -70f)
                    "rightUpperArm" -> listOf(0f, 0f, 70f)
                    "hips" -> listOf(0f, keys.last().r.getOrElse(1) { 0f }, 0f)
                    else -> listOf(0f, 0f, 0f)
                },
            )
        }
        val hips = if (spec.hips.isEmpty()) emptyList() else {
            val last = spec.hips.last().p
            spec.hips + PosKey(end, listOf(last.getOrElse(0) { 0f }, 0f, last.getOrElse(2) { 0f }))
        }
        val expressions = spec.expressions.mapValues { (_, keys) ->
            if (keys.isEmpty()) keys else keys + WeightKey(end, 0f)
        }
        return spec.copy(duration = end, tracks = tracks, hips = hips, expressions = expressions)
    }

    private fun withAutomaticExpressions(spec: MotionSpec, text: String): MotionSpec {
        val out = spec.expressions.mapValuesTo(linkedMapOf()) { it.value.toMutableList() }
        val lower = text.lowercase()
        if (out.keys.none { it in setOf("happy", "sad", "angry", "surprised", "relaxed") }) {
            val emotion = when {
                listOf("开心", "高兴", "笑", "happy", "joy").any(lower::contains) -> "happy"
                listOf("难过", "伤心", "sad", "cry").any(lower::contains) -> "sad"
                listOf("生气", "愤怒", "angry").any(lower::contains) -> "angry"
                listOf("惊讶", "吃惊", "surpris").any(lower::contains) -> "surprised"
                else -> "relaxed"
            }
            out[emotion] = mutableListOf(
                WeightKey(0f, 0f),
                WeightKey(minOf(0.3f, spec.duration * 0.25f), if (emotion == "relaxed") 0.35f else 0.7f),
                WeightKey(spec.duration, if (spec.loop) 0.35f else 0f),
            )
        }
        if ("blink" !in out && spec.duration >= 2f) {
            val blink = mutableListOf<WeightKey>()
            var time = 2f
            while (time < spec.duration) {
                blink += WeightKey((time - 0.08f).coerceAtLeast(0f), 0f)
                blink += WeightKey(time, 1f)
                blink += WeightKey((time + 0.08f).coerceAtMost(spec.duration), 0f)
                time += 3f
            }
            if (blink.isNotEmpty()) out["blink"] = blink
        }
        return spec.copy(expressions = out)
    }
}
