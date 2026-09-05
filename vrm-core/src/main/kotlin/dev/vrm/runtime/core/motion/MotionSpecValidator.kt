package dev.vrm.runtime.core.motion

import dev.vrm.runtime.core.humanoid.HumanBoneName
import kotlin.math.max
import kotlin.math.min

/**
 * Safety guardrail for LLM-generated [MotionSpec] (port of text-to-vrma's
 * `validateSpec`). LLM keyframe output is unreliable — joints can exceed
 * range, bones can be misspelled, keys can be broken. This clamps, prunes and
 * normalizes everything so the downstream sampler never drives a joint into an
 * impossible pose (which would look broken or stretch the mesh).
 *
 * Pure Kotlin, JVM-testable.
 */
object MotionSpecValidator {

    /** Maximum allowed clip duration (seconds). Longer specs are truncated. */
    const val MAX_DURATION = 20f

    /** The canonical set of driveable human-bone names (the same ones the
     *  normalized rig exposes). Unknown bones from the LLM are dropped. */
    private val BONE_NAMES: Set<String> = setOf(
        HumanBoneName.HIPS, HumanBoneName.SPINE, HumanBoneName.CHEST,
        HumanBoneName.UPPER_CHEST, HumanBoneName.NECK, HumanBoneName.HEAD,
        HumanBoneName.LEFT_SHOULDER, HumanBoneName.RIGHT_SHOULDER,
        HumanBoneName.LEFT_UPPER_ARM, HumanBoneName.RIGHT_UPPER_ARM,
        HumanBoneName.LEFT_LOWER_ARM, HumanBoneName.RIGHT_LOWER_ARM,
        HumanBoneName.LEFT_HAND, HumanBoneName.RIGHT_HAND,
        HumanBoneName.LEFT_UPPER_LEG, HumanBoneName.RIGHT_UPPER_LEG,
        HumanBoneName.LEFT_LOWER_LEG, HumanBoneName.RIGHT_LOWER_LEG,
        HumanBoneName.LEFT_FOOT, HumanBoneName.RIGHT_FOOT,
        HumanBoneName.LEFT_TOES, HumanBoneName.RIGHT_TOES,
    )

    /** Bone-specific max |euler| per axis (degrees). From text-to-vrma. */
    private val ANGLE_LIMITS: Map<String, Float> = mapOf(
        "leftHand" to 25f, "rightHand" to 25f,
        "leftUpperArm" to 75f, "rightUpperArm" to 75f,
        "neck" to 45f, "head" to 70f,
        "spine" to 45f, "chest" to 45f, "upperChest" to 45f,
        "leftFoot" to 60f, "rightFoot" to 60f,
    )
    private const val DEFAULT_ANGLE_LIMIT = 175f

    /** The set of VRM preset expression names that can carry weight tracks. */
    private val EXPRESSION_NAMES: Set<String> = setOf(
        "happy", "angry", "sad", "relaxed", "surprised", "neutral",
        "aa", "ih", "ou", "ee", "oh",
        "blink", "blinkLeft", "blinkRight",
        "lookUp", "lookDown", "lookLeft", "lookRight",
    )

    /**
     * Validate + sanitize a [MotionSpec]. Returns a cleaned copy (the input is
     * left untouched). Never throws for LLM mess — it prunes bad data and
     * clamps out-of-range values so the caller can always proceed. Throws only
     * if the result is unusable (no tracks at all, or non-positive duration).
     */
    fun validate(spec: MotionSpec): MotionSpec {
        // Duration
        var duration = spec.duration
        if (!duration.isFinite() || duration <= 0f) {
            throw IllegalArgumentException("MotionSpec: non-positive duration")
        }
        if (duration > MAX_DURATION) duration = MAX_DURATION

        // Tracks: drop unknown bones, drop broken keys, clamp eulers, apply
        // hinge-joint protections for knees and elbows.
        val outTracks = LinkedHashMap<String, List<RotKey>>()
        for ((bone, keys) in spec.tracks) {
            if (bone !in BONE_NAMES) continue
            // shoulders: let the engine auto-follow (text-to-vrma drops them —
            // LLM shoulder rotations distort cloth meshes).
            if (bone == HumanBoneName.LEFT_SHOULDER || bone == HumanBoneName.RIGHT_SHOULDER) continue
            val limit = ANGLE_LIMITS[bone] ?: DEFAULT_ANGLE_LIMIT
            val cleaned = keys
                .filter { it.t.isFinite() && it.r.size == 3 && it.r.all { v -> v.isFinite() } }
                .map { k ->
                    RotKey(
                        t = min(max(k.t, 0f), duration),
                        r = listOf(
                            k.r[0].coerceIn(-limit, limit),
                            k.r[1].coerceIn(-limit, limit),
                            k.r[2].coerceIn(-limit, limit),
                        ),
                    )
                }
                .sortedBy { it.t }
            if (cleaned.isNotEmpty()) {
                outTracks[bone] = applyJointGuards(bone, cleaned)
            }
        }
        applyCrossBoneGuards(outTracks)

        // Hips: drop broken keys, clamp t to duration.
        val outHips = spec.hips
            .filter { it.t.isFinite() && it.p.size >= 3 && it.p.all { v -> v.isFinite() } }
            .map { PosKey(min(max(it.t, 0f), duration), listOf(it.p[0], it.p[1], it.p[2])) }
            .sortedBy { it.t }
            .takeIf { it.isNotEmpty() }

        // Expressions: keep only known presets, drop broken keys, clamp weight.
        val outExpr = LinkedHashMap<String, List<WeightKey>>()
        for ((name, keys) in spec.expressions) {
            if (name !in EXPRESSION_NAMES) continue
            val cleaned = keys
                .filter { it.t.isFinite() && it.w.isFinite() }
                .map { WeightKey(min(max(it.t, 0f), duration), it.w.coerceIn(0f, 1f)) }
                .sortedBy { it.t }
            if (cleaned.isNotEmpty()) outExpr[name] = cleaned
        }

        if (outTracks.isEmpty() && outHips == null) {
            throw IllegalArgumentException("MotionSpec: no usable tracks after validation")
        }

        return MotionSpec(
            name = spec.name,
            duration = duration,
            loop = spec.loop,
            tracks = outTracks,
            hips = outHips ?: emptyList(),
            expressions = outExpr,
        )
    }

