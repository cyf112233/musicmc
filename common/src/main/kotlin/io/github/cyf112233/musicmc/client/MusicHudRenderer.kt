package io.github.cyf112233.musicmc.client

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.platform.HudGui
import io.github.cyf112233.musicmc.ui.hud.HudFrame
import io.github.cyf112233.musicmc.ui.hud.HudLayout
import io.github.cyf112233.musicmc.ui.hud.HudLyricsCache
import net.minecraft.client.Minecraft
import kotlin.math.roundToInt

/**
 * 游戏内 HUD(悬浮音乐面板)渲染入口(仅 fabric / neoforge 编译,允许 net.minecraft)。
 *
 * fabric 端由 HudElementRegistry 注册的 HudElement 回调调用,
 * neoforge 端由 RegisterGuiLayersEvent 注册的 GuiLayer 回调调用,
 * 两平台回调先把各自的 GuiGraphicsExtractor 包装成 [HudGui](见 [GuiGraphicsHudGui])
 * 再传入本入口 —— common 不直接操作 MC 的 blit / fill / text / pose 等版本差异大的
 * 渲染 API,那些差异全部隔离在平台适配器内。
 *
 * 纯显示:不读鼠标 / 按键,位置只来自 config.hudX/hudY(由 HUD 编辑器持久化)。
 * 坐标 = GUI 缩放坐标(逻辑像素)。
 *
 * 封面纹理:后台线程只做下载 / 解码 / CPU 端方形预裁剪(见 CoverTextureCache),
 * onFrame 开头调 CoverTextureCache.pump() 在 GL 上下文内创建 DynamicTexture 并注册;
 * 绘制时以全图对称 UV 交给 [HudGui.drawTexture],不依赖具体 MC 版本对
 * blit UV 参数顺序的定义。
 */
object MusicHudRenderer {

    private var lastSongId: String? = null

    /** 歌词总开关上次记录的状态(切换时清空 / 重载歌词块;null=首帧未记录) */
    private var lastLyricsEnabled: Boolean? = null

    /** HUD 歌词开关上次记录的状态(切换时刷新歌词块;null=首帧未记录) */
    private var lastHudLyricEnabled: Boolean? = null

    /** 歌词缓存 dirty 重载节流:上次实际触发 refresh 的时间戳(ms) */
    private var lastLyricRefreshMs: Long = 0L

    /** 封面日志去重:上次记录过的"url + 就绪态"(无纹理 warn 一次 / 就绪 info 一次) */
    private var coverLogUrl: String? = null
    private var coverLogReady: Boolean? = null

    private const val LYRICS_REFRESH_THROTTLE_MS = 500L

    /** [Cover] 前缀调试日志(url 截 100 防刷屏),沿用 runCatching 静默模式 */
    private fun coverLog(level: String, msg: String) {
        runCatching {
            when (level) {
                "info" -> NetMusic.logger.info("[Cover] $msg")
                else -> NetMusic.logger.warn("[Cover] $msg")
            }
        }
    }

    private fun coverUrlTag(url: String?): String =
        url?.let { if (it.length > 100) it.take(100) + "…" else it } ?: "(无封面)"

    /** 渲染入口(fabric HudElement / neoforge GuiLayer 每帧调用,delta 未使用故不接收) */
    fun onFrame(gui: HudGui) {
        val mc = Minecraft.getInstance()

        // a) 每帧先驱动封面纹理注册(本回调在 extract/渲染阶段,GL 上下文有效;
        //    放在所有早退之前,保证任何界面状态下待注册纹理都能被消费)
        CoverTextureCache.pump()

        // b) 打开任何界面(screen != null)或 HUD 关闭 → 不绘制
        if (mc.screen != null || !NetMusic.config.hudEnabled) return

        val player = NetMusic.player
        val song = player.current ?: return
        val config = NetMusic.config

        // c) 换歌检查:刷新歌词与封面纹理
        if (song.id != lastSongId) {
            lastSongId = song.id
            HudLyricsCache.refresh(song)
            CoverTextureCache.prepare(song.picUrl)
        }

        // c2) 歌词 GUI(播放页偏移 / 手动绑定)改后 HUD 同步 + 开关切换联动:
        //     - 歌词总开关切换:开启 → 立即加载;关闭 → 清空缓存(HUD / 聊天栏都无数据);
        //     - HUD 歌词开关切换(显示层,独立于总开关):开启 → 确保数据已加载
        //       (refresh 内部受总开关 gate);关闭 → 仅不再显示,不 clear 缓存;
        //     - 总开关开启且缓存被 invalidate 标脏 → 节流 500ms 对当前歌重载
        //       (歌曲 id 未变,原 refresh 同歌 return,须走 dirty 放宽路径)。
        if (lastLyricsEnabled != config.lyricsEnabled) {
            lastLyricsEnabled = config.lyricsEnabled
            if (config.lyricsEnabled) {
                HudLyricsCache.refresh(song)
            } else {
                HudLyricsCache.clear()
            }
        } else if (lastHudLyricEnabled != config.hudLyricEnabled) {
            lastHudLyricEnabled = config.hudLyricEnabled
            if (config.hudLyricEnabled) HudLyricsCache.refresh(song)
        } else if (config.lyricsEnabled && HudLyricsCache.dirty) {
            val now = System.currentTimeMillis()
            if (now - lastLyricRefreshMs >= LYRICS_REFRESH_THROTTLE_MS) {
                lastLyricRefreshMs = now
                HudLyricsCache.refresh(song)
            }
        }

        // d) 布局(锚点 / 缩放只读配置,不做任何交互)
        //    HUD 歌词开关关闭时不传歌词快照(HUD 歌词块不画;聊天栏歌词不受影响)
        val frame = HudLayout.compute(
            w = gui.guiWidth(),
            h = gui.guiHeight(),
            scale = config.hudScale,
            player = player,
            lyric = if (config.hudLyricEnabled) HudLyricsCache.current else null,
            hudX = config.hudX,
            hudY = config.hudY,
        ) ?: return

        // e) 绘制
        drawCover(gui, frame)
        drawTexts(gui, frame, config.hudScale)
        drawProgressBar(gui, frame)
    }

