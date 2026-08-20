package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.client.CoverTextureCache
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.player.PlayerState
import io.github.cyf112233.musicmc.platform.McScreens
import io.github.cyf112233.musicmc.ui.Widgets
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 现代化主界面(uiMode=YACL 或 AUTO 且 Android / 无 ModernUI 时)。
 *
 * 现代化视觉:深色渐变背景、主题绿进度、卡片式分组、胶囊按钮、hover 高亮、
 * 状态着色;进度条 / 音量条带可拖动滑块与拖动反馈(拖动中显示目标时间/音量,
 * 按住后可移出条身继续拖动,松手结束);布局与交互语义与原版主界面一致
 * (工具行 / 曲目信息 / 进度条可拖拽 seek / 音量条 / 控制行),功能零回归。
 *
 * 屏幕切换统一走 McScreens 版本自适应桥(26.1 调 Minecraft.setScreen,
 * 26.2 调 Minecraft.gui.setScreen),保证同一 jar 双版本可用。
 */
class YaclMusicScreen : Screen(Component.literal("MusicMC")) {

    private val player get() = NetMusic.player
    private val config get() = NetMusic.config

    private val rectSearch = YaclTheme.Rect(0, 0, 0, 0)
    private val rectDiscover = YaclTheme.Rect(0, 0, 0, 0)
    private val rectQueue = YaclTheme.Rect(0, 0, 0, 0)
    private val rectFav = YaclTheme.Rect(0, 0, 0, 0)
    private val rectLyrics = YaclTheme.Rect(0, 0, 0, 0)
    private val rectSettings = YaclTheme.Rect(0, 0, 0, 0)
    private val rectClose = YaclTheme.Rect(0, 0, 0, 0)
    private val rectPrev = YaclTheme.Rect(0, 0, 0, 0)
    private val rectPlay = YaclTheme.Rect(0, 0, 0, 0)
    private val rectNext = YaclTheme.Rect(0, 0, 0, 0)
    private val rectMode = YaclTheme.Rect(0, 0, 0, 0)
    private val rectProgress = YaclTheme.Rect(0, 0, 0, 0)
    private val rectVolume = YaclTheme.Rect(0, 0, 0, 0)

    private var lastSongId: String? = null

    // ---- 进度 / 音量拖动状态(优化拖动逻辑:按下即锁定,可移出条身继续拖) ----
    private var draggingProgress = false
    private var draggingVolume = false
    /** 拖动进度条时缓存的当前歌曲总时长(切换歌曲时重置) */
    private var dragDurationMs = 0
    /** 最近一次拖动进度条的 x(拖动预览用;非拖动态无效) */
    private var lastDragX = 0.0
    /** 拖动节流:seek 是"停旧会话开新会话",每帧触发会高频切会话,150ms 节流一次 */
    private var lastSeekMs = 0L

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val g = GuiGraphicsHudGui(graphics)
        val w = g.guiWidth()
        val h = g.guiHeight()

        CoverTextureCache.pump()
        val song = player.current
        if (song != null && song.id != lastSongId) {
            lastSongId = song.id
            dragDurationMs = song.durationMs
            CoverTextureCache.prepare(song.picUrl)
        }

        YaclTheme.drawBackground(g, w, h)

        // ---- 顶部栏:标题 + 工具按钮 ----
        g.drawText("MusicMC", 12, 8, 14f, 1f, YaclTheme.colorTextMain)
        val topBtnW = 48
        val topBtnH = 15
        val topBtnY = 8
        rectClose.set(w - topBtnW - 4, topBtnY, w - 4, topBtnY + topBtnH)
        rectSettings.set(w - 2 * topBtnW - 8, topBtnY, w - topBtnW - 4, topBtnY + topBtnH)
        rectLyrics.set(w - 3 * topBtnW - 12, topBtnY, w - 2 * topBtnW - 8, topBtnY + topBtnH)
        rectFav.set(w - 4 * topBtnW - 16, topBtnY, w - 3 * topBtnW - 12, topBtnY + topBtnH)
        rectQueue.set(w - 5 * topBtnW - 20, topBtnY, w - 4 * topBtnW - 16, topBtnY + topBtnH)
        rectDiscover.set(w - 6 * topBtnW - 24, topBtnY, w - 5 * topBtnW - 20, topBtnY + topBtnH)
        rectSearch.set(w - 7 * topBtnW - 28, topBtnY, w - 6 * topBtnW - 24, topBtnY + topBtnH)
        YaclTheme.drawPill(g, rectSearch, "搜索", mouseX, mouseY)
        YaclTheme.drawPill(g, rectDiscover, "发现", mouseX, mouseY)
        YaclTheme.drawPill(g, rectQueue, "队列", mouseX, mouseY)
        YaclTheme.drawPill(g, rectFav, "收藏", mouseX, mouseY)
        YaclTheme.drawPill(g, rectLyrics, "歌词", mouseX, mouseY)
        YaclTheme.drawPill(g, rectSettings, "设置", mouseX, mouseY)
        YaclTheme.drawPill(g, rectClose, "关闭", mouseX, mouseY)

