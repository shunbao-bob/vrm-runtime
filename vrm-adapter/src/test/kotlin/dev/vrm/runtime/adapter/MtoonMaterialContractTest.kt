package dev.vrm.runtime.adapter

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class MtoonMaterialContractTest {
    private val projectDir = File(requireNotNull(System.getProperty("user.dir")))

    @Test
    fun `all blending variants implement normal matcap and emission paths`() {
        val variants = listOf("opaque", "masked", "transparent")

        for (variant in variants) {
            val source = projectDir.resolve("src/main/assets/materials/mtoon_$variant.mat").readText()
            val requiredTokens = listOf(
                "name : normalMap",
                "name : normalScale",
                "getWorldTangentFrame()",
                "name : matcapTexture",
                "name : matcapFactor",
                "getWorldViewVector()",
                "name : emissiveMap",
                "name : emissiveFactor",
                "name : uvAnimationMaskTexture",
                "name : uvAnimationOffset",
                "name : uvAnimationRotation",
            )
            for (token in requiredTokens) {
                assertTrue(source.contains(token), "$variant material is missing: $token")
            }
        }
    }

    @Test
    fun `runtime binds every extended mtoon texture with the correct color space`() {
        val source = projectDir.resolve(
            "src/main/java/dev/vrm/runtime/adapter/filament/MToonMaterialApplier.kt",
        ).readText()

        val requiredTokens = listOf(
            "\"normalMap\", params.normalMapIndex, TextureColorSpace.LINEAR",
            "\"shadingShiftMap\", params.shadingShiftTextureIndex, TextureColorSpace.LINEAR",
            "\"matcapTexture\", params.matcapTextureIndex, TextureColorSpace.SRGB",
            "\"rimMultiplyTexture\", params.rimMultiplyTextureIndex, TextureColorSpace.SRGB",
            "\"emissiveMap\", params.emissiveTextureIndex, TextureColorSpace.SRGB",
            "\"uvAnimationMaskTexture\", params.uvAnimationMaskTextureIndex, TextureColorSpace.LINEAR",
        )
        for (token in requiredTokens) {
            assertTrue(source.contains(token), "runtime binding is missing: $token")
        }
    }

    @Test
    fun `runtime applies alpha culling depth and primitive blend order`() {
        val source = projectDir.resolve(
            "src/main/java/dev/vrm/runtime/adapter/filament/MToonMaterialApplier.kt",
        ).readText()

        assertTrue(source.contains("if (matName == \"masked\") mi.setMaskThreshold(params.alphaCutoff)"))
        assertTrue(source.contains("mi.setDoubleSided(params.doubleSided)"))
        assertTrue(source.contains("mi.setDepthWrite"))
        assertTrue(source.contains("setBlendOrderAt"))
    }

    @Test
    fun `avatar renderer advances mtoon uv animation every frame`() {
        val source = projectDir.resolve(
            "src/main/java/dev/vrm/runtime/adapter/AvatarRenderer.kt",
        ).readText()
        assertTrue(source.contains("mtoonApplier?.update(deltaSeconds)"))
    }
}
