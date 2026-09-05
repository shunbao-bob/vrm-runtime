package dev.vrm.runtime.demo

import dev.vrm.runtime.core.controller.AnimationPreset
import dev.vrm.runtime.core.controller.AvatarCommand
import dev.vrm.runtime.core.controller.AvatarConfig
import dev.vrm.runtime.core.controller.ExpressionPreset
import dev.vrm.runtime.core.controller.NamedPreset
import dev.vrm.runtime.core.controller.ModelPreset
import dev.vrm.runtime.core.controller.PosePreset
import dev.vrm.runtime.core.controller.RawBoneRotation
import dev.vrm.runtime.core.controller.SequencePreset
import dev.vrm.runtime.core.math.Vec3

/**
 * Demo asset catalogue for the adapter's [AvatarConfig]: which bundled VRM
 * models and VRMA animations are available, plus expression presets.
 *
 * VRMAs are the 20 clips copied from xlunar-ai-avatar's `public/1.0/animations/`.
 */
object DemoAssets {

    private fun bone(x: Float, y: Float, z: Float) =
        RawBoneRotation(degrees = Vec3(x, y, z))

    private fun pose(id: String, name: String, vararg bones: Pair<String, RawBoneRotation>) =
        PosePreset(id, name, bones = linkedMapOf(*bones))

    /** xlunar-inspired static body poses.  Values are VRM human-bone Euler degrees. */
    val poses: List<PosePreset> = listOf(
        pose("tpose", "T-Pose"),
        pose("apose", "A-Pose", "leftUpperArm" to bone(0f, 0f, -45f), "rightUpperArm" to bone(0f, 0f, 45f)),
        pose("relaxed", "Relaxed", "spine" to bone(2f, 0f, 0f), "leftUpperArm" to bone(0f, 0f, -78f), "rightUpperArm" to bone(0f, 0f, 78f)),
        pose("handsOnHips", "Hands on Hips", "leftUpperArm" to bone(0f, 35f, -55f), "rightUpperArm" to bone(0f, -35f, 55f), "leftLowerArm" to bone(0f, 0f, -80f), "rightLowerArm" to bone(0f, 0f, 80f)),
        pose("armsCrossed", "Arms Crossed", "leftUpperArm" to bone(-30f, 50f, -40f), "rightUpperArm" to bone(-30f, -50f, 40f), "leftLowerArm" to bone(0f, 15f, -125f), "rightLowerArm" to bone(0f, -15f, 125f)),
        pose("thinking", "Thinking", "head" to bone(10f, -8f, -3f), "rightUpperArm" to bone(-55f, -20f, 25f), "rightLowerArm" to bone(0f, -35f, 140f)),
        pose("presenting", "Presenting", "spine" to bone(0f, -8f, 0f), "rightUpperArm" to bone(-50f, 0f, 15f), "rightLowerArm" to bone(0f, 0f, 20f)),
        pose("waving", "Waving", "rightUpperArm" to bone(-10f, -15f, -20f), "rightLowerArm" to bone(-15f, 0f, 100f)),
        pose("pointing", "Pointing", "spine" to bone(0f, -5f, 0f), "rightUpperArm" to bone(-60f, 0f, 10f), "rightLowerArm" to bone(0f, 0f, 5f)),
        pose("surprised", "Surprised", "spine" to bone(-4f, 0f, 0f), "leftUpperArm" to bone(-20f, 20f, -35f), "rightUpperArm" to bone(-20f, -20f, 35f), "leftLowerArm" to bone(0f, 20f, -60f), "rightLowerArm" to bone(0f, -20f, 60f)),
        pose("bow", "Bow", "spine" to bone(25f, 0f, 0f), "chest" to bone(5f, 0f, 0f)),
        pose("sad", "Sad", "spine" to bone(8f, 0f, 0f), "chest" to bone(5f, 0f, 0f), "head" to bone(15f, 0f, 0f)),
        // Gesture aliases used by the command API / combos.
        pose("peace", "Peace Sign", "rightUpperArm" to bone(-25f, -10f, -15f), "rightLowerArm" to bone(0f, -10f, 95f)),
        pose("thumbsUp", "Thumbs Up", "rightUpperArm" to bone(-35f, -10f, 10f), "rightLowerArm" to bone(0f, -25f, 115f)),
        pose("nod", "Nod", "head" to bone(12f, 0f, 0f)),
        pose("shake", "Shake Head", "head" to bone(0f, 18f, 0f)),
        pose("shrug", "Shrug", "leftUpperArm" to bone(-10f, 0f, -65f), "rightUpperArm" to bone(-10f, 0f, 65f)),
    )

    val handGestures = listOf(
        NamedPreset("open", "Open Hand"), NamedPreset("fist", "Fist"),
        NamedPreset("pointing", "Pointing"), NamedPreset("peace", "Peace Sign"),
        NamedPreset("thumbsUp", "Thumbs Up"), NamedPreset("wave", "Wave"),
    )

    val bodyGestures = listOf(
        NamedPreset("nod", "Nod"), NamedPreset("shake", "Shake Head"),
        NamedPreset("wave", "Wave"), NamedPreset("bow", "Bow"), NamedPreset("shrug", "Shrug"),
    )

    val bodyMotions = listOf(
        NamedPreset("none", "None"), NamedPreset("idleNatural", "Idle Natural"),
        NamedPreset("breathingSubtle", "Breathing"), NamedPreset("swayGentle", "Gentle Sway"),
    )

