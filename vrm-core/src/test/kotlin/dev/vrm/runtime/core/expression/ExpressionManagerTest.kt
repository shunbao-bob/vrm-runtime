package dev.vrm.runtime.core.expression

import dev.vrm.runtime.core.vrm.VrmLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M2 acceptance: expressions. Loads the official Seed-san VRM, builds an
 * [ExpressionManager] via [ExpressionLoader], and asserts:
 *  - preset / custom classification
 *  - weight clamping and reset
 *  - per-frame [ExpressionManager.update] drives morph weights, honoring the
 *    blink / lookAt / mouth group override multipliers.
 *
 * Morph-weight verification uses a recording [ExpressionBindProvider] that
 * keeps the channel for each node so tests can read the exact blended weight.
 */
class ExpressionManagerTest {

    private fun loadBytes(): ByteArray {
        val url = javaClass.classLoader.getResource("fixtures/Seed-san.vrm")
        assertNotNull(url, "fixture Seed-san.vrm not found on test classpath")
        return File(url!!.toURI()).readBytes()
    }

    /** a recording [ExpressionBindProvider] over a node->channel map. */
    private inner class RecordingProvider : ExpressionBindProvider {
        private val channels = HashMap<Int, InMemoryMorphTargetChannel>()

        fun morphWeight(node: Int, index: Int): Float =
            channels[node]?.getMorphWeight(index) ?: 0f

        override fun morphTargetChannel(nodeIndex: Int, morphIndex: Int): MorphTargetChannel? {
            return channels.getOrPut(nodeIndex) { InMemoryMorphTargetChannel(FloatArray(64)) }
        }

        override fun materialColorAccess(materialIndex: Int): MaterialColorAccess? = null
        override fun textureTransformAccess(materialIndex: Int): TextureTransformAccess? = null
    }

    private class Fixture(val manager: ExpressionManager, val channels: RecordingProvider)

    /** Build the manager + a fresh recording provider over seed-san. */
    private fun build(): Fixture {
        val vrm = VrmLoader.load(loadBytes())
        val vrmc = vrm.vrm ?: throw AssertionError("Seed-san must carry VRMC_vrm")
        val provider = RecordingProvider()
        val manager = ExpressionLoader(provider).load(vrm.gltf, vrmc)
            ?: throw AssertionError("seed-san must build an expression manager")
        return Fixture(manager, provider)
    }

    // ---- schema / registration ----

    @Test
    fun `builds manager with all 18 presets and no customs`() {
        val fx = build()
        assertEquals(18, fx.manager.expressions.size)
        assertEquals(18, fx.manager.presetExpressionMap.size)
        assertTrue(fx.manager.customExpressionMap.isEmpty(), "seed-san has no custom expressions")
        assertEquals(18, fx.manager.expressionMap.size)
    }

    @Test
    fun `registers expression under its name`() {
        val fx = build()
        assertNotNull(fx.manager.getExpression("happy"), "happy should be registered")
        val happy = fx.manager.getExpression("happy")!!
        assertEquals("happy", happy.name)
        assertTrue(happy.binds.isNotEmpty(), "happy must have morph binds (1 in seed-san)")
    }

    @Test
    fun `duplicate registration only overwrites the map entry`() {
        val fx = build()
        val dup = VrmExpression("happy")
        fx.manager.registerExpression(dup)
        assertEquals(19, fx.manager.expressions.size, "list keeps both entries")
        assertTrue(fx.manager.getExpression("happy") === dup, "map points at the latest")
    }

    @Test
    fun `unregister removes expression from both list and map`() {
        val fx = build()
        val happy = fx.manager.getExpression("happy")!!
        fx.manager.unregisterExpression(happy)
        assertNull(fx.manager.getExpression("happy"))
        assertEquals(17, fx.manager.expressions.size)
    }

    @Test
    fun `getExpressionTrackName returns name dot weight`() {
        val fx = build()
        assertEquals("happy.weight", fx.manager.getExpressionTrackName("happy"))
        assertNull(fx.manager.getExpressionTrackName("missing"))
    }

    // ---- weights ----

    @Test
    fun `setValue clamps between zero and one and getValue reads back`() {
        val fx = build()
        fx.manager.setValue("aa", 2f); assertEquals(1f, fx.manager.getValue("aa"))
        fx.manager.setValue("aa", -0.5f); assertEquals(0f, fx.manager.getValue("aa"))
        fx.manager.setValue("aa", 0.6f); assertEquals(0.6f, fx.manager.getValue("aa"))
    }

