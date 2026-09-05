package dev.vrm.runtime.character

import dev.vrm.runtime.core.controller.AvatarCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExpressionControllerTest {

    @Test
    fun `fades toward target over time`() {
        val out = FakeCharacterOutput()
        val c = ExpressionController(out, fadeTime = 0.5f)
        c.setExpression("happy", 1f)
        // After a small dt, the weight should be strictly between 0 and 1 (easing).
        c.update(0.25f)
        val w = c.weights()["happy"] ?: 0f
        assertTrue(w > 0f && w < 1f, "expected mid-fade weight, got $w")
    }

    @Test
    fun `reaches target after enough time`() {
        val out = FakeCharacterOutput()
        val c = ExpressionController(out, fadeTime = 0.1f)
        c.setExpression("happy", 1f)
        repeat(10) { c.update(0.1f) }
        assertEquals(1f, c.weights()["happy"] ?: 0f, 0.001f)
    }

    @Test
    fun `commit sends RawExpression with current weights`() {
        val out = FakeCharacterOutput()
        val c = ExpressionController(out, fadeTime = 0.01f)
        c.setExpression("sad", 0.8f)
        repeat(10) { c.update(0.1f) }
        c.commit()
        val cmd = out.sentCommands.lastOrNull()
        assertTrue(cmd is AvatarCommand.RawExpression)
        val we = (cmd as AvatarCommand.RawExpression).values
        assertEquals(0.8f, we["sad"] ?: 0f, 0.001f)
    }
}

private inline fun <reified T> List<Any?>.lastIsInstance(): T? =
    this.filterIsInstance<T>().lastOrNull()