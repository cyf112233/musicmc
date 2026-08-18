#!/usr/bin/env bash
# =============================================================================
# netmusicmc — FFmpeg 裁剪原生构建脚本(cppbuild 阶段)
#
# Fork 自 bytedeco javacpp-presets 1.5.12 的 ffmpeg/cppbuild.sh 与根 cppbuild.sh
# (Apache-2.0 https://github.com/bytedeco/javacpp-presets)
# 原始脚本备份见 native/toolbox/ffmpeg-cppbuild-original-1.5.12.sh
#
# 与上游的主要差异:
#   1. FFmpeg 源码取自本地 <native>/ffmpeg/ffmpeg-7.1.5.tar.xz,不再 download 7.1.1.tar.bz2
#   2. 移除全部外部依赖库构建(nasm/zlib/lame/opus/openssl/x264/...):
#      裁剪配置只保留 FFmpeg 内置解码组件,无需任何第三方源码
#   3. configure 采用 --disable-everything 白名单式裁剪,见 FFMPEG_DISABLE / FFMPEG_ENABLE
#   4. 不应用 ffmpeg.patch / ffmpeg-vulkan.patch / ffmpeg-macosx.patch(基于 7.1.1,
#      裁剪后无需其修复项;若编译错误再按需回加)
#   5. 只保留本项目所需平台:linux-x86_64 / linux-arm64 / windows-x86_64 /
#      windows-arm64 / android-arm64 / android-x86_64(删除 ppc64le/s390x/macosx 等分支)
#   6. 自包含:可独立运行,不需要上游根 cppbuild.sh
#
# 产物约定(与 bytedeco 一致):安装前缀为
#   native/cppbuild/ffmpeg/cppbuild/<platform>/{bin,include,lib}
# 生成的共享库:libavcodec.so.61 libavformat.so.61 libavutil.so.59 libswresample.so.5
# =============================================================================
set -eu

FFMPEG_VERSION=7.1.5

# --- 定位项目目录 ------------------------------------------------------------
# 本脚本:<native>/cppbuild/ffmpeg/cppbuild.sh
THIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_DIR="$(cd "$THIS_DIR/../.." && pwd)"     # <native>/
DFLT_TARBALL="$NATIVE_DIR/ffmpeg/ffmpeg-$FFMPEG_VERSION.tar.xz"

# --- 裁剪配置(结果见 native/STATUS.md) --------------------------------------
FFMPEG_DISABLE="
    --disable-everything
    --disable-programs
    --disable-doc
    --disable-avdevice
    --disable-avfilter
    --disable-swscale
    --disable-postproc
    --disable-network
    --disable-vaapi
    --disable-vulkan
    --disable-libdrm
    --disable-bzlib
    --disable-zlib
    --disable-static
"
FFMPEG_ENABLE="
    --enable-shared
    --enable-pic
    --enable-pthreads
    --enable-swresample
    --enable-avcodec
    --enable-avformat
    --enable-avutil
    --enable-decoder=aac --enable-decoder=mp3 --enable-decoder=mp3adu --enable-decoder=mp3on4
    --enable-decoder=flac --enable-decoder=opus --enable-decoder=vorbis
    --enable-demuxer=mov --enable-demuxer=matroska
    --enable-demuxer=mp3 --enable-demuxer=flac --enable-demuxer=ogg --enable-demuxer=opus --enable-demuxer=aac
    --enable-parser=aac --enable-parser=flac --enable-parser=mpegaudio --enable-parser=opus --enable-parser=vorbis
    --enable-protocol=file
"

# --- CLI 解析(兼容上游风格) ---------------------------------------------------
PLATFORM=
OPERATION=
TARBALL="$DFLT_TARBALL"
REBUILD=0
while [[ $# > 0 ]]; do
    case "$1" in
        -platform=*) PLATFORM="${1#-platform=}" ;;
        -platform)   shift; PLATFORM="$1" ;;
        install)     OPERATION=install ;;
        clean)       OPERATION=clean ;;
        --tarball=*) TARBALL="${1#--tarball=}" ;;
        --rebuild)   REBUILD=1 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
    shift
