package dev.vrm.runtime.adapter

/**
 * A single character/phoneme timestamp + pinyin parsed from the [info] field
 * of an Aliyun TTS callback.
 *
 * Format example:
 * ```json
 * {"begin_time": 100, "end_time": 250, "text": "今", "phoneme": "jin"}
 * ```
 */
data class SubtitleFrame(
    /** Start time of this character in the audio (ms). */
    val beginTime: Long,
    /** End time of this character in the audio (ms). */
    val endTime: Long,
    /** The hanzi text. */
    val text: String,
    /** The pinyin (zh-cn pinyin, e.g. "jin"). */
    val phoneme: String,
) {
    companion object {
        /**
         * Parse from the [info] JSON string of an Aliyun TTS callback.
         * Returns null if parsing fails.
         */
        fun fromJson(info: String): SubtitleFrame? {
            if (info.isBlank()) return null
            return try {
                val json = org.json.JSONObject(info)
                SubtitleFrame(
                    beginTime = json.optLong("begin_time", 0L),
                    endTime = json.optLong("end_time", 0L),
                    text = json.optString("text", ""),
                    phoneme = json.optString("phoneme", ""),
                )
            } catch (_: org.json.JSONException) {
                null
            }
        }
    }
}