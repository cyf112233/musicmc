package io.github.cyf112233.musicmc.ui.vanilla

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.mojang.blaze3d.platform.NativeImage

/**
 * 纯像素二维码生成(Android 兼容)。
 *
 * 旧 [io.github.cyf112233.musicmc.util.QrCode] 用 java.awt.image.BufferedImage +
 * ImageIO —— FCL 的 NDK OpenJDK 没有 java.desktop 模块,Android 上直接
 * NoClassDefFoundError。这里改用 ZXing BitMatrix 直写 [NativeImage](白底黑点),
 * 由渲染层注册为 MC 纹理后 blit(与 CoverTextureCache 同款流程,不依赖 Java2D)。
 */
object QrCodeNative {

    /**
     * 生成 [content] 的二维码 [NativeImage](RGBA,白底黑点)。
     * 任何异常返回 null(调用方自行提示失败)。
     */
    fun generate(content: String, size: Int = 240): NativeImage? {
        return try {
            val hints = mapOf<EncodeHintType, Any>(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val img = NativeImage(NativeImage.Format.RGBA, size, size, false)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    // setPixelABGR:ABGR int 经 memPutInt(小端)落到内存的字节序恰为 R,G,B,A
                    img.setPixelABGR(
                        x,
                        y,
                        if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(),
                    )
                }
            }
            img
        } catch (e: Exception) {
            null
        }
    }
}
