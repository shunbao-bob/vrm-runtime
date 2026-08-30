package dev.vrm.runtime.core.humanoid

import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3

/**
 * The names of VRM humanoid bones, matching the VRM 1.0 spec and three-vrm's
 * VRMHumanBoneName. All 56 standard bones are enumerated.
 */
object HumanBoneName {
    const val HIPS = "hips"
    const val SPINE = "spine"
    const val CHEST = "chest"
    const val UPPER_CHEST = "upperChest"
    const val NECK = "neck"

    const val HEAD = "head"
    const val LEFT_EYE = "leftEye"
    const val RIGHT_EYE = "rightEye"
    const val JAW = "jaw"

    const val LEFT_UPPER_LEG = "leftUpperLeg"
    const val LEFT_LOWER_LEG = "leftLowerLeg"
    const val LEFT_FOOT = "leftFoot"
    const val LEFT_TOES = "leftToes"

    const val RIGHT_UPPER_LEG = "rightUpperLeg"
    const val RIGHT_LOWER_LEG = "rightLowerLeg"
    const val RIGHT_FOOT = "rightFoot"
    const val RIGHT_TOES = "rightToes"

    const val LEFT_SHOULDER = "leftShoulder"
    const val LEFT_UPPER_ARM = "leftUpperArm"
    const val LEFT_LOWER_ARM = "leftLowerArm"
    const val LEFT_HAND = "leftHand"

    const val RIGHT_SHOULDER = "rightShoulder"
    const val RIGHT_UPPER_ARM = "rightUpperArm"
    const val RIGHT_LOWER_ARM = "rightLowerArm"
    const val RIGHT_HAND = "rightHand"

    const val LEFT_THUMB_METACARPAL = "leftThumbMetacarpal"
    const val LEFT_THUMB_PROXIMAL = "leftThumbProximal"
    const val LEFT_THUMB_DISTAL = "leftThumbDistal"
    const val LEFT_INDEX_PROXIMAL = "leftIndexProximal"
    const val LEFT_INDEX_INTERMEDIATE = "leftIndexIntermediate"
    const val LEFT_INDEX_DISTAL = "leftIndexDistal"
    const val LEFT_MIDDLE_PROXIMAL = "leftMiddleProximal"
    const val LEFT_MIDDLE_INTERMEDIATE = "leftMiddleIntermediate"
    const val LEFT_MIDDLE_DISTAL = "leftMiddleDistal"
    const val LEFT_RING_PROXIMAL = "leftRingProximal"
    const val LEFT_RING_INTERMEDIATE = "leftRingIntermediate"
    const val LEFT_RING_DISTAL = "leftRingDistal"
    const val LEFT_LITTLE_PROXIMAL = "leftLittleProximal"
    const val LEFT_LITTLE_INTERMEDIATE = "leftLittleIntermediate"
    const val LEFT_LITTLE_DISTAL = "leftLittleDistal"

    const val RIGHT_THUMB_METACARPAL = "rightThumbMetacarpal"
    const val RIGHT_THUMB_PROXIMAL = "rightThumbProximal"
    const val RIGHT_THUMB_DISTAL = "rightThumbDistal"
    const val RIGHT_INDEX_PROXIMAL = "rightIndexProximal"
    const val RIGHT_INDEX_INTERMEDIATE = "rightIndexIntermediate"
    const val RIGHT_INDEX_DISTAL = "rightIndexDistal"
    const val RIGHT_MIDDLE_PROXIMAL = "rightMiddleProximal"
    const val RIGHT_MIDDLE_INTERMEDIATE = "rightMiddleIntermediate"
    const val RIGHT_MIDDLE_DISTAL = "rightMiddleDistal"
    const val RIGHT_RING_PROXIMAL = "rightRingProximal"
    const val RIGHT_RING_INTERMEDIATE = "rightRingIntermediate"
    const val RIGHT_RING_DISTAL = "rightRingDistal"
    const val RIGHT_LITTLE_PROXIMAL = "rightLittleProximal"
    const val RIGHT_LITTLE_INTERMEDIATE = "rightLittleIntermediate"
    const val RIGHT_LITTLE_DISTAL = "rightLittleDistal"
}

