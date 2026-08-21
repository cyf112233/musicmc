package io.github.cyf112233.musicmc.ui

import icyllis.modernui.R
import icyllis.modernui.fragment.Fragment
import icyllis.modernui.graphics.drawable.ColorDrawable
import icyllis.modernui.text.TextUtils
import icyllis.modernui.text.Typeface
import icyllis.modernui.util.DataSet
import icyllis.modernui.view.Gravity
import icyllis.modernui.view.LayoutInflater
import icyllis.modernui.view.MotionEvent
import icyllis.modernui.view.View
import icyllis.modernui.view.ViewGroup
import icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT
import icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT
import icyllis.modernui.widget.ArrayAdapter
import icyllis.modernui.widget.Button
import icyllis.modernui.widget.EditText
import icyllis.modernui.widget.FrameLayout
import icyllis.modernui.widget.LinearLayout
import icyllis.modernui.widget.ListView
import icyllis.modernui.widget.TextView
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.lyrics.LyricCandidate
import io.github.cyf112233.musicmc.lyrics.LyricManager
import io.github.cyf112233.musicmc.lyrics.LyricProviders
import io.github.cyf112233.musicmc.model.LyricLine
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.player.PlayerListener
import io.github.cyf112233.musicmc.ui.hud.HudLyricsCache
import io.github.cyf112233.musicmc.util.Async
import io.github.cyf112233.musicmc.util.Lrc
import icyllis.modernui.core.Context
import kotlin.jvm.Volatile

/**
 * 歌词页(BBPlayer 式完整逻辑):
 * - 顶部工具行:搜索歌词 / -0.5s / +0.5s / 偏移显示 / 返回;
 *   来源小字(来源: CC字幕 / 网易云 / QQ音乐 / 酷狗 / 本地缓存 / Hub)放第二行;
 * - 歌词区:多行滚动歌词(ListView),当前行 colorPrimary + 16sp + BOLD,
 *   其余行 onSurfaceVariant + 12sp,行内水平居中;
 * - 滚动跟随:播放进度回调中 Lrc.findLineIndex(positionMs - offset*1000) 得当前行,
 *   idx 变化 → notifyDataSetChanged + setSelection(idx-2)(当前行保持在可视区上部约 1/3);
 *   用户触摸列表后 3s 内暂停自动跟随;
 * - 搜索模式:EditText + 搜索按钮 + 候选 ListView("标题 歌手 · 来源"),点击行手动绑定;
 * - 偏移即点即存(经 LyricManager.adjustOffset,并推送 Hub);
 * - Esc / 返回按钮:搜索模式下先退出搜索,再回退 Fragment。
 */
class LyricFragment : Fragment() {

    private var sourceText: TextView? = null
    private var offsetLabel: TextView? = null
    private var lyricPanel: LinearLayout? = null
    private var lyricList: ListView? = null
    private var lyricEmptyText: TextView? = null
    private var searchPanel: LinearLayout? = null
    private var searchInput: EditText? = null
    private var searchList: ListView? = null
    private var lyricAdapter: LyricLineAdapter? = null

    /** 当前歌曲的歌词(按时间排序) */
    private var lines: List<LyricLine> = emptyList()

    /** 当前歌词偏移(秒;显示时间 = 播放位置 - offset*1000) */
    private var offsetSec: Float = 0f

    /** 最近一次进度(用于偏移调整后立即重渲染当前行) */
    private var lastPosMs: Int = 0

    /** 当前高亮行索引(上次渲染过的行);-1 = 尚未渲染(还没到第一行歌词) */
    private var currentLineIndex: Int = -1

    /** 最近一次用户触摸歌词列表的时间(ms);期间暂停自动跟随 */
    private var lastUserTouchMs: Long = 0L

