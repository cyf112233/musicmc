package io.github.cyf112233.musicmc.neoforge

import com.mojang.blaze3d.platform.InputConstants
import icyllis.modernui.mc.MuiModApi
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.common.NeoForge
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.client.ChatLyricSender
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.client.MusicHudRenderer
import io.github.cyf112233.musicmc.platform.ModPlatform
import io.github.cyf112233.musicmc.platform.MusicLogger
import io.github.cyf112233.musicmc.player.PlayerState
import io.github.cyf112233.musicmc.ui.HudEditorFragment
import io.github.cyf112233.musicmc.ui.MusicMainFragment
import io.github.cyf112233.musicmc.ui.UiBackend
import io.github.cyf112233.musicmc.ui.UiBackendResolver
import io.github.cyf112233.musicmc.ui.yacl.YaclMusicScreen
import net.neoforged.fml.ModList
import java.nio.file.Path

/**
 * NeoForge 入口。
 *
 * 必须以 class(而非 object)声明:javafml 语言加载器通过 FMLModContainer 反射实例化
 * net.neoforged.fml.javafmlmod.FMLModContainer 仅接受 public 构造函数:
 *   (IEventBus)、(IEventBus, ModContainer) 或无参。
 * Kotlin 的 `private val bus: IEventBus` 构造函数对 JVM 是 public,且携带 IEventBus 参数,
 * 符合注入要求(与 NeoForge 26.1.2 源码一致,已核实)。
 */
@Mod("musicmc")
class NetMusicNeoForge(private val bus: IEventBus) {

    init {
        // 幂等注入平台实现(common 契约)
        NetMusic.init(NeoForgePlatform())

        // 以下全部为物理客户端专属逻辑:
        // 先判断 dist,避免 DedicatedServer 上加载 @OnlyIn(Dist.CLIENT) 的客户端类
        // (KeyMapping / MuiModApi 等)导致 ClassNotFoundError。
        // 顶层 KEY / KEY_CATEGORY 因此也只在本分支内被引用,惰性初始化。
        if (FMLEnvironment.getDist().isClient) {
            // 0) 聊天栏歌词:每句歌词同步输出到玩家聊天栏(独立于 HUD,开关见设置)
            NetMusic.player.addListener(ChatLyricSender)

            // 1) 按键注册:RegisterKeyMappingsEvent 是 IModBusEvent,挂 mod 总线
            bus.addListener(RegisterKeyMappingsEvent::class.java) { event ->
                event.registerCategory(KEY_CATEGORY)
                event.register(KEY)
            }

            // 2) 客户端 tick 检测按键:ClientTickEvent.Post 挂主总线
            //    (NeoForge 26.x 由 Minecraft.tick() -> ClientHooks.fireClientTickPost() 触发,已核实)
            NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) {
                while (KEY.consumeClick()) NetMusic.openScreen()
                suppressVanillaMusic()
            }

            // 3) 平台配置菜单入口:NeoForge 自带 Mod 列表界面 → MusicMC → Config
            //    按钮打开 Cloth Config 设置(不再注册 /netmusic 命令;跨平台配置入口
            //    统一走平台 mod 菜单:fabric 用 ModMenu,neoforge 用自带界面)
            ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory::class.java) {
                IConfigScreenFactory { _, parent -> io.github.cyf112233.musicmc.ui.yacl.YaclConfigScreen.open(parent) }
            }

            // 4) 游戏内 HUD(悬浮音乐面板):RegisterGuiLayersEvent 是 IModBusEvent,
            //    挂 mod 总线。回调拿到 (GuiGraphicsExtractor, DeltaTracker) 后立即
            //    包成统一绘制接口 HudGui(见 common GuiGraphicsHudGui),common 渲染
            //    逻辑不再直接碰 MC blit / fill / text 等版本差异大的内部 API。
            bus.addListener(RegisterGuiLayersEvent::class.java) { event ->
                event.registerAboveAll(
                    Identifier.fromNamespaceAndPath("musicmc", "music_hud"),
                ) { graphics, _ ->
                    MusicHudRenderer.onFrame(GuiGraphicsHudGui(graphics))
                }
            }
        }
    }
}

// ---- 按键定义(客户端专属,顶层属性仅在客户端分支被引用)----
// KeyMapping 构造签名已通过 javap(MC 26.1.2 client.jar)验证:
//   public KeyMapping(String name, InputConstants.Type type, int keyCode, KeyMapping.Category category)
// 26.1 起分类是记录类 KeyMapping.Category(Identifier),不再是旧版 String 分类;
// 其 label() 实现为 id.toLanguageKey("key.category"),因此自定义分类的
// 语言键格式为 "key.category.<namespace>.<path>"(NeoForge 26.x 文档亦如此)。
private val KEY_CATEGORY: KeyMapping.Category =
    KeyMapping.Category(Identifier.fromNamespaceAndPath("musicmc", "musicmc"))

