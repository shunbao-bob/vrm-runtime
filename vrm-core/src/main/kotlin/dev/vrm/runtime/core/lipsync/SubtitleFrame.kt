package dev.vrm.runtime.core.lipsync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A single character/phoneme timestamp + pinyin parsed from the [info] field
 * of an Aliyun TTS callback.
 *
 * Format example:
 * ```json
 * {"begin_time": 100, "end_time": 250, "text": "今", "phoneme": "jin"}
 * ```
 *
 * Lives in vrm-core (engine-agnostic) so both the vrm-adapter (engine mouth
 * application) and vrm-character (LipSyncController) can share it. Parsing uses
 * kotlinx.serialization (no org.json dependency) so it stays JVM-testable.
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
            return runCatching {
                val dto = JSON.decodeFromString<SubtitleDto>(info)
                SubtitleFrame(dto.beginTime, dto.endTime, dto.text, dto.phoneme)
            }.getOrNull()
        }

        private val JSON = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class SubtitleDto(
            @SerialName("begin_time") val beginTime: Long = 0L,
            @SerialName("end_time") val endTime: Long = 0L,
            val text: String = "",
            val phoneme: String = "",
        )
    }
}
