package dev.vrm.runtime.adapter.filament

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import dev.vrm.runtime.core.gltf.Gltf
import dev.vrm.runtime.core.mtoon.MtoonLoader
import dev.vrm.runtime.core.mtoon.MtoonMaterialParameters
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies a VRM MToon material to a gltfio model to restore cel-shaded rendering.
 *
 * Replaces gltfio's default PBR material with the matc-precompiled
 * mtoon_{opaque,masked,transparent}.filamat (unlit + manual cel/toon lighting).
 * Textures are extracted from the GLB BIN chunk as PNG, decoded with Android
 * BitmapFactory, and bound to the material samplers as Filament Textures.
 *
 * The matching blending variant (OPAQUE/MASK/BLEND ->
 * opaque/masked/transparent) is chosen per glTF material's alphaMode, so the
 * texture's transparent regions (eye iris 81% transparent, skirt 36%
 * transparent) are properly clipped/blended instead of rendering as black
 * blocks.
 *
 * Main thread only.
 */
class MToonMaterialApplier(
    private val engine: Engine,
    instance: FilamentInstance,
    private val gltf: Gltf,
    private val binary: ByteArray?,  // GLB BIN chunk (source of texture PNG bytes)
    /** Direction pointing FROM the surface TOWARD the scene directional light.
     *  This is the negation of StageConfig.directionalLightPosition (which is
     *  the light propagation direction). The shader's lightDir must track the
     *  configured stage light, otherwise the MToon cel shading stays stuck on
     *  the mat's hardcoded default and ignores host light changes. */
    private val lightDir: FloatArray = floatArrayOf(0f, -0.2f, 1f),
) {
    private val renderableManager: RenderableManager = engine.renderableManager
    private val asset: FilamentAsset = instance.getAsset()

    /** Cache of blending variant name -> Material. */
    private val materialCache = HashMap<String, Material>()
    private enum class TextureColorSpace { SRGB, LINEAR }

    /** A texture can be sampled as color or data, so color space is part of the cache key. */
    private val textureCache = HashMap<Pair<Int, TextureColorSpace>, Texture>()

    /** The .filamat bytes of the three blending variants. */
    class Filamat(
        val opaque: ByteArray,
        val masked: ByteArray,
        val transparent: ByteArray,
    )

    /**
     * Apply the MToon material. Call it after the model instance is created
     * and before the first frame is rendered.
     *
     * Replace materials via the SceneView ModelNode's RenderableNode list —
     * **do NOT iterate asset.getRenderableEntities() yourself**: the renderable
     * instances obtained that way are not the same ones SceneView actually
     * renders (verified: after setMaterialInstanceAt the model still uses the
     * gltfio default material, and a solid-color shader output doesn't show
     * either). You must use RenderableNode.getRenderableInstance() already
     * resolved by the ModelNode.
     *
     * @param renderableNodes the model's renderable nodes from SceneView (ModelNode.getRenderableNodes())
     */
    fun apply(
        mtoonSchemas: Map<Int, dev.vrm.runtime.core.vrm.VrmcMaterialsMtoon>,
        filamat: Filamat,
        renderableNodes: List<io.github.sceneview.node.RenderableNode>,
    ) {
        val loader = MtoonLoader(gltf, mtoonSchemas)
        val paramsByMat = loader.loadAll()

        // Preload the 3 blending variant Materials
        val materials = HashMap<String, Material>()
        loadMaterial("opaque", filamat.opaque)?.let { materials["opaque"] = it }
        loadMaterial("masked", filamat.masked)?.let { materials["masked"] = it }
        loadMaterial("transparent", filamat.transparent)?.let { materials["transparent"] = it }
        if (materials.isEmpty()) {
            android.util.Log.w("MToon", "no mtoon material variants loaded")
            return
        }

        // material index -> MaterialInstance (pick the blending variant by alphaMode and bind textures)
        val miByMat = HashMap<Int, MaterialInstance>()
        gltf.materials?.forEachIndexed { matIdx, mat ->
            if (!paramsByMat.containsKey(matIdx)) return@forEachIndexed
            val alphaMode = mat.alphaMode
            val matName = when (alphaMode?.uppercase()) {
                "MASK" -> "masked"
                "BLEND" -> "transparent"
                else -> "opaque"
            }
            val m = materials[matName] ?: materials.values.firstOrNull() ?: return@forEachIndexed
            val mi = m.createInstance()
            instances.add(mi)
            val params = paramsByMat[matIdx] ?: MtoonMaterialParameters()
            bindParams(m, mi, params)
            // Filament only generates the internal _maskThreshold uniform for
            // materials compiled with blending: masked. Calling this setter on
            // opaque/transparent materials is a native PreconditionPanic.
            if (matName == "masked") mi.setMaskThreshold(params.alphaCutoff)
            mi.setDoubleSided(params.doubleSided)
            mi.setDepthWrite(matName != "transparent" || params.transparentWithZWrite)
            bindTexture(m, mi, "baseColorMap", params.baseColorTextureIndex, TextureColorSpace.SRGB)
            bindTexture(m, mi, "shadeColorMap", params.shadeMultiplyTextureIndex, TextureColorSpace.SRGB)
            bindTexture(m, mi, "normalMap", params.normalMapIndex, TextureColorSpace.LINEAR)
            bindTexture(m, mi, "shadingShiftMap", params.shadingShiftTextureIndex, TextureColorSpace.LINEAR)
            bindTexture(m, mi, "matcapTexture", params.matcapTextureIndex, TextureColorSpace.SRGB)
            bindTexture(m, mi, "rimMultiplyTexture", params.rimMultiplyTextureIndex, TextureColorSpace.SRGB)
            bindTexture(m, mi, "emissiveMap", params.emissiveTextureIndex, TextureColorSpace.SRGB)
            bindTexture(m, mi, "uvAnimationMaskTexture", params.uvAnimationMaskTextureIndex, TextureColorSpace.LINEAR)
            animatedInstances.add(AnimatedMaterial(mi, params))
            miByMat[matIdx] = mi
        }

        // node name -> glTF node index
        val nodeIndexByName = HashMap<String, Int>()
        gltf.nodes?.forEachIndexed { i, n -> n.name?.let { nodeIndexByName[it] = i } }

        var replaced = 0
        var bound = 0
        for (rn in renderableNodes) {
            val renderable = rn.renderableInstance
            if (renderable == 0) continue
            val primCount = renderableManager.getPrimitiveCount(renderable)
            val nodeIdx = nodeIndexByName[rn.name]
            val mesh = nodeIdx?.let { gltf.nodes?.getOrNull(it)?.mesh }?.let { gltf.meshes?.getOrNull(it) }
            for (p in 0 until primCount) {
                val matIdx = mesh?.primitives?.getOrNull(p)?.material
                val mi = if (matIdx != null) miByMat[matIdx] else null
                if (mi != null) {
                    renderableManager.setMaterialInstanceAt(renderable, p, mi)
                    val params = matIdx?.let { paramsByMat[it] }
                    if (matIdx != null && gltf.materials?.getOrNull(matIdx)?.alphaMode?.uppercase() == "BLEND" && params != null) {
                        // Preserve three-vrm's relative renderOrder for transparent
                        // primitives. Filament's blend order is unsigned; center the
                        // VRM range (-9..9) around 128.
                        val blendOrder = (128 + params.renderQueueOffsetNumber).coerceIn(0, 255)
                        renderableManager.setBlendOrderAt(renderable, p, blendOrder)
                        renderableManager.setGlobalBlendOrderEnabledAt(renderable, p, true)
                    }
                    bound++
                }
                replaced++
            }
        }
        android.util.Log.i("MToon", "applied MToon $replaced primitives (byMaterial=$bound), materials=${paramsByMat.size}")
    }

    /** Load one blending variant's .filamat into a Material (cached). */
    private fun loadMaterial(key: String, filamatBytes: ByteArray): Material? {
        return materialCache.getOrPut(key) {
            val buf = ByteBuffer.wrap(filamatBytes)
            Material.Builder().payload(buf, buf.remaining()).build(engine)
        }
    }

    /** 1x1 white fallback texture, bound when a MToon material declares a sampler
     *  but has no texture (or its texture can't be decoded). The shader samples
     *  baseColorMap/shadeColorMap unconditionally; an unbound Filament sampler samples
     *  as black, which would zero out baseColorFactor/shadeColorFactor for
     *  texture-less materials. Binding white keeps the factor-only color path. */
    private var whiteTexture: Texture? = null
    private var blackTexture: Texture? = null
    private var neutralNormalTexture: Texture? = null

    /** MaterialInstance created per MToon glTF material index (for explicit destroy). */
    private val instances = ArrayList<MaterialInstance>()

    private data class AnimatedMaterial(
        val instance: MaterialInstance,
        val params: MtoonMaterialParameters,
        var scrollX: Float = 0f,
        var scrollY: Float = 0f,
        var rotation: Float = 0f,
    )

    private val animatedInstances = ArrayList<AnimatedMaterial>()

    /** Advance VRMC_materials_mtoon UV scroll/rotation using three-vrm's phase convention. */
    fun update(deltaSeconds: Float) {
        if (deltaSeconds <= 0f) return
        for (state in animatedInstances) {
            state.scrollX += state.params.uvAnimationScrollXSpeedFactor * deltaSeconds
            state.scrollY += state.params.uvAnimationScrollYSpeedFactor * deltaSeconds
            state.rotation += state.params.uvAnimationRotationSpeedFactor * deltaSeconds
            state.instance.setParameter("uvAnimationOffset", state.scrollX, state.scrollY)
            state.instance.setParameter("uvAnimationRotation", state.rotation)
        }
    }

    /**
     * Destroy every Filament object this applier created, in the safe order:
     * MaterialInstances first, then Materials, then Textures.
     *
     * WHY (crash fix): Filament's Java bindings release native Material /
     * MaterialInstance via finalizers when the wrapping object is GC'd. If the
     * applier just goes out of scope (it's a local in loadModel), a GC cycle
     * may run Material's finalizer while its MaterialInstances are still
     * alive -> native PreconditionPanic: "destroying material X but N
     * instances still alive" -> SIGABRT. We must own them and destroy them
     * explicitly, AFTER the owning ModelNode has been destroyed (so the
     * renderables no longer reference the instances).
     */
    fun destroy() {
        for (mi in instances) {
            runCatching { engine.destroyMaterialInstance(mi) }
        }
        instances.clear()
        animatedInstances.clear()
        for (mat in materialCache.values) {
            runCatching { engine.destroyMaterial(mat) }
        }
        materialCache.clear()
        for (tex in textureCache.values) {
            runCatching { engine.destroyTexture(tex) }
        }
        textureCache.clear()
        whiteTexture?.let { runCatching { engine.destroyTexture(it) } }
        blackTexture?.let { runCatching { engine.destroyTexture(it) } }
        neutralNormalTexture?.let { runCatching { engine.destroyTexture(it) } }
        whiteTexture = null
        blackTexture = null
        neutralNormalTexture = null
    }

    /** Bind the decoded Texture for a glTF texture index onto a material instance sampler (only if the shader declares the sampler). */
    private fun bindTexture(
        m: Material,
        mi: MaterialInstance,
        param: String,
        textureIndex: Int?,
        colorSpace: TextureColorSpace,
    ) {
        if (!m.hasParameter(param)) {
            android.util.Log.w("MToon", "bindTexture: material has no param '$param'")
            return
        }
        val tex = textureIndex?.let { decodeTexture(it, colorSpace) }
        val effective = tex ?: fallbackTexture(param)
        android.util.Log.i("MToon", "bindTexture: $param idx=$textureIndex -> ${if (tex != null) "real(tex@${System.identityHashCode(tex)})" else "fallback"}")
        mi.setParameter(param, effective,
            TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT))
    }

    private fun fallbackTexture(param: String): Texture = when (param) {
        "normalMap" -> neutralNormalTexture
            ?: createSolidTexture(128, 128, 255, TextureColorSpace.LINEAR).also { neutralNormalTexture = it }
        "shadingShiftMap", "matcapTexture" -> blackTexture
            ?: createSolidTexture(0, 0, 0, TextureColorSpace.LINEAR).also { blackTexture = it }
        else -> whiteTexture
            ?: createSolidTexture(255, 255, 255, TextureColorSpace.SRGB).also { whiteTexture = it }
    }

    private fun createSolidTexture(r: Int, g: Int, b: Int, colorSpace: TextureColorSpace): Texture {
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        buf.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(255.toByte())
        buf.rewind()
        return Texture.Builder()
            .width(1).height(1).levels(1)
            .format(if (colorSpace == TextureColorSpace.SRGB) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .build(engine)
            .also { it.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE, 1)) }
    }

    /** Extract the texture for a glTF texture index from the GLB and decode it into a Filament Texture. */
    private fun decodeTexture(textureIndex: Int, colorSpace: TextureColorSpace): Texture? {
        val key = textureIndex to colorSpace
        textureCache[key]?.let { return it }
        val tex = gltf.textures?.getOrNull(textureIndex) ?: return null
        val imgIdx = tex.source ?: return null
        val img = gltf.images?.getOrNull(imgIdx) ?: return null
        val imgBytes = imageBytes(img) ?: run {
            android.util.Log.w("MToon", "cannot extract image data for texture $textureIndex")
            return null
        }
        val bitmap = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size) ?: run {
            android.util.Log.w("MToon", "BitmapFactory failed to decode image ${img.name}")
            return null
        }
        val t = bitmapToTexture(bitmap, colorSpace)
        bitmap.recycle()
        textureCache[key] = t
        return t
    }

    /** Get the image's raw PNG bytes (inline bufferView). */
    private fun imageBytes(img: dev.vrm.runtime.core.gltf.Image): ByteArray? {
        val bvIdx = img.bufferView ?: return null
        val bv = gltf.bufferViews?.getOrNull(bvIdx) ?: return null
        val buffer = gltf.buffers?.getOrNull(bv.buffer) ?: return null
        if (buffer.uri != null) {
            android.util.Log.w("MToon", "external buffer uri unsupported for image: ${buffer.uri}")
            return null
        }
        val bin = binary ?: return null
        val start = bv.byteOffset
        val end = start + bv.byteLength
        if (start < 0 || end > bin.size) return null
        return bin.copyOfRange(start, end)
    }

    /** Android Bitmap -> Filament Texture (RGBA8). */
    private fun bitmapToTexture(bmp: Bitmap, colorSpace: TextureColorSpace): Texture {
        val w = bmp.width
        val h = bmp.height
        val rgba = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)
        // Android Bitmap rows are top-to-bottom, while glTF UVs uploaded through
        // Filament's raw PixelBufferDescriptor expect the first row at the
        // texture's bottom. Flip only the row order; keep RGBA channel order.
        for (y in h - 1 downTo 0) {
            val row = y * w
            for (x in 0 until w) {
                val px = pix[row + x]
                rgba.put((px shr 16 and 0xFF).toByte())
                rgba.put((px shr 8 and 0xFF).toByte())
                rgba.put((px and 0xFF).toByte())
                rgba.put((px ushr 24 and 0xFF).toByte())
            }
        }
        rgba.rewind()
        val tex = Texture.Builder()
            .width(w).height(h).levels(1)
            // Color maps use sRGB decoding; normal and scalar data maps stay linear.
            .format(if (colorSpace == TextureColorSpace.SRGB) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .build(engine)
        tex.setImage(engine, 0, Texture.PixelBufferDescriptor(rgba, Texture.Format.RGBA, Texture.Type.UBYTE, 1))
        return tex
    }

       /** Bind the parsed MToon parameters onto the material instance's uniforms (only if the shader declares the uniform). */
    private fun bindParams(m: Material, mi: MaterialInstance, p: MtoonMaterialParameters) {
        if (m.hasParameter("baseColorFactor"))
            mi.setParameter("baseColorFactor", p.colorFactor[0], p.colorFactor[1], p.colorFactor[2], p.colorFactor[3])
        if (m.hasParameter("shadeColorFactor"))
            mi.setParameter("shadeColorFactor", p.shadeColorFactor[0], p.shadeColorFactor[1], p.shadeColorFactor[2])
        if (m.hasParameter("shadingShift")) mi.setParameter("shadingShift", p.shadingShiftFactor)
        if (m.hasParameter("shadingToony")) mi.setParameter("shadingToony", p.shadingToonyFactor)
        if (m.hasParameter("shadingShiftTextureScale")) mi.setParameter("shadingShiftTextureScale", p.shadingShiftTextureScale)
        if (m.hasParameter("normalScale")) mi.setParameter("normalScale", p.normalScale)
        if (m.hasParameter("giEqualization")) mi.setParameter("giEqualization", p.giEqualizationFactor)
        if (m.hasParameter("matcapFactor"))
            mi.setParameter("matcapFactor", p.matcapFactor[0], p.matcapFactor[1], p.matcapFactor[2])
        if (m.hasParameter("parametricRimColorFactor"))
            mi.setParameter("parametricRimColorFactor", p.parametricRimColorFactor[0], p.parametricRimColorFactor[1], p.parametricRimColorFactor[2])
        if (m.hasParameter("rimLightingMix")) mi.setParameter("rimLightingMix", p.rimLightingMixFactor)
        if (m.hasParameter("rimFresnelPower")) mi.setParameter("rimFresnelPower", p.parametricRimFresnelPowerFactor)
        if (m.hasParameter("rimLift")) mi.setParameter("rimLift", p.parametricRimLiftFactor)
        if (m.hasParameter("emissiveFactor"))
            mi.setParameter("emissiveFactor", p.emissiveFactor[0], p.emissiveFactor[1], p.emissiveFactor[2])
        if (m.hasParameter("lightDir")) mi.setParameter("lightDir", lightDir[0], lightDir[1], lightDir[2])
        // Mirror text-to-vrma viewer.js exactly:
        // DirectionalLight(0xffffff, PI * 0.9)
        // AmbientLight(0xbfd4ff, PI * 0.35)
        // Filament MaterialInstance parameters must be initialized explicitly;
        // relying on .mat defaults leaves these uniforms at zero on device.
        if (m.hasParameter("lightColor"))
            mi.setParameter("lightColor", 2.8274333f, 2.8274333f, 2.8274333f)
        if (m.hasParameter("ambientColor"))
            mi.setParameter("ambientColor", 0.8235901f, 0.9141419f, 1.0995574f)
    }
}