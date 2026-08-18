package io.github.cyf112233.musicmc.ui

import icyllis.modernui.R
import icyllis.modernui.core.Context
import icyllis.modernui.fragment.Fragment
import icyllis.modernui.util.DataSet
import icyllis.modernui.view.Gravity
import icyllis.modernui.view.LayoutInflater
import icyllis.modernui.view.View
import icyllis.modernui.view.ViewGroup
import icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT
import icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT
import icyllis.modernui.widget.LinearLayout
import icyllis.modernui.widget.ListView
import icyllis.modernui.widget.TextView
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.bilibili.BiliActions
import io.github.cyf112233.musicmc.bilibili.FavFolder

/**
 * 我的收藏夹页(首页工具行"收藏"进入):全部收藏夹列表(名称 + "N 个内容"),
 * 点击行 → [FavFolderDetailFragment]。跨子页导航用 parentFragmentManager + containerId
 * (本 Fragment 由 MusicMainFragment 的 childFragmentManager 管理,容器在其视图里)。
 */
class FavFoldersFragment : Fragment() {

    private val containerId: Int get() = requireArguments().getInt(KEY_CONTAINER, 0)

    private var statusText: TextView? = null
    private var listView: ListView? = null

    private var folders = mutableListOf<FavFolder>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: DataSet?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(
            TextView(context).apply {
                text = "我的收藏夹"
                setTextAppearance(R.attr.textAppearanceTitleLarge)
                setPadding(dp(16f), dp(12f), dp(16f), dp(8f))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        statusText = TextView(context).apply {
            text = "加载中…"
            setTextSize(14f)
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
        }
        root.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        listView = ListView(context).apply {
            setOnItemClickListener { _, _, position, _ -> openDetail(position) }
        }
        root.addView(listView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        load()
        return root
    }

    /** 加载收藏夹列表;未登录(入口已隐藏,防御性守卫)显示提示 */
    private fun load() {
        if (!NetMusic.bilibiliLoggedIn()) {
            statusText?.text = "请先在设置中登录 B 站"
            statusText?.visibility = View.VISIBLE
            listView?.visibility = View.GONE
            return
        }
        statusText?.text = "加载中…"
        BiliActions.folders { list, err ->
            if (!isAdded) return@folders
            if (err != null) {
                statusText?.text = "加载失败: $err"
                statusText?.visibility = View.VISIBLE
                return@folders
            }
            folders = list.toMutableList()
            if (folders.isEmpty()) {
                statusText?.text = "暂无收藏夹"
                statusText?.visibility = View.VISIBLE
            } else {
                statusText?.visibility = View.GONE
            }
            listView?.adapter = FavFolderAdapter(requireContext(), folders, showCheck = false)
            listView?.visibility = View.VISIBLE
        }
    }

    /** 点击行 → 收藏夹内容页(跨子页导航用 parentFragmentManager + containerId) */
    private fun openDetail(position: Int) {
        if (containerId == 0) return
        val folder = folders.getOrNull(position) ?: return
        parentFragmentManager.beginTransaction()
            .replace(containerId, FavFolderDetailFragment.newInstance(folder, containerId))
            .addToBackStack(null)
            .commit()
    }

    companion object {
        private const val KEY_CONTAINER = "containerId"

        fun newInstance(containerId: Int): FavFoldersFragment {
            val f = FavFoldersFragment()
            f.setArguments(DataSet().apply { put(KEY_CONTAINER, containerId) })
            return f
        }
    }
}