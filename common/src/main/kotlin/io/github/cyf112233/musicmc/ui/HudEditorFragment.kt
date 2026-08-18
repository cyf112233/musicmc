package io.github.cyf112233.musicmc.ui

import icyllis.modernui.R
import icyllis.modernui.core.Context
import icyllis.modernui.fragment.Fragment
import icyllis.modernui.graphics.Canvas
import icyllis.modernui.graphics.Image
import icyllis.modernui.graphics.Paint
import icyllis.modernui.graphics.Rect
import icyllis.modernui.graphics.RectF
import icyllis.modernui.text.StaticLayout
import icyllis.modernui.text.TextPaint
import icyllis.modernui.text.TextUtils
import icyllis.modernui.util.DataSet
import icyllis.modernui.view.Gravity
import icyllis.modernui.view.LayoutInflater
import icyllis.modernui.view.MotionEvent
import icyllis.modernui.view.View
import icyllis.modernui.view.ViewGroup
import icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT
import icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT
import icyllis.modernui.widget.Button
import icyllis.modernui.widget.LinearLayout
import icyllis.modernui.widget.SeekBar
import icyllis.modernui.widget.TextView
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.platform.PlatformHolder
import io.github.cyf112233.musicmc.ui.hud.HudFrame
import io.github.cyf112233.musicmc.ui.hud.HudLayout
import io.github.cyf112233.musicmc.ui.hud.HudLyricsCache
import io.github.cyf112233.musicmc.ui.hud.HudRect
import kotlin.math.max
import kotlin.math.roundToInt

/** 预览刷新周期:进度条 / 歌词按此节奏重绘 */
private const val HUD_EDITOR_REFRESH_MS = 50L

/**
 * HUD 编辑器:单开 MUI 屏幕(由 SettingsFragment 的按钮经 loader 侧
 * [io.github.cyf112233.musicmc.platform.ModPlatform.openHudEditor] 打开)。
 *
 * - 全屏预览 View:onDraw 用 icyllis Canvas 按 [HudLayout.compute] 渲染与游戏内一致的 HUD
 *   (封面经 AsyncImageLoader.loadCallback 取 MUI Image、文本用 StaticLayout、
 *   进度条用 Canvas.drawRect、歌词行读 HudLyricsCache 现有数据);
 *   尺寸统一:布局用 GUI 缩放坐标(GUI px,与游戏内 graphics.guiWidth/Height 同单位),
 *   MUI 密度 = guiScale*0.5(javap 核实 MixinWindow.onSetGuiScale)→ guiScale = density*2,
 *   View 像素 = GUI px × guiScale;绘制时 canvas.save + canvas.scale(guiScale) + 按 GUI px 画 + restore;
 * - 触摸拖拽:DOWN 命中面板矩形(事件坐标先换算到 GUI px)→ 消费并开始拖动,MOVE 实时预览,
 *   UP 半自动吸附(阈值同旧 HudController:max(48×scale, 相应边 8%))并经
 *   NetMusic.updateConfig 持久化 hudX/hudY;拖动时在吸附目标边缘画半透明参考线;
 * - 底部控制条:缩放 SeekBar(50..200%,实时生效)/ 重置位置 / 完成(保存并关闭);
 * - "完成"调 PlatformHolder.require().closeScreen();Esc / back 走 MUI 返回。
 *
 * 与设置页双向读同一 config,均即时生效;游戏内 HUD(MusicHudRenderer)为纯显示,
 * 只读 config.hudX/hudY/hudScale 渲染(封面恒为方形)。
 */
class HudEditorFragment : Fragment() {

    private var preview: HudPreviewView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: DataSet?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        preview = HudPreviewView(context).also { previewView ->
            root.addView(previewView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        root.addView(buildControlBar(context), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return root
    }

    override fun onDestroyView() {
        preview = null
        super.onDestroyView()
    }

    // ---------------- 底部控制条 ----------------

    private fun buildControlBar(context: Context): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(12f))
        }

