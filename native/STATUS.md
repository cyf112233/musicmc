# musicmc FFmpeg 原生构建 — 状态日志

> 按里程碑 (M1–M5) 推进,每个里程碑结束时在此记录。最后更新:见各里程碑小节时间戳。

---

## 环境与决策(本代理接手时确认)

### 磁盘(2026-08-17 检查)
- `/` :**剩 5.5G(96% 已用)— 低于 15G 阈值,已报告**。
  - `~/.gradle`(在 `/`)已占用 5.5G;后续**所有**原生构建产物、工具链、jar 一律放
    `/media/cyf112233/data/netmusicmc/native/`(该挂载点剩 29G),避免写爆 `/`。
- `/tmp` 是 3.7G tmpfs(内存盘):上一个代理的临时文件(已把关键件拷入 `native/toolbox/`),
  tmpfs 内容重启即失,勿作为唯一副本。
- `/media/cyf112233/data` :301G,剩 29G(91% 已用)—— 构建输出放这里,安全。

### 版本决策(重要)
- 查 maven-metadata(`org/bytedeco/ffmpeg`):**bytedeco 没有 `7.1.5-*` 发布版**。
  7.1.x 线最新是 `7.1.1-1.5.12`,之后直接跳到 FFmpeg 8.x(`8.0.1-1.5.13` / `8.1.2-1.5.14`)。
- **采用**:`org.bytedeco:ffmpeg:7.1.1-1.5.12`(绑定 API)+ `org.bytedeco:javacpp:1.5.12`。
  理由:7.1.x 系列 libtool 版本号不变(avcodec 61 / avformat 61 / avutil 59 / swresample 5),
  ABI 与本地源码 7.1.5 构建完全兼容;源码用 7.1.5,绑定用 7.1.1。
- 确认的链接名(presets @Platform):`avutil@.59`、`swresample@.5`、`avcodec@.61`、`avformat@.61`
  → 产物须为 `libavutil.so.59 / libswresample.so.5 / libavcodec.so.61 / libavformat.so.61`。

### 架构(复用上一个代理的调研,避免 gradle-javacpp 插件)
- 不用 `org.bytedeco.gradle-javacpp`(Gradle 9.7 兼容风险)。
- 两条命令链,由 gradle Exec 任务驱动:
  1. `bash native/cppbuild/ffmpeg/cppbuild.sh`(fork 自 bytedeco 1.5.12 ffmpeg/cppbuild.sh,见该文件头注释)
  2. javacpp Builder CLI(见 `native/build.sh`)。
- Conventions(from bytedeco javacpp-presets):
  - cppbuild 安装前缀:`native/cppbuild/ffmpeg/cppbuild/<platform>/`(lib/ include/ bin/)
  - javacpp Builder classOrPackageName:按预设平台 pom 等价为 `org.bytedeco.ffmpeg.presets.{avcodec,avformat,avutil,swresample}`
    (avcodec inherit swresample inherit avutil,链式覆盖全部 4 个 global 类)
  - 平台资源路径:`org/bytedeco/ffmpeg/<platform>/`(官方 platform jar 同款布局)
  - 每个 global 类一个 JNI lib:`libjniavcodec.so / libjniavformat.so / libjniavutil.so / libjniswresample.so`
  - 运行时还需 `org.bytedeco:javacpp:1.5.12:linux-x86_64`(提供 `org/bytedeco/javacpp/linux-x86_64/libjnijavacpp.so`)

### bytedeco 1.5.12 ffmpeg 官方支持的平台(platform/pom.xml)
`android-arm64, android-x86_64, linux-x86_64, linux-arm64, macosx-x86_64, macosx-arm64, windows-x86_64`
**没有 windows-arm64** —— M4 时如实记录(绑定层未支持,大概率放弃)。

---

## M1 校验与模块骨架(完成)

时间:`2026-08-17 15:3x`

### 源码包校验
- `native/ffmpeg/ffmpeg-7.1.5.tar.xz` = `de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f`
- ffmpeg.org 无公开 SHA256SUMS(401/404),但有 .asc PGP 签名:
  - `gpg --verify` 格式校验通过:2026-06-20 由 FFmpeg 发布密钥
    `FCF986EA15E6E293A5644F10B4322F04D67658D8` 签名(密钥服务器拉到的 key 无 user ID,未能完成 Web-of-Trust 校验)。
  - **权威源重新下载比对**:从 `https://www.ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz`
    重新下载后 sha256 与本地完全一致 → 传输/落盘完整性确认。**结论:校验通过,无需重下。**

### 目录结构(native/)
```
native/
├── STATUS.md                 # 本文件
├── build.sh                  # 两阶段封装(cppbuild + javacpp Builder),支持 --skip-build
├── build.gradle.kts          # native 模块:两个普通 jar 依赖 + buildNative Exec 任务
├── ffmpeg/ffmpeg-7.1.5.tar.xz
├── cppbuild/ffmpeg/cppbuild.sh   # fork 的裁剪构建脚本
├── cppbuild/ffmpeg/cppbuild/<platform>/   # 构建产物前缀(cppbuild 阶段)
├── toolbox/                  # 预先下载的构建用 jar 与测试素材
│   ├── javacpp-1.5.12.jar / javacpp-1.5.12-sources.jar
│   ├── javacpp-1.5.12-linux-x86_64.jar      # libjnijavacpp.so(运行期必需)
│   ├── ffmpeg-7.1.1-1.5.12.jar              # 绑定 API(224 classes)
│   ├── ffmpeg-pom-1.5.12.xml
│   ├── ffmpeg-cppbuild-original-1.5.12.sh   # fork 前的原始脚本备份
│   └── full_audio.m4s                       # 冒烟测试素材(6MB,bilibili)
└── build/                   # 构建输出(gitignore 已覆盖 build/)
```

### settings.gradle.kts
已 `include("native")`。

### native/build.gradle.kts
- `bytedeco` 配置:非 transitive 解析 `ffmpeg` + `javacpp` 两个普通 jar(不拉平台列化产物)。
- `:native:buildNative` Exec 任务:调 `bash build.sh <nativePlatform>`
  (nativePlatform 默认 `linux-x86_64`,M3 在 gradle.properties 落该项)。
