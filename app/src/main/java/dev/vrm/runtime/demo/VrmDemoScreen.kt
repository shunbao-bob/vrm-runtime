package dev.vrm.runtime.demo

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vrm.runtime.adapter.AvatarRenderer
import dev.vrm.runtime.adapter.StageConfig
import dev.vrm.runtime.core.controller.AvatarCommand
import dev.vrm.runtime.core.controller.AvatarController
import dev.vrm.runtime.core.controller.AnimationPreset
import dev.vrm.runtime.core.controller.ExpressionPreset
import dev.vrm.runtime.core.controller.ModelPreset
import dev.vrm.runtime.core.controller.NamedPreset
import dev.vrm.runtime.core.controller.PosePreset
import dev.vrm.runtime.core.controller.SequencePreset
import io.github.sceneview.Scene
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNode
import io.github.sceneview.rememberOnGestureListener
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Demo tab identifiers (matching xlunar's panel). */
private val TABS = listOf("Model", "Pose", "Combos", "VRMA", "Face", "Scene", "Speech", "Control")

private const val TAB_MODEL = 0
private const val TAB_POSE = 1
private const val TAB_COMBOS = 2
private const val TAB_VRMA = 3
private const val TAB_FACE = 4
private const val TAB_SCENE = 5

/** Look-at direction expression names (VRM 1.0 preset). */
private val LOOK_EXPRESSIONS = setOf("lookUp", "lookDown", "lookLeft", "lookRight")

/**
 * Applies an expression weight. The look* expressions have no morph binding on a
 * bone-type lookAt model, so they instead drive VrmLookAt's yaw/pitch (rotate the eye
 * bones); expression-type (Seed-san) uses the normal expression channel.
 */
private fun applyFace(ctrl: dev.vrm.runtime.adapter.AvatarEngineController?, name: String, weight: Float) {
    val la = ctrl?.lookAt
    val boneType = la?.applier is dev.vrm.runtime.core.lookAt.VrmLookAtBoneApplier
    if (boneType && name in LOOK_EXPRESSIONS && la != null) {
        la.autoUpdate = false  // disable the automatic target when manually controlling
        when (name) {
            "lookUp" -> { la.yaw = 0f; la.pitch = -weight * 90f }
            "lookDown" -> { la.yaw = 0f; la.pitch = weight * 90f }
            "lookLeft" -> { la.yaw = weight * 90f; la.pitch = 0f }
            "lookRight" -> { la.yaw = -weight * 90f; la.pitch = 0f }
        }
        la.update(0f)
    } else {
        ctrl?.setExpressionWeight(name, weight)
    }
}

private const val TAB_SPEECH = 6
private const val TAB_CONTROL = 7

/**
 * Demo screen (xlunar-ai-avatar style): left 3D avatar viewport + right control
 * panel with tabs. Uses the [AvatarRenderer] adapter for all VRM operations and
 * an [AvatarController] command facade for avatar actions.
 */
@Composable
fun VrmDemoScreen() {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine, context)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // tab + selection state
    var activeTab by remember { mutableIntStateOf(TAB_MODEL) }
    var selectedModelIdx by remember { mutableIntStateOf(0) }
    var selectedAnimId by remember { mutableStateOf(DemoAssets.animations[0].id) }
    var selectedComboId by remember { mutableStateOf(DemoAssets.sequences.first().id) }
    var vrmaLoop by remember { mutableStateOf(true) }
    var springEnabled by remember { mutableStateOf(true) }
    var ambientIntensity by remember { mutableStateOf(0.6f) }
    var dirIntensity by remember { mutableStateOf(1.1f) }
    var cameraDistance by remember { mutableStateOf(1.0f) }
    var lookAtAuto by remember { mutableStateOf(false) }
    var currentEnvId by remember { mutableStateOf("studio") }
    var speaking by remember { mutableStateOf(false) }
    var faceWeights by remember {
        mutableStateOf(DemoAssets.expressions.associate { it.id to 0f })
    }
    val combosScope = rememberCoroutineScope()

    // the adapter renderer (created once)
    val rendererRef = remember { AtomicReference<AvatarRenderer?>(null) }

    // the model node surfaced as Compose state so Scene recomposes on model switch
    val modelNodeState = remember { mutableStateOf<io.github.sceneview.node.ModelNode?>(null) }
    // the directional light node surfaced as Compose state so Scene includes it
    val lightNodeState = remember { mutableStateOf<io.github.sceneview.node.LightNode?>(null) }

    val assetResolver = { key: String ->
        runCatching { context.assets.open(key).use { it.readBytes() } }.getOrNull()
    }

    // environment map (initially uses the studio ktx)
    var environment by remember {
        mutableStateOf<io.github.sceneview.environment.Environment?>(environmentLoader.createKTX1Environment(
            iblAssetFile = "environments/studio/studio_ibl.ktx",
            skyboxAssetFile = "environments/studio/studio_skybox.ktx",
        ))
    }

    // scene nodes
    val centerNode = rememberNode(engine)
    val cameraNode = rememberCameraNode(engine) {
        position = io.github.sceneview.math.Position(y = 1.0f, z = 1.0f)
        lookAt(centerNode)
        centerNode.addChildNode(this)
    }
    // update camera position when the camera distance changes
    LaunchedEffect(cameraDistance) {
        cameraNode.position = io.github.sceneview.math.Position(y = 1.0f, z = cameraDistance)
        cameraNode.lookAt(centerNode)
    }
    // reload the ktx when the environment changes
    LaunchedEffect(currentEnvId) {
        runCatching {
            val ibl = environmentLoader.createKTX1Environment(
                iblAssetFile = "environments/$currentEnvId/${currentEnvId}_ibl.ktx",
                skyboxAssetFile = "environments/$currentEnvId/${currentEnvId}_skybox.ktx",
            )
            if (ibl != null) {
                environment = ibl
            }
        }
    }

    // load the default model via the adapter once ready
    LaunchedEffect(engine, modelLoader) {
        val renderer = AvatarRenderer(engine, modelLoader, context, assetResolver, DemoAssets.config)
        rendererRef.set(renderer)
        // load the default model and bake the default animation
        val defaultAnim = DemoAssets.animations.firstOrNull { it.id == "greeting" }
            ?: DemoAssets.animations.firstOrNull { it.id == "spin" }
            ?: DemoAssets.animations.first { it.loop }
        vrmaLoop = defaultAnim.loop
        renderer.loadModel(DemoAssets.models.firstOrNull { it.id == "vroid-b" }?.source
            ?: DemoAssets.models.first().source, animationSource = defaultAnim.source)
        modelNodeState.value = renderer.modelNode
        lightNodeState.value = renderer.createOrUpdateLight()
    }

    // ── DIAG control: switch model/animation via adb broadcast ──
    //   adb shell am broadcast -a dev.vrm.runtime.demo.SWITCH -e model vroid-c
    //   adb shell am broadcast -a dev.vrm.runtime.demo.SWITCH -e vrma spin
    DisposableEffect(Unit) {
        val filter = IntentFilter("dev.vrm.runtime.demo.SWITCH")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent) {
                val renderer = rendererRef.get() ?: return
                val modelId = intent.getStringExtra("model")
                val vrmaId = intent.getStringExtra("vrma")
                if (modelId != null) {
                    val m = DemoAssets.models.firstOrNull { it.id == modelId }
                        ?: DemoAssets.models.firstOrNull { it.name.contains(modelId, ignoreCase = true) }
                        ?: return
                    selectedModelIdx = DemoAssets.models.indexOf(m)
                    renderer.loadModel(m.source, animationSource = renderer.currentAnimationSource)
                    modelNodeState.value = renderer.modelNode
                    lightNodeState.value = renderer.createOrUpdateLight()
                    // Don't call Reset: keep the gltfio bind pose after switching models
                    // (same as first load), to avoid resetNormalizedPose(identity) writing
                    // bones back to the GLB rest local space and distorting the legs/hands of
                    // A-pose models like VRoid under the gltfio bind parent chain.
                }
                if (vrmaId != null) {
                    val a = DemoAssets.animations.firstOrNull { it.id == vrmaId }
                        ?: DemoAssets.animations.firstOrNull { it.name.contains(vrmaId, ignoreCase = true) }
                    if (a != null) {
                        renderer.loadAnimation(a.source)
                        modelNodeState.value = renderer.modelNode
                        lightNodeState.value = renderer.createOrUpdateLight()
                        vrmaLoop = a.loop
                    }
                }
                // LIVE POSE/COMBO DIAG: adb shell am broadcast -a dev.vrm.runtime.demo.SWITCH -e pose <id> | -e combo <id>
                val poseId = intent.getStringExtra("pose")
                if (poseId != null) {
                    val p = DemoAssets.poses.firstOrNull { it.id == poseId }
                        ?: DemoAssets.poses.firstOrNull { it.name.contains(poseId, ignoreCase = true) }
                    if (p != null) {
                        renderer.loadPose(p)
                        android.util.Log.i("AvatarEngine", "EVENT pose live id=${p.id}")
                    }
                    return
                }
                val comboId = intent.getStringExtra("combo")
                if (comboId != null) {
                    val seq = DemoAssets.sequences.firstOrNull { it.id == comboId }
                        ?: DemoAssets.sequences.firstOrNull { it.name.contains(comboId, ignoreCase = true) }
                    if (seq != null) {
                        val evs = renderer.loadCombo(seq, DemoAssets.poses)
                        android.util.Log.i("AvatarEngine", "EVENT combo live id=${seq.id} exprEvents=${evs.size}")
                    }
                    return
                }
                // EXPRESSION DIAG: adb shell am broadcast -a dev.vrm.runtime.demo.SWITCH -e expr happy [-e weight 1]
                val exprId = intent.getStringExtra("expr")
                if (exprId != null) {
                    val weight = intent.getStringExtra("weight")?.toFloatOrNull() ?: 1f
                    applyFace(renderer.engineController, exprId, weight.coerceIn(0f, 1f))
                    android.util.Log.i("AvatarEngine", "EVENT expr live id=$exprId weight=$weight")
                    return
                }
            }
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    // re-apply stage config when sliders change
    LaunchedEffect(ambientIntensity, dirIntensity) {
        rendererRef.get()?.let { r ->
            r.applyStage(
                StageConfig(
                    ambientLightIntensity = ambientIntensity,
                    directionalLightIntensity = dirIntensity,
                )
            )
        }
    }

    LaunchedEffect(cameraDistance) {
        cameraNode.position = io.github.sceneview.math.Position(y = 1.0f, z = cameraDistance)
        cameraNode.lookAt(centerNode)
    }

    val lookAtTime = remember { AtomicLong(0L) }
    var frameCounter by remember { mutableIntStateOf(0) }

    Row(modifier = Modifier.fillMaxSize()) {
        // ---- Left: 3D viewport ----
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            LaunchedEffect(ambientIntensity, dirIntensity) {
                environment?.indirectLight?.let { il ->
                    runCatching {
                        val total = (ambientIntensity * 400_000f + dirIntensity * 250_000f).coerceAtLeast(1f)
                        il.setIntensity(total)
                    }
                }
            }
            Scene(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                cameraNode = cameraNode,
                cameraManipulator = rememberCameraManipulator(
                    orbitHomePosition = cameraNode.worldPosition,
                    targetPosition = centerNode.worldPosition,
                ),
                childNodes = listOfNotNull(centerNode, modelNodeState.value, lightNodeState.value),
                environment = environment!!,
                onFrame = {
                    val renderer = rendererRef.get()
                    if (renderer != null) {
                        renderer.engineController?.springBoneEnabled = springEnabled
                        if (speaking) {
                            renderer.engineController?.updateMouth(1f / 60f)
                        }
                        renderer.update(1f / 60f)
                        // LIVE DRIVING (08-24): renderer.update() already
                        // advances the real-time VRMA player -> humanoid.update()
                        // -> store.setLocalRotation (TransformManager) and calls
                        // Animator.updateBoneMatrices(), so the skin follows the
                        // live bone writes. No baked-GLB/updateBakedAnimation.
                        val lookAt = renderer.engineController?.lookAt
                        if (lookAtAuto && lookAt != null) {
                            lookAt.autoUpdate = true
                            val t = (lookAtTime.getAndIncrement() % 400L) / 400f * 2f * Math.PI
                            val head = lookAt.getLookAtWorldPosition(dev.vrm.runtime.core.math.Vec3())
                            lookAt.target = dev.vrm.runtime.core.math.Vec3(
                                head.x + 0.5f * kotlin.math.sin(t).toFloat(),
                                head.y + 0.25f * kotlin.math.cos(t).toFloat(),
                                head.z + 0.6f,
                            )
                        }
                    }
                },
                onGestureListener = rememberOnGestureListener(),
            )
        }

        // ---- Right: control panel ----
        Column(
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TABS.forEachIndexed { idx, label ->
                    TabBtn(label, idx == activeTab) { activeTab = idx }
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (activeTab) {
                    TAB_MODEL -> ModelTab(DemoAssets.models, selectedModelIdx) { idx ->
                        selectedModelIdx = idx
                        rendererRef.get()?.let { r ->
                            // Load the new model, re-baking the currently selected
                            // animation onto it (keeps motion across model switches).
                            r.loadModel(DemoAssets.models[idx].source, animationSource = r.currentAnimationSource)
                            modelNodeState.value = r.modelNode
                            lightNodeState.value = r.createOrUpdateLight()
                            // Don't call Reset (same entry as broadcast model switch): keep the
                            // gltfio bind pose, to avoid identity NLR writing back to GLB rest
                            // local space and skewing the pose of A-pose models.
                        }
                    }
                    TAB_POSE -> PoseTab(
                        poses = DemoAssets.poses,
                        gestures = DemoAssets.handGestures,
                        motions = DemoAssets.bodyMotions,
                        onPose = { id ->
                            val r = rendererRef.get() ?: return@PoseTab
                            val pose = DemoAssets.poses.firstOrNull { it.id == id }
                            if (pose != null) {
                                r.loadPose(pose)
                                modelNodeState.value = r.modelNode
                                lightNodeState.value = r.createOrUpdateLight()
                            }
                        },
                        onGesture = { id -> rendererRef.get()?.avatar?.execute(AvatarCommand.SetHandGesture(id)) },
                        onMotion = { id -> rendererRef.get()?.avatar?.execute(AvatarCommand.SetBodyMotion(id)) },
                        onReset = { rendererRef.get()?.avatar?.execute(AvatarCommand.ResetPose) },
                    )
                    TAB_COMBOS -> CombosTab(DemoAssets.sequences, selectedComboId) { id ->
                        selectedComboId = id
                        val r = rendererRef.get() ?: return@CombosTab
                        val seq = DemoAssets.sequences.firstOrNull { it.id == id } ?: return@CombosTab
                        // Play the choreography LIVE (08-24): no GLB bake, no
                        // model reload. Returns the expression events to fire
                        // out-of-band at their times.
                        val exprEvents = r.loadCombo(seq, DemoAssets.poses)
                        combosScope.launch {
                            // fire expression/morph events at their clip times
                            for ((name, at) in exprEvents) {
                                if (at > 0f) delay((at * 1000L).toLong())
                                if (name == "__reset__") {
                                    r.engineController?.resetExpressions()
                                } else {
                                    r.engineController?.setExpressionWeight(name, 1f)
                                }
                            }
                        }
                    }
                    TAB_VRMA -> VrmaTab(DemoAssets.animations, selectedAnimId, vrmaLoop, { vrmaLoop = it }) { id ->
                        if (id.isEmpty()) {
                            rendererRef.get()?.avatar?.execute(AvatarCommand.StopVrma)
                            return@VrmaTab
                        }
                        selectedAnimId = id
                        val r = rendererRef.get() ?: return@VrmaTab
                        val src = DemoAssets.animations.first { it.id == id }.source
                        // Re-bake the selected VRMA onto the current model and
                        // play it through the gltfio Animator.
                        r.loadAnimation(src)
                        modelNodeState.value = r.modelNode
                        lightNodeState.value = r.createOrUpdateLight()
                    }
                    TAB_FACE -> FaceTab(DemoAssets.expressions, faceWeights, { name, weight ->
                        faceWeights = faceWeights.toMutableMap().also { it[name] = weight }
                        applyFace(rendererRef.get()?.engineController, name, weight)
                    }, {
                        faceWeights = faceWeights.mapValues { 0f }
                        rendererRef.get()?.avatar?.execute(AvatarCommand.ResetExpression)
                    })
                    TAB_SCENE -> SceneTab(
                        ambient = ambientIntensity,
                        onAmbient = { ambientIntensity = it },
                        dir = dirIntensity,
                        onDir = { dirIntensity = it },
                        cameraDistance = cameraDistance,
                        onCameraDistance = { cameraDistance = it },
                        lookAtAuto = lookAtAuto,
                        onLookAtAuto = { lookAtAuto = it },
                        currentEnv = currentEnvId,
                        onSetEnv = { id ->
                            currentEnvId = id
                            // environment switch is triggered automatically by produceState
                        },
                    )
                    TAB_SPEECH -> SpeechTab(
                        speaking = speaking,
                        onSpeak = { text ->
                            rendererRef.get()?.engineController?.let { c ->
                                c.startMouth(text.length)
                                // the main demo has no TTS subtitle callback; simulate a simple 3s mouth-opening sequence
                                val durationMs = (text.length * 120L).coerceIn(1000L, 5000L)
                                val frameMs = 150L
                                var t = 0L
                                while (t < durationMs) {
                                    c.pushSubtitleFrame(
                                        dev.vrm.runtime.adapter.SubtitleFrame(
                                            beginTime = t,
                                            endTime = (t + frameMs).coerceAtMost(durationMs),
                                            text = "",
                                            phoneme = "",
                                        )
                                    )
                                    t += frameMs
                                }
                                c.markPlaybackStarted()
                            }
                            speaking = true
                        },
                        onStop = {
                            rendererRef.get()?.engineController?.let { c -> c.stopMouth() }
                            speaking = false
                        },
                    )
                    else -> ControlTab(
                        springEnabled = springEnabled,
                        onSpringToggle = { springEnabled = it },
                        onReset = { rendererRef.get()?.avatar?.reset() },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabBtn(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 14.sp, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun ModelTab(
    models: List<ModelPreset>,
    selectedIdx: Int,
    onSelect: (Int) -> Unit,
) {
    SectionTitle("Model Selection")
    models.forEachIndexed { idx, m ->
        Text(
            text = m.name,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = if (idx == selectedIdx) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                )
                .clickable { onSelect(idx) }
                .padding(10.dp),
        )
    }
}

@Composable
private fun PoseTab(
    poses: List<PosePreset>,
    gestures: List<NamedPreset>,
    motions: List<NamedPreset>,
    onPose: (String) -> Unit,
    onGesture: (String) -> Unit,
    onMotion: (String) -> Unit,
    onReset: () -> Unit,
) {
    SectionTitle("Body Poses (${poses.size})")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        poses.forEach { p ->
            Text(
                text = p.name,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                    .clickable { onPose(p.id) }
                    .padding(8.dp),
            )
        }
    }
    SectionTitle("Hand Gestures")
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        gestures.forEach { g ->
            Text(g.name, fontSize = 10.sp, modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                .clickable { onGesture(g.id) }
                .padding(horizontal = 8.dp, vertical = 6.dp))
        }
    }
    SectionTitle("Body Motion")
    motions.forEach { m ->
        Text(m.name, fontSize = 11.sp, modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .clickable { onMotion(m.id) }
            .padding(8.dp))
    }
    Text("Reset Pose", color = MaterialTheme.colorScheme.primary, modifier = Modifier
        .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
        .clickable(onClick = onReset)
        .padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun CombosTab(
    sequences: List<SequencePreset>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    SectionTitle("Motion Combos (${sequences.size})")
    Text("Choreographed pose + face sequences, matching xlunar's Combos tab.", fontSize = 11.sp)
    sequences.forEach { sequence ->
        Text(
            text = sequence.name,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (sequence.id == selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.shapes.small,
                )
                .clickable { onSelect(sequence.id) }
                .padding(9.dp),
        )
    }
}

@Composable
private fun VrmaTab(
    animations: List<AnimationPreset>,
    selectedAnim: String,
    loop: Boolean,
    onLoopChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    SectionTitle("VRMA Animation (${animations.size})")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = loop, onCheckedChange = onLoopChange)
        Spacer(Modifier.width(6.dp))
        Text("Loop playback", fontSize = 12.sp)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        animations.forEach { a ->
            Text(
                text = "${if (a.loop) "🔁" else "🕐"} ${a.name}",
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (a.id == selectedAnim) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    )
                    .clickable { onSelect(a.id) }
                    .padding(8.dp),
            )
        }
    }
    Text(
        text = "Stop VRMA",
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
            .clickable { onSelect("") }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun FaceTab(
    expressions: List<ExpressionPreset>,
    weights: Map<String, Float>,
    onSelect: (String, Float) -> Unit,
    onResetFace: () -> Unit,
) {
    SectionTitle("Faces (${expressions.size})")
    expressions.forEach { e ->
        val value = weights[e.id] ?: 0f
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name, fontSize = 11.sp, modifier = Modifier.width(92.dp))
            Slider(
                value = value,
                onValueChange = { onSelect(e.id, it) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
            )
            Text("${(value * 100).toInt()}%", fontSize = 10.sp, modifier = Modifier.width(38.dp))
        }
    }
    Text(
        text = "Reset Face",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
            .clickable(onClick = onResetFace)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SceneTab(
    ambient: Float,
    onAmbient: (Float) -> Unit,
    dir: Float,
    onDir: (Float) -> Unit,
    cameraDistance: Float,
    onCameraDistance: (Float) -> Unit,
    lookAtAuto: Boolean,
    onLookAtAuto: (Boolean) -> Unit,
    currentEnv: String,
    onSetEnv: (String) -> Unit,
) {
    SectionTitle("Scene / Lighting")
    Text("Ambient light")
    Slider(value = ambient, onValueChange = onAmbient, valueRange = 0f..1.5f)
    Text("Directional light")
    Slider(value = dir, onValueChange = onDir, valueRange = 0f..2f)
    Text("Camera distance: ${"%.2f".format(cameraDistance)}")
    Slider(value = cameraDistance, onValueChange = onCameraDistance, valueRange = 0.5f..2.5f)
    Text("Environment", fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("studio", "flower_road", "venice").forEach { id ->
            Text(
                text = id.replaceFirstChar { it.uppercaseChar() },
                fontSize = 12.sp,
                color = if (currentEnv == id) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        color = if (currentEnv == id) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    )
                    .clickable { onSetEnv(id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = lookAtAuto, onCheckedChange = onLookAtAuto)
        Spacer(Modifier.width(6.dp))
        Text("Auto look-at", fontSize = 12.sp)
    }
}

@Composable
private fun SpeechTab(
    speaking: Boolean,
    onSpeak: (String) -> Unit,
    onStop: () -> Unit,
) {
    SectionTitle("Speech (local lip-sync)")
    var text by remember { mutableStateOf("Hello there! I am a VRM avatar.") }
    androidx.compose.material3.OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Text to speak") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (speaking) "Speaking…" else "🔊 Speak",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
                .clickable(enabled = !speaking && text.isNotEmpty()) { onSpeak(text) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        if (speaking) {
            Text(
                text = "⏹ Stop",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                    .clickable(onClick = onStop)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
    Text(
        "Mouth visemes (aa/ih/ou/ee/oh) cycle while speaking — a local lip-sync " +
            "demo without any TTS dependency.",
        fontSize = 11.sp,
    )
}

@Composable
private fun ControlTab(
    springEnabled: Boolean,
    onSpringToggle: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    SectionTitle("Control")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = springEnabled, onCheckedChange = onSpringToggle)
        Spacer(Modifier.width(6.dp))
        Text("Spring bones", fontSize = 12.sp)
    }
    Text(
        text = "Reset All",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
            .clickable(onClick = onReset)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
