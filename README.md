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
│       ├── humanoid/             HumanBone mapping / VRMRig / normalized rig / VRMHumanoid
│       ├── expression/           ExpressionManager / ExpressionLoader / three kinds of bind
│       ├── vrma/                 VRMA parsing / retargeting clip building / KeyframeTrack
│       ├── springbone/           Verlet spring bone / collider / manager / loader
│       ├── mtoon/                MToon material parameter model
│       ├── lookAt/               LookAt controller / bone+expression applier / range map
│       ├── controller/           AvatarController imperative API (reference: xlunar) + AvatarConfig resource list
│       └── math/                 Vec3 / Quat / Mat4 (three.js semantics, zero-dependency)
├── vrm-adapter/                  Android library (Filament/SceneView binding layer, sits on top of vrm-core)
│   └── src/main/java/dev/vrm/runtime/adapter/
│       ├── AvatarEngineController.kt    engine controller (implements AvatarBinding + lip-sync)
│       ├── AvatarRenderer.kt            adapter main entry (loadModel / applyStage / update)
│       ├── StageConfig.kt              xlunar-style lighting/scene config
│       ├── VrmAnimationPlayer.kt       VRMA player
│       └── filament/                   FilamentNodeTransformStore / SpringBoneStore / ExpressionBindProvider
└── app/                     demo (Compose + SceneView/Filament, depends only on :vrm-adapter)
    └── src/main/java/dev/vrm/runtime/demo/
        ├── VrmDemoScreen.kt          xlunar style: left viewport + right tab control panel
        ├── DemoAssets.kt             model/VRMA/expression resource list
        └── MainActivity.kt
```

## Environment

- JDK 17, Android API 26+
- Kotlin 2.2.x, AGP 8.12.x, Gradle 8.13
- Rendering: SceneView 2.3.3 (bundles Filament 1.68.2)
- JSON: kotlinx.serialization 1.7.3
- Testing: JUnit5 + the official Seed-san.vrm fixture

## Integration guide (library usage)

### 0. Dependency coordinates (Maven publishable)

`vrm-core` ships with `maven-publish` configured, coordinates `dev.vrm.runtime:vrm-core:1.0.0`:

```bash
./gradlew :vrm-core:publishToMavenLocal     # publish to the local ~/.m2
```

Host projects (JVM / Android) depend on:

```kotlin
// settings.gradle.kts must be able to reach mavenCentral + mavenLocal()
implementation("dev.vrm.runtime:vrm-core:1.0.0")
```

The publication is jar + pom + Gradle module metadata, generated automatically by
`publishing { MavenPublication("maven") }`; to push to a remote repository (e.g. Maven
Central) just add the repository under `publishing.repositories` and configure signing as
needed.

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

Most models such as Seed-san use the `expression` type of LookAt (driving the lookUp/Down/
Left/Right expressions); the `bone` type rotates the leftEye/rightEye bones directly.

### 4. Spring bones

```kotlin
import dev.vrm.runtime.core.springbone.SpringBoneLoader

val springManager = SpringBoneLoader(vrm.gltf, vrm.springBone!!, springStore).load()
// every frame:
springManager.update(deltaSeconds)
```

### 5. Play VRMA animation (cross-model retargeting)

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

The `VrmAnimationPlayer` in the demo is a minimal implementation that plays these tracks.

### 6. Math library

`core/math` provides three.js-semantics `Vec3` / `Quat` / `Mat4` (mutable objects, `copy()`
snapshots), zero-dependency. All internal VRM semantics run on top of it.

### 7. AvatarController (imperative control layer, reference: xlunar-ai-avatar)

`core/controller` wraps operations like "model selection / animation switching / expression
driving" into an engine-agnostic imperative API; the demo only needs to implement the
`AvatarBinding` interface to drive it:

```kotlin
import dev.vrm.runtime.core.controller.*