private val KEY: KeyMapping =
    KeyMapping("key.musicmc.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, KEY_CATEGORY)

/**
 * 播放中抑制 Minecraft 环境音乐。
 *
 * 无 mixin 方案下,vanilla 的 MusicManager.tick 每 client tick 都会运行,
 * 播完或被 stopPlaying 打断后会按自己的节拍重新 startPlaying 环境音乐,
 * 所以不能在开始播放时只调用一次 stopPlaying,必须在 PLAYING 状态下
 * 每个 client tick 持续抑制;暂停/停止后条件不成立,vanilla 音乐自动
 * 恢复,符合预期。
 *
 * 注:已 javap 核实 MC 26.1.2 的 MusicManager 没有 isPlaying() 方法
 * (仅有 isPlayingMusic(Music) 需要传入当前播放的 Music 实例,此处无法得知),
 * 故保持"每 tick 直接 stopPlaying"的现状,不做空调用优化。
 *
 * 与 KEY / KEY_CATEGORY 同理,Minecraft 等客户端类仅在本函数体内被引用,
 * 且本函数只在 isClient 分支注册的 ClientTickEvent.Post 中调用,惰性初始化,
 * 不会在 DedicatedServer 上触发 ClassNotFoundError。
 */
private fun suppressVanillaMusic() {
    if (NetMusic.config.pauseGameMusicOnPlay && NetMusic.player.state == PlayerState.PLAYING) {
        Minecraft.getInstance().musicManager.stopPlaying()
    }
}

// ---- 平台实现 ----
private class NeoForgePlatform : ModPlatform {

    private val logger = LoggerFactory.getLogger("musicmc")

    override fun configDirectory(): Path = FMLPaths.CONFIGDIR.get()

    override fun logger(): MusicLogger = Slf4jMusicLogger(logger)

    override fun openMusicScreen() {
        // UI 后端按配置 + 已加载 mod 自动选择:
        // PC:ModernUI > YACL > 原版;Android:YACL > 原版(ModernUI 永不用于 Android)
        when (
            UiBackendResolver.resolve(
                NetMusic.config.uiMode,
                io.github.cyf112233.musicmc.player.ffmpeg.NativeLibBridge.isAndroid(),
                isModernUiLoaded(),
            )
        ) {
            UiBackend.YACL -> io.github.cyf112233.musicmc.platform.McScreens.open(YaclMusicScreen())
            UiBackend.MODERN_UI -> io.github.cyf112233.musicmc.platform.McScreens.open(MuiModApi.get().createScreen(MusicMainFragment()))
        }
    }

    override fun isModernUiLoaded(): Boolean = ModList.get().isLoaded("modernui")

    override fun isChinese(): Boolean = runCatching {
        val code = Minecraft.getInstance().options.languageCode
        !code.isNullOrBlank() && code.lowercase().startsWith("zh")
    }.getOrDefault(false)


    override fun openConfigScreen() {
        io.github.cyf112233.musicmc.ui.yacl.YaclConfigScreen.open(io.github.cyf112233.musicmc.platform.McScreens.current())
    }

    override fun openHudEditor() {
        io.github.cyf112233.musicmc.platform.McScreens.open(MuiModApi.get().createScreen(HudEditorFragment()))
    }

    override fun closeScreen() {
        io.github.cyf112233.musicmc.platform.McScreens.open(null)
    }

    override fun postToUiThread(runnable: Runnable) {
        // 按实际 UI 后端选择线程:ModernUI 界面用 ModernUI 主线程,
        // YACL/原版界面用 MC 渲染(主)线程 —— 不依赖 ModernUI(ModernUI 可选)
        val backend = io.github.cyf112233.musicmc.ui.UiBackendResolver.resolve(
            io.github.cyf112233.musicmc.NetMusic.config.uiMode,
            io.github.cyf112233.musicmc.player.ffmpeg.NativeLibBridge.isAndroid(),
            isModernUiLoaded(),
        )
        if (backend == io.github.cyf112233.musicmc.ui.UiBackend.MODERN_UI) {
            MuiModApi.postToUiThread(runnable)
        } else {
            Minecraft.getInstance().execute(runnable)
        }
    }
}

/** org.slf4j.Logger 到 common 契约 MusicLogger 的适配器。 */
private class Slf4jMusicLogger(private val log: org.slf4j.Logger) : MusicLogger {
    override fun info(msg: String) {
        log.info(msg)
    }

    override fun warn(msg: String) {
        log.warn(msg)
    }

    override fun error(msg: String, t: Throwable?) {
        if (t != null) log.error(msg, t) else log.error(msg)
    }
}