    // ---------------- 绘制 ----------------

    /** 封面:纹理就绪则全图绘制(已 CPU 预裁剪方形,blit 对称 UV 见适配器),否则占位块 */
    private fun drawCover(gui: HudGui, frame: HudFrame) {
        if (!frame.showCover) return
        val id = CoverTextureCache.currentIdentifier()
        val url = frame.song?.picUrl
        // 调试日志:每首歌曲的就绪状态变化只记一次(无纹理 warn / 就绪 info),避免每帧刷屏
        val ready = id != null
        if (url != coverLogUrl || ready != coverLogReady) {
            coverLogUrl = url
            coverLogReady = ready
            val tag = coverUrlTag(url)
            if (ready) {
                // 纹理已是方形(CPU 预裁剪),绘制恒为全图对称 UV,不涉及版本差异;
                // 记源尺寸与目标矩形,排查显示问题时直接看日志
                coverLog(
                    "info",
                    "HUD 封面纹理就绪,开始绘制: $tag | src=${CoverTextureCache.currentImageSize()} " +
                        "rect=${frame.cover.x},${frame.cover.y},${frame.cover.w}x${frame.cover.h}(方形预裁剪,全图绘制)",
                )
            } else {
                coverLog("warn", "HUD 封面纹理未就绪,绘制占位块: $tag")
            }
        }
        if (id != null) {
            // 纹理已中心裁剪为方形,直接铺满 cover 矩形;适配器内用全图对称 UV blit,
            // 不在此计算裁剪 UV(旧实现按比例算 UV 依赖 MC 版本对 blit 参数顺序的定义)
            gui.drawTexture(id, frame.cover.x, frame.cover.y, frame.cover.w, frame.cover.h)
        } else {
            // 占位块(纹理加载中 / 无封面 / 加载失败)
            val c = frame.cover
            gui.fill(c.x, c.y, c.x + c.w, c.y + c.h, 0xFF333333.toInt())
        }
    }

    /** 文本:标题 / 艺术家 / 时间 / 歌词块(字号缩放由适配器 [HudGui.drawText] 内部完成) */
    private fun drawTexts(gui: HudGui, frame: HudFrame, scale: Float) {
        val textW = frame.bar.w.toFloat()
        // 统一缩放系数:布局(HudLayout)所有尺寸都乘 s = scale×0.5,字号必须同系数,
        // 否则行距(按 s)< 字高(按 scale)导致文字重叠;编辑器侧同样用 s,两侧一致。
        val s = scale.coerceIn(0.5f, 2f) * 0.5f

        gui.drawText(
            truncate(gui, frame.title, textW, HudLayout.TITLE_SIZE / gui.textHeight() * s),
            frame.titleX, frame.titleY, HudLayout.TITLE_SIZE, s, 0xFFFFFFFF.toInt(),
        )
        if (frame.artist.isNotBlank()) {
            gui.drawText(
                truncate(gui, frame.artist, textW, HudLayout.SUB_SIZE / gui.textHeight() * s),
                frame.artistX, frame.artistY, HudLayout.SUB_SIZE, s, 0xFFAAAAAA.toInt(),
            )
        }
        gui.drawText(frame.timeText, frame.timeX, frame.timeY, HudLayout.SUB_SIZE, s, 0xFFBBBBBB.toInt())

        // 歌词块:仅当前行(高亮);超长时横向往返滚动(不截断,见 HudLayout.lyricScrollOffset)
        if (frame.lyricLines.isNotEmpty()) {
            val i = frame.lyricCurrentIndex
            val text = frame.lyricLines[i]
            val size = HudLayout.LYRIC_CURRENT_SIZE
            val k = size / gui.textHeight() * s
            val textWpx = gui.textWidth(text) * k
            val offsetX = HudLayout.lyricScrollOffset(System.currentTimeMillis(), textWpx, textW)
            gui.drawText(text, frame.lyricX + offsetX.roundToInt(), frame.lyricY, size, s, 0xFF4FC3F7.toInt())
        }
    }

    /** 进度条:背景半透明深色 fill + 前景白色 fill(渲染层拿不到 MUI theme,用固定色) */
    private fun drawProgressBar(gui: HudGui, frame: HudFrame) {
        val b = frame.bar
        if (b.w <= 0 || b.h <= 0) return
        gui.fill(b.x, b.y, b.x + b.w, b.y + b.h, 0x80000000.toInt())
        val fw = (b.w * frame.progress).toInt().coerceIn(0, b.w)
        if (fw > 0) gui.fill(b.x, b.y, b.x + fw, b.y + b.h, 0xFFFFFFFF.toInt())
    }

    /** 按可用宽度截断文本(考虑缩放系数 [k]),超长加省略号 */
    private fun truncate(gui: HudGui, text: String, maxWidth: Float, k: Float): String {
        if (text.isEmpty() || maxWidth <= 0f) return text
        if (gui.textWidth(text) * k <= maxWidth) return text
        var t = text
        while (t.length > 1 && gui.textWidth(t + "…") * k > maxWidth) {
            t = t.dropLast(1)
        }
        return t + "…"
    }
}
