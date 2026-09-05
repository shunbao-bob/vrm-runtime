package dev.vrm.runtime.character

import dev.vrm.runtime.core.controller.AvatarCommand
import dev.vrm.runtime.core.motion.MotionSpec
import dev.vrm.runtime.core.motion.MotionSpecValidator
import dev.vrm.runtime.core.motion.RotKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdleMotionTest {

    @Test
    fun `idle spec is valid, looping and has blink`() {
        val spec = IdleMotion.spec()
        // Passes the validator → safe to play.
        val validated = MotionSpecValidator.validate(spec)
        assertTrue(validated.loop)
        assertTrue(validated.tracks.isNotEmpty())
        assertTrue(validated.expressions.containsKey("blink"))
        assertTrue(validated.expressions["blink"]!!.any { it.w > 0f })
    }

    @Test
    fun `idle spec has breathing chest and head tracks`() {
        val spec = IdleMotion.spec()
        assertTrue(spec.tracks.containsKey("chest"))
        assertTrue(spec.tracks.containsKey("head"))
        assertTrue(spec.tracks.containsKey("leftUpperArm"))
    }
}

class CharacterControllerMotionTest {

    private val scene = SceneConfig(
        name = "test",
        bounds = Bounds(minX = -4f, maxX = 4f, minZ = -4f, maxZ = 4f),
    )

    @Test
    fun `playMotion issues PlayMotionSpec command`() {
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, scene)
        val spec = MotionSpec(
            name = "wave", duration = 2f,
            tracks = mapOf("spine" to listOf(RotKey(0f, listOf(0f, 0f, 0f)), RotKey(1f, listOf(10f, 0f, 0f)))),
        )
        cc.playMotion(spec)
        val cmd = out.sentCommands.filterIsInstance<AvatarCommand.PlayMotionSpec>().lastOrNull()
        assertTrue(cmd != null)
        assertEquals("wave", cmd?.spec?.name)
    }

    @Test
    fun `playIdle issues a PlayMotionSpec with the idle clip`() {
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, scene)
        cc.playIdle()
        val cmd = out.sentCommands.filterIsInstance<AvatarCommand.PlayMotionSpec>().lastOrNull()
        assertTrue(cmd != null)
        assertEquals("idle", cmd?.spec?.name)
        assertTrue(cmd?.loop == true)
    }

    @Test
    fun `playMotionJson parses validates and plays`() {
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, scene)
        val ok = cc.playMotionJson(
            """{"name":"bow","duration":2,"tracks":{"spine":[{"t":0,"r":[0,0,0]},{"t":1,"r":[20,0,0]},{"t":2,"r":[0,0,0]}]}}"""
        )
        assertTrue(ok)
        val cmd = out.sentCommands.filterIsInstance<AvatarCommand.PlayMotionSpec>().lastOrNull()
        assertTrue(cmd != null)
        assertEquals("bow", cmd?.spec?.name)
    }

    @Test
    fun `playMotionJson rejects garbage`() {
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, scene)
        val ok = cc.playMotionJson("this is not json")
        assertEquals(false, ok)
        assertTrue(out.sentCommands.none { it is AvatarCommand.PlayMotionSpec })
    }
}