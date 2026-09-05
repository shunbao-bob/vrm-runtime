package dev.vrm.runtime.character

import dev.vrm.runtime.core.controller.AvatarCommand
import dev.vrm.runtime.core.lipsync.SubtitleFrame
import dev.vrm.runtime.core.motion.MotionSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The high-level "director" facade for an autonomous VRM character (task 10).
 *
 * Composes the engine-agnostic controllers into one object the host / LLM
 * drives:
 *  - [motion]: whole-body movement (MoveTo / wander / locomotion state)
 *  - [emotion]: emotion state machine (decay, dominant emotion → tone/expression)
 *  - [expressions]: expression weight smoothing + commit
 *  - [lipsync]: subtitle-driven mouth animation
 *
 * It also exposes [drive] to turn an LLM's structured JSON intent (actions)
 * into the matching command stream — the single integration point for
 * "LLM 驱动情感 / 驱动动作 / 口型同步".
 *
 * Pure Kotlin: JVM-testable.
 */
class CharacterController(
    val output: CharacterOutput,
    val scene: SceneConfig,
    private val emotionProfile: EmotionProfile = EmotionProfile.defaults(),
) {

    val collision: CollisionWorld = CollisionWorld.from(scene)
    val motion = MotionController(output, collision)
    val emotions = EmotionEngine(emotionProfile)
    val expressions = ExpressionController(output)
    val lipsync = LipSyncController(output)

    /** Whether the idle breathing clip is playing. */
    var idleEnabled: Boolean = false
        private set

    /** When true, [update] auto-plays [WalkMotion.spec] while the character
     *  is moving (locomotion state != IDLE) and stops it on idle — i.e.
     *  "random gentle walk" needs no host animation plumbing: moving =
     *  legs animate, stopped = animation stops. Opt-in so existing hosts
     *  (main demo) that drive animation themselves are unaffected. */
    var autoWalkAnim = false
        private set

    private val walkSpec by lazy { WalkMotion.spec() }
    private var walkAnimPlaying = false
    private var emotionOwnedExpression: String? = null

    /** LLM-driven keyframe motion: latest validated spec (for inspection/tests). */
    var lastMotionSpec: MotionSpec? = null
        private set

    init {
        // Keep the wander AI in sync with the scene bounds by default.
        motion.wander = WanderAI(collision)
    }

    /** Enable the auto-walk animation while moving (see [autoWalkAnim]). */
    fun setAutoWalkAnim(enabled: Boolean) {
        autoWalkAnim = enabled
        if (!enabled && walkAnimPlaying) {
            walkAnimPlaying = false
            stopLocomotionAnim()
        }
    }

    /** Enable autonomous wandering (random targets, obstacle + bounds aware). */
    var wanderEnabled: Boolean
        get() = motion.wanderEnabled
        set(v) { motion.wanderEnabled = v }

    // ------------------------------------------------------------------
    // High-level actions
    // ------------------------------------------------------------------

    /** Speak text (with optional subtitle frames for precise lip-sync). */
    fun speak(text: String, frames: List<SubtitleFrame> = emptyList()) {
        lipsync.start(text.length)
        if (frames.isNotEmpty()) {
            frames.forEach { lipsync.pushSubtitleFrame(it) }
        } else {
            lipsync.speakText(text)
        }
    }

    /** Trigger an emotion by name (e.g. "happy"); drives expression + tone. */
    fun emote(name: String, intensity: Float = 1f): Boolean {
        if (name !in emotionProfile.emotions) return false
        emotions.emote(name, intensity)
        applyDominantEmotion()
        return true
    }

    /** Set a raw expression weight directly. */
    fun setExpression(name: String, weight: Float) {
        expressions.setExpression(name, weight)
    }

    /** Move to an absolute world XZ. */
    fun moveTo(x: Float, z: Float, speed: Float = motion.walkSpeed): Boolean {
        motion.wanderEnabled = false
        return motion.moveTo(x, z, speed)
    }

    /** Move to a named scene anchor. */
    fun moveToAnchor(id: String, speed: Float = motion.walkSpeed): Boolean {
        val anchor = scene.anchors.firstOrNull { it.id == id } ?: return false
        motion.wanderEnabled = false
        return motion.moveToAnchor(anchor, speed)
    }

    /** Stop all movement. */
    fun stop() = motion.stop()

    /** Reset emotions / expressions / lipsync to neutral. */
    fun reset() {
        emotions.clear()
        expressions.clear()
        lipsync.stop()
        motion.stop()
    }

    /** Play an LLM-generated keyframe motion. */
    fun playMotion(spec: MotionSpec, loop: Boolean = spec.loop) {
        lastMotionSpec = spec
        motion.stop()
        output.execute(dev.vrm.runtime.core.controller.AvatarCommand.PlayMotionSpec(spec, loop))
    }

    /**
     * Play a locomotion clip (e.g. [WalkMotion.spec]) WITHOUT cancelling the
     * current MoveTo — the bone animation runs while the engine keeps moving
     * the model node's world position. Use for walking: motion stays active,
     * the legs just animate in sync. Stops any previous keyframe motion.
     */
    fun playLocomotion(spec: MotionSpec, loop: Boolean = true) {
        lastMotionSpec = spec
        output.execute(dev.vrm.runtime.core.controller.AvatarCommand.PlayMotionSpec(spec, loop))
    }

    /** Stop only the keyframe animation (keeps locomotion / MoveTo running). */
    fun stopLocomotionAnim() {
        output.execute(dev.vrm.runtime.core.controller.AvatarCommand.StopVrma)
    }

    /** Start the programmatic idle (breathing + blink) loop. */
    fun playIdle(loop: Boolean = true) {
        idleEnabled = true
        playMotion(IdleMotion.spec(), loop)
    }

    /** Stop any keyframe motion (returns to whatever the host drives). */
    fun stopMotion() {
        idleEnabled = false
        output.execute(dev.vrm.runtime.core.controller.AvatarCommand.StopVrma)
    }

    /** Generate a motion from text via the supplied [speaker] and play it. */
    fun generateAndPlay(text: String, speaker: MotionSpeaker, loop: Boolean = false): Boolean {
        val json = speaker.generate(text)
        val spec = try {
            dev.vrm.runtime.core.motion.MotionSpecValidator.validate(
                MotionSpec.fromJson(json)
            )
        } catch (t: Exception) {
            println("MotionLLM: generateAndPlay failed: ${t.message}")
            return false
        }
        playMotion(spec, loop)
        return true
    }

    /** Advance the whole character one frame. Call every frame on the main thread. */
    fun update(dt: Float) {
        emotions.update(dt)
        // Re-apply the dominant emotion's expression each frame (decay keeps it live).
        applyDominantEmotion()
        expressions.update(dt)
        expressions.commit()
        lipsync.update()
        motion.update(dt)
        // Auto walk animation: legs animate while moving, stop on idle.
        if (autoWalkAnim) {
            val s = motion.state
            if (s != MotionController.LocomotionState.IDLE && !walkAnimPlaying) {
                playLocomotion(walkSpec, loop = true)
                walkAnimPlaying = true
            } else if (s == MotionController.LocomotionState.IDLE && walkAnimPlaying) {
                walkAnimPlaying = false
                stopLocomotionAnim()
            }
        }
    }

    private fun applyDominantEmotion() {
        val expr = emotions.dominantExpression()
        val previous = emotionOwnedExpression
        if (previous != null && previous != expr) expressions.setExpression(previous, 0f)
        if (expr == null) {
            emotionOwnedExpression = null
            return
        }
        emotionOwnedExpression = expr
        // Use the fade-smoothed weight so the face eases in/out.
        val w = emotions.expressionWeight
        expressions.setExpression(expr, w)
    }

    private companion object {
        val INTENT_JSON = Json { ignoreUnknownKeys = true }
    }

    // ------------------------------------------------------------------
    // LLM intent driving
    // ------------------------------------------------------------------

    /**
     * Parse an LLM's structured JSON intent and execute the actions in order.
     *
     * Example:
     * ```json
     * {
     *   "actions": [
     *     { "type": "emote", "name": "happy", "intensity": 1.0 },
     *     { "type": "move_to", "x": 3.0, "z": 2.0, "speed": 1.5 },
     *     { "type": "speak", "text": "你好" }
     *   ]
     * }
     * ```
     *
     * Unknown action types are logged and skipped, so the schema can evolve
     * without the host crashing. Returns the number of actions executed.
     */
    fun drive(intentJson: String): Int {
        val intent = try {
            INTENT_JSON.decodeFromString<CharacterIntent>(intentJson)
        } catch (t: Exception) {
            output.execute(AvatarCommand.RawExpression(emptyMap())) // no-op keepalive
            return 0
        }
        return drive(intent)
    }

    /** Execute a parsed [CharacterIntent]. */
    fun drive(intent: CharacterIntent): Int {
        var executed = 0
        for (a in intent.actions) {
            when (a.type) {
                "emote" -> if (a.name?.let { emote(it, a.intensity ?: 1f) } == true) executed++
                "move_to" -> if (a.x != null && a.z != null && moveTo(a.x, a.z, a.speed ?: motion.walkSpeed)) executed++
                "move_to_anchor" -> if (a.name?.let { moveToAnchor(it) } == true) executed++
                "speak" -> if (!a.text.isNullOrBlank()) { speak(a.text); executed++ }
                "stop" -> { stop(); executed++ }
                "set_expression" -> if (!a.name.isNullOrBlank()) { setExpression(a.name, a.intensity ?: 1f); executed++ }
                "wander" -> { wanderEnabled = a.enabled ?: true; executed++ }
                "play_motion" -> if (a.motionSpecJson?.let { playMotionJson(it) } == true) executed++
                "idle" -> { if (a.enabled != false) playIdle() else stopMotion(); executed++ }
                // unknown action types: ignore (schema may grow)
            }
        }
        return executed
    }

    /** Parse and play a MotionSpec JSON (validated). */
    fun playMotionJson(json: String): Boolean {
        return try {
            val spec = dev.vrm.runtime.core.motion.MotionSpecValidator.validate(
                MotionSpec.fromJson(json)
            )
            playMotion(spec)
            true
        } catch (t: Exception) {
            println("MotionLLM: playMotionJson failed: ${t.message}")
            false
        }
    }
}

/** Structured intent from an LLM: an ordered list of high-level actions. */
@Serializable
data class CharacterIntent(
    val actions: List<CharacterAction> = emptyList(),
)

/** One action in a [CharacterIntent]. */
@Serializable
data class CharacterAction(
    val type: String,
    val name: String? = null,
    val text: String? = null,
    val intensity: Float? = null,
    val x: Float? = null,
    val z: Float? = null,
    val speed: Float? = null,
    val enabled: Boolean? = null,
    val motionSpecJson: String? = null,
)
