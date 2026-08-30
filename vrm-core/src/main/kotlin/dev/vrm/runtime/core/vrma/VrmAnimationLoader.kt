package dev.vrm.runtime.core.vrma

import dev.vrm.runtime.core.gltf.Animation
import dev.vrm.runtime.core.gltf.AnimationChannel
import dev.vrm.runtime.core.gltf.Gltf
import dev.vrm.runtime.core.humanoid.GltfNodeTransformStore
import dev.vrm.runtime.core.humanoid.HumanBoneName
import dev.vrm.runtime.core.humanoid.HUMAN_BONE_PARENT_MAP
import dev.vrm.runtime.core.math.Mat4
import dev.vrm.runtime.core.math.Quat
import dev.vrm.runtime.core.math.Vec3
import java.nio.ByteBuffer

/**
 * Loads one or more [VrmAnimation]s from a `.vrma` file (a GLB whose glTF JSON
 * carries the `VRMC_vrm_animation` extension and glTF animations).
 *
 * Port of three-vrm's `VRMAnimationLoaderPlugin` (VRMAnimationLoaderPlugin.ts),
 * synchronous and engine-agnostic. For each glTF `animation` it:
 *  - maps each channel's glTF node back to a semantic (humanoid bone /
 *    expression / lookAt) via the extension's `humanoid` / `expressions` /
 *    `lookAt` sections;
 *  - bakes the source keyframes into local frames relative to the nearest
 *    animated ancestor (rotation) or parent space (hips translation), so the
 *    resulting tracks are directly applicable to a normalized humanoid.
 *
 * The world-matrix query uses an in-memory [GltfNodeTransformStore] built from
 * the VRMA's own node hierarchy, matching three-vrm's `updateWorldMatrix`.
 *
 * @param gltf parsed glTF of the VRMA
 * @param bin the GLB BIN chunk, or null for JSON-only glTF
 * @param extension the parsed VRMC_vrm_animation extension
 */
