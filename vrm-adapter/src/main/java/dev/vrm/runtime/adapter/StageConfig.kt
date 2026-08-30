package dev.vrm.runtime.adapter

/**
 * Stage (scene) configuration for the avatar viewport, port of xlunar-ai-avatar's
 * `AvatarStage` defaults (AvatarStage.tsx). Controls background + lighting so
 * the avatar is clearly visible even without environment IBL.
 */
data class StageConfig(
    /** Viewport background color (RGB 0..1). xlunar default #1a1a1a. */
    val backgroundColor: FloatArray = floatArrayOf(0.10f, 0.10f, 0.10f),

    /** Ambient light intensity. xlunar default 0.6. */
    val ambientLightIntensity: Float = 0.6f,

    /** Ambient light color (RGB 0..1). */
    val ambientLightColor: FloatArray = floatArrayOf(1f, 1f, 1f),

    /** Directional light intensity. xlunar default 1.1. */
    val directionalLightIntensity: Float = 1.1f,

    /**
     * Directional light DIRECTION (the vector the light TRAVELS along, as
     * Filament's LightManager expects). Camera is at +Z looking at the origin;
     * to light the avatar's face/torso (the side facing the camera) the light
     * must travel from +Z toward -Z, i.e. direction ≈ (0, 0.2, -1). Using a
     * +Z direction would back-light the model and leave the torso black.
     */
    val directionalLightPosition: FloatArray = floatArrayOf(0f, 0.2f, -1f),
    val directionalLightColor: FloatArray = floatArrayOf(1f, 1f, 1f),

    /** Whether to show a ground grid helper. */
    val showGrid: Boolean = false,

    /**
     * Optional environment IBL asset key (e.g. "environments/neutral/neutral_ibl.ktx").
     * When null, only direct lights are used (xlunar's Environment preset is a
     * react-three-drei feature; we map it to an IBL asset key or null).
     */
    val environmentIblAsset: String? = null,
    val environmentSkyboxAsset: String? = null,
) {
    companion object {
        /** xlunar's default stage (direct lights, dark background). */
        val XLUNAR_DEFAULT = StageConfig()
    }
}
