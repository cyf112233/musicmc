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
import io.github.cyf112233.musicmc.player.PlayMode
import io.github.cyf112233.musicmc.ui.UiBackend
import io.github.cyf112233.musicmc.ui.UiBackendResolver
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * YACL 原生配置界面(替代 Cloth Config;全平台唯一配置入口)。
 *
 * 分类:通用(UI 方案)/ 播放(音量、码率、模式)/ 歌词 / HUD / 高级(FFmpeg 平台覆盖)。
 * 保存回调统一写 [io.github.cyf112233.musicmc.config.ModConfig]。
 */
object YaclConfigScreen {

    /** UI 方案(含 AUTO 默认;字符串值与 ModConfig.uiMode 兼容,含旧值 LDLIB) */
    enum class UiModeOption(val label: String, val value: String) {
        AUTO("自动(装了哪个用哪个)", "AUTO"),
        MODERN_UI("ModernUI", "MODERN_UI"),
        YACL("YACL 现代化", "YACL"),
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
        var bitrate = cfg.bitrate
        var playMode = cfg.playMode
        var lyricsEnabled = cfg.lyricsEnabled
        var hudLyricEnabled = cfg.hudLyricEnabled
        var chatLyricEnabled = cfg.chatLyricEnabled
        var hudEnabled = cfg.hudEnabled
        var nativeOverride = cfg.nativePlatformOverride
        var nativeCacheDir = cfg.nativeCacheDir

        fun boolOption(name: String, desc: String, get: () -> Boolean, set: (Boolean) -> Unit): Option<Boolean> =
            Option.createBuilder<Boolean>()
                .name(Component.literal(name))
                .description { OptionDescription.of(Component.literal(desc)) }
                .binding(true, get, set)
                .controller { BooleanControllerBuilder.create(it).coloured(true).yesNoFormatter() }
                .build()

        val categoryGeneral = ConfigCategory.createBuilder()
            .name(Component.literal("通用"))
            .option(
                Option.createBuilder<UiModeOption>()
                    .name(Component.literal("UI 方案"))
                    .description {
                        OptionDescription.of(
                            Component.literal(
                                "自动:装了哪个用哪个 —— PC:ModernUI > YACL > 原版;" +
                                    "Android:YACL > 原版(ModernUI 依赖 Java2D,Android 上文字空白,永不使用)",
                            ),
                        )
                    }
                    .binding(UiModeOption.AUTO, { uiMode }, { uiMode = it })
                    .controller { EnumControllerBuilder.create(it).enumClass(UiModeOption::class.java) }
                    .build(),
            )
            .option(
                ButtonOption.createBuilder()
                    .name(Component.literal("切换 UI"))
                    .description(
                        OptionDescription.of(
                            Component.literal(
                                "在 ModernUI 与 YACL 界面之间一键切换(需对应模组已安装);" +
                                    "Android 上 ModernUI 不可用,该按钮隐藏",
                            ),
                        ),
                    )
                    .text(Component.literal("切换"))
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
                    .name(Component.literal("音量"))
                    .description { OptionDescription.of(Component.literal("全局播放音量 0-100%")) }
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
                    .name(Component.literal("音质码率"))
                    .description { OptionDescription.of(Component.literal("请求的音频码率(kbps),越高音质越好但更耗流量")) }
                    .binding(320, { bitrate }, { bitrate = it })
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
                    .name(Component.literal("播放模式"))
                    .description { OptionDescription.of(Component.literal("列表循环 / 单曲循环 / 顺序 / 随机")) }
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
                                        PlayMode.SEQUENCE -> "顺序播放"
                                        PlayMode.LOOP_ALL -> "列表循环"
                                        PlayMode.LOOP_ONE -> "单曲循环"
                                        PlayMode.SHUFFLE -> "随机播放"
                                    },
                                )
                            }
                    }
                    .build(),
            )
            .build()

        val categoryLyrics = ConfigCategory.createBuilder()
            .name(Component.literal("歌词"))
            .option(boolOption("显示歌词", "播放时显示当前歌曲歌词", { lyricsEnabled }, { lyricsEnabled = it }))
            .option(boolOption("HUD 歌词", "游戏内悬浮面板显示歌词", { hudLyricEnabled }, { hudLyricEnabled = it }))
            .option(boolOption("聊天栏歌词", "每句歌词同步输出到聊天栏", { chatLyricEnabled }, { chatLyricEnabled = it }))
            .build()

        val categoryHud = ConfigCategory.createBuilder()
            .name(Component.literal("HUD"))
            .option(boolOption("悬浮播放面板", "游戏内显示音乐 HUD(封面/歌名/进度)", { hudEnabled }, { hudEnabled = it }))
            .option(
                ButtonOption.createBuilder()
                    .name(Component.literal("打开 HUD 编辑器"))
                    .description(OptionDescription.of(Component.literal("拖动调整悬浮面板位置,滚轮缩放;拖动时实时生效")))
                    .text(Component.literal("编辑"))
                    .action { io.github.cyf112233.musicmc.platform.McScreens.open(YaclHudEditorScreen()) }
                    .build(),
            )
            .build()

        val categoryAdvanced = ConfigCategory.createBuilder()
            .name(Component.literal("高级"))
            .option(
                Option.createBuilder<String>()
                    .name(Component.literal("FFmpeg 平台覆盖"))
                    .description {
                        OptionDescription.of(
                            Component.literal(
                                "强制 javacpp 平台名(如 android-arm64 / linux-x86_64);" +
                                    "留空则按系统自动判定。一般无需修改",
                            ),
                        )
                    }
                    .binding("", { nativeOverride }, { nativeOverride = it.trim() })
                    .controller { StringControllerBuilder.create(it) }
                    .build(),
            )
            .option(
                Option.createBuilder<String>()
                    .name(Component.literal("原生库缓存目录"))
                    .description {
                        OptionDescription.of(
                            Component.literal(
                                "Android 上 javacpp/AAudio 原生库解包目录(需可执行)。" +
                                    "留空自动判定(按 tmpdir → user.home → user.dir 取首个可写且可执行目录);" +
                                    "非 FCL 启动器或自动判定失败时,可手动填 app 私有可执行目录" +
                                    "(如 /data/user/0/<包名>/cache/musicmc-native)",
                            ),
                        )
                    }
                    .binding("", { nativeCacheDir }, { nativeCacheDir = it.trim() })
                    .controller { StringControllerBuilder.create(it) }
                    .build(),
            )
            .build()

        val screen = YetAnotherConfigLib.createBuilder()
            .title(Component.literal("MusicMC 设置"))
            .category(categoryGeneral)
            .category(categoryLyrics)
            .category(categoryHud)
            .category(categoryAdvanced)
            .save {
                NetMusic.updateConfig { old ->
                    old.copy(
                        uiMode = uiMode.value,
                        volume = volume.coerceIn(0f, 1f),
                        bitrate = bitrate,
                        playMode = playMode,
                        lyricsEnabled = lyricsEnabled,
                        hudLyricEnabled = hudLyricEnabled,
                        chatLyricEnabled = chatLyricEnabled,
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
