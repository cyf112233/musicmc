# MusicMC

使用 Kotlin 编写的 Minecraft 音乐播放 Mod,目标 MC 26.1+,支持 Fabric 与 NeoForge。接入哔哩哔哩作为唯一音乐源(视频搜索 / 排行榜,匿名可用;可选扫码登录),歌词支持 CC 字幕与网易云 / QQ音乐 / 酷狗三源(BBPlayer 式,可手动搜索绑定 + ±0.5s 偏移 + 本地缓存 + 自建 Hub 多端同步)。UI 基于 Modern UI(icyllis),使用其 Material 3 主题组件。

## 功能

- 搜索视频(即歌曲)/ 排行榜歌单
- 排行榜(全站 / 音乐 / 娱乐)
- 播放队列
- 播放模式(顺序 / 循环 / 单曲 / 随机)
- 音量
- 进度跳转
- 歌词显示(可开关,默认关闭;karaoke 逐行:CC 字幕优先,无 CC 字幕时可自动三源匹配或手动搜索绑定)
- B 站扫码登录(Web passport 链路,持久化;登录后可防搜索风控、提升流优先级)
- 歌词 Hub 自托管同步(多设备共享歌词与偏移,含 `hub/` 零依赖 Node 服务)
- 哔哩哔哩音源:视频搜索、全站/音乐/娱乐排行榜、fMP4 音频流播放(AAC 解码,无需登录;纯 FFmpeg 解码)

## 架构

- `common`:平台无关部分——音乐源抽象、哔哩哔哩实现(搜索 / 排行榜 / 播放地址 / CC 字幕歌词 / 扫码登录)、歌词三源提供方(网易云 / QQ音乐 / 酷狗)、歌词缓存与 Hub 客户端、音频引擎、UI Fragment

### 音频引擎(纯 FFmpeg 解码)

播放器经 `FfmpegAudioEngine`(common 唯一引擎)播放:

1. **FFmpeg 引擎(唯一)**:`common/.../player/ffmpeg/`——FFmpeg 7.1(avformat 解封装 + avcodec 解码 + swresample 转 s16 交错 PCM)统一处理 AAC / MP3 / FLAC / Opus 等格式;HTTP 流走**自定义 AVIO**(Java 读/seek 回调:带 Referer 拉流、seek 时按 offset 重开 Range 流,CDN 忽略 Range 返回 200 时丢弃定位);seek 时 `av_seek_frame` 按流 time_base 精确锚定。原生库来自 `native` 模块(javacpp 绑定 + 自编平台 .so)。
2. **平台无原生库时**(未打包平台 jar / 加载失败):播放发起即提示"当前平台不支持播放(缺少 FFmpeg 原生库)",不进入播放流程。
- `fabric`:Fabric 入口、按键、平台服务
- `neoforge`:NeoForge 入口、按键、平台服务
- `hub`:自建歌词同步服务(Node >= 18,零依赖,协议见 `hub/README.md`)

不依赖混淆映射表(26.1+ 官方名)。不使用 Mixin,仅使用稳定 modding API。

## B 站账号

设置页 → **B 站账号**:

- **扫码登录**:打开二维码页(2s 轮询,B 站官方 passport 链路),手机 B 站 App 扫码确认后自动登录;
  登录 cookie(SESSDATA 等)持久化到配置,重启后保持。
- **退出登录**:仅清除本地 cookie。
- 登录后的作用:搜索个性化并降低 -412 风控;播放流优先级提升
  (dolby.audio → flac.audio → 30280 AAC → 默认,登录 + 大会员才可能命中无损/杜比)。

## 歌词说明

歌词页(总开关在设置页开启后从主界面"歌词"按钮进入),顶部工具行:

- **搜索歌词**:切换三源手动搜索模式(网易云 / QQ音乐 / 酷狗并行,按完成顺序追加结果),
  点击候选行("标题 歌手 · 来源")手动绑定歌词,绑定后自动回歌词视图并持久化;
