package dev.vrm.runtime.adapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocomotionMathTest {
    @Test
    fun `stop duration converts to rate and integrates forward distance`() {
        val rate = LocomotionMath.decelerationForDuration(speed = 1.5f, durationSeconds = 0.5f)
        var speed = 1.5f
        var distance = 0f
        repeat(5) {
            val step = LocomotionMath.decelerationStep(speed, rate, 0.1f)
            speed = step.speed
            distance += step.distance
        }

        assertEquals(3f, rate, 0.0001f)
        assertEquals(0f, speed, 0.0001f)
        assertEquals(0.375f, distance, 0.0001f)
    }

    @Test
    fun `final deceleration frame integrates only until stop time`() {
        val step = LocomotionMath.decelerationStep(
            speed = 1f,
            deceleration = 2f,
            deltaSeconds = 1f,
        )

        assertEquals(0f, step.speed, 0.0001f)
        assertEquals(0.25f, step.distance, 0.0001f)
    }

    @Test
    fun `braking direction follows latest displacement across yaw wrap`() {
        val previous = LocomotionMath.Direction(
            x = kotlin.math.sin(Math.toRadians(179.0)).toFloat(),
            z = kotlin.math.cos(Math.toRadians(179.0)).toFloat(),
        )
        val nextYaw = Math.toRadians(-179.0)
        val direction = LocomotionMath.movementDirection(
            deltaX = kotlin.math.sin(nextYaw).toFloat() * 0.2f,
            deltaZ = kotlin.math.cos(nextYaw).toFloat() * 0.2f,
            previous = previous,
        )

        assertEquals(kotlin.math.sin(nextYaw).toFloat(), direction.x, 0.0001f)
        assertEquals(kotlin.math.cos(nextYaw).toFloat(), direction.z, 0.0001f)
    }

    @Test
    fun `move step never overshoots target`() {
        val result = LocomotionMath.stepToward(0f, 0f, 0.1f, 0f, 3f, 1f)
        assertEquals(0.1f, result.x, 0.0001f)
        assertEquals(0f, result.z, 0.0001f)
        assertTrue(result.arrived)
    }

    @Test
    fun `invalid movement inputs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocomotionMath.validateMove(Float.NaN, 1f, 0.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocomotionMath.validateMove(1f, 0f, 0.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocomotionMath.validateMove(1f, 1f, -0.1f)
        }
    }

    @Test
    fun `stop deceleration reduces speed over time instead of stopping instantly`() {
        val first = LocomotionMath.decelerate(2f, 3f, 0.25f)
        assertEquals(1.25f, first, 0.0001f)
        assertFalse(first == 0f)
        assertEquals(0f, LocomotionMath.decelerate(first, 3f, 1f), 0.0001f)
    }

    @Test
    fun `visual and node yaw consistently account for baked facing offset`() {
        assertEquals(180f, LocomotionMath.visualYaw(0f, 180f), 0.0001f)
        assertEquals(0f, LocomotionMath.nodeYawForVisual(180f, 180f), 0.0001f)
    }
}