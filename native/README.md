# musicmc — FFmpeg 原生构建(native 模块)

为 musicmc 构建**裁剪版 FFmpeg 7.1.5** 共享库 + javacpp JNI 包装,产出各平台 jar
`musicmc-native-<platform>.jar`(布局 `org/bytedeco/ffmpeg/<platform>/`,javacpp Loader 约定),
由 fabric(include)/neoforge(jarJar)嵌套进最终 mod jar。

与 bytedeco 官方的关系:API 绑定用官方 `org.bytedeco:ffmpeg:7.1.1-1.5.12`(7.1.x
libtool 版本一致,ABI 兼容 7.1.5);ffmpeg 本体用本地 7.1.5 源码自编译。
cppbuild 脚本 fork 自 javacpp-presets 1.5.12(原始备份 `toolbox/ffmpeg-cppbuild-original-1.5.12.sh`)。
详细决策与里程碑记录见 [STATUS.md](STATUS.md)。

## 协议

- FFmpeg 7.1.5 以 **LGPL 组件集** 裁剪编译:configure 白名单仅启用
  `libavcodec / libavformat / libavutil / libswresample` 及 aac/mp3/flac/opus/vorbis 等
  内置解码器(demuxer/parser 同源),**未启用任何 GPL 选项**(无 libx264/x265 等外部 GPL 库)。
- **解码组件按 LGPL-2.1+(FFmpeg 组合许可)分发;mod 整体按 GPL-3.0 分发**(与根 README 一致,
  GPL-3.0 可包含 LGPL-2.1+ 代码)。打包时保留 FFmpeg 许可声明;二进制再分发遵守对应条款。

## 平台矩阵(2026-08-17 现状)

| 平台 | 状态 | 工具链 | 平台 jar | 大小 |
|---|---|---|---|---|
| linux-x86_64 | ✅ M2 完成(冒烟通过) | 宿主 gcc/g++ 15 | `musicmc-native-linux-x86_64.jar` | 1,616,431 B |
| linux-arm64 | ✅ M4 完成 | `g++-aarch64-linux-gnu`(系统) | `musicmc-native-linux-arm64.jar` | 1,598,261 B |
| windows-x86_64 | ✅ M5 完成(2026-08-17) | `g++-mingw-w64-x86-64`(系统) | `musicmc-native-windows-x86_64.jar` | 1,774,675 B |
| windows-arm64 | ✅ 完成(2026-08-17) | llvm-mingw(数据分区) | `musicmc-native-windows-arm64.jar` | 2,216,633 B |
| android-arm64 | ✅ M4 完成 | NDK r27b clang(API 24) | `musicmc-native-android-arm64.jar` | 1,659,923 B |
| android-x86_64 | ✅ M4 完成 | NDK r27b clang(API 24) | `musicmc-native-android-x86_64.jar` | 1,708,782 B |

成功判据:两阶段(build.sh)走完 + build.sh 尾部 DT_NEEDED 校验
`OK: all libs depend only on standard system libs / sibling libs`。
linux 产物带版本号(`libavcodec.so.61` 等,SONAME 与 bytedeco 绑定一致);android 不带版本号
(`libavcodec.so`,android 平台 jars 惯例)。windows 产物为 PE32+ DLL:javacpp JNI 库命名 `libjni*.dll`,ffmpeg 库按 preload 名
`avcodec-61.dll / avformat-61.dll / avutil-59.dll / swresample-5.dll`;校验用
`x86_64-w64-mingw32-objdump -p` / `aarch64-w64-mingw32-objdump -p` 查 **DLL Name**
(只含系统 dll + 同级兄弟 dll,无 libwinpthread;**arm64 另附 libc++.dll/libunwind.dll**,
因 llvm-mingw libc++ 动态链接)。

## 依赖安装

### linux-arm64(Ubuntu/Debian)

```bash
sudo apt-get install -y gcc-aarch64-linux-gnu g++-aarch64-linux-gnu
# 提供 aarch64-linux-gnu-gcc/g++(build.sh/cppbuild.sh 直接调用)
```

### windows-x86_64(已装,2026-08-17)

```bash
sudo apt-get install -y g++-mingw-w64-x86-64
# 提供 x86_64-w64-mingw32-g++(cppbuild 阶段仅需 gcc,Builder 阶段必须 g++)
# 本机已安装;装时注意:目标是系统盘(/),当前 / 只剩 5.4G,安装前确认余量
```

