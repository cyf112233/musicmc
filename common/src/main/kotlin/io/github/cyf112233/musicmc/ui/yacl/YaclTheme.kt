package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui

/**
 * YACL 版界面共享主题:配色与绘制辅助。
 * 现代化风格:深色渐变背景、圆角卡片、胶囊按钮、主题绿高亮、hover 反馈、
 * 带滑块与拖动高亮的进度条。所有 Yacl*Screen 复用本主题,保证视觉统一。
 *
 * 说明:底层绘制原语只有 fill / fillGradient / drawText / drawTexture,
 * 没有圆角矩形 / 描边 / 阴影 API,因此:
 * - 圆角用「中段矩形 + 四角 1px 阶梯」近似(半径小,视觉足够圆润);
 * - 描边用「外框比内底多一圈像素」实现;
 * - 进度条滑块用「小方块 + 边框」近似圆形捏手。
 */
object YaclTheme {

    // ---- 配色(26.2 主题:深蓝黑底 + 青绿主色,更通透) ----
    val colorBgTop = 0xFF141824.toInt()
    val colorBgBottom = 0xFF0B0E16.toInt()
    val colorCard = 0xFF1B2130.toInt()
    val colorCardBorder = 0xFF2E3850.toInt()
    val colorCardTopLine = 0xFF3A4666.toInt()
    val colorBtn = 0xFF232B3D.toInt()
    val colorBtnHover = 0xFF2E3A52.toInt()
    val colorAccent = 0xFF3DDC97.toInt()
    val colorAccentBright = 0xFF7BF0BD.toInt()
    val colorAccentDark = 0xFF2BB57C.toInt()
    val colorAccentGlow = 0xFF5FE8B0.toInt()
    val colorTextMain = 0xFFFFFFFF.toInt()
    val colorTextSub = 0xFFB6BFD4.toInt()
    val colorTextDim = 0xFF7C87A0.toInt()
    val colorTextFaint = 0xFF5A647C.toInt()
    val colorTrack = 0xFF2C3547.toInt()
    val colorRowHover = 0xFF232B3D.toInt()
    val colorRowCurrent = 0xFF143426.toInt()
    val colorError = 0xFFFF5C5C.toInt()
    val colorWarn = 0xFFFFC45C.toInt()
    val colorCoverFrame = 0xFF3A4666.toInt()

    /** 交互矩形(各界面复用;坐标每帧刷新,点击判定用) */
    open class Rect(var x1: Int, var y1: Int, var x2: Int, var y2: Int) {
        fun set(x1: Int, y1: Int, x2: Int, y2: Int) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2
        }

