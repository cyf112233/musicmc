#!/usr/bin/env bash
# =============================================================================
# musicmc native 构建入口:两阶段封装
#   阶段 1 (cppbuild):  native/cppbuild/ffmpeg/cppbuild.sh
#                       编译裁剪后的 FFmpeg 7.1.5 共享库 → native/cppbuild/ffmpeg/cppbuild/<platform>/
#   阶段 2 (builder):   javacpp Builder CLI
#                       由 ffmpeg.jar 的 presets 生成并编译 JNI 包装库 → native/build/native/<platform>/
#
# 用法:
#   bash build.sh <platform> [--skip-build]
#     <platform>   linux-x86_64 | linux-arm64 | windows-x86_64 | windows-arm64 | android-arm64 | android-x86_64
#     --skip-build 跳过阶段 1,复用 native/cppbuild/ffmpeg/cppbuild/<platform> 的缓存
#
# 环境变量(可选):
#   FFMPEG_JAR / JAVACPP_JAR  两个 jar 的路径(由 :native:buildNative 传入;缺省用 native/toolbox/)
#   JAVA_HOME                 缺省自动探测 java 所在 JDK
#   LLVM_MINGW_DIR            windows-arm64 交叉工具链根目录(缺省 /media/cyf112233/data/llvm-mingw)
#   ANDROID_NDK               android 平台必需(NDK 根目录)
# =============================================================================
set -eu

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"        # <native>/
if [[ $# -lt 1 || -z "$1" ]]; then
    echo "Usage: bash build.sh <platform> [--skip-build]" >&2
    echo "  <platform>   linux-x86_64 | linux-arm64 | windows-x86_64 | windows-arm64 | android-arm64 | android-x86_64" >&2
    exit 1
fi
CPPBUILD_SH="$ROOT/cppbuild/ffmpeg/cppbuild.sh"
PREFIX="$ROOT/cppbuild/ffmpeg/cppbuild/$1"
PLATFORM="$1"
SKIP_BUILD=0
[[ "${2:-}" == "--skip-build" ]] && SKIP_BUILD=1

if [[ -z "${FFMPEG_JAR:-}" ]]; then FFMPEG_JAR="$ROOT/toolbox/ffmpeg-7.1.1-1.5.12.jar"; fi
if [[ -z "${JAVACPP_JAR:-}" ]]; then JAVACPP_JAR="$ROOT/toolbox/javacpp-1.5.12.jar"; fi
for f in "$FFMPEG_JAR" "$JAVACPP_JAR"; do
    [[ -f "$f" ]] || { echo "ERROR: missing jar: $f (set FFMPEG_JAR/JAVACPP_JAR)" >&2; exit 1; }
done

# 探测 JDK(jni.h 所在,Builder 编译 JNI 需要)
if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_BIN="$(readlink -f "$(command -v java)")"
    JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
fi
for j in "$JAVA_HOME/include" "$JAVA_HOME/include/linux"; do
    [[ -d "$j" ]] || { echo "ERROR: JDK include dir missing: $j" >&2; exit 1; }
done

OPT=()
[[ "$SKIP_BUILD" == 1 ]] && OPT=(--skip-build)

# windows-arm64:交叉工具链位于 llvm-mingw;须在阶段 1 (cppbuild) 之前加入 PATH——
# cppbuild.sh 经 --cross-prefix="aarch64-w64-mingw32-" 派生 ar/ranlib/as 等,
# 且其 command -v aarch64-w64-mingw32-gcc 检查依赖本 PATH。
BUILDER_PLATFORM="$PLATFORM"   # 平台伪装 hack:见下方 windows-arm64 分支
if [[ "$PLATFORM" == "windows-arm64" ]]; then
    export PATH="${LLVM_MINGW_DIR:-/media/cyf112233/data/llvm-mingw}/bin:$PATH"
fi

echo "============================================================"
echo "  stage 1/2: cppbuild (FFmpeg $PLATFORM)"
echo "  prefix:    $PREFIX"
echo "============================================================"
# 各平台"已构建"判定产物不同:linux 带 SONAME 版本号(lib/libavcodec.so.61);
# windows DLL 装在 bin/(avcodec-61.dll);android 不带版本号(lib/libavcodec.so)。
# 修 2026-08:原判定只认 linux 产物,其余平台命中印章也总是重跑阶段 1(虽被
# cppbuild.sh 自己的印章短路,但日志误导、且多一次 configure 探测)。
case "$PLATFORM" in
    windows-*) BUILT_MARKER="$PREFIX/bin/avcodec-61.dll" ;;
    android-*) BUILT_MARKER="$PREFIX/lib/libavcodec.so" ;;
    *)         BUILT_MARKER="$PREFIX/lib/libavcodec.so.61" ;;
