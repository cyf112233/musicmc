#!/usr/bin/env bash
# aarch64-g++.sh — aarch64-w64-mingw32-g++ 包装器入口
# 设置 REAL_GPP 后转交公共过滤器 mingw-g++.sh。
set -euo pipefail

export REAL_GPP="${REAL_GPP:-/media/cyf112233/data/llvm-mingw/bin/aarch64-w64-mingw32-g++}"
exec "$(dirname "${BASH_SOURCE[0]}")/mingw-g++.sh" "$@"