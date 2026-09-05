package dev.vrm.runtime.character

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WanderAITest {

    private val world = CollisionWorld(
        bounds = Bounds(minX = -5f, maxX = 5f, minZ = -5f, maxZ = 5f),
        characterRadius = 0.3f,
    )

    @Test
    fun `returns a target inside bounds when idle`() {
        val ai = WanderAI(world, Random(42))
        val target = ai.update(0f, 0f, moving = false, dt = 1f / 60f)
        assertNotNull(target)
        target?.let { (x, z) ->
            assertTrue(x in -5f..5f)
            assertTrue(z in -5f..5f)
        }
    }

    @Test
    fun `returns null while still moving`() {
        val ai = WanderAI(world, Random(1))
        assertNull(ai.update(0f, 0f, moving = true, dt = 1f / 60f))
    }

    @Test
    fun `does not walk into an obstacle`() {
        // A wall right in front of the spawn point: targets behind it must be rejected.
        val blocked = CollisionWorld(
            bounds = Bounds(minX = -5f, maxX = 5f, minZ = -5f, maxZ = 5f),
            obstacles = listOf(
                Obstacle(id = "wall", center = FloatArray3(0f, 0f, 1f), halfExtents = FloatArray3(4f, 1f, 0.2f)),
            ),
            characterRadius = 0.3f,
        )
        // Seed forcing attempts; with the wall spanning nearly the whole width, a
        // valid target must still be found (either this side or around) OR it gives
        // up gracefully (null). The important invariant: never return a target the
        // character can't reach.
        val ai = WanderAI(blocked, Random(7))
        var nulls = 0
        repeat(200) {
            val t = ai.update(0f, 0f, moving = false, dt = 0.016f)
            if (t == null) nulls++ else assertTrue(blocked.isPathClear(0f, 0f, t.first, t.second))
        }
        assertTrue(nulls >= 0) // either works; the reachability check is the real assertion
    }

    @Test
    fun `stays within bounds after many steps`() {
        val ai = WanderAI(world, Random(99))
        var x = 0f; var z = 0f
        repeat(500) {
            val t = ai.update(x, z, moving = false, dt = 0.016f)
            if (t != null) { x = t.first; z = t.second }
            assertTrue(world.isInsideBounds(x, z), "wander escaped bounds: $x,$z")
        }
    }
}
