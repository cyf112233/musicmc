package io.github.cyf112233.musicmc.ui

import icyllis.modernui.R
import icyllis.modernui.core.Context
import icyllis.modernui.graphics.drawable.ShapeDrawable
import icyllis.modernui.text.TextUtils
import icyllis.modernui.view.Gravity
import icyllis.modernui.view.View
import icyllis.modernui.view.ViewGroup
import icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT
import icyllis.modernui.widget.ArrayAdapter
import icyllis.modernui.widget.ImageView
import icyllis.modernui.widget.LinearLayout
import icyllis.modernui.widget.TextView
import io.github.cyf112233.musicmc.model.Song

/**
 * 歌曲列表适配器(Material3 + 主流播放器直觉):
 * 每行 = 44dp 封面或序号 + 竖排(标题 15sp / 艺术家 12sp onSurfaceVariant,weight 1)
 * + 时长 12sp onSurfaceVariant(padding 12/8,留白作行距)。
 * 当前播放行标题用 colorPrimary 着色;[showIndex] 模式下左侧为 1-based 序号,
 * 当前播放行序号同样用 colorPrimary(队列页用;其余页面显示封面)。
 */
class SongListAdapter(
    context: Context,
    items: List<Song>,
) : ArrayAdapter<Song>(context, items) {

    /** 当前播放歌曲 id(用于高亮);由外部在队列变化时刷新 */
    var currentSongId: String? = null

    /** 序号模式:true 时左侧显示 1-based 序号代替封面(队列页);false 显示封面 */
    var showIndex: Boolean = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val song = getItem(position)

        val row: LinearLayout = (convertView as? LinearLayout)?.also { it.removeAllViews() }
            ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            }

        // 左侧 44dp:序号(队列页,当前播放行 colorPrimary)或封面(圆角占位)
        if (showIndex) {
            val idxColor = if (song.id == currentSongId) {
                Widgets.resolveColor(context, R.attr.colorPrimary) ?: 0xFFFFFFFF.toInt()
            } else {
                Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt()
            }
            row.addView(TextView(context).apply {
                text = "${position + 1}"
                setTextSize(15f)
                gravity = Gravity.CENTER
                setTextColor(idxColor)
            }, LinearLayout.LayoutParams(Widgets.dp(context, 44), Widgets.dp(context, 44)))
        } else {
            val cover = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackground(ShapeDrawable().apply {
                    setCornerRadius(Widgets.dp(context, 8).toFloat())
                    setColor(Widgets.resolveColor(context, R.attr.colorSurfaceContainerHighest) ?: 0xFF444444.toInt())
                })
            }
            AsyncImageLoader.load(song.picUrl, cover)
            row.addView(cover, LinearLayout.LayoutParams(Widgets.dp(context, 44), Widgets.dp(context, 44)))
        }

        // 竖排:标题 / 艺术家(weight 1)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleColor = if (song.id == currentSongId) {
            Widgets.resolveColor(context, R.attr.colorPrimary) ?: 0xFFFFFFFF.toInt()
        } else {
            Widgets.resolveColor(context, R.attr.colorOnSurface) ?: 0xFFFFFFFF.toInt()
        }
        column.addView(TextView(context).apply {
            text = song.title
            setTextSize(15f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(titleColor)
        })
        column.addView(TextView(context).apply {
            text = song.artist
            setTextSize(12f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
        })
        row.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            setMargins(Widgets.dp(context, 8), 0, Widgets.dp(context, 8), 0)
        })

        // 时长 12sp onSurfaceVariant
        row.addView(TextView(context).apply {
            text = Widgets.formatTime(song.durationMs)
            setTextSize(12f)
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
        })

        return row
    }
}