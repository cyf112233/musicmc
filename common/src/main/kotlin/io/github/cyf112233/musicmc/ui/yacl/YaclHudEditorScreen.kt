package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.platform.McScreens
import io.github.cyf112233.musicmc.client.CoverTextureCache
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.player.PlayerState
import io.github.cyf112233.musicmc.ui.Widgets
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 版 HUD 编辑器:拖拽调整游戏内悬浮面板位置 / 滚轮缩放,实时持久化
 * config.hudX/hudY/hudScale(0..1 归一化锚点,语义与原 ModernUI 编辑器一致)。
 *
 * 面板预览简化自 MusicHudRenderer 布局(封面占位 + 歌名 + 进度条);
 * 拖动时面板跟随鼠标,所见即所得。
 */
class YaclHudEditorScreen : Screen(Component.literal(UiText.t("HUD 编辑器", "HUD Editor"))) {

    private val config get() = NetMusic.config

    private var curX = config.hudX
    private var curY = config.hudY
    private var scale = config.hudScale

    /** 拖拽中:面板内点击偏移(归一化,避免拖动时面板跳变) */
    private var dragging = false
    private var grabDX = 0f
    private var grabDY = 0f

    private val rectReset = YaclTheme.Rect(0, 0, 0, 0)
    private val rectDone = YaclTheme.Rect(0, 0, 0, 0)

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val g = GuiGraphicsHudGui(graphics)
        val w = g.guiWidth()
        val h = g.guiHeight()

        // 半透明遮罩(保留游戏画面可见性,预览 HUD 效果)
        g.fill(0, 0, w, h, 0x90000000.toInt())

        // HUD 面板预览(尺寸随缩放)
        val panelW = (190 * scale).toInt()
        val panelH = (64 * scale).toInt()
        val px = (curX * (w - panelW).coerceAtLeast(0)).toInt()
        val py = (curY * (h - panelH).coerceAtLeast(0)).toInt()

        // 面板底
        g.fill(px, py, px + panelW, py + panelH, 0xD01E2532.toInt())
        g.fill(px, py, px + panelW, py + 1, YaclTheme.colorCardBorder)
        g.fill(px, py + panelH - 1, px + panelW, py + panelH, YaclTheme.colorCardBorder)

        // 封面占位(48px 基准,随缩放)
        val coverSize = (44 * scale).toInt()
        val coverId = CoverTextureCache.currentIdentifier()
        if (coverId != null) {
            g.drawTexture(coverId, px + 4, py + 4, coverSize, coverSize)
        } else {
            g.fill(px + 4, py + 4, px + 4 + coverSize, py + 4 + coverSize, YaclTheme.colorBtn)
        }

        // 文本预览(歌名 + 状态;超出面板宽度截断)
        val song = NetMusic.player.current
        val textX = px + 4 + coverSize + 6
        val titleMaxW = (px + panelW - 4 - textX).coerceAtLeast(40)
        YaclTheme.drawTextClipped(
            g,
            song?.title?.ifBlank { UiText.t("未知标题", "Unknown") } ?: UiText.t("未在播放", "Not Playing"),
            textX,
            py + 4,
            (10 * scale).coerceAtLeast(6f),
            titleMaxW,
            YaclTheme.colorTextMain,
        )
        val stateText = when (NetMusic.player.state) {
            PlayerState.PLAYING -> UiText.t("播放中", "Playing")
            PlayerState.PAUSED -> UiText.t("已暂停", "Paused")
            PlayerState.LOADING -> UiText.t("加载中…", "Loading…")
            PlayerState.ERROR -> UiText.t("播放出错", "Playback Error")
            else -> UiText.t("未播放", "Idle")
        }
        YaclTheme.drawTextClipped(g, stateText, textX, py + 4 + (16 * scale).toInt(), (8 * scale).coerceAtLeast(5f), titleMaxW, YaclTheme.colorAccentBright)

        // 进度条预览
        val barY = py + panelH - (16 * scale).toInt()
        val barH = (4 * scale).toInt().coerceAtLeast(2)
        val barW = panelW - 8
        g.fill(px + 4, barY, px + 4 + barW, barY + barH, YaclTheme.colorTrack)
        val dur = song?.durationMs ?: 0
        val pos = NetMusic.player.engine.positionMs()
        val progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        val fillW = (barW * progress).toInt()
        if (fillW > 0) g.fill(px + 4, barY, px + 4 + fillW, barY + barH, YaclTheme.colorAccent)

        // 顶部提示 + 缩放显示(长文本按屏宽截断,不溢出屏幕)
        val hint = "Drag to move · Scroll to scale · Pos: ${(curX * 100).toInt()}%,${(curY * 100).toInt()}% · Scale: ${(scale * 100).toInt()}%"
        YaclTheme.drawTextClipped(g, hint, 12, 10, 11f, w - 24, YaclTheme.colorTextSub)

        // 按钮:重置 / 完成
        rectReset.set(w - 160, h - 30, w - 86, h - 8)
        rectDone.set(w - 78, h - 30, w - 12, h - 8)
        YaclTheme.drawBtn(g, rectReset, UiText.t("重置位置", "Reset Pos"), mouseX, mouseY)
        YaclTheme.drawBtn(g, rectDone, UiText.t("完成", "Done"), mouseX, mouseY, accent = true)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x().toFloat()
        val y = event.y().toFloat()
        if (rectReset.hit(x.toDouble(), y.toDouble())) {
            curX = 0.92f; curY = 0.86f
            save()
            return true
        }
        if (rectDone.hit(x.toDouble(), y.toDouble())) {
            save()
            McScreens.open(null)
            return true
        }
        // 面板内按下 → 进入拖拽
        val w = width
        val h = height
        val panelW = (190 * scale).toInt()
        val panelH = (64 * scale).toInt()
        val px = (curX * (w - panelW).coerceAtLeast(0)).toInt()
        val py = (curY * (h - panelH).coerceAtLeast(0)).toInt()
        if (x >= px && x < px + panelW && y >= py && y < py + panelH) {
            dragging = true
            grabDX = (x - px) / (w - panelW).coerceAtLeast(1)
            grabDY = (y - py) / (h - panelH).coerceAtLeast(1)
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (dragging) {
            val w = width
            val h = height
            val panelW = (190 * scale).toInt()
            val panelH = (64 * scale).toInt()
            val denomX = (w - panelW).coerceAtLeast(1)
            val denomY = (h - panelH).coerceAtLeast(1)
            curX = ((event.x() - grabDX.toDouble() * denomX) / denomX).toFloat().coerceIn(0f, 1f)
            curY = ((event.y() - grabDY.toDouble() * denomY) / denomY).toFloat().coerceIn(0f, 1f)
            save()
            return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        scale = (scale - dy.toFloat() * 0.05f).coerceIn(0.5f, 2.0f)
        save()
        return true
    }

    override fun isPauseScreen(): Boolean = false

    private fun save() {
        NetMusic.updateConfig { it.copy(hudX = curX, hudY = curY, hudScale = scale) }
    }
}