    @Test
    fun `setValue on unknown name is a no-op`() {
        val fx = build()
        fx.manager.setValue("nope", 0.5f)
        assertNull(fx.manager.getValue("nope"))
    }

    @Test
    fun `resetValues zeroes every weight`() {
        val fx = build()
        fx.manager.setValue("aa", 0.8f)
        fx.manager.setValue("happy", 0.7f)
        fx.manager.resetValues()
        assertTrue(fx.manager.expressions.all { it.weight == 0f })
    }

    // ---- update / morph application ----

    /** seed-san: aa -> node2 target #25 (bind weight 1). */
    @Test
    fun `update applies morph bind weight with no overrides`() {
        val fx = build()
        fx.manager.setValue("aa", 1f)
        fx.manager.update()
        assertEquals(1f, fx.channels.morphWeight(2, 25), 1e-5f)
    }

    @Test
    fun `partial weight scales morph bind`() {
        val fx = build()
        fx.manager.setValue("aa", 0.25f)
        fx.manager.update()
        assertEquals(0.25f, fx.channels.morphWeight(2, 25), 1e-5f)
    }

    // ---- override groups ----

    @Test
    fun `no override leaves blink at full weight`() {
        val fx = build()
        fx.manager.setValue("blink", 1f)
        fx.manager.update()
        // blink -> node2 target #1, bind weight 1, no suppressor -> 1
        assertEquals(1f, fx.channels.morphWeight(2, 1), 1e-5f)
    }

    @Test
    fun `happy (block) suppresses blink group`() {
        val fx = build()
        // happy: isBinary=true, overrideBlink=block -> active it blocks blink
        fx.manager.setValue("blink", 1f)
        fx.manager.setValue("happy", 0.9f) // isBinary -> outputWeight 1.0
        fx.manager.update()
        assertEquals(0f, fx.channels.morphWeight(2, 1), 1e-5f)
    }

    @Test
    fun `relaxed suppresses both blink and lookAt groups`() {
        val fx = build()
        // relaxed: isBinary=true, overrideBlink=block, overrideLookAt=block
        fx.manager.setValue("blink", 1f)
        fx.manager.setValue("lookUp", 1f)
        fx.manager.setValue("relaxed", 0.9f) // isBinary -> outputWeight 1.0 (needs > 0.5)
        fx.manager.update()

        assertEquals(0f, fx.channels.morphWeight(2, 1), 1e-5f) // blink target #0
        assertEquals(0f, fx.channels.morphWeight(2, 39), 1e-5f) // lookUp target #39
    }

    @Test
    fun `blend override multiplies group weight`() {
        val fx = build()
        val suppressor = VrmExpression("mySuppressor")
        suppressor.overrideBlink = ExpressionOverrideType.BLEND
        suppressor.weight = 0.5f
        fx.manager.registerExpression(suppressor)

        fx.manager.setValue("blink", 1f)
        fx.manager.update()
        // blink multiplier = 1 - 0.5 = 0.5 -> bind weight 1 * 0.5 = 0.5
        assertEquals(0.5f, fx.channels.morphWeight(2, 1), 1e-5f)
    }

    @Test
    fun `isBinary output weight thresholds`() {
        val fx = build()
        // happy isBinary -> outputWeight = 1 when weight > 0.5, else 0
        fx.manager.setValue("happy", 0.4f)
        assertEquals(0f, fx.manager.getExpression("happy")!!.outputWeight)
        fx.manager.setValue("happy", 0.9f)
        assertEquals(1f, fx.manager.getExpression("happy")!!.outputWeight)
    }

    @Test
    fun `clone copies expressions and config`() {
        val fx = build()
        fx.manager.setValue("aa", 0.7f)
        val cloned = fx.manager.clone()
        assertEquals(0.7f, cloned.getValue("aa"))
        assertEquals(18, cloned.expressions.size)
        // three-vrm semantics: the clone shares expression instances, so
        // setting a weight through the clone is also visible on the original.
        cloned.setValue("aa", 0.2f)
        assertEquals(0.2f, fx.manager.getValue("aa"), "expressions are shared between clone and original")
    }
}