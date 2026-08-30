package dev.vrm.runtime.adapter


import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.LightManager
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import dev.vrm.runtime.adapter.filament.FilamentExpressionBindProvider
import dev.vrm.runtime.adapter.filament.FilamentNodeTransformStore
import dev.vrm.runtime.adapter.filament.FilamentSpringBoneStore
import dev.vrm.runtime.core.controller.AvatarBinding
import dev.vrm.runtime.core.controller.AvatarCommand
import dev.vrm.runtime.core.controller.AvatarConfig
import dev.vrm.runtime.core.controller.RawBoneRotation
import dev.vrm.runtime.core.expression.ExpressionLoader
import dev.vrm.runtime.core.expression.ExpressionManager
import dev.vrm.runtime.core.humanoid.GltfNodeTransformStore
import dev.vrm.runtime.core.humanoid.PoseTransform
import dev.vrm.runtime.core.humanoid.VRMHumanoid
import dev.vrm.runtime.core.lookAt.VrmLookAt
import dev.vrm.runtime.core.lookAt.VrmLookAtLoader
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3
import dev.vrm.runtime.core.springbone.SpringBoneLoader
import dev.vrm.runtime.core.springbone.SpringBoneManager
import dev.vrm.runtime.core.vrma.VRMAnimationClipBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * The Filament-backed engine controller for one loaded avatar. It implements
 * [AvatarBinding] so the engine-agnostic [dev.vrm.runtime.core.controller.AvatarController]
 * can drive it by command.
 *
 * This is the heart of the `vrm-adapter` library: it owns the Filament
 * TransformManager / RenderableManager bindings, the humanoid / expressions /
 * lookAt / spring-bone controllers, and per-frame [update].
 *
 * @param assetResolver resolves an asset source key (e.g. "animations/wave.vrma")
 *   to raw bytes (from assets / file / network).
 */
