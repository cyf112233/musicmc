package io.github.cyf112233.musicmc.ui

import icyllis.modernui.R
import icyllis.modernui.core.Context
import icyllis.modernui.fragment.Fragment
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
import icyllis.modernui.widget.ImageView
import icyllis.modernui.widget.LinearLayout
import icyllis.modernui.widget.ListView
import icyllis.modernui.widget.TextView
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.model.Playlist
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.util.Async

/**
 * 歌单详情(主流播放器直觉):顶部 makeCard 头部卡片 = 封面 96dp +
 * 竖排(歌单名 18sp / N 首 12sp)+ "▶ 播放全部" elevated 按钮
 * (点击 NetMusic.player.play(songs[0], songs, 0) 整单播放);下方歌曲列表,
 * 点击行播放整单。懒加载逻辑保留(空 songs 时调 playlistDetail)。
 */
class PlaylistDetailFragment : Fragment() {

    /** 当前持有的歌曲列表(懒加载完成后更新),"播放全部"以它为准 */
    private var songs: List<Song> = emptyList()

    private var songCountText: TextView? = null
    private var playAllButton: Button? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: DataSet?): View {
        val playlist = requireArguments().get("playlist") as Playlist
        val context = requireContext()
        songs = playlist.songs

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), 0)
        }

        // ---- 头部卡片(makeCard):封面 + 名称/数量 + 播放全部 ----
        val headerCard = Widgets.makeCard(context)
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackground(ShapeDrawable().apply {
                setCornerRadius(Widgets.dp(context, 12).toFloat())
                setColor(Widgets.resolveColor(context, R.attr.colorSurfaceContainerHighest) ?: 0xFF444444.toInt())
            })
        }
        headerRow.addView(cover, LinearLayout.LayoutParams(Widgets.dp(context, 96), Widgets.dp(context, 96)))
        AsyncImageLoader.load(playlist.coverUrl, cover)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(TextView(context).apply {
            text = playlist.name
            setTextSize(18f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurface) ?: 0xFFFFFFFF.toInt())
        })
        songCountText = TextView(context).apply {
            text = if (songs.isEmpty()) "加载中…" else "${songs.size} 首"
            setTextSize(12f)
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
            setPadding(0, dp(2f), 0, 0)
        }
        column.addView(songCountText)
        headerRow.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            setMargins(Widgets.dp(context, 12), 0, 0, 0)
        })
        headerCard.addView(headerRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // "▶ 播放全部" elevated 按钮:整单播放(懒加载完成前禁用)
        playAllButton = Button(context, null, R.attr.buttonElevatedStyle).apply {
            text = "▶ 播放全部"
            isEnabled = songs.isNotEmpty()
            setOnClickListener {
                val s = this@PlaylistDetailFragment.songs
                if (s.isNotEmpty()) NetMusic.player.play(s[0], s, 0)
            }
        }
        headerCard.addView(playAllButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = Widgets.dp(context, 10)
        })
        root.addView(headerCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val listView = ListView(context)
        root.addView(listView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        if (playlist.songs.isEmpty()) {
            // 只有歌单信息(从首页推荐/我的歌单进入):先显示"加载中…",异步拉取详情后重建列表
            val loading = TextView(context).apply {
                text = "加载中…"
                setTextSize(15f)
                gravity = Gravity.CENTER
                setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
            }
            root.addView(loading, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            listView.visibility = View.GONE

            NetMusic.source.playlistDetail(playlist.id) { detail, err ->
                Async.onUi {
                    // 视图生命周期保护:Fragment 已 detach 或视图已销毁时不再更新
                    if (!isAdded || view == null) return@onUi
                    loading.visibility = View.GONE
                    if (err != null) {
                        loading.text = "加载失败: $err"
                        loading.visibility = View.VISIBLE
                    } else if (detail.songs.isEmpty()) {
                        loading.text = "歌单为空"
                        loading.visibility = View.VISIBLE
                    } else {
                        songs = detail.songs
                        songCountText?.text = "${detail.songs.size} 首"
                        playAllButton?.isEnabled = true
                        bindSongs(listView, detail.songs)
                        listView.visibility = View.VISIBLE
                    }
                }
            }
        } else {
            bindSongs(listView, playlist.songs)
        }

        return root
    }

    /** 用歌曲列表填充 ListView(adapter + 点击行播放整单) */
    private fun bindSongs(listView: ListView, songs: List<Song>) {
        listView.adapter = SongListAdapter(listView.context, songs)
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in songs.indices) {
                NetMusic.player.play(songs[position], songs, position)
            }
        }
    }

    companion object {
        private const val KEY_PLAYLIST = "playlist"

        fun newInstance(playlist: Playlist): PlaylistDetailFragment {
            val f = PlaylistDetailFragment()
            f.setArguments(DataSet().apply { put(KEY_PLAYLIST, playlist) })
            return f
        }
    }
}