package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.platform.McScreens
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.lyrics.LyricCandidate
import io.github.cyf112233.musicmc.lyrics.LyricManager
import io.github.cyf112233.musicmc.lyrics.LyricProviders
import io.github.cyf112233.musicmc.model.LyricLine
import io.github.cyf112233.musicmc.util.Lrc
import io.github.cyf112233.musicmc.ui.Widgets
import io.github.cyf112233.musicmc.ui.hud.HudLyricsCache
import io.github.cyf112233.musicmc.util.Async
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 版歌词页:显示当前歌曲歌词,当前行高亮,自动跟随播放进度。
 * 手动滚动后暂停跟随,点「跟随」重新开启;视觉走 YaclTheme。
 *
 * 2026-08 补齐与 MUI LyricFragment 对齐的完整歌词管线:
 * - 加载走 [LyricManager.load](本地缓存 → Hub 同步 → CC 字幕 → 标题自动匹配三源),
 *   不再直接调 source.lyric 绕过缓存/回退链路(旧实现只显示 CC 字幕);
 * - 工具行:搜索歌词 / -0.5s / +0.5s / 偏移显示 / 来源标签;
 * - 搜索模式:MC 原生 EditBox 输入(IME/光标/粘贴,与 YaclSearchScreen 一致),
 *   三源并行搜索(LyricManager.manualSearch),点击候选手动绑定
 *   (LyricManager.bind),成功后回歌词视图并刷新 HUD 缓存;
 * - 偏移即点即存(LyricManager.adjustOffset,推送 Hub),HUD 同步重载。
 */
class YaclLyricScreen(private val back: Screen) : Screen(Component.literal(UiText.t("歌词", "Lyrics"))) {

    private val player get() = NetMusic.player

    private var lines: List<LyricLine> = emptyList()
    private var loaded = false
    private var error: String? = null
    private var scroll = 0
    private var autoFollow = true
    private var lastSongId: String? = null

    /** 当前歌词偏移(秒;显示时间 = 播放位置 - offsetSec*1000) */
    private var offsetSec: Float = 0f

    /** 来源标签("本地缓存"/"Hub"/"CC字幕"/来源名) */
    private var sourceLabel: String = ""

    // ---- 搜索模式状态 ----
    private var searchMode = false
    private var candidates: List<LyricCandidate> = emptyList()
    private var searching = false
    /** 搜索模式下的候选列表滚动偏移(候选多于一屏时可滚) */
    private var candidateScroll = 0

    private var editBox: EditBox? = null

    private val rectBackBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectFollowBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectSearchBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectMinusBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectPlusBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectSearchGoBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectSearchBackBtn = YaclTheme.Rect(0, 0, 0, 0)