- 依赖 javacpp Builder 可直接运行(JDK 26 上的 javacpp 1.5.12 正常执行)。

### 裁剪配置(最终,记录偏差)
```
--disable-everything --disable-programs --disable-doc --disable-avdevice --disable-avfilter
--disable-swscale --disable-postproc --disable-network
--enable-shared --enable-pic --enable-pthreads --enable-swresample
--enable-avcodec --enable-avformat --enable-avutil
--enable-decoder=aac,mp3,mp3adu,mp3on4,flac,opus,vorbis
--enable-demuxer=mov,matroska,mp3,flac,ogg,opus,aac
--enable-parser=aac,flac,mpegaudio,opus,vorbis
--enable-protocol=file
```
相对任务清单的偏差(均记录):
- demuxer 无 `mp4`/`m4v`/`webm` 名:FFmpeg 中 `mp4`/`m4v` 由 `mov` demuxer 覆盖、`webm` 由 `matroska` 覆盖(直接传名会 configure 报错)。
- 增加 `--enable-protocol=file`:没有 file protocol,avformat_open_input 打不开本地文件,冒烟测试无法进行。
- 增加 `--enable-pthreads`(解码线程)、`--enable-avcodec/avformat/avutil`(白名单后显式保险)。
- 网络协议(http/https/tcp/tls)未开(遵守 `--disable-network`)。**备注**:后续接入 B 站在线流需要
  `--enable-network --enable-protocol=http,...` + TLS 后端,记入 M5 文档。
- linux-x86_64 加 `--disable-x86asm`(本机无 nasm,裁剪解码场景性能影响可忽略)。
- 上游 patch 组(ffmpeg.patch/ffmpeg-vulkan.patch)不对 7.1.5 启用:补丁基于 7.1.1,裁剪后无需其修复项;若编译出错再评估。

### 下一步(M2)
1. `bash native/build.sh linux-x86_64`(cppbuild → Builder)
2. 产物检查:lib{avcodec,avformat,avutil,swresample}+libjni* 
3. 冒烟测试(临时目录)后回写本节。

---

## M2 宿主平台 linux-x86_64 全链(完成)

时间:`2026-08-17 15:5x`

### 构建命令链(最终形态,均验证通过)
- 阶段 1:`native/cppbuild/ffmpeg/cppbuild.sh install -platform=linux-x86_64 --tarball=...` → 安装前缀 `native/cppbuild/ffmpeg/cppbuild/linux-x86_64/`
- 阶段 2:`java -cp javacpp.jar:ffmpeg.jar org.bytedeco.javacpp.tools.Builder ...`(参数见 `native/build.sh`)
- 入口:`./gradlew :native:buildNative`(Exec → `bash build.sh linux-x86_64`)
- 缓存复用:`build.sh` 检查 `cppbuild/.../<platform>/.ffmpeg-7.1.5-built` 印章;`--skip-build` 强制只跑 Builder。
- 全链耗时:约 50s(cppbuild 40s + Builder 20s),`make -j8`。

### 过程中踩坑与结论(对 M4 复用至关重要)
1. **Builder 输入必须是 `global.*` + 包内 `.class` 类,不能只给 `presets.*`**:
   - 只给 presets 类会触发 C 头文件**重新解析**(cinclude),要重新生成绑定;
     而 ffmpeg.jar 已含现成绑定 → 直接以 `global.avcodec` 等 + `org.bytedeco.ffmpeg.avcodec.*`(包内类,自带
     `@Properties(inherit=presets.xxx.class)` 继承链接/加载信息)编译即可,不重解析。
   - 若仍想重解析(改绑定时),需要 include 全部头 + 生成目录,本仓库不需要。
2. **log_callback.h 缺失**:javacpp 生成的辅助头(av_log_set_callback 用)在 1.5.12 发布 jar 中被移除,
   编译期需要放到 include 路径。已从 presets 源码拷到 `native/cppbuild/ffmpeg/resource-include/log_callback.h`
   并在 build.sh 的 `-Dplatform.includepath` 首位注入。
3. **configure 必须显式关掉自动探测的硬件/压缩依赖**(否则污染 DT_NEEDED):
   `--disable-vaapi --disable-vulkan --disable-libdrm --disable-bzlib --disable-zlib`。
   不加时 libavcodec/lavutil 会硬链接系统 libva/libdrm,用户机器无这些库时 mod 加载直接崩。
4. **共享库需要 `$ORIGIN` rpath**:上游脚本在每个平台 configure 前设 `LDEXEFLAGS='-Wl,-rpath,$ORIGIN/'`,
   fork 版已加。否则 javacpp 解包目录内各 .so 互相找不到(冒烟时 ldd "not found")。
5. **libva/libdrm 残留副本**:`-copylibs` 因 presets 里 `@Platform(value="linux-x86", preload={va@.1,...})`
   前缀匹配到 linux-x86_64 而拷贝系统 libva/libdrm;运行时 Loader 对 preload 失败是宽容的(catch UnsatisfiedLinkError
   不抛出)。build.sh 尾段已删除这些副本 + 用 objdump 校验 DT_NEEDED 只含标准库/自家库。

### 产物检查
目录 `native/build/native/linux-x86_64/org/bytedeco/ffmpeg/linux-x86_64/`:
```
libavcodec.so.61       921KB   (官方全量 35.8MB)
libavformat.so.61      553KB
libavutil.so.59        949KB
libswresample.so.5     100KB
libjniavcodec.so       176KB   (javacpp JNI 包装)
libjniavformat.so      196KB
libjniavutil.so        765KB
libjniswresample.so     89KB
```
DT_NEEDED 校验:仅 libav*/libm/libc/libstdc++ 等,无 libva/libdrm/bz2/zlib。

