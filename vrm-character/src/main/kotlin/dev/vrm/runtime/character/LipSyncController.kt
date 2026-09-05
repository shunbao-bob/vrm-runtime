package dev.vrm.runtime.character

import dev.vrm.runtime.core.controller.AvatarCommand
import dev.vrm.runtime.core.lipsync.PinyinToVisemeMapper
import dev.vrm.runtime.core.lipsync.SubtitleFrame
import kotlin.math.abs

/**
 * Lip-sync controller (task 9): the LOGIC layer of mouth animation, extracted
 * from vrm-adapter into vrm-character so it is engine-agnostic and JVM-testable.
 *
 * Consumes [SubtitleFrame]s (timestamped pinyin) and advances a playback clock;
 * each frame it computes the 5 VRM viseme weights ([aa,ih,ou,ee,oh]) and emits
 * them to the engine via [CharacterOutput] as a RawExpression command. The
 * engine (vrm-adapter) is responsible only for APPLYING those weights to the
 * ExpressionManager — it no longer computes them.
 *
 * Pure Kotlin: JVM-testable.
 */
class LipSyncController(
    private val output: CharacterOutput,
) {

    /** The viseme weight map pushed to the engine on the last commit. */
    var lastVisemeWeights: Map<String, Float> = emptyMap()
        private set

    private val frames = ArrayDeque<SubtitleFrame>()
    private var currentFrame: SubtitleFrame? = null
    private var nextFrame: SubtitleFrame? = null
    private var playbackStartNanos: Long = 0L
    private var speaking = false

    /** Start a new speech segment, clearing any prior subtitle queue. */
    fun start(textLength: Int = 0) {
        frames.clear()
        currentFrame = null
        nextFrame = null
        speaking = true
        playbackStartNanos = 0L // set by first pushSubtitleFrame or speakText
        if (textLength > 0) {
            playbackStartNanos = System.nanoTime()
        }
    }

    /** Enqueue one subtitle frame (typically from a TTS callback). */
    fun pushSubtitleFrame(frame: SubtitleFrame) {
        frames.addLast(frame)
        if (playbackStartNanos == 0L) {
            playbackStartNanos = System.nanoTime()
        }
        speaking = true
    }

    /**
     * Speak a plain string with no subtitle timestamps: synthesizes a simple
     * per-character timeline (each char gets [charDurationMs]) so the mouth
     * visibly moves. Pinyin is left blank → the viseme mapper pulls an /a/
     * mouth, which is a decent default open-mouth for arbitrary speech.
     */
    fun speakText(text: String, charDurationMs: Long = 120L) {
        frames.clear()
        playbackStartNanos = System.nanoTime()
        var t = 0L
        for (ch in text) {
            frames.addLast(
                SubtitleFrame(beginTime = t, endTime = t + charDurationMs, text = ch.toString(), phoneme = "")
            )
            t += charDurationMs
        }
        currentFrame = null
        nextFrame = null
        speaking = true
    }

    /** Mark playback started (called when the audio actually begins). */
    fun markPlaybackStarted() {
        playbackStartNanos = System.nanoTime()
    }

    /** Stop lip-sync and return the mouth to closed. */
    fun stop() {
        frames.clear()
        currentFrame = null
        nextFrame = null
        speaking = false
        playbackStartNanos = 0L
        emit(PinyinToVisemeMapper.VISEME_NAMES.associateWith { 0f })
    }

    /**
     * Advance lip-sync. Call every frame; computes viseme weights from the
     * subtitle queue + playback progress and emits them (RawExpression).
     *
     * @param nowNanos the current time in nanoseconds (default System.nanoTime).
     */
    fun update(nowNanos: Long = System.nanoTime()) {
        if (!speaking || playbackStartNanos == 0L) return
        val elapsedMs = (nowNanos - playbackStartNanos) / 1_000_000L
        updateElapsed(elapsedMs)
    }

    /**
     * Advance lip-sync by a directly-injected elapsed time (ms since playback
     * started). Testable: no wall-clock dependency. The public [update] computes
     * elapsed from [playbackStartNanos] and delegates here.
     */
    fun updateElapsed(elapsedMs: Long) {
        if (!speaking) return

        // No frames at all but still "speaking" → simulate a pulse so the mouth
        // moves even without subtitles (matches the engine's fallback behaviour).
        if (frames.isEmpty() && currentFrame == null && nextFrame == null) {
            val t = elapsedMs % 420L
            val pulse = if (t < 320L) 0.7f * (1f - abs((t / 320f) * 2f - 1f)) else 0f
            emit(mapOf("aa" to pulse, "ih" to 0f, "ou" to 0f, "ee" to 0f, "oh" to 0f))
            return
        }

        // Consume expired frames and find the current one.
        while (true) {
            val head = frames.firstOrNull() ?: break
            if (head.endTime <= elapsedMs) {
                frames.removeFirst()
                if (currentFrame == null) {
                    currentFrame = frames.firstOrNull()
                    nextFrame = null
                }
                continue
            }
            if (head.beginTime <= elapsedMs && elapsedMs < head.endTime) {
                currentFrame = head
                nextFrame = frames.firstOrNull()?.takeIf { it !== head }
                break
            }
            break // not yet at this frame
        }

        val frame = currentFrame
        if (frame == null) {
            // No current frame anymore → mouth closes.
            emit(PinyinToVisemeMapper.VISEME_NAMES.associateWith { 0f })
            return
        }

        // Current frame finished and nothing queued → mouth closes.
        if (frame.endTime <= elapsedMs) {
            emit(PinyinToVisemeMapper.VISEME_NAMES.associateWith { 0f })
            currentFrame = null
            nextFrame = null
            return
        }

        // Interpolation factor 0..1 across the frame.
        val progress = if (frame.endTime > frame.beginTime) {
            ((elapsedMs - frame.beginTime).toFloat() / (frame.endTime - frame.beginTime).toFloat())
                .coerceIn(0f, 1f)
        } else 0.5f

        val rawWeights = PinyinToVisemeMapper.toWeights(frame.phoneme)
        val names = PinyinToVisemeMapper.VISEME_NAMES
        val weights = HashMap<String, Float>()
        // Forward pass: set active visemes (scaled 0.9 like the engine did).
        for (i in names.indices) {
            weights[names[i]] = rawWeights[i] * 0.9f
        }
        // If phoneme blank, pulse the mouth (single character being pronounced).
        if (frame.phoneme.isBlank()) {
            val pulse = (1f - abs(progress * 2f - 1f)).coerceIn(0.2f, 1f)
            weights["aa"] = pulse
        }
        emit(weights)
    }

    /** Whether a speech segment is currently active. */
    fun isSpeaking(): Boolean = speaking && (frames.isNotEmpty() || currentFrame != null)

    private fun emit(weights: Map<String, Float>) {
        lastVisemeWeights = weights
        // Always push the weights (including an all-zero close) so the mouth
        // reliably returns to neutral — the engine applies whatever it received.
        output.execute(AvatarCommand.RawExpression(weights))
    }
}