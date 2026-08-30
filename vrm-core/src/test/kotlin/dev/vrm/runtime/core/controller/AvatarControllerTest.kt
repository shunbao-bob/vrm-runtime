package dev.vrm.runtime.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Acceptance for the AvatarController layer: command execution, state
 * transitions, events, batches and the sequential queue.
 */
class AvatarControllerTest {

    /** A recording binding that captures each call. */
    private class RecordingBinding : AvatarBinding {
        val calls = CopyOnWriteArrayList<String>()
        var currentPose: String? = null
        var currentExpression: String? = null
        var vrmaSrc: String? = null
        var vrmaPlayingState = false

        override fun setPose(id: String?) { calls += "setPose:$id"; currentPose = id }
        override fun setHandGesture(id: String?) { calls += "setHandGesture:$id" }
        override fun setBodyGesture(id: String?) { calls += "setBodyGesture:$id" }
        override fun setBodyMotion(id: String?) { calls += "setBodyMotion:$id" }
        override fun setExpression(id: String?) { calls += "setExpression:$id"; currentExpression = id }
        override fun playVrma(source: String?, loop: Boolean) { calls += "playVrma:$source:$loop"; vrmaSrc = source; vrmaPlayingState = source != null }
        override fun setSequence(id: String?) { calls += "setSequence:$id" }
        override fun setRawPose(bones: Map<String, RawBoneRotation>) { calls += "setRawPose:${bones.size}" }
        override fun setRawExpression(values: Map<String, Float>) { calls += "setRawExpression:${values.size}" }
        override fun reset() { calls += "reset"; currentPose = null; currentExpression = null; vrmaSrc = null; vrmaPlayingState = false }
    }

    private val config = AvatarConfig(
        expressions = listOf(ExpressionPreset("happy", "Happy"), ExpressionPreset("sad", "Sad")),
        poses = listOf(PosePreset("relaxed", "Relaxed")),
        animations = listOf(
            AnimationPreset("wave", "Wave", "animations/wave.vrma", category = "greeting", loop = false),
        ),
        sequences = listOf(
            SequencePreset(
                "greet", "Greet",
                listOf(
                    AvatarCommand.SetExpression("happy"),
                    AvatarCommand.Wait(50),
                    AvatarCommand.Reset,
                ),
            ),
        ),
    )

    @Test
    fun `execute sets expression and updates state`() {
        val binding = RecordingBinding()
        val controller = AvatarController(binding, config)

        controller.setExpression("happy")
        assertEquals("happy", binding.currentExpression)
        assertEquals("happy", controller.currentState.expression)
    }

    @Test
    fun `unknown expression id emits error`() {
        val binding = RecordingBinding()
        val controller = AvatarController(binding, config)
        val errors = mutableListOf<AvatarEvent>()
        controller.on(AvatarEventType.ERROR) { errors += it }

        controller.setExpression("nonexistent")
        assertTrue(errors.isNotEmpty(), "should emit an error event")
        assertNull(binding.currentExpression)
    }

    @Test
    fun `reset clears all state`() {
        val binding = RecordingBinding()
        val controller = AvatarController(binding, config)
        controller.batch(
            listOf(
                AvatarCommand.SetExpression("happy"),
                AvatarCommand.SetPose("relaxed"),
                AvatarCommand.PlayVrma(id = "wave"),
            ),
        )
        assertEquals("happy", binding.currentExpression)
        assertEquals("relaxed", binding.currentPose)
        assertEquals("animations/wave.vrma", binding.vrmaSrc)
        assertTrue(binding.vrmaPlayingState)

        controller.reset()
        assertNull(binding.currentExpression)
        assertNull(binding.currentPose)
        assertNull(binding.vrmaSrc)
        assertFalse(binding.vrmaPlayingState)
        assertTrue(controller.currentState.isIdle)
    }

    @Test
    fun `raw expression and pose commands`() {
        val binding = RecordingBinding()
        val controller = AvatarController(binding, config)

        controller.execute(AvatarCommand.RawExpression(mapOf("happy" to 1f, "blink" to 0.5f)))
        assertTrue(binding.calls.any { it == "setRawExpression:2" })
        assertEquals("raw(2)", controller.currentState.expression)

        controller.execute(AvatarCommand.RawPose(mapOf("head" to RawBoneRotation())))
        assertTrue(binding.calls.any { it == "setRawPose:1" })
        assertEquals("raw(1)", controller.currentState.pose)
    }

