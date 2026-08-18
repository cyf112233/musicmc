package io.github.cyf112233.musicmc.platform

import java.nio.file.Path

/**
 * 平台无关的日志接口,由 loader(fabric/neoforge)侧实现。
 */
interface MusicLogger {
    fun info(msg: String)
    fun warn(msg: String)
    fun error(msg: String, t: Throwable? = null)
}

/**
 * loader 侧注入的平台能力契约。common 模块不依赖任何 net.minecraft / com.mojang 类。
 */
interface ModPlatform {
    /** 配置文件目录(如 config/musicmc) */
    fun configDirectory(): Path
    fun logger(): MusicLogger
    /** 打开音乐界面(loader 负责用 ModernUI 展示 [io.github.cyf112233.musicmc.ui.MusicMainFragment]) */
    fun openMusicScreen()
    /** 打开 HUD 编辑器(loader 负责用 ModernUI 展示 [io.github.cyf112233.musicmc.ui.HudEditorFragment]) */
    fun openHudEditor()
    /** 关闭当前打开的屏幕(回到游戏;HUD 编辑器"完成"用) */
    fun closeScreen()
    /** 回 UI 线程执行(ModernUI 主线程) */
    fun postToUiThread(runnable: Runnable)
}

object PlatformHolder {
    @Volatile
    private var platform: ModPlatform? = null

    fun set(p: ModPlatform) {
        platform = p
    }

    fun require(): ModPlatform =
        platform ?: error("NetMusic not initialized")
}
