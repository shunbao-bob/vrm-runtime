package dev.vrm.runtime.core.mtoon

/**
 * Outline width modes for an MToon material. Port of `MToonMaterialOutlineWidthMode.ts`.
 */
enum class OutlineWidthMode(val jsonValue: String) {
    NONE("none"),
    WORLD_COORDINATES("worldCoordinates"),
    SCREEN_COORDINATES("screenCoordinates");

    companion object {
        fun fromJson(v: String?): OutlineWidthMode =
            entries.firstOrNull { it.jsonValue == v } ?: NONE
    }
}

/**
 * Debug visualization modes for an MToon material (three-vrm `MToonMaterialDebugMode`).
 * How each mode is visualized is a demo-layer concern; the core only carries the value.
 */
enum class MToonDebugMode(val jsonValue: String) {
    NONE("none"),
    NORMAL("normal"),
    LIT("lit"),
    SHADE("shade"),
    NORMALIZED_LIT("normalizedLit"),
    NORMALIZED_SHADE("normalizedShade"),
    UV("uv"),
    SHADING_SHIFT("shadingShift"),
    SHADING_TOONY("shadingToony"),
    GI("gi"),
    RIM("rim"),
    MATCAP("matcap"),
    OUTLINE("outline");

    companion object {
        fun fromJson(v: String?): MToonDebugMode =
            entries.firstOrNull { it.jsonValue == v } ?: NONE
    }
}