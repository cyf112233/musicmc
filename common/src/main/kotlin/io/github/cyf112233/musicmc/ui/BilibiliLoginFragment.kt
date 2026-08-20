package io.github.cyf112233.musicmc.ui

import icyllis.modernui.R
import icyllis.modernui.fragment.Fragment
import icyllis.modernui.graphics.BitmapFactory
import icyllis.modernui.graphics.Image
import icyllis.modernui.graphics.drawable.ColorDrawable
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
import icyllis.modernui.widget.TextView
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.bilibili.BiliHttp
import io.github.cyf112233.musicmc.bilibili.QrStatus
import io.github.cyf112233.musicmc.util.Async
import io.github.cyf112233.musicmc.util.QrCode

/**
 * B 站扫码登录页(Web passport 链路,BBPlayer 同款):
 * 后台线程 qrGenerate → UI 线程把 QrCode.pngBytes 渲染到 ImageView
 * (BitmapFactory.decodeByteArray → Image.createTextureFromBitmap → setImage);
 * 后台 2s 轮询 qrPoll,状态文案:等待扫码… / 已扫码,请在手机上确认 /
 * 二维码已过期,请点击刷新 / 登录成功。
 *
 * 并发防护:generation 计数(generate/刷新/onDestroyView 自增),过期线程回调直接丢弃;
 * 成功 → NetMusic.setBilibiliCookie(持久化)+ toast + 刷新昵称 + popBackStack。
 */
class BilibiliLoginFragment : Fragment() {

    /** 会话号:每次 startLogin(首次/刷新)自增,旧轮询循环与回调据此失效 */
    private var generation = 0

    private var qrImage: ImageView? = null
    private var statusText: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: DataSet?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
        }

        root.addView(
            TextView(context).apply {
                text = "Bilibili Login"
                setTextAppearance(R.attr.textAppearanceTitleLarge)
                setPadding(0, 0, 0, dp(16f))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        qrImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackground(ColorDrawable(0xFFFFFFFF.toInt()))
        }
        root.addView(
            qrImage,
            LinearLayout.LayoutParams(Widgets.dp(context, 240), Widgets.dp(context, 240)).apply { gravity = Gravity.CENTER_HORIZONTAL },
        )

        statusText = TextView(context).apply {
            text = "Generating QR code…"
            setTextSize(14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16f), 0, dp(8f))
            setTextColor(Widgets.resolveColor(context, R.attr.colorOnSurfaceVariant) ?: 0xFFAAAAAA.toInt())
        }
        root.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(
            Button(context, null, R.attr.buttonElevatedStyle).apply {
                text = "Refresh QR Code"
                setOnClickListener { startLogin() }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )

        startLogin()
        return root
    }

    override fun onDestroyView() {
        // 停止轮询:自增 generation,所有后台线程回调 / 循环自然退出
        generation++
        super.onDestroyView()
    }

    /** 生成二维码并启动 2s 轮询(新会话 generation 自增,旧会话作废) */
    private fun startLogin() {
        val gen = ++generation
        statusText?.text = "Generating QR code…"
        Async.executor.execute {
            try {
                val qr = BiliHttp.qrGenerate()
                // 重活(矩阵编码 + PNG 编码 + 解码成纹理)全部在后台线程;UI 线程只 setImage
                val image = QrCode.pngBytes(qr.url)?.let { png ->
                    runCatching { Image.createTextureFromBitmap(BitmapFactory.decodeByteArray(png, 0, png.size)) }.getOrNull()
                }
                Async.onUi {
                    if (gen != generation || !isAdded) return@onUi
                    if (image != null) {
                        qrImage?.setImage(image)
                    }
                    pollLoop(gen, qr.qrcodeKey)
                }
            } catch (e: Exception) {
                Async.onUi {
                    if (gen != generation || !isAdded) return@onUi
                    statusText?.text = "Failed to generate QR code: ${e.message ?: "network error"}"
                }
            }
        }
    }

    /**
     * 后台 2s 间隔轮询循环。退出条件:generation 变化(刷新 / onDestroyView)或
     * 成功 / 过期(需用户点"刷新二维码"重新生成)。
     */
    private fun pollLoop(gen: Int, key: String) {
        Async.executor.execute {
            while (gen == generation) {
                val result = runCatching { BiliHttp.qrPoll(key) }.getOrNull()
                if (gen != generation) return@execute
                when (result?.status) {
                    QrStatus.SUCCESS -> {
                        Async.onUi {
                            if (gen != generation || !isAdded) return@onUi
                            val cookie = result.cookieHeader
                            if (cookie.isNullOrBlank()) {
                                statusText?.text = "Login failed: no login cookie obtained"
                            } else {
                                NetMusic.setBilibiliCookie(cookie)
                                statusText?.text = "Login successful"
                                Widgets.toast(requireContext(), "Login successful")
                                refreshNickname()
                                runCatching { parentFragmentManager.popBackStack() }
                            }
                        }
                        return@execute
                    }
                    QrStatus.EXPIRED -> {
                        Async.onUi {
                            if (gen != generation || !isAdded) return@onUi
                            statusText?.text = "QR code expired, please refresh"
                        }
                        return@execute
                    }
                    QrStatus.SCANNED -> {
                        Async.onUi {
                            if (gen != generation || !isAdded) return@onUi
                            statusText?.text = "Scanned, please confirm on your phone"
                        }
                    }
                    QrStatus.WAIT -> {
                        Async.onUi {
                            if (gen != generation || !isAdded) return@onUi
                            statusText?.text = "Waiting for scan…"
                        }
                    }
                    null -> {
                        Async.onUi {
                            if (gen != generation || !isAdded) return@onUi
                            statusText?.text = "Polling failed, retrying automatically…"
                        }
                    }
                }
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    return@execute
                }
            }
        }
    }

    /** 登录成功后后台取昵称(兜底更新状态文案;页面通常已 pop 返回设置页) */
    private fun refreshNickname() {
        Async.executor.execute {
            val nick = NetMusic.bilibiliNickname()
            Async.onUi {
                if (!isAdded) return@onUi
                statusText?.text = if (nick.isNullOrBlank()) "Login successful" else "Login successful ($nick)"
            }
        }
    }

    companion object {
        fun newInstance(): BilibiliLoginFragment = BilibiliLoginFragment()
    }
}
