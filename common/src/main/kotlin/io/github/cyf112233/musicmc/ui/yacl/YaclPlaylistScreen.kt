package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.client.RowCoverCache
import io.github.cyf112233.musicmc.platform.McScreens
import io.github.cyf112233.musicmc.model.Playlist
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.util.Async
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 版Playlist详情页:加载Playlist歌曲(NetMusic.source.playlistDetail),
 * 点击行从该位置播放整单;顶部「播放全部」;视觉走 YaclTheme。
 */
class YaclPlaylistScreen(
    private val playlist: Playlist,
    private val back: Screen,
) : Screen(Component.literal(UiText.t("歌单", "Playlist"))) {

    private val player get() = NetMusic.player

    private var songs: List<Song>? = playlist.songs.takeIf { it.isNotEmpty() }
    private var error: String? = null
    private var scroll = 0

    private val rectBackBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectPlayAllBtn = YaclTheme.Rect(0, 0, 0, 0)

    /** 歌单封面头部卡片高度(列表从该行之下开始) */
    private val listTop: Int get() = 40 + 64 + 12

    override fun init() {
        super.init()
        if (songs == null && error == null) load()
    }

    private fun load() {
        error = null
        NetMusic.source.playlistDetail(playlist.id) { detail, err ->
            // 回调在后台线程(BilibiliSource 直接 executor.execute 回调):切 UI 线程更新
            Async.onUi {
                if (err != null) error = err
                else {
                    songs = detail.songs
                    if (songs.isNullOrEmpty()) songs = emptyList()
                }
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
        val title = playlist.name.ifBlank { UiText.t("未命名歌单", "Unnamed playlist") }
        // 歌单名居中截断:裸居中 drawText 在长标题下会盖住右侧「播放全部」按钮;
        // 左右控件占用约 192px(返回 56 + 播放全部 84 + 间距),其余为标题可用宽
        val titleMaxW = (w - 192).coerceAtLeast(40)
        YaclTheme.drawCenteredClipped(g, title, w / 2, 10, 14f, titleMaxW, YaclTheme.colorTextMain)
        rectPlayAllBtn.x1 = w - 96; rectPlayAllBtn.y1 = 10; rectPlayAllBtn.x2 = w - 12; rectPlayAllBtn.y2 = 26
        YaclTheme.drawBtn(g, rectPlayAllBtn, UiText.t("播放全部", "Play All"), mouseX, mouseY, accent = true)

        // 歌单封面 + 歌曲数(对齐 MUI PlaylistDetailFragment 的封面头部卡片)
        RowCoverCache.pump()
        val headerY = 40
        val coverSize = 64
        val coverX = 12
        val coverUrl = playlist.coverUrl
        val coverId = RowCoverCache.identifier(coverUrl)
        if (coverId != null) {
            YaclTheme.drawCover(g, coverId, coverX, headerY, coverSize)
        } else {
            YaclTheme.drawCoverPlaceholder(g, coverX, headerY, coverSize)
            // 封面未就绪:发起加载(幂等;后续帧 pump 建纹理后自然换上)
            if (coverUrl != null) RowCoverCache.request(coverUrl)
        }
        val countText = if (songs == null) UiText.t("加载中…", "Loading…") else UiText.t("${songs!!.size} 首歌曲", "${songs!!.size} songs")
        YaclTheme.drawTextClipped(g, title, coverX + coverSize + 10, headerY, 13f, w - coverX - coverSize - 110, YaclTheme.colorTextMain)
        YaclTheme.drawTextClipped(g, countText, coverX + coverSize + 10, headerY + 18, 10f, w - coverX - coverSize - 110, YaclTheme.colorTextDim)

        val list = songs
        if (list == null && error == null) {
            g.drawText(UiText.t("加载歌曲中…", "Loading songs…"), w / 2 - 60, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        if (error != null) {
            YaclTheme.drawTextClipped(g, UiText.t("加载失败: $error", "Failed: $error"), w / 2 - 100, h / 2 - 16, 11f, 200, YaclTheme.colorError)
            return
        }
        if (list!!.isEmpty()) {
            g.drawText(UiText.t("歌单暂无歌曲", "No songs in this playlist"), w / 2 - 60, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        RowCoverCache.pump()
        val rowH = 24
        val listX = 12
        val listW = w - 24
        val currentId = player.current?.id
        var idx = scroll
        var y = listTop
        while (idx < list.size && y + rowH < h - 8) {
            val song = list[idx]
            RowCoverCache.request(song.picUrl)
            YaclTheme.drawSongRow(g, song.title, song.artist, song.id == currentId, listX, y, listW, rowH, mouseX, mouseY, song.picUrl, song.durationMs)
            y += rowH
            idx++
        }
        if (list.size > (h - listTop) / rowH) {
            YaclTheme.drawScrollHint(g, w, h)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        if (rectBackBtn.hit(x, y)) { McScreens.open(back); return true }
        val list = songs ?: return super.mouseClicked(event, doubleClick)
        if (rectPlayAllBtn.hit(x, y)) {
            if (list.isNotEmpty()) player.play(list[0], list, 0)
            return true
        }
        if (list.isNotEmpty()) {
            val rowH = 24
            val listX = 12
            val listW = width - 24
            // 上界与绘制一致(绘制止于 h-8),避免空白区映射到未渲染行
            if (x >= listX && x < listX + listW && y >= listTop && y < height - 8) {
                val row = (y - listTop).toInt() / rowH + scroll
                if (row in list.indices) {
                    player.play(list[row], list, row)
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        val rowH = 24
        val maxScroll = ((songs?.size ?: 0) - (height - listTop) / rowH).coerceAtLeast(0)
        scroll = (scroll - dy.toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}
