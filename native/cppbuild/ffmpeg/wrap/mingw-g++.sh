#!/usr/bin/env bash
# =============================================================================
# mingw-g++.sh — mingw-w64 交叉编译 g++ 包装器(链接标志过滤器)
#
# 背景:javacpp Builder 会把宿主(linux)默认链接标志注入交叉编译命令行,
# 其中三个是 mingw 工具链不接受的:
#   -Wl,-z,noexecstack
#   -Wl,-Bsymbolic
#   -Wl,-rpath,$ORIGIN/
# 本包装器逐参数遍历(整串匹配),命中以上三条即丢弃,其余原样透传。
#
# 用法(由 x86_64-g++.sh / aarch64-g++.sh 调用,或外部直接设置 REAL_GPP):
#   REAL_GPP=x86_64-w64-mingw32-g++ mingw-g++.sh <原 g++ 参数...>
# bash 数组传参,保留引号,透传参数完整性不受影响。
# =============================================================================
set -euo pipefail

: "${REAL_GPP:?REAL_GPP 环境变量未设置(应由 x86_64-g++.sh / aarch64-g++.sh 导出)}"

args=()
for a in "$@"; do
    case "$a" in
        "-Wl,-z,noexecstack"|"-Wl,-Bsymbolic"|"-Wl,-rpath,\$ORIGIN/")
            continue
            ;;
    esac
    args+=("$a")
done

exec "$REAL_GPP" "${args[@]}"