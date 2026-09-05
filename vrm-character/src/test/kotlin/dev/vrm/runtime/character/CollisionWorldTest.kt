package dev.vrm.runtime.character

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CollisionWorldTest {

    private val config = SceneConfig(
        name = "test",
        bounds = Bounds(minX = -4f, maxX = 4f, minZ = -4f, maxZ = 4f),
        obstacles = listOf(
            Obstacle(id = "wall", center = FloatArray3(0f, 0f, 2f), halfExtents = FloatArray3(0.5f, 1f, 0.5f)),
        ),
        characterRadius = 0.3f,
    )
    private val world = CollisionWorld.from(config)

    @Test
    fun `inside bounds is walkable`() {
        assertTrue(world.isInsideBounds(0f, 0f))
        assertTrue(world.isInsideBounds(3.9f, -3.9f))
    }

    @Test
    fun `outside bounds is blocked`() {
        assertFalse(world.isInsideBounds(4.1f, 0f))
        assertFalse(world.isInsideBounds(0f, -4.1f))
        assertTrue(world.isBlocked(5f, 0f))
    }

    @Test
    fun `clampToBounds keeps point inside`() {
        val (x, z) = world.clampToBounds(10f, -10f)
        assertEquals(4f, x)
        assertEquals(-4f, z)
    }

    @Test
    fun `overlaps obstacle at the box center`() {
        assertTrue(world.overlapsObstacle(0f, 2f))          // dead center of the wall
        assertFalse(world.overlapsObstacle(0f, 0f))          // far from the wall
        assertTrue(world.overlapsObstacle(0.4f, 2.2f))       // within radius of the box
    }

    @Test
    fun `raycast hits the wall from in front`() {
        val d = world.raycastDistance(0f, 0f, 0f, 1f, maxDist = 10f)
        // Wall spans z 1.5..2.5 with radius 0.3 → hit before reaching 2.2
        assertTrue(d in 1.0f..2.2f, "expected hit around 1.2-2.2, got $d")
    }

    @Test
    fun `raycast to a short clear direction returns maxDist`() {
        // From the center toward -Z with maxDist 2: stays well inside bounds
        // (minZ=-4) and no obstacle → clear.
        val d = world.raycastDistance(0f, 0f, 0f, -1f, maxDist = 2f)
        assertEquals(2f, d, 0.001f)
    }

    @Test
    fun `raycast stops at the bounds edge`() {
        // Toward -Z far enough to leave bounds (minZ=-4) → blocked at the edge.
        val d = world.raycastDistance(0f, 0f, 0f, -1f, maxDist = 10f)
        assertTrue(d in 3.8f..4.2f, "expected hit near bounds edge 4.0, got $d")
    }

    @Test
    fun `path clear rejects a path through the wall`() {
        // Wall at (0,2) half (0.5,1,0.5) spans x -0.5..0.5, z 1.5..2.5.
        // A straight line at z=2 from x=-3 to x=3 crosses it.
        assertFalse(world.isPathClear(-3f, 2f, 3f, 2f))
        // A line along z=-3 avoids the wall.
        assertTrue(world.isPathClear(-3f, -3f, 3f, -3f))
    }
}
