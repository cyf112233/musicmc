package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.platform.McScreens
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.model.LyricLine
import io.github.cyf112233.musicmc.util.Lrc
import io.github.cyf112233.musicmc.ui.Widgets
import io.github.cyf112233.musicmc.util.Async
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 版Lyrics页:显示当前歌曲Lyrics,当前行高亮,自动跟随播放进度。
 * 手动滚动后暂停跟随,点「跟随」重新开启;视觉走 YaclTheme。
 */
class YaclLyricScreen(private val back: Screen) : Screen(Component.literal(UiText.t("歌词", "Lyrics"))) {

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
        YaclTheme.drawBtn(g, rectBackBtn, UiText.t("< 返回", "< Back"), mouseX, mouseY)

        // 歌名 + 跟随开关
        val song = player.current
        val songTitle = song?.title?.ifBlank { UiText.t("未知标题", "Unknown") } ?: UiText.t("未在播放", "Not Playing")
        YaclTheme.drawCenteredTitle(g, songTitle, w / 2, 12)

        rectFollowBtn.x1 = w / 2 + 120; rectFollowBtn.y1 = 10
        rectFollowBtn.x2 = w / 2 + 120 + 48; rectFollowBtn.y2 = 26
        YaclTheme.drawBtn(g, rectFollowBtn, if (autoFollow) UiText.t("跟随:开", "Follow: On") else UiText.t("跟随:关", "Follow: Off"), mouseX, mouseY)

        // Lyrics加载
        if (song == null) {
            g.drawText(UiText.t("未在播放", "Not Playing"), w / 2 - 160, 40, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        if (song.id != lastSongId) {
            lastSongId = song.id
            lines = emptyList()
            loaded = false
            error = null
            scroll = 0
            autoFollow = true
            val reqSongId = song.id
            NetMusic.source.lyric(reqSongId) { list, err ->
                // 回调在后台线程(BilibiliSource 直接 executor.execute 回调):
                // 切 UI 线程更新;并校验请求时的歌曲 id —— 快速切歌后旧请求晚到
                // 不得覆盖新歌的歌词(否则显示与当前歌不匹配的歌词直到再次切歌)
                Async.onUi {
                    if (reqSongId != player.current?.id) return@onUi
                    if (err != null) {
                        error = err
                        loaded = true
                    } else {
                        lines = list
                        loaded = true
                    }
                }
            }
        }
        if (!loaded) {
            g.drawText(UiText.t("歌词加载中…", "Loading lyrics…"), w / 2 - 160, 40, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        if (error != null) {
            YaclTheme.drawTextClipped(g, UiText.t("歌词加载失败: $error", "Failed to load lyrics: $error"), w / 2 - 100, 40, 11f, 200, YaclTheme.colorError)
            return
        }
        if (lines.isEmpty()) {
            g.drawText(UiText.t("暂无歌词", "No lyrics"), w / 2 - 160, 40, 12f, 1f, YaclTheme.colorTextDim)
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

        // Lyrics列表
        val listX = w / 2 - 160
        val textMaxW = 318
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
                g.fill(listX - 6, y, listX + textMaxW, y + rowH, YaclTheme.colorRowCurrent)
                g.fill(listX - 6, y, listX - 3, y + rowH, YaclTheme.colorAccent)
            }
            YaclTheme.drawTextClipped(g, line.text, listX, y + 2, if (isCurrent) 12f else 11f, textMaxW, color)
            // 时间列:右对齐到列表右端(旧实现左对齐锚在 listX+318 且宽度 42,
            // 会画出 320 边界与歌词文本重叠)
            val timeText = Widgets.formatTime(line.timeMs)
            val tw = g.textWidth(timeText).toInt()
            g.drawText(timeText, listX + textMaxW - tw, y + 5, 8f, 1f, YaclTheme.colorTextFaint)
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
        // 无歌词时不吞滚轮事件(页面无可滚动内容,交给上级处理)
        if (lines.isEmpty()) return false
        autoFollow = false
        scroll = (scroll - dy.toInt()).coerceAtLeast(0)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}
