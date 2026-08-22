package io.github.cyf112233.musicmc.client

import com.mojang.blaze3d.platform.NativeImage
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.net.Http
import io.github.cyf112233.musicmc.player.ffmpeg.ImageDecoder
import io.github.cyf112233.musicmc.player.ffmpeg.RgbaImage
import io.github.cyf112233.musicmc.util.Async
import io.github.cyf112233.musicmc.client.UiText
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.TextureManager
import net.minecraft.resources.Identifier
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.ImageIO

/**
 * HUD 封面纹理缓存(仅 fabric / neoforge 编译,允许 net.minecraft;common 不含此类)。
 *
 * 生命周期(修复"游戏内封面不显示"):
 * - 后台线程:Http.openStream(url) 整包下载(带 B 站 Referer)→ 优先
 *   [ImageDecoder.decode](FFmpeg 解内存缓冲,覆盖 jpg/png/webp/avif;失败/Native 不可用
 *   回退 NativeImage.read 保底)→ CPU 端中心裁剪成方形(见 [cropSquare])→
 *   放入 [readyQueue] 待注册;
 * - 纹理创建(不再是 Async.onUi!):DynamicTexture(Supplier, NativeImage) 构造即
 *   createTexture + upload(javap 已核实),必须在渲染线程 / GL 上下文内执行;
 *   Async.onUi 切到的是 MUI 主线程,不是 GL 渲染线程,故游戏内封面一直不显示。
 *   改为:游戏内 [MusicHudRenderer.onFrame](extract/渲染回调,GL 上下文有效)
 *   每帧调用 [pump] 消费 [readyQueue],在此创建 DynamicTexture 并 register。
 *   26.1.2 无 RenderSystem.recordRenderCall(javap 已核实),onFrame 即 GL 有效点,
 *   不再需要别的等价物。
 * - key = url;换歌时先 release 旧纹理再注册新的
 *   (register 对已存在 id 会自动 safeClose,双保险;AbstractTexture.close 幂等);
 * - 下载 / 解码失败静默:渲染层画占位块。
 *
 * 线程模型:textures 映射与 register/release 全部在渲染回调(onFrame → pump)访问;
 * 后台线程只做下载 / 解码,把结果放进 [readyQueue] 由 pump 驱动。
 */
object CoverTextureCache {

    private const val NAMESPACE = "musicmc"

    /** 已注册纹理:key → AbstractTexture(仅渲染回调线程访问) */
    private val textures = HashMap<String, AbstractTexture>()

    /** 已注册纹理的源图片宽高:key → (w, h)(仅渲染回调线程访问;预裁剪后恒为方形) */
    private val sizes = HashMap<String, Pair<Int, Int>>()

    /** 当前展示的 key(@Volatile:后台线程比对"是否已切走") */
    @Volatile
    private var currentKey: String? = null

    /** 正在下载 / 解码的 key(后台线程与主线程并发访问) */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** 后台解码完成、等待渲染回调创建纹理:key → 已遮罩的 NativeImage(队列由 pump 消费) */
    private val readyQueue = ConcurrentLinkedQueue<Pair<String, NativeImage>>()

    private val mc: Minecraft get() = Minecraft.getInstance()
    private val textureManager: TextureManager get() = mc.textureManager

    /** 统一 [Cover] 前缀日志(模块未初始化时 logger 不可用,静默;url 截 100 防刷屏) */
    private fun logInfo(msg: String) = runCatching { NetMusic.logger.info("[Cover] $msg") }
    private fun logWarn(msg: String) = runCatching { NetMusic.logger.warn("[Cover] $msg") }
    private fun urlTag(url: String): String = if (url.length > 100) url.take(100) + "…" else url

    /** 封面缓存 key:url(空 url 用 "none",即无封面) */
    fun keyFor(url: String?): String = url ?: "none"

    /** 当前纹理对应的 Identifier(纹理未注册返回 null) */
    fun currentIdentifier(): Identifier? {
        val key = currentKey ?: return null
        if (key !in textures) return null
        return identifier(key)
    }

    /** 当前纹理的源图片宽高(纹理未注册返回 null;预裁剪后恒为方形,仅日志用) */
    fun currentImageSize(): Pair<Int, Int>? {
        val key = currentKey ?: return null
        return sizes[key]
    }

