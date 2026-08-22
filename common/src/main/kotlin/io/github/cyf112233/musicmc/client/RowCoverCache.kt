package io.github.cyf112233.musicmc.client

import com.mojang.blaze3d.platform.NativeImage
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.net.Http
import io.github.cyf112233.musicmc.player.ffmpeg.ImageDecoder
import io.github.cyf112233.musicmc.util.Async
import io.github.cyf112233.musicmc.client.UiText
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.TextureManager
import net.minecraft.resources.Identifier
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 歌曲列表行封面缓存(搜索 / 歌单 / 队列 / 收藏等列表页用)。
 *
 * 与 [CoverTextureCache](当前播放大封面,单 key)不同:列表页同时展示几十个
 * 不同歌曲封面,这里维护 **key → 纹理** 的多槽缓存:
 * - 后台线程按 url 下载 → 解码(FFmpeg → stb → ImageIO 三级回退)→ CPU 中心
 *   裁剪成小方形缩略图(缩到 32px,省显存与带宽);
 * - 渲染回调([pump])在 GL 上下文内消费就绪队列,创建 DynamicTexture 注册;
 * - 缓存上限 [MAX_TEXTURES] 个,超出按 FIFO 淘汰并释放纹理(防长列表内存膨胀);
 * - 同一 url 去重([pending] 集合),失败静默(渲染层画占位块)。
 *
 * 线程模型与 CoverTextureCache 一致:纹理映射与 register/release 全部在渲染
 * 回调线程访问;后台线程只下载 / 解码,结果放 [readyQueue] 由 [pump] 消费。
 */
object RowCoverCache {

    private const val NAMESPACE = "musicmc"

    /** 缓存上限(纹理数):长列表滚动时淘汰最旧,防显存 / 句柄膨胀 */
    private const val MAX_TEXTURES = 128

    /** 失败冷却(ms):失败 url 在冷却期内不重试(防坏 url 反复重试刷队列);
     *  到期后允许重试 —— 网络临时故障恢复后封面能重新加载(永久黑名单会让
     *  一次网络抖动导致封面永远不显示) */
    private const val FAILED_COOLDOWN_MS = 5 * 60_000L

    /** 已注册纹理:key → AbstractTexture(仅渲染回调线程访问) */
    private val textures = HashMap<String, AbstractTexture>()

    /** 纹理注册顺序(FIFO 淘汰用;仅渲染回调线程访问) */
    private val order = ArrayDeque<String>()

    /** 正在下载 / 解码的 key(后台线程与主线程并发访问) */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** 后台解码完成、等待渲染回调创建纹理:key → 缩略 NativeImage */
    private val readyQueue = ConcurrentLinkedQueue<Pair<String, NativeImage>>()

    /** 失败冷却:key → 上次失败时间戳(后台线程写,渲染线程读) */
    private val failed = ConcurrentHashMap<String, Long>()

    private val mc: Minecraft get() = Minecraft.getInstance()
    private val textureManager: TextureManager get() = mc.textureManager

    private fun logWarn(msg: String) = runCatching { NetMusic.logger.warn("[RowCover] $msg") }

    /** 行封面纹理是否就绪(url 为空 / 加载失败返回 null,渲染层画占位块) */
    fun identifier(url: String?): Identifier? {
        if (url.isNullOrBlank()) return null
        val key = keyFor(url)
        if (key !in textures) return null
        return idFor(key)
    }

    /** 渲染回调每帧调用:消费就绪队列创建纹理(必须 GL 上下文内) */
    fun pump() {
        while (true) {
            val item = readyQueue.poll() ?: return
            val key = item.first
            val image = item.second
            pending.remove(key)
            try {
                val texture = DynamicTexture({ "musicmc_row_cover" }, image)
                textureManager.register(idFor(key), texture)
                textures[key]?.let { runCatching { it.close() } }
                textures[key] = texture
                order.remove(key)
                order.addLast(key)
                // FIFO 淘汰
                while (order.size > MAX_TEXTURES) {
                    val old = order.removeFirst()
                    releaseKey(old)
                }
            } catch (e: Exception) {
                logWarn("纹理注册异常:${e.javaClass.simpleName}: ${e.message} key=${key.take(60)}")
                runCatching { image.close() }
            }
        }
    }

