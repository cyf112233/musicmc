package io.github.cyf112233.musicmc.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 二维码生成(B 站扫码登录用)。基于 ZXing core 的 [QRCodeWriter],
 * 手绘到 TYPE_INT_RGB 的 BufferedImage(白底黑点)再编码为 PNG 字节。
 *
 * 使用方式:后台线程生成 [pngBytes] → UI 线程 decodeByteArray →
 * Image.createTextureFromBitmap → ImageView.setImage(见 BilibiliLoginFragment)。
 */
object QrCode {

    /**
     * 生成 [content] 的二维码 PNG 字节(纠错级别 M,默认 240x240)。
     * 任何异常返回 null(调用方自行提示失败)。
     */
    fun pngBytes(content: String, size: Int = 240): ByteArray? {
        return try {
            val hints = mapOf<EncodeHintType, Any>(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    image.setRGB(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            val out = ByteArrayOutputStream()
            ImageIO.write(image, "png", out)
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
}
