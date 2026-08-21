package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.bilibili.BiliHttp
import io.github.cyf112233.musicmc.bilibili.QrStatus
import io.github.cyf112233.musicmc.platform.McScreens
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.client.UiText
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
 * YACL 版 B 站扫码Login页。
 *
 * 二维码生成不走 java.awt(Android NDK 无 java.desktop):后台线程
 * [io.github.cyf112233.musicmc.ui.vanilla.QrCodeNative.generate] 直写 [NativeImage],
 * 渲染回调(extractRenderState,GL 上下文有效)里创建 DynamicTexture 注册后 blit。
 *
 * 轮询:后台 2s 轮询 [BiliHttp.qrPoll],成功即 [NetMusic.setBilibiliCookie] 并返回上一页。
 */
class YaclLoginScreen(private val back: Screen) : Screen(Component.literal(UiText.t("登录", "Login"))) {

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
    private var status: String = UiText.t("二维码生成中…", "Generating QR…")

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
        status = UiText.t("二维码生成中…", "Generating QR…")
        Async.run {
            try {
                val qr = BiliHttp.qrGenerate()
                val img = io.github.cyf112233.musicmc.ui.vanilla.QrCodeNative.generate(qr.url, 240)
                if (gen != generation) return@run
                if (img != null) {
                    pendingQr.add(img)
                } else {
                    status = UiText.t("二维码生成失败", "QR generation failed")
                    return@run
                }
                pollLoop(gen, qr.qrcodeKey)
            } catch (e: Exception) {
                if (gen != generation) return@run
                status = "QR generation failed: ${e.message ?: "network error"}"
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
                            status = "Login failed: no cookie received"
                        } else {
                            NetMusic.setBilibiliCookie(cookie)
                            status = "Logged in"
                            runCatching { McScreens.open(back) }
                        }
                        return@run
                    }
                    QrStatus.EXPIRED -> {
                        status = "QR code expired, click to refresh"
                        return@run
                    }
                    QrStatus.SCANNED -> status = "Scanned, confirm on your phone"
                    QrStatus.WAIT -> status = "Waiting for scan…"
                    null -> status = "Polling failed, retrying…"
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
        YaclTheme.drawBtn(g, rectBackBtn, UiText.t("< 返回", "< Back"), mouseX, mouseY)
        YaclTheme.drawCenteredTitle(g, UiText.t("B 站扫码登录", "Bilibili QR Login"), w / 2, 10)

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
            g.drawText(UiText.t("二维码生成中…", "Generating QR…"), qrX + 40, qrY + qrSize / 2 - 5, 11f, 1f, YaclTheme.colorTextDim)
        }

        val statusColor = when {
            status.contains("Logged in") || status.contains("success") -> YaclTheme.colorAccentBright
            status.contains("failed") || status.contains("expired") -> YaclTheme.colorError
            else -> YaclTheme.colorTextSub
        }
        g.drawText(status, w / 2 - g.textWidth(status) / 2, qrY + qrSize + 14, 11f, 1f, statusColor)

        rectRefreshBtn.x1 = w / 2 - 50; rectRefreshBtn.y1 = qrY + qrSize + 34
        rectRefreshBtn.x2 = w / 2 + 50; rectRefreshBtn.y2 = qrY + qrSize + 58
        YaclTheme.drawBtn(g, rectRefreshBtn, UiText.t("刷新二维码", "Refresh QR"), mouseX, mouseY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        if (rectBackBtn.hit(x, y)) { McScreens.open(back); return true }
        if (rectRefreshBtn.hit(x, y)) { startLogin(); return true }
        return super.mouseClicked(event, doubleClick)
    }

    override fun isPauseScreen(): Boolean = false
}