esac
if [[ "$SKIP_BUILD" == 0 ]] && [[ -f "$PREFIX/.ffmpeg-7.1.5-built" ]] && [[ -f "$BUILT_MARKER" ]]; then
    echo "  -- ffmpeg already built ($PREFIX/.ffmpeg-7.1.5-built), skipping stage 1 (use: rm stamp 或 cppbuild --rebuild 以强制重建)"
else
    bash "$CPPBUILD_SH" install -platform="$PLATFORM" --tarball="$ROOT/ffmpeg/ffmpeg-7.1.5.tar.xz"
fi
# 说明:Builder 直接以 global.* 类为输入(继承 presets @Properties),不做头文件重解析。

echo "============================================================"
echo "  stage 2/2: javacpp Builder (JNI for $PLATFORM)"
echo "============================================================"
OUT="$ROOT/build/native/$PLATFORM"
rm -rf "$OUT"
mkdir -p "$OUT"

RESOURCE_INCLUDE="$ROOT/cppbuild/ffmpeg/resource-include"   # javacpp 生成的辅助头(libavutil/log_callback.h)

# 各平台 Builder 参数(compiler / 附加 include / 附加 linkpath / 附加 -Xcompiler)
# 注:javacpp 会把宿主(linux-x86_64)的 platform.compiler.default(-march=x86-64 -m64)
# 混入交叉编译,必须用 -Dplatform.compiler.default 覆盖。
case "$PLATFORM" in
    linux-x86_64)
        COMPILER=g++
        DEF_FLAGS=""
        EXTRA_INC="$JAVA_HOME/include:$JAVA_HOME/include/linux"
        EXTRA_LINKPATH=()
        EXTRA_CC=()
        ;;
    linux-arm64)
        # 交叉编译:x86_64 宿主 + aarch64-linux-gnu 工具链(已装:g++-15-aarch64-linux-gnu)
        COMPILER=aarch64-linux-gnu-g++
        DEF_FLAGS="-O3 -s"
        EXTRA_INC="$JAVA_HOME/include:$JAVA_HOME/include/linux"
        EXTRA_LINKPATH=()
        EXTRA_CC=()
        ;;
    windows-x86_64)
        # 交叉编译:x86_64 宿主 + mingw-w64 工具链(已装:g++-mingw-w64-x86-64)
        # 编译/链接均经 wrap/x86_64-g++.sh 过滤 mingw 不接受的宿主链接标志
        COMPILER="$ROOT/cppbuild/ffmpeg/wrap/x86_64-g++.sh"
        LINKER="$ROOT/cppbuild/ffmpeg/wrap/x86_64-g++.sh"
        DEF_FLAGS="-march=x86-64 -m64 -O3 -s -static-libgcc -static-libstdc++"
        EXTRA_INC="$JAVA_HOME/include:$JAVA_HOME/include/linux"
        EXTRA_LINKPATH=()
        EXTRA_CC=(-Wl,--kill-at)
        ;;
    windows-arm64)
        # 交叉编译:需先安装 aarch64-w64-mingw32-g++ 工具链;编译/链接均经包装器过滤
        # 平台伪装 hack:bytedeco 1.5.12 绑定层不认识 windows-arm64,借用 windows-x86_64
        # 的链接规则(-Dplatform=windows-x86_64);产物目录仍走 $PLATFORM=windows-arm64 的
        # OUT 路径,因 -d 用 $OUT/org/bytedeco/ffmpeg/$PLATFORM。
        COMPILER="$ROOT/cppbuild/ffmpeg/wrap/aarch64-g++.sh"
        LINKER="$ROOT/cppbuild/ffmpeg/wrap/aarch64-g++.sh"
        BUILDER_PLATFORM=windows-x86_64
        # DEF_FLAGS 覆盖 windows-x86_64 默认的 -march=x86-64 -m64,防 x86 标志泄漏进 aarch64 产物
        DEF_FLAGS="-O3 -s"
        EXTRA_INC="$JAVA_HOME/include:$JAVA_HOME/include/linux"
        EXTRA_LINKPATH=()
        EXTRA_CC=()
        ;;
    android-arm64|android-x86_64)
        if [[ -z "${ANDROID_NDK:-}" ]]; then
            echo "ERROR: android 平台需要 ANDROID_NDK=/path/to/android-ndk/ 环境变量" >&2
            exit 1
        fi
        NDK_BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
        NDK_SYSROOT="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
        if [[ "$PLATFORM" == android-arm64 ]]; then
            COMPILER="$NDK_BIN/aarch64-linux-android21-clang++"
            ABI_DIR=aarch64-linux-android
        else
            COMPILER="$NDK_BIN/x86_64-linux-android21-clang++"
            ABI_DIR=x86_64-linux-android
        fi
        # -static-libstdc++: JNI 库静态链接 libc++,不再依赖 libc++_shared.so。
        # FCL 的 JVM(libjvm.so)已 dlopen 自己的 libc++_shared.so,Android linker
        # namespace 隔离(clns-N)拒绝再加载同名库,必须让 libjni*.so 自带 C++ 运行库。
        # -llog: javacpp 运行时在 Android 上用 __android_log_vprint 打日志(liblog.so 提供),
        # 不链接则 dlopen 时报 "cannot locate symbol __android_log_vprint"。
        # 注意: 这些 -l 标志经 -Dplatform.compiler.default 同时进编译/链接命令行,
        # 编译(-c)阶段会被忽略,链接阶段生效;liblog.so 是 Android 公开系统库,
        # NDK clang 内建 sysroot 可直接 -llog 解析,运行时任何 namespace 均可访问。
        DEF_FLAGS="-O3 -static-libstdc++ -llog"
        EXTRA_INC="$NDK_SYSROOT/usr/include:$NDK_SYSROOT/usr/include/$ABI_DIR"
        EXTRA_LINKPATH=()
        EXTRA_CC=()
        ;;
    *)
        echo "ERROR: unsupported platform $PLATFORM" >&2
        exit 1
        ;;
