package io.github.cyf112233.musicmc.ui

import icyllis.modernui.R
import icyllis.modernui.core.Context
import icyllis.modernui.fragment.Fragment
import icyllis.modernui.graphics.drawable.ShapeDrawable
import icyllis.modernui.text.TextUtils
import icyllis.modernui.util.DataSet
import icyllis.modernui.view.LayoutInflater
import icyllis.modernui.view.View
import icyllis.modernui.view.ViewGroup
import icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT
import icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT
import icyllis.modernui.widget.FrameLayout
import icyllis.modernui.widget.GridLayout
import icyllis.modernui.widget.ImageView
import icyllis.modernui.widget.LinearLayout
import icyllis.modernui.widget.NestedScrollView
import icyllis.modernui.widget.TextView
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.model.Playlist
import io.github.cyf112233.musicmc.util.Async

/**
 * 首页(竖向滚动,对齐主流播放器):
 * 仅保留排行榜区(textAppearanceTitleLarge + colorOnSurface 标题,
 * homePlaylists 返回的 3 个 B 站榜单,3 列网格)。
 * 设置/歌词/收藏入口已随旧顶部工具行移除(导航统一归 MusicMainFragment 左侧栏),
 * 内容容器左右边距 16dp,每区块用 makeCard 包裹。
 */
class HomeFragment : Fragment() {

    private val containerId: Int get() = requireArguments().getInt(KEY_CONTAINER, 0)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: DataSet?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = NestedScrollView(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 页边距 16dp:卡片与屏幕边缘保持留白
            setPadding(dp(16f), dp(8f), dp(16f), dp(16f))
        }
        scroll.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        buildContent(context, content)
        return root
    }

    // ---------------- 内容构建 ----------------

    private fun buildContent(context: Context, content: LinearLayout) {
        content.addView(buildHomePlaylistsSection(context, UiText.t("排行榜", "Rankings")), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    // ---------------- 排行榜区 ----------------

    /** 排行榜区:makeCard 卡片内 = 标题(textAppearanceTitleLarge + colorOnSurface)+ 3 列网格 */
    private fun buildHomePlaylistsSection(context: Context, title: String): LinearLayout {
        val card = Widgets.makeCard(context)
        card.addView(
            TextView(context).apply {
                text = title
                setTextAppearance(R.attr.textAppearanceTitleLarge)
                setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurface) ?: 0xFFFFFFFF.toInt())
                setPadding(0, 0, 0, dp(4f))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        box.addView(buildLoadingPlaceholder(context))
        card.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        loadHomePlaylists(context, box)
        return card
    }

    // ---------------- 异步加载 ----------------

    private fun loadHomePlaylists(context: Context, box: LinearLayout) {
        NetMusic.source.homePlaylists { list, err ->
            Async.onUi {
                if (err != null) {
                    box.removeAllViews()
                    Widgets.toast(context, UiText.t("排行榜加载失败: $err", "Failed to load rankings: $err"))
                } else {
                    box.removeAllViews()
                    val grid = GridLayout(context).apply {
                        columnCount = 3
                        setPadding(Widgets.dp(context, 4), Widgets.dp(context, 8), Widgets.dp(context, 4), Widgets.dp(context, 4))
                    }
                    box.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    for (playlist in list) {
                        grid.addView(
                            buildCard(context, playlist),
                            GridLayout.LayoutParams().apply {
                                setMargins(Widgets.dp(context, 4), Widgets.dp(context, 4), Widgets.dp(context, 4), Widgets.dp(context, 4))
                            },
                        )
                    }
                }
            }
        }
    }

    // ---------------- 行构建 ----------------

    /** 排行卡片(区块卡片内,无嵌套背景):圆角封面 100dp + 榜单名 */
    private fun buildCard(context: Context, playlist: Playlist): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackground(ShapeDrawable().apply {
                setCornerRadius(Widgets.dp(context, 12).toFloat())
                setColor(Widgets.resolveColor(context, R.attr.colorSurfaceContainerHighest) ?: 0xFF333333.toInt())
            })
        }
        card.addView(image, LinearLayout.LayoutParams(Widgets.dp(context, 100), Widgets.dp(context, 100)))
        AsyncImageLoader.load(playlist.coverUrl, image)

        card.addView(
            TextView(context).apply {
                text = playlist.name
                setTextSize(13f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurface) ?: 0xFFFFFFFF.toInt())
                setPadding(0, dp(4f), 0, 0)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        card.setOnClickListener { openPlaylist(playlist) }
        return card
    }

    /** "加载中…"占位 TextView */
    private fun buildLoadingPlaceholder(context: Context): TextView =
        TextView(context).apply {
            text = UiText.t("加载中…", "Loading…")
            setTextSize(13f)
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }

    // ---------------- 操作 ----------------

    /**
     * 子页导航必须使用 [parentFragmentManager](即 MusicMainFragment 的 childFragmentManager):
     * HomeFragment 自身就是 MusicMainFragment 的 childFragmentManager 管理的子 Fragment,
     * 它的 childFragmentManager 作用域只覆盖 HomeFragment 自己的视图,里面没有宿主导航容器
     * (容器 id [containerId] 位于 MusicMainFragment 的视图里)。用 childFragmentManager 会抛
     * "IllegalArgumentException: No view found for id 0x..."。
     * 另外 [containerId] 缺失(== 0)时直接返回,避免 replace(0, ...) 抛同样的异常。
     */
    private fun openPlaylist(playlist: Playlist) {
        if (containerId == 0) return
        parentFragmentManager.beginTransaction()
            .replace(containerId, PlaylistDetailFragment.newInstance(playlist))
            .addToBackStack(null)
            .commit()
    }

    companion object {
        private const val KEY_CONTAINER = "containerId"

        fun newInstance(containerId: Int): HomeFragment {
            val f = HomeFragment()
            f.setArguments(DataSet().apply { put(KEY_CONTAINER, containerId) })
            return f
        }
    }
}