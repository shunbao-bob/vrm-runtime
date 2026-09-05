# vrm-android

[English](README.md) | **中文** | [日本語](README.ja.md)

纯 Kotlin 的 **VRM 1.0 解析与运行时库**（代号 vrm-android / vrmruntime），
让 Android 应用能加载 VRM 虚拟人模型并驱动其基础行为：骨骼映射、表情、
弹簧骨骼物理、VRMA 动画重定向、视线跟随（LookAt）。

渲染层基于 **Google Filament**（经 SceneView 的 Engine / ModelLoader 接入），
不依赖 Unity / 游戏引擎 / JNI 游戏框架。核心库 `:vrm-core` 为纯 Kotlin/JVM、
零 Android 依赖，可用 JUnit5 秒级测试。

移植蓝本：[three-vrm](https://github.com/pixiv/three-vrm)（TypeScript），
标准：[VRM 1.0 规范](https://github.com/vrm-c/vrm-specification)（重点为
`VRMC_vrm-1.0`、`VRMC_vrm_animation-1.0`、`VRMC_springBone-1.0`、
`VRMC_materials_mtoon-1.0`）。

## 工程结构

```
vrmruntime/
├── vrm-core/                    纯 Kotlin JVM 库（Maven 可发布）
│   └── src/main/kotlin/dev/vrm/runtime/core/
│       ├── gltf/                 GLB 解析 + glTF 2.0 数据类
│       ├── vrm/                  VRMC_vrm / VRMC_springBone / VRMC_materials_mtoon 数据类 + VrmLoader
│       ├── humanoid/             HumanBone 映射 / VRMRig / 归一化 rig / VRMHumanoid
│       ├── expression/           ExpressionManager / ExpressionLoader / 三类 bind
│       ├── vrma/                 VRMA 解析 / 重定向 clip 构建 / KeyframeTrack
│       ├── springbone/           Verlet 弹簧骨骼 / collider / manager / loader
│       ├── mtoon/                MToon 材质参数模型
│       ├── lookAt/               LookAt 控制器 / bone+expression applier / range map
│       ├── controller/           AvatarController 命令式 API（参考 xlunar）+ AvatarConfig 资源清单
│       └── math/                 Vec3 / Quat / Mat4（three.js 语义，零依赖）
├── vrm-character/               引擎无关的角色行为层（JVM：情绪、动作、口型、待机、漫游 AI、SceneConfig）
│   └── src/main/kotlin/dev/vrm/runtime/character/
├── vrm-adapter/                 Android 库（Filament/SceneView 绑定层，位于 vrm-core 之上）
│   └── src/main/java/dev/vrm/runtime/adapter/
│       ├── AvatarEngineController.kt   引擎控制器（实现 AvatarBinding + 口型）
│       ├── AvatarRenderer.kt           adapter 主入口（loadModel/loadModelAsync / applyStage / update）
│       ├── StageConfig.kt              光照/场景配置
│       ├── VrmAnimationPlayer.kt       实时 VRMA 播放器（TransformManager 写骨骼）
│       └── filament/                   FilamentNodeTransformStore / SpringBoneStore / MToonMaterialApplier
├── app/                         主横屏 demo（Compose + SceneView/Filament，依赖 :vrm-adapter）
│   └── src/main/java/dev/vrm/runtime/demo/
│       ├── VrmDemoScreen.kt            xlunar 风格：左视口 + 右 tab 控制面板
│       ├── DemoAssets.kt               模型/VRMA/表情资源清单
│       └── MainActivity.kt
└── demoPhone/                   手机竖屏 AI 虚拟人助理（Compose + SceneView）
    └── src/main/java/dev/vrm/runtime/phonephone/
        ├── PhoneDemoScreen.kt          竖屏 UI + 相机 + STT/TTS
        ├── PhoneViewModel.kt           LLM 对话 + 表情/手势/动画分发 + 口型
        ├── PhoneAssets.kt              手机端资产目录（模型 / 语音 / 环境）
        ├── brain/                      AssistantBrain + KeywordAssistantBrain
        └── voice/                      NUI（阿里云）STT/TTS + 播放门控
```

> 说明：`vrm-character` 是引擎无关的待机/动作层，两个 demo 共用；`demoPhone` 是
> 竖屏 AI 助理 app，因捆绑闭源阿里云 `nuisdk-release.aar` 仅保留在本地开发（不属于
> 对外发布/开源的模块集合）。

## 环境

- JDK 17，Android API 26+
- Kotlin 2.2.x，AGP 8.12.x，Gradle 8.13
- 渲染：SceneView 2.3.3（内置 Filament 1.68.2）
- JSON：kotlinx.serialization 1.7.3
- 测试：JUnit5 + 官方 Seed-san.vrm fixture

## 接入指南（库使用）

### 0. 依赖坐标（Maven 可发布）

三个库都配置了 `maven-publish`（groupId `dev.vrm.runtime`）：

| 模块       | 坐标                               | 类型 | 发布命令                                          |
|------------|------------------------------------|------|--------------------------------------------------|
| vrm-core   | `dev.vrm.runtime:vrm-core:1.0.0`     | jar  | `./gradlew :vrm-core:publishToMavenLocal`     |
| vrm-character | `dev.vrm.runtime:vrm-character:1.0.0` | jar | `./gradlew :vrm-character:publishToMavenLocal` |
| vrm-adapter| `dev.vrm.runtime:vrm-adapter:1.0.0`   | aar  | `./gradlew :vrm-adapter:publishToMavenLocal`   |

宿主工程（JVM / Android）依赖：

```kotlin
// settings.gradle.kts 需能访问 mavenCentral + mavenLocal()
implementation("dev.vrm.runtime:vrm-core:1.0.0")        // 纯引擎（JVM/Android）
implementation("dev.vrm.runtime:vrm-adapter:1.0.0")    // Filament/SceneView 绑定（Android）
implementation("dev.vrm.runtime:vrm-character:1.0.0")  // 角色行为层（JVM/Android）
```

发布结构：jar/aar + pom + Gradle module metadata 由 `publishing { MavenPublication(...) }`
自动生成；要推到远端仓库（如 Maven Central）只需在 `publishing.repositories` 加对应
仓库并按需配置签名。

### 1. 解析一个 VRM

```kotlin
import dev.vrm.runtime.core.vrm.VrmLoader

val bytes: ByteArray = ... // .glb / .vrm 文件字节
val vrm = VrmLoader.load(bytes)
// vrm.gltf          底层 glTF
// vrm.vrm           VRMC_vrm 扩展（humanoid/lookAt/expressions/meta）
// vrm.springBone    VRMC_springBone 扩展
// vrm.mtoon         material index -> VRMC_materials_mtoon
// vrm.vrmAnimation  VRMC_vrm_animation（.vrma 文件用）
```

### 2. 构建 Humanoid + 表情（需要提供 NodeTransformStore）

```kotlin
import dev.vrm.runtime.core.humanoid.VRMHumanoid
import dev.vrm.runtime.core.expression.ExpressionLoader

// store 抽象了渲染引擎的节点图；demo 里用 FilamentNodeTransformStore
val humanoid = VRMHumanoid.fromVrm(vrm.gltf, vrm.vrm!!, store)
val expressionManager = ExpressionLoader(bindProvider).load(vrm.gltf, vrm.vrm!!)
```

核心库引擎无关：`NodeTransformStore`（getLocal*/getWorldMatrix/parentNodeIndex）、
`ExpressionBindProvider`（morphTargetChannel/materialColorAccess/textureTransformAccess）
由宿主实现。

### 3. LookAt 视线跟随

```kotlin
import dev.vrm.runtime.core.lookAt.VrmLookAtLoader

val lookAt = VrmLookAtLoader(humanoid, expressionManager, store).load(vrm.vrm!!)
lookAt.target = Vec3(x, y, z)   // 世界坐标目标点
// 每帧：
lookAt.update(deltaSeconds)     // autoUpdate=true 时朝 target 转动并写入表情/骨骼
```

Seed-san 等大多数模型用 `expression` 类型 LookAt（驱动 lookUp/Down/Left/Right 表情）；
`bone` 类型会直接旋转 leftEye/rightEye 骨骼。

### 4. 弹簧骨骼

```kotlin
import dev.vrm.runtime.core.springbone.SpringBoneLoader

val springManager = SpringBoneLoader(vrm.gltf, vrm.springBone!!, springStore).load()
// 每帧：
springManager.update(deltaSeconds)
```

### 5. 播放 VRMA 动画（跨模型重定向）

```kotlin
import dev.vrm.runtime.core.vrma.VrmAnimationLoader
import dev.vrm.runtime.core.vrma.VRMAnimationClipBuilder

val vrma = VrmLoader.load(vrmaBytes)
val ext = vrma.vrmAnimation!!
val bin = vrma.binary?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
val animation = VrmAnimationLoader(vrma.gltf, bin, ext).loadAll().first()
val clip = VRMAnimationClipBuilder(animation, humanoid, expressionManager).build()
// clip.tracks 是引擎无关的 KeyframeTrack：
//   Normalized_<bone>.quaternion / .position
//   <expressionName>.weight
```

demo 里的 `VrmAnimationPlayer` 是播放这些 track 的最小实现。

### 6. 数学库

`core/math` 提供 three.js 语义的 `Vec3`/`Quat`/`Mat4`（可变对象、copy() 快照），
零依赖。内部所有 VRM 语义都跑在它上面。

### 7. AvatarController（命令式控制层，参考 xlunar-ai-avatar）

`core/controller` 把"模型选择 / 动画切换 / 表情驱动"等操作封装成引擎无关的
命令式 API，demo 只需实现 `AvatarBinding` 接口即可驱动：

```kotlin
import dev.vrm.runtime.core.controller.*

// 1. 宿主实现 AvatarBinding（demo 里 VrmController 已实现，对接 Filament）
val binding: AvatarBinding = myController

// 2. 配置资源清单（模型/动画/表情/姿态）
val config = AvatarConfig(
    models = listOf(ModelPreset("seed", "Seed-san", "avatars/Seed-san.vrm")),
    animations = listOf(AnimationPreset("wave", "Wave", "animations/wave.vrma")),
)

// 3. 创建控制器
val avatar = AvatarController(binding, config)

// 4. 命令式驱动
avatar.setModel("B");            // 切换模型
avatar.playVrma("wave");         // 播放动画（按 app id 或原始 source）
avatar.setExpression("happy");   // 表情
avatar.execute(AvatarCommand.ResetExpression)
avatar.batch(listOf(...))        // 同时多条
avatar.queue(listOf(...))        // 顺序队列（支持 Wait）
avatar.on(AvatarEventType.STATE_CHANGE) { event -> ... }   // 订阅事件
avatar.onVrmaComplete()          // 播放器结束后由宿主回调
```

命令类型（`AvatarCommand`）：SetPose / SetHandGesture / SetBodyGesture /
SetBodyMotion / SetExpression / PlayVrma / SetSequence / Wait / Reset /
ResetPose / ResetExpression / StopVrma / StopSequence / RawPose / RawExpression。

## demo app

`app` 模块是完整可运行的 Compose demo（横屏）：

- **模型选择器**：切换 42 个 VRM 1.0 模型（`avatars/` 8 个样例 + `models/` 34 个角色）
- **Pose**：静态人体姿态、手势别名、身体动作入口、重置姿态
- **Combos**：多步姿态 + 表情编排（friendly greeting / thinking eureka / explaining 等）
- **VRMA**：20 个 VRMA、实时播放（不烘焙 GLB）、按源文件 loop、手动 loop 开关、停止播放
- **Face**：标准 VRM 表情逐项 0..1 滑杆（happy / sad / mouth / blink / lookAt）
- **Scene**：环境光、方向光、相机距离、自动 LookAt 开关、环境切换（Studio / Ferndale / Brown）
- **弹簧骨骼开关** / **LookAt 跟随**：头发/裙摆物理、视线跟踪

所有动画/姿态/组合都走**实时 VRMA 驱动**（`VrmAnimationPlayer`）：每帧把骨骼局部旋转写入
`TransformManager`，用 `updateBoneMatrices()` 驱动蒙皮——**无 GLB 烘焙、无模型重建、
无 reload 竞态**。动画淡入/淡出平滑，播完自动回 natural relaxed 站姿（呼吸 + 随机眨眼
+ 微动作）；一次性 clip 播完缓动归位而不是冻结在末帧。

Demo 资产：`app/src/main/assets/avatars/`（8 个样例 VRM）、`models/`（34 个角色 VRM）、
`animations/`（20 个 VRMA）、`environments/`（3 组 IBL+skybox）。

> ⚠️ **环境贴图是必要资产**：demo 依赖 `assets/environments/*/*_ibl.ktx` + `*_skybox.ktx`
> （Studio / Ferndale Studio / Brown Photo Studio）。SceneView 的
> `createKTX1Environment(...)` 从 **app 自己的 assets** 读取（不是库 assets）。缺少时
> 场景无环境光，**模型会渲染成黑色**。
>
> ⚠️ 主 demo 为横屏；请按实际在 AndroidManifest 声明 `screenOrientation`（或 unspecified），
> 否则 ComposeView 可能被 letterbox 挤压。

构建：`./gradlew :app:assembleDebug`，APK 在 `app/build/outputs/apk/debug/`。

## demoPhone（AI 虚拟人助理）

基于同一 `vrm-adapter` 的**竖屏语音驱动助理 app**（Compose + SceneView）——语音对话
（阿里云 STT/TTS），虚拟人实时口型同步 + 表情 + 手势 + LLM 匹配的 VRMA 动画。

![demoPhone effect](demo/demophone_effect.gif)

亮点：

- **语音对话循环**：STT → AI 回复 → TTS（字幕帧驱动口型）。
- **LLM 表情/手势/动画协议**：system prompt 要求 LLM 从当前支持的预设集（表情、手势、
  20 个 VRMA）里选一个标准 `[表情:xxx]` / `[动作:xxx]` 标签；客户端在播报前剥掉标签并
  播放匹配的脸/手势/clip（LLM 不吐标签时也有正文关键词兜底识别）。
- **口型同步**基于阿里云字幕时间戳，以真实起播点为基准并加少量 lead，让嘴型对上语音。
- 完整 10s 效果录屏见 `demo/demophone_effect_10s.mp4`。

因捆绑闭源阿里云 `nuisdk-release.aar`，`demoPhone` 不属于对外发布/开源的模块集合
（仅本地开发保留）。

## 测试

```bash
./gradlew :vrm-core:test --console=plain
```

95 个测试全绿（17 个测试类），覆盖：

| 模块 | 测试类 | 数量 |
|---|---|---|
| glb + VRM 数据层 | VrmLoadingTest（Seed-san 全字段解析） | 4 |
| Humanoid | VRMHumanoidTest（骨映射 / required 校验 / 归一化 rig） | 4 |
| Humanoid | TwistHumanoidMapTest | 1 |
| Humanoid | UpdateRestRoundtripTest（姿势写回后的 rest 姿态往返一致性） | 1 |
| Expressions | ExpressionManagerTest（preset 解析 / 钳制 / override 组 / morph 写值） | 16 |
| Expressions | MaterialColorBindTest（materialColorBinds → baseColor） | 2 |
| VRMA | VrmAnimationLoaderTest（合成 VRMA 解析 + 重定向到 Seed-san） | 4 |
| VRMA | RealVrmaDiagnosticTest（真实 test.vrma 通道诊断） | 1 |
| VRMA | TwistArmPoseTest（旋转动画中的手臂世界坐标） | 1 |
| SpringBone | SpringBoneColliderTest（sphere/capsule 精确期望值） | 6 |
| SpringBone | SpringBoneTest（Verlet 下垂 / 骨长守恒） | 4 |
| MToon | MtoonTest（10 材质解析 / 参数 / renderOrder） | 6 |
| LookAt | LookAtTest（range map / 角度工具 / applier 符号 / loader / 行为） | 18 |
| Controller | AvatarControllerTest（命令队列 / 事件 / 状态 / batch） | 13 |
| Guards | VrmLoaderGuardTest（空输入 / 非 GLB / VRM 0.x） | 6 |
| Guards | NodeTransformStoreGuardTest（越界 node index） | 6 |
| math | Vec3ApiTest（three.js 语义 API） | 2 |

## 已实现特性

- [x] GLB 解析（头 + JSON/BIN chunk）
- [x] VRMC_vrm：humanoid（52 标准骨骼，17 required 校验）、expressions（18 preset + custom）、lookAt、meta、firstPerson 数据类
- [x] VRMC_springBone：collider（sphere/capsule）、joint、group、center
- [x] VRMC_materials_mtoon：参数模型（着色因子 / 纹理 index / outline / renderOrder）
- [x] VRMC_vrm_animation（.vrma）：humanoid / expressions / lookAt 轨道 + 重定向 clip
- [x] Humanoid：raw rig 读写 / 归一化 rig / rest 姿势捕获
- [x] Expressions：权重钳制 / isBinary / override 组（blink/lookAt/mouth）/ morph+材质+UV bind
- [x] SpringBone：Verlet 积分 / stiffness / gravity / drag / 碰撞推出 / 深度排序
- [x] MToon：sRGB opt-in 转换、renderOrder、Filament 卡通 shader
- [x] LookAt：yaw/pitch / bone + expression applier / range map / faceFront
- [x] 实时 VRMA 播放（TransformManager 写骨骼 + updateBoneMatrices） + 平滑待机/过渡
- [x] demoPhone：STT/TTS、LLM 标签解析、口型同步

## 已知不支持项 / 限制

**功能层面**
- 只支持 **VRM 1.0**（含 1.0-beta）；不做 VRM 0.x 兼容。
- 只读不写 VRM；无编辑器功能。
- MToon 为参数模型 + 近似还原，不做 100% 像素级还原（以 three-vrm 渲染为行为基准）；
  渲染层用 matc 预编译的 `mtoon_{opaque,masked,transparent}.filamat`（unlit + 手动
  cel/toon 光照）替换 gltfio 默认 PBR，按每个材质 alphaMode 选 blending 变体。
  近似实现：只映射 baseColor / shade 色 + shadingShift/Toony + 单向光，
  rim / matcap / outline / UV 动画等 MToon 专有通道未实现（解析了但 shader 未用）。
- 不做 VRMA 编辑，仅运行时播放。
- glTF 约束（constraint / KHR 扩展）不解析；VRMC_node_constraint 未实现。

**引擎接入层面（demo 的 Filament 绑定）**
- **PNG 贴图在 MToon 通道已支持、gltfio 默认路径仍不支持（已解决）**：gltfio 1.68 的
  `ResourceLoader` 无 `addTextureProvider`、filament-utils 无 ImageDecoder，所以
  `image/png` 贴图的 VRM（如 Seed-san）走默认 gltfio 渲染仍为素色/灰。但 demo 的
  MToon 路径（`MToonMaterialApplier`）已实现**从 GLB BIN chunk 提取 PNG → BitmapFactory
  解码 → Filament Texture** 的自有通道，开启 MToon 后 PNG 贴图可正常上色。
- `FilamentNodeTransformStore.parentNodeIndex` 已修复：传入 glTF nodes 构建 nodeIndex→parent 映射，
  `parentNodeIndex` 现在返回真实父子关系（修复了 bone 类型 LookAt / springbone center / 归一化 rig 的父世界旋转补偿）。
- VRMA 的 lookAt 轨道（`lookAt.quaternion`）在 `VrmAnimationPlayer` 中已消费：
  采样世界空间视线 quaternion → 转 yaw/pitch → 应用到 VrmLookAt 控制器
  （`VrmAnimationPlayer.apply`，lookAt 传入时生效）。
- 表情的材质颜色 / UV 变换 bind 在 demo 的 `FilamentExpressionBindProvider` 中
  按材质属性实现；若某材质属性未在 shader 中声明，则该 bind 被跳过。
- **VRoid/xlunar 模型骨架根链（已解决，live 驱动）**：早期 gltfio 对"独立骨架根 + 单共享 skin"
  结构的关节世界矩阵计算曾出错。现已改为 **live VRMA 驱动**——`AvatarRenderer.loadModel`
  加载普通 GLB，动画/姿态/组合全部经实时 VRMA 播放器 + `TransformManager` 写骨骼 +
  `updateBoneMatrices()` 驱动（官方支持的通道，`AnimatorImpl::updateBoneMatrices` 会从
  TransformManager 读回关节世界变换）。**无 GLB 烘焙、无 ModelNode 重建、无 reload
  窗口期**，从头 yaw 单驱动源到完整 VRMA 动画均已在真机验证。烘焙通道（`VrmaBake`/
  `loadModelWithClips`/`switchClip`）已删除。
- **Directional Light 对 MToon/卡通材质无效（正确行为）**：VRoid/角色模型的材质
  都是 `KHR_materials_unlit` / MToon 卡通材质，**不响应方向光**（这是卡通渲染的
  特性，xlunar/three-vrm 同样如此）。Scene tab 的 ambient + directional 两个滑块
  当前**合并为环境光（IndirectLight）总亮度**来控制画面明暗：`总亮度 =
  ambient*400k + dir*250k`。若要方向光真正生效需把材质改成 PBR（会破坏卡通效果）。
- **实时 lookAt 在 live 路径已正常**：live 播放器里 lookAt 每帧把世界视线 quaternion
  转 yaw/pitch 写入 VrmLookAt，实时生效（不再有烘焙模式下 Animator 接管骨骼的固有限制）。
- 弹簧骨骼碰撞按三个球体近似（capsule 用两点 + 半径），与 three-vrm 行为一致。

## 切模型后 pose 混乱：根因与方案

### 现象
- **首次加载**任意模型 → 姿态正确（gltfio 的 bind pose，作者烘焙的站姿）。
- **切换一次模型**（主 demo 的模型选择器或 `dev.vrm.runtime.demo.SWITCH` 广播）→ **新模型的静止姿态歪曲/乱套**（手/腿非自然，尤其大腿、手臂明显）；但 face 大方向、且**播放该模型的 VRMA 动画时姿态又正常**。

### 根因（不是"写入失效"，是"identity NLR 写回模型 rest local"）
主 demo 切换模型入口原先在 `loadModel(新模型)` 之后执行 `avatar.execute(AvatarCommand.Reset)`：

- `Reset` → `AvatarEngineController.reset()` → `humanoid.resetNormalizedPose()`（把 55 个**归一化 rig 骨骼的 quaternion 全置 identity**）→ `humanoid.update()`。
- `update()` 用 VRM 1.0 归一化重定向公式写回骨骼局部旋转：`PoseForB = L · W⁻¹ · NLR · W`。当 **NLR = identity** 时退化为 `L · W⁻¹ · W = L`，即把每个骨骼的局部旋转写回 **GLB 里的 rest local rotation**。
- 问题：**VRoid / xlunar 这类模型的 rest 是 A-pose，rest local 带大旋转**（实测 VRoid_Sample_B/C 的 `J_Bip_L_UpperLeg` rest local 绕 X ~170°，quat≈(0.996,-0.03,-0.026,0.08)）；而 gltfio 的蒙皮/joint 父链是它自己重算的 bind 树，**与 GLB rest 的父链不一致**。把"GLB rest local"挂到 "gltfio bind 父链"上 → 腿部/手臂的 world 朝向就偏了，视觉上"乱套"。

日志佐证（`AvatarEngine`）：
- 切换后 `DIAG initial-rot-BEFORE-reset: leftUpperLeg=(1.00,-0.03,-0.03,0.08)` —— **正是 GLB rest local**（与 `glb_inspect`/单测读出的 rest 值完全一致）。
- 但 `DIAG world:` 里 hips/feet 的**世界坐标一直正常**（脚在 y=0.14，站立合理）—— 因为 DIAG 读的是 headless `liveStore`（GLB 数学），读不到 gltfio 真实渲染值。
- `leftUpperLeg` 对父（hips）的世界旋转极敏感（L 绕 X 170°），父链偏差被放大成视觉大偏移；而 arm 的 rest L 接近 identity，对父旋转不敏感，所以手看着还行。这解释了"为什么腿歪最明显"。
- 播放 VRMA 时每帧用**真实的非身份 NLR** 覆盖写（重定向后的实际姿态），即使某条父链有误差也只是叠加成相对旋转，视觉被"压住"→ 动画正常。

**结论：`avatar.reset()`/`resetNormalizedPose()` 对 VRoid 这类"非 T-pose rest + 大局部旋转"的模型是**危险的**：它把骨骼强制写回 GLB rest local，一旦 gltfio 父链与 GLB 不一致就整个骨架歪。首次加载不 reset（骨骼保持 gltfio bind pose）所以对。

### 当前采用的方案（推荐，已生效）
**主 demo 切换模型的入口不再调 `AvatarCommand.Reset`，保持 gltfio bind pose（同首次加载）。**
- 改 `app/.../VrmDemoScreen.kt` 两处（`SWITCH` 广播分支 + UI 模型 Tab 点击分支），删掉 `renderer.avatar?.execute(AvatarCommand.Reset)`。
- 效果：切模型后骨骼停留在 gltfio 的 bind pose（首次加载那样），站姿自然；若切换前有 VRMA，切模型会保留当前动画继续播，姿态也正常。
- 验证：装机后切换 VRoid B/C / Seed-san 等，静止姿态正常；logcat 无 `EVENT reset`。

> 说明：人工"Reset 姿态"按钮（`onReset`，走 `avatar?.reset()`）仍保留，它是用户显式触发的，不属于切模型入口，不受本次改动影响。

### 可选的其他方案（为什么暂不采用）
1. **切换后用 `SetPose("relaxed")` 而非 Reset**：`relaxed` 是明确的手臂/站姿数值（spine + left/rightUpperArm rotZ ±78），走 **normalized 通道显式覆盖**，而不是"写回各模型 rest local"。它从不触发"写回 GLB rest local"这条坑路。主 demo 因为希望"切完保留各模型作者站姿"而选了上文"当前采用的方案"（保持 bind pose），故未用它。
   - 优点：统一站姿、可跨模型一致；缺点：失去各模型作者的原站姿差异，且 `relaxed` 只控制上/躯干，不控制腿（腿部仍靠 bind）。
2. **把 `resetNormalizedPose` 真正语义改为"归一化 T-pose"**：让 identity NLR 写回的是归一化 T-pose 而非 GLB rest local。这需要改造 `VRMHumanoidRig.update()` 的 rest 补偿（如不让 `L·W⁻¹·N·W` 在 N=I 时回落成 L，而让 normalized rig 的 rest 就是 T-pose）。这是更复杂的引擎层修改，且与 three-vrm 的"consistent pose 映射回 rest"语义冲突，风险高、收益低（不解决跨模型的不一致），未采用。
3. **根链对齐修复**：让 gltfio 与 GLB 的**父链/根链一致**（修 `Root→Global→Position→Hips` 链的施加），这样写回 rest local 也能正确呈现。这是最"治本"的方案，但需要在 gltfio 层修正骨架根合并，工程量大且属引擎层改动，暂未做（当前方案已满足需求）。

---

- **VRoid 头发"盖脸"根因：lookAt 持续转头 + 烘焙 hair-lock 只锁局部 rest**。
  烘焙 clip 只含 humanoid 骨，springbone 关节（VRoid_B 的 47 根 HairJoint）不在 clip
  内；已把每个 HairJoint 的 **rest 局部旋转** 烘成恒定 track（`hairLock_*`），发丝
  不再被大旋转"甩成激光"。但 HairJoint 是 `J_Bip_C_Head` 的子级，**head 旋转时发丝
  仍继承 head 世界方向** —— lookAt 每帧驱动 head 做圆周摆动
  （`target = head + 0.35·sin / 0.2·cos / +0.6`），发丝随 head 摆动间歇盖脸。
  当前默认关闭 lookAt 规避盖脸；若要开启，需在烘焙路径外提供独立骨骼通道才能让
  发丝真正"锁世界方向"。

**性能 / 平台**
- 所有 Filament JNI 调用必须在主线程；模型/VRMA 解析建议走 IO dispatcher。
- 库本身不依赖 Compose；demo 用 Compose，库是纯 Kotlin API。

## License

本库基于 **MIT License** 发布。详见 [LICENSE](LICENSE) 文件。

### 第三方致谢

`vrm-core` 的重要部分（humanoid、expressions、lookAt、spring-bone 物理、
MToon 参数模型、VRMA 重定向、math）移植自
[three-vrm](https://github.com/pixiv/three-vrm)，后者以 MIT License
（Copyright (c) 2019-2026 pixiv Inc.）分发。按 MIT 条款，其版权声明与本
许可声明保留在源码中（见 `Port of three-vrm...` 注释）。

`vrm-adapter`（Filament/SceneView 绑定层）与 `app`（demo）是基于 vrm-core
的原创作品，继承相同的 MIT License。

命令式角色控制层（`AvatarController` / `AvatarCommand` / `AvatarConfig` /
`AvatarBinding`）与 demo 的 stage/pose/gesture/combo 预设移植自
[xlunar-ai-avatar](https://github.com/iamenahs/xlunar-ai-avatar)，后者以
MIT License（Copyright (c) 2024 VaultX.technology）分发。按 MIT 条款，
其版权声明保留在源码中（见 `Port of xlunar...` 注释）。

### 模型资产

本库不捆绑任何模型资产。demo 的示例模型（VRM Consortium 的 `Seed-san.vrm`、
three-vrm 示例的 `test.vrma`）归原作者所有，仅用于演示 —— 商业使用前请
查阅每个资产的自身许可证。