- **-0.5s / +0.5s**:逐行偏移,即点即存(本地缓存持久化,并推送 Hub);
- **偏移显示**:当前偏移(如"偏移 0.5s"),显示时间 = 播放位置 - 偏移;
- **返回**:搜索模式下先退出搜索,再返回主界面。

歌词加载优先级(在 `LyricManager` 内统一处理):

1. **本地缓存**(手动绑定 / 自动匹配 / CC / Hub 拉取的结果均落盘,偏移即时保存);
2. **Hub 同步**(设置了"歌词 Hub 地址"且本地未命中时,自动从 Hub 拉取并落缓存);
3. **CC 字幕**(UP 主上传的字幕轨道;当前 B 站 UGC 绝大多数无 CC);
4. **标题自动匹配**(设置页开关,默认关):按视频标题清洗后依次尝试
   网易云(取第一首)→ QQ音乐(duration±3s 匹配)→ 酷狗(同样匹配),命中即落缓存;
5. 全部失败显示"暂无歌词"。

歌词页左上角小字显示当前来源:CC字幕 / 网易云 / QQ音乐 / 酷狗 / 本地缓存 / Hub。

## 歌词 Hub(自建,可选)

多设备共享歌词与偏移:

```bash
cd hub
node server.js        # 需要 Node >= 18;PORT/HUB_TOKEN 可用环境变量覆盖
```

游戏内 **设置 → 歌词 Hub 地址** 填 `http://<主机>:8787` 并保存即可。
任一设备调整偏移 / 手动绑定歌词后自动推送,其它设备加载歌词时自动拉取。
协议与部署注意事项见 [`hub/README.md`](hub/README.md)(默认无鉴权,请仅内网使用或加 `HUB_TOKEN` / 反向代理)。

## 依赖(玩家侧)

