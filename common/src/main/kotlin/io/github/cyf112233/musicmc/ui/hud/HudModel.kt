package io.github.cyf112233.musicmc.ui.hud

import io.github.cyf112233.musicmc.model.LyricLine
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.player.MusicPlayer
import io.github.cyf112233.musicmc.ui.Widgets
import io.github.cyf112233.musicmc.util.Lrc
import kotlin.math.roundToInt

/**
 * HUD 渲染所需的最小矩形(逻辑 px,即 GUI 缩放坐标)。
 */
data class HudRect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

/**
 * 单帧 HUD 渲染数据:由 [HudLayout.compute] 每帧产出,渲染层只读绘制。
 */
data class HudFrame(
    val song: Song?,
    /** 面板整体矩形(编辑器拖拽命中测试 / 高亮边框用) */
    val panel: HudRect,
    /** 封面矩形(正方形;圆形遮罩时仍按此矩形绘制) */
    val cover: HudRect,
    val title: String,
    val titleX: Int,
    val titleY: Int,
    val artist: String,
    val artistX: Int,
    val artistY: Int,
    val timeText: String,
    val timeX: Int,
    val timeY: Int,
    /** 进度条矩形 */
    val bar: HudRect,
    /** 播放进度 0..1 */
    val progress: Float,
    /** 可见歌词行(单行:仅当前行;超长横向滚动,见 [HudLayout.lyricScrollOffset]) */
    val lyricLines: List<String>,
    /** lyricLines 中的当前行下标(-1=无歌词) */
    val lyricCurrentIndex: Int,
    val lyricX: Int,
    val lyricY: Int,
    val showCover: Boolean,
)

/**
 * HUD 歌词快照:三源歌词行 + 偏移 + 来源名(由 [HudLyricsCache] 异步填充)。
 */
data class CachedHudLyric(
    val lines: List<LyricLine>,
    val offsetSec: Float,
    val sourceLabel: String,
)

/**
 * HUD 布局计算(纯数据 + 布局,零 net.minecraft 依赖)。
 *
 * 约定:
 * - 锚点 [hudX]/[hudY] = 面板左上角的屏幕归一化坐标(0..1,由设置 / HUD 编辑器持久化);
 * - 尺寸基准:封面 96 逻辑 px × scale,文本列宽约 260 × scale(逻辑 px = GUI 缩放坐标,
 *   游戏内由渲染管线放大 guiScale 倍,编辑器经 canvas.scale(guiScale) 放大,两侧物理尺寸一致);
 * - 垂直布局:封面 + 信息列在上,歌词块在下,面板总高随歌词行数变化;
 * - 时间 / 百分比从播放器引擎状态取(engine.positionMs 基于 volatile 字段,渲染线程安全读)。
 */
object HudLayout {

    /** 封面基准边长(逻辑 px,×hudScale) */
    const val COVER_LOGICAL = 96f

    /** 文本列基准宽(逻辑 px,×hudScale) */
    const val TEXT_COL_LOGICAL = 260f

    /** 面板内边距(逻辑 px) */
    const val PAD_LOGICAL = 10f

    /** 封面与文本列间距(逻辑 px) */
    const val COVER_TEXT_GAP_LOGICAL = 10f

    /** 进度条高(逻辑 px) */
    const val BAR_HEIGHT_LOGICAL = 6f

    // 文本字号(逻辑 px;渲染层以 font.lineHeight 为基准用 pose 缩放实现)
    const val TITLE_SIZE = 16f
    const val SUB_SIZE = 12f
    const val LYRIC_CURRENT_SIZE = 14f
    const val LYRIC_OTHER_SIZE = 12f

    /** 行高 = 字号 + 4(渲染层 y 推进需与此一致) */
    const val LINE_TITLE = TITLE_SIZE + 4f
    const val LINE_SUB = SUB_SIZE + 4f
    const val LINE_LYRIC_CURRENT = LYRIC_CURRENT_SIZE + 4f
    const val LINE_LYRIC_OTHER = LYRIC_OTHER_SIZE + 4f

    /** 歌词滚动速度(GUI px/秒;超长歌词往返滚动) */
    const val LYRIC_SCROLL_SPEED = 30f

    /** 歌词滚动两端停留(毫秒) */
    const val LYRIC_SCROLL_HOLD_MS = 1500L