Builder 阶段经 `cppbuild/ffmpeg/wrap/{x86_64-,mingw-}g++.sh` 过滤宿主链接标志;
产物 `.so` 后缀实为 PE DLL,打包前改名为 `libjni*.dll`(javacpp 预载名),ffmpeg bin/
下的 dll 一并复制进 `org/bytedeco/ffmpeg/windows-x86_64/`(含 swresample-5.dll,勿漏)。
校验用 `x86_64-w64-mingw32-objdump -p` 查 **DLL Name**(只含系统 dll + 同级兄弟 dll)。

### windows-arm64(已装,llvm-mingw,2026-08-17)

`/media/cyf112233/data/llvm-mingw/`(数据分区):`bin/aarch64-w64-mingw32-g++` + 同目录
binutils;C++ 运行库 dll 在 `aarch64-w64-mingw32/bin/libc++.dll`、`libunwind.dll`。

```bash
# build.sh 已处理:PATH 前置、平台伪装(-Dplatform=windows-x86_64 借用 x86_64 链接规则)
bash native/build.sh windows-arm64
```

bytedeco 1.5.12 官方平台列表**无 windows-arm64**(绑定/加载分支缺失),靠伪装完成
Builder;且 **llvm-mingw 的 libc++ 是动态链接** → 打包时需从
`/media/cyf112233/data/llvm-mingw/aarch64-w64-mingw32/bin/` 附带
`libc++.dll`+`libunwind.dll`(`ldd`/DLL Name 校验:不可 static-libstdc++,与 x86_64 不同)。
产物目录 10 个 dll(4 libjni + 4 ffmpeg + 2 C++ 运行库);
**运行时 Loader 无该平台映射 → 需自解包 + System.load(待办)**。

### android(NDK r27b,官方地址)

```bash
# 下载到数据分区(约 634MB 下载 / 解压后约 2GB),勿占系统盘
mkdir -p /media/cyf112233/data/android-ndk
wget -c --tries=0 --timeout=60 --waitretry=5 \
  -O /media/cyf112233/data/android-ndk/android-ndk-r27b-linux.zip \
  https://dl.google.com/android/repository/android-ndk-r27b-linux.zip
cd /media/cyf112233/data/android-ndk && unzip -q -o android-ndk-r27b-linux.zip
export ANDROID_NDK=/media/cyf112233/data/android-ndk/android-ndk-r27b
```

- r27b 链接问题时可换 r26d:https://dl.google.com/android/repository/android-ndk-r26d-linux.zip
- cppbuild.sh 与 build.sh 共用同一约定:`$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin` +
  `.../sysroot`;两阶段均识别 `ANDROID_NDK` 环境变量。
- r27b 的 sysroot 平台 stub 库按 API 分目录(`usr/lib/<abi>/<api>/`),clang 按
  `-target aarch64-linux-android24` 自动定位,无需手动 `-L`。

## 构建命令

方式一:Gradle 任务(推荐,自动传入 bytedeco jar 路径与平台参数)

```bash
./gradlew :native:buildNative -PnativePlatform=linux-arm64
ANDROID_NDK=/path/to/android-ndk-r27b ./gradlew :native:buildNative -PnativePlatform=android-arm64
./gradlew :native:packageNative -PnativePlatform=android-x86_64   # = buildNative + 打 jar
```

方式二:直调脚本(等价,fabric/neoforge 构建不依赖它)

```bash
bash native/build.sh <platform> [--skip-build]           # linux-arm64 | windows-x86_64 | ...
ANDROID_NDK=/path/android-ndk bash native/build.sh android-arm64
```

`--skip-build` 跳过阶段 1,复用 cppbuild 缓存只跑 javacpp Builder(改绑定/参数后快速重编)。

两阶段说明:
1. `native/cppbuild/ffmpeg/cppbuild.sh install -platform=<p> --tarball=` — configure +
   make -j8 + make install → `native/cppbuild/ffmpeg/cppbuild/<p>/{lib,include,bin}`;
2. javacpp Builder(global.avcodec 等 + 包内 class)→ `native/build/native/<p>/...` JNI 包装库。

### 全平台打入 mod jar(-PnativePlatform=all)

fabric/neoforge 的依赖块读取 `-PnativePlatform`(默认 `linux-x86_64` 单平台,行为与此前完全一致);
传 `all` 时把 **6 个 native 平台 jar + 5 个 javacpp 官方平台坐标 + javacpp-windows-arm64 本地文件**
(native/build/libs 下,bytedeco 1.5.12 无该平台坐标)全部嵌套进最终 mod jar:

```bash
./gradlew --no-daemon :fabric:build :neoforge:build -PnativePlatform=all
```