### 冒烟测试(临时目录 /tmp/opencode/smoke,不进仓库)
`FfmpegSmoke.java`:`avformat_open_input(full_audio.m4s) → 找 audio 流 → avcodec 解码 3 帧`。
素材 `/tmp/opencode/full_audio.m4s`(bilibili fMP4,6MB,已备份到 native/toolbox/)。
输出(节选):
```
Input #0, mov,mp4,m4a,3gp,3g2,mj2: bilibili fMP4, aac (LC), 48000 Hz, stereo, fltp, 167 kb/s
== audio stream #0: codec=aac rate=48000 channels=2 fmt=fltp bit_rate=167516
frame#0 pts=0 nb_samples=1024 rate=48000 ch=2 fmt=fltp
frame#1 pts=1024 nb_samples=1024 rate=48000 ch=2 fmt=fltp
frame#2 pts=2048 nb_samples=1024 rate=48000 ch=2 fmt=fltp
== RESULT: decoded 3 frames (read 3 packets)
== SMOKE TEST PASSED
```
**结论:M2 完成,解码链路可用。**

### 下一步(M3)
- `:native:packageNative` 产物 jar;gradle.properties 落 `nativePlatform`
- fabric include / neoforge jarJar 打进最终 mod jar;跑 `:fabric:build` `:neoforge:build`
- 记录"下一步:引擎接入 FFmpeg 解码"

---

## M3 打包与接入 mod(完成)

时间:`2026-08-17 16:0x`

### native 模块打包
- `gradle.properties` 新增 `nativePlatform=linux-x86_64`(native/build.gradle.kts 读取,默认 linux-x86_64)。
- `:native:packageNative`:`build/native/<platform>` → `native/build/libs/musicmc-native-<platform>.jar`
  (1.62MB 压缩),布局 `org/bytedeco/ffmpeg/linux-x86_64/{libavcodec,libavformat,libavutil,libswresample}.so.6x +
  libjni{avcodec,avformat,avutil,swresample}.so`,与官方 platform jar 路径一致(javacpp Loader 约定)。
- native 项目声明 `version`(取 mod_version=0.1.0),并把平台 jar 挂到 "default" 消费配置
  → fabric/neoforge 用 `project(":native")` 依赖即解析到该 jar。

### mod 集成
- **fabric**:`implementation(project(":native"))` + `include(project(":native"))`
  → 最终 `musicmc-fabric-0.1.0.jar`(4.9MB)内含 `META-INF/jars/musicmc-native-linux-x86_64.jar`。
- **neoforge**:`implementation(project(":native"))` + `jarJar(project(":native"))`
  → 最终 `musicmc-neoforge-0.1.0.jar`(6.6MB)内含
  `META-INF/jarjar/io.github.cyf112233.musicmc.musicmc-native-linux-x86_64.jar` + metadata.json。
- `:fabric:build` / `:neoforge:build` 均 BUILD SUCCESSFUL,未破坏现有构建(common 源码照常并入两模块)。
- jar 内 .so 的 DT_NEEDED 仅标准系统库/兄弟库,无 libva/libdrm/zlib/bz2 依赖。

### 运行时加载(本步只保证 lib 进包,未做引擎重构)
**下一步:引擎接入 FFmpeg 解码。** 计划要点(供后续代理/开发者):
  1. 在 common 引擎层新增 FFmpeg 解码路径:fMP4(avformat demux)→ aac/opus/flac(avcodec decode)→
     swresample 转 48k/stereo/float → 现有音频输出管线。
  2. 依赖坐标:`org.bytedeco:ffmpeg:7.1.1-1.5.12` + `org.bytedeco:javacpp:1.5.12`
     (需 resolver 引入;lib 文件由 native 平台 jar 提供,API 类需从 common 的依赖注入)。
  3. 运行期 Loader 会从 classpath 资源 `org/bytedeco/ffmpeg/linux-x86_64/` 提取 .so(
     fabric 的 META-INF/jars / neoforge 的 META-INF/jarjar 嵌套 jar 已在 classpath 上)。
  4. 冒烟用例:native/toolbox/FfmpegSmoke.java(临时,已跑通)。

---

## M4 其余 5 平台(任务小时盒推进,结果逐平台记录)

时间:`2026-08-17 16:1x` 起,每平台时间盒 45 分钟。

### qemu 排查结论(重要)
- **fork 的 `native/cppbuild/ffmpeg/cppbuild.sh` 全文无任何 qemu 路径**(grep `qemu`
  无命中):无 qemu-aarch64 调用、无 qemu 工具链下载分支。6 个平台分支全部是
  标准的 `--enable-cross-compile --cross-prefix=...`(linux/windows)或 NDK clang(android)
  交叉编译路径。**无需删除/绕过,直接可用。**

### linux-arm64 ✅ 成功(2026-08-17 16:14–16:15,58s)
- 命令:`bash native/build.sh linux-arm64`(阶段1 cppbuild + 阶段2 javacpp Builder)
- 工具链:系统 `aarch64-linux-gnu-gcc/g++`(Ubuntu gcc 15.2.0),aarch64 交叉 sysroot 在位
  (`/usr/aarch64-linux-gnu/include`);configure 加了 `--cpu=armv8-a`(用户要求),
  保持 `--disable-asm`(无 nasm/汇编器依赖,裁剪解码场景性能影响可忽略)。
- 产物 `native/build/native/linux-arm64/org/bytedeco/ffmpeg/linux-arm64/`:
  ```
  libavcodec.so.61     855768 B   libjniavcodec.so    659088 B
  libavformat.so.61    528640 B   libjniavformat.so   593768 B
  libavutil.so.59      723776 B   libjniavutil.so    1317624 B
  libswresample.so.5   133528 B   libjniswresample.so 133888 B
  ```
- build.sh 尾部 NEEDED 校验:`OK: all libs depend only on standard system libs / sibling libs`
- 平台 jar:`native/build/libs/musicmc-native-linux-arm64.jar` = **1,598,261 B**(hand-jar 与
  `:native:packageNative` 等价布局 `org/bytedeco/ffmpeg/linux-arm64/`)。
- 说明:aarch64 二进制约为 x86_64 体积的 60–80%同裁剪配置;交叉产物**不做本机冒烟测试**
  (宿主 x86_64,禁 qemu),可信度靠 NEEDED 静态校验 + 与 x86_64 同源同配置构建。