done
if [[ -z "$PLATFORM" || -z "$OPERATION" ]]; then
    echo "Usage: bash cppbuild.sh <install|clean> -platform=<name> [--tarball=<path>] [--rebuild]" >&2
    echo "  platforms: linux-x86_64 linux-arm64 windows-x86_64 windows-arm64 android-arm64 android-x86_64" >&2
    exit 1
fi

# --- 会话环境 -----------------------------------------------------------------
[[ -z ${MAKEJ:-} ]] && MAKEJ=$(( $(nproc 2>/dev/null || echo 4) > 8 ? 8 : $(nproc 2>/dev/null || echo 4) ))
export MAKEJ

# Android 需要的环境(与上游根脚本一致;非 android 平台不引用)
if [[ -z ${ANDROID_NDK:-} ]]; then
    ANDROID_NDK="$HOME/Android/android-ndk/"
fi
export ANDROID_NDK
KERNEL="$(uname -s | tr '[A-Z]' '[a-z]')"
ARCH="$(uname -m | tr '[A-Z]' '[a-z]')"
[[ "$KERNEL" == *darwin* ]] && KERNEL=macosx
# 上游在 linux×linux 宿主上用 llvm 预编译目录名;此处仅 android 平台使用
export ANDROID_BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$KERNEL-$ARCH/"
export ANDROID_PREFIX="$ANDROID_BIN/bin/llvm"
# M4 修正:r27b 的 sysroot 实际在此;原为空导致 --sysroot 失效
export ANDROID_ROOT="$ANDROID_BIN/sysroot"
case $PLATFORM in
    android-arm64)
        export ANDROID_FLAGS="-DANDROID -fPIC -ffunction-sections -funwind-tables -fstack-protector-strong -target aarch64-linux-android24 -march=armv8-a -z text -Wno-unused-command-line-argument -Wno-unknown-warning-option -Wno-ignored-optimization-argument -Wl,--no-undefined -nostdlib++"
        export ANDROID_LIBS="-llog -lc++_static -lc++abi -ldl -lm -lc"
        ;;
    android-x86_64)
        export ANDROID_FLAGS="-DANDROID -fPIC -ffunction-sections -funwind-tables -fstack-protector-strong -target x86_64-linux-android24 -march=x86-64 -mtune=atom -z text -Wno-unused-command-line-argument -Wno-unknown-warning-option -Wno-ignored-optimization-argument -Wl,--no-undefined -nostdlib++"
        export ANDROID_LIBS="-llog -lc++_static -lc++abi -ldl -lm -lc"
        ;;
esac

WORKSPACE="$THIS_DIR/cppbuild/$PLATFORM"
STAMP="$WORKSPACE/.ffmpeg-$FFMPEG_VERSION-built"

echo "== cppbuild: platform=$PLATFORM workspace=$WORKSPACE tarball=$TARBALL"

# =============================================================================
# 共用函数
# =============================================================================

# 准备源码:解出 ffmpeg-$FFMPEG_VERSION 到 $WORKSPACE(幂等)
prepare_source() {
    if [[ ! -d "$WORKSPACE/ffmpeg-$FFMPEG_VERSION" || "$REBUILD" == 1 ]]; then
        rm -rf "$WORKSPACE"
        mkdir -p "$WORKSPACE"
        echo "-- unpacking $TARBALL"
        cp -f "$TARBALL" "$WORKSPACE/ffmpeg-$FFMPEG_VERSION.tar.xz"
        tar -xJf "$WORKSPACE/ffmpeg-$FFMPEG_VERSION.tar.xz" -C "$WORKSPACE"
    fi
    cd "$WORKSPACE/ffmpeg-$FFMPEG_VERSION"
}

