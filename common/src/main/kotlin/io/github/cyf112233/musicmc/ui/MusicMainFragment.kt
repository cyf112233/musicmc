package io.github.cyf112233.musicmc.ui

import icyllis.modernui.R
import icyllis.modernui.core.Context
import icyllis.modernui.fragment.Fragment
import icyllis.modernui.fragment.FragmentContainerView
import icyllis.modernui.graphics.drawable.ShapeDrawable
import icyllis.modernui.text.TextUtils
import icyllis.modernui.util.DataSet
import icyllis.modernui.view.Gravity
import icyllis.modernui.view.LayoutInflater
import icyllis.modernui.view.View
import icyllis.modernui.view.ViewGroup
import icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT
import icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT
import icyllis.modernui.widget.Button
import icyllis.modernui.widget.EditText
import icyllis.modernui.widget.ImageView
import icyllis.modernui.widget.LinearLayout
import icyllis.modernui.widget.SeekBar
import icyllis.modernui.widget.TextView
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.bilibili.BiliActions
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.player.PlayMode
import io.github.cyf112233.musicmc.player.PlayerListener
import io.github.cyf112233.musicmc.player.PlayerState

/**
 * 主界面(桌面播放器式):
 * 根布局 = 上部(左侧导航栏 + 内容区)+ 下部三行播放栏(全宽,不变)。
 * - 左侧导航栏:发现/收藏/队列/歌词/设置 五枚竖排项(桌面播放器式),当前项圆角底色;
 * - 内容区:顶部搜索行(搜索框 + 搜索按钮,一直可见)+ 子导航容器(weight 1);
 * - 底部三行播放栏(对齐主流播放器):
 *   第一行:封面(48dp 圆角)/ 标题/艺术家 / 点赞(♥/♡)/ 收藏(★/☆)/
 *     模式 / 上一首 / 圆形主播放按钮(▶/▮▮,48dp,ColorPrimary 底 + 白色 glyph)/
 *     下一首;
 *   第二行:当前时间 + 进度条(weight 1)+ 总时长。
 *   音量滑条(0-100)在第一行右侧(按钮压缩腾位,拖动实时生效 + 松手落盘)。
 *
 * 全部使用 icyllis.modernui.* 构建(无 net.minecraft 依赖),
 * 颜色从主题解析(Material3 token),图标用 Unicode 字符。
 */
class MusicMainFragment : Fragment() {

    private var containerId = 0

    private var searchInput: EditText? = null
    private var coverView: ImageView? = null
    private var titleText: TextView? = null
    private var artistText: TextView? = null
    private var modeButton: Button? = null
    private var playButton: Button? = null
    private var progressSeek: SeekBar? = null
    private var timeCurrentText: TextView? = null
    private var timeTotalText: TextView? = null
    private var volumeSeek: SeekBar? = null
    private var likeButton: Button? = null
    private var favButton: Button? = null

    /** 左侧导航栏按钮(按导航索引顺序;选中态刷新用) */
    private val navButtons = mutableListOf<Button>()

    /** 当前选中导航项索引(侧栏项;NO_NAV = 搜索结果等非侧栏页,不点亮任何项) */
    @Volatile
    private var currentNavIndex = NAV_HOME

    /** 用户正在拖动进度条时暂停刷新,避免抖动 */
    private var dragging = false

