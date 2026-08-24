package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.bilibili.BiliActions
import io.github.cyf112233.musicmc.bilibili.FavFolder
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.client.RowCoverCache
import io.github.cyf112233.musicmc.platform.McScreens
import io.github.cyf112233.musicmc.model.Song
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 版Favorites详情页:按页(BiliActions.folderSongs,ps=20)加载Favorites内视频,
 * 点击行从该位置播放整夹;「加载更多」翻页追加;视觉走 YaclTheme。
 */
class YaclFavDetailScreen(
    private val folder: FavFolder,
    private val back: Screen,
) : Screen(Component.literal(UiText.t("收藏", "Favorites"))) {

    private val player get() = NetMusic.player

    private val songs = ArrayList<Song>()
    private var page = 0
    private var loading = false
    private var allLoaded = false
    private var error: String? = null
    private var scroll = 0

    /** 每页拉取数量(与 BiliActions.folderSongs 的 ps 一致) */
    private val pageSize = 20
    /** 翻页上限(防御 mediaCount 误报导致无限拉取) */
    private val maxPages = 20

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
                // 达页数上限或已拉满媒体数量,或本页不满一页 → 判定全部加载完成
                if (page >= maxPages || list.size < pageSize || songs.size >= folder.mediaCount) allLoaded = true
            }
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val g = GuiGraphicsHudGui(graphics)
        val w = g.guiWidth()
        val h = g.guiHeight()

        YaclTheme.drawBackground(g, w, h)

        rectBackBtn.x1 = 12; rectBackBtn.y1 = 10; rectBackBtn.x2 = 56; rectBackBtn.y2 = 26
        YaclTheme.drawBtn(g, rectBackBtn, UiText.t("< 返回", "< Back"), mouseX, mouseY)
        val title = folder.title.ifBlank { UiText.t("未命名收藏夹", "Unnamed folder") }
        // 收藏夹名居中截断:裸居中 drawText 在长标题下会盖住右侧「播放全部」按钮
        val titleMaxW = (w - 192).coerceAtLeast(40)
        YaclTheme.drawCenteredClipped(g, title, w / 2, 10, 14f, titleMaxW, YaclTheme.colorTextMain)
        // 媒体数量副标题(对齐 MUI FavFolderDetailFragment 的"N 个内容")
        YaclTheme.drawCenteredClipped(g, UiText.t("${folder.mediaCount} 个内容", "${folder.mediaCount} items"), w / 2, 26, 9f, titleMaxW, YaclTheme.colorTextDim)
        rectPlayAllBtn.x1 = w - 96; rectPlayAllBtn.y1 = 10; rectPlayAllBtn.x2 = w - 12; rectPlayAllBtn.y2 = 26
        YaclTheme.drawBtn(g, rectPlayAllBtn, UiText.t("播放全部", "Play All"), mouseX, mouseY, accent = true)

        if (songs.isEmpty() && loading) {
            g.drawText(UiText.t("加载中…", "Loading…"), w / 2 - 40, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        if (error != null && songs.isEmpty()) {
            YaclTheme.drawTextClipped(g, UiText.t("加载失败: $error", "Failed: $error"), w / 2 - 100, h / 2 - 16, 11f, 200, YaclTheme.colorError)
            return
        }
        if (songs.isEmpty()) {
            g.drawText(UiText.t("收藏夹暂无内容", "This folder is empty"), w / 2 - 70, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
RowCoverCache.pump()
        val rowH = 24
        val listX = 12
        val listW = w - 24
        val currentId = player.current?.id
        var idx = scroll
        var y = 40
        while (idx < songs.size && y + rowH < h - 32) {
            val song = songs[idx]
            RowCoverCache.request(song.picUrl)
            YaclTheme.drawSongRow(g, song.title, song.artist, song.id == currentId, listX, y, listW, rowH, mouseX, mouseY, song.picUrl, song.durationMs)
            y += rowH
            idx++
        }
        // 底部「加载更多」
        if (!allLoaded) {
            rectMoreBtn.x1 = w / 2 - 50; rectMoreBtn.y1 = h - 24
            rectMoreBtn.x2 = w / 2 + 50; rectMoreBtn.y2 = h - 8
            YaclTheme.drawBtn(g, rectMoreBtn, if (loading) UiText.t("加载中…", "Loading…") else UiText.t("加载更多", "Load More"), mouseX, mouseY)
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
            val rowH = 24
            val listX = 12
            val listW = width - 24
            // 行映射上界与绘制一致(绘制止于 h-32 给"加载更多"留位):
            // 不加 y < height - 32 上界,allLoaded 后点击列表底部空白区会映射到
            // 屏幕外行(视觉上"点了没反应但吞了事件")
            if (x >= listX && x < listX + listW && y >= 40 && y < height - 32) {
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
        val rowH = 24
        val maxScroll = (songs.size - (height - 40) / rowH).coerceAtLeast(0)
        scroll = (scroll - dy.toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}
