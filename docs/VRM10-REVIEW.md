# VRM 1.0 运行时库 — 代码 Review 结论

> 更新约定：每次修改代码库后，按优先级重新核对本文件，更新「待办状态」与「更新记录」。先高优先级后低优先级。

## 最近一次 Review

- 日期：2026-08-30
- 结论概览：核心架构扎实，83 个单测全绿（强制重跑确认，15 个测试类）。VRM 1.0 主干（humanoid / expressions / lookAt / springBone 算法 / vrma 重定向 / mtoon 解析）已移植自 three-vrm 且质量高。按"完整协议"标准有 2 个实质性缺口 + 若干健壮性问题（详见下）。

---

## 一、已实现且质量高的部分

| 模块 | 说明 |
|---|---|
| glTF/GLB 解析层 | `Gltf.kt` 覆盖 glTF 2.0 全部所需字段（含 sparse accessor/animation/skin）；`GlbParser.kt` 魔数/版本/长度/chunk 三重校验 |
| VRM 语义 JSON 模型 | `VrmExtensions.kt`：meta/humanoid/firstPerson/lookAt/expressions/specVersion 全覆盖；expression 的 morph/materialColor/textureTransform 三种 bind 齐全 |
| Humanoid | 56 骨骼枚举完整、required bones 校验、raw/normalized 双 rig + 重定向 |
| Expressions | preset/custom 校验、overrideBlink/LookAt/Mouth 三组抑制、isBinary、逐 bind 驱动——与 three-vrm 逐行对应 |
| LookAt | rangeMap、eye 镜像、faceFront 四元数、offsetFromHeadBone——完整 |
| SpringBone 算法 | Verlet 积分（惯性/stiffness/gravity/drag/collision）+ center space 变换；Sphere/Capsule collider（含 inside）——逐行移植 |
| VRMA | humanoid 骨骼重定向（rotation 相对父骨骼、hips 专用平移烘焙）、expression weight 轨道、lookAt 轨道、单帧 keyframe 处理 |
| Filament 适配 | `FilamentNodeTransformStore` 用 skin.joints ↔ getJointsAt 修复骨骼 entity 映射；live bone driving + updateBoneMatrices 正确 |

## 二、实质性缺口（按优先级）

### 【高】1. 弹簧骨骼被整体禁用
- 位置：`AvatarEngineController.springManager = null`（注释：DISABLED for diagnosis，网格拉伸）
- 根因：`FilamentSpringBoneStore` 仍用旧错误 entity 映射（`instance.entities[nodeIndex]`）且 `parentNodeIndex` 恒返回 -1
- 修复路径：复用 `FilamentNodeTransformStore` 已实现的 `skin.joints ↔ getJointsAt` 映射，修复 `FilamentSpringBoneStore` 后启用
- 状态：`[x] 已修复`（2026-08-28，commit 8e0503d 修 entity 映射）+ `[x] 深修`（2026-08-28：世界矩阵由静态 rest → 共享 live store，弹簧跟随动画，见更新记录）

### 【高】2. MToon 解析了但完全未消费
- 位置：`MtoonLoader` 正确解析全部参数，但 vrm-adapter 无任何 Filament 材质应用（无 filamat 编译、无 uniform 设置、无 MToon shader）
- 现状：模型实际用 Filament 默认 PBR 渲染（配合光照近似卡通效果）
- 说明：目标为"近似即可"，非 bug；但应明确哪些参数可被 Filament 近似映射（shadeColor→漫反射、transparentWithZWrite→alpha、outline→可选），或用文档声明"以 PBR 近似"
- 状态：`[x] 已实现真机生效`（commit 7d6b91a：matc 工具链 + 材质变体 + PNG 贴图解码；08-30 修 cel shader 法线取错 + lightDir，两款真机验证 MToon 卡通明暗正常显示）

### 【中】3. VRMC_node_constraint（约束扩展）未实现
- 说明：VRM 1.0 官方扩展（aim/rotation 约束，IK 辅助）；官方测试模型 `VRM1_Constraint_Twist_Sample.vrm` 即带约束；three-vrm 有 VRMNodeConstraint 支持
- 状态：`[ ] 未实现`

### 【中】4. 官方 VRM 0.x 文件缺少显式拒绝
- 位置：`VrmLoader` 不校验 specVersion
- 风险：传 VRM 0.x 会静默解析出错或错位
- 修复：load() 开头检查 `vrm.specVersion` 非 1.0 时抛明确异常
- 状态：`[x] 已修复`（2026-08-28，commit 39e6414）

## 三、健壮性 / 规范细节问题

