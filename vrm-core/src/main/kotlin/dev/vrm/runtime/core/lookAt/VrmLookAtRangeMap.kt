package dev.vrm.runtime.core.lookAt

/**
 * Maps a LookAt input angle to an output (angle for bone type, weight for
 * expression type), port of three-vrm's `VRMLookAtRangeMap`.
 *
 * `map(src) = outputScale * saturate(src / inputMaxValue)`.
 */
class VrmLookAtRangeMap(
    /** The maximum input angle (degrees) that maps to outputScale. */
    val inputMaxValue: Float,
    /** The output value reached when the input angle equals inputMaxValue. */
    val outputScale: Float,
) {
    /**
     * Evaluate an input value and return a mapped value, clamped so the result
     * never exceeds [outputScale] for inputs at or beyond [inputMaxValue].
     */
    fun map(src: Float): Float = outputScale * saturate(src / inputMaxValue)

    private fun saturate(v: Float): Float = v.coerceIn(0f, 1f)
}