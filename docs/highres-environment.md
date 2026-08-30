# 高清环境背景（HDRI Skybox）方案

## 问题

车机屏幕 VRM 场景的背景（天空盒）模糊。之前用 Filament 的
`loadHDREnvironment` 运行时将 equirectangular HDR 转为 cubemap，
输出固定 256px/面，在车机 1920x720 屏幕上看起来模糊。

尝试用 Python 脚本 (hdr2ktx.py) 直接生成 1024px/面的 KTX1 cubemap
失败——生成的 KTX 数据格式不正确，显示为噪点/纯色块。

## 根因

1. Filament 的 `EquirectangularToCubemap` Java API 固定输出 256px，
   无尺寸参数，无法运行时放大。
2. cmgen 生成的 KTX 使用 `R11F_G11F_B10F` (0x8C3A) 格式，每个像素
   32 位半浮点打包。手动构建的 KTX 要么字节序/格式声明错误，要么
   色调映射缺失，导致渲染异常。

## 最终解决方案

使用 **cmgen 工具**（Filament 官方镜像工具）在 PC 端将 HDR 转为
KTX1 cubemap，支持指定输出尺寸。

### 步骤

```bash
# 安装 cmgen（Windows 版）
# 从 https://github.com/google/filament/releases 下载
# filament-v1.75.1-windows.tgz（789MB，含 cmgen.exe）

# 转换 HDR -> 1024px/面 cubemap KTX
cmgen.exe -f ktx --size=1024 -x <output_dir> <input.hdr>
```

cmgen 输出两个文件：
- `_skybox.ktx` — 1024px/面 cubemap (25MB)
- `_ibl.ktx` — 预过滤的 IBL 环境光 (33MB)

### 集成到 demo

1. 将 `_skybox.ktx` 和 `_ibl.ktx` 放入
   `demoPhone/src/main/assets/environments/venice/`
2. 在 `PhoneAssets.kt` 中定义路径（`ENV_VENICE_IBL` / `ENV_VENICE_SKYBOX`）
3. 在 `PhoneDemoScreen.kt` 中用 `createKTX1Environment` 加载

```kotlin
val env = environmentLoader.createKTX1Environment(
    iblAssetFile = PhoneAssets.ENV_VENICE_IBL,
    skyboxAssetFile = PhoneAssets.ENV_VENICE_SKYBOX,
)
```

### 文件清单

| 文件 | 来源 | 尺寸 | 说明 |
|------|------|------|------|
| venice_ibl.ktx | cmgen 生成 | 33MB | 预过滤环境光 |
| venice_skybox.ktx | cmgen 生成 | 25MB | 1024px/面天空盒 |

### 后续环境维护

添加新环境步骤：
1. 从 Poly Haven 等网站下载 4K/8K HDR
2. 用 cmgen 转成 KTX (1024px)
3. 文件放入 `environments/<name>/` 目录
4. 更新 `PhoneAssets.kt` 添加新常量

## 对比

| 方案 | 清晰度 | 加载方式 | 生效条件 |
|------|--------|----------|----------|
| 运行时 equirect→cubemap | 256px 模糊 | `loadHDREnvironment` | 无需额外工具 |
| 自制 Python KTX | 1024px 噪点 | `createKTX1Environment` | 格式不兼容 |
| **cmgen 生成 KTX** | **1024px 清晰** | `createKTX1Environment` | **需要 PC 端 cmgen 工具** |