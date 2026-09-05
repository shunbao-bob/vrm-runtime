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
import dev.vrm.runtime.core.motion.MotionSpec
import dev.vrm.runtime.core.motion.MotionSpecClipBuilder
import dev.vrm.runtime.core.motion.MotionSpecValidator
import dev.vrm.runtime.core.springbone.SpringBoneLoader
import dev.vrm.runtime.core.springbone.SpringBoneManager
import dev.vrm.runtime.core.vrma.VRMAnimationClipBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import io.github.sceneview.node.ModelNode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Filament-backed engine controller for one loaded avatar. It implements
 * [AvatarBinding] so the engine-agnostic [dev.vrm.runtime.core.controller.AvatarController]
 * can drive it by command.
 *
 * This is the heart of the `vrm-adapter` library: it owns the Filament
 * TransformManager / RenderableManager bindings, the humanoid / expressions /
 * lookAt / spring-bone controllers, and per-frame [update].
 *
 * ── INTERNAL USE ONLY ─────────────────────────────────────────────
 * This class is the implementation detail behind [AvatarRenderer]: it is kept
 * `public` because [AvatarRenderer] (its sole legitimate owner) must be able to
 * hand out the live `engineController` for per-frame access (spring bones,
 * look-at, mouth, locomotion state). External consumers should treat it as
 * internal and avoid constructing it directly — the supported entry point is
 * [AvatarRenderer] (loadModel / applyStage / avatar). Callers who hold a
 * reference returned by `renderer.engineController` must respect its lifecycle
 * (it is destroyed with the renderer) and call its methods on the main thread.
 * The low-level Filament entities it exposes (`asset` / `instance` /
 * `humanoid` / `lookAt` / `springManager`) are for advanced debugging use
 * only and their contents may change without warning.
 * ────────────────────────────────────────────────────────────────
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

    // ── Whole-body locomotion state (vrm-character layer drives via commands) ──
    /** The SceneView model node this avatar is attached to; locomotion writes
     *  its world position / yaw each frame. Set via [bindModelNode]. */
    private var modelNode: ModelNode? = null

    /** Current world XZ of the model node (locomotion integration state). */
    private var locX = 0f
    /** Current world Z of the model node. */
    private var locZ = 0f
    /** Current facing yaw in degrees around +Y (0 = facing +Z). */
    private var locYawDeg = 0f

    /** Offset (deg) between the locomotion's assumed +Z forward and the
     *  model's ACTUAL visual forward. VRM files often bake a root rotation
     *  (e.g. 180 deg), so the avatar's face does NOT point along +Z when the
     *  node yaw is 0. Derived once from the head bone's world orientation
     *  (LookAt's faceFront=+Z convention). Travel / turn use
     *  locYawDeg + facingOffsetDeg so the whole body walks straight forward.
     *  visualYaw = locYawDeg + facingOffsetDeg = where the face points. */
    private var facingOffsetDeg = 0f

    /** Actual visual forward (degrees around +Y) read back from the model
     *  node's world quaternion after applying the yaw. This is the ground
     *  truth of where the avatar's face points, decoupled from any euler/
     *  RotationsOrder convention. Locomotion travels along THIS so feet /
     *  body / gaze all stay aligned. Updated in [applyNodeTransform]. */
    private var actualVisualYawDeg = 0f

    /** Desired yaw target the feedback loop is driving toward, or null. */
    private var visualTargetDeg: Float? = null
    /** Current locomotion speed (units/s), used by the speed→state machine. */
    private var locSpeed = 0f
    /** Active move target XZ, or null when not moving. */
    private var moveTarget: Pair<Float, Float>? = null
    private var moveSpeed = 1.5f
    private var arrivalRadius = 0.15f
    private var stopDeceleration = 0f
    private var turnTargetDeg: Float? = null
    private var turnSpeedDegPerSec = 180f
    /** Below this measured yaw error (deg) the turn feedback stops — kills
     *  the read-noise wobble around the target. */
    private val TURN_DEAD_ZONE_DEG = 1.5f
    /** Low-passed visual yaw used as the travel direction. */
    private var travelYawSm = 0f
    /** Last non-zero displacement direction, retained for physically continuous braking. */
    private var lastMoveDirection = LocomotionMath.Direction(0f, 1f)
    /** Locomotion clip mapping (consumed by the animation state machine). */
    private var locIdleClip: String? = null
    private var locWalkClip: String? = null
    private var locRunClip: String? = null
    private var activeLocClip: String? = null
    private var maxWalkSpeed = 1.5f
    private var maxRunSpeed = 3.2f

    /** Attach the SceneView model node so locomotion can drive its transform. */
    fun bindModelNode(node: ModelNode?) {
        modelNode = node
        if (node != null) {
            locX = node.position.x
            locYawDeg = 0f
            locZ = node.position.z
            facingOffsetDeg = computeFacingOffsetDeg()
            actualVisualYawDeg = normalizeYaw(LocomotionMath.visualYaw(readVisualForwardDeg(node), facingOffsetDeg))
            Log.i(logTag, "facingOffset=$facingOffsetDeg deg visualForward vs +Z")
        }
    }

    /** True visual forward = face direction in world XZ, as an angle relative
     *  to +Z, derived from the head bone's world orientation (LookAt uses the
     *  same faceFront=+Z convention). When the VRM's root carries a baked
     *  rotation this is non-zero and travel must compensate it. */
    private fun computeFacingOffsetDeg(): Float {
        val h = humanoid ?: return 0f
        val headIdx = h.getRawBoneNodeIndex(dev.vrm.runtime.core.humanoid.HumanBoneName.HEAD) ?: return 0f
        val m = h.rawStore.getWorldMatrix(headIdx)
        val p = dev.vrm.runtime.core.math.Vec3()
        val q = dev.vrm.runtime.core.math.Quat()
        val s = dev.vrm.runtime.core.math.Vec3()
        m.decompose(p, q, s)
        val front = q.rotate(dev.vrm.runtime.core.math.Vec3(0f, 0f, 1f))
        val fx = front.x
        val fz = front.z
        if (fx * fx + fz * fz < 1e-6f) return 0f
        return (kotlin.math.atan2(fx, fz) * 180f / kotlin.math.PI.toFloat())
    }

    /** The yaw the FACE actually points at (visual forward), world degrees. */
    private fun visualYawDeg(): Float = locYawDeg + facingOffsetDeg
    /** Current world XZ (for the character layer's collision queries). */
    fun getLocomotionXZ(): Pair<Float, Float> = locX to locZ
    /** Current facing yaw in degrees (visual forward, face direction) for
     *  the character layer / walkHome waypoints. */
    fun getLocomotionYaw(): Float = actualVisualYawDeg
    /** Current speed (units/s). */
    fun getLocomotionSpeed(): Float = locSpeed
    /** True while a MoveTo target is active. */
    fun isMoving(): Boolean = moveTarget != null

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

    /** Set by [destroy]; update() short-circuits so a stale onFrame cannot write
     *  onto Filament entities the owning ModelNode already freed. */
    @Volatile
    private var destroyed: Boolean = false

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
    /** True while a brand-new clip fades IN from the idle stance (no previous
     *  playing clip to cross-fade from). During this window the idle body-motion
     *  layer stays active so the ramp starts from a live pose, not a snap. */
    private var fadeInFromIdle: Boolean = false

    /** Main-thread choreography state for Pose/Combos presets. */
    private var sequenceCommands: List<AvatarCommand> = emptyList()
    private var sequenceIndex = 0
    private var sequenceWaitMillis = 0f
    private var bodyMotionId: String? = null
    private var bodyMotionClock = 0f

    // ── RANDOM IDLE (09-03): naturalistic idle layer. While bodyMotionId ==
    // "idleNatural" (no VRMA / no explicit motion) this drives a subtle breathing
    // sway + random blinks + occasional head/arm micro-moves, so the avatar never
    // stands frozen and never repeats a fixed 4s loop. When [listeningActive] is
    // set (STT mic open) micro-moves pause and only breathing + lip-sync remain.
    @Volatile
    var listeningActive: Boolean = false

    /** Freeze random idle while TTS is broadcasting (09-05): during lip-sync the
     *  LLM-picked expression must not fight random blinks / hair-fixing-like
     *  micro-moves — only breathing sway + lip-sync remain. */
    @Volatile
    var speakingActive: Boolean = false

    private val idleRand = java.util.Random()

    /** Seconds until the next random blink (re-rolled after each blink). */
    private var idleBlinkTimer = 2.5f + idleRand.nextFloat() * 3.5f

    /** Blink envelope phase: <0 inactive, [0, 1] active (close->hold->open). */
    private var idleBlinkPhase = -1f

    /** Seconds until the next random micro-move (re-rolled after each move). */
    private var idleActionTimer = 6f + idleRand.nextFloat() * 9f

    /** Active micro-move type: -1 none, 0 look-left, 1 look-right, 2 shrug, 3 tilt-head. */
    private var idleActionType = -1

    /** Progress (0..1) of the current micro-move envelope. */
    private var idleActionProgress = 0f

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

    override fun moveTo(x: Float, z: Float, y: Float, speed: Float, arrivalRadius: Float) {
        if (!x.isFinite() || !z.isFinite() || !speed.isFinite() || speed <= 0f ||
            !arrivalRadius.isFinite() || arrivalRadius < 0f) return
        // Recovery: if the avatar drifted out of the scene bounds (spiral
        // bug residue), teleport it back near the origin before moving.
        if (kotlin.math.abs(locX) > 10f || kotlin.math.abs(locZ) > 10f) {
            Log.w(logTag, "moveTo: out-of-bounds pos=($locX,$locZ) -> teleport to (0,0)")
            locX = 0f
            locZ = 0f
            travelYawSm = 0f
        }
        moveTarget = x to z
        moveSpeed = speed
        stopDeceleration = 0f
        this.arrivalRadius = arrivalRadius
        // Face the destination first (progressive turn), so the body turns
        // before walking - movement then follows the body yaw, not a straight
        // line to the point (fixes diagonal sliding when facing is stale).
        val dx = x - locX
        val dz = z - locZ
        if (dx * dx + dz * dz > 0.0001f) {
            // Desired face (visual) direction to the target, minus the baked
            // root offset => the node yaw to aim at.
            turnTargetDeg = (atan2(dx, dz) * 180f / PI).toFloat()
        }
        Log.i(logTag, "EVENT moveTo target=($x,$z) speed=$speed radius=$arrivalRadius")
    }

    override fun turnTo(yawDegrees: Float, turnSpeedDegPerSec: Float) {
        if (!yawDegrees.isFinite() || !turnSpeedDegPerSec.isFinite() || turnSpeedDegPerSec <= 0f) return
        turnTargetDeg = yawDegrees
        this.turnSpeedDegPerSec = turnSpeedDegPerSec
        Log.i(logTag, "EVENT turnTo yaw=$yawDegrees deg")
    }

    override fun setWorldTransform(x: Float, y: Float, z: Float, yawDegrees: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !yawDegrees.isFinite()) return
        locX = x; locZ = z; locYawDeg = LocomotionMath.nodeYawForVisual(yawDegrees, facingOffsetDeg)
        moveTarget = null; turnTargetDeg = null
        applyNodeTransform(y)
        Log.i(logTag, "EVENT setWorldTransform pos=($x,$y,$z) yaw=$yawDegrees")
    }

    override fun stopMove(deceleration: Float) {
        moveTarget = null
        turnTargetDeg = null
        stopDeceleration = if (deceleration.isFinite() && deceleration > 0f && locSpeed > 0f) {
            LocomotionMath.decelerationForDuration(locSpeed, deceleration)
        } else {
            0f
        }
        if (stopDeceleration == 0f) locSpeed = 0f
        Log.i(logTag, "EVENT stopMove decel=$deceleration")
    }

    override fun setLocomotion(
        idle: String?, walk: String?, run: String?,
        maxWalkSpeed: Float, maxRunSpeed: Float,
    ) {
        if (!maxWalkSpeed.isFinite() || !maxRunSpeed.isFinite() ||
            maxWalkSpeed <= 0f || maxRunSpeed < maxWalkSpeed) return
        locIdleClip = idle; locWalkClip = walk; locRunClip = run
        this.maxWalkSpeed = maxWalkSpeed; this.maxRunSpeed = maxRunSpeed
        activeLocClip = null
        Log.i(logTag, "EVENT setLocomotion idle=$idle walk=$walk run=$run")
    }

    override fun playMotionSpec(spec: MotionSpec, loop: Boolean) {
        val h = humanoid ?: return
        val em = expressionManager
        val validated = try {
            MotionSpecValidator.validate(spec)
        } catch (e: IllegalArgumentException) {
            Log.e(logTag, "playMotionSpec validation rejected: ${e.message}")
            return
        }
        val clip = MotionSpecClipBuilder(h, em).build(validated)
        val player = VrmAnimationPlayer(h, em, clip, lookAt)
        installVrmaPlayer(player)
        vrmaPlayer?.loop = loop
        vrmaPlayer?.playing = true
        activeVrmaSource = "spec:${validated.name}"
        bodyMotionId = null
        Log.i(logTag, "EVENT playMotionSpec name=${validated.name} dur=${"%.2f".format(clip.duration)} tracks=${clip.tracks.size} loop=$loop")
    }

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
            // Fade out instead of freezing on the last sampled pose: a hard
            // stop left the walk pose (legs mid-stride, arms out) baked into
            // the normalized rig, and the idle layer + springbones pulling
            // against it made the whole body wobble at every stop.
            vrmaPlayer?.startFadeOut(0.3f)
            return
        }
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
        installVrmaPlayer(player)
        Log.d(logTag, "loadVrma: clip tracks=${clip.tracks.size} dur=${clip.duration} " +
            "hasLookAt=${clip.tracks.any { it.name == "lookAt.quaternion" }}")
        Log.i(logTag, "EVENT loadVrma tracks=${clip.tracks.size} dur=${"%.2f".format(clip.duration)}")
        return player.duration
    }

    private fun installVrmaPlayer(player: VrmAnimationPlayer) {
        val old = vrmaPlayer
        val transition = AnimationPlayerOwnership.install(
            previous = vrmaPrevPlayer,
            current = old,
            currentPlaying = old?.playing == true,
            next = player,
            clearOwnedExpressions = VrmAnimationPlayer::clearOwnedExpressions,
        )
        val previous = transition.previous
        if (previous != null) {
            // Clip→clip: cross-fade between the two players.
            vrmaPrevPlayer = previous
            vrmaFadeTimer = 0f
            vrmaPrevWeight = 1f
            vrmaNextWeight = 0f
            player.blendWeight = 0f
        } else if (old?.playing != true) {
            // Clip→idle→new clip (or very first clip): there is no playing
            // previous clip to cross-fade from, and the avatar is on the idle
            // stance. Fade the new clip IN from blend 0 so we don't flash from
            // idle into frame 0 at full weight. The idle body-motion layer keeps
            // running underneath (bodyMotionId is not cleared yet) and shows
            // through while the clip ramps up.
            vrmaPrevPlayer = null
            vrmaFadeTimer = 0f
            vrmaPrevWeight = 0f
            vrmaNextWeight = 0f
            player.blendWeight = 0f
            fadeInFromIdle = true
        } else {
            vrmaPrevPlayer = null
            vrmaFadeTimer = -1f
            player.blendWeight = 1f
            fadeInFromIdle = false
        }
        vrmaPlayer = transition.current
    }

    /** Pause / resume the loaded VRMA clip. */
    fun setVrmaPlaying(playing: Boolean) {
        vrmaPlayer?.playing = playing
    }

    // ==========================================================================
        // Precise lip-sync driven by Aliyun TTS subtitle timestamps
    // ==========================================================================

    /** Extra lead (ms) to shift mouth-sync ahead of audio playback start, so the
     *  mouth opens as each sound is spoken rather than lagging. Tune on device:
     *  too large → mouth moves before the voice; too small → lags. */
    private val mouthStartLeadMs = 120L

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
        // NOTE (09-05): do NOT start the timeline here. Frames arrive while TTS is
        // still synthesizing (audio not playing yet); starting the base at frame
        // arrival makes the mouth move before the sound. The base is set ONLY by
        // markPlaybackStarted() (real audio start).
    }

    /** Mark the playback start time (called from TtsPlayer's onStarted callback).
     *  Aliyun subtitle begin_time/end_time are relative to the start of the audio
     *  stream, so the timeline base must be the moment audio actually starts. */
    fun markPlaybackStarted() {
        playbackStartNanos = System.nanoTime() + mouthStartLeadMs * 1_000_000L
        android.util.Log.i("MouthDebug", "markPlaybackStarted() called, queue=${subtitleFrames.size}, lead=${mouthStartLeadMs}ms")
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

    /** Advance the simulation. Call every frame on the main thread. */
    fun update(deltaSeconds: Float) {
            if (deltaSeconds <= 0f) return
            // Use-after-free guard: once destroy() is called (owner ModelNode freed the
            // Filament entities), never drive the skeleton/spring-bones again.
            if (destroyed) return

        updateSequence(deltaSeconds)
        updateBodyMotion(deltaSeconds)
        updateLiveCombo(deltaSeconds)
        updateLocomotion(deltaSeconds)

                // VRMA fade transition (clip→clip cross-fade, or idle→clip fade-in)
        if (vrmaFadeTimer >= 0f && (vrmaPrevPlayer != null || fadeInFromIdle)) {
            vrmaFadeTimer += deltaSeconds
            val t = (vrmaFadeTimer / vrmaFadeDuration).coerceIn(0f, 1f)
            // Easing curve: smoothstep
            val st = t * t * (3f - 2f * t)
            vrmaNextWeight = st
            vrmaPrevWeight = if (fadeInFromIdle) 0f else 1f - st
            val previous = vrmaPrevPlayer
            val next = vrmaPlayer
            previous?.blendWeight = vrmaPrevWeight
            previous?.update(deltaSeconds, applyExpressions = false)
            next?.blendWeight = vrmaNextWeight
            next?.update(deltaSeconds, applyExpressions = false)
            val expressionNames = previous?.expressionTrackNames.orEmpty() + next?.expressionTrackNames.orEmpty()
            val previousValues = previous?.sampledExpressionWeights().orEmpty()
            val nextValues = next?.sampledExpressionWeights().orEmpty()
            ExpressionBlendMath.merge(expressionNames, previousValues, nextValues).forEach { (name, weight) ->
                expressionManager?.setValue(name, weight)
            }
            if (t >= 1f) {
                vrmaPrevPlayer?.playing = false
                vrmaPrevPlayer?.clearOwnedExpressions(vrmaPlayer?.expressionTrackNames.orEmpty())
                vrmaFadeTimer = -1f
                vrmaPrevPlayer = null
                vrmaPrevWeight = 1f
                vrmaNextWeight = 1f
                vrmaPlayer?.blendWeight = 1f
                if (fadeInFromIdle) {
                    // Fade-in finished: the clip is at full weight — hand the body
                    // over to the clip and stop the idle layer fighting it.
                    fadeInFromIdle = false
                    bodyMotionId = null
                }
            }
        } else {
            vrmaPlayer?.blendWeight = 1f
            vrmaPlayer?.update(deltaSeconds)
        }

        if (vrmaPlayer?.completed == true || vrmaPlayer?.fadingOut == true) {
            if (vrmaPlayer?.completed == true) {
                activeVrmaSource = null
                // Re-assert the canonical relaxed stance (arms down, slight spine
                // lean) so the idle layer never inherits the last clip's arm pose.
                // Without this, after clip→clip cross-fades the arms drift into a
                // spread/A-pose that the spine-only idle layer cannot correct.
                setPose("relaxed")
            }
            // One-shot clip finished (or is fading out): fall back to the natural
            // idle (breathing + random blinks + micro-moves) instead of freezing
            // on the last frame. Restoring idle DURING the fade makes the
            // come-down feel alive rather than "hard stop → then idle".
            // Looping clips never hit this branch; explicit stop uses playVrma(null).
            if (bodyMotionId == null) setBodyMotion("idleNatural")
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
        runCatching { instance.getAnimator().updateBoneMatrices() }
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

    /** Advance whole-body locomotion: turn toward target yaw, then move toward
     *  the active target at [moveSpeed]; write the result to [modelNode]. */
    private fun updateLocomotion(deltaSeconds: Float) {
        if (deltaSeconds <= 0f) return

        // Turn toward the requested yaw (shortest arc), if any.
        // CLOSED-LOOP on the MEASURED rendered forward (actualVisualYawDeg):
        // the node's world transform carries a small theta-dependent yaw
        // distortion (measured ~13 deg*sin(theta)) after the quaternion
        // write, so driving the raw locYawDeg alone leaves a constant
        // error. Feedback on the read-back value converges to the target
        // regardless of the distortion's shape.
        turnTargetDeg?.let { target ->
            var diff = (target - actualVisualYawDeg) % 360f
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            val maxTurn = turnSpeedDegPerSec * deltaSeconds
            // Dead-zone + damped correction: the measured visual yaw has
            // per-frame read noise, so a full correction every frame made
            // the body wobble around the target. Correct only half the
            // error per frame (converges smoothly) and stop entirely once
            // within the dead-zone.
            if (abs(diff) <= TURN_DEAD_ZONE_DEG) {
                turnTargetDeg = null
                Log.d(logTag, "turn done target=$target visual=$actualVisualYawDeg")
            } else if (abs(diff) <= maxTurn) {
                locYawDeg += diff * 0.5f
            } else {
                locYawDeg += if (diff > 0f) maxTurn else -maxTurn
            }
        }

        val t = moveTarget
        if (t != null) {
            val dx = t.first - locX
            val dz = t.second - locZ
            val dist = sqrt(dx * dx + dz * dz)
            if (dist <= arrivalRadius) {
                locX = t.first; locZ = t.second
                locSpeed = 0f
                moveTarget = null
                turnTargetDeg = null
                Log.d(logTag, "moveTo arrived (${locX},${locZ})")
            } else if (turnTargetDeg != null) {
                // Still turning to face the target: don't translate yet.
                locSpeed = 0f
            } else {
                // Travel directly toward the target; visual yaw is corrected separately.
                val toTargetYaw = Math.toDegrees(
                    kotlin.math.atan2(dx.toDouble(), dz.toDouble())
                ).toFloat()
                travelYawSm += (toTargetYaw - travelYawSm) * 0.2f
                val movement = LocomotionMath.stepToward(
                    x = locX,
                    z = locZ,
                    targetX = t.first,
                    targetZ = t.second,
                    speed = moveSpeed,
                    deltaSeconds = deltaSeconds,
                )
                lastMoveDirection = LocomotionMath.movementDirection(
                    deltaX = movement.x - locX,
                    deltaZ = movement.z - locZ,
                    previous = lastMoveDirection,
                )
                locX = movement.x
                locZ = movement.z
                locSpeed = if (movement.arrived) 0f else moveSpeed
                if (movement.arrived) {
                    moveTarget = null
                    turnTargetDeg = null
                }
                // Keep the body facing the travel direction (shortest arc),
                // damped + dead-zoned: a full correction every frame let the
                // per-frame read noise visibly wobble the body while walking.
                var yawDiff = (toTargetYaw - actualVisualYawDeg) % 360f
                if (yawDiff > 180f) yawDiff -= 360f
                if (yawDiff < -180f) yawDiff += 360f
                if (abs(yawDiff) > TURN_DEAD_ZONE_DEG) {
                    locYawDeg += yawDiff * 0.5f
                }
                // Safety: if the avatar somehow escapes the scene bounds,
                // abort the move instead of wandering off-screen forever.
                if (kotlin.math.abs(locX) > 10f || kotlin.math.abs(locZ) > 10f) {
                    Log.e(logTag, "moveTo ABORT out of bounds pos=($locX,$locZ)")
                    moveTarget = null
                    locSpeed = 0f
                }
            }
        } else if (locSpeed > 0f && stopDeceleration > 0f) {
            val coast = LocomotionMath.decelerationStep(locSpeed, stopDeceleration, deltaSeconds)
            locX += lastMoveDirection.x * coast.distance
            locZ += lastMoveDirection.z * coast.distance
            locSpeed = coast.speed
            if (locSpeed == 0f) stopDeceleration = 0f
        } else {
            locSpeed = 0f
        }

        updateLocomotionClip()
        applyNodeTransform(null)
        if ((++diagFrames) % 30 == 0) {
            Log.i(logTag, "DIAG locYaw=$locYawDeg actualVisual=$actualVisualYawDeg target=$visualTargetDeg pos=($locX,$locZ)")
        }
    }

    private fun updateLocomotionClip() {
        val desired = when {
            locSpeed <= 0.05f -> locIdleClip
            locSpeed <= maxWalkSpeed -> locWalkClip
            else -> locRunClip ?: locWalkClip
        }
        if (desired == null || desired == activeLocClip) return
        runCatching { playVrma(desired, loop = true) }
            .onSuccess { activeLocClip = desired }
            .onFailure { Log.w(logTag, "locomotion clip unavailable: $desired", it) }
    }

    /** Push the current locomotion state into [modelNode]'s world transform.
     *  Y is preserved from the node unless an explicit override is given (the
     *  foot-bottom alignment sets the model's ground Y, so movement only touches
     *  XZ + yaw). */
    private fun applyNodeTransform(yOverride: Float?) {
        val node = modelNode ?: return
        val y = yOverride ?: node.position.y
        node.position = io.github.sceneview.math.Position(x = locX, y = y, z = locZ)
        // Turn the WHOLE body by an explicit axis-angle quaternion around the
        // world +Y axis. SceneView's Rotation(y=...) euler setter maps the y
        // component to pitch (local X axis) in this version, which silently
        // fails to yaw the avatar (verified: worldQuaternion stayed ~identity
        // while locYaw changed -> avatar slid sideways/backward without turning).
        // A direct Y-axis quaternion is unambiguous and always yaws the body.
        val yawRad = (locYawDeg * PI / 180f).toFloat()
        // romainguy Quaternion(Float3, Float) is (xyz=imaginary, w=real), NOT
        // axis-angle — passing Float3(0,1,0), yawRad built a malformed quat
        // (y=1, w=yaw). Build the yaw quaternion from its components directly:
        // pure Y rotation by θ = (0, sin(θ/2), 0, cos(θ/2)).
        val yawQuat = dev.romainguy.kotlin.math.Quaternion(
            0f,
            kotlin.math.sin(yawRad * 0.5f),
            0f,
            kotlin.math.cos(yawRad * 0.5f),
        )
        node.worldQuaternion = yawQuat
        actualVisualYawDeg = normalizeYaw(LocomotionMath.visualYaw(readVisualForwardDeg(node), facingOffsetDeg))
    }

    private fun normalizeYaw(value: Float): Float = ((value + 180f) % 360f + 360f) % 360f - 180f

    /** Ground truth visual forward: rotate (0,0,1) by the node's current
     *  world quaternion and report the XZ angle relative to +Z. */
    private fun readVisualForwardDeg(node: ModelNode): Float {
        return try {
            val q = node.worldQuaternion
            val qx = q.x; val qy = q.y; val qz = q.z; val qw = q.w
            val ix = qw * 0f + qy * 1f - qz * 0f
            val iy = qw * 0f + qz * 0f - qx * 1f
            val iz = qw * 1f + qx * 0f - qy * 0f
            val iw = -qx * 0f - qy * 0f - qz * 1f
            val fx = ix * qw + iw * -qx + iy * -qz - iz * -qy
            val fz = iz * qw + iw * -qz + ix * -qy - iy * -qx
            if (fx * fx + fz * fz < 1e-6f) return locYawDeg
            (kotlin.math.atan2(fx, fz) * 180f / kotlin.math.PI.toFloat())
        } catch (t: Throwable) {
            locYawDeg
        }
    }

    /** DIAGNOSTIC: read the node's ACTUAL world orientation (rendered truth)
     *  and log it against locYawDeg to calibrate the Rotation(y) convention.
     *  Rotates (0,0,1) by the world quaternion and reports the XZ forward angle. */
    private var diagFrames = 0
    private fun diagLogForward(node: ModelNode) {
        if ((++diagFrames) % 30 != 0) return
        val q = node.worldQuaternion
        val qx = q.x; val qy = q.y; val qz = q.z; val qw = q.w
        val e = node.worldRotation
        // rotate (0,0,1) by q: v' = q*v*q^-1
        val ix = qw * 0f + qy * 1f - qz * 0f
        val iy = qw * 0f + qz * 0f - qx * 1f
        val iz = qw * 1f + qx * 0f - qy * 0f
        val iw = -qx * 0f - qy * 0f - qz * 1f
        val fx = ix * qw + iw * -qx + iy * -qz - iz * -qy
        val fy = iy * qw + iw * -qy + iz * -qx - ix * -qz
        val fz = iz * qw + iw * -qz + ix * -qy - iy * -qx
        val yawActualDeg = kotlin.math.atan2(fx, fz) * 180f / kotlin.math.PI.toFloat()
        val pos = node.position
        Log.i(logTag, "DIAG locYaw=$locYawDeg visualYaw=$yawActualDeg wrY=${e.y} round=${e.y * 180f / kotlin.math.PI.toFloat()} pos=(${pos.x},${pos.z}) q=($qx,$qy,$qz,$qw)")
    }

    /** Small procedural motion layer matching xlunar's idle/breathing presets. */
        private fun updateBodyMotion(deltaSeconds: Float) {
            val id = bodyMotionId ?: return
            val h = humanoid ?: return
            bodyMotionClock += deltaSeconds
            val amplitude = when (id) {
                "breathingSubtle" -> 1.5f
                "swayGentle" -> 3f
                // RANDOM IDLE (09-03): idleNatural is no longer static — it drives a
                // breathing sway + random blinks + occasional micro-moves, so the
                // avatar never stands frozen and never repeats a fixed 4s loop.
                "idleNatural" -> 1.8f
                else -> return
            }
            val phase = bodyMotionClock * if (id == "swayGentle") 1.2f else 2.0f
            val sway = sin(phase) * amplitude

            // Micro-move pose (only for idleNatural, paused while listening).
            val micro = if (id == "idleNatural") {
                updateRandomIdle(deltaSeconds)
                idleMicroPose()
            } else {
                emptyMap()
            }

            val bones = when (id) {
                "swayGentle" -> mapOf(
                    "spine" to RawBoneRotation(degrees = Vec3(0f, sway * 0.6f, sway * 0.25f)),
                    "head" to RawBoneRotation(degrees = Vec3(0f, sway * 0.35f, 0f)),
                )
                else -> mapOf(
                    "spine" to RawBoneRotation(degrees = Vec3(sway, 0f, 0f)),
                )
            }
            // Merge the active micro-move skeleton (spine/head/neck) on top of breathing.
            val merged = LinkedHashMap(bones)
            micro.forEach { (bone, rot) -> merged[bone] = rot }

            // Keep the layer tolerant of models without optional chest/head bones.
            val filtered = merged.filterKeys { h.getNormalizedBoneNode(it) != null }
            // normalized channel shares the NLR state with setPose, so body motion
            // (spine sway) and a static pose (arms) coexist without erasing each other.
            if (filtered.isNotEmpty()) setRawPose(filtered)
        }

        /** Advance the random blink + micro-move state machines for idleNatural. */
        private fun updateRandomIdle(deltaSeconds: Float) {
            // Speaking (lip-sync broadcast, 09-05): no NEW blinks, no NEW
            // micro-moves — an in-flight action finishes its ease-out envelope
            // so the head doesn't snap back. Only breathing + lip-sync remain.
            if (speakingActive) {
                if (idleBlinkPhase >= 0f) {
                    idleBlinkPhase += deltaSeconds * (if (idleBlinkPhase < 0.2f) 7f else 5f)
                    expressionManager?.setValue("blink", if (idleBlinkPhase < 0.35f) 1f else 0f)
                    if (idleBlinkPhase >= 1f) {
                        expressionManager?.setValue("blink", 0f)
                        idleBlinkPhase = -1f
                    }
                }
                if (idleActionType >= 0) {
                    idleActionProgress += deltaSeconds / 2.2f
                    if (idleActionProgress >= 1f) idleActionType = -1
                }
                return
            }
            // Blink envelope: quick close (to weight 1), brief hold, quick open.
            if (idleBlinkPhase >= 0f) {
                idleBlinkPhase += deltaSeconds * (if (idleBlinkPhase < 0.2f) 7f else 5f)
                expressionManager?.setValue("blink", if (idleBlinkPhase < 0.35f) 1f else 0f)
                if (idleBlinkPhase >= 1f) {
                    expressionManager?.setValue("blink", 0f)
                    idleBlinkPhase = -1f
                    // Frequent + random blinks (1.2~4s), with ~20% chance of a
                    // natural double-blink (next one fires 0.22s later).
                    idleBlinkTimer = if (idleRand.nextFloat() < 0.2f) 0.22f
                        else 1.2f + idleRand.nextFloat() * 2.8f
                }
            } else {
                idleBlinkTimer -= deltaSeconds
                if (idleBlinkTimer <= 0f) idleBlinkPhase = 0f
            }

            // Micro-move envelope (paused while listening: no big head/arm gestures).
            if (listeningActive) {
                idleActionType = -1
                return
            }
            if (idleActionType >= 0) {
                idleActionProgress += deltaSeconds / 2.2f
                if (idleActionProgress >= 1f) idleActionType = -1
            } else {
                idleActionTimer -= deltaSeconds
                if (idleActionTimer <= 0f) {
                    idleActionType = idleRand.nextInt(5) // 0..4
                    idleActionProgress = 0f
                    idleActionTimer = 6f + idleRand.nextFloat() * 9f
                }
            }
        }

        /** Build the current micro-move pose for [idleActionType] with an ease envelope. */
        private fun idleMicroPose(): Map<String, RawBoneRotation> {
            val type = idleActionType
            if (type < 0) return emptyMap()
            val a = idleActionProgress.coerceIn(0f, 1f)
            val env = when {
                a < 0.2f -> a / 0.2f
                a > 0.8f -> (1f - a) / 0.2f
                else -> 1f
            }
            return when (type) {
                0, 1 -> {
                    val dir = if (type == 0) -1f else 1f
                    mapOf(
                        "head" to RawBoneRotation(degrees = Vec3(0f, 14f * dir * env, 0f)),
                        "spine" to RawBoneRotation(degrees = Vec3(0f, 8f * dir * env, 0f)),
                    )
                }
                2 -> mapOf("neck" to RawBoneRotation(degrees = Vec3(4f * env, 0f, 0f)))
                3 -> mapOf("head" to RawBoneRotation(degrees = Vec3(6f * env, 0f, 8f * env)))
                // rebound / weight-shift: a gentle hips+spine forward-then-back sway
                else -> mapOf(
                    "hips" to RawBoneRotation(degrees = Vec3(-6f * env, 0f, 0f)),
                    "spine" to RawBoneRotation(degrees = Vec3(4f * env, 0f, 0f)),
                )
            }
        }

    /** Release Filament resources owned outside the asset (if any). */
    fun destroy() {
        // Guard against use-after-free: the owning ModelNode frees the Filament
        // entities (incl. spring-bone TransformManager entities) on destroy; a
        // lingering SceneView onFrame must not keep driving a controller whose
        // entities are already released (SIGSEGV in TransformManager.setTransform).
        destroyed = true
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
