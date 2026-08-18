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
        Minecraft.getInstance().setScreen(MuiModApi.get().createScreen(MusicMainFragment()))
    }

    override fun openHudEditor() {
        Minecraft.getInstance().setScreen(MuiModApi.get().createScreen(HudEditorFragment()))
    }

    override fun closeScreen() {
        Minecraft.getInstance().setScreen(null)
    }

    override fun postToUiThread(runnable: Runnable) {
        // postToUiThread 是 MuiModApi 的静态方法(javap 已核实),须经类名调用
        MuiModApi.postToUiThread(runnable)
    }
}
