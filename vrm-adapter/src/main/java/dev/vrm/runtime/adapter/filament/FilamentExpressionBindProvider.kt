package dev.vrm.runtime.adapter.filament

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import dev.vrm.runtime.core.expression.ChannelProperty
import dev.vrm.runtime.core.expression.ExpressionBindProvider
import dev.vrm.runtime.core.expression.MaterialColorAccess
import dev.vrm.runtime.core.expression.MaterialColorType
import dev.vrm.runtime.core.expression.MorphTargetChannel
import dev.vrm.runtime.core.expression.ResolvedMaterialColor
import dev.vrm.runtime.core.expression.ScalarProperty
import dev.vrm.runtime.core.expression.TextureTransformAccess
import dev.vrm.runtime.core.gltf.Material as GltfMaterial
import dev.vrm.runtime.core.gltf.Mesh
import dev.vrm.runtime.core.gltf.Node

/**
 * An [ExpressionBindProvider] + [MorphTargetChannel] pair backed by a Filament
 * [FilamentInstance].
 *
 * Resolves morph-target weights through `RenderableManager.setMorphWeights`
 * and exposes a per-frame [commitAll] that pushes accumulated weights to
 * Filament (call it once per frame after `ExpressionManager.update()`).
 *
 * ALSO resolves `materialColor` expression binds onto the Filament materials'
 * `baseColor` uniform (framework: only [MaterialColorType.COLOR] maps; the
 * MToon-specific channels shadeColor/rimColor/outlineColor/emissionColor/matcap
 * resolve to `null` and are skipped gracefully, since the PBR fallback material
 * has no such uniform).
 *
 * CRITICAL: gltfio does NOT map `instance.entities[nodeIndex]` to the
 * renderable of node `nodeIndex` for morph targets (verified: twist Face
 * node=1 -> entities[1]=9 with 0 morphs, but the face mesh has 57). We look
 * the node up BY NAME in the asset and use that entity's renderable.
 */
