package dev.vrm.runtime.adapter

import net.sourceforge.pinyin4j.PinyinHelper

/**
 * Hanzi → pinyin (for TTS lip-sync).
 *
 * Aliyun TTS (01B) subtitles return only hanzi + timestamps, without a
 * phoneme. The engine-side [PinyinToVisemeMapper] needs pinyin to compute
 * real viseme mouth shapes, so here the pinyin4j library converts each hanzi
 * to pinyin (with tone digits, e.g. "jin1").
 *
 * Notes:
 *  - Only the most common reading of each character is taken (no context-based
 *    disambiguation for polyphonic characters; the mouth shape is sensitive to
 *    the final, and the common reading is usually enough).
 *  - Non-hanzi characters are preserved as-is; the engine-side
 *    [PinyinToVisemeMapper] ignores non-pinyin parts.
 */
object HanziPinyin {

    /** Cache of character → pinyin, avoiding repeated lookups on the JNI callback thread. */
    private val cache = HashMap<Char, String>()

    /**
     * Convert every hanzi in a text to pinyin (with tone), keeping non-hanzi
     * characters unchanged. E.g. "你好" -> "ni3hao3".
     */
    fun toPinyin(text: String): String {
        if (text.isBlank()) return text
        val sb = StringBuilder(text.length * 3)
        for (ch in text) {
            sb.append(pinyinOf(ch) ?: ch)
        }
        return sb.toString()
    }

    /** Returns the most common pinyin (with tone digit) for a hanzi, or null for a non-hanzi or conversion failure. */
    fun pinyinOf(ch: Char): String? {
        if (!isHanzi(ch)) return null
        cache[ch]?.let { return it }
        val result = try {
            PinyinHelper.toHanyuPinyinStringArray(ch)?.firstOrNull()
        } catch (_: Exception) {
            null
        }
        if (result != null) cache[ch] = result
        return result
    }

    private fun isHanzi(ch: Char): Boolean {
        val cp = ch.code
        return cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF
    }
}