esac

BUILDER_ARGS=(-classpath "$FFMPEG_JAR" -d "$OUT/org/bytedeco/ffmpeg/$PLATFORM" -Dplatform=$BUILDER_PLATFORM -Dplatform.compiler=$COMPILER)
if [[ -n "${LINKER:-}" ]]; then BUILDER_ARGS+=(-Dplatform.linker="$LINKER"); fi
if [[ -n "$DEF_FLAGS" ]]; then BUILDER_ARGS+=(-Dplatform.compiler.default="$DEF_FLAGS"); fi
BUILDER_ARGS+=(-Dplatform.includepath="$RESOURCE_INCLUDE:$PREFIX/include:$EXTRA_INC" -Dplatform.linkpath="$PREFIX/lib" -copylibs)
for cc in "${EXTRA_CC[@]}"; do BUILDER_ARGS+=(-Xcompiler "$cc"); done
BUILDER_ARGS+=(org.bytedeco.ffmpeg.global.avcodec org.bytedeco.ffmpeg.global.avformat org.bytedeco.ffmpeg.global.avutil org.bytedeco.ffmpeg.global.swresample org.bytedeco.ffmpeg.avcodec.* org.bytedeco.ffmpeg.avformat.* org.bytedeco.ffmpeg.avutil.* org.bytedeco.ffmpeg.swresample.*)

java -cp "$JAVACPP_JAR:$FFMPEG_JAR" org.bytedeco.javacpp.tools.Builder "${BUILDER_ARGS[@]}"