        // ---- 中部卡片:封面 + 曲目信息 ----
        val panelX = (w - 340).coerceAtLeast(8) / 2
        val panelW = 340
        val cardY = 40
        val cardH = 200
        YaclTheme.drawCard(g, panelX - 8, cardY - 8, panelX + panelW + 8, cardY + cardH + 8)

        val coverY = cardY
        val coverSize = 96
        val coverId = CoverTextureCache.currentIdentifier()
        if (coverId != null) {
            YaclTheme.drawCover(g, coverId, panelX, coverY, coverSize)
        } else {
            YaclTheme.drawCoverPlaceholder(g, panelX, coverY, coverSize)
        }
        val textX = panelX + coverSize + 12
        val titleY = coverY
        val artistY = titleY + 22
        val albumY = artistY + 18
        val stateY = albumY + 18

        if (song != null) {
            g.drawText(song.title.ifBlank { "未知标题" }, textX, titleY, 16f, 1f, YaclTheme.colorTextMain)
            g.drawText(song.artist, textX, artistY, 12f, 1f, YaclTheme.colorTextSub)
            if (song.album.isNotBlank()) g.drawText(song.album, textX, albumY, 12f, 1f, YaclTheme.colorTextDim)
            val (stateText, stateColor) = when (player.state) {
                PlayerState.PLAYING -> "播放中" to YaclTheme.colorAccentBright
                PlayerState.PAUSED -> "已暂停" to YaclTheme.colorWarn
                PlayerState.LOADING -> "加载中…" to YaclTheme.colorTextSub
                PlayerState.ERROR -> "播放出错" to YaclTheme.colorError
                else -> "未播放" to YaclTheme.colorTextDim
            }
            g.drawText(stateText, textX, stateY, 12f, 1f, stateColor)
        } else {
            g.drawText("未在播放", textX, titleY, 16f, 1f, YaclTheme.colorTextMain)
            g.drawText("点击下方「搜索歌曲」搜索并播放", textX, artistY, 12f, 1f, YaclTheme.colorTextSub)
        }

        // ---- 进度条 + 时间(拖动中显示目标时间) ----
        val barY = coverY + coverSize + 26
        val barH = 6
        val barW = panelW
        rectProgress.set(panelX, barY, panelX + barW, barY + barH)
        val posMs = player.engine.positionMs()
        val durMs = if (draggingProgress) dragDurationMs else (song?.durationMs ?: 0)
        val progress = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
        YaclTheme.drawProgressBar(
            g, rectProgress, progress, mouseX, mouseY,
            hoverable = true, active = draggingProgress, color = YaclTheme.colorAccent,
        )
        val timeText = if (draggingProgress) {
            "${Widgets.formatTime(dragPreviewMs(posMs, durMs))} / ${Widgets.formatTime(durMs)}"
        } else {
            "${Widgets.formatTime(posMs)} / ${Widgets.formatTime(durMs)}"
        }
        g.drawText(timeText, panelX, barY + 10, 10f, 1f, YaclTheme.colorTextSub)

        // ---- 控制行 ----
        val ctrlY = barY + 32
        val btnH = 20
        val btnW = 40
        val gap = 8
        rectPrev.set(panelX, ctrlY, panelX + btnW, ctrlY + btnH)
        rectPlay.set(panelX + btnW + gap, ctrlY, panelX + 2 * btnW + gap, ctrlY + btnH)
        rectNext.set(panelX + 2 * btnW + 2 * gap, ctrlY, panelX + 3 * btnW + 2 * gap, ctrlY + btnH)
        YaclTheme.drawBtn(g, rectPrev, "<<", mouseX, mouseY)
        YaclTheme.drawBtn(g, rectPlay, if (player.state == PlayerState.PLAYING) "||" else ">", mouseX, mouseY, accent = true)
        YaclTheme.drawBtn(g, rectNext, ">>", mouseX, mouseY)

        val modeLabel = "模式:" + Widgets.playModeLabel(player.mode)
        val modeW = 96
        rectMode.set(panelX + 3 * btnW + 3 * gap, ctrlY, panelX + 3 * btnW + 3 * gap + modeW, ctrlY + btnH)
        YaclTheme.drawBtn(g, rectMode, modeLabel, mouseX, mouseY)

