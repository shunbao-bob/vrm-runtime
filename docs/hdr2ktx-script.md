# 1. 下载 cmgen
# 从 https://github.com/google/filament/releases 下载 filament-v1.75.1-windows.tgz (~789MB)
# 解压后 cmgen.exe 在 bin/ 目录下

# 2. 用法
# 将 cmgen.exe 所在目录加入 PATH，或直接使用完整路径

# 3. 转换命令
# cmgen -f ktx --size=1024 -x <输出目录> <输入.hdr>
# 例：将 venice_sunset_4k.hdr 转为 1024px/面 cubemap KTX
cmgen -f ktx --size=1024 -x ./venice ./venice_sunset_4k.hdr

# 4. 输出
# 在 venice/ 目录下生成两个文件：
#   venice_ibl.ktx     — 预过滤环境光 (~33MB)
#   venice_skybox.ktx  — 1024px/面天空盒 (~25MB)

# 5. 集成到工程
# 将 venice/ 目录放入 demoPhone/src/main/assets/environments/
# 在 PhoneAssets.kt 中注册环境路径