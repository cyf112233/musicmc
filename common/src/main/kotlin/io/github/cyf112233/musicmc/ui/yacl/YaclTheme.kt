package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui

/**
 * YACL 版界面共享主题:配色与绘制辅助。
 * 现代化风格:深色渐变背景、卡片式分组、胶囊按钮、主题绿高亮、hover 反馈。
 * 所有 Yacl*Screen 复用本主题,保证视觉统一。
 */
object YaclTheme {

    // ---- 配色 ----
    val colorBgTop = 0xFF1B1F2A.toInt()
    val colorBgBottom = 0xFF12141C.toInt()
    val colorCard = 0xFF1F2533.toInt()
    val colorCardBorder = 0xFF2C3444.toInt()
    val colorBtn = 0xFF262D3D.toInt()
    val colorBtnHover = 0xFF323B4F.toInt()
    val colorAccent = 0xFF4CAF50.toInt()
    val colorAccentBright = 0xFF66BB6A.toInt()
    val colorAccentDark = 0xFF43A047.toInt()
    val colorTextMain = 0xFFFFFFFF.toInt()
    val colorTextSub = 0xFFB8BFCC.toInt()
    val colorTextDim = 0xFF7A8295.toInt()
    val colorTextFaint = 0xFF5A6275.toInt()
    val colorTrack = 0xFF333B4D.toInt()
    val colorRowHover = 0xFF262D3D.toInt()
    val colorRowCurrent = 0xFF1E3326.toInt()
    val colorError = 0xFFFF5252.toInt()
    val colorWarn = 0xFFFFB74D.toInt()

    /** 交互矩形(各界面复用;坐标每帧刷新,点击判定用) */
    open class Rect(var x1: Int, var y1: Int, var x2: Int, var y2: Int) {
        fun set(x1: Int, y1: Int, x2: Int, y2: Int) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2
        }

        fun hit(px: Double, py: Double): Boolean = px >= x1 && px < x2 && py >= y1 && py < y2
    }

    // ---- 绘制辅助 ----

    /** 全屏深色渐变背景 */
    fun drawBackground(g: GuiGraphicsHudGui, w: Int, h: Int) {
        g.fillGradient(0, 0, w, h, colorBgTop, colorBgBottom)
    }

    /** 顶部工具按钮(胶囊形状) */
    fun drawPill(g: GuiGraphicsHudGui, r: Rect, label: String, mouseX: Int, mouseY: Int) {
        val hover = mouseX in r.x1 until r.x2 && mouseY in r.y1 until r.y2
        val bg = if (hover) colorBtnHover else colorBtn
        val h2 = (r.y2 - r.y1) / 2
        g.fill(r.x1 + h2, r.y1, r.x2 - h2, r.y2, bg)
        g.fill(r.x1, r.y1 + 1, r.x1 + h2, r.y2 - 1, bg)
        g.fill(r.x2 - h2, r.y1 + 1, r.x2, r.y2 - 1, bg)
        g.fill(r.x1, r.y1, r.x1 + h2, r.y1 + 1, colorCardBorder)
        g.fill(r.x2 - h2, r.y2 - 1, r.x2, r.y2, colorCardBorder)
        g.drawText(label, r.x1 + 4, r.y1 + 2, 10f, 1f, if (hover) colorTextMain else colorTextSub)
    }

    /** 普通按钮(圆角近似 + hover + 主题色文字;[accent] 为主题色实底按钮) */
    fun drawBtn(g: GuiGraphicsHudGui, r: Rect, label: String, mouseX: Int, mouseY: Int, accent: Boolean = false) {
        val hover = mouseX in r.x1 until r.x2 && mouseY in r.y1 until r.y2
        val bg = when {
            accent -> if (hover) colorAccentDark else colorAccent
            hover -> colorBtnHover
            else -> colorBtn
        }
        val h2 = (r.y2 - r.y1) / 2
        g.fill(r.x1 + h2, r.y1, r.x2 - h2, r.y2, bg)
        g.fill(r.x1, r.y1 + 1, r.x1 + h2, r.y2 - 1, bg)
        g.fill(r.x2 - h2, r.y1 + 1, r.x2, r.y2 - 1, bg)
        g.fill(r.x1, r.y1, r.x2, r.y1 + 1, colorCardBorder)
        g.fill(r.x1, r.y2 - 1, r.x2, r.y2, colorCardBorder)
        val textColor = if (accent) colorTextMain else if (hover) colorAccentBright else colorTextSub
        g.drawText(label, r.x1 + 6, r.y1 + 4, 11f, 1f, textColor)
    }

    /** 居中小标题(按 [sizePx] 字号,以 [centerX] 为水平中心) */
    fun drawCenteredTitle(g: GuiGraphicsHudGui, text: String, centerX: Int, y: Int, sizePx: Float = 14f) {
        val tw = (g.textWidth(text).toFloat() * (sizePx / 12f)).toInt()
        g.drawText(text, centerX - tw / 2, y, sizePx, 1f, colorTextMain)
    }

    /** 歌曲行(标题+歌手;当前播放左侧主题色竖条+底色,hover 高亮) */
    fun drawSongRow(
        g: GuiGraphicsHudGui,
        title: String,
        artist: String,
        current: Boolean,
        x: Int,
        y: Int,
        w: Int,
        rowH: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hover = mouseY in y until y + rowH && mouseX in x until x + w
        when {
            current -> {
                g.fill(x, y, x + 3, y + rowH, colorAccent)
                g.fill(x, y, x + w, y + rowH, colorRowCurrent)
            }
            hover -> g.fill(x, y, x + w, y + rowH, colorRowHover)
        }
        g.drawText(title.ifBlank { "未知标题" }, x + 10, y + 2, 11f, 1f, if (current) colorTextMain else 0xFFDDDDDD.toInt())
        g.drawText(artist, x + 10, y + 12, 9f, 1f, colorTextDim)
    }

    /** 通用行(标题+副标题;hover 高亮) */
    fun drawListRow(
        g: GuiGraphicsHudGui,
        title: String,
        sub: String,
        x: Int,
        y: Int,
        w: Int,
        rowH: Int,
        mouseX: Int,
        mouseY: Int,
        highlight: Boolean = false,
    ) {
        val hover = mouseY in y until y + rowH && mouseX in x until x + w
        if (highlight) {
            g.fill(x, y, x + 3, y + rowH, colorAccent)
            g.fill(x, y, x + w, y + rowH, colorRowCurrent)
        } else if (hover) {
            g.fill(x, y, x + w, y + rowH, colorRowHover)
        }
        g.drawText(title.ifBlank { "未命名" }, x + 6, y + 2, 11f, 1f, if (highlight) colorTextMain else colorTextMain)
        g.drawText(sub, x + 6, y + 14, 9f, 1f, colorTextDim)
    }

    /** 列表底部滚动提示 */
    fun drawScrollHint(g: GuiGraphicsHudGui, w: Int, h: Int) {
        g.drawText("↑↓ 滚动查看更多", w / 2 + 100, h - 18, 9f, 1f, colorTextFaint)
    }
}