        // ---- 音量条(带滑块;拖动中显示百分比) ----
        val volX = panelX + 3 * btnW + 3 * gap + modeW + 12
        val volW = panelW - (volX - panelX)
        val volY = ctrlY + (btnH - 4) / 2
        rectVolume.set(volX, volY, volX + volW, volY + 4)
        val volF = config.volume.coerceIn(0f, 1f)
        YaclTheme.drawProgressBar(
            g, rectVolume, volF, mouseX, mouseY,
            hoverable = true, active = draggingVolume, color = YaclTheme.colorAccent,
            showThumb = true,
        )
        val volLabel = if (draggingVolume) "音量 ${(volF * 100).toInt()}%" else "音量"
        g.drawText(volLabel, volX, ctrlY - 14, 10f, 1f, if (draggingVolume) YaclTheme.colorAccentBright else YaclTheme.colorTextDim)

        // ---- 无歌曲:提示文字(顶部工具行已有「搜索」按钮,不重复放入口) ----
        if (song == null) {
            val emptyY = ctrlY + 40
            g.drawText("点上方「搜索」搜索并播放", panelX, emptyY + 2, 11f, 1f, YaclTheme.colorTextSub)
        }
    }

    // ---------------- 交互 ----------------

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        when {
            rectClose.hit(x, y) -> { McScreens.open(null); return true }
            rectSearch.hit(x, y) -> { McScreens.open(YaclSearchScreen(this)); return true }
            rectDiscover.hit(x, y) -> { McScreens.open(YaclDiscoverScreen(this)); return true }
            rectQueue.hit(x, y) -> { McScreens.open(YaclQueueScreen(this)); return true }
            rectFav.hit(x, y) -> { McScreens.open(YaclFavScreen(this)); return true }
            rectLyrics.hit(x, y) -> { McScreens.open(YaclLyricScreen(this)); return true }
            rectSettings.hit(x, y) -> { NetMusic.openConfigScreen(); return true }
            rectPrev.hit(x, y) -> { player.prev(); return true }
            rectPlay.hit(x, y) -> { player.toggle(); return true }
            rectNext.hit(x, y) -> { player.next(); return true }
            rectMode.hit(x, y) -> { player.cycleMode(); return true }
            rectProgress.hit(x, y) -> {
                // 按下进度条:锁定拖动态(之后即使移出条身仍继续 seek,直到 mouseReleased)
                draggingProgress = true
                lastDragX = x
                dragDurationMs = player.current?.durationMs ?: 0
                seekFrom(x)
                return true
            }
            rectVolume.hit(x, y) -> {
                draggingVolume = true
                volumeFrom(x)
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val x = event.x()
        // 拖动态已锁定:无论指针是否仍在条身上,都按当前 x 持续 seek / 调音量。
        // seek = 停旧会话开新会话,拖动期间每帧调用开销大且易抖动 → 150ms 节流,
        // 但拖动预览(lastDragX)每次事件都更新,时间标签实时跟随。
        if (draggingProgress) {
            lastDragX = x
            val now = System.currentTimeMillis()
            if (now - lastSeekMs >= 150) {
                lastSeekMs = now
                seekFrom(x)
            }
            return true
        }
        if (draggingVolume) { volumeFrom(x); return true }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        // 松手时补一次最终 seek,保证停在精确位置(节流期间可能错过最后一次)
        if (draggingProgress) {
            val x = event.x()
            lastDragX = x
            seekFrom(x)
        }
        draggingProgress = false
        draggingVolume = false
        return super.mouseReleased(event)
    }

    override fun isPauseScreen(): Boolean = false

    /** 拖动进度条时预览的 seek 目标(毫秒);未拖动时返回当前进度 */
    private fun dragPreviewMs(posMs: Int, durMs: Int): Int {
        if (!draggingProgress) return posMs.toInt()
        val bar = rectProgress
        if (bar.x2 <= bar.x1 || durMs <= 0) return posMs.toInt()
        val ratio = ((lastDragX.coerceIn(bar.x1.toDouble(), bar.x2.toDouble()) - bar.x1) / (bar.x2 - bar.x1))
        return (ratio.toFloat() * durMs).toInt().coerceIn(0, durMs)
    }

    private fun seekFrom(x: Double) {
        val song = player.current ?: return
        val bar = rectProgress
        if (bar.x2 <= bar.x1) return
        val ratio = ((x - bar.x1) / (bar.x2 - bar.x1)).toFloat().coerceIn(0f, 1f)
        player.seekTo((ratio * song.durationMs).toInt())
    }

    private fun volumeFrom(x: Double) {
        val bar = rectVolume
        if (bar.x2 <= bar.x1) return
        val v = ((x - bar.x1) / (bar.x2 - bar.x1)).toFloat().coerceIn(0f, 1f)
        player.setVolume(v)
        NetMusic.updateConfig { it.copy(volume = v) }
    }
}