| # | 问题 | 位置 | 状态 |
|---|---|---|---|
| 1 | REQUIRED_HUMAN_BONES 少 3 根（VRM 1.0 规范 required 为 18 根，现仅 15 根，缺 chest/upperChest/neck）；注释"17 根"也错 | `HumanBone.kt` | `[x] 已修复`（2026-08-28，commit 39e6414；改为宽松 warning 模式） |
| 2 | update() 和 init 残留大段 DIAG 调试日志（每 300 帧 dump 骨骼位置+旋转 + 初始化 root-chain/world/rot dump，约 250 行） | `AvatarEngineController.update()` + `init` | `[x] 已清理`（2026-08-28，commit 232dc49 + 5bf110c） |
| 3 | `VrmAnimationPlayer.weightTracks` 每帧覆盖 expressionManager 手动滑块值——VRMA 带 `.weight` 轨道时手动表情被顶掉（规范上属"动画优先"正确行为） | `VrmAnimationPlayer.kt` | `[x] 已修复`（新增 `expressionOverride` 开关，默认 true=动画优先；false 时跳过 weight 写，让手动权重生效） |
| 4 | `VrmFirstPerson.meshAnnotations` 解析了但没用（first-person 裁剪；展示类语义，运行时通常可忽略，需明确记录） | `VrmExtensions.kt` | `[ ] 待明确` |
| 5 | `VrmLookAt.type` 用 nullable String，规范要求必填（"bone"/"expression"），非法值默认走 bone，建议显式校验 | `VrmExtensions.kt` | `[x] 已修复`（`VrmLookAtLoader` 显式 `when` 校验 + warn，非法/null 默认 bone） |
| 6 | Expression `overrideBlink` 等三字段规范值仅 "block"/"none"，实现了 BLEND 分支（three-vrm 兼容，无副作用，保留即可） | `Expression.kt` | `[ ] 无需改` |
| 7 | VRoid 等 bone 型 lookAt 模型的 `lookUp/lookDown/lookLeft/lookRight` 预设表情 **morphTargetBinds 为空**——调 `setExpression("lookUp")` 无任何视觉变化；bone 型视线只靠左右眼骨骼旋转（`VrmLookAtBoneApplier`，已与 three-vrm 逐行一致）。属模型数据事实，非库 bug；要"整脸看"需用 expression 型模型或补绑点 | `VrmLookAtBoneApplier.kt` / 模型数据 | `[ ] 已澄清（数据事实）` |

## 四、测试覆盖（83 个全绿，2026-08-30 强制重跑确认）

- 覆盖：humanoid（VRMHumanoid/Twist/UpdateRestRoundtrip）、expression、lookAt、springbone（Joint+Collider）、mtoon、vrma（Loader/TwistArm/RealVrma）、VrmLoading、AvatarController（13）
- 缺口：
  - 未覆盖"多 skin、多 mesh、大纹理"真实复杂模型（如 VRoid_Sample_B）的端到端
  - `SpringBoneTest` 已补 2 个"live/共享 store vs frozen-rest store"回归用例，实证弹簧跟随动画；`FilamentSpringBoneStore` 真机层仍无纯 JVM 单测（需 Filament 引擎实例）

## 五、建议补全优先级

1. [x] 修 `FilamentSpringBoneStore`（复用 `FilamentNodeTransformStore` 的 joint 映射 → 启用弹簧骨骼）——2026-08-28
2. [x] `VrmLoader` 加 specVersion 校验 + `REQUIRED_HUMAN_BONES` 补全——2026-08-28
3. [x] 清掉所有 DIAG 调试日志（update + init）——2026-08-28
4. [x] MToon 真机渲染（matc 预编译 unlit cel 材质 + PNG 贴图解码 + 材质变体）——2026-08-29/08-30；修 cel shader 法线取错
5. [ ] `VRMC_node_constraint` 视需求决定是否补（若只用官方演示模型可延后）

---

## 更新记录

| 日期 | 更新内容 |
|---|---|
| 2026-08-27 | 首次 Review，建立本文件 |
| 2026-08-28 | 已修复：弹簧骨骼启用（commit 8e0503d）、specVersion 校验 + REQUIRED_BONES 补全（commit 39e6414）、清理 DIAG 日志 update + init（commit 232dc49 + 5bf110c） |
| 2026-08-28 | 深修：弹簧骨骼世界矩阵从"静态 rest"改为 nodeStore/springStore 共享一个可变 live store（跟随动画 + 父先子后排序生效）；补 2 个回归测试；清理错误 plan/文本 |
| 2026-08-28 | `VrmLookAt.type` 显式校验（warn+默认 bone）；`VrmAnimationPlayer.expressionOverride` 表情覆盖开关；`materialColor`→Filament `baseColor` 框架实现（仅 COLOR，其余跳过）——均经车机实机验证不崩 |
| 2026-08-29 | MToon 卡通渲染落地（commit 7d6b91a）：matc v1.72.1 工具链 + opaque/masked/transparent 材质变体 + PNG 贴图解码绑定；VRMA lookAt 轨道接线到 VrmLookAt（29bcaae）；look* 表情驱动 bone 型 lookAt（de14c40） |
| 2026-08-30 | 修 MToon cel shader 法线取错（`getWorldTangentFrame()[0]` 是切线 → 改用 `[2]`，unlit 无 `getWorldNormal`）+ lightDir 对齐场景方向光；恢复 `mtoonEnabled=true`；真机（Xiaomi + Redmi）验证 MToon 应用成功、卡通明暗正常；单测 80→83；README 三处过时描述同步 |