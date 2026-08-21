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
import icyllis.modernui.widget.ArrayAdapter
import icyllis.modernui.widget.Button
import icyllis.modernui.widget.EditText
import icyllis.modernui.widget.LinearLayout
import icyllis.modernui.widget.ListView
import icyllis.modernui.widget.TextView
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.bilibili.BiliActions
import io.github.cyf112233.musicmc.bilibili.FavFolder
import io.github.cyf112233.musicmc.client.UiText

/**
 * 收藏夹选择器(点击主页 ★ 打开;可浏览 / 选择 / 新建 / 取消收藏):
 * - 打开时加载全部收藏夹(BiliActions.folders)+ 刷新当前歌曲的收藏状态缓存(BiliActions.refreshFavState);
 * - 每行:夹名 + "N 个内容" + 右侧 ✓(当前歌曲在其中,colorPrimary);
 * - 点击行:BiliActions.toggleFavInFolder 切换该夹收藏态,Toast("已收藏到 X"/"已从 X 移除"),绑定刷新该行;
 * - 底部"新建收藏夹":展开 EditText + 确定/取消 面板,createFolder 成功后刷新列表;
 * - 未登录显示"请先在设置中登录 B 站"(列表 / 新建入口隐藏)。
 * ModernUI 无 AlertDialog,新建输入用底部内联面板实现。
 */
class FavPickerFragment : Fragment() {

    private val containerId: Int get() = requireArguments().getInt(KEY_CONTAINER, 0)

    private var statusText: TextView? = null
    private var listView: ListView? = null
    private var createPanel: LinearLayout? = null
    private var createInput: EditText? = null

