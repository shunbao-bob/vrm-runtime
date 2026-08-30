package dev.vrm.runtime.core.lookAt

import dev.vrm.runtime.core.expression.ExpressionLoader
import dev.vrm.runtime.core.expression.ExpressionManager
import dev.vrm.runtime.core.expression.ExpressionBindProvider
import dev.vrm.runtime.core.expression.InMemoryMorphTargetChannel
import dev.vrm.runtime.core.expression.MaterialColorAccess
import dev.vrm.runtime.core.expression.MorphTargetChannel
import dev.vrm.runtime.core.expression.TextureTransformAccess
import dev.vrm.runtime.core.gltf.Gltf
import dev.vrm.runtime.core.gltf.Node
import dev.vrm.runtime.core.humanoid.GltfNodeTransformStore
import dev.vrm.runtime.core.humanoid.MutableNodeTransformStore
import dev.vrm.runtime.core.humanoid.VRMHumanoid
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3
import dev.vrm.runtime.core.vrm.VRMCVrm
import dev.vrm.runtime.core.vrm.VrmHumanoid
import dev.vrm.runtime.core.vrm.VrmLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs

/**
 * M6 acceptance: LookAt range maps, angle utilities, expression & bone appliers,
 * and the loader wired from a real VRM (Seed-san, expression lookAt).
 */
class LookAtTest {

    // ---------- utilities ----------

    @Test
    fun `range map maps zero input to zero`() {
        val map = VrmLookAtRangeMap(inputMaxValue = 90f, outputScale = 1f)
        assertEquals(0f, map.map(0f), 1e-5f)
    }

    @Test
    fun `range map maps input max to output scale`() {
        val map = VrmLookAtRangeMap(inputMaxValue = 90f, outputScale = 10f)
        assertEquals(10f, map.map(90f), 1e-4f)
        // over max clamps to outputScale
        assertEquals(10f, map.map(180f), 1e-4f)
    }

    @Test
    fun `range map clamps negative input to zero`() {
        val map = VrmLookAtRangeMap(inputMaxValue = 90f, outputScale = 1f)
        assertEquals(0f, map.map(-45f), 1e-5f)
    }

    @Test
    fun `range map scales linearly inside the range`() {
        val map = VrmLookAtRangeMap(inputMaxValue = 90f, outputScale = 1f)
        assertEquals(0.5f, map.map(45f), 1e-5f)
    }

    @Test
    fun `calcAzimuthAltitude of plus X is zero zero`() {
        val (az, alt) = calcAzimuthAltitude(1f, 0f, 0f)
        assertEquals(0f, az, 1e-5f)
        assertEquals(0f, alt, 1e-5f)
    }

    @Test
    fun `calcAzimuthAltitude of plus Z is minus pi over 2`() {
        val (az, _) = calcAzimuthAltitude(0f, 0f, 1f)
        assertEquals(-PI / 2.0, az.toDouble(), 1e-5)
    }

    @Test
    fun `calcAzimuthAltitude of straight up is pi over 2 altitude`() {
        val (_, alt) = calcAzimuthAltitude(0f, 1f, 0f)
        assertEquals(PI / 2.0, alt.toDouble(), 1e-5)
    }

    @Test
    fun `sanitize angle wraps one and a half pi to minus half pi`() {
        val wrapped = sanitizeAngle((1.5f * PI.toFloat()))
        assertEquals(-0.5f * PI.toFloat(), wrapped, 1e-4f)
    }

    // ---------- expression applier (real Seed-san lookAt) ----------

    private fun loadSeed(): Triple<Gltf, VRMCVrm, ExpressionManager> {
        val url = javaClass.classLoader.getResource("fixtures/Seed-san.vrm")
        val file = File(url!!.toURI())
        val vrm = VrmLoader.load(file.readBytes())
        val bindProvider = NoopExpressionBindProvider()
        val manager = ExpressionLoader(bindProvider).load(vrm.gltf, vrm.vrm!!)
            ?: throw AssertionError("Seed-san must build an expression manager")
        return Triple(vrm.gltf, vrm.vrm!!, manager)
    }

