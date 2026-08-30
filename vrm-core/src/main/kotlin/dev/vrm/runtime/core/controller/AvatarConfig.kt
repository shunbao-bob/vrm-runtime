package dev.vrm.runtime.core.controller

/**
 * Engine-agnostic preset catalogue for an avatar runtime: which models,
 * animations, expressions, poses, gestures and sequences are available.
 * Port of xlunar-ai-avatar's `lib/avatar/config` files (animations.ts,
 * expressions.ts, poses.ts, sequences.ts) — pure data, no engine imports.
 *
 * A host (demo app) builds an [AvatarConfig] from its bundled assets and passes
 * it to [AvatarController] so commands can be issued by stable id.
 */
data class AvatarConfig(
    /** Selectable VRM 1.0 models. */
    val models: List<ModelPreset> = emptyList(),

    /** VRMA animation clips, keyed by stable id. */
    val animations: List<AnimationPreset> = emptyList(),

    /** Expression presets (happy / sad / angry / ...). */
    val expressions: List<ExpressionPreset> = emptyList(),

    /** Named T-poses / static poses. */
    val poses: List<PosePreset> = emptyList(),

    /** Hand gestures (A-pose style). */
    val handGestures: List<NamedPreset> = emptyList(),

    /** Body gestures (nod / wave / ...). */
    val bodyGestures: List<NamedPreset> = emptyList(),

    /** Body-motion loops (idle sway / ...). */
    val bodyMotions: List<NamedPreset> = emptyList(),

    /** Choreography sequences (ordered command lists). */
    val sequences: List<SequencePreset> = emptyList(),
) {
    companion object {
        /** An empty config usable when commands always carry raw sources. */
        val EMPTY = AvatarConfig()
    }

    // ---- lookups ----
    fun animationById(id: String): AnimationPreset? = animations.firstOrNull { it.id == id }
    fun animationSource(id: String): String? = animationById(id)?.source
    fun expressionById(id: String): ExpressionPreset? = expressions.firstOrNull { it.id == id }
    fun poseById(id: String): PosePreset? = poses.firstOrNull { it.id == id }
    fun sequenceById(id: String): SequencePreset? = sequences.firstOrNull { it.id == id }
    fun handGestureById(id: String): NamedPreset? = handGestures.firstOrNull { it.id == id }
    fun bodyGestureById(id: String): NamedPreset? = bodyGestures.firstOrNull { it.id == id }
    fun bodyMotionById(id: String): NamedPreset? = bodyMotions.firstOrNull { it.id == id }

    // ---- validators (throw on unknown id) ----
    fun requirePose(id: String) = require(poseById(id) != null) { "Unknown pose: $id" }
    fun requireHandGesture(id: String) = require(handGestureById(id) != null) { "Unknown hand gesture: $id" }
    fun requireBodyGesture(id: String) = require(bodyGestureById(id) != null) { "Unknown body gesture: $id" }
    fun requireBodyMotion(id: String) = require(bodyMotionById(id) != null) { "Unknown body motion: $id" }
    fun requireExpression(id: String) = require(expressionById(id) != null) { "Unknown expression: $id" }
    fun requireSequence(id: String) = require(sequenceById(id) != null) { "Unknown sequence: $id" }
}

/** A selectable VRM 1.0 model. */
data class ModelPreset(
    val id: String,
    val name: String,
    /** Asset key resolved by the host (file path, resource id, uri...). */
    val source: String,
    val description: String = "",
)

/** A VRMA animation clip. */
data class AnimationPreset(
    val id: String,
    val name: String,
    /** Asset key resolved by the host (file path / uri). */
    val source: String,
    val category: String = "action",
    val loop: Boolean = true,
    val description: String = "",
)

/** An expression preset. */
data class ExpressionPreset(
    val id: String,
    val name: String,
    val description: String = "",
)

/** A named static pose. */
data class PosePreset(
    val id: String,
    val name: String,
    val description: String = "",
    /** Optional raw bone rotations in degrees, keyed by VRM human-bone name. */
    val bones: Map<String, RawBoneRotation> = emptyMap(),
)

/** A generic named preset (hand gesture / body gesture / body motion). */
data class NamedPreset(
    val id: String,
    val name: String,
    val description: String = "",
)

/** A choreography sequence: an ordered list of commands. */
data class SequencePreset(
    val id: String,
    val name: String,
    val commands: List<AvatarCommand>,
    val description: String = "",
)