### windows-x86_64 ⏭ 跳过(工具链不完整,2026-08-17 16:1x)
- `which x86_64-w64-mingw32-g++` → 无;`apt list --installed | grep mingw` 显示只有
  gcc/binutils 系(`gcc-mingw-w64-x86-64`、`binutils-mingw-w64-x86-64` 已装,提供
  `x86_64-w64-mingw32-gcc`),**缺 `g++-mingw-w64-x86-64`(提供 g++)**。
- cppbuild 阶段(仅需 gcc)本可构建,但阶段 2 javacpp Builder 强制需要
  `x86_64-w64-mingw32-g++`,无 g++ 无法完成全链 → 按用户要求记录并跳过。
- **需安装命令**(恢复此平台时执行):`sudo apt-get install -y g++-mingw-w64-x86-64`
  (约 60MB,安装目标是 `/` 分区 ***勿写爆 5.4G 剩余盘***,装完重跑 `bash native/build.sh windows-x86_64`)。
- cppbuild.sh windows-x86_64 分支已就绪:`--enable-cross-compile --target-os=mingw32
  --arch=x86_64 --cross-prefix=x86_64-w64-mingw32- --disable-x86asm
  --extra-ldflags=-static-libgcc`;build.sh Builder 侧已配
  `-Dplatform.compiler=x86_64-w64-mingw32-g++ -Wl,--kill-at`。

### windows-arm64 ⏭ 跳过 → 已收尾完成(2026-08-17,见文末"M windows-arm64 收尾")
- 当时 `which aarch64-w64-mingw32-g++` 无(llvm-mingw 工具链未安装);
  且 STATUS.md M0 已确认 **bytedeco 1.5.12 绑定层官方无 windows-arm64 平台 jar**
  (platform/pom.xml 列表无该项)。
- 后续:数据分区安装 llvm-mingw(`/media/cyf112233/data/llvm-mingw/`),并按
  **平台伪装 hack**(-Dplatform=windows-x86_64 借用 x86_64 链接规则)完成全链
  (cppbuild 产物在 `cppbuild/windows-arm64/`,Builder 产物走 `build/native/windows-arm64/`,
  见文末记录)。

### android-arm64 ✅ 成功(2026-08-17 17:05–17:06,36s)
- 命令:`ANDROID_NDK=/media/cyf112233/data/android-ndk/android-ndk-r27b bash native/build.sh android-arm64`
- NDK:**r27b**(2024-09,官方 zip 663,976,775 B,下载见下),解压后 2.0G 于数据分区。
- cppbuild.sh android 分支 **M4 修正两点**(否则无法构建):
  1. `ANDROID_ROOT` 原为空 → `--sysroot` 失效;改为 `$ANDROID_BIN/sysroot`(r27b 实际位置)。
  2. 原 `--cross-prefix="$ANDROID_PREFIX-"`(`.../bin/llvm-`)指向不存在的 `llvm-cc/llvm-gcc`;
     改为显式 `--cc/--cxx=aarch64-linux-android24-clang(++) --ar/--ranlib/--nm/--strip=llvm-*`。
- 另修 `build_ffmpeg()` 的 `"$*"` 拼接 bug:多词选项(如 `--extra-cflags="-DANDROID -fPIC ..."`)
  被拆坏导致 configure 报 `Unknown option "-fPIC"`;改数组 `"${extra[@]}"` 保引用。
  该 bug 只影响 android(windows/linux 分支的无空参数选项此前未被触发)。
- 产物 `native/build/native/android-arm64/org/bytedeco/ffmpeg/android-arm64/`(android 惯例不带版本号):
  ```
  libavcodec.so    802816 B   libjniavcodec.so  850560 B
  libavformat.so   500888 B   libjniavformat.so 748376 B
  libavutil.so     667296 B   libjniavutil.so  1681224 B
  libswresample.so  94768 B   libjniswresample.so 139584 B
  ```
- NEEDED 校验 ```OK: all libs depend only on standard system libs / sibling libs```;ELF 确认为
  **aarch64, for Android 24, built by NDK r27b**。
- 平台 jar:`musicmc-native-android-arm64.jar` = **1,659,923 B**。

### android-x86_64 ✅ 成功(2026-08-17 17:06–17:07,37s)
- 命令同上(platform=android-x86_64;clang 为 `x86_64-linux-android24-clang(++)`)。
- 产物:`libavcodec.so` 846632 B / `libavformat.so` 520896 B / `libavutil.so` 720664 B /
  `libswresample.so` 104712 B + `libjni{avcodec 790856,avformat 699656,avutil 1582968,swresample 128584}.so`。
- NEEDED 校验 OK;ELF 确认为 **x86-64, for Android 24, built by NDK r27b**。
- 平台 jar:`musicmc-native-android-x86_64.jar` = **1,708,782 B**。
- 注:android 两平台均用 API 24 clang 编译(`-target ...android24`),build.sh Builder 侧用
  `aarch64/x86_64-linux-android21-clang++`(r27b 均含),链接无冲突。

### NDK 下载(已完成,数据分区,断点续传)
- 目标:`/media/cyf112233/data/android-ndk/android-ndk-r27b-linux.zip`(URL:
  `https://dl.google.com/android/repository/android-ndk-r27b-linux.zip`)
- 总大小 663,976,775 B(~634MB),16:16 开始,16:2x 完成(峰值 ~2.4MB/s);
  `wget -c --tries=0 --timeout=60 --waitretry=5` 可断点续传。
- 解压至 `/media/cyf112233/data/android-ndk/android-ndk-r27b/`(2.0G),zip 仍保留占 634MB。
- cppbuild.sh / build.sh 对 NDK 的期望路径与变量名一致:
  `$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/{clang,<triple><api>-clang,llvm-ar,...}`
  + `$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot`;分别以 `ANDROID_NDK` 环境变量传入。
- r27b sysroot 布局注意:平台 stub 库(`liblog.so` 等)按 API 分目录
  `sysroot/usr/lib/<abi>/<api>/`,NDK clang 按 `-target ...android24` 自动定位,无需手动 `-L`。

