package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.platform.McScreens
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.model.Playlist
import io.github.cyf112233.musicmc.util.Async
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 版发现页:展示首页推荐Playlist(NetMusic.source.homePlaylists),
 * 点击Playlist进入详情页;视觉走 YaclTheme。
 */
class YaclDiscoverScreen(private val back: Screen) : Screen(Component.literal(UiText.t("发现", "Discover"))) {


    private var playlists: List<Playlist>? = null
    private var error: String? = null
    private var scroll = 0

    private val rectBackBtn = YaclTheme.Rect(0, 0, 0, 0)

    override fun init() {
        super.init()
        if (playlists == null && error == null) load()
    }

    private fun load() {
        error = null
        NetMusic.source.homePlaylists { list, err ->
            // 回调在后台线程(BilibiliSource 直接 executor.execute 回调):切 UI 线程更新
            Async.onUi {
                if (err != null) error = err else playlists = list
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
        YaclTheme.drawCenteredTitle(g, UiText.t("发现", "Discover"), w / 2, 10)

        val list = playlists
        if (list == null && error == null) {
            g.drawText(UiText.t("加载中…", "Loading…"), w / 2 - 60, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        if (error != null) {
            YaclTheme.drawTextClipped(g, UiText.t("加载失败: $error", "Failed: $error"), w / 2 - 100, h / 2 - 16, 11f, 200, YaclTheme.colorError)
            return
        }
        if (list!!.isEmpty()) {
            g.drawText(UiText.t("暂无推荐歌单", "No playlists yet"), w / 2 - 60, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        val rowH = 24
        val listX = 12
        val listW = w - 24
        var idx = scroll
        var y = 40
        while (idx < list.size && y + rowH < h - 8) {
            val p = list[idx]
            YaclTheme.drawListRow(g, p.name.ifBlank { UiText.t("未命名歌单", "Unnamed playlist") }, UiText.t("${p.trackCount} 首", "${p.trackCount} tracks"), listX, y, listW, rowH, mouseX, mouseY)
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
        if (rectBackBtn.hit(x, y)) { McScreens.open(back); return true }
        val list = playlists ?: return super.mouseClicked(event, doubleClick)
        if (list.isNotEmpty()) {
            val rowH = 24
            val listX = 12
            if (x >= listX && x < listX + 360 && y >= 40) {
                val row = (y - 40).toInt() / rowH + scroll
                if (row in list.indices) {
                    McScreens.open(YaclPlaylistScreen(list[row], this))
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        val rowH = 24
        val maxScroll = ((playlists?.size ?: 0) - (height - 40) / rowH).coerceAtLeast(0)
        scroll = (scroll - dy.toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}