    /**
     * 确保某 url 的封面开始后台加载(幂等;渲染回调线程调用)。
     * 已缓存 / 加载中 / 失败冷却期内 直接返回。
     */
    fun request(url: String?) {
        if (url.isNullOrBlank()) return
        val key = keyFor(url)
        if (key in textures || key in pending) return
        // 失败冷却:冷却期内不重试,到期(网络恢复)重新尝试
        val failAt = failed[key]
        if (failAt != null && System.currentTimeMillis() - failAt < FAILED_COOLDOWN_MS) return
        if (!pending.add(key)) return
        Async.run { loadInBackground(key, url) }
    }

    /** 清空全部缓存(切页 / 资源回收;渲染回调线程调用) */
    fun clear() {
        val keys = textures.keys.toList()
        keys.forEach { releaseKey(it) }
        readyQueue.clear()
        pending.clear()
        failed.clear()
    }

    private fun loadInBackground(key: String, url: String) {
        try {
            val bytes = Http.openStream(url).use { it.readBytes() }
            val decoded = runCatching { ImageDecoder.decode(bytes) }.getOrNull()
            val img = if (decoded != null) {
                buildNativeImage(decoded)
            } else {
                decodeFallback(bytes)
            }
            val thumb = cropSquare(img)
            readyQueue.add(key to thumb)
        } catch (e: Exception) {
            failed[key] = System.currentTimeMillis()
            pending.remove(key)
            logWarn("封面加载失败:${e.javaClass.simpleName}: ${e.message} url=${url.take(80)}")
        }
    }

    /** FFmpeg 解码结果(RGBA 行序)→ NativeImage(RGBA),ABGR 约定同 CoverTextureCache */
    private fun buildNativeImage(rgba: io.github.cyf112233.musicmc.player.ffmpeg.RgbaImage): NativeImage {
        val img = NativeImage(NativeImage.Format.RGBA, rgba.width, rgba.height, false)
        val data = rgba.data
        var p = 0
        for (y in 0 until rgba.height) {
            for (x in 0 until rgba.width) {
                val r = data[p].toInt() and 0xFF
                val g = data[p + 1].toInt() and 0xFF
                val b = data[p + 2].toInt() and 0xFF
                val a = data[p + 3].toInt() and 0xFF
                p += 4
                img.setPixelABGR(x, y, (a shl 24) or (b shl 16) or (g shl 8) or r)
            }
        }
        return img
    }

    /** stb(NativeImage.read)→ ImageIO 回退(同 CoverTextureCache 语义) */
    private fun decodeFallback(bytes: ByteArray): NativeImage {
        try {
            return NativeImage.read(bytes)
        } catch (_: Exception) {
        }
        val bi = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
            ?: throw java.io.IOException(UiText.t("ImageIO 无法识别图片格式", "ImageIO cannot recognize image format"))
        val w = bi.width
        val h = bi.height
        val img = NativeImage(NativeImage.Format.RGBA, w, h, false)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = bi.getRGB(x, y)
                val a = (argb ushr 24) and 0xFF
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                img.setPixelABGR(x, y, (a shl 24) or (b shl 16) or (g shl 8) or r)
            }
        }
        return img
    }

    /**
     * 中心裁剪成方形(26.1 的 NativeImage.getPixelABGR 是 private,无法逐像素
     * 降采样;保留方形原尺寸,绘制时由 drawTexture GPU 缩放 —— 列表行封面
     * 小,GPU 双线性缩放质量足够,且省去 CPU 缩放)。
     */
    private fun cropSquare(img: NativeImage): NativeImage {
        val w = img.getWidth()
        val h = img.getHeight()
        if (w <= 0 || h <= 0) return img
        val side = minOf(w, h)
        if (w == h) return img
        val x0 = (w - side) / 2
        val y0 = (h - side) / 2
        val out = NativeImage(NativeImage.Format.RGBA, side, side, false)
        // 26.1 copyRect 语义:this 读、参数(source)写 = img.copyRect(out, srcX, srcY, dstX, dstY, w, h)
        img.copyRect(out, x0, y0, 0, 0, side, side, false, false)
        runCatching { img.close() }
        return out
    }

    private fun releaseKey(key: String) {
        val tex = textures.remove(key) ?: return
        runCatching { textureManager.release(idFor(key)) }
        runCatching { tex.close() }
    }

    private fun keyFor(url: String): String = url

    private fun idFor(key: String): Identifier {
        val digest = MessageDigest.getInstance("SHA-256")
        val hex = digest.digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(24)
        return Identifier.fromNamespaceAndPath(NAMESPACE, "row_cover_$hex")
    }
}
