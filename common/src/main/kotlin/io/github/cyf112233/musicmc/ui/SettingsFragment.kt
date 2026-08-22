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
import icyllis.modernui.widget.EditText
import icyllis.modernui.widget.FrameLayout
import icyllis.modernui.widget.LinearLayout
import icyllis.modernui.widget.NestedScrollView
import icyllis.modernui.widget.SeekBar
import icyllis.modernui.widget.Switch
import icyllis.modernui.widget.TextView
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.player.AudioCache
import io.github.cyf112233.musicmc.platform.PlatformHolder
import io.github.cyf112233.musicmc.util.Async

/**
 * 设置页:音源信息 + 歌词相关开关(总开关 / 标题匹配 / 聊天栏歌词)+
 * 歌词 Hub 地址(自建服务,协议见 hub/README.md)+ 音频缓存(占用 / 清除)+
 * 游戏内 HUD(总开关 / HUD 歌词 / 封面形状 / 转速 / 缩放 / 重置位置)+ B 站账号(扫码登录 / 退出登录)+ 返回按钮。
 * 根布局为竖排 LinearLayout:内容列(NestedScrollView,weight 1,全部设置区可滚动)+
 * 底部固定"返回"按钮。
 *
 * 跨子页导航注意:SettingsFragment 由 MusicMainFragment 的 childFragmentManager 管理,
 * 打开 BilibiliLoginFragment 必须用 [parentFragmentManager] 与传入的 [containerId]
 * (照 HomeFragment 的既有正确写法;containerId 指向 MusicMainFragment 视图里的导航容器)。
 *
 * Switch 可用性(javap 已核实 modernui-core-3.13.0):
 * icyllis.modernui.widget.Switch 存在,构造 (Context, AttributeSet, ResourceId)
 * 可用 R.attr.switchStyle;setOnCheckedChangeListener(Checkable.OnCheckedChangeListener)
 * 的签名是 onCheckedChanged(View, boolean)。
 */
class SettingsFragment : Fragment() {

