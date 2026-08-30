package dev.vrm.runtime.core.expression

import dev.vrm.runtime.core.gltf.Gltf
import dev.vrm.runtime.core.gltf.Node
import dev.vrm.runtime.core.vrm.VRMCVrm
import dev.vrm.runtime.core.vrm.VrmExpression as VrmExpressionSchema

/**
 * Supplies the engine-specific targets that expression binds drive.
 *
 * The core is engine-agnostic; an implementation is provided by the host:
 *  - A headless / glTF-backed implementation for tests (see the test sources).
 *  - A Filament-backed implementation that reads/writes RenderableManager
 *    morph weights and material parameters (demo).
 */
interface ExpressionBindProvider {

    /**
     * Resolve the morph-target channel of the mesh attached to [nodeIndex].
     *
     * @return a channel whose [MorphTargetChannel.getMorphWeight] index is the
     *   glTF morph target index, or `null` if the node's mesh has no such
     *   morph target (bind is skipped, mirroring three-vrm's warning+skip).
     */
    fun morphTargetChannel(nodeIndex: Int, morphIndex: Int): MorphTargetChannel?

    /**
     * Resolve the material color access for the glTF material [materialIndex],
     * or `null` if material color binds are unsupported for it.
     */
    fun materialColorAccess(materialIndex: Int): MaterialColorAccess?

    /**
     * Resolve the texture transform access for the glTF material
     * [materialIndex], or `null` if texture transform binds are unsupported.
     */
    fun textureTransformAccess(materialIndex: Int): TextureTransformAccess?
}

/**
 * Builds an [ExpressionManager] from a VRM 1.0 `VRMC_vrm` extension.
 *
 * Port of three-vrm's `VRMExpressionLoaderPlugin._v1Import`
 * (VRMExpressionLoaderPlugin.ts), synchronous and engine-agnostic:
 *  - preset expressions are registered under their map key (which must be a
 *    known [ExpressionPresetName]).
 *  - custom expressions may not use a preset name (skipped with a warning).
 *  - each expression's binds are resolved through [ExpressionBindProvider].
 *
 * @param provider how to resolve morph channels / material accesses
 */
class ExpressionLoader(private val provider: ExpressionBindProvider) {


    /**
     * Import the expressions of a VRM. Returns `null` when the VRM carries no
     * `expressions` section.
     */
    fun load(gltf: Gltf, vrm: VRMCVrm): ExpressionManager? {
        val schemaExpressions = vrm.expressions ?: return null

        // ---- list expressions (preset + custom), validating names ----
        val nameSchemaMap = LinkedHashMap<String, VrmExpressionSchema>()

        schemaExpressions.preset?.forEach { (name, schemaExpression) ->
            if (name !in ExpressionPresetName.ALL) {
                // three-vrm: warn "Unknown preset name" and ignore
                return@forEach
            }
            nameSchemaMap[name] = schemaExpression
        }

        schemaExpressions.custom?.forEach { (name, schemaExpression) ->
            if (name in ExpressionPresetName.ALL) {
                // three-vrm: warn "Custom expression cannot have preset name" and ignore
                return@forEach
            }
            nameSchemaMap[name] = schemaExpression
        }

        // ---- build manager ----
        val manager = ExpressionManager()

        for ((name, schemaExpression) in nameSchemaMap) {
            val expression = VrmExpression(name)
            expression.isBinary = schemaExpression.isBinary ?: false
            expression.overrideBlink = parseOverride(schemaExpression.overrideBlink)
            expression.overrideLookAt = parseOverride(schemaExpression.overrideLookAt)
            expression.overrideMouth = parseOverride(schemaExpression.overrideMouth)

            // morph target binds
            schemaExpression.morphTargetBinds?.forEach { bind ->
                val channel = provider.morphTargetChannel(bind.node, bind.index) ?: return@forEach
                expression.addBind(MorphTargetBind(channel, bind.index, bind.weight))
            }

            // material color binds
            schemaExpression.materialColorBinds?.forEach { bind ->
                val access = provider.materialColorAccess(bind.material) ?: return@forEach
                expression.addBind(
                    MaterialColorBind(access, MaterialColorType.fromJson(bind.type), bind.targetValue.toFloatArray())
                )
            }

            // texture transform binds
            schemaExpression.textureTransformBinds?.forEach { bind ->
                val access = provider.textureTransformAccess(bind.material) ?: return@forEach
                expression.addBind(
                    TextureTransformBind(
                        access = access,
                        materialIndex = bind.material,
                        scaleX = bind.scale?.getOrNull(0) ?: 1f,
                        scaleY = bind.scale?.getOrNull(1) ?: 1f,
                        offsetX = bind.offset?.getOrNull(0) ?: 0f,
                        offsetY = bind.offset?.getOrNull(1) ?: 0f,
                    )
                )
            }

            manager.registerExpression(expression)
        }

        return manager
    }

    private fun parseOverride(value: String?): ExpressionOverrideType = when (value) {
        "block" -> ExpressionOverrideType.BLOCK
        "blend" -> ExpressionOverrideType.BLEND
        else -> ExpressionOverrideType.NONE
    }
}

/**
 * A glTF-backed [ExpressionBindProvider] useful for headless evaluation and
 * tests. Morph weights are stored per-node in memory; material accessors are
 * supplied by [materialColorAccesses] / [textureTransformAccesses] (absent
 * materials resolve to `null`, so their binds are skipped).
 */
class GltfExpressionBindProvider(
    private val gltf: Gltf,
    private val materialColorAccesses: Map<Int, MaterialColorAccess> = emptyMap(),
    private val textureTransformAccesses: Map<Int, TextureTransformAccess> = emptyMap(),
) : ExpressionBindProvider {

    private val channels = HashMap<Int, MorphTargetChannel>()

    override fun morphTargetChannel(nodeIndex: Int, morphIndex: Int): MorphTargetChannel? {
        val node = gltf.node(nodeIndex) ?: return null
        val mesh = node.mesh?.let { gltf.mesh(it) } ?: return null

        // every primitive of the mesh must have enough morph targets
        val primitives = mesh.primitives
        if (primitives.isEmpty()) return null
        val ok = primitives.all { p ->
            val targets = p.targets
            targets != null && morphIndex < targets.size
        }
        if (!ok) return null

        return channels.getOrPut(nodeIndex) {
            // allocate one channel sized to the largest primitive's target count
            val maxTargets = primitives.maxOf { it.targets?.size ?: 0 }
            InMemoryMorphTargetChannel(FloatArray(maxTargets))
        }
    }

    override fun materialColorAccess(materialIndex: Int): MaterialColorAccess? =
        materialColorAccesses[materialIndex]

    override fun textureTransformAccess(materialIndex: Int): TextureTransformAccess? =
        textureTransformAccesses[materialIndex]
}

/** Small glTF index helpers used by the loaders. */
private fun Gltf.node(index: Int): Node? = nodes?.getOrNull(index)
private fun Gltf.mesh(index: Int): dev.vrm.runtime.core.gltf.Mesh? = meshes?.getOrNull(index)
