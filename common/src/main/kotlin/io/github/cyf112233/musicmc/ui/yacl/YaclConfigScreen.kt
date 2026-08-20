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
 * 分类:General(UI 方案)/ 播放(Volume、码率、模式)/ Lyrics / HUD / 高级(FFmpeg 平台覆盖)。
 * 保存回调统一写 [io.github.cyf112233.musicmc.config.ModConfig]。
 */
object YaclConfigScreen {

    /** UI 方案(含 AUTO 默认;字符串值与 ModConfig.uiMode 兼容,含旧值 LDLIB) */
    enum class UiModeOption(val label: String, val value: String) {
        AUTO("Auto (use whichever is installed)", "AUTO"),
        MODERN_UI("ModernUI", "MODERN_UI"),
        YACL("YACL (Modern)", "YACL"),
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
            .name(Component.literal("General"))
            .option(
                Option.createBuilder<UiModeOption>()
                    .name(Component.literal("UI Mode"))
                    .description {
                        OptionDescription.of(
                            Component.literal(
                                "Auto: pick what is installed — PC: ModernUI > YACL > vanilla;" +
                                    "Android: YACL > vanilla (ModernUI needs Java2D, blank text on Android, never used)",
                            ),
                        )
                    }
                    .binding(UiModeOption.AUTO, { uiMode }, { uiMode = it })
                    .controller { EnumControllerBuilder.create(it).enumClass(UiModeOption::class.java) }
                    .build(),
            )
            .option(
                ButtonOption.createBuilder()
                    .name(Component.literal("Switch UI"))
                    .description(
                        OptionDescription.of(
                            Component.literal(
                                "Switch between ModernUI and YACL (requires the matching mod installed);" +
                                    "Hidden on Android (ModernUI unavailable)",
                            ),
                        ),
                    )
                    .text(Component.literal("Switch"))
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
                    .name(Component.literal("Volume"))
                    .description { OptionDescription.of(Component.literal("Global volume 0-100%")) }
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
                    .name(Component.literal("Audio Bitrate"))
                    .description { OptionDescription.of(Component.literal("Requested audio bitrate (kbps). Higher = better quality but more data")) }
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
                    .name(Component.literal("Play Mode"))
                    .description { OptionDescription.of(Component.literal("Loop All / Loop One / Sequential / Shuffle")) }
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
                                        PlayMode.SEQUENCE -> "Sequential"
                                        PlayMode.LOOP_ALL -> "Loop All"
                                        PlayMode.LOOP_ONE -> "Loop One"
                                        PlayMode.SHUFFLE -> "Shuffle"
                                    },
                                )
                            }
                    }
                    .build(),
            )
            .build()

        val categoryLyrics = ConfigCategory.createBuilder()
            .name(Component.literal("Lyrics"))
            .option(boolOption("Show Lyrics", "Show lyrics while playing", { lyricsEnabled }, { lyricsEnabled = it }))
            .option(boolOption("HUD Lyrics", "Show lyrics on the in-game HUD", { hudLyricEnabled }, { hudLyricEnabled = it }))
            .option(boolOption("Chat Lyrics", "Also send each lyric line to chat", { chatLyricEnabled }, { chatLyricEnabled = it }))
            .build()

        val categoryHud = ConfigCategory.createBuilder()
            .name(Component.literal("HUD"))
            .option(boolOption("HUD Panel", "Show the in-game music HUD (cover / title / progress)", { hudEnabled }, { hudEnabled = it }))
            .option(
                ButtonOption.createBuilder()
                    .name(Component.literal("Open HUD Editor"))
                    .description(OptionDescription.of(Component.literal("Drag to move the HUD, scroll to scale; changes apply live")))
                    .text(Component.literal("Edit"))
                    .action { io.github.cyf112233.musicmc.platform.McScreens.open(YaclHudEditorScreen()) }
                    .build(),
            )
            .build()

        val categoryAdvanced = ConfigCategory.createBuilder()
            .name(Component.literal("Advanced"))
            .option(
                Option.createBuilder<String>()
                    .name(Component.literal("FFmpeg Platform Override"))
                    .description {
                        OptionDescription.of(
                            Component.literal(
                                "Force the javacpp platform name (e.g. android-arm64 / linux-x86_64);" +
                                    "Leave empty for auto-detection. Usually no change needed.",
                            ),
                        )
                    }
                    .binding("", { nativeOverride }, { nativeOverride = it.trim() })
                    .controller { StringControllerBuilder.create(it) }
                    .build(),
            )
            .option(
                Option.createBuilder<String>()
                    .name(Component.literal("Native Lib Cache Dir"))
                    .description {
                        OptionDescription.of(
                            Component.literal(
                                "Android extract dir for javacpp/AAudio native libs (must be executable)." +
                                    "Leave empty for auto (first writable & executable of tmpdir → user.home → user.dir);" +
                                    "For non-FCL launchers or failed detection, set an app-private executable dir manually" +
                                    "(e.g. /data/user/0/<package>/cache/musicmc-native)",
                            ),
                        )
                    }
                    .binding("", { nativeCacheDir }, { nativeCacheDir = it.trim() })
                    .controller { StringControllerBuilder.create(it) }
                    .build(),
            )
            .build()

        val screen = YetAnotherConfigLib.createBuilder()
            .title(Component.literal("MusicMC Settings"))
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
