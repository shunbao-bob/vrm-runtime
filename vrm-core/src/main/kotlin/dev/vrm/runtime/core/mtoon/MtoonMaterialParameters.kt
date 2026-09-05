package dev.vrm.runtime.core.mtoon

/**
 * Resolved MToon material parameters, engine-agnostic.
 *
 * This is the engine-independent port of three-vrm's `MToonMaterialParameters`
 * and the core deliverable of M5: the [MToonLoader] fills this from the
 * `VRMC_materials_mtoon` schema. Textures are represented as glTF texture
 * indices (`*TextureIndex`) so the host render layer (Filament / gltfio) can
 * resolve the actual [`Texture`] objects; the GLSL shader and Filament material
 * generation live in the demo layer, not here.
 *
 * `specVersion` is kept from the schema (validated at load by [MtoonLoader]).
 */
data class MtoonMaterialParameters(
    // ---- base color (from the underlying glTF material, merged) ----
    /** Base/albedo color as RGBA (linear), from the glTF material.colorFactor. */
    var colorFactor: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    /** Base color texture index, or null. */
    var baseColorTextureIndex: Int? = null,

    /** Normal-map texture index, or null. */
    var normalMapIndex: Int? = null,
    /** Normal scale (x = strength, y unused). */
    var normalScale: Float = 1f,
    /** Emissive color multiplier from the underlying glTF material. */
    var emissiveFactor: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** Emissive texture index, or null. */
    var emissiveTextureIndex: Int? = null,
    /** Alpha cutoff used by MASK materials. */
    var alphaCutoff: Float = 0.5f,
    /** Whether both faces should be rendered. */
    var doubleSided: Boolean = false,

    // ---- MToon spec fields (from VRMC_materials_mtoon) ----
    /** Whether to write to depth when the material is transparent. */
    var transparentWithZWrite: Boolean = false,
    /** Render-queue offset (transparency sort). */
    var renderQueueOffsetNumber: Int = 0,

    /** Shade color as RGB (linear). */
    var shadeColorFactor: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** Shade-color texture index, or null. */
    var shadeMultiplyTextureIndex: Int? = null,

    /** Base shading shift (-1..1); a lower value gives a larger lit region. */
    var shadingShiftFactor: Float = 0f,
    /** ShadingShift texture index, or null (sampled then scaled). */
    var shadingShiftTextureIndex: Int? = null,
    /** Multiplier for the shading-shift texture. */
    var shadingShiftTextureScale: Float = 1f,

    /** Toony factor (0..1): soft vs. hard shading boundary. */
    var shadingToonyFactor: Float = 0.95f,

    /** GI equalization (how much ambient/indirect light flattens shading). */
    var giEqualizationFactor: Float = 0.9f,

    /** Matcap color factor (RGB). */
    var matcapFactor: FloatArray = floatArrayOf(1f, 0f, 0f),
    /** Matcap texture index, or null. */
    var matcapTextureIndex: Int? = null,

    /** Parametric rim color factor (RGB). */
    var parametricRimColorFactor: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** Rim multiply texture index, or null. */
    var rimMultiplyTextureIndex: Int? = null,
    /** How much lighting mixes into the rim. */
    var rimLightingMixFactor: Float = 1f,
    /** Fresnel power of the parametric rim. */
    var parametricRimFresnelPowerFactor: Float = 1f,
    /** Rim lift (brightness boost). */
    var parametricRimLiftFactor: Float = 0f,

    /** Outline width mode. */
    var outlineWidthMode: OutlineWidthMode = OutlineWidthMode.NONE,
    /** Outline width (world or screen units depending on mode). */
    var outlineWidthFactor: Float = 0f,
    /** Outline-width multiply texture index, or null. */
    var outlineWidthMultiplyTextureIndex: Int? = null,
    /** Outline color (RGB). */
    var outlineColorFactor: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** How much lighting affects outline color. */
    var outlineLightingMixFactor: Float = 1f,

    /** UV-animation mask texture index, or null. */
    var uvAnimationMaskTextureIndex: Int? = null,
    /** UV scroll X speed (uv/sec). */
    var uvAnimationScrollXSpeedFactor: Float = 0f,
    /** UV scroll Y speed (uv/sec). */
    var uvAnimationScrollYSpeedFactor: Float = 0f,
    /** UV rotation speed (radians/sec). */
    var uvAnimationRotationSpeedFactor: Float = 0f,

    // ---- flags / debug (demo use) ----
    /** Enable the v0-compat "PBR absolutely" shading line. */
    var v0CompatShade: Boolean = false,
    /** Debug visualization mode. */
    var debugMode: MToonDebugMode = MToonDebugMode.NONE,
) {
    /**
     * True when an outline should be generated (a dedicated outline draw pass),
     * matching three-vrm `_shouldGenerateOutline`.
     */
    val shouldGenerateOutline: Boolean
        get() = outlineWidthMode != OutlineWidthMode.NONE && outlineWidthFactor > 0f

    /**
     * The transparency render-order the host should assign (three-variant
     * `_parseRenderOrder`): 0..9 when Z-write on, 19..28 when off, plus the
     * queue offset.
     */
    val renderOrder: Int
        get() = (if (transparentWithZWrite) 0 else 19) + renderQueueOffsetNumber
}