    val sequences = listOf(
        SequencePreset("friendlyGreeting", "Friendly Greeting", listOf(
            AvatarCommand.SetPose("waving"), AvatarCommand.SetExpression("happy"),
            AvatarCommand.Wait(1600), AvatarCommand.SetPose("relaxed"), AvatarCommand.Wait(400),
            AvatarCommand.ResetExpression,
        )),
        SequencePreset("thinkingEureka", "Thinking Eureka", listOf(
            AvatarCommand.SetPose("thinking"), AvatarCommand.SetExpression("relaxed"),
            AvatarCommand.Wait(1800), AvatarCommand.SetPose("surprised"), AvatarCommand.SetExpression("happy"),
            AvatarCommand.Wait(1200), AvatarCommand.Reset,
        )),
        SequencePreset("explainingIdea", "Explaining Idea", listOf(
            AvatarCommand.SetPose("presenting"), AvatarCommand.SetExpression("happy"),
            AvatarCommand.Wait(2200), AvatarCommand.SetPose("relaxed"), AvatarCommand.ResetExpression,
        )),
        SequencePreset("surprisedReaction", "Surprised Reaction", listOf(
            AvatarCommand.SetPose("surprised"), AvatarCommand.SetExpression("surprised"),
            AvatarCommand.Wait(1200), AvatarCommand.SetPose("relaxed"), AvatarCommand.ResetExpression,
        )),
        SequencePreset("confidentPresenter", "Confident Presenter", listOf(
            AvatarCommand.SetPose("presenting"), AvatarCommand.SetExpression("happy"),
            AvatarCommand.Wait(2400), AvatarCommand.Reset,
        )),
    )

    /** Bundled VRM 1.0 models (only the 4 VRoid Sample avatars). */
    val models = listOf(
        ModelPreset("vroid-a", "VRoid Sample A", "avatars/VRoid_Sample_A.vrm"),
        ModelPreset("vroid-b", "VRoid Sample B", "avatars/VRoid_Sample_B.vrm"),
        ModelPreset("vroid-c", "VRoid Sample C", "avatars/VRoid_Sample_C.vrm"),
        ModelPreset("vroid-d", "VRoid Sample D", "avatars/VRoid_Sample_D.vrm"),
    )

    /** Bundled VRMA clips (id -> animation file). */
    val animations: List<AnimationPreset> = listOf(
        AnimationPreset("test", "Test", "animations/test.vrma", category = "action", loop = true),
        AnimationPreset("show-full-body", "Show Full Body", "animations/ShowFullBody.vrma", category = "vroid", loop = false),
        AnimationPreset("greeting", "Greeting", "animations/Greeting.vrma", category = "vroid", loop = false),
        AnimationPreset("peace-sign", "Peace Sign", "animations/PeaceSign.vrma", category = "vroid", loop = false),
        AnimationPreset("shoot", "Shoot", "animations/Shoot.vrma", category = "vroid", loop = false),
        AnimationPreset("spin", "Spin", "animations/Spin.vrma", category = "vroid", loop = true),
        AnimationPreset("model-pose", "Model Pose", "animations/ModelPose.vrma", category = "vroid", loop = false),
        AnimationPreset("squat", "Squat", "animations/Squat.vrma", category = "vroid", loop = true),
        AnimationPreset("goodbye", "Goodbye", "animations/Goodbye.vrma", category = "greeting", loop = true),
        AnimationPreset("angry", "Angry", "animations/Angry.vrma", category = "emotion", loop = true),
        AnimationPreset("clapping", "Clapping", "animations/Clapping.vrma", category = "action", loop = true),
        AnimationPreset("jump", "Jump", "animations/Jump.vrma", category = "action", loop = true),
        AnimationPreset("look-around", "Look Around", "animations/LookAround.vrma", category = "action", loop = true),
        AnimationPreset("blush", "Blush", "animations/Blush.vrma", category = "emotion", loop = true),
        AnimationPreset("sad", "Sad", "animations/Sad.vrma", category = "emotion", loop = true),
        AnimationPreset("sleepy", "Sleepy", "animations/Sleepy.vrma", category = "idle", loop = true),
        AnimationPreset("surprised", "Surprised", "animations/Surprised.vrma", category = "emotion", loop = false),
        AnimationPreset("thinking", "Thinking", "animations/Thinking.vrma", category = "action", loop = true),
        AnimationPreset("relax", "Relaxing", "animations/Relax.vrma", category = "idle", loop = true),
        AnimationPreset("mocopi", "Mocopi Idle", "animations/sample-mocopi.vrma", category = "idle", loop = true),
    )

    /** Standard preset expressions. */
    val expressions: List<ExpressionPreset> = listOf(
        "neutral", "happy", "sad", "angry", "surprised", "relaxed",
        "aa", "ih", "ou", "ee", "oh",
        "blink", "blinkLeft", "blinkRight",
        "lookUp", "lookDown", "lookLeft", "lookRight",
    ).map { ExpressionPreset(it, it.replaceFirstChar { c -> c.uppercaseChar() }) }

    /** The full [AvatarConfig] for the demo. */
    val config: AvatarConfig = AvatarConfig(
        models = models,
        animations = animations,
        expressions = expressions,
        poses = poses,
        handGestures = handGestures,
        bodyGestures = bodyGestures,
        bodyMotions = bodyMotions,
        sequences = sequences,
    )
}