// 1. the host implements AvatarBinding (VrmController in the demo already does, wired to Filament)
val binding: AvatarBinding = myController

// 2. configure the resource list (models/animations/expressions/poses)
val config = AvatarConfig(
    models = listOf(ModelPreset("seed", "Seed-san", "avatars/Seed-san.vrm")),
    animations = listOf(AnimationPreset("wave", "Wave", "animations/wave.vrma")),
)

// 3. create the controller
val avatar = AvatarController(binding, config)

// 4. drive imperatively
avatar.setModel("B");            // switch model
avatar.playVrma("wave");         // play animation (by app id or raw source)
avatar.setExpression("happy");   // expression
avatar.execute(AvatarCommand.ResetExpression)
avatar.batch(listOf(...))        // several at once
avatar.queue(listOf(...))        // sequential queue (supports Wait)
avatar.on(AvatarEventType.STATE_CHANGE) { event -> ... }   // subscribe to events
avatar.onVrmaComplete()          // host calls back when the player finishes
```

Command types (`AvatarCommand`): SetPose / SetHandGesture / SetBodyGesture /
SetBodyMotion / SetExpression / PlayVrma / SetSequence / Wait / Reset /
ResetPose / ResetExpression / StopVrma / StopSequence / RawPose / RawExpression.

## demo app

> 🎬 **Demo video**: [`demo/demophone_effect_10s.mp4`](demo/demophone_effect_10s.mp4)

The `app` module is a fully runnable Compose demo:

- **Model picker**: switch among the 42 VRM 1.0 models synced from `public/1.0`
  (`avatars/` 8 samples + `models/` 34 characters)
- **Pose**: static human poses, hand-gesture aliases, body-motion entry points, reset pose
- **Combos**: multi-step pose + expression choreography (friendly greeting / thinking
  eureka / explaining, etc.)
- **VRMA**: 20 VRMAs, default loop per source file, manual loop toggle, stop playback
- **Face**: per-item 0..1 sliders for the standard VRM expressions
  (happy / sad / mouth / blink / lookAt)
- **Scene**: ambient light, directional light, camera distance, auto-lookAt toggle
- **Expression panel** (bottom right): preset expression sliders driving morph weights live
- **Spring-bone toggle** / **LookAt follow**: hair/skirt physics, gaze tracking
- **VRMA play/pause** + model name/state display

> Demo assets mirror the `public/1.0` directory of
> [xlunar-ai-avatar](https://github.com/iamenahs/xlunar-ai-avatar):
> `app/src/main/assets/avatars/` (8 sample VRMs), `models/` (34 character VRMs),
> `animations/` (20 VRMAs). The source keys in `DemoAssets` stay consistent with these three
> directories.

> ⚠️ **Environment maps are required assets**: the demo depends on
> `assets/environments/neutral/neutral_ibl.ktx` + `neutral_skybox.ktx` to provide IBL
> ambient light. SceneView's `createKTX1Environment(iblAssetFile=…)` reads from the **app's
> own assets** (not the library's assets). If these two files are missing the scene has no
> ambient light and **models render black**.
> The files come from `assets/environments/neutral/` inside the SceneView 2.3.3 AAR
> (`sh.txt` records their origin).
> You may also add a `LightNode` (Directional) to brighten the model.

> ⚠️ **The car head-unit landscape must be declared**: `MainActivity`'s
> `android:screenOrientation` in the AndroidManifest must be `landscape` (or unspecified).
> If written as `portrait`, on a 1920x720 landscape head-unit the system will **letterbox it
> into a narrow 270px strip on the right** (window bounds=[825,0][1095,720], the ComposeView
> squeezed to 0x0-0x48), appearing as "the model is visible but the entire UI panel has
> disappeared". Changing it to `landscape` restores fullscreen.

Build: `./gradlew :app:assembleDebug`, the APK is at `app/build/outputs/apk/debug/`.

## Tests

```bash
./gradlew :vrm-core:test --console=plain
```

95 tests all green (17 test classes), covering:

| Module | Test class | Count |
|---|---|---|
| glb + VRM data layer | VrmLoadingTest (full-field parsing of Seed-san) | 4 |
| Humanoid | VRMHumanoidTest (bone mapping / required validation / normalized rig) | 4 |
| Humanoid | TwistHumanoidMapTest | 1 |
| Humanoid | UpdateRestRoundtripTest (rest-pose round-trip after pose write-back) | 1 |
| Expressions | ExpressionManagerTest (preset parsing / clamping / override groups / morph writes) | 16 |
| Expressions | MaterialColorBindTest (materialColorBinds -> baseColor) | 2 |
| VRMA | VrmAnimationLoaderTest (synthesized VRMA parsing + retargeting to Seed-san) | 4 |
| VRMA | RealVrmaDiagnosticTest (channel diagnostics on a real test.vrma) | 1 |
| VRMA | TwistArmPoseTest (arm world positions during a spin) | 1 |
| SpringBone | SpringBoneColliderTest (sphere/capsule exact expected values) | 6 |
| SpringBone | SpringBoneTest (Verlet droop / bone-length conservation) | 4 |
| MToon | MtoonTest (10-material parsing / parameters / renderOrder) | 6 |
| LookAt | LookAtTest (range map / angle utilities / applier signs / loader / behavior) | 18 |
| Controller | AvatarControllerTest (command queue / events / state / batch) | 13 |
| Guards | VrmLoaderGuardTest (empty / non-GLB / VRM 0.x input) | 6 |
| Guards | NodeTransformStoreGuardTest (out-of-range node index) | 6 |
| math | Vec3ApiTest (three.js-semantics API) | 2 |

## Implemented features

- [x] GLB parsing (header + JSON/BIN chunks)
- [x] VRMC_vrm: humanoid (52 standard bones, 17 required validation), expressions
  (18 presets + custom), lookAt, meta, firstPerson data classes
- [x] VRMC_springBone: collider (sphere/capsule), joint, group, center
- [x] VRMC_materials_mtoon: parameter model (shading factors / texture index / outline / renderOrder)
- [x] VRMC_vrm_animation (.vrma): humanoid / expressions / lookAt tracks + retargeting clip
- [x] Humanoid: raw rig read/write / normalized rig / rest-pose capture
- [x] Expressions: weight clamping / isBinary / override groups (blink/lookAt/mouth) / morph+material+UV binds
- [x] SpringBone: Verlet integration / stiffness / gravity / drag / collision ejection / depth sorting
- [x] MToon: sRGB opt-in conversion, shouldGenerateOutline, renderOrder
- [x] LookAt: yaw/pitch / bone + expression appliers / range map / faceFront
- [x] demo app: expression panel + spring toggle + LookAt follow + VRMA playback

## Known unsupported items / limitations

**Feature level**
- Supports only **VRM 1.0** (including 1.0-beta); no VRM 0.x compatibility.
- Reads VRM only, never writes; no editor features.
- MToon is a parameter model with approximate restoration, not 100% pixel-level (three-vrm's
  rendering is the behavioral baseline); the render layer swaps gltfio's default PBR for
  matc-precompiled `mtoon_{opaque,masked,transparent}.filamat` (unlit + manual cel/toon
  lighting), choosing the blending variant per-material by alphaMode. Approximation scope:
  only baseColor / shade colors + shadingShift/Toony + one-way light are mapped; MToon
  exclusive channels such as rim / matcap / outline / UV animation are not implemented
  (parsed but unused by the shader).
- No VRMA editing; runtime playback only.
- glTF constraints (constraint / KHR extensions) are not parsed; VRMC_node_constraint is not
  implemented.

**Engine integration level (the demo's Filament binding)**
- **PNG textures are supported on the MToon path but still unsupported on gltfio's default
  path (resolved)**: gltfio 1.68's `ResourceLoader` has no `addTextureProvider` and
  filament-utils has no ImageDecoder, so VRMs with `image/png` textures (e.g. Seed-san)
  still render as flat gray through the default gltfio path. But the demo's MToon path
  (`MToonMaterialApplier`) implements its own channel that extracts PNGs from the GLB BIN
  chunk → decodes via BitmapFactory → builds Filament textures, so PNG textures color
  correctly once MToon is enabled.
- `FilamentNodeTransformStore.parentNodeIndex` is fixed: it now builds a nodeIndex→parent
  mapping from the glTF nodes, so `parentNodeIndex` returns the true parent-child
  relationship (this fixed parent world-rotation compensation for bone-type LookAt /
  springbone center / normalized rig).
- The VRMA lookAt track (`lookAt.quaternion`) is consumed in `VrmAnimationPlayer`: it
  samples the world-space gaze quaternion → converts to yaw/pitch → applies to the VrmLookAt
  controller (takes effect when lookAt is passed to `VrmAnimationPlayer.apply`).
- Expression material-color / UV-transform binds are implemented per material property in
  the demo's `FilamentExpressionBindProvider`; if a material property is not declared in the
  shader, that bind is skipped.
- Spring-bone collision is currently approximated with three spheres (capsule = two points +
  radius), consistent with three-vrm's behavior.
- **VRoid/xlunar model skeleton root-chain problem (resolved)**: early gltfio computed the
  joint world matrices incorrectly for the "standalone skeleton root + single shared skin"
  structure (VRoid's `Root(90° rotation)→Global→Position→Hips` was wrongly merged → hips
  world y≈0.08, legs inverted/crossed). **Fixed by "baking the VRMA into the model GLB +
  driving with gltfio's Animator"**: animations/poses bake the retargeted bone local
  rotations into an in-model glTF animation and drive skinning via
  `Animator.applyAnimation` + `updateBoneMatrices`; the VRoid leg-crossing disappears.
  **08-24 correction**: on-device testing proved **driving bones directly via TransformManager
  is also an officially supported path** (`AnimatorImpl::updateBoneMatrices` reads back joint
  world transforms from TransformManager, see the PROGRESS 08-24 section); the earlier "has
  no effect on gltfio skinning" conclusion was a misdiagnosis caused by a NO-OP store + a
  wrong entity index. The live-bone path (write joint local matrices → updateBoneMatrices)
  has been validated on-device with a single head-yaw drive source. Baking remains the
  reliable channel for clip playback, but live driving is a lightweight alternative that can
  drive pose/animation/spring-bone/gaze all in real time.
- **Directional Light has no effect on MToon/cel materials (correct behavior)**: VRoid /
  character materials are all `KHR_materials_unlit` / MToon cel materials, which **do not
  respond to directional light** (a property of cel rendering; the same is true in
  xlunar/three-vrm). The Scene tab's ambient + directional sliders are currently **combined
  into the ambient (IndirectLight) total brightness** to control image exposure: `total
  brightness = ambient*400k + dir*250k`. For directional light to take effect the materials
  would have to be switched to PBR (which would break the cel look).
- **lookAt (gaze) is limited in the baked-animation mode**: VRM lookAt has bone-type and
  expression-type. twist is bone-type (rotates head/eye bones). In the baked scheme the
  **Animator takes over all bones every frame, so real-time bone rotation from lookAt cannot
  be layered**, therefore lookAt does not take effect while an animation/POSE is playing (in
  a pure T-pose without animation it also does not take effect because TransformManager has
  no effect on gltfio skinning). This is an inherent limitation of the baking architecture;
  an independent bone channel would be needed for real-time gaze.
- **COMBOS (sequence choreography) is temporarily disabled**: sequentially executing
  `loadPose` (bake + reload the model each time) in a sequence triggers a native SIGSEGV in
  gltfio `Animator::applyAnimation` (it accesses a destroyed Animator during the reload
  window). `applyLoaded` was changed to "create new before destroying old" to mitigate, but
  burst sequencing is still unstable, so it is disabled.
- **Repeated animation-switch model rebuild race (root-caused, multi-clip singleton
  renderer)**: `playAnimation` through `loadAnimation` re-baked a 14MB VRMA → created a new
  Filament `ModelNode` + `AvatarEngineController` → destroyed the old one **every time**. A
  single simulation rebuilt the controller 8 times and baked 24 times; the high-frequency
  destroy/rebuild caused: main-thread GC + Filament resource corruption, a transient loss of
  the hair rest-lock during the rebuild window (hair "laser-blasting/covering the face"),
  and occasional native SIGSEGV from concurrent Animator writes. **Solution (08-24: switched
  to live driving)**: no more baking. `AvatarRenderer.loadModel` loads a plain GLB; all
  animation/pose/combos go through the real-time VRMA player + writing bones via
  `TransformManager` + driving via `updateBoneMatrices()` (the live channel, see the 08-24
  correction above). No GLB rewriting, no ModelNode rebuild, no reload window, and
  inherently no SIGSEGV. The baking channel (`VrmaBake` / `loadModelWithClips` /
  `switchClip`) has been removed.


- **VRoid hair "covering the face" root cause: lookAt keeps turning the head + baked
  hair-lock only locks local rest**.
  The baked clip contains only humanoid bones; the springbone joints (VRoid_B's 47
  HairJoints) are not in the clip; each HairJoint's **rest local rotation** has been baked
  into a constant track (`hairLock_*`), so strands are no longer "flung into lasers" by large
  rotations. But HairJoints are children of `J_Bip_C_Head`, so **when the head rotates the
  strands still inherit the head's world direction** — demoPhone's default-on lookAt drives
  the head in a circular sway every frame (`target = head + 0.35·sin / 0.2·cos / +0.6`), so
  strands intermittently cover the face as the head sways. **demoPhone currently defaults to
  lookAt off (`PhoneViewModel.lookAtEnabled=false`) to avoid the face-covering**; to turn it
  on, a separate bone channel outside the baking path would be needed to truly "lock the
  strands' world direction".

**Performance / platform**
- All Filament JNI calls must be on the main thread; model/VRMA parsing should go through an
  IO dispatcher.
- The library itself does not depend on Compose; the demo uses Compose, and the library is a
  pure Kotlin API.

## License

This library is licensed under the **MIT License**. See the [LICENSE](LICENSE) file.

### Third-party notice

Significant portions of `vrm-core` (humanoid, expressions, lookAt, spring-bone
physics, MToon parameter model, VRMA retargeting, math) are ports of
[three-vrm](https://github.com/pixiv/three-vrm), which is distributed under
the MIT License (Copyright (c) 2019-2026 pixiv Inc.). Per MIT terms, that
copyright notice and this permission notice are preserved in the source
(see the `Port of three-vrm...` doc comments).

The `vrm-adapter` (Filament/SceneView binding) and `app` (demo) are original
work built on top of vrm-core, and inherit the same MIT license.

The imperative avatar control layer (`AvatarController` / `AvatarCommand` /
`AvatarConfig` / `AvatarBinding`) and the demo's stage/pose/gesture/combo
presets are ports of
[xlunar-ai-avatar](https://github.com/iamenahs/xlunar-ai-avatar), which is
distributed under the MIT License (Copyright (c) 2024 VaultX.technology).
Per MIT terms, that copyright notice is preserved in the source
(see the `Port of xlunar...` doc comments).

### Model assets

This library bundles no model assets. The demo's sample models (`Seed-san.vrm`
from the VRM Consortium, `test.vrma` from three-vrm examples) belong to their
respective authors and are included for demonstration only — check each asset's
own license before commercial use.