class FilamentExpressionBindProvider(
    private val engine: Engine,
    instance: FilamentInstance,
    private val nodeNames: Map<Int, String> = emptyMap(),
    gltfNodes: List<Node>? = null,
    gltfMeshes: List<Mesh>? = null,
    gltfMaterials: List<GltfMaterial>? = null,
) : ExpressionBindProvider {

    private val renderableManager: RenderableManager = engine.renderableManager
    private val asset: FilamentAsset = instance.getAsset()
    private val entities: IntArray = instance.entities

    private val channels = HashMap<String, MorphChannel>()

    /** materialIndex -> Filament entities (nodes whose mesh primitive references
     *  the material, resolved BY NAME as for morph bindings). */
    private val materialEntities: Map<Int, IntArray>

    /** materialIndex -> glTF rest base color (rgba), the bind's "initial". */
    private val baseColorFactors: Map<Int, FloatArray>

    init {
        // 1) material -> node indices
        val materialToNodes = HashMap<Int, MutableList<Int>>()
        val materialCount = gltfMaterials?.size ?: 0
        gltfNodes?.forEachIndexed { ni, n ->
            val meshIdx = n.mesh ?: return@forEachIndexed
            val mesh = gltfMeshes?.getOrNull(meshIdx) ?: return@forEachIndexed
            mesh.primitives.forEach { prim ->
                val mi = prim.material ?: return@forEach
                if (mi in 0 until materialCount) {
                    materialToNodes.getOrPut(mi) { mutableListOf() }.add(ni)
                }
            }
        }
        // 2) node -> entities by name (reliable, like morph)
        val resolved = HashMap<Int, MutableList<Int>>()
        for ((mi, nodeIdxs) in materialToNodes) {
            val ents = ArrayList<Int>()
            for (ni in nodeIdxs) {
                val name = nodeNames[ni] ?: continue
                val named = asset.getEntitiesByName(name)
                if (named != null) for (e in named) if (e != 0 && e !in ents) ents.add(e)
            }
            if (ents.isNotEmpty()) resolved[mi] = ents
        }
        materialEntities = resolved.mapValues { it.value.toIntArray() }

        // 3) initial base color factors
        val factors = HashMap<Int, FloatArray>()
        gltfMaterials?.forEachIndexed { mi, m ->
            val f = m.pbrMetallicRoughness?.baseColorFactor
            factors[mi] =
                if (f != null && f.size >= 4) floatArrayOf(f[0], f[1], f[2], f[3])
                else floatArrayOf(1f, 1f, 1f, 1f)
        }
        baseColorFactors = factors
    }

    override fun materialColorAccess(materialIndex: Int): MaterialColorAccess? {
        val ents = materialEntities[materialIndex] ?: return null
        val factors = baseColorFactors[materialIndex] ?: return null

        val instances = ArrayList<MaterialInstance>()
        for (e in ents) {
            val ri = renderableManager.getInstance(e)
            if (ri == 0) continue
            val pc = renderableManager.getPrimitiveCount(ri)
            for (p in 0 until pc) {
                // getMaterialInstanceAt takes the RENDERABLE INSTANCE, not the entity.
                val mi = renderableManager.getMaterialInstanceAt(ri, p)
                if (mi != null) instances.add(mi)
            }
        }
        if (instances.isEmpty()) return null
        return FilamentMaterialColorAccess(instances, factors)
    }

    /**
     * A [MaterialColorAccess] that drives a Filament `baseColor` uniform.
     * Only [MaterialColorType.COLOR] maps (other channels are MToon-only and
     * return null -> bind silently skipped).
     */
    private class FilamentMaterialColorAccess(
        private val instances: List<MaterialInstance>,
        initialRgba: FloatArray,
    ) : MaterialColorAccess {
        private val current = initialRgba.copyOf() // [r,g,b,a]

        /** Resolve the real baseColor parameter name (gltfio materials may use
         *  baseColor/color, etc.), and confirm it exists on every instance;
         *  instances without it are skipped (avoids a native crash on setParameter). */
        private val pushable: List<Pair<MaterialInstance, String>> = run {
            val resolved = ArrayList<Pair<MaterialInstance, String>>()
            for (mi in instances) {
                val mat = mi.material
                val name = listOf("baseColor", "color", "baseColorFactor").firstOrNull { mat.hasParameter(it) }
                if (name != null) resolved.add(mi to name)
                else android.util.Log.w("AvatarEngine", "materialColor: material '${mat.name}' has no baseColor param (params=${mat.parameterCount})")
            }
            resolved
        }

        private fun push() {
            val a = current
            // float4 setter: setParameter(String, float, float, float, float)
            for ((mi, name) in pushable) mi.setParameter(name, a[0], a[1], a[2], a[3])
        }

        private val colorProp = object : ChannelProperty {
            override fun get(): FloatArray = floatArrayOf(current[0], current[1], current[2])
            override fun set(r: Float, g: Float, b: Float) {
                current[0] = r
                current[1] = g
                current[2] = b
                push()
            }
        }

        private val alphaProp = object : ScalarProperty {
            override fun get(): Float = current[3]
            override fun set(v: Float) {
                current[3] = v
                push()
            }
        }

        override fun resolve(type: MaterialColorType): ResolvedMaterialColor? {
            if (pushable.isEmpty() || type != MaterialColorType.COLOR) return null
            return object : ResolvedMaterialColor {
                override val colorProp = this@FilamentMaterialColorAccess.colorProp
                override val alphaProp = this@FilamentMaterialColorAccess.alphaProp
                override val initialColor = floatArrayOf(current[0], current[1], current[2])
                override val initialAlpha = current[3]
            }
        }
    }

    /**
     * Composite morph channel: VRoid-style meshes (Face.baked etc.) duplicate
     * the SAME set of morph targets across MULTIPLE primitives (each primitive
     * is a separate Filament renderable/entity with its own material). Writing
     * only the first entity's morphs leaves the visible mouth/hair on other
     * primitives unaffected. This channel writes the same weights to EVERY
     * entity resolved for the node name, so the morph moves on all regions.
     */
    private inner class MorphChannel(
        private val entities: IntArray,
        val morphCount: Int,
    ) : MorphTargetChannel {
        val entityCount: Int get() = entities.size
        private val weights = FloatArray(morphCount)

        override fun getMorphWeight(index: Int): Float =
            if (index in weights.indices) weights[index] else 0f

        override fun setMorphWeight(index: Int, value: Float) {
            if (index in weights.indices) weights[index] = value
        }

        fun commit() {
            if (morphCount > 0) {
                // CRITICAL FIX (08-27): the 3rd arg is OFFSET, not count.
                // The native layer reads weights.length elements starting at
                // offset. Passing weights.size as offset meant "start at the
                // last morph" -> out of range -> ZERO weights applied, so no
                // expression ever moved. Correct call: offset=0 applies the
                // full array from the first morph.
                for (entity in entities) {
                    val renderable = renderableManager.getInstance(entity)
                    if (renderable != 0) {
                        renderableManager.setMorphWeights(renderable, weights, 0)
                    }
                }
            }
        }
    }

    override fun morphTargetChannel(nodeIndex: Int, morphIndex: Int): MorphTargetChannel? {
        // CRITICAL FIX (08-24): resolve by NAME first. Verified on
        // VRoid_Sample_B: instance.entities[157]=e=166 has r=0 (invalid
        // renderable, no morphs), while asset.getEntitiesByName("Face")=e=11
        // IS the real Face renderable (morph=41). Index-based lookup is
        // unreliable here; name lookup wins.
        val name = nodeNames[nodeIndex]
        var resolved: IntArray? = if (name != null) asset.getEntitiesByName(name) else null
        if (resolved == null || resolved.isEmpty()) {
            if (nodeIndex in entities.indices && entities[nodeIndex] != 0) {
                resolved = intArrayOf(entities[nodeIndex])
            }
        }
        if (resolved == null || resolved.isEmpty()) {
            android.util.Log.d("AvatarEngine", "morph: node=$nodeIndex no entity")
            return null
        }
        // keep only entities that actually have morph targets; use the max count
        // (all prims of a VRoid face expose the same morph set)
        val withMorphs = resolved.filter { e ->
            val r = renderableManager.getInstance(e)
            r != 0 && renderableManager.getMorphTargetCount(r) > 0
        }
        if (withMorphs.isEmpty()) {
            android.util.Log.d("AvatarEngine", "morph: node=$nodeIndex entities=${resolved.size} no morph targets")
            return null
        }
        val count = withMorphs.maxOf { e -> renderableManager.getMorphTargetCount(renderableManager.getInstance(e)) }
        if (morphIndex >= count) {
            android.util.Log.d("AvatarEngine", "morph: node=$nodeIndex morphIndex=$morphIndex >= count=$count")
            return null
        }
        return channels.getOrPut(name ?: "n$nodeIndex") { MorphChannel(withMorphs.toIntArray(), count) }
    }

    override fun textureTransformAccess(materialIndex: Int): TextureTransformAccess? = null

    /** Push every accumulated channel's weights to Filament. Main thread only. */
    fun commitAll() {
        for (channel in channels.values) {
            channel.commit()
        }
    }
}