class VrmAnimationLoader(
    private val gltf: Gltf,
    private val bin: ByteBuffer?,
    private val extension: VrmcVrmAnimation,
) {

    private val nodeStore: GltfNodeTransformStore = GltfNodeTransformStore.fromGltfNodes(gltf.nodes)

    // node index -> semantic name (built from the extension)
    private val humanoidIndexToName = HashMap<Int, String>()
    private val expressionsIndexToName = HashMap<Int, String>()
    private val lookAtIndex: Int? = extension.lookAt?.node

    // bone name -> world matrix; special key "hipsParent" -> hips' parent world
    private val worldMatrixMap = HashMap<String, Mat4>()

    init {
        buildNodeMaps()
        buildWorldMatrixMap()
    }

    private fun buildNodeMaps() {
        extension.humanoid?.humanBones?.forEach { (name, bone) ->
            humanoidIndexToName[bone.node] = name
        }
        extension.expressions?.preset?.forEach { (name, expression) ->
            expressionsIndexToName[expression.node] = name
        }
        extension.expressions?.custom?.forEach { (name, expression) ->
            expressionsIndexToName[expression.node] = name
        }
    }

    private fun buildWorldMatrixMap() {
        val humanBones = extension.humanoid?.humanBones ?: return
        for ((name, bone) in humanBones) {
            worldMatrixMap[name] = nodeStore.getWorldMatrix(bone.node)
            if (name == HumanBoneName.HIPS) {
                val parent = nodeStore.parentNodeIndex(bone.node)
                worldMatrixMap[HIPS_PARENT] =
                    if (parent >= 0) nodeStore.getWorldMatrix(parent) else Mat4.IDENTITY
            }
        }
    }

    /**
     * Load every glTF animation as a [VrmAnimation].
     */
    fun loadAll(): List<VrmAnimation> {
        val animations = gltf.animations ?: emptyList()
        return animations.map { parseAnimation(it) }
    }

    private fun parseAnimation(animation: Animation): VrmAnimation {
        val result = VrmAnimation()
        val channels = animation.channels ?: emptyList()

        result.duration = computeDuration(animation)

        // hips rest position (world)
        val hipsIdx = extension.humanoid?.humanBones?.get(HumanBoneName.HIPS)?.node
        result.restHipsPosition = if (hipsIdx != null && nodeStore.hasNode(hipsIdx)) {
            nodeStore.getWorldMatrix(hipsIdx).transformPoint(Vec3())
        } else {
            Vec3()
        }

        for (channel in channels) {
            val node = channel.target.node ?: continue
            val path = channel.target.path

            val boneName = humanoidIndexToName[node]
            if (boneName != null) {
                parseHumanoidChannel(animation, channel, boneName, path, result)
                continue
            }

            if (lookAtIndex != null && node == lookAtIndex) {
                parseLookAtChannel(animation, channel, path, result)
                continue
            }

            val expressionName = expressionsIndexToName[node]
            if (expressionName != null) {
                parseExpressionChannel(animation, channel, expressionName, path, result)
            }
        }

        return result
    }

    /** Max input time over all animation samplers. */
    private fun computeDuration(animation: Animation): Float {
        var duration = 0f
        val samplers = animation.samplers ?: emptyList()
        for (sampler in samplers) {
            val times = try {
                AccessorReader.read(gltf, bin, sampler.input)
            } catch (e: VrmaAccessorException) {
                continue
            }
            if (times.isNotEmpty()) {
                val max = times.max()
                if (max > duration) duration = max
            }
        }
        return duration
    }

    private fun parseHumanoidChannel(
        animation: Animation,
        channel: AnimationChannel,
        boneName: String,
        path: String,
        result: VrmAnimation,
    ) {
        val sampler = animation.samplers?.getOrNull(channel.sampler) ?: return
        val times = readTimes(sampler.input) ?: return

        when (path) {
            "translation" -> {
                // spec: only hips may translate
                if (boneName != HumanBoneName.HIPS) return
                val values = readValues(sampler.output) ?: return
                val hipsParentWorld = worldMatrixMap[HIPS_PARENT] ?: Mat4.IDENTITY

                val baked = FloatArray(values.size)
                for (i in values.indices step 3) {
                    val v = Vec3(values[i], values[i + 1], values[i + 2]).applyMatrix4(hipsParentWorld)
                    baked[i] = v.x; baked[i + 1] = v.y; baked[i + 2] = v.z
                }
                result.humanoidTracks.translation[boneName] =
                    KeyframeTrack("$boneName.translation", times.copyOf(), baked)
            }
            "rotation" -> {
                val values = readValues(sampler.output) ?: return
                val world = worldMatrixMap[boneName] ?: return
                val parentBoneName = findParentBoneName(boneName)
                val parentWorld = worldMatrixMap[parentBoneName] ?: Mat4.IDENTITY

                // q' = parentQ * q * worldQ_inv
                val worldQInv = Quat()
                world.decompose(Vec3(), worldQInv, Vec3())
                worldQInv.invert()

                val parentQ = Quat()
                parentWorld.decompose(Vec3(), parentQ, Vec3())

                val baked = FloatArray(values.size)
                for (i in values.indices step 4) {
                    val q = Quat(values[i], values[i + 1], values[i + 2], values[i + 3]).normalized()
                    val qPrime = q.copy().premultiply(parentQ).multiply(worldQInv)
                    baked[i] = qPrime.x; baked[i + 1] = qPrime.y
                    baked[i + 2] = qPrime.z; baked[i + 3] = qPrime.w
                }
                result.humanoidTracks.rotation[boneName] =
                    KeyframeTrack("$boneName.rotation", times.copyOf(), baked)
            }
            // other paths (e.g. scale) are ignored per the VRMC_vrm_animation spec
        }
    }

    /** Climb the humanoid parent chain until a bone with a world matrix is found. */
    private fun findParentBoneName(leaf: String): String {
        var parent: String? = HUMAN_BONE_PARENT_MAP[leaf]
        while (parent != null && worldMatrixMap[parent] == null) {
            parent = HUMAN_BONE_PARENT_MAP[parent]
        }
        return parent ?: HIPS_PARENT
    }

    private fun parseExpressionChannel(
        animation: Animation,
        channel: AnimationChannel,
        expressionName: String,
        path: String,
        result: VrmAnimation,
    ) {
        if (path != "translation") return
        val sampler = animation.samplers?.getOrNull(channel.sampler) ?: return
        val times = readTimes(sampler.input) ?: return
        val values = readValues(sampler.output) ?: return

        // expression weight = x component of the (usually VEC3) output
        val weights = FloatArray(values.size / 3)
        for (i in weights.indices) weights[i] = values[i * 3]
        val track = KeyframeTrack("$expressionName.weight", times.copyOf(), weights)

        if (expressionName in ExpressionPresetSet) {
            result.expressionTracks.preset[expressionName] = track
        } else {
            result.expressionTracks.custom[expressionName] = track
        }
    }

    private fun parseLookAtChannel(
        animation: Animation,
        channel: AnimationChannel,
        path: String,
        result: VrmAnimation,
    ) {
        if (path != "rotation") return
        val sampler = animation.samplers?.getOrNull(channel.sampler) ?: return
        val times = readTimes(sampler.input) ?: return
        val values = readValues(sampler.output) ?: return
        result.lookAtTrack = KeyframeTrack("lookAt.rotation", times.copyOf(), values.copyOf())
    }

    private fun readTimes(accessorIndex: Int): FloatArray? =
        try { AccessorReader.read(gltf, bin, accessorIndex) } catch (e: VrmaAccessorException) { null }

    private fun readValues(accessorIndex: Int): FloatArray? =
        try { AccessorReader.read(gltf, bin, accessorIndex) } catch (e: VrmaAccessorException) { null }

    private companion object {
        const val HIPS_PARENT = "hipsParent"

        val ExpressionPresetSet = setOf(
            "aa", "ih", "ou", "ee", "oh", "blink",
            "happy", "angry", "sad", "relaxed", "lookUp", "surprised",
            "lookDown", "lookLeft", "lookRight", "blinkLeft", "blinkRight", "neutral",
        )
    }
}