# 配置 + 编译 + 安装(参数:configure 附加参数...)
# 注:LDEXEFLAGS 传给 FFmpeg configure,为共享库加 $ORIGIN rpath,
#     使各 .so 在 javacpp 解包目录内互相定位(与上游 bytedeco 脚本一致)。
build_ffmpeg() {
    # M4 修正:上游用 "$*" 拼接再 word-split,会把 --extra-cflags="-DANDROID -fPIC ..."
    # 之类带空格的选项拆坏(configure 报 Unknown option "-fPIC")。改为数组保引用。
    local -a extra=("$@")
    echo "-- configure: $(echo $FFMPEG_DISABLE $FFMPEG_ENABLE | tr '\n' ' ') ${extra[*]}"
    LDEXEFLAGS='-Wl,-rpath,$ORIGIN/' ./configure --prefix="$WORKSPACE" $FFMPEG_DISABLE $FFMPEG_ENABLE "${extra[@]}" || { echo "configure FAILED, config.log:"; cat ffbuild/config.log 2>/dev/null; exit 1; }
    echo "-- make -j $MAKEJ"
    make -j "$MAKEJ"
    echo "-- make install"
    make install
    # 去除 .so 版本软链接:javacpp 运行时按名加载(.so.61),jar 内不放软链接
    if [[ -d "$WORKSPACE/lib" ]]; then
        for f in "$WORKSPACE"/lib/*.so.*; do
            [[ -L "$f" ]] && { real="$(readlink -f "$f")"; cp -f --remove-destination "$real" "$f"; }
        done
    fi
    touch "$STAMP"
}

# =============================================================================
# 各平台构建
# =============================================================================
case "$PLATFORM" in

    linux-x86_64)
        if [[ -f "$STAMP" && "$REBUILD" == 0 ]]; then
            echo "== already built ($STAMP), use --rebuild to force"
            exit 0
        fi
        prepare_source
        # 本机 gcc 15;无 nasm → --disable-x86asm
        build_ffmpeg --disable-x86asm
        ;;

    linux-arm64)
        if [[ -f "$STAMP" && "$REBUILD" == 0 ]]; then
            echo "== already built ($STAMP), use --rebuild to force"
            exit 0
        fi
        # 交叉工具链 aarch64-linux-gnu-* 由系统提供或 javacpp 自动下载(见 STATUS.md/M4)
        command -v aarch64-linux-gnu-gcc >/dev/null || { echo "ERROR: aarch64-linux-gnu-gcc not found (need gcc-aarch64-linux-gnu)" >&2; exit 1; }
        prepare_source
        build_ffmpeg --enable-cross-compile --target-os=linux --arch=aarch64 --cpu=armv8-a \
            --cross-prefix="aarch64-linux-gnu-" --disable-asm
        ;;

    windows-x86_64)
        if [[ -f "$STAMP" && "$REBUILD" == 0 ]]; then
            echo "== already built ($STAMP), use --rebuild to force"
            exit 0
        fi
        command -v x86_64-w64-mingw32-gcc >/dev/null || { echo "ERROR: x86_64-w64-mingw32-gcc not found (need gcc-mingw-w64-x86-64)" >&2; exit 1; }
        prepare_source
        # 上游在 Windows 宿主上用 "--cc=gcc -m64";我们从 Linux 交叉,采用 mingw 交叉前缀
        # M4 修正:FFMPEG_ENABLE 全局的 --enable-pthreads 在 mingw32 上触发 pthreads 探测,
        # 其编译测试需 C23 头 <stdbit.h>(GCC 13-win32 无)→ "ERROR: pthreads requested but not found"。
        # Windows 默认线程后端本就是 w32threads:显式 --disable-pthreads --enable-w32threads(与上游一致)。
        build_ffmpeg --enable-cross-compile --target-os=mingw32 --arch=x86_64 \
            --cross-prefix="x86_64-w64-mingw32-" --disable-x86asm --disable-pthreads --enable-w32threads \
            --extra-ldflags="-static-libgcc"
        ;;

    windows-arm64)
        if [[ -f "$STAMP" && "$REBUILD" == 0 ]]; then
            echo "== already built ($STAMP), use --rebuild to force"
            exit 0
        fi
        # bytedeco 1.5.12 绑定层未发布 windows-arm64;需自备 aarch64-w64-mingw32 工具链(如 apt g++-mingw-w64-* 替代)
        # 参数对照 windows-x86_64 分支:aarch64 无 x86 asm → --disable-asm --disable-x86asm;
        # 线程后端与 x86_64 一致用 w32threads(mingw32 上 pthreads 探测缺 C23 头会失败)
        if command -v aarch64-w64-mingw32-gcc >/dev/null; then
            prepare_source
            build_ffmpeg --enable-cross-compile --target-os=mingw32 --arch=aarch64 \
                --cross-prefix="aarch64-w64-mingw32-" --cc=aarch64-w64-mingw32-gcc \
                --cxx=aarch64-w64-mingw32-g++ --disable-asm --disable-x86asm \
                --disable-pthreads --enable-w32threads --extra-ldflags="-static-libgcc"
        else
            echo "ERROR: windows-arm64 需要 aarch64-w64-mingw32-gcc,未安装;且绑定层无官方支持,放弃" >&2
            exit 1
        fi
        ;;

    android-arm64)
        if [[ -f "$STAMP" && "$REBUILD" == 0 ]]; then
            echo "== already built ($STAMP), use --rebuild to force"
            exit 0
        fi
        if [[ ! -x "$ANDROID_BIN/bin/clang" ]]; then
            echo "ERROR: Android NDK 未找到 ($ANDROID_NDK), 设置 ANDROID_NDK=/path/to/android-ndk/" >&2
            exit 1
        fi
        prepare_source
        # M4 修正:NDK 无 llvm-cc/llvm-gcc(原 --cross-prefix=.../bin/llvm- 指向不存在的二进制),
        # 改为显式指定 NDK clang 包装器 + llvm binutils
        build_ffmpeg --enable-cross-compile --target-os=android --arch=aarch64 \
            --cc="$ANDROID_BIN/bin/aarch64-linux-android24-clang" \
            --cxx="$ANDROID_BIN/bin/aarch64-linux-android24-clang++" \
            --ar="$ANDROID_BIN/bin/llvm-ar" --ranlib="$ANDROID_BIN/bin/llvm-ranlib" \
            --nm="$ANDROID_BIN/bin/llvm-nm" --strip="$ANDROID_BIN/bin/llvm-strip" \
            --sysroot="$ANDROID_ROOT" \
            --extra-cflags="$ANDROID_FLAGS" --extra-ldflags="$ANDROID_FLAGS" \
            --extra-libs="$ANDROID_LIBS -latomic" --disable-symver --disable-asm
        ;;

    android-x86_64)
        if [[ -f "$STAMP" && "$REBUILD" == 0 ]]; then
            echo "== already built ($STAMP), use --rebuild to force"
            exit 0
        fi
        if [[ ! -x "$ANDROID_BIN/bin/clang" ]]; then
            echo "ERROR: Android NDK 未找到 ($ANDROID_NDK), 设置 ANDROID_NDK=/path/to/android-ndk/" >&2
            exit 1
        fi
        prepare_source
        build_ffmpeg --enable-cross-compile --target-os=android --arch=x86_64 \
            --cc="$ANDROID_BIN/bin/x86_64-linux-android24-clang" \
            --cxx="$ANDROID_BIN/bin/x86_64-linux-android24-clang++" \
            --ar="$ANDROID_BIN/bin/llvm-ar" --ranlib="$ANDROID_BIN/bin/llvm-ranlib" \
            --nm="$ANDROID_BIN/bin/llvm-nm" --strip="$ANDROID_BIN/bin/llvm-strip" \
            --sysroot="$ANDROID_ROOT" \
            --extra-cflags="$ANDROID_FLAGS" --extra-ldflags="$ANDROID_FLAGS" \
            --extra-libs="$ANDROID_LIBS -latomic" --disable-symver --disable-asm
        ;;

    *)
        echo "ERROR: unsupported platform \"$PLATFORM\"" >&2
        exit 1
        ;;
esac

echo "== cppbuild done: $WORKSPACE"
ls -la "$WORKSPACE/lib" 2>/dev/null || true