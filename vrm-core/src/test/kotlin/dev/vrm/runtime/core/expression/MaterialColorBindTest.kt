package dev.vrm.runtime.core.expression

import dev.vrm.runtime.core.vrm.VrmLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M-08-29: the materialColorBinds expression material color-change pipeline.
 *
 * Synthesizes an expression with `materialColorBinds` (happy: color -> [1,0.4,0.4,1]),
 * parses it with [ExpressionLoader] into a [MaterialColorBind], and verifies with a
 * recording [MaterialColorAccess]:
 *  - the binding is parsed and registered
 *  - applyWeight lerps the color from the initial value toward the target by weight
 *  - clearAppliedWeight restores the initial color
 *
 * This is a core-layer logic verification; the Filament side
 * (FilamentExpressionBindProvider) only wires the same logic to the baseColor uniform
 * (it compiles, but needs on-device visual acceptance).
 */
class MaterialColorBindTest {

    /** Recording MaterialColorAccess: reads back the current RGBA to verify the driven result. */
    private class RecordingMaterialColorAccess : MaterialColorAccess {
        var current = floatArrayOf(1f, 1f, 1f, 1f) // [r,g,b,a]
        val colorProp = object : ChannelProperty {
            override fun get(): FloatArray = floatArrayOf(current[0], current[1], current[2])
            override fun set(r: Float, g: Float, b: Float) {
                current[0] = r; current[1] = g; current[2] = b
            }
        }
        val alphaProp = object : ScalarProperty {
            override fun get(): Float = current[3]
            override fun set(v: Float) { current[3] = v }
        }
        override fun resolve(type: MaterialColorType): ResolvedMaterialColor? {
            if (type != MaterialColorType.COLOR) return null
            return object : ResolvedMaterialColor {
                override val colorProp = this@RecordingMaterialColorAccess.colorProp
                override val alphaProp = this@RecordingMaterialColorAccess.alphaProp
                override val initialColor = floatArrayOf(current[0], current[1], current[2])
                override val initialAlpha = current[3]
            }
        }
    }

    private fun build(): Pair<ExpressionManager, RecordingMaterialColorAccess> {
        // Synthesize a VRM: one node(mesh) + one material(baseColor=white) + happy expression
        // materialColorBinds: target color = [1, 0.4, 0.4, 1]
        val gltf = """
        {
          "asset": {"version": "2.0"},
          "scene": 0,
          "scenes": [{"nodes":[0]}],
          "nodes": [{"name":"Body","mesh":0}],
          "meshes": [{"primitives":[{"attributes":{"POSITION":0},"material":0}]}],
          "materials": [{"pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1]}}],
          "buffers": [],
          "bufferViews": [],
          "accessors": [],
          "extensionsUsed": ["VRMC_vrm"],
          "extensions": {
            "VRMC_vrm": {
              "specVersion": "1.0",
              "meta": {"name": "test", "version": "1.0", "authors": [], "contactInformation": "", "references": [], "thirdPartyLicenses": "", "avatarPermission": "everyone", "allowExcessivelyViolentUsage": false, "allowExcessivelySexualUsage": false, "commercialUssageName": "", "commercialUsageAllowed": false, "politicalOrReligiousUsage": false, "antisocialOrHateUsage": false},
              "humanoid": {
                "humanBones": {"hips": {"node": 0}}
              },
              "expressions": {
                "preset": {
                  "happy": {
                    "materialColorBinds": [{"material":0,"type":"color","targetValue":[1,0.4,0.4,1]}]
                  }
                }
              }
            }
          }
        }
        """.trimIndent()
        // Plain JSON glTF (no BIN) — can VrmLoader parse it? GLB requires a full container.
        // Here we simply wrap it in a Glb container.
        val bin = ByteArray(0)
        val jsonBytes = gltf.toByteArray(Charsets.UTF_8)
        val pad = (4 - jsonBytes.size % 4) % 4
        val jsonPadded = jsonBytes + ByteArray(pad) { 0x20 }
        val total = 12 + 8 + jsonPadded.size
        val out = java.io.ByteArrayOutputStream()
        out.write(int32(0x46546C67)); out.write(int32(2)); out.write(int32(total))
        out.write(int32(jsonPadded.size)); out.write(int32(0x4E4F534A))
        out.write(jsonPadded)
        val vrm = VrmLoader.load(out.toByteArray())

        val access = RecordingMaterialColorAccess()
        val provider = object : ExpressionBindProvider {
            override fun morphTargetChannel(nodeIndex: Int, morphIndex: Int): MorphTargetChannel? = null
            override fun materialColorAccess(materialIndex: Int): MaterialColorAccess? = access
            override fun textureTransformAccess(materialIndex: Int): TextureTransformAccess? = null
        }
        val manager = ExpressionLoader(provider).load(vrm.gltf, vrm.vrm!!)
            ?: throw AssertionError("must build manager")
        return manager to access
    }

    private fun int32(v: Int): ByteArray =
        java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    @Test
    fun `materialColorBind is parsed and applies weight toward target`() {
        val (manager, access) = build()
        val happy = manager.getExpression("happy")
        assertNotNull(happy, "happy expression must exist")
        assertNotNull(happy!!.binds.firstOrNull { it is MaterialColorBind },
            "happy must carry a MaterialColorBind")

        // weight 0 -> color unchanged (white)
        happy.weight = 0f
        happy.applyWeight(1f)
        assertEquals(1f, access.current[0], 1e-3f)
        assertEquals(1f, access.current[1], 1e-3f)

        // weight 1 -> color = target [1, 0.4, 0.4]
        happy.weight = 1f
        happy.applyWeight(1f)
        assertEquals(1f, access.current[0], 1e-3f)
        assertEquals(0.4f, access.current[1], 1e-3f)
        assertEquals(0.4f, access.current[2], 1e-3f)

        // half weight -> lerp halfway: white->[1,0.4,0.4] mid = [1,0.7,0.7]
        access.current = floatArrayOf(1f, 1f, 1f, 1f)
        happy.weight = 0.5f
        happy.applyWeight(1f)
        assertEquals(1f, access.current[0], 1e-3f)
        assertEquals(0.7f, access.current[1], 1e-3f)
        assertEquals(0.7f, access.current[2], 1e-3f)
    }

    @Test
    fun `clearAppliedWeight restores initial color`() {
        val (manager, access) = build()
        val happy = manager.getExpression("happy")!!
        happy.weight = 1f
        happy.applyWeight(1f)
        assertEquals(0.4f, access.current[1], 1e-3f)

        happy.clearAppliedWeight()
        assertEquals(1f, access.current[1], 1e-3f, "clear must restore white")
    }
}