/**
 * The 17 required bones per VRM 1.0. Missing any of these makes a VRM invalid.
 */
val REQUIRED_HUMAN_BONES: List<String> = listOf(
    HumanBoneName.HIPS,
    HumanBoneName.SPINE,
    HumanBoneName.HEAD,
    HumanBoneName.LEFT_UPPER_LEG,
    HumanBoneName.LEFT_LOWER_LEG,
    HumanBoneName.LEFT_FOOT,
    HumanBoneName.RIGHT_UPPER_LEG,
    HumanBoneName.RIGHT_LOWER_LEG,
    HumanBoneName.RIGHT_FOOT,
    HumanBoneName.LEFT_UPPER_ARM,
    HumanBoneName.LEFT_LOWER_ARM,
    HumanBoneName.LEFT_HAND,
    HumanBoneName.RIGHT_UPPER_ARM,
    HumanBoneName.RIGHT_LOWER_ARM,
    HumanBoneName.RIGHT_HAND,
)

/**
 * The full list of 56 human bone names in dependency order (parent before children),
 * matching three-vrm's VRMHumanBoneList.
 */
val HUMAN_BONE_LIST: List<String> = listOf(
    HumanBoneName.HIPS,
    HumanBoneName.SPINE,
    HumanBoneName.CHEST,
    HumanBoneName.UPPER_CHEST,
    HumanBoneName.NECK,
    HumanBoneName.HEAD,
    HumanBoneName.LEFT_EYE,
    HumanBoneName.RIGHT_EYE,
    HumanBoneName.JAW,
    HumanBoneName.LEFT_UPPER_LEG,
    HumanBoneName.LEFT_LOWER_LEG,
    HumanBoneName.LEFT_FOOT,
    HumanBoneName.LEFT_TOES,
    HumanBoneName.RIGHT_UPPER_LEG,
    HumanBoneName.RIGHT_LOWER_LEG,
    HumanBoneName.RIGHT_FOOT,
    HumanBoneName.RIGHT_TOES,
    HumanBoneName.LEFT_SHOULDER,
    HumanBoneName.LEFT_UPPER_ARM,
    HumanBoneName.LEFT_LOWER_ARM,
    HumanBoneName.LEFT_HAND,
    HumanBoneName.RIGHT_SHOULDER,
    HumanBoneName.RIGHT_UPPER_ARM,
    HumanBoneName.RIGHT_LOWER_ARM,
    HumanBoneName.RIGHT_HAND,
    HumanBoneName.LEFT_THUMB_METACARPAL,
    HumanBoneName.LEFT_THUMB_PROXIMAL,
    HumanBoneName.LEFT_THUMB_DISTAL,
    HumanBoneName.LEFT_INDEX_PROXIMAL,
    HumanBoneName.LEFT_INDEX_INTERMEDIATE,
    HumanBoneName.LEFT_INDEX_DISTAL,
    HumanBoneName.LEFT_MIDDLE_PROXIMAL,
    HumanBoneName.LEFT_MIDDLE_INTERMEDIATE,
    HumanBoneName.LEFT_MIDDLE_DISTAL,
    HumanBoneName.LEFT_RING_PROXIMAL,
    HumanBoneName.LEFT_RING_INTERMEDIATE,
    HumanBoneName.LEFT_RING_DISTAL,
    HumanBoneName.LEFT_LITTLE_PROXIMAL,
    HumanBoneName.LEFT_LITTLE_INTERMEDIATE,
    HumanBoneName.LEFT_LITTLE_DISTAL,
    HumanBoneName.RIGHT_THUMB_METACARPAL,
    HumanBoneName.RIGHT_THUMB_PROXIMAL,
    HumanBoneName.RIGHT_THUMB_DISTAL,
    HumanBoneName.RIGHT_INDEX_PROXIMAL,
    HumanBoneName.RIGHT_INDEX_INTERMEDIATE,
    HumanBoneName.RIGHT_INDEX_DISTAL,
    HumanBoneName.RIGHT_MIDDLE_PROXIMAL,
    HumanBoneName.RIGHT_MIDDLE_INTERMEDIATE,
    HumanBoneName.RIGHT_MIDDLE_DISTAL,
    HumanBoneName.RIGHT_RING_PROXIMAL,
    HumanBoneName.RIGHT_RING_INTERMEDIATE,
    HumanBoneName.RIGHT_RING_DISTAL,
    HumanBoneName.RIGHT_LITTLE_PROXIMAL,
    HumanBoneName.RIGHT_LITTLE_INTERMEDIATE,
    HumanBoneName.RIGHT_LITTLE_DISTAL,
)