    /**
     * 超长歌词横向滚动偏移(GUI px,负值向左)。[textWidth] <= [availWidth] 时恒 0;
     * 往返式:停 [LYRIC_SCROLL_HOLD_MS] → 向左滚到末尾 → 停 → 滚回起点,循环。
     * 纯函数(只依赖 [nowMs]),游戏内渲染层与 HUD 编辑器共用,动画两侧一致。
     */
    fun lyricScrollOffset(nowMs: Long, textWidth: Float, availWidth: Float): Float {
        if (textWidth <= availWidth) return 0f
        val range = textWidth - availWidth
        val tripMs = (range / LYRIC_SCROLL_SPEED * 1000f).toLong().coerceAtLeast(1)
        val period = tripMs * 2 + LYRIC_SCROLL_HOLD_MS * 2
        val phase = nowMs % period
        return when {
            phase < LYRIC_SCROLL_HOLD_MS -> 0f
            phase < LYRIC_SCROLL_HOLD_MS + tripMs ->
                -(phase - LYRIC_SCROLL_HOLD_MS) / 1000f * LYRIC_SCROLL_SPEED
            phase < LYRIC_SCROLL_HOLD_MS * 2 + tripMs -> -range
            else -> -(range - (phase - LYRIC_SCROLL_HOLD_MS * 2 - tripMs) / 1000f * LYRIC_SCROLL_SPEED)
        }
    }

    /**
     * 计算本帧 HUD 布局。[w]/[h] 为 GUI 缩放屏幕尺寸(渲染层取 graphics.guiWidth/Height)。
     * 无当前歌曲时返回 null(渲染层跳过绘制)。
     */
    fun compute(
        w: Int,
        h: Int,
        scale: Float,
        player: MusicPlayer,
        lyric: CachedHudLyric?,
        hudX: Float,
        hudY: Float,
    ): HudFrame? {
        val song = player.current ?: return null
        // 尺寸语义修正(用户反馈"50% 已相当于别家 100%"):100% = 标准尺寸
        // (封面 48 逻辑 px ≈ 96 物理 px @guiScale2,与其他音乐 mod 的 100% 封面相当);
        // 旧实现 100% 对应 96 逻辑 px,整体偏大一倍。内部系数 0.5 等比缩小
        // 封面/文字列/字号/行高,编辑器预览与游戏内共用本函数,两侧一致。
        val s = scale.coerceIn(0.5f, 2f) * 0.5f
        val pad = (PAD_LOGICAL * s).roundToInt()
        val coverSize = (COVER_LOGICAL * s).roundToInt()
        val textW = (TEXT_COL_LOGICAL * s).roundToInt()
        val gap = (COVER_TEXT_GAP_LOGICAL * s).roundToInt()

        // ---- 歌词:仅当前行(不重叠;超长歌词由渲染层横向滚动) ----
        var lyricLines = emptyList<String>()
        var lyricIndex = -1
        var lyricBlockH = 0
        val posMs = player.engine.positionMs()
        if (lyric != null && lyric.lines.isNotEmpty()) {
            val syncMs = posMs - (lyric.offsetSec * 1000f).roundToInt()
            val idx = Lrc.findLineIndex(lyric.lines, syncMs)
            if (idx >= 0) {
                lyricLines = listOf(lyric.lines[idx].text)
                lyricIndex = 0
                lyricBlockH = (LINE_LYRIC_CURRENT * s).roundToInt()
            }
        }

        // ---- 面板尺寸(垂直:封面 + 信息列在上,歌词块在下) ----
        // 歌词块为空时不留间距,面板只包住封面 + 信息列(与旧布局同高)
        val panelW = pad * 2 + coverSize + gap + textW
        val hasLyrics = lyricLines.isNotEmpty()
        val lyricBlockGap = if (hasLyrics) gap else 0
        val panelH = pad + coverSize + lyricBlockGap + lyricBlockH + pad

        // 锚点归一化 → 像素,clamp 保持面板完整可见
        var px = (hudX * w).roundToInt()
        var py = (hudY * h).roundToInt()
        px = px.coerceIn(0, (w - panelW).coerceAtLeast(0))
        py = py.coerceIn(0, (h - panelH).coerceAtLeast(0))

        val coverX = px + pad
        val rowTop = py + pad
        val textX = coverX + coverSize + gap
        val titleY = rowTop
        val artistY = titleY + (LINE_TITLE * s).roundToInt()
        val timeY = artistY + (LINE_SUB * s).roundToInt()
        val barY = timeY + (LINE_SUB * s).roundToInt() + (4 * s).roundToInt()
        val barH = (BAR_HEIGHT_LOGICAL * s).roundToInt()

        // ---- 时间 / 进度 ----
        val durMs = song.durationMs
        val progress = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
        val timeText = "${Widgets.formatTime(posMs)} / ${Widgets.formatTime(durMs)}"

        return HudFrame(
            song = song,
            panel = HudRect(px, py, panelW, panelH),
            cover = HudRect(coverX, rowTop, coverSize, coverSize),
            title = song.title.ifBlank { "未知标题" },
            titleX = textX,
            titleY = titleY,
            artist = song.artist,
            artistX = textX,
            artistY = artistY,
            timeText = timeText,
            timeX = textX,
            timeY = timeY,
            bar = HudRect(textX, barY, textW, barH),
            progress = progress,
            lyricLines = lyricLines,
            lyricCurrentIndex = lyricIndex,
            lyricX = px + pad,
            lyricY = py + pad + coverSize + lyricBlockGap,
            showCover = true,
        )
    }
}
