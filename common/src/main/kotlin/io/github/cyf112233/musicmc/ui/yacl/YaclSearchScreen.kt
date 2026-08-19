package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.model.Song
import net.minecraft.client.Minecraft
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
class YaclSearchScreen(private val back: Screen) : Screen(Component.literal("搜索")) {

    private val mc get() = Minecraft.getInstance()
    private val results = ArrayList<Song>()
    private var error: String? = null
    private var searching = false
    private var scroll = 0

    private var editBox: EditBox? = null

    private val rectSearchBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectBackBtn = YaclTheme.Rect(0, 0, 0, 0)

    override fun init() {
        super.init()
        val box = EditBox(font, width / 2 - 180, 20, 300, 16, Component.literal("搜索歌曲"))
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
        YaclTheme.drawBtn(g, rectBackBtn, "< 返回", mouseX, mouseY)

        // 输入框(MC 原生渲染)
        editBox?.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick)

        // 搜索按钮
        rectSearchBtn.x1 = width / 2 + 126; rectSearchBtn.y1 = 20
        rectSearchBtn.x2 = width / 2 + 126 + 56; rectSearchBtn.y2 = 36
        YaclTheme.drawBtn(g, rectSearchBtn, if (searching) "…" else "搜索", mouseX, mouseY, accent = true)

        // 状态 / 错误
        var listY = 48
        if (error != null) {
            g.drawText("搜索失败:$error", width / 2 - 180, listY, 11f, 1f, YaclTheme.colorError)
            listY += 16
        } else if (results.isEmpty() && !searching) {
            g.drawText("输入关键词后点「搜索」,或按回车", width / 2 - 180, listY, 11f, 1f, YaclTheme.colorTextDim)
            listY += 16
        }

        // 结果列表(滚动)
        val rowH = 20
        val listW = 360
        val listX = width / 2 - 180
        var idx = scroll
        var y = listY
        while (idx < results.size && y + rowH < h - 8) {
            val song = results[idx]
            YaclTheme.drawSongRow(g, song.title, song.artist, false, listX, y, listW, rowH, mouseX, mouseY)
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
        if (rectBackBtn.hit(x, y)) { mc.setScreen(back); return true }
        if (rectSearchBtn.hit(x, y)) { doSearch(); return true }
        // 结果行点击 → 播放并返回主界面
        val listY = 48
        val rowH = 20
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
        val rowH = 20
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
        mc.setScreen(YaclMusicScreen())
    }

    private fun doSearch() {
        val keyword = editBox?.getValue()?.trim().orEmpty()
        if (keyword.isEmpty() || searching) return
        searching = true
        error = null
        scroll = 0
        NetMusic.source.search(keyword, 30, 0) { list, err ->
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
