package io.github.cyf112233.musicmc.ui

import icyllis.modernui.R
import icyllis.modernui.core.Context
import icyllis.modernui.fragment.Fragment
import icyllis.modernui.text.TextUtils
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
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.model.Song

/**
 * 收藏夹内容页([FavFoldersFragment] 点行进):收藏夹内视频列表(SongListAdapter,
 * medias→Song:id=bvid,title,artist=upper.name,picUrl=cover,durationMs=duration*1000),
 * 经 [BiliActions.folderSongs] 按页(ps=20)拼接加载直到取完(mediaCount)或页不满 / 页数上限,
 * 点击行播放整夹(与 PlaylistDetailFragment 同一模式:play(song, songs, position))。
 */
class FavFolderDetailFragment : Fragment() {

    private val containerId: Int get() = requireArguments().getInt(KEY_CONTAINER, 0)
    private val folder: FavFolder get() = requireArguments().get(KEY_FOLDER) as FavFolder

    private var statusText: TextView? = null
    private var listView: ListView? = null

    private val songs = mutableListOf<Song>()
    private var page = 1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: DataSet?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(
            TextView(context).apply {
                text = folder.title
                setTextAppearance(R.attr.textAppearanceTitleLarge)
                setPadding(dp(16f), dp(12f), dp(16f), dp(4f))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        root.addView(
            TextView(context).apply {
                text = UiText.t("${folder.mediaCount} 个内容", "${folder.mediaCount} items")
                setTextSize(13f)
                setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
                setPadding(dp(16f), 0, dp(16f), dp(8f))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        statusText = TextView(context).apply {
            text = UiText.t("加载中…", "Loading…")
            setTextSize(14f)
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
        }
        root.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        listView = ListView(context).apply {
            setOnItemClickListener { _, _, position, _ ->
                if (position in songs.indices) {
                    NetMusic.player.play(songs[position], songs.toList(), position)
                }
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        loadMore()
        return root
    }

    /**
     * 按页加载收藏夹内容并追加:每页 ps=20,直到某页不足一页 / 已收满 mediaCount /
     * 超过页数上限(防御:mediaCount 解析失败为 0 时首页满页即停)。回调在 UI 线程,逐页拼接。
     */
    private fun loadMore() {
        val currentPage = page
        BiliActions.folderSongs(folder.id, currentPage) { list, err ->
            if (!isAdded) return@folderSongs
            if (err != null) {
                if (songs.isEmpty()) {
                    statusText?.text = UiText.t("加载失败: $err", "Failed to load: $err")
                    statusText?.visibility = View.VISIBLE
                } else {
                    Widgets.toast(requireContext(), UiText.t("加载更多失败: $err", "Failed to load more: $err"))
                }
                return@folderSongs
            }
            songs += list
            page = currentPage + 1
            val done = list.size < PAGE_SIZE || songs.size >= folder.mediaCount || page > MAX_PAGES
            if (done) bind() else loadMore()
        }
    }

    private fun bind() {
        if (!isAdded) return
        if (songs.isEmpty()) {
            statusText?.text = UiText.t("收藏夹暂无内容", "Folder is empty")
            statusText?.visibility = View.VISIBLE
            return
        }
        statusText?.visibility = View.GONE
        listView?.adapter = SongListAdapter(requireContext(), songs.toList())
        listView?.visibility = View.VISIBLE
    }

    companion object {
        private const val KEY_FOLDER = "folder"
        private const val KEY_CONTAINER = "containerId"

        /** 单页大小(与 BiliHttp.favResourceList 的 ps=20 一致) */
        private const val PAGE_SIZE = 20

        /** 页数上限(防御异常数据;最多 20 页 × 20 = 400 条) */
        private const val MAX_PAGES = 20

        fun newInstance(folder: FavFolder, containerId: Int): FavFolderDetailFragment {
            val f = FavFolderDetailFragment()
            f.setArguments(DataSet().apply {
                put(KEY_FOLDER, folder)
                put(KEY_CONTAINER, containerId)
            })
            return f
        }
    }
}