/**
 * Map from each human bone to its parent human bone (null for hips).
 * Matches three-vrm's VRMHumanBoneParentMap.
 */
val HUMAN_BONE_PARENT_MAP: Map<String, String?> = mapOf(
    HumanBoneName.HIPS to null,
    HumanBoneName.SPINE to HumanBoneName.HIPS,
    HumanBoneName.CHEST to HumanBoneName.SPINE,
    HumanBoneName.UPPER_CHEST to HumanBoneName.CHEST,
    HumanBoneName.NECK to HumanBoneName.UPPER_CHEST,
    HumanBoneName.HEAD to HumanBoneName.NECK,
    HumanBoneName.LEFT_EYE to HumanBoneName.HEAD,
    HumanBoneName.RIGHT_EYE to HumanBoneName.HEAD,
    HumanBoneName.JAW to HumanBoneName.HEAD,
    HumanBoneName.LEFT_UPPER_LEG to HumanBoneName.HIPS,
    HumanBoneName.LEFT_LOWER_LEG to HumanBoneName.LEFT_UPPER_LEG,
    HumanBoneName.LEFT_FOOT to HumanBoneName.LEFT_LOWER_LEG,
    HumanBoneName.LEFT_TOES to HumanBoneName.LEFT_FOOT,
    HumanBoneName.RIGHT_UPPER_LEG to HumanBoneName.HIPS,
    HumanBoneName.RIGHT_LOWER_LEG to HumanBoneName.RIGHT_UPPER_LEG,
    HumanBoneName.RIGHT_FOOT to HumanBoneName.RIGHT_LOWER_LEG,
    HumanBoneName.RIGHT_TOES to HumanBoneName.RIGHT_FOOT,
    HumanBoneName.LEFT_SHOULDER to HumanBoneName.UPPER_CHEST,
    HumanBoneName.LEFT_UPPER_ARM to HumanBoneName.LEFT_SHOULDER,
    HumanBoneName.LEFT_LOWER_ARM to HumanBoneName.LEFT_UPPER_ARM,
    HumanBoneName.LEFT_HAND to HumanBoneName.LEFT_LOWER_ARM,
    HumanBoneName.RIGHT_SHOULDER to HumanBoneName.UPPER_CHEST,
    HumanBoneName.RIGHT_UPPER_ARM to HumanBoneName.RIGHT_SHOULDER,
    HumanBoneName.RIGHT_LOWER_ARM to HumanBoneName.RIGHT_UPPER_ARM,
    HumanBoneName.RIGHT_HAND to HumanBoneName.RIGHT_LOWER_ARM,
    HumanBoneName.LEFT_THUMB_METACARPAL to HumanBoneName.LEFT_HAND,
    HumanBoneName.LEFT_THUMB_PROXIMAL to HumanBoneName.LEFT_THUMB_METACARPAL,
    HumanBoneName.LEFT_THUMB_DISTAL to HumanBoneName.LEFT_THUMB_PROXIMAL,
    HumanBoneName.LEFT_INDEX_PROXIMAL to HumanBoneName.LEFT_HAND,
    HumanBoneName.LEFT_INDEX_INTERMEDIATE to HumanBoneName.LEFT_INDEX_PROXIMAL,
    HumanBoneName.LEFT_INDEX_DISTAL to HumanBoneName.LEFT_INDEX_INTERMEDIATE,
    HumanBoneName.LEFT_MIDDLE_PROXIMAL to HumanBoneName.LEFT_HAND,
    HumanBoneName.LEFT_MIDDLE_INTERMEDIATE to HumanBoneName.LEFT_MIDDLE_PROXIMAL,
    HumanBoneName.LEFT_MIDDLE_DISTAL to HumanBoneName.LEFT_MIDDLE_INTERMEDIATE,
    HumanBoneName.LEFT_RING_PROXIMAL to HumanBoneName.LEFT_HAND,
    HumanBoneName.LEFT_RING_INTERMEDIATE to HumanBoneName.LEFT_RING_PROXIMAL,
    HumanBoneName.LEFT_RING_DISTAL to HumanBoneName.LEFT_RING_INTERMEDIATE,
    HumanBoneName.LEFT_LITTLE_PROXIMAL to HumanBoneName.LEFT_HAND,
    HumanBoneName.LEFT_LITTLE_INTERMEDIATE to HumanBoneName.LEFT_LITTLE_PROXIMAL,
    HumanBoneName.LEFT_LITTLE_DISTAL to HumanBoneName.LEFT_LITTLE_INTERMEDIATE,
    HumanBoneName.RIGHT_THUMB_METACARPAL to HumanBoneName.RIGHT_HAND,
    HumanBoneName.RIGHT_THUMB_PROXIMAL to HumanBoneName.RIGHT_THUMB_METACARPAL,
    HumanBoneName.RIGHT_THUMB_DISTAL to HumanBoneName.RIGHT_THUMB_PROXIMAL,
    HumanBoneName.RIGHT_INDEX_PROXIMAL to HumanBoneName.RIGHT_HAND,
    HumanBoneName.RIGHT_INDEX_INTERMEDIATE to HumanBoneName.RIGHT_INDEX_PROXIMAL,
    HumanBoneName.RIGHT_INDEX_DISTAL to HumanBoneName.RIGHT_INDEX_INTERMEDIATE,
    HumanBoneName.RIGHT_MIDDLE_PROXIMAL to HumanBoneName.RIGHT_HAND,
    HumanBoneName.RIGHT_MIDDLE_INTERMEDIATE to HumanBoneName.RIGHT_MIDDLE_PROXIMAL,
    HumanBoneName.RIGHT_MIDDLE_DISTAL to HumanBoneName.RIGHT_MIDDLE_INTERMEDIATE,
    HumanBoneName.RIGHT_RING_PROXIMAL to HumanBoneName.RIGHT_HAND,
    HumanBoneName.RIGHT_RING_INTERMEDIATE to HumanBoneName.RIGHT_RING_PROXIMAL,
    HumanBoneName.RIGHT_RING_DISTAL to HumanBoneName.RIGHT_RING_INTERMEDIATE,
    HumanBoneName.RIGHT_LITTLE_PROXIMAL to HumanBoneName.RIGHT_HAND,
    HumanBoneName.RIGHT_LITTLE_INTERMEDIATE to HumanBoneName.RIGHT_LITTLE_PROXIMAL,
    HumanBoneName.RIGHT_LITTLE_DISTAL to HumanBoneName.RIGHT_LITTLE_INTERMEDIATE,
)

/**
 * A single human bone: name + the glTF node index it's bound to.
 */
data class HumanBone(
    val name: String,
    val nodeIndex: Int,
)

/**
 * A pose of the humanoid: bone name -> local transform relative to rest pose.
 */
class PoseTransform {
    var position: Vec3? = null
    var rotation: Quat? = null
}

typealias Pose = Map<String, PoseTransform>

/**
 * Thrown when a VRM is missing required humanoid bones.
 */
class MissingHumanoidBoneException(val missingBones: List<String>) :
    RuntimeException("These humanoid bones are required but not exist: ${missingBones.joinToString(", ")}")
