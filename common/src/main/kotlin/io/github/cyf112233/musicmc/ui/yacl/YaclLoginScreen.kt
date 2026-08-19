package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.bilibili.BiliHttp
import io.github.cyf112233.musicmc.bilibili.QrStatus
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.util.Async
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * YACL 版 B 站扫码登录页。
 *
 * 二维码生成不走 java.awt(Android NDK 无 java.desktop):后台线程
 * [io.github.cyf112233.musicmc.ui.vanilla.QrCodeNative.generate] 直写 [NativeImage],
 * 渲染回调(extractRenderState,GL 上下文有效)里创建 DynamicTexture 注册后 blit。
 *
 * 轮询:后台 2s 轮询 [BiliHttp.qrPoll],成功即 [NetMusic.setBilibiliCookie] 并返回上一页。
 */
class YaclLoginScreen(private val back: Screen) : Screen(Component.literal("登录")) {

    private val mc get() = Minecraft.getInstance()
    private val textureManager get() = mc.textureManager

    private val rectBackBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectRefreshBtn = YaclTheme.Rect(0, 0, 0, 0)

    /** 后台生成好的二维码图,渲染回调 pump 消费(仅渲染线程访问队列) */
    private val pendingQr = ConcurrentLinkedQueue<NativeImage>()

    /** 当前已注册的二维码纹理(渲染线程访问;refresh 时先释放再注册) */
    private var qrTexture: DynamicTexture? = null

    /** 二维码尺寸(逻辑像素) */
    private val qrSize = 200

    @Volatile
    private var status: String = "正在生成二维码…"

    @Volatile
    private var generation = 0

    private val qrId = Identifier.fromNamespaceAndPath("musicmc", "login_qr")

    override fun init() {
        super.init()
        startLogin()
    }

    override fun onClose() {
        generation++ // 作废旧轮询
        qrTexture?.let { runCatching { it.close() } }
        qrTexture = null
        super.onClose()
    }

    private fun startLogin() {
        val gen = ++generation
        status = "正在生成二维码…"
        Async.run {
            try {
                val qr = BiliHttp.qrGenerate()
                val img = io.github.cyf112233.musicmc.ui.vanilla.QrCodeNative.generate(qr.url, 240)
                if (gen != generation) return@run
                if (img != null) {
                    pendingQr.add(img)
                } else {
                    status = "二维码生成失败"
                    return@run
                }
                pollLoop(gen, qr.qrcodeKey)
            } catch (e: Exception) {
                if (gen != generation) return@run
                status = "生成二维码失败: ${e.message ?: "网络错误"}"
            }
        }
    }

    private fun pollLoop(gen: Int, key: String) {
        Async.run {
            while (gen == generation) {
                val result = runCatching { BiliHttp.qrPoll(key) }.getOrNull()
                if (gen != generation) return@run
                when (result?.status) {
                    QrStatus.SUCCESS -> {
                        val cookie = result.cookieHeader
                        if (cookie.isNullOrBlank()) {
                            status = "登录失败:未获取到登录 Cookie"
                        } else {
                            NetMusic.setBilibiliCookie(cookie)
                            status = "登录成功"
                            runCatching { mc.setScreen(back) }
                        }
                        return@run
                    }
                    QrStatus.EXPIRED -> {
                        status = "二维码已过期,请点击刷新"
                        return@run
                    }
                    QrStatus.SCANNED -> status = "已扫码,请在手机上确认"
                    QrStatus.WAIT -> status = "等待扫码…"
                    null -> status = "轮询失败,自动重试…"
                }
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    return@run
                }
            }
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val g = GuiGraphicsHudGui(graphics)
        val w = g.guiWidth()
        val h = g.guiHeight()

        YaclTheme.drawBackground(g, w, h)

        rectBackBtn.x1 = 12; rectBackBtn.y1 = 10; rectBackBtn.x2 = 56; rectBackBtn.y2 = 26
        YaclTheme.drawBtn(g, rectBackBtn, "< 返回", mouseX, mouseY)
        YaclTheme.drawCenteredTitle(g, "B 站扫码登录", w / 2, 10)

        // 渲染帧 pump:后台生成的二维码 → DynamicTexture 注册(需要 GL 上下文)
        val pending = pendingQr.poll()
        if (pending != null) {
            qrTexture?.let { runCatching { it.close() } }
            val tex = DynamicTexture({ "musicmc_login_qr" }, pending)
            textureManager.register(qrId, tex)
            qrTexture = tex
        }

        val qrX = (w - qrSize) / 2
        val qrY = (h - qrSize) / 2 - 20
        if (qrTexture != null) {
            g.fill(qrX - 4, qrY - 4, qrX + qrSize + 4, qrY + qrSize + 4, YaclTheme.colorCard)
            g.fill(qrX - 4, qrY - 4, qrX + qrSize + 4, qrY - 3, YaclTheme.colorAccent) // 主题色描边
            g.drawTexture(qrId, qrX, qrY, qrSize, qrSize)
        } else {
            g.fill(qrX, qrY, qrX + qrSize, qrY + qrSize, 0xFF181818.toInt())
            g.drawText("二维码生成中…", qrX + 40, qrY + qrSize / 2 - 5, 11f, 1f, YaclTheme.colorTextDim)
        }

        val statusColor = when {
            status.contains("成功") -> YaclTheme.colorAccentBright
            status.contains("失败") || status.contains("过期") -> YaclTheme.colorError
            else -> YaclTheme.colorTextSub
        }
        g.drawText(status, w / 2 - g.textWidth(status) / 2, qrY + qrSize + 14, 11f, 1f, statusColor)

        rectRefreshBtn.x1 = w / 2 - 50; rectRefreshBtn.y1 = qrY + qrSize + 34
        rectRefreshBtn.x2 = w / 2 + 50; rectRefreshBtn.y2 = qrY + qrSize + 58
        YaclTheme.drawBtn(g, rectRefreshBtn, "刷新二维码", mouseX, mouseY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        if (rectBackBtn.hit(x, y)) { mc.setScreen(back); return true }
        if (rectRefreshBtn.hit(x, y)) { startLogin(); return true }
        return super.mouseClicked(event, doubleClick)
    }

    override fun isPauseScreen(): Boolean = false
}
