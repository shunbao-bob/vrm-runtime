package dev.vrm.runtime.adapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AvatarLoadLifecycleTest {
    @Test
    fun `only newest non-destroyed request may apply`() {
        val lifecycle = AvatarLoadLifecycle()
        val first = lifecycle.beginLoad()
        val second = lifecycle.beginLoad()

        assertFalse(lifecycle.mayApply(first))
        assertTrue(lifecycle.mayApply(second))
        lifecycle.destroy()
        assertFalse(lifecycle.mayApply(second))
    }

    @Test
    fun `publication runs only for current live generation`() {
        val lifecycle = AvatarLoadLifecycle()
        val stale = lifecycle.beginLoad()
        val current = lifecycle.beginLoad()
        val published = mutableListOf<String>()

        assertFalse(lifecycle.applyIfCurrent(stale) { published += "stale" })
        assertTrue(lifecycle.applyIfCurrent(current) { published += "current" })
        lifecycle.destroy()
        assertFalse(lifecycle.applyIfCurrent(current) { published += "destroyed" })
        assertEquals(listOf("current"), published)
    }

    @Test
    fun `resources release in controller node applier order despite a failure`() {
        val events = mutableListOf<String>()
        val resources = AvatarLoadResources(
            node = { events += "node" },
            applier = { events += "applier" },
            controller = { events += "controller"; error("controller failed") },
        )

        resources.release()

        assertEquals(listOf("controller", "node", "applier"), events)
    }
}