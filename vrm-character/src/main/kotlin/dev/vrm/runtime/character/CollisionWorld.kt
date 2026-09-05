package dev.vrm.runtime.character

import kotlin.math.abs

/**
 * The character layer's collision world — entirely independent of Filament.
 *
 * Filament only renders the scene's triangles; it has no notion of "can the
 * character walk here". This class answers exactly those questions using a
 * simplified footprint (a circle of [SceneConfig.characterRadius] on the XZ
 * plane) and axis-aligned box obstacles. The box list comes from
 * [SceneConfig]; it deliberately does NOT need to match the render mesh
 * pixel-perfectly — visual tolerance is fine for a walking NPC.
 *
 * All math is 2D on the XZ plane (Y = vertical, handled by groundY).
 * Pure Kotlin: fully JVM-testable, no Filament dependency.
 */
class CollisionWorld(
    val bounds: Bounds,
    val obstacles: List<Obstacle> = emptyList(),
    val characterRadius: Float = 0.3f,
) {

    // ------------------------------------------------------------------
    // Boundary queries
    // ------------------------------------------------------------------

    /** Whether the point (x, z) is inside the walkable bounds. */
    fun isInsideBounds(x: Float, z: Float): Boolean =
        x in bounds.minX..bounds.maxX && z in bounds.minZ..bounds.maxZ

    /** Clamp a point to the walkable bounds. */
    fun clampToBounds(x: Float, z: Float): Pair<Float, Float> =
        x.coerceIn(bounds.minX, bounds.maxX) to z.coerceIn(bounds.minZ, bounds.maxZ)

    // ------------------------------------------------------------------
    // Obstacle queries (circle footprint)
    // ------------------------------------------------------------------

    /** Whether a circle of [radius] at (x, z) overlaps any obstacle. */
    fun overlapsObstacle(x: Float, z: Float, radius: Float = characterRadius): Boolean =
        obstacles.any { circleIntersectsBox(x, z, radius, it) }

    /**
     * Whether the character at (x, z) is blocked: outside bounds or overlapping
     * an obstacle.
     */
    fun isBlocked(x: Float, z: Float, radius: Float = characterRadius): Boolean =
        !isInsideBounds(x, z) || overlapsObstacle(x, z, radius)

    /**
     * Raycast along a direction on the XZ plane. Returns the distance to the
     * first hit against an obstacle, or [maxDist] when clear (beyond the
     * bounds / obstacles). The ray starts at the character's footprint edge.
     *
     * @param ox,oz ray origin
     * @param dx,dz normalized direction (need not be unit — it is normalized here)
     * @param maxDist max distance to scan
     */
    fun raycastDistance(
        ox: Float, oz: Float,
        dx: Float, dz: Float,
        maxDist: Float,
    ): Float {
        val len = kotlin.math.sqrt(dx * dx + dz * dz)
        if (len <= 1e-6f) return maxDist
        val ux = dx / len
        val uz = dz / len

        // Step along the ray; for each sample test the circle footprint.
        val step = characterRadius * 0.5f
        var d = 0f
        while (d <= maxDist) {
            val px = ox + ux * d
            val pz = oz + uz * d
            if (!isInsideBounds(px, pz) || overlapsObstacle(px, pz)) {
                return d
            }
            d += step
        }
        return maxDist
    }

    /**
     * A swept-circle probe: would the character, moving from (ox,oz) toward
     * (tx,tz), stay clear along the whole segment? Used by the wander AI to
     * reject a target that requires walking through an obstacle.
     */
    fun isPathClear(
        ox: Float, oz: Float,
        tx: Float, tz: Float,
        radius: Float = characterRadius,
    ): Boolean {
        val dx = tx - ox
        val dz = tz - oz
        val len = kotlin.math.sqrt(dx * dx + dz * dz)
        if (len <= 1e-4f) return true
        val steps = (len / (radius * 0.5f)).toInt().coerceIn(1, 64)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val px = ox + dx * t
            val pz = oz + dz * t
            if (isBlocked(px, pz, radius)) return false
        }
        return true
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Circle (x,z,radius) vs AABB box overlap on the XZ plane. */
    private fun circleIntersectsBox(x: Float, z: Float, radius: Float, o: Obstacle): Boolean {
        if (o.type != "box") return false
        val cx = o.center.x
        val cz = o.center.z
        val hx = o.halfExtents.x
        val hz = o.halfExtents.z
        val closestX = x.coerceIn(cx - hx, cx + hx)
        val closestZ = z.coerceIn(cz - hz, cz + hz)
        val ddx = x - closestX
        val ddz = z - closestZ
        return ddx * ddx + ddz * ddz <= radius * radius
    }

    companion object {
        /** Build a CollisionWorld from a parsed [SceneConfig]. */
        fun from(config: SceneConfig): CollisionWorld =
            CollisionWorld(
                bounds = config.bounds,
                obstacles = config.obstacles,
                characterRadius = config.characterRadius,
            )
    }
}
