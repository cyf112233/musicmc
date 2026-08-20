package io.github.cyf112233.musicmc.ui

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
import icyllis.modernui.widget.Toast
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.util.Async

/**
 * 搜索结果页。构造入参 keyword,异步搜索后展示 ListView;
 * 点击行 → player.play(song, results, index)。空结果显示"未找到相关歌曲"。
 */
class SearchFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: DataSet?): View {
        val context = requireContext()
        val keyword = requireArguments().getString(KEY_KEYWORD, "")

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val loading = TextView(context).apply {
            text = "Searching..."
            setTextSize(15f)
            gravity = Gravity.CENTER
        }
        root.addView(loading, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val emptyView = TextView(context).apply {
            text = "No related songs found"
            setTextSize(16f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(emptyView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val listView = ListView(context)
        listView.visibility = View.GONE
        root.addView(listView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        Async.run {
            NetMusic.source.search(keyword, 50, 0) { results, err ->
                Async.onUi {
                    loading.visibility = View.GONE
                    if (err != null) {
                        Toast.makeText(context, "Search failed: $err", Toast.LENGTH_SHORT).show()
                    } else if (results.isEmpty()) {
                        emptyView.visibility = View.VISIBLE
                    } else {
                        listView.visibility = View.VISIBLE
                        listView.adapter = SongListAdapter(context, results)
                        listView.setOnItemClickListener { _, _, position, _ ->
                            if (position in results.indices) {
                                NetMusic.player.play(results[position], results, position)
                            }
                        }
                    }
                }
            }
        }
        return root
    }

    companion object {
        private const val KEY_KEYWORD = "keyword"
        private const val KEY_CONTAINER = "containerId"

        fun newInstance(keyword: String, containerId: Int): SearchFragment {
            val f = SearchFragment()
            f.setArguments(DataSet().apply {
                put(KEY_KEYWORD, keyword)
                put(KEY_CONTAINER, containerId)
            })
            return f
        }
    }
}