    private val listener = object : PlayerListener {
        override fun onProgress(posMs: Int, durationMs: Int) {
            lastPosMs = posMs
            if (System.currentTimeMillis() - lastUserTouchMs < FOLLOW_SUPPRESS_MS) return
            followCurrentLine()
        }

        override fun onSongChanged(song: Song?) {
            if (isAdded && NetMusic.config.lyricsEnabled) {
                resetAndReload()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: DataSet?): View {
        val context = requireContext()
        val root = FrameLayout(context).apply {
            setBackground(ColorDrawable(0xCC000000.toInt()))
        }

        // 防御:正常路径下歌词按钮已拦截未开启入口,此处再兜底一次直接显示文案不加载
        val lyricsEnabled = NetMusic.config.lyricsEnabled

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ---- 顶部工具行 ----
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        }
        toolbar.addView(
            Button(context, null, R.attr.borderlessButtonStyle).apply {
                text = UiText.t("搜索歌词", "Search Lyrics")
                setOnClickListener { toggleSearchMode() }
                setMinimumWidth(dp(52f))
            },
        )
        toolbar.addView(
            Button(context, null, R.attr.borderlessButtonStyle).apply {
                text = "-0.5s"
                setOnClickListener { adjustOffset(-0.5f) }
                setMinimumWidth(dp(44f))
            },
        )
        toolbar.addView(
            Button(context, null, R.attr.borderlessButtonStyle).apply {
                text = "+0.5s"
                setOnClickListener { adjustOffset(0.5f) }
                setMinimumWidth(dp(44f))
            },
        )
        offsetLabel = TextView(context).apply {
            text = UiText.t("偏移 0.0s", "Offset 0.0s")
            setTextSize(13f)
            gravity = Gravity.CENTER
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
        }
        toolbar.addView(offsetLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        toolbar.addView(
            Button(context, null, R.attr.borderlessButtonStyle).apply {
                text = UiText.t("返回", "Back")
                setOnClickListener { back() }
                setMinimumWidth(dp(44f))
            },
        )
        column.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- 歌词显示区(第二行来源小字 + 多行滚动歌词 ListView) ----
        lyricPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        sourceText = TextView(context).apply {
            text = ""
            setTextSize(12f)
            gravity = Gravity.CENTER
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
            setPadding(0, dp(4f), 0, dp(4f))
        }
        lyricPanel?.addView(sourceText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val lyricArea = FrameLayout(context)
        lyricList = ListView(context).apply {
            setDivider(null) // 歌词列表无分隔线
            // 用户触摸(按下)即记录时间,3s 内进度回调不自动跳行/滚动
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    lastUserTouchMs = System.currentTimeMillis()
                }
                false // 不消费事件,交给 ListView 正常滚动
            }
        }
        lyricArea.addView(lyricList, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // 空态文案层(有歌词时隐藏):复用现有文案"歌词功能已关闭"/"暂无歌词"/错误信息
        lyricEmptyText = TextView(context).apply {
            text = if (lyricsEnabled) UiText.t("暂无歌词", "No lyrics yet") else UiText.t("歌词功能已禁用", "Lyrics disabled")
            setTextSize(22f)
            gravity = Gravity.CENTER
            setTextColor(Widgets.resolveColor(context, R.attr.colorPrimary) ?: 0xFFBB86FC.toInt())
            setPadding(dp(24f), 0, dp(24f), 0)
        }
        lyricArea.addView(lyricEmptyText, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        lyricPanel?.addView(lyricArea, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        column.addView(lyricPanel, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // ---- 搜索模式面板(初始隐藏) ----
        searchPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val searchBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }
        searchInput = EditText(context).apply {
            hint = UiText.t("输入歌曲标题 / 歌手", "Enter song title / artist")
            setTextSize(14f)
            // Enter = 搜索(无 IME 管线,直接按键触发)
            Widgets.bindEnter(this) { doSearch() }
        }
        searchBar.addView(searchInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        searchBar.addView(
            Button(context, null, R.attr.buttonElevatedStyle).apply {
                text = UiText.t("搜索", "Search")
                setOnClickListener { doSearch() }
                setMinimumWidth(dp(52f))
            },
        )
        searchPanel?.addView(searchBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        searchList = ListView(context).apply {
            setOnItemClickListener { _, _, position, _ ->
                val adapter = searchList?.adapter as? LyricCandidateAdapter ?: return@setOnItemClickListener
                val candidate = adapter.getItem(position) ?: return@setOnItemClickListener
                bindLyric(candidate)
            }
        }
        searchPanel?.addView(searchList, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        column.addView(searchPanel, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        NetMusic.player.addListener(listener)
        if (lyricsEnabled) loadLyric()
        return root
    }

    override fun onDestroyView() {
        NetMusic.player.removeListener(listener)
        super.onDestroyView()
    }

    // ---------------- 歌词加载 / 渲染 ----------------

    private fun resetAndReload() {
        lines = emptyList()
        offsetSec = 0f
        currentLineIndex = -1
        lastUserTouchMs = 0L
        lyricList?.adapter = null
        lyricAdapter = null
        lyricEmptyText?.text = UiText.t("暂无歌词", "No lyrics yet")
        lyricEmptyText?.visibility = View.VISIBLE
        sourceText?.text = ""
        offsetLabel?.text = UiText.t("偏移 0.0s", "Offset 0.0s")
        loadLyric()
    }

    private fun loadLyric() {
        val song = NetMusic.player.current ?: return
        Async.run {
            LyricManager.load(song) { result, err ->
                Async.onUi {
                    if (!isAdded) return@onUi
                    offsetSec = result.offsetSec
                    offsetLabel?.text = UiText.t("偏移 ${"%.1f".format(offsetSec)}s", "Offset ${"%.1f".format(offsetSec)}s")
                    showLyrics(result.lines, result.from, err)
                }
            }
        }
    }

    /**
     * 用加载/绑定结果填充歌词列表:[newLines] 非空 → 设置适配器并按当前进度跟随;
     * 为空 → 显示空态文案(err 优先,兜底"暂无歌词")。
     */
    private fun showLyrics(newLines: List<LyricLine>, from: String, err: String?) {
        lines = newLines
        sourceText?.text = if (newLines.isNotEmpty()) UiText.t("来源: $from", "Source: $from") else ""
        lyricEmptyText?.apply {
            text = if (newLines.isEmpty()) (err ?: UiText.t("暂无歌词", "No lyrics yet")) else ""
            visibility = if (newLines.isEmpty()) View.VISIBLE else View.GONE
        }
        if (newLines.isEmpty()) {
            lyricList?.adapter = null
            lyricAdapter = null
            currentLineIndex = -1
            return
        }
        val adapter = LyricLineAdapter(requireContext(), newLines)
        lyricAdapter = adapter
        lyricList?.adapter = adapter
        currentLineIndex = -1 // 强制首次渲染高亮行
        followCurrentLine()
    }

    /**
     * 滚动跟随:currentTime = positionMs - offsetSec*1000,取当前行 idx;
     * idx 变化时刷新高亮并把列表滚动到 idx-2(当前行位于可视区上部约 1/3)。
     */
    private fun followCurrentLine() {
        val adapter = lyricAdapter ?: return
        if (adapter.count == 0) return
        val idx = Lrc.findLineIndex(lines, lastPosMs - (offsetSec * 1000).toInt())
        if (idx == currentLineIndex) return
        currentLineIndex = idx
        adapter.currentIndex = idx
        adapter.notifyDataSetChanged()
        lyricList?.setSelection((idx - 2).coerceAtLeast(0))
    }

    /** 恢复显示(绑词失败回退到当前已加载歌词视图) */
    private fun refreshDisplay() {
        if (lines.isEmpty()) {
            lyricEmptyText?.apply {
                text = UiText.t("暂无歌词", "No lyrics yet")
                visibility = View.VISIBLE
            }
        } else {
            lyricEmptyText?.visibility = View.GONE
            followCurrentLine()
        }
    }

    // ---------------- 工具行操作 ----------------

    private fun adjustOffset(delta: Float) {
        val song = NetMusic.player.current ?: return
        LyricManager.adjustOffset(song, delta) { newOffset ->
            offsetSec = newOffset
            offsetLabel?.text = UiText.t("偏移 ${"%.1f".format(newOffset)}s", "Offset ${"%.1f".format(newOffset)}s")
            followCurrentLine()
            // 偏移已即点即存落盘:通知 HUD 歌词缓存重载(回调在 UI 线程,直接标脏)
            HudLyricsCache.invalidate()
        }
    }

    private fun back() {
        if (searchPanel?.visibility == View.VISIBLE) {
            toggleSearchMode()
        } else if (parentFragmentManager.backStackEntryCount > 0) {
            runCatching { parentFragmentManager.popBackStack() }
        }
    }

    // ---------------- 搜索模式 ----------------

    private fun toggleSearchMode() {
        val panel = searchPanel ?: return
        val show = panel.visibility == View.GONE
        panel.visibility = if (show) View.VISIBLE else View.GONE
        lyricPanel?.visibility = if (show) View.GONE else View.VISIBLE
        if (show) {
            searchInput?.setText("")
            searchList?.adapter = null
        } else {
            // 从搜索切回歌词视图:按当前进度重新同步跟随(如拖动抑制窗口内跳过了更新)
            followCurrentLine()
        }
    }

    private fun doSearch() {
        val keyword = searchInput?.text?.toString()?.trim().orEmpty()
        if (keyword.isEmpty()) {
            Widgets.toast(requireContext(), UiText.t("请输入搜索关键词", "Please enter a search keyword"))
            return
        }
        searchList?.adapter = LyricCandidateAdapter(requireContext(), emptyList())
        LyricManager.manualSearch(keyword) { candidates, err ->
            if (!isAdded) return@manualSearch
            if (candidates.isEmpty() && err != null) {
                Widgets.toast(requireContext(), err)
            }
            searchList?.adapter = LyricCandidateAdapter(requireContext(), candidates)
        }
    }

    /** 手动绑定候选歌词(bind 成功后回歌词视图并刷新) */
    private fun bindLyric(candidate: LyricCandidate) {
        val song = NetMusic.player.current ?: return
        lyricEmptyText?.text = UiText.t("正在更新歌词…", "Updating lyrics…")
        lyricEmptyText?.visibility = View.VISIBLE
        LyricManager.bind(song, candidate) { result, err ->
            if (!isAdded) return@bind
            if (result.lines.isEmpty()) {
                Widgets.toast(requireContext(), err ?: UiText.t("该来源暂无歌词", "No lyrics from this source"))
                refreshDisplay()
                return@bind
            }
            offsetSec = result.offsetSec
            offsetLabel?.text = UiText.t("偏移 ${"%.1f".format(offsetSec)}s", "Offset ${"%.1f".format(offsetSec)}s")
            showLyrics(result.lines, result.from, null)
            toggleSearchMode() // 回歌词视图
            // 手动绑定新歌词成功:歌词已变更,通知 HUD 歌词缓存重载(回调在 UI 线程)
            HudLyricsCache.invalidate()
            Widgets.toast(requireContext(), UiText.t("歌词已更新", "Lyrics updated"))
        }
    }

    companion object {
        /** 用户触摸歌词列表后暂停自动跟随的时长(ms) */
        private const val FOLLOW_SUPPRESS_MS = 3000L

        fun newInstance(): LyricFragment = LyricFragment()
    }
}

/** 歌词候选行适配器:"标题 歌手 · 来源" */
private class LyricCandidateAdapter(
    context: Context,
    items: List<LyricCandidate>,
) : ArrayAdapter<LyricCandidate>(context, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val candidate = getItem(position)
        val row: LinearLayout = (convertView as? LinearLayout)?.also { it.removeAllViews() }
            ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(TextView(context).apply {
            text = candidate.title
            setTextSize(15f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        })
        column.addView(TextView(context).apply {
            text = "${candidate.artist} · ${LyricProviders.sourceLabel(candidate.source)}"
            setTextSize(12f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
        })
        row.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return row
    }
}

/**
 * 歌词行适配器(ArrayAdapter 自绘 getView 模式):
 * 每行 = 单行 TextView,水平居中;当前行(colorPrimary + 16sp + BOLD)与
 * 其余行(onSurfaceVariant + 12sp)仅由 [currentIndex] 区分,行垂直 padding 10px。
 */
private class LyricLineAdapter(
    context: Context,
    items: List<LyricLine>,
) : ArrayAdapter<LyricLine>(context, items) {

    /** 当前高亮行索引;由外部(LyricFragment 进度回调)在 UI 线程刷新 */
    @Volatile
    var currentIndex: Int = -1

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val line = getItem(position)
        val current = position == currentIndex
        val tv = convertView as? TextView ?: TextView(context)
        tv.text = line.text
        tv.gravity = Gravity.CENTER_HORIZONTAL
        tv.setSingleLine(true)
        tv.ellipsize = TextUtils.TruncateAt.END
        if (current) {
            tv.setTextSize(16f)
            tv.setTextStyle(Typeface.BOLD)
            tv.setTextColor(Widgets.resolveColor(context, R.attr.colorPrimary) ?: 0xFFBB86FC.toInt())
        } else {
            tv.setTextSize(12f)
            tv.setTextStyle(Typeface.NORMAL)
            tv.setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
        }
        tv.setPadding(tv.dp(8f), 10, tv.dp(8f), 10)
        return tv
    }
}