前置条件:`native/build/libs` 需已存在 7 个文件:6 个 `musicmc-native-<platform>.jar`
(由各平台 `:native:packageNative` 产出)+ `musicmc-javacpp-windows-arm64.jar`
(javacpp 1.5.12 无 windows-arm64 坐标,该本地文件随仓库 `native/toolbox/` 提供,CI 亦从
该处复制;缺失时 all 模式构建会抛出明确错误)。all 模式**不会**触发 native 重建(不依赖
`project(":native")`),避免 android 平台在无 `ANDROID_NDK` 环境下构建失败。

产物(2026-08-17 实测;fabric 嵌套于 `META-INF/jars/`,neoforge 嵌套于 `META-INF/jarjar/`):

| 目标 | 单平台 | all 模式 | 嵌套内容 |
|---|---|---|---|
| fabric jar | 3,505,930 B | 14,817,750 B | `META-INF/jars/` 16 个 jar:6 native + 6 javacpp 平台(5 坐标 + 1 本地文件)+ ffmpeg/javacpp/gson/zxing |
| neoforge jar | 5,225,164 B | 16,534,827 B | `META-INF/jarjar/` 17 项(含 metadata.json):同上 12 个平台 jar + kotlin-stdlib;metadata 中文件型条目按模块名记录 |

DSL 要点(两个 build.gradle.kts 均已实现,注释已注明):
- **fabric(Loom 1.17.19)**:`include` 只接受模块构件,file 依赖在 `processIncludeJars` 报
  "not a module component and has no capabilities";且 `JarNester` 只嵌套含 `fabric.mod.json`
  的 "mod jar"。故 7 个本地文件先经 `prepareInclude*` 任务复制为补合成 `fabric.mod.json`
  (仿 Loom 对 include 产物合成的 json)的副本,再由 `NestJarsAction`(Loom 内部同一机制)
  追加进 mod jar;副本同时挂 `implementation` 供 dev 运行期 classpath 可见。
- **neoforge(MDG 2.0.144)**:`jarJar` 任务的 `run()` 对"文件型"依赖强制要求 manifest 携带
  `Automatic-Module-Name`(否则 GradleException)。故 7 个本地文件先经 `prepareJarjar*` 任务
  写 `Automatic-Module-Name` 副本再 `jarJar(files(...))`;副本同时挂 `implementation`。

## GitHub Actions CI(native 六平台矩阵)

仓库已配两个工作流(均在 ubuntu-latest、temurin JDK 25 上运行):

| 工作流 | 触发 | 内容 |
|---|---|---|
| `.github/workflows/native-build.yml` | workflow_dispatch / push(限 `native/**` 与自身文件) | `native` job 六平台矩阵并行(`linux-x86_64 / linux-arm64 / windows-x86_64 / windows-arm64 / android-arm64 / android-x86_64`):`bash native/build.sh <platform>` 两阶段构建 → 打平台 jar → artifact `musicmc-native-<platform>`;`assemble-all` job 下载 6 个 jar + `native/toolbox/` 的 javacpp-windows-arm64.jar 到 `native/build/libs/` → `-PnativePlatform=all` 打全平台 mod jar(artifact `mod-fabric-all` / `mod-neoforge-all`) |
| `.github/workflows/build-mod.yml` | workflow_dispatch(`nativePlatform`: linux-x86_64 / all;`native_run_id` 可选) | 手动打 mod jar;all 模式填 `native_run_id`(指向一次成功的 native-build run)下载 6 个平台 jar,第 7 个 jar 由 `native/toolbox/` 补,缺文件在 Gradle 前显式报错 |

缓存键(actions/cache,跨 run 复用;命中即免下载工具链 / 免重编 ffmpeg):

| 缓存 | 键 | 内容 |
|---|---|---|
| llvm-mingw(仅 windows-arm64) | `llvm-mingw-20260616` | `~/llvm-mingw`(官方 release 20260616,msvcrt-ubuntu-22.04-x86_64) |
| Android NDK(android-* 两平台共用) | `android-ndk-r27b` | `~/android-ndk-r27b` |
| cppbuild 编译树(每平台独立) | `cppbuild-<platform>-ffmpeg-7.1.5-<cppbuild.sh sha256 前 8 位>` | `native/cppbuild/ffmpeg/cppbuild/<platform>/`(脚本内容变更自动失效) |

CI 与本地差异(脚本已参数化,缺省值保持本机路径,行为不变):

- `build.sh` windows-arm64 分支:PATH 改由 `LLVM_MINGW_DIR` 提供(`export PATH="${LLVM_MINGW_DIR:-/media/cyf112233/data/llvm-mingw}/bin:$PATH"`);
  `cppbuild/ffmpeg/wrap/aarch64-g++.sh` 的 `REAL_GPP` 支持环境变量覆盖
  (`${REAL_GPP:-/media/cyf112233/data/llvm-mingw/bin/aarch64-w64-mingw32-g++}`)。
  CI 的 llvm-mingw 安装步骤经 `$GITHUB_ENV` 写入 `LLVM_MINGW_DIR` / `REAL_GPP` 并把 `bin/`
  加入 PATH。