    /** Knee / elbow hinge-joint guards (from text-to-vrma). */
    private fun applyJointGuards(bone: String, keys: List<RotKey>): List<RotKey> = when {
        // Knee: hinge joint — X is the only meaningful axis (0..140 bend, no
        // backward / reverse-bend), Y/Z only small sway.
        bone == HumanBoneName.LEFT_LOWER_LEG || bone == HumanBoneName.RIGHT_LOWER_LEG ->
            keys.map { k ->
                RotKey(
                    k.t,
                    listOf(
                        k.r[0].coerceIn(-3f, 140f),
                        k.r[1].coerceIn(-15f, 15f),
                        k.r[2].coerceIn(-15f, 15f),
                    ),
                )
            }
        // Elbow: no X twist (cone rotation makes forearm drift), no hyperextension.
        bone == HumanBoneName.LEFT_LOWER_ARM || bone == HumanBoneName.RIGHT_LOWER_ARM ->
            keys.map { k ->
                val fwdSign = if (bone == HumanBoneName.RIGHT_LOWER_ARM) 1f else -1f
                RotKey(
                    k.t,
                    listOf(
                        k.r[0].coerceIn(-10f, 10f),
                        fwdSign * (fwdSign * k.r[1]).coerceIn(-15f, 135f),
                        k.r[2].coerceIn(-135f, 135f),
                    ),
                )
            }
        else -> keys
    }

    /** Guards that need both upper-arm and lower-arm tracks. */
    private fun applyCrossBoneGuards(tracks: MutableMap<String, List<RotKey>>) {
        for (side in listOf("left", "right")) {
            val upperName = "${side}UpperArm"
            val lowerName = "${side}LowerArm"
            var upper = tracks[upperName] ?: continue
            val lower = tracks[lowerName] ?: continue

            tracks[lowerName] = lower.map { key ->
                val upperZ = sampleZ(upper, key.t)
                val lowerZ = key.r[2]
                if (kotlin.math.abs(upperZ) >= 40f &&
                    kotlin.math.sign(lowerZ) == -kotlin.math.sign(upperZ) &&
                    kotlin.math.abs(lowerZ) > 15f
                ) {
                    key.copy(r = key.r.toMutableList().also { it[2] = -kotlin.math.sign(upperZ) * 15f })
                } else key
            }

            val maxBend = lower.maxOf { max(kotlin.math.abs(it.r[1]), kotlin.math.abs(it.r[2])) }
            if (maxBend > 55f) {
                val raiseSign = if (side == "left") 1f else -1f
                upper = upper.map { key ->
                    if (raiseSign * key.r[2] > 58f) {
                        key.copy(r = key.r.toMutableList().also { it[2] = raiseSign * 58f })
                    } else key
                }
                tracks[upperName] = upper
            }
        }
    }

    private fun sampleZ(keys: List<RotKey>, time: Float): Float {
        if (time <= keys.first().t) return keys.first().r[2]
        for (i in 0 until keys.lastIndex) {
            val a = keys[i]
            val b = keys[i + 1]
            if (time <= b.t) {
                val span = b.t - a.t
                val u = if (span > 0f) ((time - a.t) / span).coerceIn(0f, 1f) else 0f
                return a.r[2] + (b.r[2] - a.r[2]) * u
            }
        }
        return keys.last().r[2]
    }
}