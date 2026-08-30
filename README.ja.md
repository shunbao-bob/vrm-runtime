# vrm-android

[English](README.md) | [中文](README.zh-CN.md) | **日本語**

純 Kotlin による **VRM 1.0 解析・ランタイムライブラリ**（コードネーム vrm-android / vrmruntime）です。Android アプリが VRM 仮想ヒューマンモデルを読み込み、その基本動作（ボーンマッピング、表情、スプリングボーン物理、VRMA アニメーションのリターゲティング、視線追従（LookAt））を駆動できるようにします。

レンダリング層は **Google Filament**（SceneView の Engine / ModelLoader 経由で接続）を基盤とし、Unity / ゲームエンジン / JNI ゲームフレームワークには依存しません。コアライブラリ `:vrm-core` は純 Kotlin/JVM で Android 依存ゼロ、JUnit5 による高速テストが可能です。

移植の青写真：[three-vrm](https://github.com/pixiv/three-vrm)（TypeScript）。規格：[VRM 1.0 仕様](https://github.com/vrm-c/vrm-specification)（重点は `VRMC_vrm-1.0`、`VRMC_vrm_animation-1.0`、`VRMC_springBone-1.0`、`VRMC_materials_mtoon-1.0`）。

## プロジェクト構造

```
vrmruntime/
├── vrm-core/                    純 Kotlin JVM ライブラリ（maven 配布可）
│   └── src/main/kotlin/dev/vrm/runtime/core/
│       ├── gltf/                 GLB 解析 + glTF 2.0 データクラス
│       ├── vrm/                  VRMC_vrm / VRMC_springBone / VRMC_materials_mtoon データクラス + VrmLoader
│       ├── humanoid/             HumanBone マッピング / VRMRig / ノーマライズドリグ / VRMHumanoid
│       ├── expression/           ExpressionManager / ExpressionLoader / 3 種の bind
│       ├── vrma/                 VRMA 解析 / リターゲット clip 構築 / KeyframeTrack
│       ├── springbone/           Verlet スプリングボーン / collider / manager / loader
│       ├── mtoon/                MToon マテリアルパラメータモデル
│       ├── lookAt/               LookAt コントローラー / bone+expression applier / range map
│       ├── controller/           AvatarController 命令的 API（xlunar 参照）+ AvatarConfig リソースマニフェスト
│       └── math/                 Vec3 / Quat / Mat4（three.js セマンティクス、ゼロ依存）
├── vrm-adapter/                 Android ライブラリ（Filament/SceneView バインド層、vrm-core の上位に位置）
│   └── src/main/java/dev/vrm/runtime/adapter/
│       ├── AvatarEngineController.kt   エンジンコントローラー（AvatarBinding 実装 + 口型）
│       ├── AvatarRenderer.kt           adapter のメインエントリ（loadModel / applyStage / update）
│       ├── StageConfig.kt              xlunar スタイルのライティング/シーン設定
│       ├── VrmAnimationPlayer.kt       VRMA プレイヤー
│       └── filament/                   FilamentNodeTransformStore / SpringBoneStore / ExpressionBindProvider
└── app/                         demo（Compose + SceneView/Filament、:vrm-adapter のみに依存）
    └── src/main/java/dev/vrm/runtime/demo/
        ├── VrmDemoScreen.kt            xlunar スタイル：左ビューポート + 右タブ操作パネル
        ├── DemoAssets.kt               モデル/VRMA/表情リソースマニフェスト
        └── MainActivity.kt
```

## 環境

- JDK 17、Android API 26+
- Kotlin 2.2.x、AGP 8.12.x、Gradle 8.13
- レンダリング：SceneView 2.3.3（内蔵 Filament 1.68.2）
- JSON：kotlinx.serialization 1.7.3
- テスト：JUnit5 + 公式 Seed-san.vrm fixture

## 導入ガイド（ライブラリの利用）

### 0. 依存座標（maven 配布）

`vrm-core` は `maven-publish` を設定済みで、座標は `dev.vrm.runtime:vrm-core:1.0.0` です：

```bash
./gradlew :vrm-core:publishToMavenLocal     # ローカル ~/.m2 に公開
```

ホストプロジェクト（JVM / Android）側の依存：

```kotlin
// settings.gradle.kts で mavenCentral + mavenLocal() にアクセスできる必要があります
implementation("dev.vrm.runtime:vrm-core:1.0.0")
```

公開構成：jar + pom + Gradle module metadata は `publishing { MavenPublication("maven") }` が自動生成します。リモートリポジトリ（例：Maven Central）へプッシュするには `publishing.repositories` に該当リポジトリを追加し、必要に応じて署名を設定します。

### 1. VRM の解析

```kotlin
import dev.vrm.runtime.core.vrm.VrmLoader

val bytes: ByteArray = ... // .glb / .vrm ファイルのバイト列
val vrm = VrmLoader.load(bytes)
// vrm.gltf          下層の glTF
// vrm.vrm           VRMC_vrm 拡張（humanoid/lookAt/expressions/meta）
// vrm.springBone    VRMC_springBone 拡張
// vrm.mtoon         material index -> VRMC_materials_mtoon
// vrm.vrmAnimation  VRMC_vrm_animation（.vrma ファイル用）
```

### 2. Humanoid + 表情の構築（NodeTransformStore の提供が必要）

```kotlin
import dev.vrm.runtime.core.humanoid.VRMHumanoid
import dev.vrm.runtime.core.expression.ExpressionLoader

// store はレンダリングエンジンのノードグラフを抽象化します。demo では FilamentNodeTransformStore を使用
val humanoid = VRMHumanoid.fromVrm(vrm.gltf, vrm.vrm!!, store)
val expressionManager = ExpressionLoader(bindProvider).load(vrm.gltf, vrm.vrm!!)
```

コアライブラリはエンジン非依存です。`NodeTransformStore`（getLocal*/getWorldMatrix/parentNodeIndex）と `ExpressionBindProvider`（morphTargetChannel / materialColorAccess / textureTransformAccess）はホスト側が実装します。

### 3. LookAt 視線追従

```kotlin
import dev.vrm.runtime.core.lookAt.VrmLookAtLoader

val lookAt = VrmLookAtLoader(humanoid, expressionManager, store).load(vrm.vrm!!)
lookAt.target = Vec3(x, y, z)   // ワールド座標のターゲット点
// 毎フレーム：
lookAt.update(deltaSeconds)     // autoUpdate=true のとき target へ向けて回転し、表情/ボーンへ書き込む
```

Seed-san など多くのモデルは `expression` 型の LookAt を使用します（lookUp/Down/Left/Right の表情を駆動）。`bone` 型は leftEye/rightEye ボーンを直接回転します。

### 4. スプリングボーン

```kotlin
import dev.vrm.runtime.core.springbone.SpringBoneLoader

val springManager = SpringBoneLoader(vrm.gltf, vrm.springBone!!, springStore).load()
// 毎フレーム：
springManager.update(deltaSeconds)
```

### 5. VRMA アニメーションの再生（モデル間リターゲット）

```kotlin
import dev.vrm.runtime.core.vrma.VrmAnimationLoader
import dev.vrm.runtime.core.vrma.VRMAnimationClipBuilder

val vrma = VrmLoader.load(vrmaBytes)
val ext = vrma.vrmAnimation!!
val bin = vrma.binary?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
val animation = VrmAnimationLoader(vrma.gltf, bin, ext).loadAll().first()
val clip = VRMAnimationClipBuilder(animation, humanoid, expressionManager).build()
// clip.tracks はエンジン非依存の KeyframeTrack です：
//   Normalized_<bone>.quaternion / .position
//   <expressionName>.weight
```

demo の `VrmAnimationPlayer` はこれらの track を再生する最小実装です。

### 6. 数学ライブラリ

`core/math` は three.js セマンティクスの `Vec3`/`Quat`/`Mat4`（可変オブジェクト、copy() スナップショット）をゼロ依存で提供します。内部のすべての VRM 処理はこれの上で動作します。

### 7. AvatarController（命令的制御層、xlunar-ai-avatar 参照）

`core/controller` は「モデル選択 / アニメーション切替 / 表情駆動」などの操作をエンジン非依存の命令的 API にカプセル化します。demo は `AvatarBinding` インターフェースを実装するだけで駆動できます：

```kotlin
import dev.vrm.runtime.core.controller.*

// 1. ホストが AvatarBinding を実装（demo では VrmController が実装済み、Filament と接続）
val binding: AvatarBinding = myController

// 2. リソースマニフェストの設定（モデル/アニメーション/表情/ポーズ）
val config = AvatarConfig(
    models = listOf(ModelPreset("seed", "Seed-san", "avatars/Seed-san.vrm")),
    animations = listOf(AnimationPreset("wave", "Wave", "animations/wave.vrma")),
)

// 3. コントローラーの作成
val avatar = AvatarController(binding, config)

// 4. 命令的駆動
avatar.setModel("B");            // モデル切替
avatar.playVrma("wave");         // アニメーション再生（app id または元の source で指定）
avatar.setExpression("happy");   // 表情
avatar.execute(AvatarCommand.ResetExpression)
avatar.batch(listOf(...))        // 同時に複数実行
avatar.queue(listOf(...))        // 順次キュー（Wait 対応）
avatar.on(AvatarEventType.STATE_CHANGE) { event -> ... }   // イベント購読
avatar.onVrmaComplete()          // プレイヤー終了後にホストからコールバック
```

コマンド型（`AvatarCommand`）：SetPose / SetHandGesture / SetBodyGesture / SetBodyMotion / SetExpression / PlayVrma / SetSequence / Wait / Reset / ResetPose / ResetExpression / StopVrma / StopSequence / RawPose / RawExpression。

## デモアプリ

`app` モジュールは完全に実行可能な Compose デモです：

- **モデルセレクター**：`public/1.0` で同期した 42 個の VRM 1.0 モデルを切替（`avatars/` の 8 サンプル + `models/` の 34 キャラクター）
- **Pose**：静的ポーズ、ジェスチャーエイリアス、ボディモーションの入口、ポーズのリセット
- **Combos**：多段階ポーズ + 表情の演出（friendly greeting / thinking eureka / explaining など）
- **VRMA**：20 個の VRMA、ソースファイルに応じたデフォルト loop、手動 loop のオン/オフ、停止
- **Face**：標準 VRM 表情を項目ごとに 0..1 スライダーで操作（happy / sad / mouth / blink / lookAt）
- **Scene**：環境光、方向光、カメラ距離、自動 LookAt のオン/オフ
- **表情パネル**（右下）：preset 表情スライダーで morph ウェイトをリアルタイム駆動
- **スプリングボーンのオン/オフ** / **LookAt 追従**：髪・スカートの物理、視線追跡
- **VRMA の再生/一時停止** + モデル名/状態の表示

> デモアセットは [xlunar-ai-avatar](https://github.com/iamenahs/xlunar-ai-avatar) の `public/1.0` ディレクトリから同期：`app/src/main/assets/avatars/`（8 サンプル VRM）、`models/`（34 キャラクター VRM）、`animations/`（20 VRMA）。`DemoAssets` の source key はこれら 3 ディレクトリと一致しています。

> ⚠️ **環境マップは必須アセットです**：demo は `assets/environments/neutral/neutral_ibl.ktx` + `neutral_skybox.ktx` を IBL 環境光として利用します。SceneView の `createKTX1Environment(iblAssetFile=…)` は **app 自身の assets** から読み取ります（ライブラリの assets ではありません）。この 2 ファイルがないとシーンに環境光がなく、**モデルが黒く描画されます**。ファイルは SceneView 2.3.3 AAR 内の `assets/environments/neutral/` から取得しています（`sh.txt` に由来を記録）。また、シーンに `LightNode`（Directional）を追加してモデルを明るくすることもできます。

> ⚠️ **車載の横画面では landscape 宣言が必須**：AndroidManifest で `MainActivity` の `android:screenOrientation` は `landscape`（または `unspecified`）にする必要があります。`portrait` を指定すると、1920x720 の横画面車載端末ではシステムによって **letterbox され、右 270px の細い帯に押し込まれます**（ウィンドウ bounds=[825,0][1095,720]、ComposeView が 0x0-0x48 に潰れる）。「モデルは見えるが UI パネルがすべて消える」という症状になります。`landscape` にすれば全画面で正常動作します。

ビルド：`./gradlew :app:assembleDebug`、APK は `app/build/outputs/apk/debug/` に出力されます。

## テスト

```bash
./gradlew :vrm-core:test --console=plain
```

95 個のテスト全緑（17 テストクラス）、カバレッジ：

| モジュール | テストクラス | 件数 |
|---|---|---|
| glb + VRM データ層 | VrmLoadingTest（Seed-san の全フィールド解析） | 4 |
| Humanoid | VRMHumanoidTest（ボーンマッピング / required 検証 / ノーマライズドリグ） | 4 |
| Humanoid | TwistHumanoidMapTest | 1 |
| Humanoid | UpdateRestRoundtripTest（ポーズ書き戻し後も rest ポーズが往復一致する検証） | 1 |
| Expressions | ExpressionManagerTest（preset 解析 / クランプ / override グループ / morph 書き込み） | 16 |
| Expressions | MaterialColorBindTest（materialColorBinds → baseColor） | 2 |
| VRMA | VrmAnimationLoaderTest（合成 VRMA 解析 + Seed-san へのリターゲット） | 4 |
| VRMA | RealVrmaDiagnosticTest（実在 test.vrma のチャンネル診断） | 1 |
| VRMA | TwistArmPoseTest（回転アニメ中の腕のワールド座標） | 1 |
| SpringBone | SpringBoneColliderTest（sphere/capsule の正確な期待値） | 6 |
| SpringBone | SpringBoneTest（Verlet 垂下 / 骨長保存） | 4 |
| MToon | MtoonTest（10 マテリアルの解析 / パラメータ / renderOrder） | 6 |
| LookAt | LookAtTest（range map / 角度ユーティリティ / applier の符号 / loader / 挙動） | 18 |
| Controller | AvatarControllerTest（コマンドキュー / イベント / 状態 / batch） | 13 |
| Guards | VrmLoaderGuardTest（空入力 / 非 GLB / VRM 0.x） | 6 |
| Guards | NodeTransformStoreGuardTest（範囲外の node index） | 6 |
| math | Vec3ApiTest（three.js セマンティクス API） | 2 |

## 実装済み機能

- [x] GLB 解析（ヘッダー + JSON/BIN chunk）
- [x] VRMC_vrm：humanoid（52 標準ボーン、17 required 検証）、expressions（18 preset + custom）、lookAt、meta、firstPerson データクラス
- [x] VRMC_springBone：collider（sphere/capsule）、joint、group、center
- [x] VRMC_materials_mtoon：パラメータモデル（シェーディング係数 / テクスチャ index / outline / renderOrder）
- [x] VRMC_vrm_animation（.vrma）：humanoid / expressions / lookAt トラック + リターゲット clip
- [x] Humanoid：raw rig の読み書き / ノーマライズドリグ / rest 姿勢の捕捉
- [x] Expressions：ウェイトのクランプ / isBinary / override グループ（blink/lookAt/mouth）/ morph+マテリアル+UV bind
- [x] SpringBone：Verlet 積分 / stiffness / gravity / drag / 衝突押し出し / 深さソート
- [x] MToon：sRGB opt-in 変換、shouldGenerateOutline、renderOrder
- [x] LookAt：yaw/pitch / bone + expression applier / range map / faceFront
- [x] demo アプリ：表情パネル + スプリングボーン切替 + LookAt 追従 + VRMA 再生

## 既知の未対応項目 / 制限

**機能面**
- **VRM 1.0 のみ**対応（1.0-beta 含む）。VRM 0.x とは互換性がありません。
- VRM の読み取りのみで書き出しは不可。編集機能はありません。
- MToon はパラメータモデル + 近似再現であり、100% ピクセル単位の再現ではありません（レンダリング基準は three-vrm）。レンダリング層は matc でプリコンパイルした `mtoon_{opaque,masked,transparent}.filamat`（unlit + 手動セル/toon ライティング）で gltfio のデフォルト PBR を置き換え、マテリアルごとの alphaMode に応じて blending バリアントを選択します。近似実装：baseColor / shade 色 + shadingShift/Toony + 一方向光のみをマップし、rim / matcap / outline / UV アニメーションなど MToon 固有のチャンネルは未実装（解析はするがシェーダーでは未使用）。
- VRMA の編集は行わず、ランタイム再生のみ対応。
- glTF の制約（constraint / KHR 拡張）は解析しません。VRMC_node_constraint は未実装です。

**エンジン接続面（demo の Filament バインド）**
- **PNG テクスチャは MToon 経路では対応済み、gltfio の既定経路では未対応（解決済み）**：gltfio 1.68 の `ResourceLoader` には `addTextureProvider` がなく、filament-utils には ImageDecoder がないため、`image/png` テクスチャを使用する VRM（例：Seed-san）は既定の gltfio レンダリングでは素色・グレーのままです。ただし demo の MToon 経路（`MToonMaterialApplier`）では **GLB の BIN chunk から PNG を取り出し → BitmapFactory でデコード → Filament Texture に変換**する独自経路を実装済みで、MToon を有効にすると PNG テクスチャが正常に着色されます。
- `FilamentNodeTransformStore.parentNodeIndex` を修正済み：gltf nodes を渡して nodeIndex→parent のマッピングを構築し、`parentNodeIndex` が実際の親子関係を返すようにしました（bone 型 LookAt / springbone center / ノーマライズドリグの親ワールド回転補正を修正）。
- VRMA の lookAt トラック（`lookAt.quaternion`）は `VrmAnimationPlayer` で消費済み：ワールド空間の視線 quaternion をサンプリング → yaw/pitch に変換 → VrmLookAt コントローラーへ適用（`VrmAnimationPlayer.apply`。lookAt が渡された場合に有効）。
- 表情のマテリアルカラー / UV 変換 bind は、demo の `FilamentExpressionBindProvider` でマテリアル属性ごとに実装しています。該当属性がシェーダーで宣言されていない場合はその bind をスキップします。
- スプリングボーンの衝突は現在 3 つの球体で近似しています（capsule は 2 点 + 半径）。three-vrm の挙動と一致します。
- **VRoid/xlunar モデルのスケルトン根チェーン問題（解決済み）**：初期の gltfio は「独立スケルトン根 + 単一共有 skin」構造の関節ワールド行列を誤って計算していました（VRoid のスケルトン根 `Root(90°回転)→Global→Position→Hips` が誤って統合され、hips のワールド y≈0.08、脚が逆さまに交差）。**「VRMA をモデル GLB へベイク + gltfio Animator 駆動」で解決済み**：アニメーション/ポーズがリターゲット後のボーンローカル回転をモデル内蔵の glTF アニメーションとしてベイクし、`Animator.applyAnimation` + `updateBoneMatrices` でスキニングを駆動するため、VRoid の脚交差は解消されました。**08-24 修正**：検証の結果、**TransformManager による直接駆動も公式サポートされたチャンネル**であることが判明しました（`AnimatorImpl::updateBoneMatrices` は TransformManager から関節のワールド変換を読み戻す。PROGRESS 08-24 の項を参照）。これまでの「gltfio のスキニングには無効」という結論は、NO-OP store + 誤ったエンティティインデックスによる誤認でした。live-bone 経路（関節ローカル行列の書き込み → updateBoneMatrices）は、頭 yaw を単一の駆動源としてデバイス上で動作を検証済みです。ベイクは clip 再生の信頼できるチャンネルですが、live 駆動は軽量な代替であり、ポーズ/アニメーション/スプリングボーン/視線をすべてリアルタイムに駆動できます。
- **Directional Light が MToon/トゥーン素材に無効（正しい挙動）**：VRoid/キャラクターモデルのマテリアルはすべて `KHR_materials_unlit` / MToon のトゥーン素材で、**方向光に反応しません**（トゥーン描画の特性で、xlunar/three-vrm も同様）。Scene タブの ambient + directional の 2 つのスライダーは現在**環境光（IndirectLight）の合計輝度として統合**し、画面の明暗を制御します：`合計輝度 = ambient*400k + dir*250k`。方向光を実際に有効にするにはマテリアルを PBR に変更する必要があります（トゥーン表現が崩れます）。
- **lookAt（視線追従）はベイクアニメーションモードでは制限あり**：VRM lookAt には bone 型と expression 型があります。twist は bone 型です（head/eye ボーンを回転）。ベイク方式では **Animator が毎フレームすべてのボーンを管理するため、リアルタイムの lookAt によるボーン回転を重ねられません**。そのため lookAt はアニメーション/POSE 再生中は無効です（アニメーションなしの純 T-pose でも、TransformManager が gltfio のスキニングに対して無効なため同様に無効）。これはベイクアーキテクチャの固有の制約で、リアルタイム視線追従をサポートするには独立したボーンチャンネルが必要です。
- **COMBOS（シーケンス編成）は一時的に無効**：シーケンス内で `loadPose` を連続実行（毎回ベイク + モデルの再ロード）すると、gltfio の `Animator::applyAnimation` で native SIGSEGV が発生することがあります（再ロードのウィンドウ期間に破棄済みの Animator へアクセス）。`applyLoaded` は「先に新規を作成し、後から旧を破棄」に変更して緩和しましたが、シーケンスレベルの連続発火はまだ不安定なため、一時的に無効にしています。
- **アニメーション切替の繰り返しによるモデル再構築の競合（根治済み）**：`playAnimation` が `loadAnimation` を経由すると**毎回** 14MB の VRMA を再ベイク → 新規 Filament `ModelNode` + `AvatarEngineController` を作成 → 旧を破棄、という処理が走ります。1 回のシミュレーションで controller 再構築 8 回・ベイク 24 回が発生し、高頻度の破棄/再構築により、メインスレッド GC + Filament リソースの混乱、再構築ウィンドウ期間中の髪の rest ロック一時消失（髪が「レーザーのように飛び出す/顔に被る」）、Animator 並行書き込みによる偶発的な native SIGSEGV を引き起こしました。**解決策（08-24 より live 駆動に変更）**：ベイクしません。`AvatarRenderer.loadModel` は通常の GLB を読み込み、アニメーション/ポーズ/組み合わせはすべてリアルタイム VRMA プレイヤー + `TransformManager` によるボーン書き込み + `updateBoneMatrices()` で駆動します（live チャンネル、前述の 08-24 修正を参照）。GLB の再書き込みなし・ModelNode 再構築なし・再ロードのウィンドウ期間なしで、SIGSEGV は自然に発生しません。ベイクチャンネル（`VrmaBake` / `loadModelWithClips` / `switchClip`）は削除済みです。


- **VRoid の髪が「顔に被る」原因：lookAt による継続的な頭の回転 + ベイクした hair-lock がローカル rest のみをロック**。ベイクした clip には humanoid ボーンしか含まれず、スプリングボーン関節（VRoid_B の 47 本の HairJoint）は clip に含まれません。各 HairJoint の **rest ローカル回転**を一定の track（`hairLock_*`）としてベイクしたため、髪が大きな回転で「レーザーのように飛ばされる」ことはなくなりました。ただし HairJoint は `J_Bip_C_Head` の子なので、**head が回転すると髪は head のワールド向きを継承**します—— demoPhone が既定で有効にしている lookAt が毎フレーム head を円周運動させ（`target = head + 0.35·sin / 0.2·cos / +0.6`）、髪が head の揺れに合わせて断続的に顔に被ります。**demoPhone は現在 lookAt を既定で無効にしています（`PhoneViewModel.lookAtEnabled=false`）**。有効にするには、ベイク経路とは別の独立したボーンチャンネルを用意して髪の「ワールド向きのロック」を実現する必要があります。

## 性能 / プラットフォーム

- すべての Filament JNI 呼び出しはメインスレッドで行う必要があります。モデル/VRMA の解析は IO dispatcher での実行を推奨します。
- ライブラリ自体は Compose に依存しません。demo が Compose を使うだけで、ライブラリは純 Kotlin の API です。

## License

このライブラリは **MIT License** の下で配布されています。詳細は [LICENSE](LICENSE) ファイルを参照してください。

### Third-party notice

`vrm-core` の主要部分（humanoid、expressions、lookAt、spring-bone physics、MToon パラメータモデル、VRMA リターゲティング、math）は [three-vrm](https://github.com/pixiv/three-vrm) からの移植であり、同ライブラリは MIT License（Copyright (c) 2019-2026 pixiv Inc.）で配布されています。MIT の条項に従い、著作権表示と本許可表示はソースコード内に保持されています（`Port of three-vrm...` の doc comment を参照）。

`vrm-adapter`（Filament/SceneView バインディング）と `app`（demo）は vrm-core の上に構築された独自実装であり、同じ MIT License を引き継ぎます。

命令的なアバター制御層（`AvatarController` / `AvatarCommand` / `AvatarConfig` / `AvatarBinding`）と、demo のステージ/ポーズ/ジェスチャー/コンボプリセットは [xlunar-ai-avatar](https://github.com/iamenahs/xlunar-ai-avatar) からの移植であり、同様に MIT License（Copyright (c) 2024 VaultX.technology）で配布されています。MIT の条項に従い、著作権表示はソースコード内に保持されています（`Port of xlunar...` の doc comment を参照）。

### Model assets

このライブラリにモデルアセットは同梱されていません。demo のサンプルモデル（VRM Consortium 提供の `Seed-san.vrm`、three-vrm の examples 由来の `test.vrma`）はそれぞれの原作者に帰属し、デモンストレーション目的でのみ含まれています。商用利用の前に各アセットのライセンスを確認してください。
