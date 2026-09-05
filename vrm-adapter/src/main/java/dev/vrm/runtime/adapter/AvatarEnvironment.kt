package dev.vrm.runtime.adapter

import android.content.Context
import com.google.android.filament.Engine
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.EnvironmentLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Environment (background) factory for the VRM runtime.
 *
 * In sceneview, the [Environment] (IndirectLight + Skybox) is a *scene-level*
 * resource, not owned by any single [AvatarRenderer]. The host owns the
 * `SceneView` and passes an [Environment] to it; this class produces that
 * environment from a [StageConfig] or from explicit IBL/skybox asset keys.
 *
 * This is a non-Compose, imperative API — consumers build their own
 * `SceneView(environment = ...)` (see the demo apps).
 *
 * ```kotlin
 * val envFactory = AvatarEnvironment(engine, context)
 * val env = envFactory.create(stage)          // uses StageConfig assets, or flat color
 * val env2 = envFactory.create("env/studio_ibl.ktx", "env/studio_skybox.ktx")
 * ...
 * envFactory.destroy(env)                     // release IndirectLight/Skybox
 * ```
 *
 * All calls must happen on the main thread (Filament requirement).
 */
class AvatarEnvironment(
    private val engine: Engine,
    context: Context,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val loader = EnvironmentLoader(engine, context, scope)

    /**
     * Build an [Environment] from a [StageConfig].
     *
     * - When [StageConfig.environmentIblAsset] / [environmentSkyboxAsset] are set,
     *   they are loaded as KTX and used for both IndirectLight (background
     *   PBR lighting) and Skybox (visible backdrop).
     * - When null, a fallback Skybox of the stage's [StageConfig.backgroundColor]
     *   is created and no IndirectLight is used (only direct lights). This is the
     *   "flat color backdrop" case that isolates an MToon avatar from the scene:
     *   the avatar keeps its baked colors and never samples the background.
     */
    fun create(stage: StageConfig): Environment {
        val ibl = stage.environmentIblAsset
        val skybox = stage.environmentSkyboxAsset
        return if (ibl != null && skybox != null) {
            create(ibl, skybox)
        } else {
            color(stage.backgroundColor)
        }
    }

    /**
     * Build an [Environment] from explicit KTX asset keys (the IBL + Skybox
     * live under the app's `assets/` or `res/raw`).
     *
     * @param iblAssetFile asset key for the prefiltered IBL KTX
     *   (e.g. "environments/studio/studio_ibl.ktx").
     * @param skyboxAssetFile asset key for the skybox KTX
     *   (e.g. "environments/studio/studio_skybox.ktx").
     */
    fun create(
        iblAssetFile: String,
        skyboxAssetFile: String,
    ): Environment = loader.createKTX1Environment(
        iblAssetFile = iblAssetFile,
        skyboxAssetFile = skyboxAssetFile,
    )!!

    /**
     * A bare solid-color backdrop with no indirect light.
     *
     * @param color RGB 0..1 background color (default stage dark blue).
     */
    fun color(
        color: FloatArray = StageConfig.XLUNAR_DEFAULT.backgroundColor,
    ): Environment {
        val skybox = com.google.android.filament.Skybox.Builder()
            .color(color[0], color[1], color[2], 1f)
            .build(engine)
        return Environment(
            indirectLight = null,
            skybox = skybox,
        )
    }

    /** Release the GPU resources owned by an environment returned from [create]. */
    fun destroy(environment: Environment) {
        loader.destroyEnvironment(environment)
    }
}