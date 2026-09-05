package dev.vrm.runtime.character

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Declarative scene definition loaded from a JSON config file — the single
 * "source of truth" for the character layer's playable area. Independent of
 * the render mesh / HDR skybox: Filament only renders triangles; this file
 * says where the character may walk and which obstacles block it.
 *
 * Example:
 * ```json
 * {
 *   "name": "studio",
 *   "groundY": 0.0,
 *   "bounds": { "minX": -6.0, "maxX": 6.0, "minZ": -6.0, "maxZ": 6.0 },
 *   "obstacles": [
 *     { "id": "table1", "type": "box",
 *       "center": [1.5, 0.0, -2.0], "halfExtents": [0.5, 1.0, 0.5] }
 *   ],
 *   "anchors": [
 *     { "id": "door", "x": 3.0, "y": 0.0, "z": 4.0 }
 *   ]
 * }
 * ```
 */
@Serializable
data class SceneConfig(
    val name: String = "default",
    /** World Y of the walkable ground plane (feet align here). */
    val groundY: Float = 0f,
    /** Rectangular playable boundary in XZ; the character turns back on it. */
    val bounds: Bounds = Bounds(),
    /** Static obstacles the character must not enter. */
    val obstacles: List<Obstacle> = emptyList(),
    /** Character radius used for collision (capsule footprint). */
    val characterRadius: Float = 0.3f,
    /** Named destinations for MoveToAnchor. */
    val anchors: List<SceneAnchor> = emptyList(),
) {
    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** Parse a SceneConfig from JSON text. */
        fun fromJson(text: String): SceneConfig = JSON.decodeFromString<SceneConfig>(text)

        /** Load from an asset/file (host resolves the bytes). */
        fun parse(bytes: ByteArray): SceneConfig {
            val text = String(bytes, Charsets.UTF_8)
            return fromJson(text)
        }
    }
}

/** Rectangular XZ playable boundary. */
@Serializable
data class Bounds(
    val minX: Float = -10f,
    val maxX: Float = 10f,
    val minZ: Float = -10f,
    val maxZ: Float = 10f,
)

/** An obstacle. First version supports axis-aligned boxes only. */
@Serializable
data class Obstacle(
    val id: String = "",
    val type: String = "box",
    /** Box center (world). */
    val center: FloatArray3 = FloatArray3(),
    /** Half extents (half width / half height / half depth). */
    val halfExtents: FloatArray3 = FloatArray3(),
)

/** Convenience holder matching the JSON's [x,y,z] float arrays. */
@Serializable
data class FloatArray3(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
) {
    fun array(): FloatArray = floatArrayOf(x, y, z)
}

/** A named world-space destination for MoveToAnchor. */
@Serializable
data class SceneAnchor(
    val id: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val arrivalRadius: Float = 0.15f,
    @SerialName("yaw") val yawDegrees: Float = 0f,
)