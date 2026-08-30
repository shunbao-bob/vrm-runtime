package dev.vrm.runtime.core.lookAt

import dev.vrm.runtime.core.humanoid.HumanBoneName
import dev.vrm.runtime.core.humanoid.MutableNodeTransformStore
import dev.vrm.runtime.core.humanoid.RigNode
import dev.vrm.runtime.core.humanoid.VRMHumanoid
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3
import kotlin.math.PI

/**
 * Applies eye-gaze directions by rotating the left/right eye bones of a VRM,
 * port of three-vrm's `VRMLookAtBoneApplier`.
 *
 * Two target transform sets are written each frame:
 *  1. the *normalized* eye [RigNode] quaternion (used for pose/retargeting),
 *  2. the *raw* eye node's local rotation through [store] (what is rendered).
 *
 * The formula converts a YXZ yaw/pitch into the eye's local rest frame using
 * the eye-parent world rest quaternion (three-vrm semantics, kept line-for-line).
 */
class VrmLookAtBoneApplier(
    private val humanoid: VRMHumanoid,
    private val store: MutableNodeTransformStore,

    /** Horizontal inward: the left eye moves right / right eye moves left. */
    val rangeMapHorizontalInner: VrmLookAtRangeMap,

    /** Horizontal outward: the left eye moves left / right eye moves right. */
    val rangeMapHorizontalOuter: VrmLookAtRangeMap,

    /** Downward gaze. */
    val rangeMapVerticalDown: VrmLookAtRangeMap,

    /** Upward gaze. */
    val rangeMapVerticalUp: VrmLookAtRangeMap,

    /** Front direction; +Z for VRM 1.0, kept for parity with three-vrm. */
    val faceFront: Vec3 = Vec3(0f, 0f, 1f),
) : VrmLookAtApplier {

    private val restLeftEyeQuat: Quat?
    private val restRightEyeQuat: Quat?
    private val restLeftEyeParentWorldQuat: Quat?
    private val restRightEyeParentWorldQuat: Quat?

    private val leftNormalized: RigNode?
    private val rightNormalized: RigNode?
    private val leftEyeIndex: Int
    private val rightEyeIndex: Int

    private val _quatA = Quat()
    private val _quatB = Quat()

    init {
        val leftIdx = humanoid.getRawBoneNodeIndex(HumanBoneName.LEFT_EYE)
        val rightIdx = humanoid.getRawBoneNodeIndex(HumanBoneName.RIGHT_EYE)
        leftEyeIndex = leftIdx ?: -1
        rightEyeIndex = rightIdx ?: -1

        leftNormalized = humanoid.getNormalizedBoneNode(HumanBoneName.LEFT_EYE)
        rightNormalized = humanoid.getNormalizedBoneNode(HumanBoneName.RIGHT_EYE)

        val leftState = captureRestEyeState(leftEyeIndex)
        val rightState = captureRestEyeState(rightEyeIndex)
        restLeftEyeQuat = leftState?.first
        restLeftEyeParentWorldQuat = leftState?.second
        restRightEyeQuat = rightState?.first
        restRightEyeParentWorldQuat = rightState?.second
    }

    private fun captureRestEyeState(index: Int): Pair<Quat, Quat>? {
        if (index == -1 || !store.hasNode(index)) return null
        val localQuat = store.getLocalRotation(index).copy()
        val parent = store.parentNodeIndex(index)
        val parentWorldQuat = if (parent != -1) {
            val p = Vec3(); val q = Quat(); val s = Vec3()
            store.getWorldMatrix(parent).decompose(p, q, s)
            q
        } else {
            Quat()
        }
        return localQuat to parentWorldQuat
    }

    override fun applyYawPitch(yaw: Float, pitch: Float) {
        applyEye(
            yaw, pitch,
            rawIndex = leftEyeIndex,
            normalized = leftNormalized,
            restQuat = restLeftEyeQuat,
            restParentWorldQuat = restLeftEyeParentWorldQuat,
            yawInnerMap = rangeMapHorizontalInner,
            yawOuterMap = rangeMapHorizontalOuter,
        )
        applyEye(
            yaw, pitch,
            rawIndex = rightEyeIndex,
            normalized = rightNormalized,
            restQuat = restRightEyeQuat,
            restParentWorldQuat = restRightEyeParentWorldQuat,
            yawInnerMap = rangeMapHorizontalInner,
            yawOuterMap = rangeMapHorizontalOuter,
            mirror = true,
        )
    }

    private fun applyEye(
        yaw: Float,
        pitch: Float,
        rawIndex: Int,
        normalized: RigNode?,
        restQuat: Quat?,
        restParentWorldQuat: Quat?,
        yawInnerMap: VrmLookAtRangeMap,
        yawOuterMap: VrmLookAtRangeMap,
        mirror: Boolean = false,
    ) {
        if (rawIndex == -1 || !store.hasNode(rawIndex)) return

        // pitch component
        val eulerPitch = if (pitch < 0f) {
            -rangeMapVerticalDown.map(-pitch)
        } else {
            rangeMapVerticalUp.map(pitch)
        }

        // yaw component — mirrored meaning for the right eye
        val (innerMap, outerMap) = if (mirror) {
            yawOuterMap to yawInnerMap
        } else {
            yawInnerMap to yawOuterMap
        }
        val eulerYaw = if (yaw < 0f) {
            -innerMap.map(-yaw)
        } else {
            outerMap.map(yaw)
        }

        // world-space face-front quaternion (identity for VRM 1.0 faceFront=+Z)
        val worldFaceFrontQuat = getWorldFaceFrontQuat(_quatB)

        // _quatA = LookAt rotation from yaw/pitch euler (degrees -> radians)
        _quatA.setFromEuler(
            Math.toRadians(eulerPitch.toDouble()).toFloat(),
            Math.toRadians(eulerYaw.toDouble()).toFloat(),
            0f,
            "YXZ",
        )

        // normalized.quaternion = quatB * quatA * quatB^-1
        val normalizedQ = worldFaceFrontQuat.copy()
            .multiply(_quatA.copy())
            .multiply(worldFaceFrontQuat.copy().invert())
        normalized?.quaternion?.copy(normalizedQ)

        // raw = parentWorldQuat^-1 * normalizedQ * parentWorldQuat * restEyeQuat
        val parentWorld = restParentWorldQuat
        val restEye = restQuat
        if (parentWorld != null && restEye != null) {
            val rawQ = parentWorld.copy().invert()
                .multiply(normalizedQ.copy())
                .multiply(parentWorld.copy())
                .multiply(restEye.copy())
            store.setLocalRotation(rawIndex, rawQ)
        } else {
            store.setLocalRotation(rawIndex, normalizedQ.copy())
        }
    }

    /**
     * The quaternion that rotates the world-space +Z unit vector to faceFront.
     * Returns identity for VRM 1.0 (faceFront = +Z).
     */
    private fun getWorldFaceFrontQuat(target: Quat): Quat {
        if (faceFront.distanceToSquared(Vec3(0f, 0f, 1f)) < 0.01f) {
            return target.set(0f, 0f, 0f, 1f)
        }
        val (faceFrontAzimuth, faceFrontAltitude) = calcAzimuthAltitude(faceFront.x, faceFront.y, faceFront.z)
        return target.setFromEuler(
            faceFrontAltitude,
            (0.5f * PI.toFloat()) + faceFrontAzimuth,
            0f,
            "YZX",
        )
    }
}