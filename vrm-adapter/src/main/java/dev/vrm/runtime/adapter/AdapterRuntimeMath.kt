package dev.vrm.runtime.adapter

import kotlin.math.max
import kotlin.math.sqrt

/** Coordinates asynchronous loads without depending on Android or Filament. */
internal class AvatarLoadLifecycle {
    private var generation = 0L
    private var destroyed = false

    @Synchronized
    fun beginLoad(): Long {
        check(!destroyed) { "AvatarRenderer is destroyed" }
        return ++generation
    }

    @Synchronized
    fun mayApply(token: Long): Boolean = !destroyed && token == generation

    /** Atomically validate a generation and publish its fully-built resources. */
    @Synchronized
    fun applyIfCurrent(token: Long, publish: () -> Unit): Boolean {
        if (destroyed || token != generation) return false
        publish()
        return true
    }

    @Synchronized
    fun destroy() {
        destroyed = true
        generation++
    }
}

/** Destruction callbacks are deliberately separate so every path preserves Filament ordering. */
internal class AvatarLoadResources(
    private val node: (() -> Unit)?,
    private val applier: (() -> Unit)?,
    private val controller: (() -> Unit)?,
) {
    fun release() {
        // Stop logical owners first so no callback can touch the asset while it is
        // being torn down. ModelNode then releases renderables/asset references;
        // MToon instances/materials are released last, after renderables are gone.
        runCatching { controller?.invoke() }
        runCatching { node?.invoke() }
        runCatching { applier?.invoke() }
    }
}

internal object LocomotionMath {
    data class Step(val x: Float, val z: Float, val arrived: Boolean)
    data class DecelerationStep(val speed: Float, val distance: Float)
    data class Direction(val x: Float, val z: Float)

    fun validateMove(deltaSeconds: Float, speed: Float, arrivalRadius: Float) {
        require(deltaSeconds.isFinite() && deltaSeconds >= 0f) { "deltaSeconds must be finite and non-negative" }
        require(speed.isFinite() && speed > 0f) { "speed must be finite and positive" }
        require(arrivalRadius.isFinite() && arrivalRadius >= 0f) { "arrivalRadius must be finite and non-negative" }
    }

    fun stepToward(x: Float, z: Float, targetX: Float, targetZ: Float, speed: Float, deltaSeconds: Float): Step {
        validateMove(deltaSeconds, speed, 0f)
        val dx = targetX - x
        val dz = targetZ - z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance == 0f) return Step(targetX, targetZ, true)
        val step = minOf(distance, speed * deltaSeconds)
        val scale = step / distance
        return Step(x + dx * scale, z + dz * scale, step >= distance)
    }

    fun decelerate(speed: Float, deceleration: Float, deltaSeconds: Float): Float {
        require(speed.isFinite() && speed >= 0f)
        require(deceleration.isFinite() && deceleration > 0f)
        require(deltaSeconds.isFinite() && deltaSeconds >= 0f)
        return max(0f, speed - deceleration * deltaSeconds)
    }

    fun decelerationForDuration(speed: Float, durationSeconds: Float): Float {
        require(speed.isFinite() && speed >= 0f)
        require(durationSeconds.isFinite() && durationSeconds > 0f)
        return speed / durationSeconds
    }

    /** Integrate constant deceleration using average velocity until this frame's stop time. */
    fun decelerationStep(speed: Float, deceleration: Float, deltaSeconds: Float): DecelerationStep {
        val nextSpeed = decelerate(speed, deceleration, deltaSeconds)
        val effectiveSeconds = minOf(deltaSeconds, speed / deceleration)
        return DecelerationStep(nextSpeed, (speed + nextSpeed) * 0.5f * effectiveSeconds)
    }

    fun movementDirection(deltaX: Float, deltaZ: Float, previous: Direction): Direction {
        val length = sqrt(deltaX * deltaX + deltaZ * deltaZ)
        return if (length > 0f) Direction(deltaX / length, deltaZ / length) else previous
    }

    fun visualYaw(nodeYaw: Float, facingOffset: Float): Float = nodeYaw + facingOffset
    fun nodeYawForVisual(visualYaw: Float, facingOffset: Float): Float = visualYaw - facingOffset
}

internal object AnimationPlaybackMath {
    fun sampleScalar(times: FloatArray, values: FloatArray, time: Float): Float {
        require(times.isNotEmpty() && times.size == values.size)
        if (time <= times.first()) return values.first()
        if (time >= times.last()) return values.last()
        var low = 0
        var high = times.lastIndex - 1
        var segment = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (times[mid] <= time) {
                segment = mid
                low = mid + 1
            } else high = mid - 1
        }
        val span = times[segment + 1] - times[segment]
        val alpha = if (span > 0f) (time - times[segment]) / span else 0f
        return values[segment] + (values[segment + 1] - values[segment]) * alpha
    }

    fun expressionWeight(sample: Float, influence: Float): Float = sample * influence.coerceIn(0f, 1f)

    fun blendFactor(hasPosition: Boolean, hasRotation: Boolean, weight: Float): Float =
        if (hasPosition || hasRotation) weight.coerceIn(0f, 1f) else 0f
}

internal object ExpressionBlendMath {
    fun merge(
        names: Set<String>,
        previous: Map<String, Float>,
        next: Map<String, Float>,
    ): Map<String, Float> = names.associateWith { name ->
        (previous.getOrDefault(name, 0f) + next.getOrDefault(name, 0f)).coerceIn(0f, 1f)
    }
}

internal object AnimationPlayerOwnership {
    data class Transition<T>(val previous: T?, val current: T)

    fun <T> install(
        previous: T?,
        current: T?,
        currentPlaying: Boolean,
        next: T,
        clearOwnedExpressions: (T) -> Unit,
    ): Transition<T> {
        if (previous != null && previous !== current) clearOwnedExpressions(previous)
        return if (current != null && currentPlaying) {
            Transition(previous = current, current = next)
        } else {
            if (current != null) clearOwnedExpressions(current)
            Transition(previous = null, current = next)
        }
    }
}