    @Test
    fun `stop vrma clears playing state`() {
        val binding = RecordingBinding()
        val controller = AvatarController(binding, config)
        controller.playVrma("animations/wave.vrma", loop = false)
        assertTrue(binding.vrmaPlayingState)

        controller.stopVrma()
        assertFalse(binding.vrmaPlayingState)
        assertNull(controller.currentState.vrmaSource)
    }

    @Test
    fun `onVrmaComplete emits event and clears state`() {
        val binding = RecordingBinding()
        val controller = AvatarController(binding, config)
        val completed = mutableListOf<AvatarEvent>()
        controller.on(AvatarEventType.VRMA_COMPLETE) { completed += it }

        controller.playVrma("animations/wave.vrma")
        assertTrue(controller.currentState.vrmaPlaying)
        controller.onVrmaComplete()

        assertTrue(completed.isNotEmpty())
        assertFalse(controller.currentState.vrmaPlaying)
    }

    @Test
    @Timeout(10)
    fun `queue runs sequence respecting waits`() {
        val binding = RecordingBinding()
        val controller = AvatarController(binding, config)
        val done = CountDownLatch(1)
        controller.on(AvatarEventType.QUEUE_COMPLETE) { done.countDown() }

        controller.queue(
            listOf(
                AvatarCommand.SetExpression("happy"),
                AvatarCommand.Wait(100),
                AvatarCommand.SetExpression("sad"),
            ),
        )

        assertTrue(done.await(5, TimeUnit.SECONDS), "queue should complete")
        assertEquals("sad", binding.currentExpression)
        assertEquals("sad", controller.currentState.expression)
        assertFalse(controller.currentState.queueRunning)
    }

    @Test
    @Timeout(10)
    fun `queue emits start and complete events`() {
        val binding = RecordingBinding()
        val controller = AvatarController(binding, config)
        val starts = CountDownLatch(1)
        val completes = CountDownLatch(1)
        controller.on(AvatarEventType.QUEUE_START) { starts.countDown() }
        controller.on(AvatarEventType.QUEUE_COMPLETE) { completes.countDown() }

        controller.queue(listOf(AvatarCommand.SetExpression("happy")))

        assertTrue(starts.await(3, TimeUnit.SECONDS))
        assertTrue(completes.await(3, TimeUnit.SECONDS))
        assertFalse(controller.currentState.queueRunning)
    }

    @Test
    fun `playVrma resolves preset id through config`() {
        val binding = RecordingBinding()
        val controller = AvatarController(binding, config)

        controller.execute(AvatarCommand.PlayVrma(id = "wave"))
        assertEquals("animations/wave.vrma", binding.vrmaSrc)
    }

    @Test
    fun `event log retains recent events`() {
        val binding = RecordingBinding()
        val controller = AvatarController(binding, config)
        controller.maxLogSize = 5

        repeat(10) { controller.setExpression("happy") }
        val log = controller.eventLog()
        assertTrue(log.size <= 5, "log should be capped")
    }

    @Test
    fun `state starts ready`() {
        val binding = RecordingBinding()
        val controller = AvatarController(binding, config)
        assertTrue(controller.isReady())
        assertTrue(controller.currentState.ready)
    }

    @Test
    fun `unsubscribe removes listener`() {
        val binding = RecordingBinding()
        val controller = AvatarController(binding, config)
        var count = 0
        val unsub = controller.on(AvatarEventType.STATE_CHANGE) { count++ }

        controller.setExpression("happy")
        assertTrue(count > 0)

        unsub()
        val before = count
        controller.setExpression("sad")
        assertEquals(before, count, "listener should be unsubscribed")
    }

    @Test
    fun `sequence preset can be resolved and run`() {
        val binding = RecordingBinding()
        val controller = AvatarController(binding, config)

        controller.execute(AvatarCommand.SetSequence("greet"))
        assertEquals("greet", controller.currentState.sequenceId)
        assertTrue(controller.currentState.sequencePlaying)
    }
}