### M4 汇总(2026-08-17 17:1x)

| 平台 | 状态 | 耗时 | 平台 jar 大小 | 备注 |
|---|---|---|---|---|
| linux-arm64 | ✅ | 58s | 1,598,261 B | 交叉工具链;NEEDED OK |
| windows-x86_64 | ✅ M5 收尾 | 补装 g++ 后全链 | 1,774,675 B | DLL Name 校验 OK(无 pthread/gen 依赖) |
| windows-arm64 | ✅ 收尾完成 | llvm-mingw(见文末节) | 2,216,633 B | 附 libc++/libunwind;Loader 无映射待办 |
| android-arm64 | ✅ | 36s | 1,659,923 B | NDK r27b clang,API 24 |
| android-x86_64 | ✅ | 37s | 1,708,782 B | NDK r27b clang,API 24 |

- qemu 排查结论:cppbuild 与 build.sh **全文无 qemu 路径**(grep 零命中),全部交叉编译,无需改动。
- 时间盒:各平台远低于 45 分钟(android 两平台各约 35s、linux-arm64 58s),无超时记录。
- 脚本修改累计(均已在本文档记录):cppbuild.sh linux-arm64 加 `--cpu=armv8-a`;android 分支
  改显式 clang/llvm-binutils + 真 sysroot;`build_ffmpeg()` 改数组保引用。
- 收尾验证:`> Task :native:buildNative / :native:packageNative` 照常执行(阶段1 印章跳过),
  `./gradlew :fabric:build :neoforge:build` **BUILD SUCCESSFUL in 43s**,产物
  `musicmc-fabric-0.1.0.jar` 4,877,873 B(内嵌 `META-INF/jars/musicmc-native-linux-x86_64.jar`)、
  `musicmc-neoforge-0.1.0.jar` 6,596,648 B(内嵌 jarjar native jar)—— **打包链路未坏**。
- 磁盘:M4 全程 `/` 保持 5.4G 未写爆;数据分区 29G→26G(NDK 2.6G + 4 平台 cppbuild 树 ~665MB)。
- 文档:native/README.md 已建(平台矩阵/依赖/构建命令/缓存位置/FAQ);根 README 增补
  "FFmpeg 原生构建"章节与许可说明。
- **下一步待办**(不变):引擎接入 FFmpeg 解码(见 M3 运行时加载);B 站在线流需
  `--enable-network + http/https/tls` 重编。windows-x86_64 已补装 g++ 完成全链(见 M5)。

---

## M5 windows-x86_64 平台收尾(完成)

时间:`2026-08-17 18:3x`

### 前置(补装工具链)
- M4 时缺 `g++-mingw-w64-x86-64`(仅 gcc);本次按 README 安装命令补装,
  `x86_64-w64-mingw32-g++` 就位后 `bash native/build.sh windows-x86_64 --skip-build`
  完成 javacpp Builder 阶段(JNI 包装库生成,18:31)。

### 包装脚本(实际路径与记录修正)
- 过滤器实际在 **`native/cppbuild/ffmpeg/wrap/`**:`mingw-g++.sh`(公共过滤器)+
  `x86_64-g++.sh`(入口,设 `REAL_GPP=x86_64-w64-mingw32-g++` 后转交)。
  过滤清单:`-Wl,-z,noexecstack`、`-Wl,-Bsymbolic`、`-Wl,-rpath,$ORIGIN/`
  (javacpp Builder 注入的宿主链接标志,mingw 不认)。
- **未加 `-pthread` 过滤**:objdump 证实全部 dll **无 `libwinpthread-1.dll` 依赖**
  (ffmpeg 裁剪构建,win32 侧用原生同步/w32threads,不需要 pthread;且 DEF_FLAGS 已带
  `-static-libgcc -static-libstdc++`,无 libgcc/libstdc++ 运行时依赖)→ 第 4 步跳过。

### dll 命名(按 bytedeco preload 名对齐)
- javacpp Builder 产物是 `.so` 后缀的 **PE DLL**(file 识别为 "PE32+ (DLL) x86-64"),
  须复制为 `.dll` 才能被 Windows Loader 按预载名找到:
  `libjniavcodec.so → libjniavcodec.dll`(jniavformat / jniavutil / jniswresample 同)。
- ffmpeg 库从 `cppbuild/ffmpeg/cppbuild/windows-x86_64/bin/` 复制,按 presets
  `@Platform(preload=...)` 预载名对齐(`avcodec@.61` → `avcodec-61.dll`):
  `avcodec-61.dll` `avformat-61.dll` `avutil-59.dll` `swresample-5.dll`。

### swresample 拷贝坑(记牢)
- objdump 证实 **`avcodec-61.dll` 依赖 `swresample-5.dll`**(及 avutil-59.dll),
  `libjniswresample.dll` 也依赖它 → swresample 在 4 个 ffmpeg dll 里最易漏拷,
  漏了 javacpp 预载 avcodec 时直接 UnsatisfiedLinkError。必须一并复制。
- 与 linux jar 对照:linux 侧同样携带 `libswresample.so.5`(此坑跨平台存在)。

### DLL Name 校验(替代 linux 的 DT_NEEDED)
- `x86_64-w64-mingw32-objdump -p *.dll | grep 'DLL Name'`:全部只含
  **系统 dll**(KERNEL32 / msvcrt / ole32 / bcrypt / USER32)+ **同级兄弟 dll**
  (avcodec-61 / avformat-61 / avutil-59 / swresample-5 版本名互依赖),无任何
  libwinpthread-1.dll / libgcc / libstdc++。
- `file` 确认 8 个文件均为 **PE32+ (DLL), x86-64, for MS Windows**。

### 产物与打包
- 目录 `native/build/native/windows-x86_64/org/bytedeco/ffmpeg/windows-x86_64/`:8 个 dll
  (`libjni{avcodec,avformat,avutil,swresample}.dll` + `avcodec-61/avformat-61/avutil-59/swresample-5.dll`)。