class AvatarEngineController(
    private val engine: Engine,
    val asset: FilamentAsset,
    val instance: FilamentInstance,
    gltfBytes: ByteArray,
    private val assetResolver: (String) -> ByteArray? = { null },
    private val avatarConfig: AvatarConfig = AvatarConfig.EMPTY,
) : AvatarBinding {

    private val logTag = "AvatarEngine"
    val vrm = dev.vrm.runtime.core.vrm.VrmLoader.load(gltfBytes)

    init {
        Log.i(logTag, "parsed VRM: meta=${vrm.vrm?.meta?.name} " +
            "hasHumanoid=${vrm.hasHumanoid} springs=${vrm.springBone?.springs?.size} " +
            "mtoon=${vrm.mtoon.size} exprs=${vrm.vrm?.expressions?.preset?.size}")
        // NOTE: do NOT touch humanoid/expressionManager/lookAt in the FIRST init
        // block — Kotlin runs init blocks in source order and those vals are
        // declared LATER, so they are still uninitialized (null) here.
    }

    /** Filament-backed morph / material bindings for expressions. */
    val expressionBindings: FilamentExpressionBindProvider =
        FilamentExpressionBindProvider(engine, instance,
            vrm.gltf.nodes?.mapIndexedNotNull { i, n ->
                n.name?.let { i to it }
            }?.toMap() ?: emptyMap(),
            vrm.gltf.nodes, vrm.gltf.meshes, vrm.gltf.materials)

    /**
     * One MUTABLE world-math store shared by the humanoid/lookAt node store
     * AND the spring-bone store. Every setLocal* from either store mirrors
     * into it, so spring bones read LIVE world matrices that track the
     * animation + earlier spring-joint writes (the contract required by
     * SpringBoneManager.update). The humanoid normalized-rig rest capture is
     * unaffected (it reads its own separate static rest store).
     */
    private val liveStore: GltfNodeTransformStore =
        GltfNodeTransformStore.fromGltfNodes(vrm.gltf.nodes)

    private val nodeStore = FilamentNodeTransformStore(engine, asset, instance, vrm.gltf.nodes, vrm.gltf.skins, liveStore)

    /** Expression manager (faces / morph presets), or null if the VRM has none. */
    val expressionManager: ExpressionManager? = vrm.vrm?.let {
        ExpressionLoader(expressionBindings).load(vrm.gltf, it)
    }

    /** Humanoid rig (bone mapping) with a Filament node store. */
    val humanoid: VRMHumanoid? = vrm.vrm?.let {
        VRMHumanoid.fromVrm(vrm.gltf, it, nodeStore)
    }

    /** Look-at controller. */
    val lookAt: VrmLookAt? = run {
        val h = humanoid ?: return@run null
        val em = expressionManager ?: return@run null
        vrm.vrm?.let { VrmLookAtLoader(h, em, nodeStore).load(it) }
    }

    /** Spring-bone physics, or null if the VRM has none. */
    val springManager: SpringBoneManager? = vrm.springBone?.let { ext ->
        val store = FilamentSpringBoneStore(engine, asset, instance, vrm.gltf.nodes, vrm.gltf.skins, liveStore)
        SpringBoneLoader(vrm.gltf, ext, store).load()
    }

    /** Whether spring-bone physics are enabled this frame. */
    var springBoneEnabled: Boolean = true

    /** Whether the look-at controller is active this frame. */
    var lookAtEnabled: Boolean = true

    /** VRMA animation player, when a .vrma clip is loaded. */
    var vrmaPlayer: VrmAnimationPlayer? = null

    /** Fade transition state for smooth VRMA animation switching. */
    private var vrmaFadeTimer: Float = 0f
    private var vrmaFadeDuration: Float = 0.3f  // 300ms blend
    private var vrmaPrevPlayer: VrmAnimationPlayer? = null
    private var vrmaPrevWeight: Float = 1f
    private var vrmaNextWeight: Float = 1f

    /** Main-thread choreography state for Pose/Combos presets. */
    private var sequenceCommands: List<AvatarCommand> = emptyList()
    private var sequenceIndex = 0
    private var sequenceWaitMillis = 0f
    private var bodyMotionId: String? = null
    private var bodyMotionClock = 0f

    /** The currently set expression name (null = neutral). */
    var activeExpression: String? = null
        private set

    /** The currently playing VRMA source (null = none). */
    var activeVrmaSource: String? = null
        private set

    /** Diagnostic frame counter for periodic state dumps. */
    private var diagFrame = 0

    // ── LIVE CHOREOGRAPHY (08-24): timed pose-sequence player (no GLB bake) ──
    // Each pose segment is (startTimeSeconds, poseBonesMap). updateLiveCombo
    // advances a clock each frame and cross-fades between the previous and
    // current pose via humanoid.setNormalizedPose + update() (live bone writes).
    private data class LiveComboSeg(val startTime: Float, val bones: Map<String, RawBoneRotation>?)
    private var liveComboSegs: List<LiveComboSeg> = emptyList()
    private var liveComboDuration = 0f
    private var liveComboClock = 0f
    private var liveComboActive = false
    private var liveComboPlayed = false

    /** Set the timeline for a live combo. Segment times are absolute seconds. */
    fun playLiveCombo(segments: List<Pair<Float, Map<String, RawBoneRotation>?>>, duration: Float) {
        // A combo owns the skeleton while it plays; stop any VRMA so it can't
        // override the combo poses every frame.
        vrmaPlayer?.playing = false
        activeVrmaSource = null
        liveComboSegs = segments.map { LiveComboSeg(it.first, it.second) }.sortedBy { it.startTime }
        liveComboDuration = duration
        liveComboClock = 0f
        liveComboActive = liveComboSegs.isNotEmpty()
        liveComboPlayed = false
        android.util.Log.d(logTag, "playLiveCombo: segs=${liveComboSegs.size} dur=$duration")
    }

    /** Stop the live combo and reset to a neutral pose. */
    fun stopLiveCombo() {
        liveComboActive = false
        liveComboSegs = emptyList()
    }

    /** Advance the live combo clock and apply the current (cross-faded) pose. */
    private fun updateLiveCombo(deltaSeconds: Float) {
        if (!liveComboActive || liveComboSegs.isEmpty()) return
        liveComboClock += deltaSeconds
        if (liveComboClock >= liveComboDuration) {
            liveComboActive = false
            return
        }
        val h = humanoid ?: return
        // find the current segment (last seg with startTime <= clock) and the
        // previous one for cross-fade
        var current: LiveComboSeg? = null
        var previous: LiveComboSeg? = null
        for (seg in liveComboSegs) {
            if (seg.startTime <= liveComboClock) {
                previous = current
                current = seg
            } else {
                break
            }
        }
        val target = current ?: return
        val blendWindow = 0.45f
        // cross-fade factor: 0..1 over blendWindow after this seg's start
        val localT = (liveComboClock - target.startTime) / blendWindow
        val alpha = localT.coerceIn(0f, 1f)
        val from = previous?.bones ?: emptyMap()
        val to = target.bones ?: emptyMap()
        // union of bones between from and to
        val pose = HashMap<String, PoseTransform>()
        val allBones = (from.keys + to.keys).toSet()
        for (boneName in allBones) {
            if (h.getNormalizedBoneNode(boneName) == null) continue
            val fromRot = from[boneName]
            val toRot = to[boneName]
            val qa = rotationOf(fromRot)
            val qb = rotationOf(toRot)
            val blended = qa.copy().slerp(qb, alpha)
            val p = PoseTransform()
            p.rotation = blended
            pose[boneName] = p
        }
        h.setNormalizedPose(pose)
        h.update()
    }

    private fun rotationOf(rot: RawBoneRotation?): Quat {
        val q = Quat()
        if (rot == null) return q
        rot.quaternion?.let { q.copy(it); return q }
        rot.degrees?.let { d ->
            q.setFromEuler(
                Math.toRadians(d.x.toDouble()).toFloat(),
                Math.toRadians(d.y.toDouble()).toFloat(),
                Math.toRadians(d.z.toDouble()).toFloat(),
                "YXZ",
            )
            return q
        }
        return q
    }

    /** Expose the raw VRM for UI (model name, etc.). */
    val metaName: String? = vrm.vrm?.meta?.name

    /** The Filament engine (for light setup). */
    val filamentEngine: Engine get() = engine

    // ==========================================================================
    // AvatarBinding implementation
    // ==========================================================================

    override fun setPose(id: String?) {
        val h = humanoid ?: return
        if (id == null) {
            h.resetNormalizedPose()
            h.update()
            return
        }
        vrmaPlayer?.playing = false
        activeVrmaSource = null
        val preset = avatarConfig.poseById(id)
        Log.i(logTag, "EVENT setPose id=$id bones=${preset?.bones?.size}")
        // NORMALIZED channel (matches app/DemoAssets — the path that yields the
        // correct arms-down relaxed pose on VRoid Sample B).
        preset?.bones?.takeIf { it.isNotEmpty() }?.let(::setRawPose)
    }

    override fun setHandGesture(id: String?) {
        // Finger-level gesture data is model-specific.  Keep the command
        // functional for the standard hand presets by mapping the useful
        // whole-arm gesture aliases to their corresponding body pose.
        val poseAlias = when (id) {
            "wave", "peace", "pointing", "thumbsUp" -> id
            else -> null
        }
        poseAlias?.let { avatarConfig.poseById(it)?.bones?.takeIf { b -> b.isNotEmpty() }?.let(::setRawPose) }
    }

    override fun setBodyGesture(id: String?) {
        val poseAlias = when (id) {
            "nod", "shake", "wave", "bow", "shrug" -> id
            else -> null
        }
        poseAlias?.let { avatarConfig.poseById(it)?.bones?.takeIf { b -> b.isNotEmpty() }?.let(::setRawPose) }
    }

    override fun setBodyMotion(id: String?) {
        Log.i(logTag, "EVENT setBodyMotion id=$id")
        bodyMotionId = id?.takeUnless { it == "none" }
        bodyMotionClock = 0f
    }

    override fun setExpression(id: String?) {
        if (id == null) {
            activeExpression = null
            resetExpressions()
            return
        }
        activeExpression = id
        resetExpressions()
        expressionManager?.setValue(id, 1f)
    }

    override fun playVrma(source: String?, loop: Boolean) {
        Log.i(logTag, "EVENT playVrma source=$source loop=$loop")
        if (source == null) {
            activeVrmaSource = null
            vrmaPlayer?.playing = false
            return
        }
        bodyMotionId = null
        val bytes = assetResolver(source) ?: throw IllegalArgumentException(
            "playVrma: cannot resolve VRMA asset source: $source -- " +
                "check the source key against the assetResolver you wired into AvatarEngineController"
        )
        val duration = loadVrma(bytes)
        if (duration != null) {
            activeVrmaSource = source
            vrmaPlayer?.loop = loop
            vrmaPlayer?.playing = true
        }
    }

    override fun setSequence(id: String?) {
        sequenceCommands = if (id == null) emptyList() else avatarConfig.sequenceById(id)?.commands.orEmpty()
        sequenceIndex = 0
        sequenceWaitMillis = 0f
        if (sequenceCommands.isNotEmpty()) advanceSequence(0f)
    }

    override fun setRawPose(bones: Map<String, RawBoneRotation>) {
        val h = humanoid ?: return
        val pose = HashMap<String, PoseTransform>()
        for ((name, rot) in bones) {
            val node = h.getNormalizedBoneNode(name) ?: continue
            val q = rot.quaternion ?: rot.degrees?.let { d ->
                val q = Quat()
                q.setFromEuler(
                    Math.toRadians(d.x.toDouble()).toFloat(),
                    Math.toRadians(d.y.toDouble()).toFloat(),
                    Math.toRadians(d.z.toDouble()).toFloat(),
                    "YXZ",
                )
                q
            } ?: continue
            val p = PoseTransform()
            p.rotation = q
            pose[name] = p
        }
        h.setNormalizedPose(pose)
        h.update()
    }

    override fun setRawExpression(values: Map<String, Float>) {
        values.forEach { (name, w) -> expressionManager?.setValue(name, w) }
    }

    override fun reset() {
        Log.i(logTag, "EVENT reset()")
        activeExpression = null
        activeVrmaSource = null
        sequenceCommands = emptyList()
        sequenceIndex = 0
        sequenceWaitMillis = 0f
        bodyMotionId = null
        bodyMotionClock = 0f
        resetExpressions()
        // Uniform normalized T-pose (same natural stance for every model).
        humanoid?.resetNormalizedPose()
        humanoid?.update()
        vrmaPlayer?.playing = false
    }

    // ==========================================================================
    // Direct API (kept for the UI panel / host)
    // ==========================================================================

    /** Set the target weight of an expression by name (clamped 0..1). */
    fun setExpressionWeight(name: String, weight: Float) {
        expressionManager?.setValue(name, weight)
    }

    /** Reset all expression weights to zero. */
    fun resetExpressions() {
        expressionManager?.resetValues()
    }

    /** Set the world-space target the avatar's gaze follows. */
    fun setLookAtTarget(x: Float, y: Float, z: Float) {
        lookAt?.target = Vec3(x, y, z)
    }

    /** Load a `.vrma` clip from raw bytes and retarget it onto the avatar. */
    fun loadVrma(bytes: ByteArray): Float? {
        val h = humanoid ?: return null
        val em = expressionManager
        val vrma = dev.vrm.runtime.core.vrm.VrmLoader.load(bytes)
        val ext = vrma.vrmAnimation ?: return null
        val bin = vrma.binary?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
        val animation = dev.vrm.runtime.core.vrma.VrmAnimationLoader(vrma.gltf, bin, ext).loadAll()
            .firstOrNull() ?: return null
        // diagnostic: why so few tracks?
        val normKeys = h.getNormalizedPose().keys
        Log.d(logTag, "loadVrma: vrmAnim rotation=${animation.humanoidTracks.rotation.size} " +
            "translation=${animation.humanoidTracks.translation.size} " +
            "normBones=${normKeys.size} normHasHips=${normKeys.contains("hips")} " +
            "normHasSpine=${normKeys.contains("spine")}")
        val clip = VRMAnimationClipBuilder(animation, h, em).build()
        val player = VrmAnimationPlayer(h, em, clip, lookAt)
        vrmaPlayer = player
        Log.d(logTag, "loadVrma: clip tracks=${clip.tracks.size} dur=${clip.duration} " +
            "hasLookAt=${clip.tracks.any { it.name == "lookAt.quaternion" }}")
        Log.i(logTag, "EVENT loadVrma tracks=${clip.tracks.size} dur=${"%.2f".format(clip.duration)}")
        return player.duration
    }

    /** Pause / resume the loaded VRMA clip. */
    fun setVrmaPlaying(playing: Boolean) {
        vrmaPlayer?.playing = playing
    }

    // ==========================================================================
        // Precise lip-sync driven by Aliyun TTS subtitle timestamps
    // ==========================================================================

    /**
     * Subtitle frame queue (thread-safe: the JNI callback thread enqueues,
     * the main-thread render loop consumes). Each frame carries [beginTime,
     * endTime] timestamps plus the pinyin.
     */
    private val subtitleFrames = java.util.concurrent.ConcurrentLinkedQueue<SubtitleFrame>()

    /** The frame currently being consumed (used for interpolation). */
    private var currentFrame: SubtitleFrame? = null
    private var nextFrame: SubtitleFrame? = null

    /** Audio playback start time (System.nanoTime), used to compute the current playback progress. */
    @Volatile private var playbackStartNanos: Long = 0L

    /** Lip-sync debug log counter (rate-limited to avoid log spam). */
    private var mouthDebugCount: Int = 0
    private var mouthProbeCount: Int = 0

    /** Start TTS speech: clear the old queue and record the playback start time. */
    fun startMouth(textLength: Int) {
        subtitleFrames.clear()
        currentFrame = null
        nextFrame = null
        playbackStartNanos = 0L
        // Reset the mouth
        val manager = expressionManager
        if (manager != null) {
            listOf("aa", "ih", "ou", "ee", "oh").forEach { manager.setValue(it, 0f) }
        }
    }

    /**
     * Add one frame to the subtitle queue (called from Nui01BProvider's JNI
     * callback thread).
     */
    fun pushSubtitleFrame(frame: SubtitleFrame) {
        subtitleFrames.offer(frame)
        // If playback has not truly started yet (markPlaybackStarted not called),
        // use the first frame's arrival time as the timeline base so updateMouth
        // can drive the mouth from subtitles immediately.
        if (playbackStartNanos == 0L) {
            playbackStartNanos = System.nanoTime()
            android.util.Log.i("MouthDebug", "pushSubtitleFrame: set playbackStart fallback, queue=${subtitleFrames.size}")
        }
    }

    /** Mark the playback start time (called from TtsPlayer's onStarted callback). */
    fun markPlaybackStarted() {
        playbackStartNanos = System.nanoTime()
        android.util.Log.i("MouthDebug", "markPlaybackStarted() called, queue=${subtitleFrames.size}")
    }

    /**
     * Called every frame; drives the mouth from the subtitle queue and the
     * current playback progress. [deltaSeconds] is unused — the logic queries
     * by timestamps instead.
     */
    fun updateMouth(deltaSeconds: Float) {
        val manager = expressionManager ?: return
        if (playbackStartNanos == 0L) return

        // Current playback progress (ms)
        val elapsedMs = (System.nanoTime() - playbackStartNanos) / 1_000_000L

        // Queue empty and no current/next frame, but TTS is still playing
        // (playbackStartNanos != 0): with no subtitle frames, fall back to a
        // time-based pulse (simulating speech) so the mouth moves even without
        // subtitles. Playback end is handled by stopMouth, which clears the
        // state and resets playbackStartNanos=0.
        if (subtitleFrames.isEmpty() && currentFrame == null && nextFrame == null) {
            val t = elapsedMs % 420
            val pulse = if (t < 320) {
                val p = t / 320f
                0.7f * (1f - kotlin.math.abs(p * 2f - 1f))
            } else 0f
            listOf("aa", "ih", "ou", "ee", "oh").forEachIndexed { i, name ->
                manager.setValue(name, if (i == 0) pulse else 0f)
            }
            return
        }

        // Consume the queue: find the frame matching the current time
        while (true) {
            val frame = subtitleFrames.peek() ?: break
            if (frame.endTime <= elapsedMs) {
                // This frame is expired — pop it
                subtitleFrames.poll()
                if (currentFrame == null) {
                    // Skipped several frames — jump straight to the next
                    currentFrame = subtitleFrames.peek()
                    nextFrame = null
                }
                continue
            }
            if (frame.beginTime <= elapsedMs && elapsedMs < frame.endTime) {
                // Current frame
                currentFrame = frame
                nextFrame = subtitleFrames.peek().let { if (it === frame) null else it }
                break
            }
            // Not yet at this frame — break
            break
        }

        val frame = currentFrame ?: return

        // Current frame finished and no new frame in the queue: mouth returns to closed
        if (frame.endTime <= elapsedMs) {
            listOf("aa", "ih", "ou", "ee", "oh").forEach { manager.setValue(it, 0f) }
            currentFrame = null
            nextFrame = null
            return
        }

        // Compute the interpolation factor (0~1)
        val progress = if (frame.endTime > frame.beginTime) {
            ((elapsedMs - frame.beginTime).toFloat() / (frame.endTime - frame.beginTime).toFloat())
                .coerceIn(0f, 1f)
        } else {
            0.5f
        }

        // Compute viseme weights from the pinyin
        var weights = PinyinToVisemeMapper.toWeights(frame.phoneme)
        // Aliyun subtitles often have an empty phoneme (only hanzi + timestamp) —
        // fall back to a per-character pulsing mouth
        if (frame.phoneme.isBlank()) {
            // For each frame, pulse the mouth open→closed as progress goes 0→1,
            // simulating a single character being pronounced
            val pulse = (1.0f - kotlin.math.abs(progress * 2.0f - 1.0f)).coerceIn(0.2f, 1.0f)
            weights = floatArrayOf(pulse, 0.0f, 0.0f, 0.0f, 0.0f)
        }
        val visemeNames = listOf("aa", "ih", "ou", "ee", "oh")
        // Forward pass: set the active visemes
        for (i in 0..4) {
            manager.setValue(visemeNames[i], weights[i] * 0.9f)
        }
        // Clear inactive visemes (prevents ghosting/afterimages)
        for (i in 0..4) {
            if (weights[i] < 0.1f) {
                manager.setValue(visemeNames[i], 0f)
            }
        }
    }

    fun stopMouth() {
        subtitleFrames.clear()
        currentFrame = null
        nextFrame = null
        playbackStartNanos = 0L
        val manager = expressionManager ?: return
        listOf("aa", "ih", "ou", "ee", "oh").forEach { manager.setValue(it, 0f) }
    }

    /**
     * Apply a stage/lighting config to the Filament engine. Call on the main
     * thread. [lightNode] is the scene's directional light node owned by the
     * host (added to the scene's childNodes); its brightness / color / direction
     * are updated in place so the sliders take effect.
     */
    fun applyStage(config: StageConfig, lightNode: io.github.sceneview.node.LightNode?) {
        if (lightNode != null) {
            val lm = engine.lightManager
            val li = lightNode.lightInstance
            // Filament directional light wants a direction + brightness in lux.
            // xlunar's directionalLightIntensity (default 1.1) is a unitless
            // multiplier; scale it to a reasonable lux value.
            lightNode.lightDirection = io.github.sceneview.math.Direction(
                config.directionalLightPosition[0],
                config.directionalLightPosition[1],
                config.directionalLightPosition[2],
            )
            android.util.Log.d("AvatarEngine", "applyStage dir light: li=$li dir=(${config.directionalLightPosition[0]},${config.directionalLightPosition[1]},${config.directionalLightPosition[2]}) intensity=${config.directionalLightIntensity * 1_000_000f} ambient=${config.ambientLightIntensity}")
            lm.setIntensity(li, config.directionalLightIntensity * 1_000_000f)
            lm.setColor(
                li,
                config.directionalLightColor[0],
                config.directionalLightColor[1],
                config.directionalLightColor[2],
            )
        } else {
            android.util.Log.d("AvatarEngine", "applyStage: lightNode null")
        }
    }

    /**
     * Advance the simulation. Call every frame on the main thread.
     */
    /** Advance the simulation. Call every frame on the main thread. */
    fun update(deltaSeconds: Float) {
        if (deltaSeconds <= 0f) return

        updateSequence(deltaSeconds)
        updateBodyMotion(deltaSeconds)
        updateLiveCombo(deltaSeconds)

                // VRMA fade transition
        if (vrmaFadeTimer >= 0f && vrmaPrevPlayer != null) {
            vrmaFadeTimer += deltaSeconds
            val t = (vrmaFadeTimer / vrmaFadeDuration).coerceIn(0f, 1f)
            // Easing curve: smoothstep
            val st = t * t * (3f - 2f * t)
            vrmaNextWeight = st
            vrmaPrevWeight = 1f - st
            vrmaPrevPlayer?.blendWeight = vrmaPrevWeight
            vrmaPrevPlayer?.update(deltaSeconds)
            vrmaPlayer?.blendWeight = vrmaNextWeight
            vrmaPlayer?.update(deltaSeconds)
            if (t >= 1f) {
                vrmaFadeTimer = -1f
                vrmaPrevPlayer = null
                vrmaPrevWeight = 1f
                vrmaNextWeight = 1f
                vrmaPlayer?.blendWeight = 1f
            }
        } else {
            vrmaPlayer?.blendWeight = 1f
            vrmaPlayer?.update(deltaSeconds)
        }

        // ── Low-frequency health signal: log the driving state every 300 frames ──
        if (++diagFrame % 300 == 0) {
            val vr = vrmaPlayer
            Log.d(logTag, "DIAG frame=$diagFrame vrmaPlaying=${vr?.playing} vrmaTime=${"%.2f".format(vr?.time ?: -1f)} " +
                "vrmaSrc=$activeVrmaSource bodyMotion=$bodyMotionId seqSize=${sequenceCommands.size} " +
                "seqIdx=$sequenceIndex expr=$activeExpression lookAtAuto=${lookAt?.autoUpdate}")
        }


        if (lookAtEnabled) {
            lookAt?.update(deltaSeconds)
        }

        expressionManager?.update()
        // After writing bone transforms to the TransformManager, ask gltfio's
        // Animator to recompute the skin bone matrices, so the mesh follows
        // our retargeted bones. (gltfio does not poll TransformManager by itself.)
        runCatching { instance.getAnimator()?.updateBoneMatrices() }
        expressionBindings.commitAll()

        if (springBoneEnabled) {
            springManager?.update(deltaSeconds)
        }
    }

    private fun updateSequence(deltaSeconds: Float) {
        if (sequenceCommands.isEmpty()) return
        var remaining = deltaSeconds * 1000f
        while (sequenceIndex < sequenceCommands.size) {
            if (sequenceWaitMillis > 0f) {
                val consumed = minOf(sequenceWaitMillis, remaining)
                sequenceWaitMillis -= consumed
                remaining -= consumed
                if (sequenceWaitMillis > 0f || remaining <= 0f) return
            }
            val command = sequenceCommands[sequenceIndex++]
            if (command is AvatarCommand.Wait) {
                sequenceWaitMillis = command.durationMillis.coerceAtLeast(0L).toFloat()
            } else {
                applySequenceCommand(command)
            }
            if (remaining <= 0f) return
        }
        sequenceCommands = emptyList()
    }

    private fun advanceSequence(@Suppress("UNUSED_PARAMETER") deltaSeconds: Float) {
        updateSequence(0f)
    }

    private fun applySequenceCommand(command: AvatarCommand) {
        when (command) {
            is AvatarCommand.SetPose -> setPose(command.id)
            is AvatarCommand.SetExpression -> setExpression(command.id)
            is AvatarCommand.RawPose -> setRawPose(command.bones)
            is AvatarCommand.RawExpression -> setRawExpression(command.values)
            is AvatarCommand.SetBodyMotion -> setBodyMotion(command.id)
            is AvatarCommand.PlayVrma -> playVrma(
                command.source ?: command.id?.let { avatarConfig.animationSource(it) },
                command.loop,
            )
            AvatarCommand.ResetExpression -> resetExpressions()
            AvatarCommand.ResetPose -> setPose(null)
            AvatarCommand.Reset -> reset()
            else -> Unit
        }
    }

    /** Small procedural motion layer matching xlunar's idle/breathing presets. */
    private fun updateBodyMotion(deltaSeconds: Float) {
        val id = bodyMotionId ?: return
        val h = humanoid ?: return
        bodyMotionClock += deltaSeconds
        val amplitude = when (id) {
            "breathingSubtle" -> 1.5f
            "swayGentle" -> 3f
            "idleNatural" -> 1.8f
            else -> return
        }
        val phase = bodyMotionClock * if (id == "swayGentle") 1.2f else 2.0f
        val sway = sin(phase) * amplitude
        val bones = when (id) {
            "swayGentle" -> mapOf(
                "spine" to RawBoneRotation(degrees = Vec3(0f, sway, sway * 0.25f)),
                "head" to RawBoneRotation(degrees = Vec3(0f, sway * 0.35f, 0f)),
            )
            else -> mapOf(
                "spine" to RawBoneRotation(degrees = Vec3(sway, 0f, 0f)),
            )
        }
        // Keep the layer tolerant of models without optional chest/head bones.
        val filtered = bones.filterKeys { h.getNormalizedBoneNode(it) != null }
        // normalized channel shares the NLR state with setPose, so body motion
        // (spine sway) and a static pose (arms) coexist without erasing each other.
        if (filtered.isNotEmpty()) setRawPose(filtered)
    }

    /** Release Filament resources owned outside the asset (if any). */
    fun destroy() {
        // ModelNode owns the asset/instance lifecycle; nothing extra to free here.
    }

    /** The vrm-core parsed VRM (for tests / inspection). */
    val parsed: dev.vrm.runtime.core.vrm.Vrm get() = vrm

    init {
        Log.i(logTag, "expressionManager=${expressionManager?.expressions?.size} " +
            "humanoid=${humanoid != null} springManager=${springManager != null} " +
            "lookAt=${lookAt != null}")
        // Deliberately do NOT overwrite the pose on load: let gltfio keep the
        // author's rest/bind pose. A previous root-chain write-back fix (writing
        // the GLB local transforms back to the hips parent chain) made hips worse
        // (0.08 -> -0.24) because gltfio recomputes the joint tree internally and
        // local writes get overwritten/misapplied — it was reverted. The uniform
        // T-pose is applied via reset() on explicit reset/switch (see the
        // model-switching bug record). (Forcing a normalized T-pose at load time
        // makes the twist's spin look stiff.)
    }
}
