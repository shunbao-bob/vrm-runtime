package dev.vrm.runtime.core.lipsync

/**
 * Pinyin → VRM 5-dimension viseme weight mapper.
 *
 * The 5 basic mouth-shape presets defined by VRM 1.0:
 *   - aa (/a/) → mouth wide open
 *   - ih (/yi/) → lips spread horizontally
 *   - ou (/wu/) → lips rounded and pushed forward
 *   - ee (/ei/) → mouth slightly open, tongue forward
 *   - oh (/o/) → mouth half open, rounded
 *
 * The mapping is based on an approximate correspondence between Chinese
 * pinyin finals and the international VRM visemes. Weights are 0.0~1.0; when
 * multiple finals are present at once, the primary final dominates.
 *
 * Port of three-vrm's viseme mapping for Chinese Pinyin:
 *   https://github.com/pixiv/three-vrm/blob/dev/packages/three-vrm-core/src/expressions/visemeMap.ts
 *
 * Lives in vrm-core (engine-agnostic) so both the vrm-adapter (engine mouth
 * application) and vrm-character (LipSyncController) can share it.
 */
object PinyinToVisemeMapper {

    /** Returns a 5-dimension weight array [aa, ih, ou, ee, oh]. */
    fun toWeights(phoneme: String): FloatArray {
        val weights = floatArrayOf(0f, 0f, 0f, 0f, 0f)
        if (phoneme.isBlank()) return weights
        val p = phoneme.lowercase().trim()

        // Extract the final (the final at the end of the pinyin decides the mouth shape)
        val finalSound = extractFinal(p)

        // Map each final onto the VRM 5 dimensions
        // Note: with multiple matches, take the one with the largest weight
        var maxWeight = 0f
        for ((pattern, idx) in FINAL_TO_VISEME) {
            if (finalSound.contains(pattern)) {
                val w = 0.9f
                if (w > maxWeight) {
                    weights.fill(0f)
                    weights[idx] = w
                    maxWeight = w
                }
            }
        }

        // No match at all: treat as a closed mouth
        return weights
    }

    /** The canonical VRM 1.0 viseme names, in index order [aa, ih, ou, ee, oh]. */
    val VISEME_NAMES: List<String> = listOf("aa", "ih", "ou", "ee", "oh")

    /**
     * Extract the final part of a pinyin.
     * Chinese pinyin structure: initial (optional) + final + tone (optional)
     * The final is the core pronouncing part and determines the mouth shape.
     */
    private fun extractFinal(pinyin: String): String {
        // Strip the tone digit
        val noTone = pinyin.replace(Regex("[0-9]"), "")
        // Strip the initial
        // Chinese initials: b p m f d t n l g k h j q x zh ch sh r z c s y w
        val finalSound = noTone.replace(Regex("^(zh|ch|sh|[bpmfdtnlgkhjqxrzcsyw])"), "")
        return finalSound.ifEmpty { noTone }
    }

    /**
     * Final → VRM viseme index mapping.
     * Index: 0=aa, 1=ih, 2=ou, 3=ee, 4=oh
     *
     * References three-vrm's visemeMap.ts and the IPA to viseme mapping.
     */
    private val FINAL_TO_VISEME = listOf(
        // aa — mouth wide open, open vowel
        "a"    to 0,   // a, ia, ua → 'a' (ah)
        "iang" to 0,   // 'yang' → wide open
        "uang" to 0,   // 'wang' → wide open

        // ih — lips spread horizontally, unrounded
        "i"    to 1,   // i, in, ing → 'i' (yi)
        "ie"   to 1,   // 'ye' → spread lips
        "ü"    to 1,   // ü, üe, üan → 'ü' (spread lips)
        "v"    to 1,   // keyboard compatibility: v = ü

        // ou — lips rounded and pushed forward, rounded lips
        "u"    to 2,   // u, un, ueng → 'u' (wu)
        "o"    to 2,   // o, uo → 'o' (wo)
        "ong"  to 2,   // 'weng' → rounded lips
        "iong" to 2,   // 'yong' → rounded lips

        // ee — mouth slightly open, tongue forward
        "e"    to 3,   // e, en, eng → 'e' (e)
        "ei"   to 3,   // 'ei' → slightly open
        "er"   to 3,   // 'er' → retroflex, slightly open
        "ê"    to 3,   // 'ê'

        // oh — mouth mid-open, rounded
        "ao"   to 4,   // 'ao' → mid-open rounded
        "iao"  to 4,   // 'yao' → mid-open rounded
        "ou"   to 4,   // 'ou' → mid-open rounded
        "iu"   to 4,   // 'you' → mid-open rounded
    )
}
