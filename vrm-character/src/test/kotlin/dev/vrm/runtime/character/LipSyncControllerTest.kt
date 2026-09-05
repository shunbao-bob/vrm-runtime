package dev.vrm.runtime.character

import dev.vrm.runtime.core.controller.AvatarCommand
import dev.vrm.runtime.core.lipsync.SubtitleFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LipSyncControllerTest {

    private fun lastRawExpression(out: FakeCharacterOutput): Map<String, Float> {
        val cmd = out.sentCommands.filterIsInstance<AvatarCommand.RawExpression>().lastOrNull()
        return cmd?.values ?: emptyMap()
    }

    @Test
    fun `pulse fallback when speaking with no subtitles`() {
        val out = FakeCharacterOutput()
        val lip = LipSyncController(out)
        lip.start(textLength = 5) // speaking, no frames
        lip.updateElapsed(100L)   // mid-pulse window
        val w = lastRawExpression(out)
        assertTrue(w.isNotEmpty())
        assertTrue((w["aa"] ?: 0f) > 0f, "expected aa mouth open in pulse window")
    }

    @Test
    fun `phoneme maps to a viseme and drives mouth`() {
        val out = FakeCharacterOutput()
        val lip = LipSyncController(out)
        lip.start()
        // "jin" → final "in" → contains 'i' → ih (index 1)
        lip.pushSubtitleFrame(SubtitleFrame(beginTime = 0, endTime = 200, text = "今", phoneme = "jin"))
        lip.updateElapsed(100L)
        val w = lastRawExpression(out)
        assertTrue((w["ih"] ?: 0f) > 0f, "expected ih for 'jin', got $w")
    }

    @Test
    fun `mouth closes after the last frame`() {
        val out = FakeCharacterOutput()
        val lip = LipSyncController(out)
        lip.start()
        lip.pushSubtitleFrame(SubtitleFrame(beginTime = 0, endTime = 100, text = "a", phoneme = "a"))
        lip.updateElapsed(50L)
        assertTrue((lastRawExpression(out)["aa"] ?: 0f) > 0f)
        lip.updateElapsed(200L) // past end → closed
        assertEquals(0f, lastRawExpression(out)["aa"] ?: 0f, 0.001f)
    }

    @Test
    fun `stop returns mouth to neutral`() {
        val out = FakeCharacterOutput()
        val lip = LipSyncController(out)
        lip.start()
        lip.pushSubtitleFrame(SubtitleFrame(0, 500, "x", "a"))
        lip.updateElapsed(50L)
        lip.stop()
        assertEquals(0f, lastRawExpression(out)["aa"] ?: 0f, 0.001f)
    }

    @Test
    fun `speakText synthesizes frames so mouth moves`() {
        val out = FakeCharacterOutput()
        val lip = LipSyncController(out)
        lip.speakText("你好世界")
        lip.updateElapsed(120L) // second char window
        assertTrue(lastRawExpression(out).isNotEmpty())
    }
}