        // ---- HUD 整体缩放(50..200% → /100f,实时生效) ----
        val scaleText = TextView(context).apply {
            text = "缩放: ${(NetMusic.config.hudScale * 100).toInt()}%"
            setTextSize(13f)
            setMinimumWidth(dp(96f))
        }
        val scaleSeek = SeekBar(context).apply {
            max = 150
            progress = ((NetMusic.config.hudScale * 100).toInt() - 50).coerceIn(0, 150)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                // 实时性修复:去掉 fromUser 门控(MUI 拖动中 onProgressChanged 本就持续触发,
                // 去掉后程序性回写/其他输入路径同样实时应用,updateConfig 幂等无害)
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val pct = progress + 50
                    scaleText.text = "缩放: $pct%"
                    NetMusic.updateConfig { it.copy(hudScale = pct / 100f) }
                    preview?.invalidate()
                }
            })
        }
        val scaleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scaleRow.addView(scaleText)
        scaleRow.addView(scaleSeek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(scaleRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- 按钮行:重置位置 / 完成 ----
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, 0)
        }
        btnRow.addView(
            Button(context, null, R.attr.buttonElevatedStyle).apply {
                text = "重置位置"
                setOnClickListener {
                    NetMusic.updateConfig { it.copy(hudX = 0.92f, hudY = 0.86f) }
                    preview?.syncAnchor()
                    preview?.invalidate()
                    Widgets.toast(context, "HUD 位置已重置")
                }
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, Widgets.dp(context, 8), 0)
            },
        )
        btnRow.addView(
            Button(context, null, R.attr.buttonElevatedStyle).apply {
                text = "完成"
                setOnClickListener {
                    PlatformHolder.require().closeScreen()
                }
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                setMargins(Widgets.dp(context, 8), 0, 0, 0)
            },
        )
        bar.addView(btnRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        return bar
    }

    // ---------------- 全屏预览 View ----------------

    /**
     * 全屏预览 + 拖拽状态机(原 HudController 的拖拽 / 吸附逻辑并入此处)。
     * 布局数据每帧走 [HudLayout.compute],与游戏内渲染共用同一布局引擎,保证一致性。
     *
     * 尺寸统一(编辑器 vs 游戏内):
     * - HudLayout 的 w/h 与所有尺寸常量都以"GUI 缩放坐标"(GUI px)为单位 —— 与游戏内
     *   graphics.guiWidth()/guiHeight() 相同(1 GUI px 渲染为 guiScale 物理像素);
     * - MUI 的 density 由 MixinWindow.onSetGuiScale 设为 guiScale*0.5(javap 已核实),
     *   故 guiScale = density*2,View 宽高(视图像素)换算 GUI px:guiW = width / guiScale;
     * - 绘制时 canvas.save + canvas.scale(guiScale) 后按 GUI px 画,物理尺寸 = GUI px × guiScale,
     *   与游戏内一致(旧实现直接用视图像素布局,差 guiScale 倍)。
     */
    private inner class HudPreviewView(context: Context) : View(context) {

        // ---- 拖拽状态(空闲 → DOWN 命中面板 → 拖动 → UP 吸附持久化) ----
        private var dragging = false
        private var dragOffsetX = 0f
        private var dragOffsetY = 0f
        private var curX = NetMusic.config.hudX
        private var curY = NetMusic.config.hudY
        private var dragPanel: HudRect? = null

        // ---- 封面 / 歌曲跟踪 ----
        private var coverImage: Image? = null
        private var coverKey: String? = null
        private var lastSongId: String? = null

        // ---- 绘制画笔(避免每帧分配) ----
        private val imagePaint = Paint().apply { setFilter(true) }
        private val barBgPaint = Paint().apply { setColor(0x80000000.toInt()) }
        private val barFgPaint = Paint().apply { setColor(0xFFFFFFFF.toInt()) }
        private val placeholderPaint = Paint().apply { setColor(0xFF333333.toInt()) }
        private val borderPaint = Paint().apply { setColor(0x66FFFFFF.toInt()) }
        private val guidePaint = Paint().apply { setColor(0x99E91E63.toInt()) }

        private val tick = object : Runnable {
            override fun run() {
                if (!isAttachedToWindow) return
                refreshSongIfNeeded()
                invalidate()
                postDelayed(this, HUD_EDITOR_REFRESH_MS)
            }
        }

        init {
            // 保证 MUI 触摸分发把它当作事件目标(命中面板时 onTouchEvent 返回 true 消费)
            isClickable = true
        }

        // ---------------- GUI 缩放坐标换算(尺寸统一的依据) ----------------
        // MixinWindow.onSetGuiScale 的字节码:dm.density = (float)guiScale * 0.5f
        // (javap 已核实,见 modernui-mc 的 icyllis.modernui.mc.mixin.MixinWindow),
        // 因此 guiScale = density * 2;游戏内 1 GUI px = guiScale 物理像素,
        // 而 MUI View 坐标 / 绘制尺寸的单位就是物理像素,故 view px = GUI px × guiScale。

        /** 当前 GUI 缩放倍数(density = guiScale*0.5 → guiScale = density*2) */
        private fun guiScale(): Float = context.resources.displayMetrics.density * 2f

        /** View 宽(视图像素)→ GUI px(与游戏内 graphics.guiWidth() 同单位) */
        private fun guiW(): Int = (width / guiScale()).roundToInt()

        /** View 高(视图像素)→ GUI px(与游戏内 graphics.guiHeight() 同单位) */
        private fun guiH(): Int = (height / guiScale()).roundToInt()

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            postDelayed(tick, HUD_EDITOR_REFRESH_MS)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(tick)
            super.onDetachedFromWindow()
        }

        /** 外部改动 config(如"重置位置"按钮)后同步本地锚点 */
        fun syncAnchor() {
            curX = NetMusic.config.hudX
            curY = NetMusic.config.hudY
            dragging = false
            dragPanel = null
        }

        /** 换歌时刷新歌词与封面(与游戏内渲染器同一触发点) */
        private fun refreshSongIfNeeded() {
            val song = NetMusic.player.current
            val id = song?.id
            if (id == lastSongId) return
            lastSongId = id
            HudLyricsCache.refresh(song)
            loadCover(song)
        }

        private fun loadCover(song: Song?) {
            val url = song?.picUrl
            val key = url ?: "none"
            if (key == coverKey) return
            coverKey = key
            coverImage = null
            if (url.isNullOrBlank()) return
            AsyncImageLoader.loadCallback(url) { image ->
                // 仅当仍是当前 key 时应用(防切歌后旧回调覆盖)
                if (key == coverKey) {
                    coverImage = image
                    invalidate()
                }
            }
        }

        /** 当前锚点:拖拽中用拖拽坐标,否则读 config(编辑器与设置双向读同一 config) */
        private fun currentAnchor(): Pair<Float, Float> =
            if (dragging) curX to curY else NetMusic.config.hudX to NetMusic.config.hudY

        /** 布局计算:[w]/[h] 必须传 GUI px(调用方用 [guiW]/[guiH]);与游戏内同一单位 */
        private fun computeFrame(w: Int, h: Int): HudFrame? {
            val config = NetMusic.config
            val (ax, ay) = currentAnchor()
            return HudLayout.compute(
                w = w,
                h = h,
                scale = config.hudScale,
                player = NetMusic.player,
                lyric = HudLyricsCache.current,
                hudX = ax,
                hudY = ay,
            )
        }

        // ---------------- 触摸拖拽 ----------------

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val w = guiW()
            val h = guiH()
            if (w <= 0 || h <= 0) return false
            // 事件坐标是视图像素,布局是 GUI px:统一除以 guiScale 换算
            val gs = guiScale()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 先同步外部可能已改过的锚点,再做命中测试
                    curX = NetMusic.config.hudX
                    curY = NetMusic.config.hudY
                    val frame = computeFrame(w, h) ?: return false
                    val p = frame.panel
                    val ex = event.x / gs
                    val ey = event.y / gs
                    if (ex >= p.x && ex <= p.x + p.w && ey >= p.y && ey <= p.y + p.h) {
                        dragging = true
                        dragPanel = p
                        dragOffsetX = ex - curX * w
                        dragOffsetY = ey - curY * h
                        invalidate()
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    val p = dragPanel ?: computeFrame(w, h)?.panel ?: return false
                    // 实时预览(clamp 保持面板完整在屏幕内),不做吸附
                    val ex = event.x / gs
                    val ey = event.y / gs
                    val nx = (ex - dragOffsetX).coerceIn(0f, (w - p.w).coerceAtLeast(0).toFloat())
                    val ny = (ey - dragOffsetY).coerceIn(0f, (h - p.h).coerceAtLeast(0).toFloat())
                    curX = nx / w
                    curY = ny / h
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) return false
                    dragging = false
                    val p = dragPanel ?: return false
                    dragPanel = null
                    // 松开才做半自动吸附并持久化(在 GUI px 空间计算)
                    val (snapX, snapY) = snap(curX * w, curY * h, w, h, p.w, p.h, NetMusic.config.hudScale)
                    curX = snapX
                    curY = snapY
                    NetMusic.updateConfig { it.copy(hudX = snapX, hudY = snapY) }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    dragPanel = null
                    invalidate()
                    return true
                }
            }
            return false
        }

        /**
         * 半自动吸附:取最近且在阈值内的边缘(阈值同旧 HudController:
         * max(48×scale, 该边 8%)),返回新的归一化锚点。
         */
        private fun snap(
            px: Float,
            py: Float,
            canvasW: Int,
            canvasH: Int,
            hudW: Int,
            hudH: Int,
            scale: Float,
        ): Pair<Float, Float> {
            val thresholdX = max(48f * scale, canvasW * 0.08f)
            val thresholdY = max(48f * scale, canvasH * 0.08f)
            val dLeft = px
            val dRight = canvasW - (px + hudW)
            val dTop = py
            val dBottom = canvasH - (py + hudH)

            var best = Float.MAX_VALUE
            var nx = px
            var ny = py
            if (dLeft < thresholdX && dLeft < best) {
                best = dLeft
                nx = 0f
            }
            if (dRight < thresholdX && dRight < best) {
                best = dRight
                nx = (canvasW - hudW).coerceAtLeast(0).toFloat()
            }
            if (dTop < thresholdY && dTop < best) {
                best = dTop
                ny = 0f
            }
            if (dBottom < thresholdY && dBottom < best) {
                best = dBottom
                ny = (canvasH - hudH).coerceAtLeast(0).toFloat()
            }
            return nx / canvasW to ny / canvasH
        }

        // ---------------- 绘制 ----------------

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val frame = computeFrame(guiW(), guiH())
            if (frame != null) {
                // 1 GUI px = guiScale 视图像素:整体放大画布后按 GUI px 绘制,
                // 物理尺寸与游戏内(同样按 GUI px 布局、由渲染管线放大)一致
                canvas.save()
                canvas.scale(guiScale(), guiScale())
                drawPanel(canvas, frame)
                canvas.restore()
            } else {
                // 提示语是 UI 文案而非 HUD 内容,仍在视图像素空间绘制
                drawEmptyHint(canvas)
            }
        }

        private fun drawPanel(canvas: Canvas, frame: HudFrame) {
            drawCover(canvas, frame)
            drawTexts(canvas, frame)
            drawProgressBar(canvas, frame)
            if (dragging) {
                drawDragBorder(canvas, frame)
                drawSnapGuide(canvas, frame)
            }
        }

        /** 封面:Image 就绪则按覆盖矩形绘制(方形,无旋转),否则占位块。
         * 源图按宽高比中心裁剪(B 站封面 16:9,cover 矩形为方形,不裁剪会压扁变形)。 */
        private fun drawCover(canvas: Canvas, frame: HudFrame) {
            if (!frame.showCover) return
            val c = frame.cover
            val img = coverImage
            if (img != null) {
                // 源图中心裁剪到 cover 同比例区域
                val sw = img.width
                val sh = img.height
                var sx = 0
                var sy = 0
                var sww = sw
                var shh = sh
                if (sw > sh && sh > 0) {
                    sx = (sw - sh) / 2
                    sww = sh
                } else if (sh > sw && sw > 0) {
                    sy = (sh - sw) / 2
                    shh = sw
                }
                canvas.drawImage(
                    img,
                    Rect(sx, sy, sx + sww, sy + shh),
                    RectF(c.x.toFloat(), c.y.toFloat(), (c.x + c.w).toFloat(), (c.y + c.h).toFloat()),
                    imagePaint,
                )
            } else {
                canvas.drawRect(c.x.toFloat(), c.y.toFloat(), (c.x + c.w).toFloat(), (c.y + c.h).toFloat(), placeholderPaint)
            }
        }

        /**
         * 文本:标题 / 艺术家 / 时间 / 歌词块,全部按 GUI px 绘制
         * (字号 = 逻辑字号 × s,s = hudScale×0.5,与 HudLayout 布局同系数:
         * 行距按 s 计算,字号也按 s,文字不重叠;与游戏内 drawScaledText 同一系数,
         * 两侧字体大小一致;画布已由 onDraw 整体 scale(guiScale))
         */
        private fun drawTexts(canvas: Canvas, frame: HudFrame) {
            val scale = NetMusic.config.hudScale
            val s = scale.coerceIn(0.5f, 2f) * 0.5f
            val textW = frame.bar.w.coerceAtLeast(1)
            drawTextLine(canvas, frame.title, frame.titleX, frame.titleY, HudLayout.TITLE_SIZE * s, 0xFFFFFFFF.toInt(), textW)
            if (frame.artist.isNotBlank()) {
                drawTextLine(canvas, frame.artist, frame.artistX, frame.artistY, HudLayout.SUB_SIZE * s, 0xFFAAAAAA.toInt(), textW)
            }
            drawTextLine(canvas, frame.timeText, frame.timeX, frame.timeY, HudLayout.SUB_SIZE * s, 0xFFBBBBBB.toInt(), textW)

            // 歌词块:仅当前行,高亮;超长时横向往返滚动(不截断,与游戏内一致)
            if (frame.lyricLines.isNotEmpty()) {
                val i = frame.lyricCurrentIndex
                val text = frame.lyricLines[i]
                val size = HudLayout.LYRIC_CURRENT_SIZE * s
                val paint = TextPaint().apply {
                    setTextSize(size)
                    setColor(0xFF4FC3F7.toInt())
                }
                // MUI Paint 无 measureText:用超大宽度 StaticLayout 量取实际行宽
                // (getLineWidth(0)),该 layout 同时用作绘制(单行完整文本)
                val layout = StaticLayout.builder(text, 0, text.length, paint, Int.MAX_VALUE / 8)
                    .setMaxLines(1)
                    .build()
                val textWpx = layout.getLineWidth(0)
                val offsetX = HudLayout.lyricScrollOffset(System.currentTimeMillis(), textWpx, textW.toFloat())
                canvas.save()
                canvas.translate((frame.lyricX + offsetX).toFloat(), frame.lyricY.toFloat())
                layout.draw(canvas)
                canvas.restore()
            }
        }

        /** 单行文本:StaticLayout(单行 + 超长省略)绘制,画布平移到 [x]/[y] */
        private fun drawTextLine(canvas: Canvas, text: String, x: Int, y: Int, sizePx: Float, color: Int, maxWidth: Int) {
            if (text.isEmpty()) return
            val paint = TextPaint().apply {
                setTextSize(sizePx)
                setColor(color)
            }
            val layout = StaticLayout.builder(text, 0, text.length, paint, maxWidth)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            canvas.save()
            canvas.translate(x.toFloat(), y.toFloat())
            layout.draw(canvas)
            canvas.restore()
        }

        /** 进度条:背景半透明深色 + 前景白色(与游戏内 fill 语义一致) */
        private fun drawProgressBar(canvas: Canvas, frame: HudFrame) {
            val b = frame.bar
            if (b.w <= 0 || b.h <= 0) return
            canvas.drawRect(b.x.toFloat(), b.y.toFloat(), (b.x + b.w).toFloat(), (b.y + b.h).toFloat(), barBgPaint)
            val fw = (b.w * frame.progress).toInt().coerceIn(0, b.w)
            if (fw > 0) canvas.drawRect(b.x.toFloat(), b.y.toFloat(), (b.x + fw).toFloat(), (b.y + b.h).toFloat(), barFgPaint)
        }

        /** 拖动中高亮边框:面板四边 1px 半透明白(与游戏内旧拖拽态一致) */
        private fun drawDragBorder(canvas: Canvas, frame: HudFrame) {
            val p = frame.panel
            val x1 = p.x.toFloat()
            val y1 = p.y.toFloat()
            val x2 = (p.x + p.w).toFloat()
            val y2 = (p.y + p.h).toFloat()
            canvas.drawRect(x1, y1, x2, y1 + 1f, borderPaint)
            canvas.drawRect(x1, y2 - 1f, x2, y2, borderPaint)
            canvas.drawRect(x1, y1, x1 + 1f, y2, borderPaint)
            canvas.drawRect(x2 - 1f, y1, x2, y2, borderPaint)
        }

        /** 拖动时:若面板已进入吸附阈值,在将吸附的屏幕边缘画一条半透明参考线(坐标用 GUI px) */
        private fun drawSnapGuide(canvas: Canvas, frame: HudFrame) {
            val w = guiW()
            val h = guiH()
            val scale = NetMusic.config.hudScale
            val p = frame.panel
            val thresholdX = max(48f * scale, w * 0.08f)
            val thresholdY = max(48f * scale, h * 0.08f)
            val px = p.x.toFloat()
            val py = p.y.toFloat()
            val dLeft = px
            val dRight = w - (px + p.w)
            val dTop = py
            val dBottom = h - (py + p.h)

            var best = Float.MAX_VALUE
            var guide: HudRect? = null
            if (dLeft < thresholdX && dLeft < best) {
                best = dLeft
                guide = HudRect(0, 0, 2, h)
            }
            if (dRight < thresholdX && dRight < best) {
                best = dRight
                guide = HudRect(w - 2, 0, 2, h)
            }
            if (dTop < thresholdY && dTop < best) {
                best = dTop
                guide = HudRect(0, 0, w, 2)
            }
            if (dBottom < thresholdY && dBottom < best) {
                best = dBottom
                guide = HudRect(0, h - 2, w, 2)
            }
            val g = guide ?: return
            canvas.drawRect(g.x.toFloat(), g.y.toFloat(), (g.x + g.w).toFloat(), (g.y + g.h).toFloat(), guidePaint)
        }

        /** 无当前歌曲:居中提示(拖拽需在有歌曲时才有可命中面板) */
        private fun drawEmptyHint(canvas: Canvas) {
            val w = width
            val h = height
            val paint = TextPaint().apply {
                setTextSize(dp(14f).toFloat())
                setColor(0x99FFFFFF.toInt())
            }
            val msg = "播放音乐后在此预览 HUD"
            val layout = StaticLayout.builder(msg, 0, msg.length, paint, (w - dp(32f)).coerceAtLeast(1))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            canvas.save()
            canvas.translate(
                ((w - layout.getWidth()) / 2f).coerceAtLeast(0f),
                ((h - layout.getHeight(false)) / 2f).coerceAtLeast(0f),
            )
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
