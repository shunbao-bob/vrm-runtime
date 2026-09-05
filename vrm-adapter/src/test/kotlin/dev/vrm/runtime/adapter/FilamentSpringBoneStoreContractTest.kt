package dev.vrm.runtime.adapter

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class FilamentSpringBoneStoreContractTest {
    @Test
    fun `spring transforms use TransformManager instance not entity`() {
        val source = File(
            "src/main/java/dev/vrm/runtime/adapter/filament/FilamentSpringBoneStore.kt",
        ).readText()

        assertTrue(source.contains("transformManager.getTransform(i, tmpMat)"))
        assertTrue(source.contains("transformManager.setTransform(i,"))
        assertFalse(source.contains("transformManager.getTransform(entity,"))
        assertFalse(source.contains("transformManager.setTransform(entity,"))
    }
}