    /**
     * 换歌时调用(渲染回调线程)。立即切换到新 key(旧纹理释放),
     * 纹理未缓存时触发后台加载。返回 true 表示新纹理已就绪可直接绘制。
     */
    fun prepare(url: String?): Boolean {
        val key = keyFor(url)
        val old = currentKey
        if (key != old) {
            currentKey = key
            old?.let { releaseKey(it) }
        }
        if (url.isNullOrBlank()) return false
        if (key in textures) return true
        if (!pending.add(key)) return false
        Async.run { loadInBackground(key, url) }
        return false
    }

    /**
     * 渲染回调每帧调用(MusicHudRenderer.onFrame 开头):在 GL 上下文内消费
     * [readyQueue],把后台解码好的 NativeImage 创建为 DynamicTexture 并注册。
     * 加载期间已切走的图直接释放;创建失败记录日志,渲染层画占位块。
     */
    fun pump() {
        while (true) {
            val item = readyQueue.poll() ?: return
            val key = item.first
            val image = item.second
            pending.remove(key)
            if (key != currentKey) {
                // 加载期间已切到别的歌 / 形状:丢弃这张图
                logWarn("队列项丢弃:key=$key 已切走(当前 currentKey=$currentKey)")
                runCatching { image.close() }
                continue
            }
            try {
                val texture = DynamicTexture({ "musicmc_hud_cover" }, image)
                textureManager.register(identifier(key), texture)
                textures[key] = texture
                sizes[key] = image.getWidth() to image.getHeight()
                logInfo("纹理注册成功:key=$key ${image.getWidth()}x${image.getHeight()}")
            } catch (e: Exception) {
                // 理论上渲染回调内 GL 上下文总是有效;万一失败释放像素并静默
                // (26.1.2 无 RenderSystem.recordRenderCall 可兜底,javap 已核实)
                logWarn("纹理注册异常:${e.javaClass.simpleName}: ${e.message} key=$key")
                runCatching { image.close() }
            }
        }
    }

