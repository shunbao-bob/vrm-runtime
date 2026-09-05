package dev.vrm.runtime.character

import kotlin.random.Random

/**
 * A simple autonomous wandering AI. Picks a random target inside the walkable
 * bounds that is reachable without crossing an obstacle, then moves there;
 * after arrival it pauses briefly and picks a new target. This is the "makes
 * the VRM walk around on its own" brain for v1 — no NavMesh, no pathfinding.
 *
 * Pure Kotlin + [CollisionWorld]: fully JVM-testable.
 */
class WanderAI(
    private val world: CollisionWorld,
    private val random: Random = Random.Default,
) {

    /** Distance from the current position to search for a valid target. */
    var maxStep = 6f

    /** How close the wander target must be to the bounds edge to be "worth it". */
    var boundsMargin = 0.5f

    /** Time to stay idle after arriving at a wander target (seconds). */
    var idleAfterArrival = 1.2f

    /** Attempts before giving up this tick and re-trying next tick. */
    private var retries = 0
    private var idleTimer = 0f
    private var waiting = false

    /**
     * Advance the wander decision loop.
     *
     * @param x,z current world XZ
     * @param moving whether the engine is currently moving toward a target
     * @param dt frame delta in seconds
     * @return the target XZ to MoveTo, or null if it should keep waiting/moving
     */
    fun update(x: Float, z: Float, moving: Boolean, dt: Float): Pair<Float, Float>? {
        if (moving) return null // let the current MoveTo finish

        if (waiting) {
            idleTimer -= dt
            if (idleTimer > 0f) return null
            waiting = false
        }

        val target = pickTarget(x, z)
        if (target != null) {
            waiting = true
            idleTimer = idleAfterArrival
            return target
        }
        return null
    }

    /** Pick a random reachable target within bounds, or null if none found. */
    private fun pickTarget(x: Float, z: Float): Pair<Float, Float>? {
        val minX = world.bounds.minX + boundsMargin
        val maxX = world.bounds.maxX - boundsMargin
        val minZ = world.bounds.minZ + boundsMargin
        val maxZ = world.bounds.maxZ - boundsMargin
        if (maxX <= minX || maxZ <= minZ) return null

        for (attempt in 0 until 16) {
            val tx = random.nextFloat() * (maxX - minX) + minX
            val tz = random.nextFloat() * (maxZ - minZ) + minZ
            if (world.isPathClear(x, z, tx, tz)) {
                retries = 0
                return tx to tz
            }
        }
        // Couldn't find a clear target (surrounded?) — give up this tick.
        retries++
        return null
    }
}