- 平台 jar:`native/build/libs/musicmc-native-windows-x86_64.jar` = **1,774,675 B**,
  布局与 linux jar 一致(`org/bytedeco/ffmpeg/windows-x86_64/` 8 个 dll + META-INF/MANIFEST.MF)。
- aarch64 mingw:**未装**(`aarch64-w64-mingw32-g++` 无);且 bytedeco 1.5.12 无
  windows-arm64 绑定,维持放弃结论,不安装。

---

## windows-arm64 平台收尾(完成)✅

时间:`2026-08-17 20:2x–20:34`(产物收尾 20:30;jar 打包 20:34)

### 工具链(来源与路径)
- **llvm-mingw**(aarch64 cross-g++ / clang 系工具链),解压于数据分区
  `/media/cyf112233/data/llvm-mingw/`(**不占系统盘**):
  - 交叉编译器:`/media/cyf112233/data/llvm-mingw/bin/aarch64-w64-mingw32-g++`
  - binutils(objdump/readobj):同目录 `aarch64-w64-mingw32-objdump` /
    通用 `llvm-readobj`(`--coff-imports` 可查 DLL Name)
  - C++ 运行库 dll:`/media/cyf112233/data/llvm-mingw/aarch64-w64-mingw32/bin/`
    `libc++.dll` + `libunwind.dll`
- build.sh windows-arm64 分支:阶段 1 前 `export PATH=/media/cyf112233/data/llvm-mingw/bin:$PATH`
  (cppbuild.sh 的 `command -v aarch64-w64-mingw32-gcc` 检查与 `--cross-prefix=aarch64-w64-mingw32-`
  派生 ar/ranlib/as 均依赖该 PATH);Builder 编译器走
  `cppbuild/ffmpeg/wrap/aarch64-g++.sh`(`REAL_GPP=.../llvm-mingw/bin/aarch64-w64-mingw32-g++` →
  公共 `mingw-g++.sh` 过滤 `-Wl,-z,noexecstack / -Wl,-Bsymbolic / -Wl,-rpath,$ORIGIN/`)。
- cppbuild 配置:`--enable-cross-compile --target-os=mingw32 --arch=aarch64
  --cross-prefix=aarch64-w64-mingw32- --cc=...-gcc --cxx=...-g++ --disable-asm --disable-x86asm`。

### 平台伪装 hack(核心)
- bytedeco 1.5.12 绑定层**不认识 windows-arm64** 平台(platform/pom.xml 无该项),
  javacpp Builder 按平台名查链接规则会失败 → `build.sh` 中
  `BUILDER_PLATFORM=windows-x86_64` 伪装:`-Dplatform=windows-x86_64` 借用 x86_64 的
  链接/预载规则,但 `-d $OUT/org/bytedeco/ffmpeg/windows-arm64` 产物目录仍按真实平台
  (OUT 路径由 `$PLATFORM` 决定)。绑定类/库名(avcodec@.61 等)两平台一致,可复用。
- 同时 `DEF_FLAGS="-O3 -s"` **覆盖 windows-x86_64 分支的 `-march=x86-64 -m64`**,
  防止 x86 标志泄漏进 aarch64 产物。
- 代价:javacpp 生成的 `libjni*.dll` 属性里平台串仍是 windows-x86_64 的加载约定
  → **运行时 Loader 不认 windows-arm64 资源路径,须自解包 + System.load(待办,见下)**。

### 产物与校验
- `objdump -p` 确认 `libjniavcodec.so`(Builder 产物,PE DLL `file coff-arm64`):
  `DLL Name: avcodec-61.dll / avutil-59.dll / **libc++.dll** / **libunwind.dll** / msvcrt.dll / KERNEL32.dll`
  - 与 windows-x86_64 不同:g++/GCC 系 mingw 用 `-static-libstdc++` 把 C++ 运行库
    静态链入,而 **llvm-mingw 的 libc++ 采用动态链接** → 必须附带 `libc++.dll` + `libunwind.dll`。
- 全套 10 个 dll 依赖闭包校验通过:各有依赖(avformat→avcodec→swresample、libc++→libunwind)
  **全部落在"系统 dll(msvcrt/ole32/bcrypt/USER32/KERNEL32)+ 输出目录同级 dll"**,
  无缺失、无 libwinpthread 等意外依赖。
- `file` 校验:10 个 dll 全部 `PE32+ executable for MS Windows 6.00 (DLL), ARM64`。

### 产物整理与打包(与 windows-x86_64 流程一致)
1. 4 个 Builder 产物 `libjni{avcodec,avformat,avutil,swresample}.so` 复制为 `.dll`
   (javacpp 预载名),删除 `.so`(与 x86_64 目标目录只留 `.dll` 对齐)。
2. 从 `cppbuild/ffmpeg/cppbuild/windows-arm64/bin/` 复制 4 个 ffmpeg dll
   `avcodec-61.dll / avformat-61.dll / avutil-59.dll / swresample-5.dll`
   (swresample 最易漏:avcodec-61.dll 依赖它)。
3. **C++ 运行库附带**:从 `/media/cyf112233/data/llvm-mingw/aarch64-w64-mingw32/bin/`
   复制 `libc++.dll` + `libunwind.dll` 进同一输出目录。
4. 最终目录 `native/build/native/windows-arm64/org/bytedeco/ffmpeg/windows-arm64/`:**10 个 dll**。
5. 平台 jar:`jar cf ../../libs/musicmc-native-windows-arm64.jar org`
   = **2,216,633 B**(比 x86_64 的 1,774,675 B 大,主要是附带 2.3MB libc++/libunwind),
   布局与 linux jar 对齐(`org/bytedeco/ffmpeg/windows-arm64/` 10 个 dll + META-INF/MANIFEST.MF)。

### 待办(记录在案)
- **Loader 无 windows-arm64 映射**:javacpp 1.5.12 Loader 的
  `org/bytedeco/javacpp/loader/windows-arm64/`(或平台表)没有对应资源路径/加载分支,
  "资源自解包 + System.load" 需在引擎接入 FFmpeg 解码时实现
  (从 classpath 提取 `org/bytedeco/ffmpeg/windows-arm64/*.dll` 到临时目录后
  按依赖序 `System.load`),或升级到支持 windows-arm64 的 bytedeco 版本。
