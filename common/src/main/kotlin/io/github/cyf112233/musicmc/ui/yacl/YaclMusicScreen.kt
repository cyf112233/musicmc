package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.client.CoverTextureCache
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.player.PlayerState
import io.github.cyf112233.musicmc.ui.Widgets
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 现代化主界面(uiMode=YACL 或 AUTO 且 Android / 无 ModernUI 时)。
 *
 * 现代化视觉(相对原版):深色渐变背景、主题绿进度、卡片式分组、胶囊按钮、
 * hover 高亮、状态着色;布局与交互语义与原版主界面一致
 * (工具行 / 曲目信息 / 进度条可拖拽 seek / 音量条 / 控制行),功能零回归。
 * 全部子界面导航到 Yacl*Screen,设置走 YACL 配置界面。
 */
class YaclMusicScreen : Screen(Component.literal("MusicMC")) {

    private val player get() = NetMusic.player
    private val config get() = NetMusic.config
    private val mc get() = Minecraft.getInstance()

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
    private val rectSearchEmpty = YaclTheme.Rect(0, 0, 0, 0)

    private var lastSongId: String? = null

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val g = GuiGraphicsHudGui(graphics)
        val w = g.guiWidth()
        val h = g.guiHeight()

        CoverTextureCache.pump()
        val song = player.current
        if (song != null && song.id != lastSongId) {
            lastSongId = song.id
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
        g.fill(panelX - 8, cardY - 8, panelX + panelW + 8, cardY + cardH + 8, YaclTheme.colorCard)
        g.fill(panelX - 8, cardY - 8, panelX + panelW + 8, cardY - 7, YaclTheme.colorCardBorder)
        g.fill(panelX - 8, cardY + cardH + 7, panelX + panelW + 8, cardY + cardH + 8, YaclTheme.colorCardBorder)

        val coverY = cardY
        val coverSize = 96
        val coverId = CoverTextureCache.currentIdentifier()
        if (coverId != null) {
            g.drawTexture(coverId, panelX, coverY, coverSize, coverSize)
        } else {
            g.fill(panelX, coverY, panelX + coverSize, coverY + coverSize, YaclTheme.colorBtn)
            g.drawText("No Cover", panelX + 8, coverY + coverSize / 2 - 5, 10f, 1f, YaclTheme.colorTextDim)
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

        // ---- 进度条 + 时间 ----
        val barY = coverY + coverSize + 26
        val barH = 6
        val barW = panelW
        rectProgress.set(panelX, barY, panelX + barW, barY + barH)
        g.fill(rectProgress.x1, rectProgress.y1, rectProgress.x2, rectProgress.y2, YaclTheme.colorTrack)
        val posMs = player.engine.positionMs()
        val durMs = song?.durationMs ?: 0
        val progress = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
        val fillW = (barW * progress).toInt()
        if (fillW > 0) {
            g.fill(panelX, barY, panelX + fillW, barY + barH, YaclTheme.colorAccent)
            g.fill(panelX, barY, panelX + fillW, barY + 1, YaclTheme.colorAccentBright)
        }
        g.drawText("${Widgets.formatTime(posMs)} / ${Widgets.formatTime(durMs)}", panelX, barY + 10, 10f, 1f, YaclTheme.colorTextSub)

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

        val volX = panelX + 3 * btnW + 3 * gap + modeW + 12
        val volW = panelW - (volX - panelX)
        val volY = ctrlY + (btnH - 4) / 2
        rectVolume.set(volX, volY, volX + volW, volY + 4)
        g.fill(rectVolume.x1, rectVolume.y1, rectVolume.x2, rectVolume.y2, YaclTheme.colorTrack)
        val volF = config.volume.coerceIn(0f, 1f)
        val volFill = (volW * volF).toInt()
        if (volFill > 0) {
            g.fill(volX, volY, volX + volFill, volY + 4, YaclTheme.colorAccent)
            g.fill(volX, volY, volX + volFill, volY + 1, YaclTheme.colorAccentBright)
        }
        g.drawText("音量", volX, ctrlY - 14, 10f, 1f, YaclTheme.colorTextDim)

        // ---- 无歌曲:搜索入口 ----
        if (song == null) {
            val emptyY = ctrlY + 40
            rectSearchEmpty.set(panelX, emptyY, panelX + 110, emptyY + btnH)
            YaclTheme.drawBtn(g, rectSearchEmpty, "搜索歌曲", mouseX, mouseY, accent = true)
        }
    }

    // ---------------- 交互 ----------------

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        when {
            rectClose.hit(x, y) -> { mc.setScreen(null); return true }
            rectSearch.hit(x, y) -> { mc.setScreen(YaclSearchScreen(this)); return true }
            rectDiscover.hit(x, y) -> { mc.setScreen(YaclDiscoverScreen(this)); return true }
            rectQueue.hit(x, y) -> { mc.setScreen(YaclQueueScreen(this)); return true }
            rectFav.hit(x, y) -> { mc.setScreen(YaclFavScreen(this)); return true }
            rectLyrics.hit(x, y) -> { mc.setScreen(YaclLyricScreen(this)); return true }
            rectSettings.hit(x, y) -> { NetMusic.openConfigScreen(); return true }
            rectPrev.hit(x, y) -> { player.prev(); return true }
            rectPlay.hit(x, y) -> { player.toggle(); return true }
            rectNext.hit(x, y) -> { player.next(); return true }
            rectMode.hit(x, y) -> { player.cycleMode(); return true }
            rectProgress.hit(x, y) -> { seekFrom(x); return true }
            rectVolume.hit(x, y) -> { volumeFrom(x); return true }
            rectSearchEmpty.hit(x, y) -> { mc.setScreen(YaclSearchScreen(this)); return true }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val x = event.x()
        val y = event.y()
        if (rectProgress.hit(x, y)) { seekFrom(x); return true }
        if (rectVolume.hit(x, y)) { volumeFrom(x); return true }
        return super.mouseDragged(event, dx, dy)
    }

    override fun isPauseScreen(): Boolean = false

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
