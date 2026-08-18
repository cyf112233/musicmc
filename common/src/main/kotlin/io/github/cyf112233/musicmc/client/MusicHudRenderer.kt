package io.github.cyf112233.musicmc.client

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.ui.hud.HudFrame
import io.github.cyf112233.musicmc.ui.hud.HudLayout
import io.github.cyf112233.musicmc.ui.hud.HudLyricsCache
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.roundToInt

/**
 * 游戏内 HUD(悬浮音乐面板)渲染入口(仅 fabric / neoforge 编译,允许 net.minecraft)。
 *
 * fabric 端由 HudElementRegistry 注册的 HudElement.extractRenderState 调用,
 * neoforge 端由 RegisterGuiLayersEvent.registerAboveAll 注册的 GuiLayer.render 调用,
 * 两者签名一致,都走 [onFrame](主线程 / 渲染线程,GL 上下文有效)。
 *
 * 纯显示:不读鼠标 / 按键,位置只来自 config.hudX/hudY(由 HUD 编辑器持久化)。
 * 绘制全部使用 GuiGraphicsExtractor 公开方法,坐标 = GUI 缩放坐标(逻辑 px × hudScale);
 * 文本字号用 pose 缩放实现(blit / text 在调用时复制当前 pose 到 render state,
 * 已 javap 验证,故 push/transform/draw/pop 安全)。
 *
 * 封面纹理:后台线程只做下载 / 解码 / 圆形遮罩,onFrame 开头调
 * CoverTextureCache.pump() 在 GL 上下文内创建 DynamicTexture 并注册(修复游戏内
 * 封面不显示 —— 旧实现经 Async.onUi 创建,切到的是 MUI 主线程而非 GL 渲染线程)。
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

    /** 渲染入口(fabric HudElement / neoforge GuiLayer 每帧调用) */
    fun onFrame(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
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
            w = graphics.guiWidth(),
            h = graphics.guiHeight(),
            scale = config.hudScale,
            player = player,
            lyric = if (config.hudLyricEnabled) HudLyricsCache.current else null,
            hudX = config.hudX,
            hudY = config.hudY,
        ) ?: return

        // e) 绘制
        drawCover(graphics, frame)
        drawTexts(graphics, mc, frame, config.hudScale)
        drawProgressBar(graphics, frame)
    }

    // ---------------- 绘制 ----------------

    /** 封面:纹理就绪则 blit(方形,无旋转),否则占位块 */
    private fun drawCover(graphics: GuiGraphicsExtractor, frame: HudFrame) {
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
                // 附上源图尺寸 / 中心裁剪 UV / 目标矩形,排查方形显示问题时直接看日志
                val uv = coverUv(CoverTextureCache.currentImageSize(), frame.cover.w, frame.cover.h)
                coverLog(
                    "info",
                    "HUD 封面纹理就绪,开始绘制: $tag | src=${CoverTextureCache.currentImageSize()} " +
                        "uv=[${uv[0]},${uv[1]},${uv[2]},${uv[3]}] rect=${frame.cover.x},${frame.cover.y},${frame.cover.w}x${frame.cover.h}",
                )
            } else {
                coverLog("warn", "HUD 封面纹理未就绪,绘制占位块: $tag")
            }
        }
        if (id != null) {
            // 按源图片比例中心裁剪 UV(cover 矩形为方形;B 站封面 16:9,不裁剪会压扁变形)
            val uv = coverUv(CoverTextureCache.currentImageSize(), frame.cover.w, frame.cover.h)
            // MC 26.1.2 blit 九参重载的 UV 字段顺序为 u0, u1, v0, v1(见 BlitRenderState,
            // innerBlit 把调用方 (u0, v0, u1, v1) 原样填入),传参必须按此顺序:
            // 水平范围在前、垂直范围在后。若按直觉的 (u0, v0, u1, v1) 传,非对称 UV 会
            // u/v 交换,裁出的是源图角落区域(用户反馈"封面像把右下角割下来用")。
            val (u0, v0, u1, v1) = uv
            graphics.blit(id, frame.cover.x, frame.cover.y, frame.cover.w, frame.cover.h, u0, u1, v0, v1)
        } else {
            // 占位块(纹理加载中 / 无封面 / 加载失败)
            val c = frame.cover
            graphics.fill(c.x, c.y, c.x + c.w, c.y + c.h, 0xFF333333.toInt())
        }
    }

    /**
     * 计算中心裁剪 UV(u0, v0, u1, v1,归一化 0..1):保持源图宽高比铺满目标矩形,
     * 超出目标比例的边居中裁掉。目标为方形且源图为 16:9 时裁左右。
     * 无尺寸信息(纹理注册前)恒返回全图。
     */
    private fun coverUv(size: Pair<Int, Int>?, dstW: Int, dstH: Int): FloatArray {
        val (iw, ih) = size ?: return floatArrayOf(0f, 0f, 1f, 1f)
        if (iw <= 0 || ih <= 0 || dstW <= 0 || dstH <= 0) return floatArrayOf(0f, 0f, 1f, 1f)
        val imgAspect = iw.toFloat() / ih
        val dstAspect = dstW.toFloat() / dstH
        var u0 = 0f
        var v0 = 0f
        var u1 = 1f
        var v1 = 1f
        if (imgAspect > dstAspect) {
            val crop = 1f - dstAspect / imgAspect
            u0 = crop / 2f
            u1 = 1f - crop / 2f
        } else if (imgAspect < dstAspect) {
            val crop = 1f - imgAspect / dstAspect
            v0 = crop / 2f
            v1 = 1f - crop / 2f
        }
        return floatArrayOf(u0, v0, u1, v1)
    }

    /** 文本:标题 / 艺术家 / 时间 / 歌词块(字号用 pose 缩放,颜色固定值,注释见报告) */
    private fun drawTexts(graphics: GuiGraphicsExtractor, mc: Minecraft, frame: HudFrame, scale: Float) {
        val font = mc.font
        val textW = frame.bar.w.toFloat()
        // 统一缩放系数:布局(HudLayout)所有尺寸都乘 s = scale×0.5,字号必须同系数,
        // 否则行距(按 s)< 字高(按 scale)导致文字重叠;编辑器侧同样用 s,两侧一致。
        val s = scale.coerceIn(0.5f, 2f) * 0.5f

        drawScaledText(
            graphics, mc,
            truncate(font, frame.title, textW, HudLayout.TITLE_SIZE / font.lineHeight * s),
            frame.titleX, frame.titleY, HudLayout.TITLE_SIZE, s, 0xFFFFFFFF.toInt(),
        )
        if (frame.artist.isNotBlank()) {
            drawScaledText(
                graphics, mc,
                truncate(font, frame.artist, textW, HudLayout.SUB_SIZE / font.lineHeight * s),
                frame.artistX, frame.artistY, HudLayout.SUB_SIZE, s, 0xFFAAAAAA.toInt(),
            )
        }
        drawScaledText(graphics, mc, frame.timeText, frame.timeX, frame.timeY, HudLayout.SUB_SIZE, s, 0xFFBBBBBB.toInt())

        // 歌词块:仅当前行(高亮);超长时横向往返滚动(不截断,见 HudLayout.lyricScrollOffset)
        if (frame.lyricLines.isNotEmpty()) {
            val i = frame.lyricCurrentIndex
            val text = frame.lyricLines[i]
            val size = HudLayout.LYRIC_CURRENT_SIZE
            val k = size / font.lineHeight * s
            val textWpx = font.width(text) * k
            val offsetX = HudLayout.lyricScrollOffset(System.currentTimeMillis(), textWpx, textW)
            drawScaledText(
                graphics, mc,
                text,
                frame.lyricX + offsetX.roundToInt(), frame.lyricY,
                size, s, 0xFF4FC3F7.toInt(),
            )
        }
    }

    /** 进度条:背景半透明深色 fill + 前景白色 fill(渲染层拿不到 MUI theme,用固定色) */
    private fun drawProgressBar(graphics: GuiGraphicsExtractor, frame: HudFrame) {
        val b = frame.bar
        if (b.w <= 0 || b.h <= 0) return
        graphics.fill(b.x, b.y, b.x + b.w, b.y + b.h, 0x80000000.toInt())
        val fw = (b.w * frame.progress).toInt().coerceIn(0, b.w)
        if (fw > 0) graphics.fill(b.x, b.y, b.x + fw, b.y + b.h, 0xFFFFFFFF.toInt())
    }

    /**
     * 以 [sizePx] 逻辑字号绘制文本:pose push → translate(x,y) →
     * scale(sizePx/lineHeight × sf) → text(font, text, 0, 0, color) → pop
     * (GuiTextRenderState 在调用时复制 pose,已 javap 验证)。
     *
     * [sf] 传统一缩放系数 s = hudScale×0.5(HudLayout 布局与编辑器同一系数,
     * 保证行距(≥字号)不重叠、两侧字体大小一致)。
     */
    private fun drawScaledText(
        graphics: GuiGraphicsExtractor,
        mc: Minecraft,
        text: String,
        x: Int,
        y: Int,
        sizePx: Float,
        scale: Float,
        color: Int,
    ) {
        if (text.isEmpty()) return
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(sizePx / mc.font.lineHeight * scale)
        graphics.text(mc.font, text, 0, 0, color)
        pose.popMatrix()
    }

    /** 按可用宽度截断文本(考虑缩放系数 [k]),超长加省略号 */
    private fun truncate(font: Font, text: String, maxWidth: Float, k: Float): String {
        if (text.isEmpty() || maxWidth <= 0f) return text
        if (font.width(text) * k <= maxWidth) return text
        var t = text
        while (t.length > 1 && font.width(t + "…") * k > maxWidth) {
            t = t.dropLast(1)
        }
        return t + "…"
    }
}