    @Test
    fun `seed-san lookAt is expression type`() {
        val (_, vrm, _) = loadSeed()
        assertEquals("expression", vrm.lookAt?.type)
        assertNotNull(vrm.lookAt?.offsetFromHeadBone)
    }

    @Test
    fun `expression applier maps positive yaw and pitch to lookLeft and lookDown`() {
        val (_, _, manager) = loadSeed()
        val map = VrmLookAtRangeMap(inputMaxValue = 90f, outputScale = 1f)
        val applier = VrmLookAtExpressionApplier(manager, map, map, map, map)

        // positive pitch -> lookDown, positive yaw -> lookLeft (three-vrm semantics)
        applier.applyYawPitch(yaw = 45f, pitch = 45f)
        assertEquals(0.5f, w(manager, "lookDown"), 1e-4f)
        assertEquals(0f, w(manager, "lookUp"), 1e-5f)
        assertEquals(0.5f, w(manager, "lookLeft"), 1e-4f)
        assertEquals(0f, w(manager, "lookRight"), 1e-5f)
    }

    @Test
    fun `expression applier maps negative yaw and pitch to lookRight and lookUp`() {
        val (_, _, manager) = loadSeed()
        val map = VrmLookAtRangeMap(inputMaxValue = 90f, outputScale = 1f)
        val applier = VrmLookAtExpressionApplier(manager, map, map, map, map)

        // negative pitch -> lookUp, negative yaw -> lookRight (three-vrm semantics)
        applier.applyYawPitch(yaw = -45f, pitch = -45f)
        assertEquals(0.5f, w(manager, "lookUp"), 1e-4f)
        assertEquals(0f, w(manager, "lookDown"), 1e-5f)
        assertEquals(0.5f, w(manager, "lookRight"), 1e-4f)
        assertEquals(0f, w(manager, "lookLeft"), 1e-5f)
    }

    private fun w(manager: ExpressionManager, name: String): Float =
        manager.getValue(name) ?: throw AssertionError("expression $name missing")

    // ---------- loader ----------

    @Test
    fun `loader builds expression lookAt from seed-san`() {
        val (gltf, vrm, manager) = loadSeed()
        val humanoid = VRMHumanoid.fromVrm(gltf, vrm)
        val lookAt = VrmLookAtLoader(humanoid, manager, humanoid.rawStore).load(vrm)

        assertNotNull(lookAt)
        assertTrue(lookAt!!.applier is VrmLookAtExpressionApplier)
        // offsetFromHeadBone [0, 0.0776, 0.1007] parsed
        assertEquals(0f, lookAt.offsetFromHeadBone.x, 1e-5f)
        assertTrue(abs(lookAt.offsetFromHeadBone.y - 0.0776f) < 1e-4f)
        assertTrue(abs(lookAt.offsetFromHeadBone.z - 0.1007f) < 1e-4f)
    }

    @Test
    fun `loader returns null when lookAt absent`() {
        val (gltf, vrm, manager) = loadSeed()
        val noLookAt = VRMCVrm(
            specVersion = vrm.specVersion,
            meta = vrm.meta,
            humanoid = vrm.humanoid,
            firstPerson = vrm.firstPerson,
            lookAt = null,
            expressions = vrm.expressions,
        )
        val humanoid = VRMHumanoid.fromVrm(gltf, noLookAt)
        val lookAt = VrmLookAtLoader(humanoid, manager, humanoid.rawStore).load(noLookAt)
        assertNull(lookAt)
    }

    // ---------- VrmLookAt behaviour ----------

    @Test
    fun `lookAt update applies yaw and pitch via applier`() {
        val (gltf, vrm, manager) = loadSeed()
        val humanoid = VRMHumanoid.fromVrm(gltf, vrm)
        val captured = ArrayList<Pair<Float, Float>>()
        val applier = object : VrmLookAtApplier {
            override fun applyYawPitch(yaw: Float, pitch: Float) {
                captured.add(yaw to pitch)
            }
        }
        val lookAt = VrmLookAt(humanoid, applier)
        lookAt.update(0.016f)
        assertTrue(captured.isNotEmpty(), "update should apply initial yaw/pitch")

        // set an explicit target in front of the head -> yaw/pitch near zero
        captured.clear()
        lookAt.target = lookAt.getLookAtWorldPosition(Vec3()).let { p -> p.copy().add(Vec3(0f, 0f, 1f)) }
        lookAt.update(0.016f)
        assertTrue(captured.isNotEmpty())
        val (yaw, pitch) = captured.last()
        assertTrue(abs(yaw) < 1f, "yaw should be near zero, was $yaw")
        assertTrue(abs(pitch) < 1f, "pitch should be near zero, was $pitch")
    }