    private val containerId: Int get() = requireArguments().getInt(KEY_CONTAINER, 0)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: DataSet?): View {
        val context = requireContext()

        // 根布局:竖排 —— 内容列(NestedScrollView,weight 1,可滚动)+ 底部固定"返回"按钮
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = NestedScrollView(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
        }
        scroll.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        content.addView(
            TextView(context).apply {
                text = UiText.t("设置", "Settings")
                setTextAppearance(R.attr.textAppearanceTitleLarge)
                setPadding(0, 0, 0, dp(16f))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        // 音源信息(唯一音源,不可切换)
        content.addView(buildInfoRow(context, UiText.t("音源", "Source"), UiText.t("B 站(唯一音源)", "Bilibili (the only source)")), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 歌词显示总开关(默认关;开启后播放页可查看歌词)
        val lyricsSwitch = Switch(context, null, R.attr.switchStyle).apply {
            isChecked = NetMusic.config.lyricsEnabled
        }
        // 标题匹配开关(默认关;仅总开关开启时生效,故初始可用性跟随总开关)
        val fallbackSwitch = Switch(context, null, R.attr.switchStyle).apply {
            isChecked = NetMusic.config.lyricTitleFallback
            isEnabled = NetMusic.config.lyricsEnabled
        }
        // 聊天栏歌词开关(默认关;是歌词功能的显示层开关,总开关关闭时不可用)
        val chatLyricSwitch = Switch(context, null, R.attr.switchStyle).apply {
            isChecked = NetMusic.config.chatLyricEnabled
            isEnabled = NetMusic.config.lyricsEnabled
        }
        // HUD 歌词显示开关(独立于"歌词显示"总开关:关掉只隐藏 HUD 歌词块;
        // 声明在歌词区以便总开关联动可用性,UI 行渲染在下方 HUD 区)
        val hudLyricSwitch = Switch(context, null, R.attr.switchStyle).apply {
            isChecked = NetMusic.config.hudLyricEnabled
            isEnabled = NetMusic.config.lyricsEnabled
        }
        lyricsSwitch.setOnCheckedChangeListener { _, checked ->
            // 更新配置并落盘;NetMusic.updateConfig 内部同步 player 引用并保存
            NetMusic.updateConfig { it.copy(lyricsEnabled = checked) }
            // 子开关仅在总开关开启时可用(依赖关系可视化)
            fallbackSwitch.isEnabled = checked
            chatLyricSwitch.isEnabled = checked
            hudLyricSwitch.isEnabled = checked
        }
        fallbackSwitch.setOnCheckedChangeListener { _, checked ->
            NetMusic.updateConfig { it.copy(lyricTitleFallback = checked) }
        }
        chatLyricSwitch.setOnCheckedChangeListener { _, checked ->
            NetMusic.updateConfig { it.copy(chatLyricEnabled = checked) }
        }
        hudLyricSwitch.setOnCheckedChangeListener { _, checked ->
            NetMusic.updateConfig { it.copy(hudLyricEnabled = checked) }
        }

        content.addView(
            buildSwitchRow(context, UiText.t("显示歌词(优先 CC 字幕)", "Show Lyrics (CC subtitles first)"), UiText.t("歌词总开关:控制播放页 / HUD / 聊天栏的所有歌词", "Master switch for lyrics: controls all lyrics on the player page / HUD / chat"), lyricsSwitch),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        content.addView(
            buildSwitchRow(context, UiText.t("无 CC 字幕时按标题匹配歌词", "Match lyrics by title when no CC subtitles"), UiText.t("网易云 → QQ 音乐 → 酷狗:三源自动匹配,仅作歌词来源", "NetEase Cloud → QQ Music → Kugou: auto-match from three sources, lyrics source only"), fallbackSwitch),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        content.addView(
            buildSwitchRow(context, UiText.t("聊天栏歌词", "Chat Lyrics"), UiText.t("将每句歌词输出到玩家聊天栏(♪ 前缀,需开启总开关)", "Output each lyric line to the player chat (♪ prefix, requires the master switch)"), chatLyricSwitch),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        // ---- 歌词 Hub 地址(自建服务同步) ----
        val hubSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        }
        hubSection.addView(
            TextView(context).apply {
                text = UiText.t("歌词 Hub 地址", "Lyrics Hub URL")
                setTextSize(15f)
            },
        )
        hubSection.addView(
            TextView(context).apply {
                text = UiText.t("自建歌词同步服务(http://host:8787,留空禁用;见 hub/README.md)", "Self-hosted lyrics sync service (http://host:8787, leave empty to disable; see hub/README.md)")
                setTextSize(12f)
                setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
            },
        )
        val hubRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, 0)
        }
        val hubInput = EditText(context).apply {
            setText(NetMusic.config.hubUrl)
            hint = "http://192.168.1.100:8787"
            setTextSize(14f)
            // Enter = 保存(无 IME 管线,直接按键触发)
            Widgets.bindEnter(this) { saveHubUrl(this) }
        }
        hubRow.addView(hubInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        hubRow.addView(
            Button(context, null, R.attr.buttonElevatedStyle).apply {
                text = UiText.t("保存", "Save")
                setOnClickListener { saveHubUrl(hubInput) }
            },
        )
        hubSection.addView(hubRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(hubSection, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- 音频缓存区(播放过的歌边播边落盘,下次本地播放;显示占用 + 一键清除) ----
        val cacheSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        }
        cacheSection.addView(
            TextView(context).apply {
                text = UiText.t("音频缓存", "Audio Cache")
                setTextSize(15f)
            },
        )
        cacheSection.addView(
            TextView(context).apply {
                text = UiText.t("播放过的歌曲会本地缓存,下次从本地播放(支持离线)", "Played songs are cached locally and play from local storage next time (works offline)")
                setTextSize(12f)
                setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
            },
        )
        val cacheSizeText = TextView(context).apply {
            text = UiText.t("占用: ${formatCacheBytes(AudioCache.totalSize())}", "Usage: ${formatCacheBytes(AudioCache.totalSize())}")
            setTextSize(13f)
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
            setPadding(0, dp(6f), 0, dp(6f))
        }
        cacheSection.addView(cacheSizeText)
        cacheSection.addView(
            Button(context, null, R.attr.buttonElevatedStyle).apply {
                text = UiText.t("清除缓存", "Clear Cache")
                setOnClickListener {
                    val freed = AudioCache.clear()
                    cacheSizeText.text = UiText.t("占用: ${formatCacheBytes(AudioCache.totalSize())}", "Usage: ${formatCacheBytes(AudioCache.totalSize())}")
                    Widgets.toast(context, UiText.t("缓存已清除(${formatCacheBytes(freed)})", "Cache cleared (${formatCacheBytes(freed)})"))
                }
            },
        )
        content.addView(cacheSection, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- 游戏内 HUD 区 ----
        val hudSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        }
        hudSection.addView(
            TextView(context).apply {
                text = UiText.t("游戏内 HUD", "In-Game HUD")
                setTextSize(15f)
            },
        )
        hudSection.addView(
            TextView(context).apply {
                text = UiText.t("HUD 位置 / 形状可在编辑器中拖拽实时预览;完成后自动保存", "HUD position / shape can be dragged and previewed live in the editor; saved automatically when done")
                setTextSize(12f)
                setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
            },
        )

        // 打开 HUD 编辑器(独立 MUI 屏幕,loader 侧 setScreen 打开)
        hudSection.addView(
            Button(context, null, R.attr.buttonElevatedStyle).apply {
                text = UiText.t("打开 HUD 编辑器(拖拽调整位置)", "Open HUD Editor (drag to adjust position)")
                setOnClickListener {
                    PlatformHolder.require().openHudEditor()
                }
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        // HUD 总开关
        val hudSwitch = Switch(context, null, R.attr.switchStyle).apply {
            isChecked = NetMusic.config.hudEnabled
        }
        hudSwitch.setOnCheckedChangeListener { _, checked ->
            NetMusic.updateConfig { it.copy(hudEnabled = checked) }
        }
        hudSection.addView(
            buildSwitchRow(context, UiText.t("游戏内显示 HUD", "Show HUD In Game"), UiText.t("游戏内悬浮封面 / 歌词 / 进度条", "Floating cover / lyrics / progress in game"), hudSwitch),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        // HUD 歌词显示开关(声明在歌词区,总开关联动可用性;此处仅渲染行)
        hudSection.addView(
            buildSwitchRow(context, UiText.t("显示 HUD 歌词", "Show HUD Lyrics"), UiText.t("在游戏内悬浮面板显示当前歌词(独立开关;不影响播放页)", "Show current lyrics in the in-game floating panel (independent switch; does not affect the player page)"), hudLyricSwitch),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        // HUD 整体缩放(50..200% → /100f)
        val scaleText = TextView(context).apply {
            setTextSize(13f)
            setPadding(0, dp(6f), 0, 0)
            text = UiText.t("HUD 缩放: ${(NetMusic.config.hudScale * 100).toInt()}%", "HUD Scale: ${(NetMusic.config.hudScale * 100).toInt()}%")
        }
        hudSection.addView(scaleText)
        val scaleSeek = SeekBar(context).apply {
            max = 150
            progress = ((NetMusic.config.hudScale * 100).toInt() - 50).coerceIn(0, 150)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                // 实时性修复:去掉 fromUser 门控,拖动中实时 updateConfig(幂等无害)
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    scaleText.text = UiText.t("HUD 缩放: ${progress + 50}%", "HUD Scale: ${progress + 50}%")
                    NetMusic.updateConfig { it.copy(hudScale = (progress + 50) / 100f) }
                }

                // 拖动结束兜底落盘(与 onProgressChanged 幂等)
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    NetMusic.updateConfig { it.copy(hudScale = (seekBar.progress + 50) / 100f) }
                }
            })
        }
        hudSection.addView(scaleSeek, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 重置 HUD 位置(默认锚点右下)
        hudSection.addView(
            Button(context, null, R.attr.buttonElevatedStyle).apply {
                text = UiText.t("重置 HUD 位置", "Reset HUD Position")
                setOnClickListener {
                    NetMusic.updateConfig { it.copy(hudX = 0.92f, hudY = 0.86f) }
                    Widgets.toast(context, UiText.t("HUD 位置已重置", "HUD position reset"))
                }
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        content.addView(hudSection, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- B 站账号区 ----
        val accountSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        }
        accountSection.addView(
            TextView(context).apply {
                text = UiText.t("B 站账号", "Bilibili Account")
                setTextSize(15f)
            },
        )
        accountSection.addView(
            TextView(context).apply {
                text = UiText.t("登录后支持个性化搜索并降低风控;流媒体优先提升(杜比 / flac)", "Logging in enables personalized search and lowers risk control; stream priority boost (dolby/flac)")
                setTextSize(12f)
                setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
            },
        )
        content.addView(accountSection, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        buildAccountSection(context, accountSection)

        // 底部固定返回按钮(在 scroll 之外,始终可见)
        root.addView(
            Button(context, null, R.attr.buttonElevatedStyle).apply {
                text = UiText.t("返回", "Back")
                setOnClickListener {
                    if (parentFragmentManager.backStackEntryCount > 0) {
                        runCatching { parentFragmentManager.popBackStack() }
                    } else if (containerId != 0) {
                        // 无 backstack(从侧栏直接 replace 进入):回退到首页,避免"返回"死键
                        parentFragmentManager.beginTransaction()
                            .replace(containerId, HomeFragment.newInstance(containerId))
                            .commit()
                    }
                }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = Widgets.dp(context, 16)
                bottomMargin = Widgets.dp(context, 16)
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

        return root
    }

    /** 保存 Hub 地址(按钮与输入框回车共用) */
    private fun saveHubUrl(input: EditText) {
        val url = input.text?.toString()?.trim().orEmpty()
        NetMusic.updateConfig { it.copy(hubUrl = url) }
        Widgets.toast(requireContext(), UiText.t("Hub 地址已保存", "Hub URL saved"))
    }

    /** B 站账号区内容(登录 / 未登录两种形态,退出登录后原地重建) */
    private fun buildAccountSection(context: Context, box: LinearLayout) {
        box.removeAllViews()
        if (NetMusic.bilibiliLoggedIn()) {
            val nameText = TextView(context).apply {
                text = UiText.t("正在获取昵称…", "Fetching nickname…")
                setTextSize(14f)
                setPadding(0, dp(4f), 0, dp(4f))
            }
            box.addView(nameText)
            Async.run {
                val nick = NetMusic.bilibiliNickname()
                Async.onUi {
                    if (!isAdded) return@onUi
                    nameText.text = if (nick.isNullOrBlank()) UiText.t("已登录(昵称获取失败)", "Logged in (failed to fetch nickname)") else UiText.t("已登录: $nick", "Logged in: $nick")
                }
            }
            box.addView(
                Button(context, null, R.attr.buttonElevatedStyle).apply {
                    text = UiText.t("退出登录", "Log Out")
                    setOnClickListener {
                        NetMusic.setBilibiliCookie("")
                        Widgets.toast(context, UiText.t("已退出登录", "Logged out"))
                        buildAccountSection(context, box)
                    }
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
            )
        } else {
            box.addView(
                Button(context, null, R.attr.buttonElevatedStyle).apply {
                    text = UiText.t("扫码登录", "Scan QR Code to Log In")
                    setOnClickListener { openBilibiliLogin() }
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
            )
        }
    }

    /** 打开扫码登录页(跨子页导航用 parentFragmentManager + containerId) */
    private fun openBilibiliLogin() {
        if (containerId == 0) return
        parentFragmentManager.beginTransaction()
            .replace(containerId, BilibiliLoginFragment.newInstance())
            .addToBackStack(null)
            .commit()
    }

    /** 一行"名称 + 值"说明 */
    private fun buildInfoRow(context: Context, label: String, value: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        }
        row.addView(
            TextView(context).apply {
                text = label
                setTextSize(15f)
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        row.addView(
            TextView(context).apply {
                text = value
                setTextSize(13f)
                setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
            },
        )
        return row
    }

    /** 一行开关:左侧竖排(主文案 + 小字说明)占满剩余宽度,右侧 Switch */
    private fun buildSwitchRow(context: Context, label: String, desc: String, switch: Switch): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(
            TextView(context).apply {
                text = label
                setTextSize(15f)
            },
        )
        column.addView(
            TextView(context).apply {
                text = desc
                setTextSize(12f)
                setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
            },
        )
        row.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(switch)
        return row
    }

    /** 字节数转可读大小(缓存占用 / 清除量展示) */
    private fun formatCacheBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        private const val KEY_CONTAINER = "containerId"

        fun newInstance(containerId: Int): SettingsFragment {
            val f = SettingsFragment()
            f.setArguments(DataSet().apply { put(KEY_CONTAINER, containerId) })
            return f
        }
    }
}