- Modern UI(对应各 loader 的 3.13.0.5+,https://www.mcmod.cn/class/2454.html)
- Fabric 还需:Fabric API、Forge Config API Port、Fabric Language Kotlin
- NeoForge 自带 Kotlin stdlib(构建时打进 jar)
- 二维码生成 / 解码由 ZXing 3.5.4 提供(已打包进 mod jar,无需额外安装)

## 构建

```bash
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv6Addresses=true"
./gradlew --no-daemon :common:compileKotlin
./gradlew --no-daemon :fabric:build
./gradlew --no-daemon :neoforge:build
node --check hub/server.js   # Hub 服务语法检查
```

产物位于 `fabric/build/libs` 与 `neoforge/build/libs`。

### 全平台打包(可选)

`-PnativePlatform=all` 时把全部 6 个 FFmpeg 原生平台 jar + 5 个 javacpp 官方平台 jar +
windows-arm64 的 javacpp 平台 jar(本地文件)一并嵌套进 mod jar(前置条件:`native/build/libs`
已由 `:native:packageNative` 产出全部平台 jar,缺失时构建报错):

```bash
./gradlew --no-daemon :fabric:build :neoforge:build -PnativePlatform=all
```

实测体积(2026-08-17):fabric 3,505,930 B → **14,817,750 B**;neoforge 5,225,164 B →
**16,534,827 B**。fabric 嵌套于 `META-INF/jars/`,neoforge 嵌套于 `META-INF/jarjar/`(含
metadata.json)。DSL 与体积细节见 [`native/README.md`](native/README.md)「全平台打入 mod jar」。

## CI(六平台原生 + mod 打包)

已配置 GitHub Actions 工作流,CI 自行安装/缓存全部交叉工具链,不依赖本机环境:

- **`.github/workflows/native-build.yml`**:push(`native/**` 变更)或手动触发 → 六平台
  (`linux-x86_64 / linux-arm64 / windows-x86_64 / windows-arm64 / android-arm64 /
  android-x86_64`)矩阵并行编译 FFmpeg 原生库,产出并上传 `musicmc-native-<platform>.jar`;
  随后 `assemble-all` job 汇总全部平台 jar,`-PnativePlatform=all` 打出全平台 fabric/neoforge
  mod jar(artifact `mod-fabric-all` / `mod-neoforge-all`)。工具链(mingw-w64 / NDK r27b /
  llvm-mingw)与 ffmpeg 编译树均走 actions/cache,命中即免下载、免重编。
- **`.github/workflows/build-mod.yml`**:手动触发打 mod jar(`nativePlatform` 选单平台或
  `all`;all 模式需填 `native_run_id` 指向一次成功的 native-build run 以获取 6 个平台 jar)。

工作流细节、缓存键与本地差异见 [`native/README.md`](native/README.md)「GitHub Actions CI」。

## FFmpeg 原生构建与解码引擎(native)

FFmpeg 解码引擎已接入(2026-08,见上文"音频引擎"):common 的 `FfmpegDecoder`/`FfmpegAudioEngine`
通过 javacpp 绑定调用 FFmpeg,原生库在 `native` 模块自编译打包:

- **协议**:FFmpeg 7.1.5 以 **LGPL 组件集裁剪编译** —— 仅启用 libavcodec / libavformat /
  libavutil / libswresample 与内置 aac/mp3/flac/opus/vorbis 解码器,未启用任何 GPL 选项;
  **解码组件按 LGPL-2.1+ 分发,mod 整体仍 GPL-3.0**。许可细节见 [`native/README.md`](native/README.md)。
- **依赖三件套**(打进最终 mod jar,共约 +0.8MB):
  - `org.bytedeco:ffmpeg:7.1.1-1.5.12`(绑定 API,272KB)
  - `org.bytedeco:javacpp:1.5.12`(Loader/Pointer 运行时,522KB)
  - `org.bytedeco:javacpp:1.5.12:<平台 classifier>`(libjnijavacpp.so,47KB)
  - FFmpeg 原生 .so 本体由 `musicmc-native-<platform>.jar`(1.6MB)承载(Loader 资源路径
    `org/bytedeco/ffmpeg/<platform>/`);fabric `include` / neoforge `jarJar` 嵌套进 mod jar。
- **平台 jar**(`musicmc-native-<platform>.jar`):
  - ✅ linux-x86_64(冒烟测试通过)、linux-arm64、android-arm64、android-x86_64(NDK r27b)
  - ✅ windows-x86_64、windows-arm64(llvm-mingw;windows-arm64 的 Loader 自解包待办,见 native/STATUS.md)
- 当前 `nativePlatform=linux-x86_64` 随构建打进;`-PnativePlatform=all` 可打入全部 6 个
  平台(体积与嵌套结构见上方「全平台打包」)。
- **Pojav 等安卓容器**:容器内 JVM 可能检测不到 android 架构,可在设置配置文件中设置
  `"nativePlatformOverride": "android-arm64"`(或 `android-x86_64` / `linux-*` 等,取值见
  native/STATUS.md 平台矩阵),mod 会在任何 FFmpeg 加载前把该值写入
  JVM 属性 `org.bytedeco.javacpp.platform`(javacpp Loader 的平台是 static final,必须提前设置)。
- 构建命令、工具链安装、缓存位置、常见问题见 [`native/README.md`](native/README.md) 与
  [`native/STATUS.md`](native/STATUS.md)。

## 按键

默认按 `M` 打开界面;或使用 `/netmusic` 命令。

## 许可

本 Mod 采用 GPL-3.0。依赖许可说明:Modern UI LGPL-3.0、Gson Apache-2.0、ZXing Apache-2.0。FFmpeg 原生库(LGPL 组件集,自编):解码组件按 LGPL-2.1+ 分发(与 GPL-3.0 兼容,详见 [native/README.md](native/README.md));bytedeco javacpp 绑定 Apache-2.0。

## 免责声明

哔哩哔哩 / 网易云 / QQ音乐 / 酷狗接口仅用于个人学习研究,请勿用于商业用途,尊重版权。
