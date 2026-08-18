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
import icyllis.modernui.widget.Button
import icyllis.modernui.widget.LinearLayout
import icyllis.modernui.widget.ListView
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.player.PlayerListener
import io.github.cyf112233.musicmc.player.PlayerState

/**
 * 当前播放队列。顶部:循环模式按钮 + 清空队列;
 * 列表行前缀为 1-based 序号(当前播放行序号与标题均用 colorPrimary,由 SongListAdapter
 * showIndex 模式处理),点击行播放。
 */
class QueueFragment : Fragment() {

    private var modeButton: Button? = null
    private var listView: ListView? = null

    private val listener = object : PlayerListener {
        override fun onStateChanged(state: PlayerState, song: Song?) {
            refresh()
        }

        override fun onSongChanged(song: Song?) {
            refresh()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: DataSet?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        }

        modeButton = Button(context, null, R.attr.borderlessButtonStyle).apply {
            setOnClickListener {
                NetMusic.player.cycleMode()
                NetMusic.saveConfig()
                updateModeText()
            }
        }
        topBar.addView(modeButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val clearButton = Button(context, null, R.attr.borderlessButtonStyle).apply {
            text = "清空队列"
            setOnClickListener { NetMusic.player.clearQueue() }
        }
        topBar.addView(clearButton)
        root.addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        listView = ListView(context).apply {
            setOnItemClickListener { _, _, position, _ ->
                val songs = NetMusic.player.queue
                if (position in songs.indices) {
                    NetMusic.player.play(songs[position], songs.toList(), position)
                }
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        NetMusic.player.addListener(listener)
        refresh()
        return root
    }

    override fun onDestroyView() {
        NetMusic.player.removeListener(listener)
        super.onDestroyView()
    }

    private fun refresh() {
        // 防止 Fragment 已 detach 时(如回退栈弹出后残留的回调)requireContext 抛异常
        if (!isAdded) return
        val adapter = SongListAdapter(requireContext(), NetMusic.player.queue.toList())
        adapter.currentSongId = NetMusic.player.current?.id
        // 序号模式:行前缀显示 1-based 序号,当前播放行序号用 colorPrimary(适配器内实现)
        adapter.showIndex = true
        listView?.adapter = adapter
        updateModeText()
    }

    private fun updateModeText() {
        modeButton?.text = "循环模式: ${Widgets.playModeLabel(NetMusic.player.mode)}"
    }

    companion object {
        private const val KEY_CONTAINER = "containerId"

        fun newInstance(containerId: Int): QueueFragment {
            val f = QueueFragment()
            f.setArguments(DataSet().apply { put(KEY_CONTAINER, containerId) })
            return f
        }
    }
}