# ------------------------------------------------------------
#  stage 3/2 (android only): AAudio 音频输出库
#  AAudio(API 26+)独立于 OpenSL/OpenAL,无 Engine/设备共享冲突 —— 是 Android 上
#  唯一确定不崩溃的播放通道(OpenSL 直调与 MC OpenAL 的 OpenSL Engine 冲突 SIGSEGV;
#  自建 OpenAL 设备破坏 MC SoundEngine)。产物 musicmc/audio/<platform>/libmusicmc_audio.so
#  (独立资源路径;Java 侧由 NativeLibBridge 解包 + System.load)。
# ------------------------------------------------------------
if [[ "$PLATFORM" == android-* ]]; then
    AUDIO_DIR="$OUT/musicmc/audio/$PLATFORM"
    mkdir -p "$AUDIO_DIR"
    if [[ -n "${ABI_DIR:-}" ]]; then
        # AAudio 需 API 26+;用 android26 clang 变体(内置对应 sysroot/宏)。
        "$NDK_BIN/$ABI_DIR"26-clang -shared -fPIC -O2 \
            -I"$NDK_SYSROOT/usr/include" -I"$NDK_SYSROOT/usr/include/$ABI_DIR" \
            "$ROOT/audio/aaudio_player.c" -o "$AUDIO_DIR/libmusicmc_audio.so" \
            -laaudio -llog -lm
        echo "  -- audio lib: $AUDIO_DIR/libmusicmc_audio.so"
        ls -la "$AUDIO_DIR/"
    else
        echo "ERROR: ABI_DIR undefined (android 分支 case 未执行?)" >&2
        exit 1
    fi
fi

echo "============================================================"
echo "  build done. artifacts:"
# -copylibs 会额外拷贝预设变体 preload 的系统库(libva/libdrm/libasound/vchiq/mmal/...)——
# 本构建未启用对应功能,这些副本只污染产物;移除(随后校验无 NEEDED 缺口)
rm -f "$OUT"/org/bytedeco/ffmpeg/$PLATFORM/libva* "$OUT"/org/bytedeco/ffmpeg/$PLATFORM/libdrm* \
      "$OUT"/org/bytedeco/ffmpeg/$PLATFORM/libasound* "$OUT"/org/bytedeco/ffmpeg/$PLATFORM/libvchiq* \
      "$OUT"/org/bytedeco/ffmpeg/$PLATFORM/libvcos* "$OUT"/org/bytedeco/ffmpeg/$PLATFORM/libvcsm* \
      "$OUT"/org/bytedeco/ffmpeg/$PLATFORM/libbcm_host* "$OUT"/org/bytedeco/ffmpeg/$PLATFORM/libmmal* 2>/dev/null || true
# android:libjni*.so 已用 -static-libstdc++ 静态链接 libc++(见 android 分支 DEF_FLAGS),
# 不再依赖 libc++_shared.so(FCL 的 JVM 已 dlopen 自己的 libc++_shared.so,Android
# linker namespace 隔离会拒绝重复加载同名库)。下述校验段将确认无 libc++_shared 依赖。
# 校验:我们的 .so 只应依赖标准系统库或本输出目录内的兄弟库
BAD=0
OUT_LIBS=""; for f in "$OUT"/org/bytedeco/ffmpeg/$PLATFORM/lib*.so*; do OUT_LIBS="$OUT_LIBS $(basename "$f")"; done
for f in "$OUT"/org/bytedeco/ffmpeg/$PLATFORM/lib*.so*; do
    deps=$(objdump -p "$f" 2>/dev/null | grep NEEDED | awk '{print $2}' | grep -vE '^(libc|libm|libpthread|libdl|libstdc|libgcc|linux-vdso|ld-linux|liblog)' || true)
    for d in $deps; do
        case " $OUT_LIBS " in
            *" $d "*) continue ;;   # 自家兄弟库,放行
        esac
        echo "WARN: $f needs non-standard lib: $d (not in output dir)"
        BAD=1
    done
done
[[ "$BAD" == 0 ]] && echo "OK: all libs depend only on standard system libs / sibling libs"
ls -la "$OUT/org/bytedeco/ffmpeg/$PLATFORM/"
echo "============================================================"