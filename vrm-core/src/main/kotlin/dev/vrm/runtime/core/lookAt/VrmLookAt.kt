package dev.vrm.runtime.core.lookAt

import dev.vrm.runtime.core.humanoid.HumanBoneName
import dev.vrm.runtime.core.humanoid.VRMHumanoid
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3

/**
 * Controls the eye gaze of a VRM avatar: computes yaw/pitch toward a target
 * and applies them through an [applier]. Port of three-vrm's `VRMLookAt`.
 *
 * Typical per-frame flow: set [target] and call [update], or call [lookAt] to
 * point the gaze at an explicit world position.
 */
class VrmLookAt(
    /** The humanoid whose head defines the gaze origin. */
    val humanoid: VRMHumanoid,

    /** Object that turns yaw/pitch into bone rotations or expression weights. */
    var applier: VrmLookAtApplier,

    /** Position offset from the head bone where the gaze origin sits. */
    val offsetFromHeadBone: Vec3 = Vec3(0f, 0.06f, 0f),

    /** The front direction of the face (+Z for VRM 1.0). */
    val faceFront: Vec3 = Vec3(0f, 0f, 1f),
) {
    var autoUpdate: Boolean = true

    /** The world-space target to look at; only used when [autoUpdate] is on. */
    var target: Vec3? = null

    /** Current yaw (around Y) in degrees. */
    var yaw: Float = 0f
        set(value) {
            field = value
            _needsUpdate = true
        }

    /** Current pitch (around X) in degrees. */
    var pitch: Float = 0f
        set(value) {
            field = value
            _needsUpdate = true
        }

    private val _restHeadWorldQuaternion: Quat

    private var _needsUpdate: Boolean = true

    init {
        _restHeadWorldQuaternion = getLookAtWorldQuaternion(Quat())
    }

    fun copy(source: VrmLookAt): VrmLookAt {
        require(source.humanoid === humanoid) { "VrmLookAt: humanoid must be same in order to copy" }
        applier = source.applier
        autoUpdate = source.autoUpdate
        target = source.target
        return this
    }

    fun reset() {
        yaw = 0f
        pitch = 0f
        _needsUpdate = true
    }

    /** The gaze origin in model/world coordinates. */
    fun getLookAtWorldPosition(target: Vec3): Vec3 {
        val headIndex = humanoid.getRawBoneNodeIndex(HumanBoneName.HEAD)
        requireNotNull(headIndex) { "VrmLookAt: head bone not found" }
        val headMatrix = humanoid.rawStore.getWorldMatrix(headIndex)
        return offsetFromHeadBone.copy().applyMatrix4(headMatrix)
    }

    /**
     * The rotation of the head in world coordinates.
     * Does not consider [faceFront].
     */
    fun getLookAtWorldQuaternion(target: Quat): Quat {
        val headIndex = humanoid.getRawBoneNodeIndex(HumanBoneName.HEAD)
            ?: error("VrmLookAt: head bone not found")
        val p = Vec3()
        val q = Quat()
        val s = Vec3()
        humanoid.rawStore.getWorldMatrix(headIndex).decompose(p, q, s)
        return target.copy(q)
    }

    /**
     * Point the gaze toward a world-space [position].
     * (Overwritten each frame if [autoUpdate] is on and [target] is set.)
     */
    fun lookAt(position: Vec3) {
        // head rotation difference (inverse)
        val headRotDiffInv = _restHeadWorldQuaternion.copy()
            .multiply(getLookAtWorldQuaternion(Quat()).invertedCopy())

        val headPos = getLookAtWorldPosition(Vec3())
        val lookAtDir = position.copy()
            .sub(headPos)
            .let { headRotDiffInv.rotate(it) }
            .normalize()

        val (azimuthFrom, altitudeFrom) = calcAzimuthAltitude(faceFront.x, faceFront.y, faceFront.z)
        val (azimuthTo, altitudeTo) = calcAzimuthAltitude(lookAtDir.x, lookAtDir.y, lookAtDir.z)

        val yawRad = sanitizeAngle(azimuthTo - azimuthFrom)
        // CCW around Z spins (1,0,0) up; CCW around X spins (0,0,1) down
        val pitchRad = sanitizeAngle(altitudeFrom - altitudeTo)

        yaw = Math.toDegrees(yawRad.toDouble()).toFloat()
        pitch = Math.toDegrees(pitchRad.toDouble()).toFloat()

        _needsUpdate = true
    }

    /**
     * Advance the look-at controller. If [autoUpdate] is on and [target] set,
     * recompute yaw/pitch toward it, then apply to the model.
     */
    fun update(delta: Float) {
        val t = target
        if (t != null && autoUpdate) {
            lookAt(t)
        }
        if (_needsUpdate) {
            _needsUpdate = false
            applier.applyYawPitch(yaw, pitch)
        }
    }
}