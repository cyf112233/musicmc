package io.github.cyf112233.musicmc.ui.yacl

import dev.isxander.yacl3.api.ButtonOption
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder
import dev.isxander.yacl3.api.controller.EnumControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder
import dev.isxander.yacl3.api.controller.StringControllerBuilder
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.player.PlayMode
import io.github.cyf112233.musicmc.ui.UiBackend
import io.github.cyf112233.musicmc.ui.UiBackendResolver
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * YACL 原生配置界面(替代 Cloth Config;全平台唯一配置入口)。
 *
 * 分类:General(UI 方案)/ 播放(Volume、码率、模式)/ Lyrics / HUD / 高级(FFmpeg 平台覆盖)。
 * 保存回调统一写 [io.github.cyf112233.musicmc.config.ModConfig]。
 */
object YaclConfigScreen {

    /** UI 方案(含 AUTO 默认;字符串值与 ModConfig.uiMode 兼容,含旧值 LDLIB) */
    enum class UiModeOption(val label: String, val value: String) {
        AUTO(UiText.t("自动(装了哪个用哪个)", "Auto (use whichever is installed)"), "AUTO"),
        MODERN_UI("ModernUI", "MODERN_UI"),
        YACL(UiText.t("YACL 现代化", "YACL (Modern)"), "YACL"),
    }