    private fun loadInBackground(key: String, url: String) {
        try {
            val bytes = Http.openStream(url).use { it.readBytes() }
            logInfo("请求下载完成:url=${urlTag(url)} 字节=${bytes.size}")
            // 优先 FFmpeg 解码(内存 AVIO,覆盖 stb 不支持的 webp/avif 等现代格式);
            // 失败(原生库不可用 / 无视频流 / 解码异常)→ 回退 NativeImage.read(stb) 保底
            val decoded = runCatching { ImageDecoder.decode(bytes) }.getOrElse { t ->
                // decode 内部吞异常返回 null,此路很少走到;兜底记录真实异常
                logWarn("FFmpeg 解码异常:${t.javaClass.simpleName}: ${t.message} url=${urlTag(url)}")
                null
            }
            val image = if (decoded != null) {
                logInfo("FFmpeg 解码成功:${decoded.width}x${decoded.height} url=${urlTag(url)}")
                buildNativeImage(decoded)
            } else {
                logWarn("FFmpeg 解码失败(无结果),回退 stb/ImageIO 解码: url=${urlTag(url)}")
                decodeFallback(bytes, url)
            }
            // CPU 端中心裁剪成方形:渲染层 blit 恒用全图对称 UV(0,0,1,1),
            // 不依赖任何 MC 版本对 blit UV 参数顺序的定义 —— 这是 MC 版本差异
            // 重灾区(26.1 的九参 blit 按 u0,u1,v0,v1 解释,旧版按 u0,v0,u1,v1),
            // 预裁剪后无论底层怎么解释,显示结果都是整张方形封面,方向 / 比例永远正确。
            // 纹理创建(需要 GL 上下文)交给渲染回调 pump。
            val cropped = cropSquare(image)
            readyQueue.add(key to cropped)
            logInfo("入队待注册:key=$key ${cropped.getWidth()}x${cropped.getHeight()} url=${urlTag(url)}")
        } catch (e: Exception) {
            pending.remove(key)
            logWarn("加载失败:url=${urlTag(url)} ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * FFmpeg 解码结果(RGBA 行序字节)→ NativeImage(Format.RGBA)。
     * 26.1.2 无 setPixelRGBA(javap 已核实),逐像素 setPixelABGR 直写:
     * ABGR int 经 memPutInt(小端)落到内存的字节序恰为 R,G,B,A,与 FFmpeg 输出一致。
     */
    private fun buildNativeImage(rgba: RgbaImage): NativeImage {
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

    /**
     * 中心裁剪成方形:取源图较短边为边长,从中心裁出方形区域。
     * 原图已方形时原样返回;否则新建方形图并复制中心区域后关闭原图。
     * (B 站封面 16:9 → 裁左右;FFmpeg / stb / ImageIO 三条解码路径统一在此裁剪)
     */
    private fun cropSquare(img: NativeImage): NativeImage {
        val w = img.getWidth()
        val h = img.getHeight()
        if (w <= 0 || h <= 0 || w == h) return img
        val side = minOf(w, h)
        val x0 = (w - side) / 2
        val y0 = (h - side) / 2
        val out = NativeImage(NativeImage.Format.RGBA, side, side, false)
        // 26.1 NativeImage.copyRect 语义(javap 核实):this 读、参数(source 参数)写,
        // 即 img.copyRect(out, srcX, srcY, dstX, dstY, w, h, flipX, flipY)
        // = 把 img(this) 的 (srcX, srcY) 起 side×side 拷贝到 out(参数) 的 (dstX, dstY)。
        // 方向写反会从目标图(较小)越界读,抛 "outside of image bounds"。
        // 不用 getPixelABGR(26.1 已改 private)逐像素拷贝。
        img.copyRect(out, x0, y0, 0, 0, side, side, false, false)
        runCatching { img.close() }
        return out
    }

    /**
     * 回退解码链:stb [NativeImage.read](仅 PNG)→ ImageIO(JPEG/GIF/BMP/PNG)。
     * native jar 缺 libswscale 时 FFmpeg 路径恒失败,而 B 站封面是 JPEG(hdslb
     * 返回 image/jpeg),stb 的 read 只认 PNG 魔数会抛 "Bad PNG Signature" ——
     * ImageIO 是 JDK 自带(java.desktop),不依赖任何 mod 原生库,作为最终保底。
     * 失败抛异常,由调用方统一失败路径处理(释放 / 记录加载失败)。
     */
    private fun decodeFallback(bytes: ByteArray, url: String): NativeImage {
        try {
            return NativeImage.read(bytes).also {
                logInfo("stb 回退解码成功:${it.getWidth()}x${it.getHeight()} url=${urlTag(url)}")
            }
        } catch (e: Exception) {
            logWarn("stb 回退解码失败:${e.javaClass.simpleName}: ${e.message} url=${urlTag(url)}")
        }
        try {
            val bi = ImageIO.read(ByteArrayInputStream(bytes))
                ?: throw IOException(UiText.t("ImageIO 无法识别图片格式", "ImageIO cannot recognize image format"))
            val img = copyArgbToNativeImage(bi)
            logInfo("ImageIO 回退解码成功:${img.getWidth()}x${img.getHeight()} url=${urlTag(url)}")
            return img
        } catch (e: Exception) {
            logWarn("ImageIO 回退解码失败:${e.javaClass.simpleName}: ${e.message} url=${urlTag(url)}")
            throw e
        }
    }

    /**
     * BufferedImage(ARGB)→ NativeImage(RGBA)。getRGB 返回 AARRGGBB 大端 int,
     * 拆出分量后按 [buildNativeImage] 相同的 ABGR 约定写回(setPixelABGR 内存字节序 = R,G,B,A)。
     */
    private fun copyArgbToNativeImage(bi: BufferedImage): NativeImage {
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

    /** 释放某 key 的纹理(remove + TextureManager.release,内部会 close;再补一次 close 幂等兜底) */
    private fun releaseKey(key: String) {
        sizes.remove(key)
        val tex = textures.remove(key) ?: return
        runCatching { textureManager.release(identifier(key)) }
        runCatching { tex.close() }
    }

    /**
     * key → Identifier。自查修复(2026-08):旧实现把 url 清洗后只截 64 字符,长 url 前缀
     * 相同的不同 key 会撞同一 Identifier,TextureManager 按 id register/release 会互相覆盖
     * (释放 A 的纹理时把同 id 的 B 纹理一起 close,导致 B 换上后渲染白图)。
     * 改用全 key 的 SHA-256 前 24 字符 hex:无碰撞,且 [a-f0-9] 均在 Identifier 路径
     * 白名单 [a-z0-9/._-] 内(每次调用新建 MessageDigest,仅在渲染回调线程低频调用,无需复用)。
     */
    private fun identifier(key: String): Identifier {
        val digest = MessageDigest.getInstance("SHA-256")
        val hex = digest.digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(24)
        return Identifier.fromNamespaceAndPath(NAMESPACE, "hud_cover_$hex")
    }
}
