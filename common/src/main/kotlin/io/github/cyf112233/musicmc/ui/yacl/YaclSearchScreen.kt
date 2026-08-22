package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.client.RowCoverCache
import io.github.cyf112233.musicmc.platform.McScreens
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.util.Async
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 版搜索页:输入关键词搜索 B 站歌曲,点击结果播放并返回主界面。
 * 输入框用 MC 原生 EditBox(IME / 光标 / 粘贴支持与原版聊天一致);视觉走 YaclTheme。
 */
class YaclSearchScreen(private val back: Screen) : Screen(Component.literal(UiText.t("搜索", "Search"))) {

    private val results = ArrayList<Song>()
    private var error: String? = null
    private var searching = false
    private var scroll = 0

    /** 搜索请求序号:每次 doSearch 自增,过期请求的结果直接丢弃(先搜 "a" 再搜 "ab",
     *  "a" 结果后到时不得覆盖新结果) */
    private var searchGen = 0

    private var editBox: EditBox? = null

    private val rectSearchBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectBackBtn = YaclTheme.Rect(0, 0, 0, 0)

    override fun init() {
        super.init()
        val box = EditBox(font, width / 2 - 180, 20, 300, 16, Component.literal(UiText.t("搜索歌曲", "Search Songs")))
        box.setMaxLength(60)
        editBox = box
        addWidget(box)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val g = GuiGraphicsHudGui(graphics)
        val w = g.guiWidth()
        val h = g.guiHeight()

        YaclTheme.drawBackground(g, w, h)

        rectBackBtn.x1 = 12; rectBackBtn.y1 = 10; rectBackBtn.x2 = 56; rectBackBtn.y2 = 26
        YaclTheme.drawBtn(g, rectBackBtn, UiText.t("< 返回", "< Back"), mouseX, mouseY)

        // 输入框(MC 原生渲染)
        editBox?.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick)

        // 搜索按钮
        rectSearchBtn.x1 = width / 2 + 126; rectSearchBtn.y1 = 20
        rectSearchBtn.x2 = width / 2 + 126 + 56; rectSearchBtn.y2 = 36
        YaclTheme.drawBtn(g, rectSearchBtn, if (searching) "…" else UiText.t("搜索", "Search"), mouseX, mouseY, accent = true)

        // 状态 / 错误
        var listY = 48
        if (error != null) {
            YaclTheme.drawTextClipped(g, UiText.t("搜索失败: $error", "Search failed: $error"), width / 2 - 180, listY, 11f, 360, YaclTheme.colorError)
            listY += 16
        } else if (results.isEmpty() && !searching) {
            YaclTheme.drawTextClipped(g, UiText.t("输入关键词,点搜索或回车", "Type a keyword and press Search or Enter"), width / 2 - 180, listY, 11f, 360, YaclTheme.colorTextDim)
            listY += 16
        }

        // 结果列表(滚动;行首缩略封面)
        RowCoverCache.pump()
        val rowH = 24
        val listW = 360
        val listX = width / 2 - 180
        var idx = scroll
        var y = listY
        while (idx < results.size && y + rowH < h - 8) {
            val song = results[idx]
            RowCoverCache.request(song.picUrl)
            YaclTheme.drawSongRow(g, song.title, song.artist, false, listX, y, listW, rowH, mouseX, mouseY, song.picUrl)
            y += rowH
            idx++
        }
        if (results.size > (h - listY) / rowH) {
            YaclTheme.drawScrollHint(g, w, h)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        if (rectBackBtn.hit(x, y)) { McScreens.open(back); return true }
        if (rectSearchBtn.hit(x, y)) { doSearch(); return true }
        // 结果行点击 → 播放并返回主界面
        val listY = 48
        val rowH = 24
        val listX = width / 2 - 180
        if (x >= listX && x < listX + 360 && y >= listY) {
            val row = (y - listY).toInt() / rowH + scroll
            if (row in results.indices) {
                playAndBack(results[row])
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        val rowH = 24
        val maxScroll = (results.size - (height - 48) / rowH).coerceAtLeast(0)
        scroll = (scroll - dy.toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            doSearch()
            return true
        }
        val box = editBox
        if (box != null && box.isFocused) {
            if (box.keyPressed(event)) return true
        }
        return super.keyPressed(event)
    }

    override fun isPauseScreen(): Boolean = false

    private fun playAndBack(song: Song) {
        NetMusic.player.play(song)
        McScreens.open(YaclMusicScreen())
    }

    private fun doSearch() {
        val keyword = editBox?.getValue()?.trim().orEmpty()
        if (keyword.isEmpty() || searching) return
        val gen = ++searchGen
        searching = true
        error = null
        scroll = 0
        NetMusic.source.search(keyword, 30, 0) { list, err ->
            // 回调在后台线程(BilibiliSource 直接 executor.execute 回调):
            // 切回 UI 线程再更新界面字段(否则与渲染线程的读取构成数据竞争)
            Async.onUi {
                if (gen != searchGen) return@onUi // 过期请求(又发了新搜索)丢弃
                searching = false
                if (err != null) {
                    error = err
                } else {
                    results.clear()
                    results.addAll(list)
                }
            }
        }
    }
}
