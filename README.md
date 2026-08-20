# MusicMC

用 Kotlin 写的 Minecraft 音乐播放 Mod,支持 **MC 26.1 / 26.2**(Fabric + NeoForge 双平台,一个 jar 双版本通用)。音源为哔哩哔哩(搜索 / 排行榜,匿名可用,可选扫码登录),纯 FFmpeg 解码播放。

## 功能

- 哔哩哔哩搜索 / 全站·音乐·娱乐排行榜
- 播放队列、播放模式(顺序 / 循环 / 单曲 / 随机)、音量、进度跳转
- 歌词:CC 字幕优先,支持网易云 / QQ音乐 / 酷狗三源手动绑定 + 逐行偏移 + 本地缓存 + 自建 Hub 多端同步(`hub/` 零依赖 Node 服务)
- B 站扫码登录(持久化;降低搜索风控、提升流优先级)
- 游戏内 HUD 悬浮面板(封面 / 歌名 / 进度,可拖动编辑)
- UI 自动选择:ModernUI(PC 已装)或 YACL(Android / 未装 ModernUI);Android 强制 YACL

## 安装

- **NeoForge**:MC 26.1 / 26.2 + [YACL](https://modrinth.com/mod/yacl)(必须,`yet_another_config_lib_v3`)+ KotlinForForge(FCL 自带)
- **Fabric**:MC 26.1 / 26.2 + Fabric API + Fabric Language Kotlin + YACL
- 全部 6 平台 FFmpeg 原生库已内嵌(linux / windows / android × arm64 / x86_64),无需额外安装
- 游戏内按 `M` 打开界面,或 `/netmusic`

> 非 FCL 启动器(Pojav 等)如检测不到 Android 架构,在设置 → 高级 → **FFmpeg 平台覆盖**填 `android-arm64`;原生库解包目录异常时可在 **原生库缓存目录** 手动指定可执行目录。

## 构建

```bash
./gradlew --no-daemon :fabric:build :neoforge:build   # 单平台(默认 linux-x86_64)
./gradlew --no-daemon :fabric:build :neoforge:build -PnativePlatform=all  # 全 6 平台
```

产物在 `fabric/build/libs` 与 `neoforge/build/libs`。全平台原生库编译见 [native/README.md](native/README.md) 与 GitHub Actions 工作流。

## 许可

GPL-3.0。FFmpeg 原生库按 LGPL-2.1+ 组件集分发(详见 [native/README.md](native/README.md));哔哩哔哩 / 网易云 / QQ音乐 / 酷狗接口仅供个人学习研究,请尊重版权。
