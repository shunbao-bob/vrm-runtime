package dev.vrm.runtime.core.controller

import dev.vrm.runtime.core.motion.MotionSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A unified imperative control layer for a VRM avatar, port of xlunar-ai-avatar's
 * `AvatarController` (AvatarController.ts). Engine-agnostic: commands are
 * applied through an [AvatarBinding] the host provides; preset ids may be
 * resolved against an [AvatarConfig].
 *
 * Features:
 *  - single commands via [execute]
 *  - simultaneous batch via [batch]
 *  - sequential, timing-aware choreography via [queue] (abortable)
 *  - event subscription ([on] / [onAny]) and state snapshots ([state])
 *
 * The core is zero-dependency (no coroutines); the command queue runs on a
 * daemon thread so [AvatarCommand.Wait] blocks without freezing the caller.
 */
class AvatarController(
    private val binding: AvatarBinding,
    private val config: AvatarConfig = AvatarConfig.EMPTY,
    private val queueDispatcher: ((() -> Unit) -> Unit) = { it() },
) {
    private val listeners = HashMap<AvatarEventType, MutableList<(AvatarEvent) -> Unit>>()
    private val wildcardListeners = mutableListOf<(AvatarEvent) -> Unit>()

    private var state: AvatarState = AvatarState()
    private val eventLog = ArrayDeque<AvatarEvent>()

    /** Max number of events retained in [eventLog]. */
    var maxLogSize: Int = 200

    // queue state
    @Volatile private var queueRunning = false
    @Volatile private var queueAborted = false
    private var queueCommands: List<AvatarCommand> = emptyList()
    private var queueThread: Thread? = null
    private val queueGeneration = AtomicLong(0L)

    init {
        state = AvatarState(ready = true)
    }

    // ==========================================================================
    // Command execution
    // ==========================================================================

    /**
     * Execute a single command immediately. Returns the number of milliseconds
     * the command blocks the queue for (0 for non-blocking commands, and the
     * requested duration for [AvatarCommand.Wait]).
     */
    fun execute(command: AvatarCommand): Long {
        emit(AvatarEventType.COMMAND, mapOf("command" to command))
        return try {
            when (command) {
                is AvatarCommand.SetPose -> {
                    config.requirePose(command.id)
                    binding.setPose(command.id)
                    updateState { it.copy(pose = command.id) }
                    0
                }

                is AvatarCommand.SetHandGesture -> {
                    config.requireHandGesture(command.id)
                    binding.setHandGesture(command.id)
                    updateState { it.copy(handGesture = command.id) }
                    0
                }

                is AvatarCommand.SetBodyGesture -> {
                    config.requireBodyGesture(command.id)
                    binding.setBodyGesture(command.id)
                    updateState { it.copy(bodyGesture = command.id) }
                    0
                }

                is AvatarCommand.SetBodyMotion -> {
                    config.requireBodyMotion(command.id)
                    binding.setBodyMotion(command.id)
                    updateState { it.copy(bodyMotion = command.id) }
                    0
                }

                is AvatarCommand.SetExpression -> {
                    config.requireExpression(command.id)
                    binding.setExpression(command.id)
                    updateState { it.copy(expression = command.id) }
                    0
                }

                is AvatarCommand.PlayVrma -> {
                    val source = command.id?.let { config.animationSource(it) } ?: command.source
                    if (source == null) {
                        emitError("PlayVrma: no id and no source")
                    } else {
                        binding.playVrma(source, command.loop)
                        updateState { it.copy(vrmaSource = source, vrmaPlaying = true) }
                    }
                    0
                }

                is AvatarCommand.SetSequence -> {
                    config.requireSequence(command.id)
                    binding.setSequence(command.id)
                    updateState { it.copy(sequenceId = command.id, sequencePlaying = true) }
                    0
                }

                is AvatarCommand.Wait -> command.durationMillis

                is AvatarCommand.Reset -> {
                    binding.reset()
                    updateState {
                        it.copy(
                            pose = null, handGesture = null, bodyGesture = null, bodyMotion = null,
                            expression = null, vrmaSource = null, vrmaPlaying = false,
                            sequenceId = null, sequencePlaying = false,
                        )
                    }
                    0
                }

                is AvatarCommand.ResetPose -> {
                    binding.setPose(null)
                    binding.setHandGesture(null)
                    updateState { it.copy(pose = null, handGesture = null) }
                    0
                }

                is AvatarCommand.ResetExpression -> {
                    binding.setExpression(null)
                    updateState { it.copy(expression = null) }
                    0
                }

                is AvatarCommand.StopVrma -> {
                    binding.playVrma(null, loop = true)
                    updateState { it.copy(vrmaSource = null, vrmaPlaying = false) }
                    0
                }

                is AvatarCommand.StopSequence -> {
                    binding.setSequence(null)
                    updateState { it.copy(sequenceId = null, sequencePlaying = false) }
                    0
                }

                is AvatarCommand.RawPose -> {
                    binding.setRawPose(command.bones)
                    updateState { it.copy(pose = "raw(${command.bones.size})") }
                    0
                }

                is AvatarCommand.RawExpression -> {
                    binding.setRawExpression(command.values)
                    updateState { it.copy(expression = "raw(${command.values.size})") }
                    0
                }

                is AvatarCommand.MoveTo -> {
                    binding.moveTo(command.x, command.z, command.y, command.speed, command.arrivalRadius)
                    updateState { it.copy(bodyMotion = "moveTo(%.2f,%.2f)".format(command.x, command.z)) }
                    0
                }

                is AvatarCommand.TurnTo -> {
                    binding.turnTo(command.yawDegrees, command.turnSpeedDegPerSec)
                    0
                }

                is AvatarCommand.MoveToAnchor -> {
                    val anchor = config.anchorById(command.anchorId)
                    if (anchor == null) {
                        emitError("MoveToAnchor: unknown anchor '${command.anchorId}'")
                    } else {
                        binding.moveTo(anchor.x, anchor.z, anchor.y, command.speed, anchor.arrivalRadius)
                        updateState { it.copy(bodyMotion = "anchor(${command.anchorId})") }
                    }
                    0
                }

                is AvatarCommand.SetWorldTransform -> {
                    binding.setWorldTransform(command.x, command.y, command.z, command.yawDegrees)
                    0
                }

                is AvatarCommand.StopMove -> {
                    binding.stopMove(command.deceleration)
                    0
                }

                is AvatarCommand.SetLocomotion -> {
                    binding.setLocomotion(
                        command.idle, command.walk, command.run,
                        command.maxWalkSpeed, command.maxRunSpeed,
                    )
                    0
                }

                is AvatarCommand.PlayMotionSpec -> {
                    binding.playMotionSpec(command.spec, command.loop)
                    updateState { it.copy(vrmaSource = "spec:${command.spec.name}", vrmaPlaying = true) }
                    0
                }
            }
        } catch (e: Exception) {
            emitError("execute failed: ${e.message}")
            0
        }
    }

    /** Execute multiple commands simultaneously (no waiting between them). */
    fun batch(commands: List<AvatarCommand>) {
        commands.forEach { if (it !is AvatarCommand.Wait) execute(it) }
    }

    /**
     * Execute commands sequentially on a background thread, honoring
     * [AvatarCommand.Wait] durations. Abortable via [abortQueue].
     */
    @Synchronized
    fun queue(commands: List<AvatarCommand>) {
        abortQueue()
        val generation = queueGeneration.incrementAndGet()
        queueAborted = false
        try {
            queueDispatcher {
                try {
                    if (queueGeneration.get() != generation || queueAborted) return@queueDispatcher
                    queueCommands = commands
                    queueRunning = true
                    emit(AvatarEventType.QUEUE_START, mapOf("length" to commands.size))
                    updateState { it.copy(queueLength = commands.size, queueRunning = true) }

                    queueThread = Thread({
                        var failed = false
                        for (cmd in commands) {
                            if (!isQueueCurrent(generation)) break
                            val dispatched = dispatchQueueAndWait(generation) { execute(cmd) }
                            if (dispatched == null) {
                                failed = true
                                invalidateQueue(generation)
                                break
                            }
                            val blockMs = dispatched.getOrElse {
                                failed = true
                                invalidateQueue(generation)
                                0L
                            }
                            if (failed) break
                            if (blockMs > 0) {
                                try {
                                    Thread.sleep(blockMs)
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                        }
                        if (!failed && !dispatchQueue(generation) { finishQueue(generation) }) {
                            invalidateQueue(generation)
                        }
                    }, "AvatarController-queue").apply { isDaemon = true; start() }
                } catch (_: Throwable) {
                    invalidateQueueStart(generation)
                }
            }
        } catch (_: Exception) {
            invalidateQueue(generation)
        }
    }

    /** Abort the currently running command queue. */
    @Synchronized
    fun abortQueue() {
        val generation = queueGeneration.incrementAndGet()
        queueAborted = true
        queueRunning = false
        queueThread?.interrupt()
        queueThread = null
        dispatchQueue(generation) {
            if (queueGeneration.get() == generation) {
                queueCommands = emptyList()
                updateState { it.copy(queueLength = 0, queueRunning = false) }
            }
        }
    }

    // ==========================================================================
    // Convenience methods
    // ==========================================================================

    fun setPose(id: String) = execute(AvatarCommand.SetPose(id))
    fun setHandGesture(id: String) = execute(AvatarCommand.SetHandGesture(id))
    fun setBodyGesture(id: String) = execute(AvatarCommand.SetBodyGesture(id))
    fun setBodyMotion(id: String) = execute(AvatarCommand.SetBodyMotion(id))
    fun setExpression(id: String) = execute(AvatarCommand.SetExpression(id))
    fun playVrma(idOrSource: String, loop: Boolean = true) =
        execute(AvatarCommand.PlayVrma(id = null, source = idOrSource, loop = loop))
    fun stopVrma() = execute(AvatarCommand.StopVrma)
    fun playSequence(id: String) = execute(AvatarCommand.SetSequence(id))
    fun stopSequence() = execute(AvatarCommand.StopSequence)

    // ---- locomotion convenience ----
    fun moveTo(x: Float, z: Float, speed: Float = 1.5f, arrivalRadius: Float = 0.15f) =
        execute(AvatarCommand.MoveTo(x = x, z = z, speed = speed, arrivalRadius = arrivalRadius))
    fun turnTo(yawDegrees: Float, turnSpeedDegPerSec: Float = 180f) =
        execute(AvatarCommand.TurnTo(yawDegrees, turnSpeedDegPerSec))
    fun moveToAnchor(anchorId: String, speed: Float = 1.5f) =
        execute(AvatarCommand.MoveToAnchor(anchorId, speed))
    fun teleportTo(x: Float, y: Float, z: Float, yawDegrees: Float = 0f) =
        execute(AvatarCommand.SetWorldTransform(x, y, z, yawDegrees))
    fun stopMove(deceleration: Float = 0.5f) = execute(AvatarCommand.StopMove(deceleration))
    fun setLocomotion(idle: String? = null, walk: String? = null, run: String? = null) =
        execute(AvatarCommand.SetLocomotion(idle, walk, run))

    fun reset() = execute(AvatarCommand.Reset)

    // ==========================================================================
    // State
    // ==========================================================================

    /** Current control state snapshot. */
    val currentState: AvatarState get() = state

    fun isReady(): Boolean = state.ready

    /** Recent events, oldest first. */
    fun eventLog(): List<AvatarEvent> = eventLog.toList()

    // ==========================================================================
    // Events
    // ==========================================================================

    /** Subscribe to a specific event type. Returns an unsubscribe lambda. */
    fun on(type: AvatarEventType, callback: (AvatarEvent) -> Unit): () -> Unit {
        listeners.getOrPut(type) { mutableListOf() }.add(callback)
        return { off(type, callback) }
    }

    /** Subscribe to all events. Returns an unsubscribe lambda. */
    fun onAny(callback: (AvatarEvent) -> Unit): () -> Unit {
        wildcardListeners.add(callback)
        return { wildcardListeners.remove(callback) }
    }

    fun off(type: AvatarEventType, callback: (AvatarEvent) -> Unit) {
        listeners[type]?.remove(callback)
    }

    // ==========================================================================
    // Renderer notifications (called by the host)
    // ==========================================================================

    /** Notify that a motion sequence completed (called by the host). */
    fun onSequenceComplete() {
        updateState { it.copy(sequenceId = null, sequencePlaying = false) }
        emit(AvatarEventType.SEQUENCE_COMPLETE, emptyMap())
    }

    /** Notify that a VRMA clip completed (called by the host). */
    fun onVrmaComplete() {
        updateState { it.copy(vrmaPlaying = false) }
        emit(AvatarEventType.VRMA_COMPLETE, emptyMap())
    }

    // ==========================================================================
    // internals
    // ==========================================================================

    private fun finishQueue(generation: Long) {
        if (queueGeneration.get() != generation) return
        val wasRunning = queueRunning
        queueRunning = false
        queueCommands = emptyList()
        if (wasRunning && !queueAborted) {
            updateState { it.copy(queueLength = 0, queueRunning = false) }
            emit(AvatarEventType.QUEUE_COMPLETE, emptyMap())
        }
    }

    private fun isQueueCurrent(generation: Long): Boolean =
        queueGeneration.get() == generation && queueRunning && !queueAborted

    /** Dispatch without letting a rejected/closed host executor escape a queue thread. */
    private fun dispatchQueue(generation: Long, block: () -> Unit): Boolean = try {
        queueDispatcher {
            if (queueGeneration.get() == generation) block()
        }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Dispatch one queue step and wait for it. The generation is checked again inside
     * the host runnable so an already-posted command cannot execute after abortQueue().
     */
    private fun <T> dispatchQueueAndWait(generation: Long, block: () -> T): Result<T>? {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        try {
            queueDispatcher {
                try {
                    result = if (isQueueCurrent(generation)) runCatching(block) else null
                } finally {
                    latch.countDown()
                }
            }
        } catch (_: Exception) {
            return null
        }
        val completed = try {
            latch.await(30, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return if (completed) result else null
    }

    private fun invalidateQueueStart(generation: Long) {
        if (!queueGeneration.compareAndSet(generation, generation + 1)) return
        queueAborted = true
        queueRunning = false
        queueThread = null
        queueCommands = emptyList()
        state = state.copy(queueLength = 0, queueRunning = false)
    }

    private fun invalidateQueue(generation: Long) {
        if (!queueGeneration.compareAndSet(generation, generation + 1)) return
        queueAborted = true
        queueRunning = false
        queueThread = null
        val invalidatedGeneration = generation + 1
        dispatchQueue(invalidatedGeneration) {
            queueCommands = emptyList()
            updateState { it.copy(queueLength = 0, queueRunning = false) }
        }
    }

    private fun updateState(transform: (AvatarState) -> AvatarState) {
        state = transform(state)
        emit(AvatarEventType.STATE_CHANGE, mapOf("state" to state))
    }

    private fun emit(type: AvatarEventType, data: Map<String, Any?>) {
        val event = AvatarEvent(type = type, data = data)
        eventLog.addLast(event)
        if (eventLog.size > maxLogSize) eventLog.removeFirst()
        listeners[type]?.toList()?.forEach { it(event) }
        wildcardListeners.toList().forEach { it(event) }
    }

    private fun emitError(message: String) {
        emit(AvatarEventType.ERROR, mapOf("message" to message))
    }
}
