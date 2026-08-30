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
) {
    private val renderableManager: RenderableManager = engine.renderableManager
    private val asset: FilamentAsset = instance.getAsset()

    /** Cache of blending variant name -> Material. */
    private val materialCache = HashMap<String, Material>()
    /** Cache of glTF texture index -> Filament Texture. */
    private val textureCache = HashMap<Int, Texture>()

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
            val m = materials[matName] ?: materials["opaque"]!!
            val mi = m.createInstance()
            val params = paramsByMat[matIdx] ?: MtoonMaterialParameters()
            bindParams(m, mi, params)
            bindTexture(m, mi, "baseColorMap", params.baseColorTextureIndex)
            bindTexture(m, mi, "shadeMap", params.shadeMultiplyTextureIndex)
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

    /** Bind the decoded Texture for a glTF texture index onto a material instance sampler (only if the shader declares the sampler). */
    private fun bindTexture(m: Material, mi: MaterialInstance, param: String, textureIndex: Int?) {
        if (textureIndex == null) return
        if (!m.hasParameter(param)) return
        val tex = decodeTexture(textureIndex) ?: return
        mi.setParameter(param, tex,
            TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT))
    }

    /** Extract the texture for a glTF texture index from the GLB and decode it into a Filament Texture. */
    private fun decodeTexture(textureIndex: Int): Texture? {
        textureCache[textureIndex]?.let { return it }
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
        val t = bitmapToTexture(bitmap)
        bitmap.recycle()
        textureCache[textureIndex] = t
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
    private fun bitmapToTexture(bmp: Bitmap): Texture {
        val w = bmp.width
        val h = bmp.height
        val rgba = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)
        for (px in pix) {
            rgba.put((px shr 16 and 0xFF).toByte())
            rgba.put((px shr 8 and 0xFF).toByte())
            rgba.put((px and 0xFF).toByte())
            rgba.put((px ushr 24 and 0xFF).toByte())
        }
        rgba.rewind()
        val tex = Texture.Builder()
            .width(w).height(h).levels(1)
            // sRGB texture: BitmapFactory decodes sRGB-encoded bytes, so use
            // SRGB8_A8 to let Filament decode to linear on sampling, avoiding
            // the washed-out double gamma of treating sRGB data as linear
            .format(Texture.InternalFormat.SRGB8_A8)
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
    }
}