        fun hit(px: Double, py: Double): Boolean = px >= x1 && px < x2 && py >= y1 && py < y2
    }

    // ---- 绘制辅助 ----

    /** 全屏深色渐变背景(顶部再压一层半透明主色,增加通透感) */
    fun drawBackground(g: GuiGraphicsHudGui, w: Int, h: Int) {
        g.fillGradient(0, 0, w, h, colorBgTop, colorBgBottom)
        // 顶部细高光(模拟天际线反光)
        g.fill(0, 0, w, 1, 0x223DDC97.toInt())
    }

    /** 圆角矩形(半径 r,以 [color] 填充;[border] 非空时画 1px 描边) */
    fun fillRound(g: GuiGraphicsHudGui, x: Int, y: Int, w: Int, h: Int, r: Int, color: Int, border: Int? = null) {
        if (w <= 0 || h <= 0) return
        val rr = r.coerceIn(0, minOf(w, h) / 2)
        // 中段矩形 + 左右两侧
        g.fill(x + rr, y, x + w - rr, y + h, color)
        g.fill(x, y + rr, x + rr, y + h - rr, color)
        g.fill(x + w - rr, y + rr, x + w, y + h - rr, color)
        // 四角阶梯(1px 阶梯近似圆角)
        if (rr >= 2) {
            g.fill(x + 1, y, x + rr, y + 1, color)
            g.fill(x + w - rr, y, x + w - 1, y + 1, color)
            g.fill(x + 1, y + h - 1, x + rr, y + h, color)
            g.fill(x + w - rr, y + h - 1, x + w - 1, y + h, color)
            g.fill(x, y + 1, x + 1, y + rr, color)
            g.fill(x + w - 1, y + 1, x + w, y + rr, color)
            g.fill(x, y + h - rr, x + 1, y + h - 1, color)
            g.fill(x + w - 1, y + h - rr, x + w, y + h - 1, color)
        }
        border?.let { b ->
            g.fill(x + rr, y, x + w - rr, y + 1, b)
            g.fill(x + rr, y + h - 1, x + w - rr, y + h, b)
            g.fill(x, y + rr, x + 1, y + h - rr, b)
            g.fill(x + w - 1, y + rr, x + w, y + h - rr, b)
        }
    }

    /** 卡片:圆角底 + 顶部高光 + 底部描边 */
    fun drawCard(g: GuiGraphicsHudGui, x1: Int, y1: Int, x2: Int, y2: Int) {
        fillRound(g, x1, y1, x2 - x1, y2 - y1, 4, colorCard, colorCardBorder)
        g.fill(x1 + 4, y1, x2 - 4, y1 + 1, colorCardTopLine)
    }

    /** 封面:1px 边框 + 深色内衬,带轻微外发光感 */
    fun drawCover(g: GuiGraphicsHudGui, id: Any, x: Int, y: Int, size: Int) {
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, colorCoverFrame)
        g.fill(x - 2, y - 2, x + size + 2, y - 1, 0x223DDC97.toInt())
        g.fill(x - 2, y + size + 1, x + size + 2, y + size + 2, 0x223DDC97.toInt())
        g.drawTexture(id, x, y, size, size)
    }

    /** 封面占位:圆角深色块 + 音符文字 */
    fun drawCoverPlaceholder(g: GuiGraphicsHudGui, x: Int, y: Int, size: Int) {
        fillRound(g, x, y, size, size, 4, colorBtn, colorCardBorder)
        g.drawText("♪", x + size / 2 - 6, y + size / 2 - 8, 16f, 1f, colorTextFaint)
        g.drawText("No Cover", x + 4, y + size - 12, 8f, 1f, colorTextFaint)
    }

    /**
     * 进度条 / 音量条统一样式:圆角轨道 + 已填充段 + 渐变高光 + 滑块。
     * [active] 为 true(拖动中 / hover)时滑块放大并高亮,填充段提亮。
     */
    fun drawProgressBar(
        g: GuiGraphicsHudGui,
        r: Rect,
        progress: Float,
        mouseX: Int,
        mouseY: Int,
        hoverable: Boolean = true,
        active: Boolean = false,
        color: Int = colorAccent,
        showThumb: Boolean = true,
    ) {
        val w = r.x2 - r.x1
        val h = r.y2 - r.y1
        if (w <= 0 || h <= 0) return
        val hover = hoverable && mouseX in r.x1 until r.x2 && mouseY in r.y1 - 3 until r.y2 + 3
        val p = progress.coerceIn(0f, 1f)

        // 轨道(圆角 2px)
        fillRound(g, r.x1, r.y1, w, h, 2, colorTrack)

        // 已填充段(端头圆角;hover/active 时提亮)
        val fillW = (w * p).toInt()
        if (fillW > 0) {
            fillRound(g, r.x1, r.y1, fillW, h, 2, if (active || hover) colorAccentGlow else color)
            // 顶部高光
            g.fill(r.x1, r.y1, r.x1 + fillW, r.y1 + 1, 0x66FFFFFF.toInt())
        }

        // 滑块(圆形捏手近似:方块 + 边框;active/hover 放大)
        if (showThumb && fillW > 0) {
            val thumbSize = if (active || hover) 9 else 7
            val tx = (r.x1 + fillW - thumbSize / 2).coerceIn(r.x1, r.x2 - thumbSize)
            val ty = r.y1 + (h - thumbSize) / 2
            fillRound(g, tx, ty, thumbSize, thumbSize, thumbSize / 2, colorTextMain, colorCardBorder)
            if (active || hover) {
                g.fill(tx - 2, ty - 2, tx + thumbSize + 2, ty - 1, 0x333DDC97.toInt())
                g.fill(tx - 2, ty + thumbSize + 1, tx + thumbSize + 2, ty + thumbSize + 2, 0x333DDC97.toInt())
            }
        } else if (fillW > 0 && !showThumb) {
            // 无滑块模式:填充段端头 1px 高亮点
            g.fill(r.x1 + fillW - 1, r.y1, r.x1 + fillW, r.y2, colorAccentBright)
        }
    }

    /** 顶部工具按钮(胶囊形状;hover 提亮 + 主色描边) */
    fun drawPill(g: GuiGraphicsHudGui, r: Rect, label: String, mouseX: Int, mouseY: Int) {
        val hover = mouseX in r.x1 until r.x2 && mouseY in r.y1 until r.y2
        val bg = if (hover) colorBtnHover else colorBtn
        val h2 = (r.y2 - r.y1) / 2
        fillRound(g, r.x1, r.y1, r.x2 - r.x1, r.y2 - r.y1, h2, bg, if (hover) colorAccentDark else null)
        g.drawText(label, r.x1 + 4, r.y1 + 2, 10f, 1f, if (hover) colorAccentBright else colorTextSub)
    }

    /** 普通按钮(圆角 + hover 提亮 + 主题色文字;[accent] 为主题色实底按钮) */
    fun drawBtn(g: GuiGraphicsHudGui, r: Rect, label: String, mouseX: Int, mouseY: Int, accent: Boolean = false) {
        val hover = mouseX in r.x1 until r.x2 && mouseY in r.y1 until r.y2
        val bg = when {
            accent -> if (hover) colorAccentDark else colorAccent
            hover -> colorBtnHover
            else -> colorBtn
        }
        val h2 = (r.y2 - r.y1) / 2
        fillRound(g, r.x1, r.y1, r.x2 - r.x1, r.y2 - r.y1, h2, bg, if (hover && !accent) colorAccentDark else null)
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
                g.fill(x + 3, y, x + w, y + rowH, colorRowCurrent)
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
            g.fill(x + 3, y, x + w, y + rowH, colorRowCurrent)
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