    @Test
    fun `lookAt to a point on the side rotates yaw`() {
        val (gltf, vrm, manager) = loadSeed()
        val humanoid = VRMHumanoid.fromVrm(gltf, vrm)
        val captured = ArrayList<Pair<Float, Float>>()
        val applier = object : VrmLookAtApplier {
            override fun applyYawPitch(yaw: Float, pitch: Float) {
                captured.add(yaw to pitch)
            }
        }
        val lookAt = VrmLookAt(humanoid, applier)
        val head = lookAt.getLookAtWorldPosition(Vec3())
        // target at +X (the avatar's right, in front of the +Z face) -> yaw != 0
        lookAt.target = head.copy().add(Vec3(1f, 0f, 0f))
        lookAt.update(0.016f)
        val (yaw, pitch) = captured.last()
        assertTrue(abs(yaw) > 1f, "target on the side should rotate yaw, was $yaw")
        assertTrue(abs(pitch) < 1f, "same-height target keeps pitch near zero, was $pitch")
    }

    // ---------- bone applier (synthetic humanoid with eye bones) ----------

    private fun buildEyeHumanoid(): VRMHumanoid {
        // nodes: 0=hips(root), 1=spine, 2=head, 3=leftEye, 4=rightEye
        // plus the 12 required limb bones (5..16) as identity children of hips
        val nodes = listOf(
            Node(name = "hips", translation = listOf(0f, 1f, 0f), children = listOf(1, 5, 8, 11, 14)),
            Node(name = "spine", translation = listOf(0f, 0.2f, 0f), children = listOf(2)),
            Node(name = "head", translation = listOf(0f, 0.1f, 0f), children = listOf(3, 4)),
            Node(name = "leftEye", translation = listOf(-0.05f, 0.02f, 0f)),
            Node(name = "rightEye", translation = listOf(0.05f, 0.02f, 0f)),
            Node(name = "leftUpperLeg"),  // 5
            Node(name = "leftLowerLeg"),  // 6
            Node(name = "leftFoot"),      // 7
            Node(name = "rightUpperLeg"), // 8
            Node(name = "rightLowerLeg"), // 9
            Node(name = "rightFoot"),     // 10
            Node(name = "leftUpperArm"),  // 11
            Node(name = "leftLowerArm"),  // 12
            Node(name = "leftHand"),      // 13
            Node(name = "rightUpperArm"), // 14
            Node(name = "rightLowerArm"), // 15
            Node(name = "rightHand"),     // 16
        )
        val gltf = Gltf(
            asset = dev.vrm.runtime.core.gltf.Asset(version = "2.0"),
            scene = 0,
            scenes = listOf(dev.vrm.runtime.core.gltf.Scene(nodes = listOf(0))),
            nodes = nodes,
        )
        val schemaHumanoid = VrmHumanoid(
            humanBones = mapOf(
                "hips" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 0),
                "spine" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 1),
                "head" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 2),
                "leftEye" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 3),
                "rightEye" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 4),
                "leftUpperLeg" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 5),
                "leftLowerLeg" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 6),
                "leftFoot" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 7),
                "rightUpperLeg" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 8),
                "rightLowerLeg" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 9),
                "rightFoot" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 10),
                "leftUpperArm" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 11),
                "leftLowerArm" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 12),
                "leftHand" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 13),
                "rightUpperArm" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 14),
                "rightLowerArm" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 15),
                "rightHand" to dev.vrm.runtime.core.vrm.VrmHumanBone(node = 16),
            ),
        )
        val store = GltfNodeTransformStore.fromGltfNodes(nodes)
        return VRMHumanoid.fromVrm(gltf, VRMCVrm(specVersion = "1.0", meta = dev.vrm.runtime.core.vrm.VrmMeta(name = "t"), humanoid = schemaHumanoid), store)
    }

    @Test
    fun `bone applier rotates raw eye nodes`() {
        val humanoid = buildEyeHumanoid()
        val store = humanoid.rawStore as GltfNodeTransformStore
        val map = VrmLookAtRangeMap(inputMaxValue = 90f, outputScale = 10f)
        val applier = VrmLookAtBoneApplier(humanoid, store, map, map, map, map)

        val leftIdx = humanoid.getRawBoneNodeIndex("leftEye")!!
        val rightIdx = humanoid.getRawBoneNodeIndex("rightEye")!!
        val beforeLeft = store.getLocalRotation(leftIdx).copy()
        val beforeRight = store.getLocalRotation(rightIdx).copy()

        applier.applyYawPitch(yaw = 30f, pitch = 0f)

        val afterLeft = store.getLocalRotation(leftIdx)
        val afterRight = store.getLocalRotation(rightIdx)
        assertTrue(!afterLeft.copy().equals(beforeLeft), "left eye should rotate")
        assertTrue(!afterRight.copy().equals(beforeRight), "right eye should rotate")
    }

    @Test
    fun `bone applier leaves eye rotation at identity for zero angles`() {
        val humanoid = buildEyeHumanoid()
        val store = humanoid.rawStore as GltfNodeTransformStore
        val map = VrmLookAtRangeMap(inputMaxValue = 90f, outputScale = 10f)
        val applier = VrmLookAtBoneApplier(humanoid, store, map, map, map, map)

        val leftIdx = humanoid.getRawBoneNodeIndex("leftEye")!!
        applier.applyYawPitch(yaw = 0f, pitch = 0f)
        val after = store.getLocalRotation(leftIdx)
        // zero angles -> identity rotation
        assertEquals(0f, after.x, 1e-5f)
        assertEquals(0f, after.y, 1e-5f)
        assertEquals(0f, after.z, 1e-5f)
        assertEquals(1f, after.w, 1e-5f)
    }

    @Test
    fun `quatToLookAtDegrees maps identity to zero and y-rotation to yaw`() {
        // identity quaternion (facing +Z) -> yaw 0, pitch 0
        val (y0, p0) = quatToLookAtDegrees(Quat(0f, 0f, 0f, 1f))
        assertTrue(abs(y0) < 0.01f, "identity yaw should be 0, got $y0")
        assertTrue(abs(p0) < 0.01f, "identity pitch should be 0, got $p0")

        // 90° around Y (0,0.707,0,0.707): +Z rotates to +X, should yield horizontal yaw ≈ ±90°, pitch 0
        // (this is one of test.vrma's lookAt keyframe values)
        val y90 = 0.70710677f
        val (y1, p1) = quatToLookAtDegrees(Quat(0f, y90, 0f, y90))
        assertTrue(abs(abs(y1) - 90f) < 1f, "y-90 should give |yaw|~90, got $y1")
        assertTrue(abs(p1) < 1f, "y-90 pitch should be ~0, got $p1")

        // 45° around X (+Z raised by 45°): should yield pitch ≈ 45°, yaw 0 (heading still forward)
        val x45 = kotlin.math.sin(Math.toRadians(22.5)).toFloat()  // sin(45/2)
        val xw45 = kotlin.math.cos(Math.toRadians(22.5)).toFloat() // cos(45/2)
        val (y2, p2) = quatToLookAtDegrees(Quat(x45, 0f, 0f, xw45))
        assertTrue(abs(y2) < 1f, "x-45 yaw should be ~0, got $y2")
        assertTrue(abs(abs(p2) - 45f) < 1f, "x-45 pitch should be ~45, got $p2")
    }
}

/** Expression bind provider that does nothing (headless tests don't render). */
private class NoopExpressionBindProvider : ExpressionBindProvider {
    override fun morphTargetChannel(nodeIndex: Int, morphIndex: Int): MorphTargetChannel? {
        return InMemoryMorphTargetChannel(FloatArray(64))
    }

    override fun materialColorAccess(materialIndex: Int): MaterialColorAccess? = null
    override fun textureTransformAccess(materialIndex: Int): TextureTransformAccess? = null
}