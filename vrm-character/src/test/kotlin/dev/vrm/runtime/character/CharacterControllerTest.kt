package dev.vrm.runtime.character

import dev.vrm.runtime.core.controller.AvatarCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CharacterControllerTest {

    private val scene = SceneConfig(
        name = "test",
        bounds = Bounds(minX = -4f, maxX = 4f, minZ = -4f, maxZ = 4f),
        characterRadius = 0.3f,
        anchors = listOf(SceneAnchor(id = "door", x = 3f, y = 0f, z = 3f)),
    )

    @Test
    fun `emote drives the dominant expression weight`() {
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, scene)
        cc.emote("happy", 1f)
        cc.update(0.5f) // enough time to fade in; emotion also decays a little
        val cmd = out.sentCommands.filterIsInstance<AvatarCommand.RawExpression>().lastOrNull()
        assertTrue(cmd != null)
        // Default decay 0.35/s over 0.5s → intensity ≈ 0.825, so the expression
        // weight should be meaningfully above zero (faded in) but < 1.
        val w = cmd?.values?.get("happy") ?: 0f
        assertTrue(w in 0.5f..1f, "expected faded-in happy weight, got $w")
    }

    @Test
    fun `moveTo issues a MoveTo command`() {
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, scene)
        cc.moveTo(2f, 1f)
        val cmd = out.sentCommands.filterIsInstance<AvatarCommand.MoveTo>().lastOrNull()
        assertEquals(2f, cmd?.x ?: -1f, 0.001f)
        assertEquals(1f, cmd?.z ?: -1f, 0.001f)
    }

    @Test
    fun `moveToAnchor resolves scene anchor`() {
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, scene)
        cc.moveToAnchor("door")
        val cmd = out.sentCommands.filterIsInstance<AvatarCommand.MoveTo>().lastOrNull()
        assertEquals(3f, cmd?.x ?: -1f, 0.001f)
        assertEquals(3f, cmd?.z ?: -1f, 0.001f)
    }

    @Test
    fun `unknown anchor is safely ignored`() {
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, scene)
        cc.moveToAnchor("nope") // no crash
        assertTrue(out.sentCommands.filterIsInstance<AvatarCommand.MoveTo>().isEmpty())
    }

    @Test
    fun `wander mode issues MoveTo targets inside bounds`() {
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, scene)
        cc.wanderEnabled = true
        repeat(10) { cc.update(1f / 60f) }
        val targets = out.sentCommands.filterIsInstance<AvatarCommand.MoveTo>()
        for (t in targets) {
            assertTrue(t.x in -4f..4f && t.z in -4f..4f, "wander target out of bounds: ${t.x},${t.z}")
        }
    }

    @Test
    fun `LLM intent json dispatches actions`() {
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, scene)
        val json = """
            {
              "actions": [
                { "type": "emote", "name": "angry", "intensity": 0.9 },
                { "type": "move_to", "x": 2.0, "z": -1.0, "speed": 1.2 },
                { "type": "speak", "text": "你好" },
                { "type": "unknown_action", "foo": "bar" }
              ]
            }
        """.trimIndent()
        val n = cc.drive(json)
        assertEquals(3, n) // unknown action skipped but counted? no — only known executed
        assertTrue(out.sentCommands.any { it is AvatarCommand.MoveTo && it.x == 2f })
    }

    @Test
    fun `malformed llm intent does not crash`() {
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, scene)
        assertEquals(0, cc.drive("not json at all"))
    }

    @Test
    fun `dominant emotion switch clears only the previous emotion expression`() {
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, scene)
        cc.setExpression("hostCustom", 0.6f)
        cc.emote("happy", 1f)
        cc.update(0.3f)
        cc.emote("sad", 1f)
        cc.update(0.3f)

        val values = out.sentCommands.filterIsInstance<AvatarCommand.RawExpression>().last().values
        assertEquals(0f, values["happy"] ?: 0f, 0.001f)
        assertTrue((values["sad"] ?: 0f) > 0f)
        assertEquals(0.6f, values["hostCustom"] ?: 0f, 0.001f)
        assertFalse(values.containsKey("aa"), "emotion commit must not own lip-sync keys")
    }

    @Test
    fun `emotion disappearance fades the owned key to zero`() {
        val out = FakeCharacterOutput()
        val fastDecay = EmotionProfile(
            emotions = mapOf("happy" to EmotionProfile.EmotionDef("happy")),
            decayPerSecond = 10f,
        )
        val cc = CharacterController(out, scene, fastDecay)
        cc.emote("happy", 1f)
        cc.update(0.1f)
        cc.update(1f)

        val values = out.sentCommands.filterIsInstance<AvatarCommand.RawExpression>().last().values
        assertEquals(0f, values["happy"] ?: 0f, 0.001f)
    }

    @Test
    fun `play motion defaults to the loop value declared by the spec`() {
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, scene)
        val spec = dev.vrm.runtime.core.motion.MotionSpec(name = "looping", loop = true)
        cc.playMotion(spec)
        val cmd = out.sentCommands.filterIsInstance<AvatarCommand.PlayMotionSpec>().last()
        assertTrue(cmd.loop)
    }

    @Test
    fun `drive counts only actions that actually execute`() {
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, scene)
        val intent = CharacterIntent(listOf(
            CharacterAction(type = "move_to_anchor", name = "missing"),
            CharacterAction(type = "speak", text = null),
            CharacterAction(type = "set_expression", name = null),
            CharacterAction(type = "emote", name = "not-supported"),
            CharacterAction(type = "move_to", x = 1f, z = 1f),
        ))
        assertEquals(1, cc.drive(intent))
    }

    @Test
    fun `explicit movement clamps bounds and rejects blocked paths`() {
        val blockedScene = scene.copy(
            obstacles = listOf(Obstacle(id = "wall", center = FloatArray3(0f, 0f, 1f), halfExtents = FloatArray3(1f, 1f, 0.2f))),
        )
        val out = FakeCharacterOutput()
        val cc = CharacterController(out, blockedScene)

        assertTrue(cc.moveTo(99f, -99f))
        val clamped = out.sentCommands.filterIsInstance<AvatarCommand.MoveTo>().last()
        assertEquals(4f, clamped.x, 0.001f)
        assertEquals(-4f, clamped.z, 0.001f)

        out.sentCommands.clear()
        assertFalse(cc.moveTo(0f, 2f), "straight path crosses the wall")
        assertTrue(out.sentCommands.filterIsInstance<AvatarCommand.MoveTo>().isEmpty())
    }
}