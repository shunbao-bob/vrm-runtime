package dev.vrm.runtime.core.motion

import dev.vrm.runtime.core.humanoid.VRMHumanoid
import dev.vrm.runtime.core.vrm.VrmLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class MotionSpecClipBuilderTest {

    private fun humanoid(): VRMHumanoid {
        val url = javaClass.classLoader.getResource("fixtures/Seed-san.vrm")
        val file = File(url!!.toURI())
        val vrm = VrmLoader.load(file.readBytes())
        return VRMHumanoid.fromVrm(vrm.gltf, vrm.vrm!!)
    }

    @Test
    fun `build turns bone tracks into quaternion keyframes`() {
        val h = humanoid()
        val spec = MotionSpec(
            name = "bow", duration = 2f,
            tracks = mapOf(
                "spine" to listOf(RotKey(0f, listOf(0f, 0f, 0f)), RotKey(1f, listOf(20f, 0f, 0f)), RotKey(2f, listOf(0f, 0f, 0f))),
            ),
        )
        val clip = MotionSpecClipBuilder(h).build(spec)
        val spineTrack = clip.tracks.firstOrNull { it.name == "spine.quaternion" }
        assertTrue(spineTrack != null, "expected spine.quaternion track, got ${clip.tracks.map { it.name }}")
        assertEquals(3, spineTrack!!.keyCount)
        assertEquals(4, spineTrack.valueStride) // quaternion
        // identity rotation at t=0 → (0,0,0,1)
        assertEquals(0f, spineTrack.values[0], 0.001f)
        assertEquals(0f, spineTrack.values[1], 0.001f)
        assertEquals(0f, spineTrack.values[2], 0.001f)
        assertEquals(1f, spineTrack.values[3], 0.001f)
    }

    @Test
    fun `hips translation becomes hips position track`() {
        val h = humanoid()
        val rest = h.getNormalizedRestPosition("hips")!!
        h.getNormalizedBoneNode("hips")!!.position.y += 9f
        val spec = MotionSpec(
            name = "jump", duration = 1f,
            tracks = emptyMap(),
            hips = listOf(PosKey(0f, listOf(0f, 0f, 0f)), PosKey(0.5f, listOf(0f, 0.2f, 0f)), PosKey(1f, listOf(0f, 0f, 0f))),
        )
        val clip = MotionSpecClipBuilder(h).build(spec)
        val hipsTrack = clip.tracks.firstOrNull { it.name == "hips.position" }
        assertTrue(hipsTrack != null, "expected hips.position track")
        assertEquals(3, hipsTrack!!.valueStride)
        assertEquals(rest.x, hipsTrack.values[0], 0.001f)
        assertEquals(rest.y, hipsTrack.values[1], 0.001f)
        assertEquals(rest.z, hipsTrack.values[2], 0.001f)
        assertEquals(rest.y + 0.2f, hipsTrack.values[3 + 1], 0.001f) // second key y
    }

    @Test
    fun `reset normalized pose restores animated hips position`() {
        val h = humanoid()
        val rest = h.getNormalizedRestPosition("hips")!!
        h.getNormalizedBoneNode("hips")!!.position.y += 3f

        h.resetNormalizedPose()

        assertEquals(rest.y, h.getNormalizedBoneNode("hips")!!.position.y, 0.001f)
    }

    @Test
    fun `unknown bone on avatar is dropped`() {
        val h = humanoid()
        val spec = MotionSpec(
            name = "x", duration = 1f,
            tracks = mapOf(
                "head" to listOf(RotKey(0f, listOf(0f,0f,0f)), RotKey(1f, listOf(0f,0f,0f))),
                "thisBoneDoesNotExistOnAvatar" to listOf(RotKey(0f, listOf(0f,0f,0f)), RotKey(1f, listOf(0f,0f,0f))),
            ),
        )
        val clip = MotionSpecClipBuilder(h).build(spec)
        assertTrue(clip.tracks.none { it.name.contains("thisBoneDoesNotExist") })
        assertTrue(clip.tracks.any { it.name == "head.quaternion" })
    }

    @Test
    fun `build auto-generates shoulder lift when raised upper arm has no shoulder track`() {
        val h = humanoid()
        val spec = MotionSpec(
            duration = 1f,
            tracks = mapOf(
                "leftUpperArm" to listOf(
                    RotKey(0f, listOf(0f, 0f, 50f)),
                    RotKey(1f, listOf(0f, 0f, 75f)),
                ),
            ),
        )

        val clip = MotionSpecClipBuilder(h).build(spec)
        val shoulder = clip.tracks.firstOrNull { it.name == "leftShoulder.quaternion" }

        assertTrue(shoulder != null, "raised arm should generate a shoulder compensation track")
        assertEquals(2, shoulder!!.keyCount)
        val expected = Math.sin(Math.toRadians(4.0)).toFloat() // 8 degrees around Z
        assertEquals(expected, shoulder.values[6], 0.001f)
    }

    @Test
    fun `build adds relaxed finger curl when finger tracks are absent`() {
        val clip = MotionSpecClipBuilder(humanoid()).build(
            MotionSpec(
                duration = 1f,
                tracks = mapOf("spine" to listOf(RotKey(0f, listOf(0f, 0f, 0f)))),
            ),
        )

        val leftIndex = clip.tracks.firstOrNull { it.name == "leftIndexProximal.quaternion" }
        val rightIndex = clip.tracks.firstOrNull { it.name == "rightIndexProximal.quaternion" }
        assertTrue(leftIndex != null)
        assertTrue(rightIndex != null)
        assertEquals(-Math.sin(Math.toRadians(7.0)).toFloat(), leftIndex!!.values[2], 0.001f)
        assertEquals(Math.sin(Math.toRadians(7.0)).toFloat(), rightIndex!!.values[2], 0.001f)
    }

    @Test
    fun `duration carried through`() {
        val h = humanoid()
        val spec = MotionSpec(name = "x", duration = 2.5f, tracks = mapOf("spine" to listOf(RotKey(0f, listOf(0f,0f,0f)))))
        val clip = MotionSpecClipBuilder(h).build(spec)
        assertEquals(2.5f, clip.duration, 0.001f)
    }
}