    private val listener = object : PlayerListener {
        override fun onStateChanged(state: PlayerState, song: Song?) {
            // 播放 ▶ / 暂停 ▮▮(几何区块字符,任何字体必有字形,不会 fallback 成 emoji
            // 彩色样式;⏸ U+23F8 在部分字体渲染下会变成彩色的"特立独行"样式)
            playButton?.text = if (state == PlayerState.PLAYING) "▮▮" else "▶"
            modeButton?.text = Widgets.playModeLabel(NetMusic.player.mode)
        }

        override fun onProgress(posMs: Int, durationMs: Int) {
            if (!dragging) progressSeek?.progress = posMs
            timeCurrentText?.text = Widgets.formatTime(posMs)
            timeTotalText?.text = Widgets.formatTime(durationMs)
        }

        override fun onSongChanged(song: Song?) {
            titleText?.text = song?.title ?: UiText.t("未在播放", "Not Playing")
            artistText?.text = song?.artist ?: ""
            progressSeek?.max = song?.durationMs ?: 0
            progressSeek?.progress = 0
            timeCurrentText?.text = "00:00"
            timeTotalText?.text = song?.let { Widgets.formatTime(it.durationMs) } ?: "00:00"
            coverView?.let { AsyncImageLoader.load(song?.picUrl, it) }
            // 点赞/收藏按钮状态:点赞态已登录时后台查 hasLiked(有内存缓存/5分钟级延迟),
            // 收藏态只读收藏夹缓存(初次为未知 → 空心;点 ★ 打开收藏夹选择器后由
            // BiliActions.refreshFavState/toggleFavInFolder 维护缓存,切歌时刷新显示)。
            likeButton?.text = "♡"
            favButton?.text = if (song != null && BiliActions.favedState(song.id) == true) "★" else "☆"
            val currentSong = song
            if (currentSong != null && NetMusic.bilibiliLoggedIn()) {
                BiliActions.refreshLike(currentSong) { liked, _ ->
                    if (isAdded) likeButton?.text = if (liked == true) "♥" else "♡"
                }
            }
        }

        override fun onToast(msg: String) {
            if (isAdded) Widgets.toast(requireContext(), msg)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: DataSet?): View {
        val context = requireContext()
        val compact = isCompactWindow(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 上部:左侧导航栏(固定宽)+ 内容区(搜索行 + 子导航容器)
        val upper = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        upper.addView(
            buildSidebar(context, compact),
            LinearLayout.LayoutParams(Widgets.dp(context, if (compact) 110 else 120), MATCH_PARENT),
        )

        val contentColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentColumn.addView(buildToolbar(context), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 中部子导航容器(childFragmentManager 管理子 Fragment 切换)
        val navContainer = FragmentContainerView(context).apply {
            id = View.generateViewId()
        }
        containerId = navContainer.id
        contentColumn.addView(navContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        upper.addView(contentColumn, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        root.addView(upper, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // 下部:底部两行播放栏(全宽,不变)
        root.addView(buildPlayerBar(context), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        childFragmentManager.beginTransaction()
            .replace(containerId, HomeFragment.newInstance(containerId))
            .commit()

        NetMusic.player.addListener(listener)
        // 立即同步一次当前状态
        listener.onStateChanged(NetMusic.player.state, NetMusic.player.current)
        listener.onSongChanged(NetMusic.player.current)

        return root
    }

    override fun onDestroyView() {
        NetMusic.player.removeListener(listener)
        super.onDestroyView()
    }

    // ---------------- 顶部工具栏 ----------------

    private fun buildToolbar(context: Context): LinearLayout {
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        }

        searchInput = buildSearchInput(context)
        toolbar.addView(searchInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val searchButton = Button(context, null, R.attr.buttonElevatedStyle).apply {
            text = UiText.t("搜索", "Search")
            setOnClickListener { search() }
            // 小窗口下避免 MUI horizontal constraints 不一致告警(按钮最小宽度过大所致)
            setMinimumWidth(dp(44f))
            setPadding(dp(10f), 0, dp(10f), 0)
        }
        toolbar.addView(searchButton)

        return toolbar
    }

    /**
     * 搜索输入框:weight 交给外层 LayoutParams,minWidth 160dp。
     * 滚轮处理:modernui 的 EditText 默认 MovementMethod(ArrowKeyMovementMethod)在
     * onGenericMotionEvent 里消费 ACTION_SCROLL 滚轮事件(滚动文本),单行搜索框不需要,
     * 此处子类化覆盖为"消费但不滚动",避免鼠标滚轮悬停在输入框上时误滚 / 与页面滚动打架。
     * (已 javap 核实:TextView 无 setScrollContainer;setVertical/HorizontalScrollBarEnabled
     * 仅控制滚动条绘制,不阻止滚轮;滚轮滚动路径是 MovementMethod.onGenericMotionEvent)
     */
    private fun buildSearchInput(context: Context): EditText = object : EditText(context) {
        override fun onGenericMotionEvent(event: icyllis.modernui.view.MotionEvent): Boolean {
            if (event.action == icyllis.modernui.view.MotionEvent.ACTION_SCROLL) {
                return true // 消费滚轮事件,禁止滚动搜索框文本
            }
            return super.onGenericMotionEvent(event)
        }
    }.apply {
        hint = UiText.t("搜索 B 站歌曲", "Search Bilibili songs")
        setTextSize(14f)
        setMinimumWidth(dp(160f))
        // 无 IME 管线:回车键直接触发搜索(统一走 Widgets.bindEnter)
        Widgets.bindEnter(this) { search() }
    }

    // ---------------- 左侧导航栏(桌面播放器式) ----------------

    /** 侧栏项(图标 + 文字;顺序即索引,与 [NAV_HOME] 等常量对应) */
    private data class NavItem(val label: String, val index: Int)

    private val navItems = listOf(
        NavItem(UiText.t("⌂ 发现", "⌂ Discover"), NAV_HOME),
        NavItem(UiText.t("★ 收藏", "★ Favorites"), NAV_FAV),
        NavItem(UiText.t("≡ 队列", "≡ Queue"), NAV_QUEUE),
        NavItem(UiText.t("♪ 歌词", "♪ Lyrics"), NAV_LYRIC),
        NavItem(UiText.t("⚙ 设置", "⚙ Settings"), NAV_SETTINGS),
    )

    /**
     * 侧栏:竖排五项,每项横向 borderless 按钮(左侧图标 + 文字,gravity start,
     * padding 12/10);小窗口用紧凑尺寸(110dp 宽 / 13sp / 44dp 行),正常 120dp / 14sp / 48dp。
     */
    private fun buildSidebar(context: Context, compact: Boolean): LinearLayout {
        navButtons.clear()
        val sidebar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
        }
        for (item in navItems) {
            val btn = Button(context, null, R.attr.borderlessButtonStyle).apply {
                text = item.label
                setTextSize(if (compact) 13f else 14f)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                setMinimumHeight(if (compact) dp(44f) else dp(48f))
                setOnClickListener { onNavClick(item.index) }
            }
            navButtons.add(btn)
            sidebar.addView(btn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        refreshNavStyles(context)
        return sidebar
    }

    /** 按 [currentNavIndex] 刷新全部侧栏项:选中项圆角底色 + onSecondaryContainer 文字,其余 onSurfaceVariant */
    private fun refreshNavStyles(context: Context) {
        val selBg = Widgets.resolveColor(context, R.attr.colorSecondaryContainer)
        val selFg = Widgets.resolveColor(context, R.attr.colorOnSecondaryContainer)
        val idleFg = Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant)
        for ((i, btn) in navButtons.withIndex()) {
            if (i == currentNavIndex) {
                btn.setBackground(ShapeDrawable().apply {
                    setCornerRadius(Widgets.dp(context, 8).toFloat())
                    selBg?.let { setColor(it) }
                })
                btn.setTextColor(selFg ?: 0xFFFFFFFF.toInt())
            } else {
                btn.setBackground(null)
                btn.setTextColor(idleFg ?: 0xFFAAAAAA.toInt())
            }
        }
    }

    /**
     * 点击侧栏项:守卫类动作(收藏/歌词)失败时仅 toast、保持当前页与选中态;
     * 成功后 replace 子导航容器(不加 backStack,侧栏切页如播放器)+ 刷新选中态。
     */
    private fun onNavClick(index: Int) {
        if (index == currentNavIndex) return
        val ok = when (index) {
            NAV_HOME -> { goHome(); true }
            NAV_FAV -> goFavFolders()
            NAV_QUEUE -> { goQueue(); true }
            NAV_LYRIC -> goLyric()
            NAV_SETTINGS -> { goSettings(); true }
            else -> false
        }
        if (ok) {
            currentNavIndex = index
            refreshNavStyles(requireContext())
        }
    }

    private fun goHome(): Boolean {
        if (containerId == 0) return false
        childFragmentManager.beginTransaction()
            .replace(containerId, HomeFragment.newInstance(containerId))
            .commit()
        return true
    }

    /** 我的收藏夹:未登录时 toast 并保持当前页(登录态在点击时检查,而非显示时) */
    private fun goFavFolders(): Boolean {
        if (!NetMusic.bilibiliLoggedIn()) {
            Widgets.toast(requireContext(), UiText.t("请先在设置中登录 B 站", "Please log in to Bilibili in Settings first"))
            return false
        }
        if (containerId == 0) return false
        childFragmentManager.beginTransaction()
            .replace(containerId, FavFoldersFragment.newInstance(containerId))
            .commit()
        return true
    }

    private fun goQueue(): Boolean {
        if (containerId == 0) return false
        childFragmentManager.beginTransaction()
            .replace(containerId, QueueFragment.newInstance(containerId))
            .commit()
        return true
    }

    /** 歌词:未开启时 toast 提示(守卫同旧 HomeFragment 工具行) */
    private fun goLyric(): Boolean {
        if (!NetMusic.config.lyricsEnabled) {
            Widgets.toast(requireContext(), UiText.t("歌词功能已关闭,请在设置中开启", "Lyrics are disabled, enable them in Settings"))
            return false
        }
        if (containerId == 0) return false
        childFragmentManager.beginTransaction()
            .replace(containerId, LyricFragment.newInstance())
            .commit()
        return true
    }

    private fun goSettings(): Boolean {
        if (containerId == 0) return false
        childFragmentManager.beginTransaction()
            .replace(containerId, SettingsFragment.newInstance(containerId))
            .commit()
        return true
    }

    /** 非侧栏页无选中项(如搜索结果页) */
    private fun selectNav(index: Int) {
        currentNavIndex = index
        refreshNavStyles(requireContext())
    }

    /**
     * 小窗口判定:MixinWindow.onSetGuiScale 把 window 宽写入 displayMetrics.widthPixels,
     * density = guiScale*0.5,故 widthDp = widthPixels/density ≈ 2×Minecraft 缩放 GUI 宽;
     * 小于阈值(约缩放宽 < 850)视为小窗口,用紧凑侧栏(110dp / 13sp / 44dp 行)。
     * 指标未就绪(widthPixels<=0)时按非紧凑处理。
     */
    private fun isCompactWindow(context: Context): Boolean {
        val dm = context.resources.displayMetrics
        // widthPixels<=0(指标未就绪)或 density<=0 时按非紧凑处理
        if (dm.widthPixels <= 0 || dm.density <= 0f) return false
        val widthDp = dm.widthPixels / dm.density
        return widthDp < SMALL_WINDOW_WIDTH_DP
    }

    // ---------------- 底部播放栏(两行) ----------------

    private fun buildPlayerBar(context: Context): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }
        bar.addView(buildPlayerRow1(context), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bar.addView(buildPlayerRow2(context), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return bar
    }

    /**
     * 第一行(对齐主流播放器):封面 48dp(圆角 ShapeDrawable 占位)→ 竖排信息列
     * (标题 14sp onSurface / 艺术家 12sp onSurfaceVariant,weight 1)
     * → ♥/♡ 36dp → ★/☆ 36dp → 模式按钮 44dp → ⏮ 44dp →
     * ▶/▮▮ 圆形主按钮 48dp(ShapeDrawable CIRCLE 底固定亮青蓝 0xFF4FC3F7、
     * 白色粗体 glyph、textSize 20sp)→ ⏭ 44dp。
     * 图标按钮统一 16sp 文字、colorPrimary(主题取色兜底)。
     */
    private fun buildPlayerRow1(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 封面 48dp,圆角 ShapeDrawable 占位(加载成功后 AsyncImageLoader 覆盖)。
        // FIT_CENTER:16:9 封面在 48dp 方框内完整显示(不裁剪),左右留白
        coverView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackground(ShapeDrawable().apply {
                setCornerRadius(Widgets.dp(context, 8).toFloat())
                setColor(Widgets.resolveColor(context, R.attr.colorSurfaceContainerHighest) ?: 0xFF444444.toInt())
            })
        }
        row.addView(coverView, LinearLayout.LayoutParams(Widgets.dp(context, 48), Widgets.dp(context, 48)))

        // 竖排:标题(14sp onSurface)+ 艺术家(12sp onSurfaceVariant),weight 1
        val infoColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleText = TextView(context).apply {
            text = UiText.t("未在播放", "Not Playing")
            setTextSize(14f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurface) ?: 0xFFFFFFFF.toInt())
        }
        infoColumn.addView(titleText)

        artistText = TextView(context).apply {
            text = ""
            setTextSize(12f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
        }
        infoColumn.addView(artistText)
        // 点击信息区域打开队列
        infoColumn.setOnClickListener { openQueue() }
        row.addView(infoColumn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            setMargins(Widgets.dp(context, 8), 0, Widgets.dp(context, 4), 0)
        })

        // 点赞(♥ 实心 / ♡ 空心)28dp(压缩腾位给音量条):图标按钮统一 16sp + colorPrimary
        likeButton = Button(context, null, R.attr.borderlessButtonStyle).apply {
            text = "♡"
            setTextSize(16f)
            setTextColor(Widgets.resolveColor(context, R.attr.colorPrimary) ?: 0xFFFFFFFF.toInt())
            setMinimumWidth(dp(28f))
            setPadding(0, 0, 0, 0)
            setOnClickListener { toggleLike() }
        }
        row.addView(likeButton)

        // 收藏(★ 实心 / ☆ 空心)28dp:点击打开收藏夹选择器
        favButton = Button(context, null, R.attr.borderlessButtonStyle).apply {
            text = "☆"
            setTextSize(16f)
            setTextColor(Widgets.resolveColor(context, R.attr.colorPrimary) ?: 0xFFFFFFFF.toInt())
            setMinimumWidth(dp(28f))
            setPadding(0, 0, 0, 0)
            setOnClickListener { openFavPicker() }
        }
        row.addView(favButton)

        // 播放模式按钮(文字循环显示)32dp
        modeButton = Button(context, null, R.attr.borderlessButtonStyle).apply {
            text = Widgets.playModeLabel(NetMusic.player.mode)
            setTextSize(16f)
            setTextColor(Widgets.resolveColor(context, R.attr.colorPrimary) ?: 0xFFFFFFFF.toInt())
            setMinimumWidth(dp(32f))
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                NetMusic.player.cycleMode()
                NetMusic.saveConfig()
            }
        }
        row.addView(modeButton)

        // 上一首 32dp
        val prevButton = Button(context, null, R.attr.borderlessButtonStyle).apply {
            text = "⏮"
            setTextSize(16f)
            setTextColor(Widgets.resolveColor(context, R.attr.colorPrimary) ?: 0xFFFFFFFF.toInt())
            setMinimumWidth(dp(32f))
            setPadding(0, 0, 0, 0)
            setOnClickListener { NetMusic.player.prev() }
        }
        row.addView(prevButton)

        // ▶/▮▮ 圆形主按钮 48dp:ShapeDrawable CIRCLE 底固定亮青蓝 0xFF4FC3F7
        // (主题 colorPrimary 在深色主题下偏暗,用户反馈"标准颜色太暗";字符用白色粗体)
        playButton = Button(context, null, R.attr.buttonElevatedStyle).apply {
            text = "▶"
            setBackground(ShapeDrawable().apply {
                setShape(ShapeDrawable.CIRCLE)
                setColor(0xFF4FC3F7.toInt())
            })
            setTextColor(0xFFFFFFFF.toInt())
            setTextStyle(icyllis.modernui.text.Typeface.BOLD)
            setTextSize(20f)
            setMinimumWidth(dp(48f))
            setMinimumHeight(dp(48f))
            setPadding(0, 0, 0, 0)
            setOnClickListener { NetMusic.player.toggle() }
        }
        row.addView(playButton)

        // 下一首 32dp
        val nextButton = Button(context, null, R.attr.borderlessButtonStyle).apply {
            text = "⏭"
            setTextSize(16f)
            setTextColor(Widgets.resolveColor(context, R.attr.colorPrimary) ?: 0xFFFFFFFF.toInt())
            setMinimumWidth(dp(32f))
            setPadding(0, 0, 0, 0)
            setOnClickListener { NetMusic.player.next() }
        }
        row.addView(nextButton)

        // 音量滑条(0-100):按钮压缩后置于行尾;拖动实时生效,松手落盘持久化
        volumeSeek = SeekBar(context).apply {
            max = 100
            progress = (NetMusic.config.volume * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    NetMusic.player.setVolume(progress / 100f)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    NetMusic.updateConfig { it.copy(volume = seekBar.progress / 100f) }
                }
            })
        }
        row.addView(volumeSeek, LinearLayout.LayoutParams(Widgets.dp(context, 110), WRAP_CONTENT).apply {
            setMargins(Widgets.dp(context, 4), 0, 0, 0)
        })

        return row
    }

    /**
     * 第二行:当前时间(12sp)+ 进度条(weight 1)+ 总时长(12sp)。
     * 音量滑条(0-100)在第一行右侧(见 [buildPlayerRow1]),进度条独占一行。
     */
    private fun buildPlayerRow2(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        timeCurrentText = TextView(context).apply {
            text = "00:00"
            setTextSize(12f)
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
        }
        row.addView(timeCurrentText)

        // 进度条(weight 1),拖动结束 seekTo
        progressSeek = SeekBar(context).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) dragging = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    dragging = false
                    NetMusic.player.seekTo(seekBar.progress)
                }
            })
        }
        row.addView(progressSeek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            setMargins(Widgets.dp(context, 8), 0, Widgets.dp(context, 8), 0)
        })

        timeTotalText = TextView(context).apply {
            text = "00:00"
            setTextSize(12f)
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
        }
        row.addView(timeTotalText)

        return row
    }

    // ---------------- 点赞 / 收藏 ----------------

    /** 点击点赞按钮:切换点赞态并更新图标 + Toast(回调在 UI 线程,BiliActions 已保证) */
    private fun toggleLike() {
        val song = NetMusic.player.current ?: return
        BiliActions.toggleLike(song) { liked, err ->
            likeButton?.text = if (liked) "♥" else "♡"
            val msg = if (err != null) err else if (liked) UiText.t("已点赞", "Liked") else UiText.t("已取消点赞", "Like removed")
            if (isAdded) Widgets.toast(requireContext(), msg)
        }
    }

    /** 点击收藏按钮:打开收藏夹选择器(浏览/选择/新建/取消;不再默认夹一键收藏) */
    private fun openFavPicker() {
        if (NetMusic.player.current == null) {
            Widgets.toast(requireContext(), UiText.t("请先播放歌曲", "Play a song first"))
            return
        }
        if (containerId == 0) return
        childFragmentManager.beginTransaction()
            .replace(containerId, FavPickerFragment.newInstance(containerId))
            .addToBackStack(null)
            .commit()
    }

    /** 从收藏夹选择器返回时刷新 ★/☆(收藏态缓存可能在选择器内被切换) */
    override fun onResume() {
        super.onResume()
        val song = NetMusic.player.current
        favButton?.text = if (song != null && BiliActions.favedState(song.id) == true) "★" else "☆"
    }

    // ---------------- 导航 ----------------

    private fun search() {
        val keyword = searchInput?.text?.toString()?.trim().orEmpty()
        if (keyword.isEmpty()) return
        if (containerId == 0) return
        childFragmentManager.beginTransaction()
            .replace(containerId, SearchFragment.newInstance(keyword, containerId))
            .commit()
        // 搜索结果页不属于任一侧栏项:清除选中态
        selectNav(NO_NAV)
    }

    /** 播放栏信息区点击:切到队列页(同步侧栏选中态,语义同侧栏"队列"项) */
    private fun openQueue() {
        onNavClick(NAV_QUEUE)
    }

    companion object {
        // 侧栏导航索引(与 [navItems] 顺序一致)
        private const val NAV_HOME = 0
        private const val NAV_FAV = 1
        private const val NAV_QUEUE = 2
        private const val NAV_LYRIC = 3
        private const val NAV_SETTINGS = 4

        /** 非侧栏页(如搜索结果)时无选中项 */
        private const val NO_NAV = -1

        /** 小窗口宽度阈值(dp,≈2×Minecraft 缩放 GUI 宽);低于则用紧凑侧栏 */
        private const val SMALL_WINDOW_WIDTH_DP = 1700f
    }
}