    override fun init() {
        super.init()
        val box = EditBox(font, width / 2 - 150, 42, 260, 16, Component.literal(UiText.t("搜索歌词", "Search Lyrics")))
        box.setMaxLength(60)
        editBox = box
        addWidget(box)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val g = GuiGraphicsHudGui(graphics)
        val w = g.guiWidth()
        val h = g.guiHeight()

        YaclTheme.drawBackground(g, w, h)

        val song = player.current

        if (searchMode) {
            drawSearchMode(graphics, g, w, h, mouseX, mouseY)
            return
        }

        rectBackBtn.x1 = 12; rectBackBtn.y1 = 10; rectBackBtn.x2 = 56; rectBackBtn.y2 = 26
        YaclTheme.drawBtn(g, rectBackBtn, UiText.t("< 返回", "< Back"), mouseX, mouseY)

        // 歌名 + 工具行(搜索歌词 / -0.5s / +0.5s / 偏移 / 跟随)
        val songTitle = song?.title?.ifBlank { UiText.t("未知标题", "Unknown") } ?: UiText.t("未在播放", "Not Playing")
        // 歌名用 marquee 横向滚动(与主界面一致):裸居中 drawText 在长标题
        // (B 站 30~80 字常见)下会盖住左右按钮
        val titleMaxW = minOf(w - 112, 240)
        YaclTheme.drawMarqueeText(g, songTitle, w / 2 - titleMaxW / 2, 12, 14f, titleMaxW, YaclTheme.colorTextMain)

        // 工具行(第二行,歌名下方):搜索歌词 / -0.5s / 偏移 / +0.5s / 跟随
        val toolY = 34
        val toolH = 16
        rectSearchBtn.x1 = 12; rectSearchBtn.y1 = toolY; rectSearchBtn.x2 = 12 + 62; rectSearchBtn.y2 = toolY + toolH
        YaclTheme.drawBtn(g, rectSearchBtn, UiText.t("搜索歌词", "Search Lyrics"), mouseX, mouseY)
        rectMinusBtn.x1 = w / 2 - 78; rectMinusBtn.y1 = toolY; rectMinusBtn.x2 = w / 2 - 28; rectMinusBtn.y2 = toolY + toolH
        YaclTheme.drawBtn(g, rectMinusBtn, "-0.5s", mouseX, mouseY)
        rectPlusBtn.x1 = w / 2 + 28; rectPlusBtn.y1 = toolY; rectPlusBtn.x2 = w / 2 + 78; rectPlusBtn.y2 = toolY + toolH
        YaclTheme.drawBtn(g, rectPlusBtn, "+0.5s", mouseX, mouseY)
        rectFollowBtn.x1 = w - 68; rectFollowBtn.y1 = toolY; rectFollowBtn.x2 = w - 12; rectFollowBtn.y2 = toolY + toolH
        YaclTheme.drawBtn(g, rectFollowBtn, if (autoFollow) UiText.t("跟随:开", "Follow: On") else UiText.t("跟随:关", "Follow: Off"), mouseX, mouseY)

        // 偏移 + 来源标签(第二行中间)
        YaclTheme.drawCenteredClipped(
            g,
            UiText.t("偏移 ${"%.1f".format(offsetSec)}s · $sourceLabel", "Offset ${"%.1f".format(offsetSec)}s · $sourceLabel"),
            w / 2,
            toolY + 2,
            9f,
            96,
            YaclTheme.colorTextSub,
        )

        // 歌词加载
        if (song == null) {
            g.drawText(UiText.t("未在播放", "Not Playing"), w / 2 - 160, 60, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        if (song.id != lastSongId) {
            lastSongId = song.id
            lines = emptyList()
            loaded = false
            error = null
            scroll = 0
            autoFollow = true
            offsetSec = 0f
            sourceLabel = ""
            loadLyrics(song.id)
        }
        if (!loaded) {
            g.drawText(UiText.t("歌词加载中…", "Loading lyrics…"), w / 2 - 160, 60, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        if (error != null && lines.isEmpty()) {
            YaclTheme.drawCenteredClipped(g, UiText.t("歌词加载失败: $error", "Failed to load lyrics: $error"), w / 2, 60, 11f, (w - 48).coerceAtLeast(40), YaclTheme.colorError)
            return
        }
        if (lines.isEmpty()) {
            g.drawText(UiText.t("暂无歌词,点「搜索歌词」手动绑定", "No lyrics. Tap \"Search Lyrics\" to bind manually"), w / 2 - 160, 60, 12f, 1f, YaclTheme.colorTextDim)
            return
        }

        // 当前行(自动跟随)
        val posMs = player.engine.positionMs()
        val currentIndex = Lrc.findLineIndex(lines, posMs - (offsetSec * 1000f).toInt())
        val rowH = 18
        val listTop = 62
        val visibleRows = (h - listTop - 8) / rowH
        if (autoFollow) {
            scroll = (currentIndex - visibleRows / 3).coerceAtLeast(0)
        }
        val maxScroll = (lines.size - visibleRows).coerceAtLeast(0)
        scroll = scroll.coerceIn(0, maxScroll)

        // Lyrics列表
        val listX = w / 2 - 160
        // 歌词文本可用宽度:给右侧时间列留位 —— 时间列右对齐锚在 listX+318 起约 27px,
        // 歌词若画满 318 会与时间文本重叠
        val textMaxW = 274
        var idx = scroll
        var y = listTop
        while (idx < lines.size && y + rowH < h - 8) {
            val line = lines[idx]
            val isCurrent = idx == currentIndex
            val color = when {
                isCurrent -> YaclTheme.colorTextMain
                idx < currentIndex -> YaclTheme.colorTextSub
                else -> YaclTheme.colorTextFaint
            }
            if (isCurrent) {
                g.fill(listX - 6, y, listX + textMaxW, y + rowH, YaclTheme.colorRowCurrent)
                g.fill(listX - 6, y, listX - 3, y + rowH, YaclTheme.colorAccent)
            }
            // 当前行歌词用 marquee 横向滚动显示全名(与主界面歌名一致,超长不截断),
            // 其余行保持截断;maxWidth 收窄到 textMaxW,滚动也不撞时间列
            if (isCurrent) {
                YaclTheme.drawMarqueeText(g, line.text, listX, y + 2, 12f, textMaxW, color)
            } else {
                YaclTheme.drawTextClipped(g, line.text, listX, y + 2, 11f, textMaxW, color)
            }
            // 时间列:右对齐到列表右端(listX+318 起),落在歌词区(textMaxW=274)右侧留白处
            val timeText = Widgets.formatTime(line.timeMs)
            val tw = g.textWidth(timeText).toInt()
            g.drawText(timeText, listX + 318 - tw, y + 5, 8f, 1f, YaclTheme.colorTextFaint)
            y += rowH
            idx++
        }
    }

    /** 搜索模式渲染:EditBox + 三源搜索结果列表(点击绑定)+ 返回歌词视图 */
    private fun drawSearchMode(graphics: GuiGraphicsExtractor, g: GuiGraphicsHudGui, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        YaclTheme.drawCenteredTitle(g, UiText.t("搜索歌词", "Search Lyrics"), w / 2, 10)

        rectSearchBackBtn.x1 = 12; rectSearchBackBtn.y1 = 10; rectSearchBackBtn.x2 = 56; rectSearchBackBtn.y2 = 26
        YaclTheme.drawBtn(g, rectSearchBackBtn, UiText.t("< 返回", "< Back"), mouseX, mouseY)

        // 输入框(MC 原生渲染,与 YaclSearchScreen 一致)
        editBox?.extractWidgetRenderState(graphics, mouseX, mouseY, 0f)

        rectSearchGoBtn.x1 = width / 2 + 118; rectSearchGoBtn.y1 = 42
        rectSearchGoBtn.x2 = width / 2 + 174; rectSearchGoBtn.y2 = 58
        YaclTheme.drawBtn(g, rectSearchGoBtn, if (searching) "…" else UiText.t("搜索", "Search"), mouseX, mouseY, accent = true)

        if (searching) {
            g.drawText(UiText.t("搜索中…", "Searching…"), w / 2 - 40, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        if (candidates.isEmpty()) {
            // 搜索失败错误优先显示;未搜索过显示提示
            if (error != null) {
                YaclTheme.drawCenteredClipped(g, UiText.t("搜索失败: $error", "Search failed: $error"), w / 2, h / 2 - 8, 11f, (w - 48).coerceAtLeast(40), YaclTheme.colorError)
            } else {
                g.drawText(UiText.t("输入关键词搜索三源歌词", "Search lyrics from 3 sources"), w / 2 - 100, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            }
            return
        }

        // 候选列表(可滚动;点击绑定)
        val rowH = 22
        val listX = 24
        val listW = w - 48
        val listTop = 74
        var idx = candidateScroll
        var y = listTop
        while (idx < candidates.size && y + rowH < h - 8) {
            val c = candidates[idx]
            val hover = mouseY in y until y + rowH && mouseX in listX until listX + listW
            if (hover) g.fill(listX, y, listX + listW, y + rowH, YaclTheme.colorRowHover)
            YaclTheme.drawTextClipped(g, c.title.ifBlank { UiText.t("未知歌曲", "Unknown") }, listX + 6, y + 1, 11f, listW - 130, YaclTheme.colorTextMain)
            YaclTheme.drawTextClipped(
                g,
                "${c.artist} · ${LyricProviders.sourceLabel(c.source)}",
                listX + 6,
                y + 12,
                9f,
                listW - 130,
                YaclTheme.colorTextDim,
            )
            g.drawText(Widgets.formatTime(c.durationMs), listX + listW - 52, y + 4, 9f, 1f, YaclTheme.colorTextFaint)
            y += rowH
            idx++
        }
        if (candidates.size > (h - listTop) / rowH) {
            YaclTheme.drawScrollHint(g, w, h)
        }
    }

    // ---------------- 加载 / 偏移 / 搜索 ----------------

    /** 经 LyricManager 加载(缓存 → Hub → CC → 标题回退),而非直接 source.lyric */
    private fun loadLyrics(songId: String) {
        val song = player.current ?: return
        LyricManager.load(song) { result, err ->
            Async.onUi {
                if (songId != player.current?.id) return@onUi
                lines = result.lines
                offsetSec = result.offsetSec
                sourceLabel = result.from
                loaded = true
                error = err
            }
        }
    }

    /** 调整偏移 ±0.5s:即点即存,推送 Hub,HUD 同步重载 */
    private fun adjustOffset(deltaSec: Float) {
        val song = player.current ?: return
        LyricManager.adjustOffset(song, deltaSec) { newOffset ->
            offsetSec = newOffset
            HudLyricsCache.invalidate()
        }
    }

    /** 进入搜索模式 */
    private fun enterSearchMode() {
        searchMode = true
        candidates = emptyList()
        searching = false
        candidateScroll = 0
        error = null
        editBox?.setValue("")
        editBox?.setFocused(true)
    }

    /** 退出搜索模式回歌词视图 */
    private fun exitSearchMode() {
        searchMode = false
        candidates = emptyList()
        searching = false
        candidateScroll = 0
    }

    private fun doSearch() {
        val keyword = editBox?.getValue()?.trim().orEmpty()
        if (keyword.isEmpty() || searching) return
        searching = true
        candidates = emptyList()
        LyricManager.manualSearch(keyword) { list, err ->
            Async.onUi {
                if (!searchMode) return@onUi
                searching = false
                if (err != null && list.isEmpty()) {
                    candidates = emptyList()
                    error = err
                } else {
                    candidates = list
                    error = null
                }
            }
        }
    }

    /** 点击候选行:手动绑定歌词 → 回歌词视图 + HUD 缓存重载 */
    private fun bindCandidate(index: Int) {
        val candidate = candidates.getOrNull(index) ?: return
        val song = player.current ?: return
        LyricManager.bind(song, candidate) { result, err ->
            Async.onUi {
                if (!searchMode) return@onUi
                if (result.lines.isEmpty()) {
                    error = err ?: UiText.t("该来源暂无歌词", "No lyrics from this source")
                    return@onUi
                }
                lines = result.lines
                offsetSec = result.offsetSec
                sourceLabel = result.from
                loaded = true
                error = null
                scroll = 0
                autoFollow = true
                exitSearchMode()
                HudLyricsCache.invalidate()
            }
        }
    }

    // ---------------- 交互 ----------------

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        if (searchMode) {
            if (rectSearchBackBtn.hit(x, y)) { exitSearchMode(); return true }
            if (rectSearchGoBtn.hit(x, y)) { doSearch(); return true }
            // EditBox 点击聚焦交给 MC Screen 自动分发(addWidget 注册的组件)
            // 候选行点击绑定(考虑滚动偏移;上界与绘制一致,避免空白区误绑定)
            if (candidates.isNotEmpty()) {
                val rowH = 22
                val listX = 24
                val listW = width - 48
                if (x >= listX && x < listX + listW && y >= 74 && y < height - 8) {
                    val row = (y - 74).toInt() / rowH + candidateScroll
                    if (row in candidates.indices) {
                        bindCandidate(row)
                        return true
                    }
                }
            }
            return true
        }
        if (rectBackBtn.hit(x, y)) { McScreens.open(back); return true }
        if (rectSearchBtn.hit(x, y)) { enterSearchMode(); return true }
        if (rectMinusBtn.hit(x, y)) { adjustOffset(-0.5f); return true }
        if (rectPlusBtn.hit(x, y)) { adjustOffset(0.5f); return true }
        if (rectFollowBtn.hit(x, y)) { autoFollow = !autoFollow; return true }
        return super.mouseClicked(event, doubleClick)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (searchMode) {
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                doSearch()
                return true
            }
            val box = editBox
            // 输入框聚焦时交给 EditBox 处理;未聚焦时交回上级(Esc 可关闭本屏),
            // 避免搜索模式下 Esc 永远被吞
            if (box != null && box.isFocused) {
                if (box.keyPressed(event)) return true
            }
            return super.keyPressed(event)
        }
        return super.keyPressed(event)
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        if (searchMode) {
            // 候选列表可滚动:不干扰歌词 autoFollow 状态
            if (candidates.isEmpty()) return false
            val rowH = 22
            val maxScroll = (candidates.size - (height - 74) / rowH).coerceAtLeast(0)
            candidateScroll = (candidateScroll - dy.toInt()).coerceIn(0, maxScroll)
            return true
        }
        // 无歌词时不吞滚轮事件(页面无可滚动内容,交给上级处理)
        if (lines.isEmpty()) return false
        autoFollow = false
        scroll = (scroll - dy.toInt()).coerceAtLeast(0)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}
