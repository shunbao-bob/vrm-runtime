package dev.vrm.runtime.core.vrm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * VRMC_springBone extension: physics for hair, skirts etc.
 */
@Serializable
data class VrmcSpringBone(
    val specVersion: String,
    val colliders: List<VrmcSpringBoneCollider>? = null,
    val colliderGroups: List<VrmcSpringBoneColliderGroup>? = null,
    val springs: List<VrmcSpringBoneSpring>? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmcSpringBoneCollider(
    val node: Int? = null,
    val shape: VrmcSpringBoneColliderShape? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmcSpringBoneColliderShape(
    val sphere: VrmcSpringBoneColliderSphere? = null,
    val capsule: VrmcSpringBoneColliderCapsule? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmcSpringBoneColliderSphere(
    val offset: List<Float>,
    val radius: Float = 0f,
)

@Serializable
data class VrmcSpringBoneColliderCapsule(
    val offset: List<Float>,
    val radius: Float = 0f,
    val tail: List<Float>,
)

@Serializable
data class VrmcSpringBoneColliderGroup(
    val name: String? = null,
    val colliders: List<Int>? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmcSpringBoneSpring(
    val name: String? = null,
    val joints: List<VrmcSpringBoneJoint> = emptyList(),
    val colliderGroups: List<Int>? = null,
    val center: Int? = null,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)

@Serializable
data class VrmcSpringBoneJoint(
    val node: Int,
    val hitRadius: Float = 0f,
    val stiffness: Float = 1f,
    val gravityPower: Float = 0f,
    val gravityDir: List<Float>? = null,
    val dragForce: Float = 0f,
    val extensions: Map<String, JsonElement>? = null,
    val extras: JsonElement? = null,
)