package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.bilibili.BiliActions
import io.github.cyf112233.musicmc.bilibili.FavFolder
import io.github.cyf112233.musicmc.platform.McScreens
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.model.Song
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 版收藏夹详情页:按页(BiliActions.folderSongs,ps=20)加载收藏夹内视频,
 * 点击行从该位置播放整夹;「加载更多」翻页追加;视觉走 YaclTheme。
 */
class YaclFavDetailScreen(
    private val folder: FavFolder,
    private val back: Screen,
) : Screen(Component.literal("收藏夹")) {

    private val player get() = NetMusic.player

    private val songs = ArrayList<Song>()
    private var page = 0
    private var loading = false
    private var allLoaded = false
    private var error: String? = null
    private var scroll = 0

    private val rectBackBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectPlayAllBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectMoreBtn = YaclTheme.Rect(0, 0, 0, 0)

    override fun init() {
        super.init()
        if (songs.isEmpty() && !loading) loadMore()
    }

    private fun loadMore() {
        if (loading || allLoaded) return
        loading = true
        error = null
        BiliActions.folderSongs(folder.id, page + 1) { list, err ->
            loading = false
            if (err != null) {
                error = err
            } else {
                page++
                songs.addAll(list)
                if (list.size < 20 || songs.size >= folder.mediaCount) allLoaded = true
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
        val title = folder.title.ifBlank { "未命名收藏夹" }
        YaclTheme.drawCenteredTitle(g, title, w / 2, 10)
        rectPlayAllBtn.x1 = w - 96; rectPlayAllBtn.y1 = 10; rectPlayAllBtn.x2 = w - 12; rectPlayAllBtn.y2 = 26
        YaclTheme.drawBtn(g, rectPlayAllBtn, "播放全部", mouseX, mouseY, accent = true)

        if (songs.isEmpty() && loading) {
            g.drawText("加载中…", w / 2 - 40, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        if (error != null && songs.isEmpty()) {
            g.drawText("加载失败:$error", w / 2 - 100, h / 2 - 16, 11f, 1f, YaclTheme.colorError)
            return
        }
        if (songs.isEmpty()) {
            g.drawText("收藏夹暂无内容", w / 2 - 70, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        val rowH = 20
        val listX = 12
        val listW = w - 24
        val currentId = player.current?.id
        var idx = scroll
        var y = 40
        while (idx < songs.size && y + rowH < h - 32) {
            val song = songs[idx]
            YaclTheme.drawSongRow(g, song.title, song.artist, song.id == currentId, listX, y, listW, rowH, mouseX, mouseY)
            y += rowH
            idx++
        }
        // 底部「加载更多」
        if (!allLoaded) {
            rectMoreBtn.x1 = w / 2 - 50; rectMoreBtn.y1 = h - 24
            rectMoreBtn.x2 = w / 2 + 50; rectMoreBtn.y2 = h - 8
            YaclTheme.drawBtn(g, rectMoreBtn, if (loading) "加载中…" else "加载更多", mouseX, mouseY)
        } else if (songs.size > (h - 40) / rowH) {
            YaclTheme.drawScrollHint(g, w, h)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        if (rectBackBtn.hit(x, y)) { McScreens.open(back); return true }
        if (rectPlayAllBtn.hit(x, y)) {
            if (songs.isNotEmpty()) player.play(songs[0], songs.toList(), 0)
            return true
        }
        if (rectMoreBtn.hit(x, y) && !allLoaded) { loadMore(); return true }
        if (songs.isNotEmpty()) {
            val rowH = 20
            val listX = 12
            if (x >= listX && x < listX + 360 && y >= 40) {
                val row = (y - 40).toInt() / rowH + scroll
                if (row in songs.indices) {
                    player.play(songs[row], songs.toList(), row)
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        val rowH = 20
        val maxScroll = (songs.size - (height - 40) / rowH).coerceAtLeast(0)
        scroll = (scroll - dy.toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}
