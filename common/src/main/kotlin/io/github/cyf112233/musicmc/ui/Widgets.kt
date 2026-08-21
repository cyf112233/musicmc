package io.github.cyf112233.musicmc.ui

import icyllis.modernui.R
import icyllis.modernui.core.Context
import icyllis.modernui.graphics.drawable.ShapeDrawable
import icyllis.modernui.resources.TypedValue
import icyllis.modernui.view.KeyEvent
import icyllis.modernui.view.View
import icyllis.modernui.widget.LinearLayout
import icyllis.modernui.widget.TextView
import icyllis.modernui.widget.Toast
import io.github.cyf112233.musicmc.player.PlayMode

/**
 * UI 工具函数。
 *
 * 主题取色模式(实测 modernui-core-3.13.0):
 *   theme.resolveAttribute(R.ns, R.attr.colorPrimary, value, true) → value.data 为 ARGB int
 * 圆角卡片模式(与 ModernUI 自带 ThemeControl.makeElevatedCard 一致):
 *   ShapeDrawable.setCornerRadius + setColor(ColorStateList) + View.setBackground
 *   (注意:View 没有 setCornerRadius,圆角由 drawable 实现,无需额外处理)
 */
object Widgets {

    /** dp 转像素 */
    fun dp(context: Context, value: Int): Int =
        Math.round(value * context.resources.displayMetrics.density)

    /**
     * 从主题解析颜色属性(如 R.attr.colorPrimary);解析失败返回 null,调用方给兜底色。
     * 注:ModernUI 3.13.0 中颜色 attr 是 String 形式(如 "colorPrimary")。
     */
    fun resolveColor(context: Context, attrName: String): Int? {
        val value = TypedValue()
        val theme = context.theme
        return if (theme.resolveAttribute(R.ns, attrName, value, true)) value.data else null
    }

    /**
     * 创建一个 Material3 风格的圆角卡片(垂直 LinearLayout):
     * 背景色 colorSurfaceContainerHigh + 圆角 + 轻阴影 + 内边距。
     */
    fun makeCard(context: Context): LinearLayout {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        val bg = ShapeDrawable()
        bg.setCornerRadius(card.dp(12f).toFloat())
        val value = TypedValue()
        val theme = context.theme
        if (theme.resolveAttribute(R.ns, R.attr.colorSurfaceContainerHigh, value, true)) {
            bg.setColor(theme.resources.loadColorStateList(value, null, theme))
        }
        card.background = bg
        card.setElevation(card.dp(1f).toFloat())
        card.setPadding(card.dp(12f), card.dp(12f), card.dp(12f), card.dp(12f))
        return card
    }

    /** 毫秒 → mm:ss */
    fun formatTime(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(total / 60, total % 60)
    }

    /** 播放模式文案(按游戏语言;不带"模式:"前缀,按钮上直接显示) */
    fun playModeLabel(mode: PlayMode): String = when (mode) {
        PlayMode.SEQUENCE -> io.github.cyf112233.musicmc.client.UiText.t("顺序", "Sequential")
        PlayMode.LOOP_ALL -> io.github.cyf112233.musicmc.client.UiText.t("列表循环", "Loop All")
        PlayMode.LOOP_ONE -> io.github.cyf112233.musicmc.client.UiText.t("单曲循环", "Loop One")
        PlayMode.SHUFFLE -> io.github.cyf112233.musicmc.client.UiText.t("随机", "Shuffle")
    }

    /** 弹 Toast(包 try/catch:Fragment 已销毁等时机弹窗可能失败,静默即可) */
    fun toast(context: Context, msg: String) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            // 静默:某些时机(如 Fragment 已 detach)Toast 会抛异常
        }
    }

    /**
     * 绑定"Enter = 确认":按下 Enter(257)/小键盘 Enter(335)且 ACTION_DOWN 时
     * 执行 [action] 并消费事件(返回 true 不再冒泡)。项目无 IME 管线,Direct 按键
     * 通过 View.setOnKeyListener 触发(与 MusicMainFragment 旧内联实现同语义,统一收敛)。
     */
    fun bindEnter(view: View, action: () -> Unit) {
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEY_ENTER || keyCode == KeyEvent.KEY_KP_ENTER)
            ) {
                action()
                true
            } else {
                false
            }
        }
    }
}
