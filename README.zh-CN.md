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
├── vrm-core/                    纯 Kotlin JVM 库（maven 可发布）
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
├── vrm-adapter/                 Android 库（Filament/SceneView 绑定层，位于 vrm-core 之上）
│   └── src/main/java/dev/vrm/runtime/adapter/
│       ├── AvatarEngineController.kt   引擎控制器（实现 AvatarBinding + 口型）
│       ├── AvatarRenderer.kt           adapter 主入口（loadModel / applyStage / update）
│       ├── StageConfig.kt              xlunar 风格光照/场景配置
│       ├── VrmAnimationPlayer.kt       VRMA 播放器
│       └── filament/                   FilamentNodeTransformStore / SpringBoneStore / ExpressionBindProvider
└── app/                         demo（Compose + SceneView/Filament，只依赖 :vrm-adapter）
    └── src/main/java/dev/vrm/runtime/demo/
        ├── VrmDemoScreen.kt            xlunar 风格：左视口 + 右 tab 控制面板
        ├── DemoAssets.kt               模型/VRMA/表情资源清单
        └── MainActivity.kt
```

## 环境

- JDK 17，Android API 26+
- Kotlin 2.2.x，AGP 8.12.x，Gradle 8.13
- 渲染：SceneView 2.3.3（内置 Filament 1.68.2）
- JSON：kotlinx.serialization 1.7.3
- 测试：JUnit5 + 官方 Seed-san.vrm fixture

## 接入指南（库使用）

### 0. 依赖坐标（maven 可发布）

`vrm-core` 已配置 `maven-publish`，坐标 `dev.vrm.runtime:vrm-core:1.0.0`：

```bash
./gradlew :vrm-core:publishToMavenLocal     # 发布到本地 ~/.m2
```

宿主工程（JVM / Android）依赖：

```kotlin
// settings.gradle.kts 需能访问 mavenCentral + mavenLocal()
implementation("dev.vrm.runtime:vrm-core:1.0.0")
```

发布结构：jar + pom + Gradle module metadata 由 `publishing { MavenPublication("maven") }`
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

`app` 模块是完整可运行的 Compose demo：

- **模型选择器**：切换 `public/1.0` 同步的 42 个 VRM 1.0 模型（`avatars/` 8 个样例 + `models/` 34 个角色）
- **Pose**：静态人体姿态、手势别名、身体动作入口、重置姿态
- **Combos**：多步姿态 + 表情编排（friendly greeting / thinking eureka / explaining 等）
- **VRMA**：20 个 VRMA、按源文件默认 loop、手动 loop 开关、停止播放
- **Face**：标准 VRM 表情逐项 0..1 滑杆（happy / sad / mouth / blink / lookAt）
- **Scene**：环境光、方向光、相机距离、自动 LookAt 开关
- **表情面板**（右下）：preset 表情 slider 实时驱动 morph 权重
- **弹簧骨骼开关** / **LookAt 跟随**：头发/裙摆物理、视线跟踪
- **VRMA 播放/暂停** + 模型名/状态显示

> Demo 资源同步自 [xlunar-ai-avatar](https://github.com/iamenahs/xlunar-ai-avatar)
> 的 `public/1.0` 目录：`app/src/main/assets/avatars/`（8 个样例 VRM）、
> `models/`（34 个角色 VRM）、`animations/`（20 个 VRMA）。`DemoAssets` 中的
> source key 与这三个目录保持一致。

> ⚠️ **环境贴图是必要资产**：demo 依赖 `assets/environments/neutral/neutral_ibl.ktx` + `neutral_skybox.ktx`
> 提供 IBL 环境光。SceneView 的 `createKTX1Environment(iblAssetFile=…)` 是从 **app 自己的 assets**
> 读取（不是库 assets）。缺少这两个文件时场景无环境光，**模型会渲染成黑色**。
> 文件取自 SceneView 2.3.3 AAR 内的 `assets/environments/neutral/`（`sh.txt` 记录了来源）。
> 另外场景可加一个 `LightNode`（Directional）提亮模型。

> ⚠️ **车机横屏必须声明 landscape**：AndroidManifest 里 `MainActivity` 的 `android:screenOrientation`
> 必须为 `landscape`（或 unspecified）。若写成 `portrait`，在 1920x720 横屏车机上会被系统
> **letterbox 到右侧 270px 窄条**（窗口 bounds=[825,0][1095,720]、ComposeView 压成 0x0-0x48），
> 表现为"模型能看见但 UI 面板全部消失"。改成 landscape 后全屏正常。

构建：`./gradlew :app:assembleDebug`，APK 在 `app/build/outputs/apk/debug/`。

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
- [x] MToon：sRGB opt-in 转换、shouldGenerateOutline、renderOrder
- [x] LookAt：yaw/pitch / bone + expression applier / range map / faceFront
- [x] demo app：表情面板 + 弹簧开关 + LookAt 跟随 + VRMA 播放

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
- 弹簧骨骼碰撞目前按三个球体近似（capsule 用两点 + 半径），与 three-vrm 行为一致。
- **VRoid/xlunar 模型骨架根链问题（已解决）**：早期 gltfio 对"独立骨架根 + 单共享
  skin"结构的关节世界矩阵计算错误（VRoid 骨架根 `Root(90° 旋转)→Global→Position→Hips`
  被错误归并 → hips 世界 y≈0.08、腿部倒立交叉）。**已通过"烘焙 VRMA 进模型 GLB + gltfio Animator 驱动"解决**：动画/姿态把重定向后的骨骼局部旋转烘焙成模型内嵌 glTF 动画，用 `Animator.applyAnimation` + `updateBoneMatrices` 驱动蒙皮，VRoid 腿交叉消失。
  后续实测证实 **TransformManager 直接驱动骨骼也是官方支持的通道**（`AnimatorImpl::updateBoneMatrices`
  从 TransformManager 读回关节世界变换），此前"对 gltfio 蒙皮无效"的结论是 NO-OP store +
  错误实体索引的误判。live-bone 路径（写关节局部矩阵 → updateBoneMatrices）已用头 yaw
  单驱动源在设备上验证生效。烘焙仍是 clip 播放的可靠通道，但 live 驱动是轻量替代，
  可将姿态/动画/弹簧/视线全部实时驱动。
- **Directional Light 对 MToon/卡通材质无效（正确行为）**：VRoid/角色模型的材质
  都是 `KHR_materials_unlit` / MToon 卡通材质，**不响应方向光**（这是卡通渲染的
  特性，xlunar/three-vrm 同样如此）。Scene tab 的 ambient + directional 两个滑块
  当前**合并为环境光（IndirectLight）总亮度**来控制画面明暗：`总亮度 =
  ambient*400k + dir*250k`。若要方向光真正生效需把材质改成 PBR（会破坏卡通效果）。
- **lookAt（视线跟随）在烘焙动画模式下受限**：VRM lookAt 分 bone 型和 expression
  型。twist 是 bone 型（转 head/eye 骨骼）。烘焙方案下 **Animator 每帧接管全部骨骼，
  实时 lookAt 的骨骼转动无法叠加**，因此 lookAt 在播放动画/POSE 时不生效（无动画的
  纯 T-pose 下同样因 TransformManager 对 gltfio 蒙皮无效而不生效）。这是烘焙架构的
  固有限制，需独立骨骼通道才能支持实时视线跟随。
- **COMBOS（序列编排）暂禁用**：序列中连续执行 `loadPose`（每次烘焙+reload 模型）
  会触发 gltfio `Animator::applyAnimation` native SIGSEGV（reload 窗口期访问已销毁的
  Animator）。`applyLoaded` 已改为"先建新后销毁旧"缓解，但序列级连发仍不稳，暂禁用。
- **反复切动画的模型重建竞态（已根治，多 clip 单例渲染器）**：`playAnimation`
  走 `loadAnimation` 时**每次**都重新烘焙 14MB VRMA→新建 Filament `ModelNode`+
  `AvatarEngineController`→销毁旧的。一次模拟 controller 重建 8 次、烘焙 24 次，
  高频销毁/重建导致：主线程 GC + Filament 资源错乱，重建窗口期头发 rest 锁定短暂
  丢失（头发"激光外射/盖脸"），Animator 并发写偶发 native SIGSEGV。**解法（已改为 live 驱动）**：不再烘焙。`AvatarRenderer.loadModel` 加载普通
  GLB，动画/姿态/组合全部经实时 VRMA 播放器 + `TransformManager` 写骨骼 +
  `updateBoneMatrices()` 驱动（live 通道，见上方修正）。无 GLB 重写、无
  ModelNode 重建、无 reload 窗口期，天然无 SIGSEGV。烘焙通道（`VrmaBake`/
  `loadModelWithClips`/`switchClip`）已删除。

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