    private var folders = mutableListOf<FavFolder>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: DataSet?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(
            TextView(context).apply {
                text = UiText.t("选择收藏夹", "Select Folder")
                setTextAppearance(R.attr.textAppearanceTitleLarge)
                setPadding(dp(16f), dp(12f), dp(16f), dp(4f))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        root.addView(
            TextView(context).apply {
                text = NetMusic.player.current?.let { UiText.t("当前歌曲: ${it.title}", "Current song: ${it.title}") } ?: UiText.t("请先播放歌曲", "Play a song first")
                setTextSize(13f)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
                setPadding(dp(16f), 0, dp(16f), dp(4f))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        statusText = TextView(context).apply {
            text = UiText.t("加载中…", "Loading…")
            setTextSize(14f)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
        }
        root.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        listView = ListView(context).apply {
            setOnItemClickListener { _, _, position, _ -> onFolderClick(position) }
        }
        root.addView(listView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // 新建收藏夹输入面板(底部内联;ModernUI 无 AlertDialog)
        createPanel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            visibility = View.GONE
        }.also { panel ->
            createInput = EditText(context).apply {
                hint = UiText.t("收藏夹名称", "Folder name")
                setTextSize(14f)
                // Enter = 确定(无 IME 管线,直接按键触发)
                Widgets.bindEnter(this) { submitCreate() }
            }
            panel.addView(createInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            panel.addView(
                Button(context, null, R.attr.buttonElevatedStyle).apply {
                    text = UiText.t("确定", "OK")
                    setOnClickListener { submitCreate() }
                },
            )
            panel.addView(
                Button(context, null, R.attr.borderlessButtonStyle).apply {
                    text = UiText.t("取消", "Cancel")
                    setOnClickListener { hideCreatePanel() }
                },
            )
        }
        root.addView(createPanel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(
            Button(context, null, R.attr.buttonElevatedStyle).apply {
                text = UiText.t("新建收藏夹", "New Folder")
                setOnClickListener { showCreatePanel() }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = Widgets.dp(context, 8)
                bottomMargin = Widgets.dp(context, 16)
            },
        )

        load()
        return root
    }

    /**
     * 加载收藏夹列表 + 刷新当前歌曲收藏状态缓存。
     * 未登录只显示提示(列表 / 新建入口隐藏)。
     */
    private fun load() {
        if (!NetMusic.bilibiliLoggedIn()) {
            statusText?.text = UiText.t("请先在设置中登录 B 站", "Please log in to Bilibili in Settings first")
            statusText?.visibility = View.VISIBLE
            listView?.visibility = View.GONE
            return
        }
        statusText?.text = UiText.t("加载中…", "Loading…")
        statusText?.visibility = View.VISIBLE
        listView?.visibility = View.VISIBLE
        // 刷新缓存(填充 folderFavs,供每行 ✓ 标记);完成后重新绑定列表
        val song = NetMusic.player.current
        if (song != null) {
            BiliActions.refreshFavState(song) { _ ->
                if (isAdded) bindFolders()
            }
        }
        BiliActions.folders { list, err ->
            if (!isAdded) return@folders
            if (err != null) {
                statusText?.text = UiText.t("加载失败: $err", "Failed to load: $err")
                statusText?.visibility = View.VISIBLE
                return@folders
            }
            folders = list.toMutableList()
            bindFolders()
        }
    }

    /** 用当前 [folders] 重建列表(右侧 ✓ 取收藏状态缓存) */
    private fun bindFolders() {
        if (!isAdded) return
        val context = requireContext()
        if (folders.isEmpty()) {
            statusText?.text = UiText.t("暂无收藏夹,点击下方按钮新建", "No favorites yet, click the button below to create one")
            statusText?.visibility = View.VISIBLE
        } else {
            statusText?.visibility = View.GONE
        }
        val showCheck = NetMusic.player.current != null
        val adapter = FavFolderAdapter(context, folders, showCheck = true)
        adapter.checkFids = if (showCheck) BiliActions.favFoldersOf(NetMusic.player.current!!.id) else emptySet()
        listView?.adapter = adapter
    }

    /** 点击行:切换当前歌曲在该夹的收藏态,刷新该行显示 + Toast */
    private fun onFolderClick(position: Int) {
        val song = NetMusic.player.current
        if (song == null) {
            Widgets.toast(requireContext(), UiText.t("请先播放歌曲", "Play a song first"))
            return
        }
        val folder = folders.getOrNull(position) ?: return
        BiliActions.toggleFavInFolder(song, folder) { faved, err ->
            if (!isAdded) return@toggleFavInFolder
            if (err != null) {
                Widgets.toast(requireContext(), err)
                return@toggleFavInFolder
            }
            folders[position] = folder.copy(favState = faved)
            bindFolders()
            Widgets.toast(
                requireContext(),
                if (faved) UiText.t("已收藏到 ${folder.title}", "Added to favorites: ${folder.title}") else UiText.t("已从 ${folder.title} 移除", "Removed from ${folder.title}"),
            )
        }
    }

    // ---------------- 新建收藏夹 ----------------

    private fun showCreatePanel() {
        if (!NetMusic.bilibiliLoggedIn()) {
            Widgets.toast(requireContext(), UiText.t("请先在设置中登录 B 站", "Please log in to Bilibili in Settings first"))
            return
        }
        createPanel?.visibility = View.VISIBLE
    }

    private fun hideCreatePanel() {
        createPanel?.visibility = View.GONE
        createInput?.setText("")
    }

    private fun submitCreate() {
        val title = createInput?.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            Widgets.toast(requireContext(), UiText.t("请输入收藏夹名称", "Please enter a folder name"))
            return
        }
        BiliActions.createFolder(title) { _, err ->
            if (!isAdded) return@createFolder
            if (err != null) {
                Widgets.toast(requireContext(), err)
                return@createFolder
            }
            Widgets.toast(requireContext(), UiText.t("收藏夹已创建", "Folder created"))
            hideCreatePanel()
            load()
        }
    }

    companion object {
        private const val KEY_CONTAINER = "containerId"

        fun newInstance(containerId: Int): FavPickerFragment {
            val f = FavPickerFragment()
            f.setArguments(DataSet().apply { put(KEY_CONTAINER, containerId) })
            return f
        }
    }
}

/**
 * 收藏夹列表适配器:每行 = 竖排(夹名 15sp / "N 个内容" 12sp onSurfaceVariant)(weight 1)
 * + 可选的右侧 ✓(showCheck=true 时,当前歌曲在该夹用 colorPrimary 着色)。
 */
class FavFolderAdapter(
    context: Context,
    items: List<FavFolder>,
    private val showCheck: Boolean,
) : ArrayAdapter<FavFolder>(context, items) {

    /** 当前歌曲所在的收藏夹 id 集合(✓ 标记只认该缓存,与行内 favState 字段无关) */
    var checkFids: Set<Long> = emptySet()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = getItem(position)

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
            text = item.title
            setTextSize(15f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        })
        column.addView(TextView(context).apply {
            text = UiText.t("${item.mediaCount} 个内容", "${item.mediaCount} items")
            setTextSize(12f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
        })
        row.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, Widgets.dp(context, 8), 0)
        })

        if (showCheck) {
            val checked = item.id in checkFids
            row.addView(TextView(context).apply {
                text = if (checked) "✓" else " "
                setTextSize(16f)
                gravity = Gravity.CENTER
                setMinimumWidth(dp(28f))
                setTextColor(Widgets.resolveColor(context, R.attr.colorPrimary) ?: 0xFFFFFFFF.toInt())
            })
        }
        return row
    }
}