#!/usr/bin/env bash
# 编译 MToon 材质：3 个 .mat 源（各写死 blending）-> 3 个 .filamat
# 用法: ./tools/compile_mtoon.sh [mobile|desktop|all]
# 需要 tools/filament/matc（已随项目提供，来源 Filament v1.72.1 mac release）
set -e
cd "$(dirname "$0")/.."
MATC=tools/filament/matc
DIR=vrm-adapter/src/main/assets/materials
PLAT=${1:-mobile}

if [ ! -x "$MATC" ]; then
  echo "缺少 matc（Filament v1.72.1 编译工具）。从 https://github.com/google/filament/releases/tag/v1.72.1 下载 filament-v1.72.1-mac.tgz 解压后放 tools/filament/matc"
  exit 1
fi

echo "编译 3 个 MToon blending 变体（platform=$PLAT）"
echo "⚠️ 注：blending 必须在 .mat 源里写死（mtoon_<blend>.mat），不要用 matc -T BLENDING= 覆盖——实测 -T 覆盖不生效，产物恒为 opaque！"

for b in opaque masked transparent; do
  "$MATC" -p "$PLAT" -o "$DIR/mtoon_$b.filamat" "$DIR/mtoon_$b.mat" && echo "  $b: OK"
done

echo "成品："
ls -la "$DIR"/mtoon_*.filamat | awk '{print "  " $NF, "("$5" bytes)"}'