- 其余不变:引擎接入 FFmpeg 解码(M3 运行时加载);B 站在线流需 `--enable-network` 重编。

---

## M6 播放引擎接入 FFmpeg 解码(完成)

时间:`2026-08-17 21:0x`

### 目标与结果
播放引擎从"嗅探魔数 + 三种专用解码器(jlayer/jflac/jaad)"重构为 **FFmpeg 主引擎 +
旧引擎回退**:所有格式(AAC/MP3/FLAC/Opus 等)统一由 avformat/avcodec/swresample 解码,
HTTP 流经自定义 AVIO 拉流并支持精确 seek。离线冒烟 + 真实 B 站 URL 冒烟全部通过。

### common 新增(common/src/main/kotlin/io/github/cyf112233/musicmc/player/)
- `ffmpeg/FfmpegDecoder.kt`:纯解码核心(不依赖 javax.sound):
  - 自定义 AVIO:`avio_alloc_context`(64KB 缓冲)+ Java 读/seek 回调
    —— **javacpp 运行时 Java 回调可行性经源码+实测证实**:回调类的 protected 无参构造器
    调用 `private native allocate()`(libjniavformat.so 内置 JNI 蹦床),C 调用时虚分发回
    Java 覆写的 `call()`,无需 Builder/编译器;
  - read 回调走 `Http.openStreamInfo`(带 Referer);seek 回调按 offset 关闭旧流重开
    (206 直接定位 / 200 丢弃 offset 字节);缓存当前 InputStream + 逻辑位置;
  - open 全候选(url + backupUrls)重试;`av_seek_frame`(流 time_base 换算 + BACKWARD)
    失败回退"重开 + 丢弃";cleanup 顺序与所有权按 FFmpeg 7.1 源码核验:
    avcodec_free_context → swr_free → avformat_close_input(CUSTOM_IO 不负责任 pb)
    → avio_context_free → av_free(avio.buffer())(探测时 FFmpeg 可能内部 realloc 替换 buffer,
    avio.buffer() 恒为当前存活缓冲);
  - 原生资源全手动管理(无 PointerScope),close 幂等可重入。
- `ffmpeg/FfmpegAudioEngine.kt`:实现 AudioEngine,线程/暂停锁/音量/会话守卫/攒批写
  (PcmBatcher 64KB 阈值)照 M4aAudioEngine 模式;load 时 native 不可用同步抛
  `FfmpegUnavailableException`(不触碰回调)供上层回退。
- `AdaptiveAudioEngine`:load 一律先走 Ffmpeg 引擎,捕获 FfmpegUnavailableException 才
  回退旧嗅探分发(Flac/Mp3/M4a 行为的旧路径完整保留)。
- `DecodedAudio`(s16 交错 LE 字节 + rate/channels/ptsMs)、`ModConfig.nativePlatformOverride`
  (Pojav 平台覆盖:NetMusic.init 里在首次 FFmpeg 加载前写
  System property `org.bytedeco.javacpp.platform`)。

### 依赖接入
- gradle.properties:`ffmpeg_api_version=7.1.1-1.5.12`、`javacpp_version=1.5.12`。
- common:implementation(ffmpeg + javacpp,isTransitive=false;POM 本就只依赖 javacpp 非平台)。
- fabric:implementation + include 三件套(ffmpeg、javacpp、javacpp 平台 classifier=平台名)。
- neoforge:同三件套走 jarJar。
- **体积实测**(远低于预估的 +6-10MB):
  | 产物 | 之前 | 之后 | 新增 |
  |---|---|---|---|
  | musicmc-fabric-0.1.0.jar | 4.88MB | 5.67MB | +0.79MB |
  | musicmc-neoforge-0.1.0.jar | 6.60MB | 7.38MB | +0.78MB |
  新增 = ffmpeg 272KB + javacpp 522KB + javacpp 平台 48KB(三者均为压缩后尺寸;native 平台
  jar 1.6MB 在 M3 已嵌入)。jar 内布局:fabric `META-INF/jars/` / neoforge `META-INF/jarjar/`
  + metadata.json 3 条 bytedeco 条目齐全。

### 冒烟(临时目录 /tmp/opencode/ffmpeg-smoke/,不进仓库)
- 探针 Probe.java:本地文件经自定义 AVIO(FileInputStream + RandomAccessFile seek)打开 fMP4
  成功;av_seek_frame 30s 后帧 pts=29994.67ms;swr 转 S16 输出 4096B/帧。
- 正式冒烟 FfmpegSmoke.java(调用 common 编译产物,FfmpegDecoder 公开 API):
  - Test A(本地文件 openLocal):rate=48000 ch=2,dur=269760ms;pts0/1/2=0/21/42ms,
    帧字节 4096;seek 30s → 29994ms。**PASS**
  - Test B(B 站真实 HTTPS + 自定义 AVIO):search→view(cid)→playurl 取 44100Hz 音频直链,
    open(baseUrl, referer, backups) 解码 2 帧;seek 30s → 29976ms(读/seek 回调全程工作)。
    **PASS**
  - 输出留存 `/tmp/opencode/ffmpeg-smoke/smoke-output.txt`。
- 构建:`:common:compileKotlin` → `:fabric:build` → `:neoforge:build` 全绿。

### 风险与待办(如实记录)
1. **自定义 AVIO 的 seek 在无 Range CDN 的行为**:CDN 忽略 Range 返回 200 时,seek 回调退化为
   全量重下 + 丢弃 offset 字节(与旧 Flac/M4a 引擎同类策略);B 站 CDN 实测支持 Range(206),
   路径为最优。
2. **javacpp 指针释放**:全部手动管理,close 幂等;`avio.buffer()` 经 FFmpeg 7.1 源码核验
   避免双释放(探测 realloc 时旧缓冲已由 FFmpeg 释放)。
3. **AVIO 缓冲区所有权**:avio_context_free 不释放 buffer/opaque(FFmpeg 7.1 aviobuf.c 核实),
   已自行 av_free;回调对象 close 时显式 deallocate。
