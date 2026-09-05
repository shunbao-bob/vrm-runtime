# vrm-android

**English** | [中文](README.zh-CN.md) | [日本語](README.ja.md)

A pure-Kotlin **VRM 1.0 parsing and runtime library** (codenamed vrm-android / vrmruntime)
that lets Android apps load VRM virtual-human models and drive their core behaviors: bone
mapping, expressions, spring-bone physics, VRMA animation retargeting, and eyesight
following (lookAt).

The rendering layer is built on **Google Filament** (integrated via SceneView's Engine /
ModelLoader) and does not depend on Unity / game engines / JNI game frameworks. The core
library `:vrm-core` is pure Kotlin/JVM with zero Android dependencies, testable in seconds
with JUnit5.

Porting blueprint: [three-vrm](https://github.com/pixiv/three-vrm) (TypeScript);
standard: [VRM 1.0 spec](https://github.com/vrm-c/vrm-specification) (focus on
`VRMC_vrm-1.0`, `VRMC_vrm_animation-1.0`, `VRMC_springBone-1.0`,
`VRMC_materials_mtoon-1.0`).

## Project structure

```
vrmruntime/
├── vrm-core/                     pure Kotlin JVM library (Maven publishable)
│   └── src/main/kotlin/dev/vrm/runtime/core/
│       ├── gltf/                 GLB parsing + glTF 2.0 data classes
│       ├── vrm/                  VRMC_vrm / VRMC_springBone / VRMC_materials_mtoon data classes + VrmLoader
│       ├── humanoid/             HumanBone mapping / VRMRig / NormalizedRig / VRMHumanoid
│       ├── expression/           ExpressionManager / ExpressionLoader / three kinds of bind
│       ├── vrma/                 VRMA parsing / retraining clip building / KeyframeTrack
│       ├── springbone/           Verlet spring bone / collider / manager / loader
│       ├── mtoon/                MToon material parameter model
│       ├── lookAt/               LookAt controller / bone+expression applier / range map
│       ├── controller/           AvatarController imperative API (reference: xlunar) + AvatarConfig resource list
│       └── math/                 Vec3 / Quat / Mat4 (three.js semantics, zero-dependency)
├── vrm-character/                engine-agnostic character behavior layer (JVM: emotion, motion,
│   │                              lip-sync, idle motion, wander AI, declarative SceneConfig)
│   └── src/main/kotlin/dev/vrm/runtime/character/
├── vrm-adapter/                  Android library (Filament/SceneView binding layer, sits on top of vrm-core)
│   └── src/main/java/dev/vrm/runtime/adapter/
│       ├── AvatarEngineController.kt    engine controller (implements AvatarBinding + lip-sync)
│       ├── AvatarRenderer.kt            adapter main entry (loadModel/loadModelAsync / applyStage / update)
│       ├── StageConfig.kt              lighting/scene config
│       ├── VrmAnimationPlayer.kt       live VRMA player (bone writes via TransformManager)
│       └── filament/                   FilamentNodeTransformStore / SpringBoneStore / MToonMaterialApplier
├── app/                     main landscape demo (Compose + SceneView/Filament, depends on :vrm-adapter)
│   └── src/main/java/dev/vrm/runtime/demo/
│       ├── VrmDemoScreen.kt          xlunar style: left viewport + right tab control panel
│       ├── DemoAssets.kt             model/VRMA/expression resource list
│       └── MainActivity.kt
└── demoPhone/               phone portrait AI virtual-human assistant (Compose + SceneView)
    └── src/main/java/dev/vrm/runtime/phonephone/
        ├── PhoneDemoScreen.kt        portrait UI + camera + STT/TTS
        ├── PhoneViewModel.kt         LLM chat + expression/gesture/animation dispatch, lip-sync
        ├── PhoneAssets.kt            phone asset catalogue (avatars / voices / environments)
        ├── brain/                    AssistantBrain + KeywordAssistantBrain
        └── voice/                    NUI (Aliyun) STT/TTS + playback gates
```

> Note: `vrm-character` shares the same engine-agnostic idle/motion layer used by both
> demos; `demoPhone` is a portrait AI-assistant app and is kept local-only (it bundles the
> closed-source Aliyun `nuisdk-release.aar`), so it is not part of the published/open-source
> modules — the repo keeps it for development.

## Environment

- JDK 17, Android API 26+
- Kotlin 2.4.x, AGP 8.12.x, Gradle 8.13
- Rendering: SceneView 2.3.3 (bundles Filament 1.6x)
- JSON: kotlinx.serialization 1.8.x
- Testing: JUnit5 + the official Seed-san.vrm fixture

## Module artifacts (Maven publishable)

All three libraries ship with `maven-publish` configured (groupId `dev.vrm.runtime`):

| Module       | Artifact                       | Type | Publish command                        |
|--------------|--------------------------------|------|----------------------------------------|
| vrm-core     | `dev.vrm.runtime:vrm-core:1.0.0`     | jar  | `./gradlew :vrm-core:publishToMavenLocal`     |
| vrm-character| `dev.vrm.runtime:vrm-character:1.0.0` | jar  | `./gradlew :vrm-character:publishToMavenLocal` |
| vrm-adapter  | `dev.vrm.runtime:vrm-adapter:1.0.0`   | aar  | `./gradlew :vrm-adapter:publishToMavenLocal`   |

Host projects depend on:

```kotlin
// settings.gradle.kts must be able to reach mavenCentral + mavenLocal()
implementation("dev.vrm.runtime:vrm-core:1.0.0")        // pure engine (JVM/Android)
implementation("dev.vrm.runtime:vrm-adapter:1.0.0")    // Filament/SceneView binding (Android)
implementation("dev.vrm.runtime:vrm-character:1.0.0")  // character behavior layer (JVM/Android)
```

The publications are jar/aar + pom + Gradle module metadata, generated automatically by
`publishing { MavenPublication(...) }`; to push to a remote repository (e.g. Maven Central)
just add a repository under `publishing.repositories` and configure signing as needed.

## Integration guide (library usage)

### 1. Parse a VRM

```kotlin
import dev.vrm.runtime.core.vrm.VrmLoader

val bytes: ByteArray = ... // .glb / .vrm file bytes
val vrm = VrmLoader.load(bytes)
// vrm.gltf           underlying glTF
// vrm.vrm            VRMC_vrm extension (humanoid/lookAt/expressions/meta)
// vrm.springBone     VRMC_springBone extension
// vrm.mtoon          material index -> VRMC_materials_mtoon
// vrm.vrmAnimation   VRMC_vrm_animation (for .vrma files)
```

### 2. Build a Humanoid + expressions (a NodeTransformStore is required)

```kotlin
import dev.vrm.runtime.core.humanoid.VRMHumanoid
import dev.vrm.runtime.core.expression.ExpressionLoader

// the store abstracts the render engine's node graph; the demo uses FilamentNodeTransformStore
val humanoid = VRMHumanoid.fromVrm(vrm.gltf, vrm.vrm!!, store)
val expressionManager = ExpressionLoader(bindProvider).load(vrm.gltf, vrm.vrm!!)
```

The core library is engine-agnostic: `NodeTransformStore` (getLocal*/getWorldMatrix/
parentNodeIndex) and `ExpressionBindProvider` (morphTargetChannel/materialColorAccess/
textureTransformAccess) are implemented by the host.

### 3. LookAt eye tracking

```kotlin
import dev.vrm.runtime.core.lookAt.VrmLookAtLoader

val lookAt = VrmLookAtLoader(humanoid, expressionManager, store).load(vrm.vrm!!)
lookAt.target = Vec3(x, y, z)   // world-space target point
// every frame:
lookAt.update(deltaSeconds)     // when autoUpdate=true, rotate toward target and write to expressions/bones
```

### 4. Spring bones

```kotlin
import dev.vrm.runtime.core.springbone.SpringBoneLoader

val springManager = SpringBoneLoader(vrm.gltf, vrm.springBone!!, springStore).load()
// every frame:
springManager.update(deltaSeconds)
```

### 5. Play VRMA animation (cross-model retraining)

```kotlin
import dev.vrm.runtime.core.vrma.VrmAnimationLoader
import dev.vrm.runtime.core.vrma.VRMAnimationClipBuilder

val vrma = VrmLoader.load(vrmaBytes)
val ext = vrma.vrmAnimation!!
val bin = vrma.binary?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
val animation = VrmAnimationLoader(vrma.gltf, bin, ext).loadAll().first()
val clip = VRMAnimationClipBuilder(animation, humanoid, expressionManager).build()
// clip.tracks are engine-agnostic KeyframeTracks:
//   Normalized_<bone>.quaternion / .position
//   <expressionName>.weight
```

The `VrmAnimationPlayer` in the adapter drives these tracks live (see below).

### 6. AvatarController (imperative control layer, reference: xlunar-ai-avatar)

`core/controller` wraps operations like "model selection / animation switching / expression
driving" into an engine-agnostic imperative API; the demo only needs to implement the
`AvatarBinding` interface to drive it:

```kotlin
import dev.vrm.runtime.core.controller.*
import dev.vrm.runtime.core.math.*

val binding: AvatarBinding = myController          // host implements AvatarBinding
val config = AvatarConfig(
    models = listOf(ModelPreset("seed", "Seed-san", "avatars/Seed-san.vrm")),
    animations = listOf(AnimationPreset("wave", "Wave", "animations/wave.vrma")),
)
val avatar = AvatarController(binding, config)
avatar.setModel("B")            // switch model
avatar.playVrma("wave")         // play animation (by app id or raw source)
avatar.setExpression("happy")   // expression
avatar.batch(listOf(...))       // several at once
avatar.queue(listOf(...))       // sequential queue (supports Wait)
```

Command types (`AvatarCommand`): SetPose / SetHandGesture / SetBodyGesture /
SetBodyMotion / SetExpression / PlayVrma / SetSequence / Wait / Reset /
ResetPose / ResetExpression / StopVrma / StopSequence / RawPose / RawExpression.

## demo app

The `app` module is a fully runnable Compose demo (landscape):

- **Model picker**: switch among the **42 VRM 1.0 models** (8 samples in `avatars/`
  incl. Seed-san / VRoid A–D / cryptovoxels + 34 characters in `models/`)
- **Pose**: static human poses, hand-gesture aliases, body-motion entry points, reset pose
- **Combos**: multi-step pose + expression choreography (friendly greeting / thinking
  eureka / explaining, etc.)
- **VRMA**: 20 packed VRMAs, live playback (no GLB baking), per-file loop toggle, stop
- **Face**: per-item 0..1 sliders for the standard VRM expressions
- **Scene**: ambient light, directional light, camera distance, auto-lookAt toggle,
  environment picker (Studio / Ferndale / Brown)
- **Spring-bone toggle** / **LookAt follow**: hair/skirt physics, gaze tracking

All animation/pose/combo go through the **live VRMA driver** (`VrmAnimationPlayer`): bone
local rotations are written to `TransformManager` every frame and driven by
`updateBoneMatrices()` — no GLB baking, no model rebuild, no reload race. Animations
fade in/out smoothly and idle falls back to a natural relaxed stance (breathing + random
blinks + micro-moves); one-shot clips ease back to the stance instead of freezing.

Demo assets: `app/src/main/assets/avatars/` (8 sample VRMs), `models/` (34 character VRMs),
`animations/` (20 VRMAs), `environments/` (3 IBL+skybox pairs).

> ⚠️ **Environment maps are required assets**: the demo depends on
> `assets/environments/*/*_ibl.ktx` + `*_skybox.ktx` (Studio / Ferndale Studio / Brown Photo
> Studio). SceneView's `createKTX1Environment(...)` reads from the **app's own assets** (not
> the library's). If the map files are missing the scene has no ambient light and **models
> render black**.
>
> ⚠️ The main demo is landscape-oriented; declare `android:screenOrientation` accordingly
> (or unspecified) in the manifest, or the ComposeView may be letterboxed on a portrait-in
> with landscape drawing area.

Build: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## demoPhone (AI virtual-human assistant)

A portrait, voice-driven assistant app (Compose + SceneView) on top of the same
`vrm-adapter` — chat by voice (Aliyun STT/TTS), and the avatar speaks with real-time
lip-sync, expressions, gestures and VRMA animations matched from an LLM.

![demoPhone effect](demo/demophone_effect.gif)

Highlights:

- **Voice conversation loop**: STT → AI reply → TTS with subtitle-frame lip-sync.
- **LLM expression/gesture/emotion protocol**: the system prompt asks the LLM to append a
  standard `[表情:xxx]` / `[动作:xxx]` tag chosen from the current supported preset set
  (expressions, gestures, 20 VRMAs). The client strips the tags before speech and plays the
  matching face/gesture/clip.
- **Mouth sync** is driven from Aliyun subtitle timestamps, rebased to real audio start with
  a small lead so the mouth matches the voice.
- Each demo effect is voice+animation; see `demo/demophone_effect_10s.mp4` for the full
  10s recording.

Because it bundles the closed-source Aliyun `nuisdk-release.aar`, `demoPhone` stays
out of the open-source module set (it is not declared in the published/remote build and is
kept only for local development).

## Tests

```bash
./gradlew :vrm-core:test --console=plain
```

95 tests all green (17 test classes), covering:

| Module | Test class | Count |
|---|---|---|
| glb + VRM data | VrmLoadingTest (full-field parsing of Seed-san) | 4 |
| Humanoid | VRMHumanoidTest (bone mapping / required validation / normalized rig) | 4 |
| Humanoid | TwistHumanoidMapTest | 1 |
| Humanoid | UpdateRestRoundtripTest | 1 |
| Expressions | ExpressionManagerTest (preset parsing / clamping / override groups / morph writes) | 16 |
| Expressions | MaterialColorBindTest (materialColorBinds -> baseColor) | 2 |
| VRMA | VrmAnimationLoaderTest (retraining to Seed-san) | 4 |
| VRMA | RealVrmaDiagnosticTest (channel diagnostics on a real test.vrma) | 1 |
| VRMA | TwistArmPoseTest (arm world positions during a spin) | 1 |
| SpringBone | SpringBoneColliderTest (sphere/capsule exact expected values) | 6 |
| SpringBone | SpringBoneTest (Verlet droop / bone-length conservation) | 4 |
| MToon | MtoonTest (10-material parsing / parameters / renderOrder) | 6 |
| LookAt | LookAtTest (range / angle / applier signs / loader / behavior) | 18 |
| Controller | AvatarControllerTest (queue / events / mul-thread / batch) | 13 |
| Guards | VrmLoaderGuardTest (empty / non-GLB / VRM 0.x input) | 6 |
| Guards | NodeTransformStoreGuardTest | 6 |
| math | Vec3ApiTest | 2 |

## Implemented features

- [x] GLB parsing (header + JSON/BIN chunks)
- [x] VRMC_vrm: humanoid (52 bones, 17 required validation), expressions
  (18 presets + custom), lookAt, meta, firstPerson data classes
- [x] VRMC_springBone: collider (sphere/capsule), joint, group, center
- [x] VRMC_materials_mtoon: parameter model (shading / texture index / outline / renderOrder)
- [x] VRMC_vrm_animation (.vrma): humanoid / expression / lookAt tracks + retraining clip
- [x] Humanoid: raw rig read/write / normalized rig / rest capture
- [x] Expressions: weight clamping / isBinary / override groups (blink/lookAt/mouth) / morph+material+UV binds
- [x] SpringBone: Verlet / stiffness / gravity / drag / collision ejection / depth sorting
- [x] MToon: sRGB opt-in, renderOrder, Filament cel shader
- [x] LookAt: yaw/pitch / bone+expression appliers / autoUpdate target
- [x] live VRMA playback (bone write + updateBoneMatrices) + smooth idle/transition helpers
- [x] demoPhone: STT/TTS, LLM tag parsing, lip-sync

## Known unsupported items / limitations

**Feature level**
- Supports only **VRM 1.0** (incl. 1.0-beta); no VRM 0.x compatibility.
- Reads VRM only, never writes; no editor features.
- MToon is a parameter model with approximate restoration, not 100% pixel-level.
- No VRMA editing; runtime playback only.
- glTF constraints / KHR extensions are not parsed (VRMC_node_constraint not implemented).

**Engine integration level (the demo's Filament binding)**
- `FilamentNodeTransformStore.parentNodeIndex` builds a nodeIndex→parent map from the glTF
  nodes, so bone-type LookAt / springbone center / normalized rig work.
- VRMA lookAt track (world quaternion → yaw/pitch) is consumed by the live player.
- Expression material-color / UV binds are per-material-property; skips if the shader
  lacks the property.
- Spring-bone collision is approximated with spheres (capsule = two spheres + radius).
- **Directional Light has no effect on MToon/cel materials (correct behavior)**: the demo's
  characters use cel materials that do not respond to directional light; the Scene tab
  combines ambient + directional into the ambient (IndirectLight) total brightness.
- MToon is approximate (rim / matcap / outline not rendered in the current
  MaterialShadingApplier; not a regression — out of scope).

## License

This library is licensed under the **MIT License**. See [LICENSE](LICENSE).

### Third-party notice

Significant portions of `vrm-core` (humanoid, expressions, lookAt, spring-bone
physics, MToon parameter model, VRMA retraining, math) are ports of
[three-vrm](https://github.com/pixiv/three-vrm), distributed under the MIT License
(Copyright (c) 2019-2026 pixiv Inc.); the copyright notice is preserved in the source.

The `vrm-adapter` (Filament/SceneView binding) and the `app` (demo) are original work built
on top of vrm-core, MIT-licensed.

The imperative avatar control layer (`AvatarController` / `AvatarCommand` / `AvatarConfig` /
`AvatarBinding`) and the demo's stage/pose/gesture/combo presets are ports of
[xlunar-ai-avatar](https://github.com/iamenahs/xlunar-ai-avatar) (MIT, © 2024
VaultX.technology); copyright preserved in the source.

### Model assets

This library bundles no model assets. The demo's sample models (Seed-san.vrm, the VRM
Consortium's test.vrma from three-vrm examples) belong to their respective authors and are
included for demonstration only — check each asset's own license before commercial use.