- windows 平台 DLL 归一化(改 `libjni*.dll`、从 cppbuild `bin/` 复制 ffmpeg dll、arm64 附带
  `libc++.dll`/`libunwind.dll`)由工作流 "Normalize Windows DLL layout" 步骤完成,等价本机
  STATUS.md 记录的"产物整理"手工步骤。
- 其余平台不依赖本机路径:linux-x86_64 用宿主 gcc/g++;linux-arm64 / windows-x86_64 由 apt
  安装交叉工具链;android-* 安装 NDK 并导出 `ANDROID_NDK`。

## 缓存与产物位置(全部在数据分区)

| 内容 | 路径 |
|---|---|
| cppbuild 工作区(源码解包 + lib/include,含印章 `.ffmpeg-7.1.5-built`) | `native/cppbuild/ffmpeg/cppbuild/<platform>/` |
| Builder 输出(共享库 + libjni*) | `native/build/native/<platform>/org/bytedeco/ffmpeg/<platform>/` |
| 平台 jar | `native/build/libs/musicmc-native-<platform>.jar` |
| 构建用 jar 本地备份(可省带宽) | `native/toolbox/(javacpp-1.5.12.jar, ffmpeg-7.1.1-1.5.12.jar, javacpp-1.5.12-linux-x86_64.jar, ...)` |
| ~/.gradle(bytedeco 解析缓存,勿删) | 系统盘(本机已占 5.5G) |

印章判定:build.sh 见 `cppbuild/<p>/.ffmpeg-7.1.5-built` + `lib/libavcodec.so.61` 时跳过阶段 1;
`rm` 印章或 `--rebuild` 强制重编。

## 常见问题

1. **qemu 禁用**:全部平台均为交叉编译(`--enable-cross-compile` + `--cross-prefix` / NDK clang),
   cppbuild.sh 内**没有任何 qemu 路径**(已 grep 排查)。交叉产物不要用 qemu 跑冒烟;
   宿主平台(linux-x86_64)的冒烟测试才具可执行性,其余平台靠 DT_NEEDED 静态校验。
2. **磁盘要求**:每平台编译树约 0.5–1GB,NDK 解压约 2GB。系统盘(`/`)需 ≥ 8–10G 余量
   (gradle 缓存 + apt 工具链);**所有构建产物/下载务必放数据分区**
   (本机 `/media/cyf112233/data`,剩余 26G)。每步 `df -h /` 确认没写爆。
3. **--enable-network**:当前裁剪为 `--disable-network`,仅 `file` protocol —— 本地文件解码
   冒烟、离线运行没问题;**B 站在线流需要** `--enable-network --enable-protocol=http,https,tcp,tls`
   并携带 TLS 后端(openssl 等)重编,回填 STATUS.md M5 待办。
4. **configure 报 `Unknown option "-fPIC"` 之类**:多词选项(如 `--extra-cflags="-DANDROID -fPIC..."`)
   被 `"$*"` 拼接再拆分导致;`build_ffmpeg()` 已改为 bash 数组保引用。改脚本时勿退回 `"$*"`。
5. **--disable-asm**:linux-arm64 / android 均禁用汇编(无 nasm;arm 侧无汇编器依赖),
   裁剪解码场景性能影响可忽略。
6. **windows 产物命名**:javacpp Builder 生成的是 `.so` 后缀的 PE DLL,复制为
   `libjni*.dll` 才符合 javacpp Windows 预载名;ffmpeg dll 从 `cppbuild/.../bin/` 复制并按
   preload 名对齐。**swresample 最易漏拷**(`avcodec-61.dll` 依赖 `swresample-5.dll`)。
   x86_64 为 8 个 dll(jni+ffmpeg);**arm64 共 10 个**(另附 `libc++.dll`+`libunwind.dll`,
   因 llvm-mingw 的 libc++ 动态链接、不可静态化)。无 pthread 依赖,无需改过滤器。
7. **NDK 版本**:r27b 实测可用(2024-09);configure 报引入的
   `--cross-prefix=.../bin/llvm-` 已改为显式 `--cc/--cxx/--ar/--ranlib/--nm/--strip` +
   `--sysroot`(r27b 无 `llvm-cc` 二进制,NDK 只有 clang 包装器与 llvm-binutils)。
8. **交叉工具链与 Builder**:javacpp Builder 的交叉编译需用 `-Dplatform.compiler`
   覆盖宿主默认编译器(build.sh 已按平台配好),否则会把 x86_64 宿主参数混入。