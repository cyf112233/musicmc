// MusicMC Fabric 平台实现。
//
// TODO: MusicLogger 接口形状以 common 模块契约为准(common 尚在编写中),
// 此处按常见形状实现(info/warn/error/debug + 带 Throwable 的重载),
// 若与 common 契约不一致,构建代理校验后修正。
package io.github.cyf112233.musicmc.fabric

import io.github.cyf112233.musicmc.platform.ModPlatform
import io.github.cyf112233.musicmc.platform.MusicLogger
import io.github.cyf112233.musicmc.ui.HudEditorFragment
import io.github.cyf112233.musicmc.ui.MusicMainFragment
import icyllis.modernui.mc.MuiModApi
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.nio.file.Path

class FabricPlatform : ModPlatform {

    private val logger: MusicLogger = object : MusicLogger {
        private val slf4j = LoggerFactory.getLogger("musicmc")

        override fun info(msg: String) = slf4j.info(msg)

        override fun warn(msg: String) = slf4j.warn(msg)

        override fun error(msg: String, t: Throwable?) {
            if (t != null) slf4j.error(msg, t) else slf4j.error(msg)
        }
    }

    override fun configDirectory(): Path = FabricLoader.getInstance().configDir

    override fun logger(): MusicLogger = logger

    override fun openMusicScreen() {
        // Android 恒 YACL;PC 按 UiBackendResolver 分派(ModernUI 装了用 ModernUI,否则 YACL)
        when (
            io.github.cyf112233.musicmc.ui.UiBackendResolver.resolve(
                io.github.cyf112233.musicmc.NetMusic.config.uiMode,
                io.github.cyf112233.musicmc.player.ffmpeg.NativeLibBridge.isAndroid(),
                isModernUiLoaded(),
            )
        ) {
            io.github.cyf112233.musicmc.ui.UiBackend.MODERN_UI ->
                io.github.cyf112233.musicmc.platform.McScreens.open(MuiModApi.get().createScreen(MusicMainFragment()))
            io.github.cyf112233.musicmc.ui.UiBackend.YACL ->
                io.github.cyf112233.musicmc.platform.McScreens.open(io.github.cyf112233.musicmc.ui.yacl.YaclMusicScreen())
        }
    }

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

    override fun isModernUiLoaded(): Boolean = FabricLoader.getInstance().isModLoaded("modernui")

}
