package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.bilibili.BiliActions
import io.github.cyf112233.musicmc.bilibili.FavFolder
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 版收藏页:展示 B 站收藏夹列表(BiliActions.folders)。
 * 未登录时提示并给出「扫码登录」入口;点击收藏夹进入详情页;视觉走 YaclTheme。
 */
class YaclFavScreen(private val back: Screen) : Screen(Component.literal("收藏")) {

    private val mc get() = Minecraft.getInstance()

    private var folders: List<FavFolder>? = null
    private var error: String? = null
    private var scroll = 0

    private val rectBackBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectLoginBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectRefreshBtn = YaclTheme.Rect(0, 0, 0, 0)

    override fun init() {
        super.init()
        if (NetMusic.bilibiliLoggedIn() && folders == null && error == null) load()
    }

    private fun load() {
        error = null
        BiliActions.folders { list, err ->
            if (err != null) error = err else folders = list
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val g = GuiGraphicsHudGui(graphics)
        val w = g.guiWidth()
        val h = g.guiHeight()

        YaclTheme.drawBackground(g, w, h)

        rectBackBtn.x1 = 12; rectBackBtn.y1 = 10; rectBackBtn.x2 = 56; rectBackBtn.y2 = 26
        YaclTheme.drawBtn(g, rectBackBtn, "< 返回", mouseX, mouseY)
        YaclTheme.drawCenteredTitle(g, "收藏", w / 2, 10)

        if (!NetMusic.bilibiliLoggedIn()) {
            g.drawText("未登录 B 站,登录后可查看收藏夹", w / 2 - 140, h / 2 - 30, 12f, 1f, YaclTheme.colorTextSub)
            rectLoginBtn.x1 = w / 2 - 70; rectLoginBtn.y1 = h / 2 - 10
            rectLoginBtn.x2 = w / 2 + 70; rectLoginBtn.y2 = h / 2 + 14
            YaclTheme.drawBtn(g, rectLoginBtn, "扫码登录", mouseX, mouseY, accent = true)
            return
        }

        val nick = NetMusic.bilibiliNickname()
        if (nick != null) g.drawText("已登录:$nick", w / 2 + 40, 12, 9f, 1f, 0xFF88AA88.toInt())

        val list = folders
        if (list == null && error == null) {
            g.drawText("加载收藏夹中…", w / 2 - 70, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        if (error != null) {
            g.drawText("加载失败:$error", w / 2 - 100, h / 2 - 16, 11f, 1f, YaclTheme.colorError)
            rectRefreshBtn.x1 = w / 2 - 40; rectRefreshBtn.y1 = h / 2 + 2
            rectRefreshBtn.x2 = w / 2 + 40; rectRefreshBtn.y2 = h / 2 + 26
            YaclTheme.drawBtn(g, rectRefreshBtn, "重试", mouseX, mouseY)
            return
        }
        if (list!!.isEmpty()) {
            g.drawText("暂无收藏夹", w / 2 - 50, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        val rowH = 24
        val listX = 12
        val listW = w - 24
        var idx = scroll
        var y = 40
        while (idx < list.size && y + rowH < h - 8) {
            val f = list[idx]
            YaclTheme.drawListRow(g, f.title.ifBlank { "未命名收藏夹" }, "${f.mediaCount} 个视频", listX, y, listW, rowH, mouseX, mouseY)
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
        if (rectLoginBtn.hit(x, y)) { mc.setScreen(YaclLoginScreen(this)); return true }
        if (rectRefreshBtn.hit(x, y)) { load(); return true }
        val list = folders ?: return super.mouseClicked(event, doubleClick)
        if (list.isNotEmpty()) {
            val rowH = 24
            val listX = 12
            if (x >= listX && x < listX + 360 && y >= 40) {
                val row = (y - 40).toInt() / rowH + scroll
                if (row in list.indices) {
                    mc.setScreen(YaclFavDetailScreen(list[row], this))
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        val rowH = 24
        val maxScroll = ((folders?.size ?: 0) - (height - 40) / rowH).coerceAtLeast(0)
        scroll = (scroll - dy.toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}
