package dev.vrm.runtime.adapter

/**
 * Stage (scene) configuration for the avatar viewport. Port of text-to-vrma's
 * lighting defaults (viewer.js): DirectionalLight(0xffffff, π*0.9) + AmbientLight(0xbfd4ff, π*0.35).
 */
data class StageConfig(
    /** Viewport background color (RGB 0..1). text-to-vrma 0x0d1119. */
    val backgroundColor: FloatArray = floatArrayOf(0.05f, 0.067f, 0.098f),

    /** Ambient light intensity. text-to-vrma: AmbientLight(0xbfd4ff, π*0.35). */
    val ambientLightIntensity: Float = 1.1f,

    /** Ambient light color (RGB 0..1). text-to-vrma 0xbfd4ff. */
    val ambientLightColor: FloatArray = floatArrayOf(0.75f, 0.83f, 1.0f),

    /** Directional light intensity. text-to-vrma: DirectionalLight(0xffffff, π*0.9). */
    val directionalLightIntensity: Float = 2.827f,

    /**
     * Directional light DIRECTION (the vector the light TRAVELS along, as
     * Filament's LightManager expects). Camera is at +Z looking at the origin;
     * to light the avatar's face/torso (the side facing the camera) the light
     * must travel from +Z toward -Z, i.e. direction ≈ (0, 0.2, -1). Using a
     * +Z direction would back-light the model and leave the torso black.
     */
    val directionalLightPosition: FloatArray = floatArrayOf(-0.384f, -0.768f, -0.512f),

    /** Directional light color (RGB 0..1). text-to-vrma 0xffffff. */
    val directionalLightColor: FloatArray = floatArrayOf(1f, 1f, 1f),

    /** Whether to show a ground grid helper. */
    val showGrid: Boolean = false,

    /**
     * Optional environment IBL asset key (e.g. "environments/neutral/neutral_ibl.ktx").
     * When null, only direct lights are used.
     */
    val environmentIblAsset: String? = null,
    val environmentSkyboxAsset: String? = null,
) {
    companion object {
        /** text-to-vrma style stage (dark background, warm lighting). */
        val XLUNAR_DEFAULT = StageConfig()
    }
}