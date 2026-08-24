package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.client.RowCoverCache
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.platform.McScreens
import io.github.cyf112233.musicmc.ui.Widgets
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 版队列页:展示当前播放队列(NetMusic.player.queue),
 * 点击行从该位置播放,支持切换循环模式与清空队列;视觉走 YaclTheme。
 */
class YaclQueueScreen(private val back: Screen) : Screen(Component.literal(UiText.t("播放队列", "Queue"))) {

    private val player get() = NetMusic.player

    private var scroll = 0

    private val rectBackBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectModeBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectClearBtn = YaclTheme.Rect(0, 0, 0, 0)

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val g = GuiGraphicsHudGui(graphics)
        val w = g.guiWidth()
        val h = g.guiHeight()

        YaclTheme.drawBackground(g, w, h)

        // 顶部:返回 + 标题 + 模式 + 清空
        rectBackBtn.x1 = 12; rectBackBtn.y1 = 10; rectBackBtn.x2 = 56; rectBackBtn.y2 = 26
        YaclTheme.drawBtn(g, rectBackBtn, UiText.t("< 返回", "< Back"), mouseX, mouseY)
        YaclTheme.drawCenteredTitle(g, UiText.t("播放队列", "Queue"), w / 2, 10)
        val modeLabel = Widgets.playModeLabel(player.mode)
        rectModeBtn.x1 = w - 150; rectModeBtn.y1 = 10; rectModeBtn.x2 = w - 90; rectModeBtn.y2 = 26
        YaclTheme.drawBtn(g, rectModeBtn, modeLabel, mouseX, mouseY)
        rectClearBtn.x1 = w - 82; rectClearBtn.y1 = 10; rectClearBtn.x2 = w - 12; rectClearBtn.y2 = 26
        YaclTheme.drawBtn(g, rectClearBtn, UiText.t("清空", "Clear"), mouseX, mouseY)

        RowCoverCache.pump()
        // 列表
        val queue = player.queue
        if (queue.isEmpty()) {
            g.drawText(UiText.t("队列为空,去搜索页点一首歌即可加入", "Queue is empty. Pick a song from Search to add it"), w / 2 - 130, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        val rowH = 24
        val listX = 12
        val listW = w - 24
        var idx = scroll
        var y = 40
        val currentId = player.current?.id
        while (idx < queue.size && y + rowH < h - 8) {
            val song = queue[idx]
            RowCoverCache.request(song.picUrl)
            YaclTheme.drawSongRow(g, song.title, song.artist, song.id == currentId, listX, y, listW, rowH, mouseX, mouseY, song.picUrl, song.durationMs)
            y += rowH
            idx++
        }
        if (queue.size > (h - 40) / rowH) {
            YaclTheme.drawScrollHint(g, w, h)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        if (rectBackBtn.hit(x, y)) { McScreens.open(back); return true }
        if (rectModeBtn.hit(x, y)) {
            player.cycleMode()
            // 播放模式持久化(对齐 MUI 的 cycleMode + saveConfig 写法)
            NetMusic.saveConfig()
            return true
        }
        if (rectClearBtn.hit(x, y)) { player.clearQueue(); return true }
        val queue = player.queue
        if (queue.isNotEmpty()) {
            val rowH = 24
            val listX = 12
            val listW = width - 24
            // 上界与绘制一致(绘制止于 h-8),避免空白区映射到未渲染行
            if (x >= listX && x < listX + listW && y >= 40 && y < height - 8) {
                val row = (y - 40).toInt() / rowH + scroll
                if (row in queue.indices) {
                    player.play(queue[row], queue.toList(), row)
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        val rowH = 24
        val maxScroll = (player.queue.size - (height - 40) / rowH).coerceAtLeast(0)
        scroll = (scroll - dy.toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}