4. **Windows-arm64 Loader 无平台映射**(M5 遗留):引擎层未做"资源自解包 + System.load"桥接,
   windows-arm64 产物暂时不可运行;其余 5 平台(含 android 两平台)已实测/构建就绪,配合
   ModConfig.nativePlatformOverride 可覆盖 Pojav 场景。
5. **B 站在线流的原生网络协议未启用**:demo 走自定义 AVIO(Java 侧 HTTP),不依赖
   `--enable-network`;若未来需要用 FFmpeg 原生 http 协议(如支持 cookies/多段并发),
   需按 M5 待办重编 TCP/TLS 后端。

---

## M7 纯 FFmpeg 化:删除字节码(JVM)解码器(完成)

时间:`2026-08-17 21:1x`

### 目标
播放链路收敛为 **FFmpeg 唯一路径**:删除全部纯 Java/JVM 解码器(jlayer/jflac/jcodec-jaad)与其依赖,
平台无 FFmpeg 原生库时明确提示"不支持播放",不再静默回退旧引擎。

### 删除清单
- **源码文件**(`common/.../musicmc/player/`):
  - `Mp3AudioEngine.kt`(jlayer MP3)
  - `FlacAudioEngine.kt`(jflac FLAC)
  - `M4aAudioEngine.kt`(jcodec 内嵌 jaadec 的 fMP4+AAC)—— 连同其独占辅助类
    `LineSlot` / `MoofInfo` / `SegmentIndex` / `BoxReader` 一并删除
    (FfmpegAudioEngine 使用的是自带私有 `FfmpegLineSlot`,无依赖,无需迁移)
  - `AdaptiveAudioEngine.kt`(旧嗅探分发器,连同 `sniffAndStart` 回退路径)
- **依赖**(gradle.properties + 三个 build.gradle.kts):
  - `gradle.properties`:删 `jlayer_version=1.0.1`、`jflac_version=1.5.2`、
    `jcodec_version=0.2.5` 及 jcodec 注释行
  - `common/build.gradle.kts`:删 jlayer/jflac/jcodec 三条 implementation 行及注释
  - `fabric/build.gradle.kts`:删三组 `implementation + include` 行
  - `neoforge/build.gradle.kts`:删 `jlayerVersion/jflacVersion/jcodecVersion` 三个
    val 声明、三组 `implementation + jarJar` 行及 jcodec 注释行
- **残留确认**:全仓 grep `jlayer|javazoom|org.jcodec|net.sourceforge.jaad|org.jflac|
  Mp3AudioEngine|FlacAudioEngine|M4aAudioEngine|AdaptiveAudioEngine` 于
  `src/main` 与全部 `*.kts` / `gradle.properties` / `README.md` 零命中;
  `common/bin`(Buildship/jdtls 陈旧同步镜像,含旧拷贝)已整体删除。
- **保留**:`PcmBatcher`、`StreamOptions`/referer/backupUrls 链(FfmpegDecoder 在用)、
  `DecodedAudio`、FfmpegAudioEngine 私有 `FfmpegLineSlot`。

### 改造
- `MusicPlayer`:`engine` 默认构造改为 `FfmpegAudioEngine()`(参数保留供注入);
  `loadUrl()` 在 `source.songUrl` 前检查 `FfmpegDecoder.nativeAvailable()`,
  false 时 `state=ERROR` + 复用 toast 机制提示**"当前平台不支持播放(缺少 FFmpeg 原生库)"**,
  不调用 engine(也不发网络请求)。
- `FfmpegAudioEngine`:保留 load 时同步抛 `FfmpegUnavailableException` 的异常语义
  (MusicPlayer 已前置拦截,此处为兜底防御);类文档/注释同步为"唯一播放引擎"。
- 相关注释同步:AudioEngine 接口文档、FfmpegDecoder 文档、
  `BilibiliSource`(fMP4 由 FfmpegAudioEngine 解码)、`Http.openStreamInfo`(FfmpegDecoder seek 重开 Range 流)。

### 不支持平台的提示行为
平台无 FFmpeg 原生库(未打包平台 jar / Loader 加载失败,`nativeAvailable()==false`)时:
**界面直接弹出"当前平台不支持播放(缺少 FFmpeg 原生库)"并置 ERROR 状态,不进入播放流程**;
`FfmpegUnavailableException` 保留为引擎层兜底防御(正常路径不会触发)。
M6 留下的 windows-arm64 Loader 无平台映射问题(待办 #4)落在此提示路径上:
该平台产物上会表现为"不支持播放"而非崩溃。

### jar 体积变化(对比 M6 后打包)
| 产物 | 之前(M6,含旧引擎) | 之后(M7,纯 FFmpeg) | 变化 |
|---|---|---|---|
| musicmc-fabric-0.1.0.jar | 5,667,902 B | 3,500,821 B | **−2,167,081 B(−38.2%)** |
| musicmc-neoforge-0.1.0.jar | 7,384,970 B | 5,220,070 B | **−2,164,900 B(−29.3%)** |
缩减 ≈ jlayer + jflac + jcodec 三库压缩后体积(约 2.2MB/产物)。

### 打包验证
- `unzip -l` 两 jar:**无** `javazoom/`、`org/jflac/`、`org/jcodec/`、`net/sourceforge/`(jaad) 类;
- bytedeco 四件套完整嵌套:
  - fabric `META-INF/jars/`:ffmpeg-7.1.1-1.5.12.jar + javacpp-1.5.12.jar +
    javacpp-1.5.12-linux-x86_64.jar + musicmc-native-linux-x86_64.jar(其余 zxing/gson)
  - neoforge `META-INF/jarjar/`:同上四件 + kotlin-stdlib + metadata.json
- 构建串行全绿:`:common:compileKotlin` → `:fabric:build` → `:neoforge:build`
  (`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv6Addresses=true`,`--no-daemon`,日志分别重定向)。

### 遗留风险
无(平台矩阵与 windows-arm64 Loader 映射为 M6 既有待办,不属于本次删除引入)。