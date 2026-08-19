package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.model.Playlist
import io.github.cyf112233.musicmc.model.Song
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 版歌单详情页:加载歌单歌曲(NetMusic.source.playlistDetail),
 * 点击行从该位置播放整单;顶部「播放全部」;视觉走 YaclTheme。
 */
class YaclPlaylistScreen(
    private val playlist: Playlist,
    private val back: Screen,
) : Screen(Component.literal("歌单")) {

    private val mc get() = Minecraft.getInstance()
    private val player get() = NetMusic.player

    private var songs: List<Song>? = playlist.songs.takeIf { it.isNotEmpty() }
    private var error: String? = null
    private var scroll = 0

    private val rectBackBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectPlayAllBtn = YaclTheme.Rect(0, 0, 0, 0)

    override fun init() {
        super.init()
        if (songs == null && error == null) load()
    }

    private fun load() {
        error = null
        NetMusic.source.playlistDetail(playlist.id) { detail, err ->
            if (err != null) error = err
            else {
                songs = detail.songs
                if (songs.isNullOrEmpty()) songs = emptyList()
            }
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val g = GuiGraphicsHudGui(graphics)
        val w = g.guiWidth()
        val h = g.guiHeight()

        YaclTheme.drawBackground(g, w, h)

        rectBackBtn.x1 = 12; rectBackBtn.y1 = 10; rectBackBtn.x2 = 56; rectBackBtn.y2 = 26
        YaclTheme.drawBtn(g, rectBackBtn, "< 返回", mouseX, mouseY)
        val title = playlist.name.ifBlank { "未命名歌单" }
        YaclTheme.drawCenteredTitle(g, title, w / 2, 10)
        rectPlayAllBtn.x1 = w - 96; rectPlayAllBtn.y1 = 10; rectPlayAllBtn.x2 = w - 12; rectPlayAllBtn.y2 = 26
        YaclTheme.drawBtn(g, rectPlayAllBtn, "播放全部", mouseX, mouseY, accent = true)

        val list = songs
        if (list == null && error == null) {
            g.drawText("加载歌曲中…", w / 2 - 60, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        if (error != null) {
            g.drawText("加载失败:$error", w / 2 - 100, h / 2 - 16, 11f, 1f, YaclTheme.colorError)
            return
        }
        if (list!!.isEmpty()) {
            g.drawText("歌单暂无歌曲", w / 2 - 60, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        val rowH = 20
        val listX = 12
        val listW = w - 24
        val currentId = player.current?.id
        var idx = scroll
        var y = 40
        while (idx < list.size && y + rowH < h - 8) {
            val song = list[idx]
            YaclTheme.drawSongRow(g, song.title, song.artist, song.id == currentId, listX, y, listW, rowH, mouseX, mouseY)
            y += rowH
            idx++
        }
        if (list.size > (h - 40) / rowH) {
            YaclTheme.drawScrollHint(g, w, h)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        if (rectBackBtn.hit(x, y)) { mc.setScreen(back); return true }
        val list = songs ?: return super.mouseClicked(event, doubleClick)
        if (rectPlayAllBtn.hit(x, y)) {
            if (list.isNotEmpty()) player.play(list[0], list, 0)
            return true
        }
        if (list.isNotEmpty()) {
            val rowH = 20
            val listX = 12
            if (x >= listX && x < listX + 360 && y >= 40) {
                val row = (y - 40).toInt() / rowH + scroll
                if (row in list.indices) {
                    player.play(list[row], list, row)
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        val rowH = 20
        val maxScroll = ((songs?.size ?: 0) - (height - 40) / rowH).coerceAtLeast(0)
        scroll = (scroll - dy.toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}