/** A shallow copy of these parameters. */
fun MtoonMaterialParameters.copyShallow(): MtoonMaterialParameters {
    val c = MtoonMaterialParameters()
    c.colorFactor = colorFactor.copyOf()
    c.baseColorTextureIndex = baseColorTextureIndex
    c.normalMapIndex = normalMapIndex
    c.normalScale = normalScale
    c.emissiveFactor = emissiveFactor.copyOf()
    c.emissiveTextureIndex = emissiveTextureIndex
    c.alphaCutoff = alphaCutoff
    c.doubleSided = doubleSided
    c.transparentWithZWrite = transparentWithZWrite
    c.renderQueueOffsetNumber = renderQueueOffsetNumber
    c.shadeColorFactor = shadeColorFactor.copyOf()
    c.shadeMultiplyTextureIndex = shadeMultiplyTextureIndex
    c.shadingShiftFactor = shadingShiftFactor
    c.shadingShiftTextureIndex = shadingShiftTextureIndex
    c.shadingShiftTextureScale = shadingShiftTextureScale
    c.shadingToonyFactor = shadingToonyFactor
    c.giEqualizationFactor = giEqualizationFactor
    c.matcapFactor = matcapFactor.copyOf()
    c.matcapTextureIndex = matcapTextureIndex
    c.parametricRimColorFactor = parametricRimColorFactor.copyOf()
    c.rimMultiplyTextureIndex = rimMultiplyTextureIndex
    c.rimLightingMixFactor = rimLightingMixFactor
    c.parametricRimFresnelPowerFactor = parametricRimFresnelPowerFactor
    c.parametricRimLiftFactor = parametricRimLiftFactor
    c.outlineWidthMode = outlineWidthMode
    c.outlineWidthFactor = outlineWidthFactor
    c.outlineWidthMultiplyTextureIndex = outlineWidthMultiplyTextureIndex
    c.outlineColorFactor = outlineColorFactor.copyOf()
    c.outlineLightingMixFactor = outlineLightingMixFactor
    c.uvAnimationMaskTextureIndex = uvAnimationMaskTextureIndex
    c.uvAnimationScrollXSpeedFactor = uvAnimationScrollXSpeedFactor
    c.uvAnimationScrollYSpeedFactor = uvAnimationScrollYSpeedFactor
    c.uvAnimationRotationSpeedFactor = uvAnimationRotationSpeedFactor
    c.v0CompatShade = v0CompatShade
    c.debugMode = debugMode
    return c
}