    /** 字节数转可读大小(音频缓存占用/清除量展示;与 MUI SettingsFragment 一致) */
    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun open(parent: Screen?): Screen {
        val cfg = NetMusic.config

        // 本地可变值(YACL binding 读写;保存时统一写 config)
        var uiMode = when (cfg.uiMode.uppercase()) {
            "MODERN_UI" -> UiModeOption.MODERN_UI
            "YACL", "LDLIB", "VANILLA" -> UiModeOption.YACL
            else -> UiModeOption.AUTO
        }
        var volume = cfg.volume
        // 码率在 UI 层按 kbps 展示(配置存 bps:如 320000;滑块 range 96..320 是 kbps 语义)。
        // 不做换算的话 binding 读写同一个 bps 值,滑块首显 320000(超出 range)且保存后
        // 配置被改写成 320 —— 缓存 key(歌曲id_码率)变化导致已缓存歌曲重新下载
        var bitrateKbps = cfg.bitrate / 1000
        var playMode = cfg.playMode
        var lyricsEnabled = cfg.lyricsEnabled
        var hudLyricEnabled = cfg.hudLyricEnabled
        var chatLyricEnabled = cfg.chatLyricEnabled
        var lyricTitleFallback = cfg.lyricTitleFallback
        var hubUrl = cfg.hubUrl
        var hudEnabled = cfg.hudEnabled
        var nativeOverride = cfg.nativePlatformOverride
        var nativeCacheDir = cfg.nativeCacheDir

        fun boolOption(name: String, desc: String, default: Boolean, get: () -> Boolean, set: (Boolean) -> Unit): Option<Boolean> =
            Option.createBuilder<Boolean>()
                .name(Component.literal(name))
                .description { OptionDescription.of(Component.literal(desc)) }
                // 默认值从配置当前值取,而非硬编码 true:
                // YACL 用 binding 默认值驱动"与默认不同"标记 / 重置按钮,
                // 硬编码默认会让未改动项误亮重置态
                .binding(default, get, set)
                .controller { BooleanControllerBuilder.create(it).coloured(true).yesNoFormatter() }
                .build()

        val categoryGeneral = ConfigCategory.createBuilder()
            .name(Component.literal(UiText.t("通用", "General")))
            .option(
                Option.createBuilder<UiModeOption>()
                    .name(Component.literal(UiText.t("UI 方案", "UI Mode")))
                    .description {
                        OptionDescription.of(
                            Component.literal(
                                UiText.t("自动:装了哪个用哪个 —— PC:ModernUI > YACL > 原版;", "Auto: pick what is installed — PC: ModernUI > YACL > vanilla;") +
                                    UiText.t("Android:YACL > 原版(ModernUI 依赖 Java2D,Android 上文字空白,永不使用)", "Android: YACL > vanilla (ModernUI needs Java2D, blank text on Android, never used)"),
                            ),
                        )
                    }
                    .binding(UiModeOption.AUTO, { uiMode }, { uiMode = it })
                    .controller { EnumControllerBuilder.create(it).enumClass(UiModeOption::class.java) }
                    .build(),
            )
            .option(
                ButtonOption.createBuilder()
                    .name(Component.literal(UiText.t("切换 UI", "Switch UI")))
                    .description(
                        OptionDescription.of(
                            Component.literal(
                                UiText.t("在 ModernUI 与 YACL 界面之间一键切换(需对应模组已安装);", "Switch between ModernUI and YACL (requires the matching mod installed);") +
                                    UiText.t("Android 上 ModernUI 不可用,该按钮隐藏", "Hidden on Android (ModernUI unavailable)"),
                            ),
                        ),
                    )
                    .text(Component.literal(UiText.t("切换", "Switch")))
                    // Android 恒 YACL,无切换意义 → 隐藏
                    .available(!io.github.cyf112233.musicmc.player.ffmpeg.NativeLibBridge.isAndroid())
                    .action { _ ->
                        val current = UiBackendResolver.resolve(
                            NetMusic.config.uiMode,
                            io.github.cyf112233.musicmc.player.ffmpeg.NativeLibBridge.isAndroid(),
                            io.github.cyf112233.musicmc.platform.PlatformHolder.require().isModernUiLoaded(),
                        )
                        val newMode = if (current == UiBackend.MODERN_UI) "YACL" else "MODERN_UI"
                        NetMusic.updateConfig { it.copy(uiMode = newMode) }
                        // 关闭配置界面并按新 UI 重新打开音乐界面
                        // (屏幕切换统一走 McScreens 版本自适应桥:26.1 Minecraft.setScreen / 26.2 Gui.setScreen)
                        io.github.cyf112233.musicmc.platform.McScreens.open(null)
                        NetMusic.openScreen()
                    }
                    .build(),
            )
            .option(
                Option.createBuilder<Int>()
                    .name(Component.literal(UiText.t("音量", "Volume")))
                    .description { OptionDescription.of(Component.literal(UiText.t("全局播放音量 0-100%", "Global volume 0-100%"))) }
                    .binding(80, { (volume * 100).toInt() }, { volume = it / 100f })
                    .controller {
                        IntegerSliderControllerBuilder.create(it)
                            .range(0, 100)
                            .step(1)
                            .valueFormatter { v -> Component.literal("$v%") }
                    }
                    .build(),
            )
            .option(
                Option.createBuilder<Int>()
                    .name(Component.literal(UiText.t("音质码率", "Audio Bitrate")))
                    .description { OptionDescription.of(Component.literal(UiText.t("请求的音频码率(kbps),越高音质越好但更耗流量", "Requested audio bitrate (kbps). Higher = better quality but more data"))) }
                    .binding(320, { bitrateKbps }, { bitrateKbps = it })
                    .controller {
                        IntegerSliderControllerBuilder.create(it)
                            .range(96, 320)
                            .step(32)
                            .valueFormatter { v -> Component.literal("${v}kbps") }
                    }
                    .build(),
            )
            .option(
                Option.createBuilder<PlayMode>()
                    .name(Component.literal(UiText.t("播放模式", "Play Mode")))
                    .description { OptionDescription.of(Component.literal(UiText.t("列表循环 / 单曲循环 / 顺序 / 随机", "Loop All / Loop One / Sequential / Shuffle"))) }
                    .binding(
                        PlayMode.SEQUENCE,
                        { runCatching { PlayMode.valueOf(playMode) }.getOrDefault(PlayMode.SEQUENCE) },
                        { playMode = it.name },
                    )
                    .controller {
                        EnumControllerBuilder.create(it).enumClass(PlayMode::class.java)
                            .formatValue { m ->
                                Component.literal(
                                    when (m) {
                                        PlayMode.SEQUENCE -> UiText.t("顺序播放", "Sequential")
                                        PlayMode.LOOP_ALL -> UiText.t("列表循环", "Loop All")
                                        PlayMode.LOOP_ONE -> UiText.t("单曲循环", "Loop One")
                                        PlayMode.SHUFFLE -> UiText.t("随机播放", "Shuffle")
                                    },
                                )
                            }
                    }
                    .build(),
            )
            .build()

        val categoryLyrics = ConfigCategory.createBuilder()
            .name(Component.literal(UiText.t("歌词", "Lyrics")))
            .option(boolOption(UiText.t("显示歌词", "Show Lyrics"), UiText.t("播放时显示当前歌曲歌词", "Show lyrics while playing"), cfg.lyricsEnabled, { lyricsEnabled }, { lyricsEnabled = it }))
            .option(boolOption(UiText.t("HUD 歌词", "HUD Lyrics"), UiText.t("游戏内悬浮面板显示歌词", "Show lyrics on the in-game HUD"), cfg.hudLyricEnabled, { hudLyricEnabled }, { hudLyricEnabled = it }))
            .option(boolOption(UiText.t("聊天栏歌词", "Chat Lyrics"), UiText.t("每句歌词同步输出到聊天栏", "Also send each lyric line to chat"), cfg.chatLyricEnabled, { chatLyricEnabled }, { chatLyricEnabled = it }))
            .option(boolOption(UiText.t("标题自动匹配歌词", "Title Fallback"), UiText.t("无 CC 字幕时按歌曲标题在网易云/QQ音乐/酷狗自动匹配歌词", "When no CC subtitles, auto-match lyrics by title from NetEase/QQ Music/Kugou"), cfg.lyricTitleFallback, { lyricTitleFallback }, { lyricTitleFallback = it }))
            .option(
                Option.createBuilder<String>()
                    .name(Component.literal(UiText.t("歌词 Hub 地址", "Lyrics Hub URL")))
                    .description {
                        OptionDescription.of(
                            Component.literal(
                                UiText.t("自建歌词同步服务地址(多设备共享歌词与偏移),留空不启用。如 http://192.168.1.100:8787", "Self-hosted lyrics sync server URL (share lyrics & offsets across devices). Leave empty to disable. e.g. http://192.168.1.100:8787"),
                            ),
                        )
                    }
                    .binding("", { hubUrl }, { hubUrl = it.trim() })
                    .controller { StringControllerBuilder.create(it) }
                    .build(),
            )
            .build()

        val categoryHud = ConfigCategory.createBuilder()
            .name(Component.literal(UiText.t("HUD", "HUD")))
            .option(boolOption(UiText.t("悬浮播放面板", "HUD Panel"), UiText.t("游戏内显示音乐 HUD(封面/歌名/进度)", "Show the in-game music HUD (cover / title / progress)"), cfg.hudEnabled, { hudEnabled }, { hudEnabled = it }))
            .option(
                ButtonOption.createBuilder()
                    .name(Component.literal(UiText.t("打开 HUD 编辑器", "Open HUD Editor")))
                    .description(OptionDescription.of(Component.literal(UiText.t("拖动调整悬浮面板位置,滚轮缩放;拖动时实时生效", "Drag to move the HUD, scroll to scale; changes apply live"))))
                    .text(Component.literal(UiText.t("编辑", "Edit")))
                    .action { io.github.cyf112233.musicmc.platform.McScreens.open(YaclHudEditorScreen()) }
                    .build(),
            )
            .build()

        val categoryAdvanced = ConfigCategory.createBuilder()
            .name(Component.literal(UiText.t("高级", "Advanced")))
            .option(
                Option.createBuilder<String>()
                    .name(Component.literal(UiText.t("FFmpeg 平台覆盖", "FFmpeg Platform Override")))
                    .description {
                        OptionDescription.of(
                            Component.literal(
                                UiText.t("强制 javacpp 平台名(如 android-arm64 / linux-x86_64);", "Force the javacpp platform name (e.g. android-arm64 / linux-x86_64);") +
                                    UiText.t("留空则按系统自动判定。一般无需修改", "Leave empty for auto-detection. Usually no change needed."),
                            ),
                        )
                    }
                    .binding("", { nativeOverride }, { nativeOverride = it.trim() })
                    .controller { StringControllerBuilder.create(it) }
                    .build(),
            )
            .option(
                Option.createBuilder<String>()
                    .name(Component.literal(UiText.t("原生库缓存目录", "Native Lib Cache Dir")))
                    .description {
                        OptionDescription.of(
                            Component.literal(
                                UiText.t("Android 上 javacpp/AAudio 原生库解包目录(需可执行)。", "Android extract dir for javacpp/AAudio native libs (must be executable).") +
                                    UiText.t("留空自动判定(按 tmpdir → user.home → user.dir 取首个可写且可执行目录);", "Leave empty for auto (first writable & executable of tmpdir → user.home → user.dir);") +
                                    UiText.t("非 FCL 启动器或自动判定失败时,可手动填 app 私有可执行目录", "For non-FCL launchers or failed detection, set an app-private executable dir manually") +
                                    UiText.t("(如 /data/user/0/<包名>/cache/musicmc-native)", "(e.g. /data/user/0/<package>/cache/musicmc-native)"),
                            ),
                        )
                    }
                    .binding("", { nativeCacheDir }, { nativeCacheDir = it.trim() })
                    .controller { StringControllerBuilder.create(it) }
                    .build(),
            )
            .build()

        val categoryStorage = ConfigCategory.createBuilder()
            .name(Component.literal(UiText.t("存储", "Storage")))
            .option(
                ButtonOption.createBuilder()
                    .name(Component.literal(UiText.t("清除音频缓存", "Clear Audio Cache")))
                    .description(
                        OptionDescription.of(
                            Component.literal(
                                UiText.t(
                                    "播放过的歌曲缓存于本地(下次离线直播);当前占用: ${formatBytes(io.github.cyf112233.musicmc.player.AudioCache.totalSize())}",
                                    "Played songs are cached locally for offline playback. Current usage: ${formatBytes(io.github.cyf112233.musicmc.player.AudioCache.totalSize())}",
                                ),
                            ),
                        ),
                    )
                    .text(Component.literal(UiText.t("清除", "Clear")))
                    .action { _ ->
                        val freed = io.github.cyf112233.musicmc.player.AudioCache.clear()
                        Minecraft.getInstance().gui.getChat().addClientSystemMessage(
                            Component.literal(
                                UiText.t("音频缓存已清除(${formatBytes(freed)})", "Audio cache cleared (${formatBytes(freed)})"),
                            ),
                        )
                    }
                    .build(),
            )
            .build()

        val categoryAccount = ConfigCategory.createBuilder()
            .name(Component.literal(UiText.t("账号", "Account")))
            .option(
                ButtonOption.createBuilder()
                    .name(Component.literal(UiText.t("B 站账号", "Bilibili Account")))
                    .description(
                        OptionDescription.of(
                            Component.literal(
                                if (NetMusic.bilibiliLoggedIn()) {
                                    UiText.t("已登录: 扫码登录后搜索个性化、降低风控;退出登录请点右侧按钮", "Logged in. QR login personalizes search & lowers risk control; use the button to log out")
                                } else {
                                    UiText.t("扫码登录 B 站(可选):搜索个性化、降低风控、流媒体优先提升", "QR-login to Bilibili (optional): personalized search, lower risk control, stream priority")
                                },
                            ),
                        ),
                    )
                    .text(
                        Component.literal(
                            if (NetMusic.bilibiliLoggedIn()) UiText.t("退出登录", "Log Out") else UiText.t("扫码登录", "QR Login"),
                        ),
                    )
                    .action { _ ->
                        if (NetMusic.bilibiliLoggedIn()) {
                            NetMusic.setBilibiliCookie("")
                            Minecraft.getInstance().gui.getChat().addClientSystemMessage(
                                Component.literal(UiText.t("已退出登录", "Logged out")),
                            )
                        } else {
                            // 打开扫码登录页(返回本配置屏;McScreens.current() 可能为 null,
                            // YaclLoginScreen 接受可空 back)
                            io.github.cyf112233.musicmc.platform.McScreens.open(YaclLoginScreen(io.github.cyf112233.musicmc.platform.McScreens.current()))
                        }
                    }
                    .build(),
            )
            .build()

        val screen = YetAnotherConfigLib.createBuilder()
            .title(Component.literal(UiText.t("MusicMC 设置", "MusicMC Settings")))
            .category(categoryGeneral)
            .category(categoryLyrics)
            .category(categoryHud)
            .category(categoryStorage)
            .category(categoryAccount)
            .category(categoryAdvanced)
            .save {
                NetMusic.updateConfig { old ->
                    old.copy(
                        uiMode = uiMode.value,
                        volume = volume.coerceIn(0f, 1f),
                        bitrate = bitrateKbps * 1000,
                        playMode = playMode,
                        lyricsEnabled = lyricsEnabled,
                        hudLyricEnabled = hudLyricEnabled,
                        chatLyricEnabled = chatLyricEnabled,
                        lyricTitleFallback = lyricTitleFallback,
                        hubUrl = hubUrl,
                        hudEnabled = hudEnabled,
                        nativePlatformOverride = nativeOverride,
                        nativeCacheDir = nativeCacheDir,
                    )
                }
            }
            .build()
            .generateScreen(parent)

        // 保持旧行为:直接打开(音乐界面「设置」按钮等调用方不变);返回 Screen 供平台配置菜单使用
        io.github.cyf112233.musicmc.platform.McScreens.open(screen)
        return screen
    }
}
