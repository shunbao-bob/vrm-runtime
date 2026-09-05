package dev.vrm.runtime.adapter

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.filament.Engine
import dev.vrm.runtime.core.controller.AvatarConfig
import dev.vrm.runtime.core.controller.AvatarController
import dev.vrm.runtime.core.controller.PosePreset
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode

/**
 * The main entry point of the `vrm-adapter` library: owns a SceneView model
 * node + the [AvatarEngineController] + the engine-agnostic [AvatarController]
 * command facade for one avatar.
 *
 * Typical host usage:
 * ```
 * val renderer = AvatarRenderer(engine, modelLoader, context, assetResolver)
 * renderer.stage = StageConfig()                       // xlunar-style lighting
 * renderer.loadModel("avatars/Seed-san.vrm")           // loads + parses
 * renderer.loadModel("avatars/Seed-san.vrm", "animations/greeting.vrma") // baked anim
 * ...
 * // per frame:
 * renderer.update(deltaSeconds)
 * ```
 *
 * All Filament calls must happen on the main thread. Model bytes are resolved
 * through [assetResolver].
 */
class AvatarRenderer(
    private val engine: Engine,
    private val modelLoader: ModelLoader,
    private val context: Context,
    private val assetResolver: (String) -> ByteArray?,
    private val avatarConfig: AvatarConfig = AvatarConfig.EMPTY,
) {

    /** MToon cel-shading material replacement switch (default on: replaces gltfio's
     *  default PBR with the matc-precompiled unlit cel material). */
    var mtoonEnabled: Boolean = true

    /** The active MToon applier (owns Filament materials/instances). Must be
     *  destroyed explicitly before the ModelNode, or a GC finalizer may destroy
     *  a Material while its MaterialInstances are still alive -> SIGABRT. */
    private var mtoonApplier: dev.vrm.runtime.adapter.filament.MToonMaterialApplier? = null
    private val loadLifecycle = AvatarLoadLifecycle()

    /** Set on destroy; update() short-circuits so a lingering SceneView onFrame
     *  cannot write transforms onto already-freed Filament entities. */
    @Volatile
    private var destroyed: Boolean = false

    /** Mark for teardown WITHOUT freeing resources. Call from a parent-scope
     *  DisposableEffect that runs BEFORE the Scene's own disposal (SceneView
     *  frees the model node's Filament entities on its own onDispose; if a
     *  stale onFrame then fires, spring-bone writes would touch freed memory
     *  -> SIGSEGV). After this, [update] is a no-op. */
    fun prepareDestroy() {
        destroyed = true
        loadLifecycle.destroy()
    }

    /** The scene model node for the currently loaded avatar, or null. */
    var modelNode: ModelNode? = null
        private set

    /** The engine controller for the current avatar, or null. */
    var engineController: AvatarEngineController? = null
        private set

    /** The engine-agnostic command facade over [engineController]. */
    var avatar: AvatarController? = null
        private set

    /** Stage / lighting configuration applied on each [loadModel]. Set via
     *  [applyStage] (which also propagates to the engine); read-only here. */
    var stage: StageConfig = StageConfig.XLUNAR_DEFAULT
        private set

    /** Directional light node added to the scene (single persistent light).
     *  Internal: owned by this renderer, created in [createOrUpdateLight]. */
    private var lightNode: io.github.sceneview.node.LightNode? = null

    /** Current model source key (null when no model loaded). */
    var currentModelSource: String? = null
        private set

    /** Current baked animation source (null when loaded without animation). */
    var currentAnimationSource: String? = null
        private set

    /** Resolve a model/animation asset key to raw bytes. */
    val resolver: (String) -> ByteArray? get() = assetResolver

    /**
     * Load (or reload) an avatar from an asset key.
     *
     * @param animationSource when non-null, the given VRMA is baked into the
     *   model GLB and driven via the gltfio Animator (the only path that moves
     *   the skin). Changing it reloads the model with the new embedded clip.
     */
    fun loadModel(source: String, animationSource: String? = null) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "AvatarRenderer.loadModel must run on the main thread; use loadModelAsync otherwise"
        }
        val token = loadLifecycle.beginLoad()
        val bytes = assetResolver(source) ?: throw IllegalArgumentException(
            "AvatarRenderer: cannot resolve asset source: $source"
        )

        // LIVE DRIVING (08-24): load the plain model GLB and drive animation
        // through the real-time VrmAnimationPlayer (live bone writes via
        // TransformManager + updateBoneMatrices), NOT the baked-GLB+Animator
        // channel. Baking is deprecated for animation; it stays only as a
        // fallback for clip playback. Live driving is lighter (no 10MB GLB
        // rewrite + reload per animation) and enables pose/animation/spring/
        // lookAt all through the same bone-write path.
        val instance = modelLoader.createModelInstance(assetFileLocation = source)
        if (!applyLoaded(source, bytes, instance, animationSource, token)) return

        // If an animation was requested, play it live via the VRMA player.
        if (animationSource != null) {
            android.util.Log.d("AvatarEngine", "loadModel live: source=$source anim=$animationSource avatar=${avatar != null}")
            try {
                // Drive directly through the engine controller's live VRMA
                // player (bypasses the AvatarController wrapper to avoid any
                // routing ambiguity on the default load path).
                engineController?.playVrma(animationSource, loop = true)
            } catch (t: Throwable) {
                android.util.Log.e("AvatarEngine", "loadModel live playVrma failed", t)
            }
        }
    }

    /**
     * Async load (gltfio decode off the main thread via SceneView
     * loadModelInstanceAsync -> Dispatchers.IO), then [applyLoaded] on the
     * main thread (SceneView resumes on Main). Fixes startup ANR where the
     * synchronous loadModel blocked the main thread >5s under input.
     */
    fun loadModelAsync(source: String, onLoaded: () -> Unit) {
        val token = loadLifecycle.beginLoad()
        android.util.Log.d("AvatarEngine", "loadModelAsync start source=$source")
        modelLoader.loadModelInstanceAsync(
            fileLocation = source,
            resourceResolver = { it },
            onResult = { instance ->
                if (instance == null) {
                    android.util.Log.e("AvatarEngine", "loadModelAsync: null instance for $source")
                    return@loadModelInstanceAsync
                }

                // CRITICAL: gltfio's onResult may fire on a background thread. All
                // Filament work in applyLoaded (ModelNode, TransformManager, spring
                // bones) MUST run on the main thread to stay serialized with the
                // SceneView onFrame that drives renderer.update() every frame.
                // Doing it on a background thread racies with the render thread and
                // writes spring-bone rotations to half-built/freed entities -> SIGSEGV.
                Handler(Looper.getMainLooper()).post {
                    if (!loadLifecycle.mayApply(token)) {
                        releaseUnattachedInstance(instance)
                        return@post
                    }
                    val bytes = assetResolver(source)
                    if (bytes == null) {
                        android.util.Log.e("AvatarEngine", "loadModelAsync: cannot resolve asset: $source")
                        releaseUnattachedInstance(instance)
                        return@post
                    }
                    if (applyLoaded(source, bytes, instance, null, token)) onLoaded()
                }
            },
        )
    }

    /** Apply the current [stage] config to the engine (main thread). */
    fun applyStage(config: StageConfig) {
        stage = config
        engineController?.applyStage(config, lightNode)
    }

    /**
     * Play an animation on the currently loaded model LIVE (08-24): reloads the
     * plain model and drives the given VRMA through the real-time player.
     */
    fun loadAnimation(animationSource: String?) {
        val model = currentModelSource ?: return
        loadModel(model, animationSource)
    }

    /**
     * Play a choreography sequence (COMBOS) LIVE (08-24): build a timed
     * pose-segment timeline and drive it through the controller's live combo
     * player (humanoid.setNormalizedPose + update -> live bone writes). No GLB
     * bake / no model reload, so no gltfio reload crash and no 10MB rewrite.
     * Returns the expression events (name + trigger time in seconds) the host
     * should fire out-of-band via setExpressionWeight.
     */
    fun loadCombo(
        sequence: dev.vrm.runtime.core.controller.SequencePreset,
        poses: List<PosePreset>,
    ): List<Pair<String, Float>> {
        val controller = engineController ?: return emptyList()
        val poseById = poses.associateBy { it.id }

        // ---- 1. build a timeline of pose-segments (absolute times) ----
        data class Ev(val t: Float, val pose: PosePreset?)
        val events = ArrayList<Ev>()
        var t = 0f
        val expressionEvents = ArrayList<Pair<String, Float>>() // (name, timeSec)
        for (cmd in sequence.commands) {
            when (cmd) {
                is dev.vrm.runtime.core.controller.AvatarCommand.SetPose -> {
                    val p = poseById[cmd.id]
                    if (p != null) events.add(Ev(t, p))
                }
                is dev.vrm.runtime.core.controller.AvatarCommand.SetExpression ->
                    expressionEvents.add(cmd.id to t)
                is dev.vrm.runtime.core.controller.AvatarCommand.ResetExpression ->
                    expressionEvents.add("__reset__" to t)
                is dev.vrm.runtime.core.controller.AvatarCommand.Wait ->
                    t += cmd.durationMillis / 1000f
                is dev.vrm.runtime.core.controller.AvatarCommand.Reset ->
                    events.add(Ev(t, null))
                else -> { /* SetHandGesture etc. ignored for skeletal live combo */ }
            }
        }
        val clipDuration = t

        // ---- 2. build (startTime, bonesMap) segments for the live player ----
        val segments = ArrayList<Pair<Float, Map<String, dev.vrm.runtime.core.controller.RawBoneRotation>?>>()
        for (ev in events) {
            segments.add(ev.t to ev.pose?.bones)
        }
        android.util.Log.d("AvatarEngine", "combo live: segs=${segments.size} duration=$clipDuration events=${events.size}")
        controller.playLiveCombo(segments, clipDuration)
        return expressionEvents
    }

    /**
     * Apply a static pose LIVE (08-24): drive the current engine controller's
     * normalized rig directly (no GLB bake / reload). Reuses the controller's
     * live setRawPose -> humanoid.setNormalizedPose + update() -> store.
     * setLocalRotation (TransformManager) -> updateBoneMatrices path.
     */
    fun loadPose(pose: PosePreset) {
        val controller = engineController ?: return
        android.util.Log.d("AvatarEngine", "pose live: bones=${pose.bones.size}")
        // A static pose owns the skeleton; stop any VRMA/combo so it can't override.
        controller.vrmaPlayer?.playing = false
        controller.stopLiveCombo()
        controller.setRawPose(pose.bones)
    }

    /**
     * Attach a model instance (possibly baked) to a fresh controller + node,
     * and surface it as [modelNode]/[engineController]/[avatar].
     */
    private fun applyLoaded(
        source: String,
        loadBytes: ByteArray,
        instance: io.github.sceneview.model.ModelInstance,
        animationSource: String?,
        token: Long,
    ): Boolean {
        if (!loadLifecycle.mayApply(token)) {
            releaseUnattachedInstance(instance)
            return false
        }
        // Build the NEW node + controller FIRST, then swap references and only
        // destroy the old ones afterwards. This avoids a window where the render
        // thread touches a destroyed ModelNode/controller.
        val node = ModelNode(
            modelInstance = instance,
            scaleToUnits = 1.0f,
        )

        // Disable back-face culling: Seed-san's materials are doubleSided=false
        // and its PNG textures can't be decoded, so with culling on the torso
        // (facing the camera) gets treated as back-facing and renders black.
        node.setCulling(false)

        val controller = try {
            AvatarEngineController(
                engine = engine,
                asset = node.model,
                instance = node.modelInstance,
                gltfBytes = loadBytes,
                assetResolver = assetResolver,
                avatarConfig = avatarConfig,
            )
        } catch (t: Throwable) {
            runCatching { node.destroy() }
            throw t
        }
        // Bind the scene ModelNode so the locomotion layer can drive the
        // whole-model world position / yaw (vrm-character's MoveTo/TurnTo/etc).
        try {
            controller.bindModelNode(node)
        } catch (t: Throwable) {
            AvatarLoadResources({ node.destroy() }, null, { controller.destroy() }).release()
            throw t
        }

        // Apply the MToon material (default on; binds textures by alphaMode;
        // turn off mtoonEnabled when tuning)
        var newMtoonApplier: dev.vrm.runtime.adapter.filament.MToonMaterialApplier? = null
        if (mtoonEnabled) {
            try {
                val opaque = context.assets.open("materials/mtoon_opaque.filamat").use { it.readBytes() }
                val masked = context.assets.open("materials/mtoon_masked.filamat").use { it.readBytes() }
                val transparent = context.assets.open("materials/mtoon_transparent.filamat").use { it.readBytes() }
                val filamat = dev.vrm.runtime.adapter.filament.MToonMaterialApplier.Filamat(opaque, masked, transparent)
                val applier = dev.vrm.runtime.adapter.filament.MToonMaterialApplier(
                    engine, node.modelInstance, controller.vrm.gltf, controller.vrm.binary,
                    // The MToon shader's lightDir points TOWARD the light; the stage
                    // config holds the light PROPAGATION direction (the negation), so
                    // mirror it — this keeps the cartoon shading tracking the host's
                    // configured stage light instead of the mat's hardcoded default.
                    lightDir = floatArrayOf(
                                            -stage.directionalLightPosition[0],
                                            -stage.directionalLightPosition[1],
                                            -stage.directionalLightPosition[2],
                                        ),
                )
                newMtoonApplier = applier
                applier.apply(controller.vrm.mtoon, filamat, node.renderableNodes)
            } catch (t: Throwable) {
                android.util.Log.w("AvatarEngine", "MToon apply failed: ${t.message}", t)
                // If an applier exists, apply() may already have replaced some
                // renderables. Those MaterialInstances must remain alive until the
                // ModelNode is destroyed, so partial application cannot fall back
                // in-place to PBR safely; abandon this new model as one resource set.
                val failedApplier = newMtoonApplier
                if (failedApplier != null) {
                    AvatarLoadResources(
                        node = { node.destroy() },
                        applier = { failedApplier.destroy() },
                        controller = { controller.destroy() },
                    ).release()
                    return false
                }
            }
        }


        // Foot-bottom alignment: accumulate the world y of the foot bone chain from
        // the humanoid, then shift the model down so the feet align to y=0 (ground),
        // eliminating the visual "floating" feel.
        runCatching {
            val footBottomY = calculateFootBottomY(controller)
            if (footBottomY != null && footBottomY > 0f) {
                val groundOffset = -(footBottomY + 0.02f)
                node.position = io.github.sceneview.math.Position(y = groundOffset)
                android.util.Log.d("AvatarEngine", "applyLoaded: footBottomY=$footBottomY groundOffset=$groundOffset")
            }
        }.onFailure { android.util.Log.w("AvatarEngine", "foot alignment failed", it) }

        val oldNode = modelNode
        val oldController = engineController
        val oldMtoonApplier = mtoonApplier
        val newAvatar = try {
            AvatarController(controller, avatarConfig) { task ->
                if (Looper.myLooper() == Looper.getMainLooper()) task()
                else check(Handler(Looper.getMainLooper()).post(task)) { "main looper rejected avatar command" }
            }
        } catch (t: Throwable) {
            AvatarLoadResources({ node.destroy() }, newMtoonApplier?.let { { it.destroy() } }, { controller.destroy() }).release()
            throw t
        }
        // beginLoad()/destroy() may be called from another thread while the main
        // thread is constructing Filament resources. Re-check immediately before
        // publication so a stale generation can never replace the current avatar.
        val published = loadLifecycle.applyIfCurrent(token) {
            modelNode = node
            engineController = controller
            mtoonApplier = newMtoonApplier
            avatar = newAvatar
            currentModelSource = source
            currentAnimationSource = animationSource
        }
        if (!published) {
            AvatarLoadResources(
                node = { node.destroy() },
                applier = newMtoonApplier?.let { { it.destroy() } },
                controller = { controller.destroy() },
            ).release()
            return false
        }

        AvatarLoadResources(
            node = oldNode?.let { { it.destroy() } },
            applier = oldMtoonApplier?.let { { it.destroy() } },
            controller = oldController?.let { { it.destroy() } },
        ).release()

        applyStage(stage)
        return true
    }

    /** Release a decoded instance that lost an async generation race before attachment. */
    private fun releaseUnattachedInstance(instance: io.github.sceneview.model.ModelInstance) {
        runCatching { ModelNode(modelInstance = instance, scaleToUnits = 1.0f).destroy() }
            .onFailure { android.util.Log.w("AvatarEngine", "discard stale model failed", it) }
    }

    /**
     * Create (or reuse) a persistent directional light node and apply the current
     * stage config to it. Call on the main thread. The host must add the returned
     * node to the scene's childNodes so the light actually illuminates the scene.
     */
    fun createOrUpdateLight(): io.github.sceneview.node.LightNode? {
        if (lightNode == null) {
            lightNode = io.github.sceneview.node.LightNode(
                engine = engine,
                type = com.google.android.filament.LightManager.Type.DIRECTIONAL,
            ) {
                direction(
                    stage.directionalLightPosition[0],
                    stage.directionalLightPosition[1],
                    stage.directionalLightPosition[2],
                )
                intensity(stage.directionalLightIntensity * 1_000_000f)
                color(
                    stage.directionalLightColor[0],
                    stage.directionalLightColor[1],
                    stage.directionalLightColor[2],
                )
            }
        }
        applyStage(stage)
        return lightNode
    }

    /**
     * Accumulate the foot bone chain of the humanoid to compute the foot-bottom
     * world y value. Used to automatically shift the model down on load so the
     * feet align with the ground (y=0).
     */
    private fun calculateFootBottomY(controller: AvatarEngineController): Float? {
        val h = controller.humanoid ?: return null
        val gltf = controller.vrm.gltf
        val nodes = gltf.nodes ?: return null

        // Recursively compute a node's world y (accumulating parent translation.y)
        fun calcWorldY(nodeIdx: Int, visited: MutableSet<Int> = mutableSetOf()): Float {
            if (!visited.add(nodeIdx)) return 0f
            val n = nodes[nodeIdx]
            val ty = n.translation?.get(1) ?: 0f
            // Find the parent node
            for (i in nodes.indices) {
                val pn = nodes[i]
                if (pn.children?.contains(nodeIdx) == true) {
                    return ty + calcWorldY(i, visited)
                }
            }
            return ty
        }

        // Take the average world y of leftFoot and rightFoot
        val footNames = listOf("leftFoot", "rightFoot")
        val footYs = footNames.mapNotNull { name ->
            h.getRawBoneNodeIndex(name)?.let { calcWorldY(it) }
        }
        if (footYs.isEmpty()) return null
        return footYs.average().toFloat()
    }

    /**
     * Advance the avatar simulation. Call every frame on the main thread.
     */
    fun update(deltaSeconds: Float) {
        if (destroyed) return
        engineController?.update(deltaSeconds)
        mtoonApplier?.update(deltaSeconds)
    }

    /** Tear down the model node + controllers. */
    fun destroy() {
        // Stop per-frame updates first so a lingering SceneView onFrame cannot
        // write transforms onto entities we are about to free (spring-bone SIGSEGV).
        destroyed = true
        loadLifecycle.destroy()
        // Destroy the MToon materials/instances AFTER the ModelNode (renderables
        // that reference them are gone by then), and BEFORE any GC finalizer can
        // touch them out of order (a Material destroyed while its instances are
        // still alive -> Filament PreconditionPanic -> SIGABRT).
        AvatarLoadResources(
            node = modelNode?.let { { it.destroy() } },
            applier = mtoonApplier?.let { { it.destroy() } },
            controller = engineController?.let { { it.destroy() } },
        ).release()
        modelNode = null
        mtoonApplier = null
        engineController = null
        avatar = null
        currentModelSource = null
        currentAnimationSource = null
    }
}