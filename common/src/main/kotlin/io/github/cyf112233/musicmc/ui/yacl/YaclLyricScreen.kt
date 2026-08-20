package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.platform.McScreens
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.model.LyricLine
import io.github.cyf112233.musicmc.util.Lrc
import io.github.cyf112233.musicmc.ui.Widgets
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 版歌词页:显示当前歌曲歌词,当前行高亮,自动跟随播放进度。
 * 手动滚动后暂停跟随,点「跟随」重新开启;视觉走 YaclTheme。
 */
class YaclLyricScreen(private val back: Screen) : Screen(Component.literal("歌词")) {

    private val player get() = NetMusic.player

    private var lines: List<LyricLine> = emptyList()
    private var loaded = false
    private var error: String? = null
    private var scroll = 0
    private var autoFollow = true
    private var lastSongId: String? = null

    private val rectBackBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectFollowBtn = YaclTheme.Rect(0, 0, 0, 0)

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val g = GuiGraphicsHudGui(graphics)
        val w = g.guiWidth()
        val h = g.guiHeight()

        YaclTheme.drawBackground(g, w, h)

        rectBackBtn.x1 = 12; rectBackBtn.y1 = 10; rectBackBtn.x2 = 56; rectBackBtn.y2 = 26
        YaclTheme.drawBtn(g, rectBackBtn, "< 返回", mouseX, mouseY)

        // 歌名 + 跟随开关
        val song = player.current
        val songTitle = song?.title?.ifBlank { "未知标题" } ?: "未在播放"
        YaclTheme.drawCenteredTitle(g, songTitle, w / 2, 12)

        rectFollowBtn.x1 = w / 2 + 120; rectFollowBtn.y1 = 10
        rectFollowBtn.x2 = w / 2 + 120 + 48; rectFollowBtn.y2 = 26
        YaclTheme.drawBtn(g, rectFollowBtn, if (autoFollow) "跟随:开" else "跟随:关", mouseX, mouseY)

        // 歌词加载
        if (song == null) {
            g.drawText("未在播放", w / 2 - 160, 40, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        if (song.id != lastSongId) {
            lastSongId = song.id
            lines = emptyList()
            loaded = false
            error = null
            scroll = 0
            autoFollow = true
            NetMusic.source.lyric(song.id) { list, err ->
                if (err != null) {
                    error = err
                    loaded = true
                } else {
                    lines = list
                    loaded = true
                }
            }
        }
        if (!loaded) {
            g.drawText("歌词加载中…", w / 2 - 160, 40, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        if (error != null) {
            g.drawText("歌词加载失败:$error", w / 2 - 160, 40, 11f, 1f, YaclTheme.colorError)
            return
        }
        if (lines.isEmpty()) {
            g.drawText("暂无歌词", w / 2 - 160, 40, 12f, 1f, YaclTheme.colorTextDim)
            return
        }

        // 当前行(自动跟随)
        val posMs = player.engine.positionMs()
        val currentIndex = Lrc.findLineIndex(lines, posMs)
        val rowH = 18
        val listTop = 44
        val visibleRows = (h - listTop - 8) / rowH
        if (autoFollow) {
            scroll = (currentIndex - visibleRows / 3).coerceAtLeast(0)
        }
        val maxScroll = (lines.size - visibleRows).coerceAtLeast(0)
        scroll = scroll.coerceIn(0, maxScroll)

        // 歌词列表
        val listX = w / 2 - 160
        var idx = scroll
        var y = listTop
        while (idx < lines.size && y + rowH < h - 8) {
            val line = lines[idx]
            val isCurrent = idx == currentIndex
            val color = when {
                isCurrent -> YaclTheme.colorTextMain
                idx < currentIndex -> YaclTheme.colorTextSub
                else -> YaclTheme.colorTextFaint
            }
            if (isCurrent) {
                g.fill(listX - 6, y, listX + 320, y + rowH, YaclTheme.colorRowCurrent)
                g.fill(listX - 6, y, listX - 3, y + rowH, YaclTheme.colorAccent)
            }
            g.drawText(line.text, listX, y + 2, if (isCurrent) 12f else 11f, 1f, color)
            g.drawText(Widgets.formatTime(line.timeMs), listX + 318, y + 5, 8f, 1f, YaclTheme.colorTextFaint)
            y += rowH
            idx++
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        if (rectBackBtn.hit(x, y)) { McScreens.open(back); return true }
        if (rectFollowBtn.hit(x, y)) { autoFollow = !autoFollow; return true }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        if (lines.isEmpty()) return true
        autoFollow = false
        scroll = (scroll - dy.toInt()).coerceAtLeast(0)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}
