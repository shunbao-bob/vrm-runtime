package dev.vrm.runtime.core.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Vec3ApiTest {
    @Test
    fun `vec3 copy exists and clones`() {
        val a = Vec3(1f, 2f, 3f)
        val b = a.copy()
        b.x = 9f
        assertEquals(1f, a.x)
        assertEquals(9f, b.x)
    }

    @Test
    fun `quat copy exists`() {
        val q = Quat(1f, 2f, 3f, 4f)
        val c = q.copy()
        c.x = 99f
        assertEquals(1f, q.x)
        assertEquals(99f, c.x)
    }
}