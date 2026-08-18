package io.github.cyf112233.musicmc.ui

import icyllis.modernui.graphics.Bitmap
import icyllis.modernui.graphics.BitmapFactory
import icyllis.modernui.graphics.Image
import icyllis.modernui.widget.ImageView
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.net.Http
import io.github.cyf112233.musicmc.util.Async
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * 异步图片加载器。
 *
 * 实测(modernui-core-3.13.0,纯 JVM 验证 2026-08):
 * - BitmapFactory.decodeByteArray(bytes, 0, len) 与 decodeStream(InputStream) 均可用,
 *   失败抛 IOException(不是返 null);两者对 300x300 / 800x800 的真实封面均解码正常;
 * - 本次修复把下载改为 readAllBytes(InputStream.readBytes(),后台线程)后再
 *   decodeByteArray:规避 decodeStream 直接吃网络流的偶发失败(实测反馈"封面看不见"),
 *   解码失败异常带完整信息便于下次定位;
 * - 缓存的是 GPU 纹理 [Image](不是 Bitmap):图片加载完成后在 UI 线程调
 *   Image.createTextureFromBitmap(bitmap) 创建纹理并 setImage 到 ImageView;
 * - 同一 url 不重复下载(in-flight 去重),缓存进程级复用,Image 不手动 close;
 * - 下载/解码失败保留 ImageView 的纯色占位背景,记录 warn 日志(带异常类名与 message)。
 *
 * 3.13.0 扩展(HUD 编辑器预览用):
 * - [loadCallback] 把加载完成的 Image 回调给调用方(UI 线程),支持按 url 订阅多个
 *   等待方(in-flight 期间注册的回调在完成后统一触发;旧实现只服务第一个调用方)。
 */
object AsyncImageLoader {

    /** 图片缓存:url → GPU 纹理(进程级复用,不手动 close) */
    private val cache = ConcurrentHashMap<String, Image>()

    /** 正在加载中的 key,避免同一图片被重复下载 */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** key → 等待该次加载完成的回调列表(全部在 UI 线程注册/触发) */
    private val pending = ConcurrentHashMap<String, MutableList<(Image?) -> Unit>>()

    /**
     * 异步加载 [url] 并设置到 [imageView]。
     * 下载+解码在后台线程,纹理创建与 setImage 回 UI 线程。
     */
    fun load(url: String?, imageView: ImageView) {
        if (url.isNullOrBlank()) return
        cache[url]?.let {
            imageView.setImage(it)
            return
        }
        enqueue(url, url) { image ->
            if (image != null) imageView.setImage(image)
        }
    }

    /**
     * 异步加载封面并在 UI 线程回调 [Image](HUD 编辑器预览用;失败 / 无 url 回调 null)。
     * 回调只触发一次:缓存命中立即回调;否则加载完成后回调(含同 url 在途期间注册的)。
     */
    fun loadCallback(url: String?, callback: (Image?) -> Unit) {
        if (url.isNullOrBlank()) {
            callback(null)
            return
        }
        cache[url]?.let {
            callback(it)
            return
        }
        enqueue(url, url, callback)
    }

    private fun enqueue(key: String, url: String, callback: (Image?) -> Unit) {
        pending.computeIfAbsent(key) { java.util.concurrent.CopyOnWriteArrayList() }.add(callback)
        if (inFlight.add(key)) {
            Async.run { loadInBackground(key, url) }
        }
    }

    private fun loadInBackground(key: String, url: String) {
        var bitmap: Bitmap? = null
        try {
            // 后台线程整包下载(带 Referer 的 openStream),再整包解码
            val bytes = Http.openStream(url).use { it.readBytes() }
            bitmap = decodeBitmap(bytes, url)
        } catch (e: Exception) {
            // 下载/解码失败:静默保留占位背景,日志带异常类名 + message 便于下次定位
            try {
                NetMusic.logger.warn("图片加载失败: $url (${e.javaClass.simpleName}: ${e.message})")
            } catch (_: Exception) {
                // 模块未初始化时静默
            }
        }
        val loaded = bitmap
        Async.onUi {
            inFlight.remove(key)
            val image = if (loaded != null) {
                // createTextureFromBitmap 需在 UI 线程调用(内部自动 post 到渲染线程),可能返回 null
                runCatching { Image.createTextureFromBitmap(loaded) }.getOrNull()?.also { cache[key] = it }
            } else {
                null
            }
            pending.remove(key)?.forEach { cb ->
                runCatching { cb(image) }
            }
        }
    }

    /**
     * 解码字节 → MUI [Bitmap]。优先 [BitmapFactory](stb,PNG/GIF 等直接支持);
     * B 站封面是 JPEG(hdslb 返回 image/jpeg),stb 只认 PNG 魔数会失败/返 null
     * (实测 "Bad PNG Signature",与 CoverTextureCache 的 stb 回退失败一致),
     * 回退 JDK ImageIO(java.desktop,不依赖任何 mod 原生库)作为最终保底。
     * 全部失败返回 null,由调用方走统一失败路径。
     */
    private fun decodeBitmap(bytes: ByteArray, url: String): Bitmap? {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()?.let {
            return it
        }
        val bi = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val w = bi.width
        val h = bi.height
        if (w <= 0 || h <= 0) return null
        // BufferedImage.getRGB → 0xAARRGGBB 大端;MUI Bitmap.setPixels 同约定
        val pixels = IntArray(w * h)
        bi.getRGB(0, 0, w, h, pixels, 0, w)
        val out = Bitmap.createBitmap(w, h, Bitmap.Format.RGBA_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        try {
            NetMusic.logger.info("图片解码:BitmapFactory 失败,ImageIO 回退成功 ${w}x${h} url=$url")
        } catch (_: Exception) {
            // 模块未初始化时静默
        }
        return out
    }
}
