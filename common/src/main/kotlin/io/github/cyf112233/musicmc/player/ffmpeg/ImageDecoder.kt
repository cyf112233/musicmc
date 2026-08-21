package io.github.cyf112233.musicmc.player.ffmpeg

import io.github.cyf112233.musicmc.client.UiText
import java.io.IOException
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer

/**
 * FFmpeg 图片解码输出:RGBA 像素(行序,行内 R,G,B,A 各 8bit)+ 宽高。
 * 放入 common(无 net.minecraft 依赖);NativeImage 组装由 client 侧
 * [io.github.cyf112233.musicmc.client.CoverTextureCache] 完成。
 */
data class RgbaImage(val data: ByteArray, val width: Int, val height: Int)

/**
 * FFmpeg 图片解码(内存缓冲,单次调用即开即闭)。
 *
 * 用途:封面字节 → RGBA 像素。覆盖 jpg / png / webp / avif 等 NativeImage.read(stb)
 * 不支持的现代格式(无后缀的 bilibili 图 CDN 会按 UA 返回 webp/avif)。
 *
 * 流程(每次 decode 独立上下文,后台线程安全;javacpp 手动管理原生资源防泄漏):
 * - 自定义 AVIO 读内存 ByteArray(avio_alloc_context + Java read/seek 回调);
 * - avformat_open_input + find_stream_info → 找 AVMEDIA_TYPE_VIDEO 流
 *   (图片/附件图在此类;纯图片只有一个流);
 * - avcodec 解码第一帧(动图 / 多帧格式取首帧);
 * - sws_scale 转 AV_PIX_FMT_RGBA → 拷贝到 ByteArray 返回。
 *
 * 失败返回 null(原生库不可用 / 无视频流 / 解码失败),调用方决定回退路径。
 */
object ImageDecoder {

    /** AVMEDIA_TYPE_VIDEO(avutil media_type 枚举,实测=0) */
    private const val AVMEDIA_TYPE_VIDEO = 0

    /** AVIO 缓冲 32KB(av_malloc 分配,avio_context_free 不释放,须自行 av_free) */
    private const val AVIO_BUFFER_SIZE = 32768

    /** SWS_BILINEAR(swscale 缩放/转换标志) */
    private const val SWS_BILINEAR = 2

    /** AVERROR(EAGAIN) */
    private const val AVERROR_EAGAIN = -11

    @Volatile
    private var swscaleAvailable: Boolean? = null

    /**
     * 解码图片字节 → RGBA(每调用独立上下文并释放;失败返回 null)。
     * 不抛异常(吞掉并返回 null),调用方负责日志。
     */
    fun decode(bytes: ByteArray): RgbaImage? {
        if (bytes.isEmpty()) return null
        if (!FfmpegDecoder.nativeAvailable()) return null
        if (!ensureSwscale()) return null
        val session = DecodeSession(bytes)
        return try {
            session.decode()
        } catch (t: Throwable) {
            null
        } finally {
            session.close()
        }
    }

    /** swscale 原生模块加载(幂等;与其他四模块独立,须单独 Loader.load) */
    private fun ensureSwscale(): Boolean {
        val cached = swscaleAvailable
        if (cached != null) return cached
        synchronized(this) {
            if (swscaleAvailable != null) return swscaleAvailable!!
            swscaleAvailable = try {
                Loader.load(swscale::class.java)
                true
            } catch (t: Throwable) {
                false
            }
            return swscaleAvailable!!
        }
    }

    /** 单次解码会话:持有所需原生资源句柄与内存读/寻址状态(仅解码线程访问) */
    internal class DecodeSession(private val bytes: ByteArray) {

        private var pos = 0L

        // ---- 原生资源句柄(finally 统一释放,顺序参照 FfmpegDecoder.closeNative) ----
        private var fmt: AVFormatContext? = null
        private var avio: AVIOContext? = null
        private var readCb: Read_packet_Pointer_BytePointer_int? = null
        private var seekCb: Seek_Pointer_long_int? = null
        private var codecCtx: AVCodecContext? = null
        private var swsCtx: SwsContext? = null
        private var packet: AVPacket? = null
        private var frame: AVFrame? = null
        private var outBuf: BytePointer? = null
        private var avioBuffer: BytePointer? = null

        fun decode(): RgbaImage? {
            // ---- 1. 内存 AVIO ----
            val bufRaw = avutil.av_malloc(AVIO_BUFFER_SIZE.toLong())
                ?: throw IOException(UiText.t("av_malloc 失败", "av_malloc failed"))
            val avioBuffer = BytePointer(bufRaw)
            this.avioBuffer = avioBuffer
            val reader = ImageReadCb(this)
            val seeker = ImageSeekCb(this)
            readCb = reader
            seekCb = seeker
            val avioCtx = avformat.avio_alloc_context(avioBuffer, AVIO_BUFFER_SIZE, 0, null, reader, null, seeker)
                ?: throw IOException(UiText.t("avio_alloc_context 失败", "avio_alloc_context failed"))
            avio = avioCtx

            val fmt = avformat.avformat_alloc_context() ?: throw IOException(UiText.t("avformat_alloc_context 失败", "avformat_alloc_context failed"))
            this.fmt = fmt
            fmt.pb(avioCtx)

            // ---- 2. 打开 + 找视频流 ----
            var ret = avformat.avformat_open_input(fmt, null as String?, null, null)
            if (ret < 0) throw IOException(UiText.t("avformat_open_input 失败($ret, AVERROR_${-ret})", "avformat_open_input failed ($ret, AVERROR_${-ret})"))
            ret = avformat.avformat_find_stream_info(fmt, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)
            if (ret < 0) throw IOException(UiText.t("avformat_find_stream_info 失败($ret)", "avformat_find_stream_info failed ($ret)"))

            val videoIdx = avformat.av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, null as AVCodec?, 0)
            if (videoIdx < 0) throw IOException(UiText.t("未找到图片/视频流($videoIdx)", "No image/video stream found ($videoIdx)"))
            val st = fmt.streams(videoIdx) ?: throw IOException(UiText.t("视频流不存在", "Video stream does not exist"))
            val par = st.codecpar() ?: throw IOException(UiText.t("缺少编解码器参数", "Missing codec parameters"))

            // ---- 3. 启动解码器 ----
            val codec: AVCodec = avcodec.avcodec_find_decoder(par.codec_id())
                ?: throw IOException(UiText.t("无可用解码器(codec_id=${par.codec_id()})", "No decoder available (codec_id=${par.codec_id()})"))
            val cc = avcodec.avcodec_alloc_context3(codec) ?: throw IOException(UiText.t("avcodec_alloc_context3 失败", "avcodec_alloc_context3 failed"))
            codecCtx = cc
            ret = avcodec.avcodec_parameters_to_context(cc, par)
            if (ret < 0) throw IOException(UiText.t("avcodec_parameters_to_context 失败($ret)", "avcodec_parameters_to_context failed ($ret)"))
            ret = avcodec.avcodec_open2(cc, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)
            if (ret < 0) throw IOException(UiText.t("avcodec_open2 失败($ret, AVERROR_${-ret})", "avcodec_open2 failed ($ret, AVERROR_${-ret})"))

            val pkt = avcodec.av_packet_alloc() ?: throw IOException(UiText.t("av_packet_alloc 失败", "av_packet_alloc failed"))
            packet = pkt
            val frm = avutil.av_frame_alloc() ?: throw IOException(UiText.t("av_frame_alloc 失败", "av_frame_alloc failed"))
            frame = frm

            // ---- 4. 读第一帧(纯图片 1 帧;动图 / 多帧取首帧即返回) ----
            var guard = 0
            while (guard++ < 10_000) { // 防御:异常容器/恶意文件死循环
                val r = avformat.av_read_frame(fmt, pkt)
                if (r < 0) break // AVERROR_EOF / 错误
                if (pkt.stream_index() != videoIdx) {
                    avcodec.av_packet_unref(pkt)
                    continue
                }
                val sr = avcodec.avcodec_send_packet(cc, pkt)
                avcodec.av_packet_unref(pkt)
                if (sr < 0 && sr != AVERROR_EAGAIN) throw IOException(UiText.t("avcodec_send_packet 失败($sr)", "avcodec_send_packet failed ($sr)"))
                while (true) {
                    val rr = avcodec.avcodec_receive_frame(cc, frm)
                    if (rr == AVERROR_EAGAIN) break
                    if (rr < 0) break // EOF / 错误
                    return convertToRgba(frm)
                }
            }
            return null
        }

        // ---------------- read / seek 回调(内存 AVIO) ----------------

        fun read(buf: BytePointer, bufSize: Int): Int {
            if (pos >= bytes.size) return FfmpegDecoder.AVERROR_EOF
            val n = minOf(bufSize.toLong(), bytes.size - pos).toInt()
            if (n <= 0) return FfmpegDecoder.AVERROR_EOF
            buf.put(bytes, pos.toInt(), n)
            pos += n
            return n
        }

        fun seek(offset: Long, whence: Int): Long {
            return when (whence) {
                FfmpegDecoder.SEEK_SET -> {
                    pos = offset.coerceIn(0, bytes.size.toLong()); pos
                }
                FfmpegDecoder.SEEK_CUR -> {
                    pos = (pos + offset).coerceIn(0, bytes.size.toLong()); pos
                }
                FfmpegDecoder.SEEK_END -> {
                    pos = (bytes.size + offset).coerceIn(0, bytes.size.toLong()); pos
                }
                FfmpegDecoder.AVSEEK_SIZE -> bytes.size.toLong()
                else -> -22L // EINVAL
            }
        }

        // ---------------- 帧 → RGBA ----------------

        private fun convertToRgba(frm: AVFrame): RgbaImage {
            val w = frm.width()
            val h = frm.height()
            if (w <= 0 || h <= 0) throw IOException(UiText.t("帧尺寸无效(w=$w h=$h)", "Invalid frame dimensions (w=$w h=$h)"))
            val srcFmt = frm.format()
            val dstFmt = avutil.av_get_pix_fmt("rgba")
            if (dstFmt < 0) throw IOException(UiText.t("av_get_pix_fmt(rgba) 失败($dstFmt)", "av_get_pix_fmt(rgba) failed ($dstFmt)"))
            val sws = swscale.sws_getContext(w, h, srcFmt, w, h, dstFmt, SWS_BILINEAR, null, null, null as org.bytedeco.javacpp.DoublePointer?)
                ?: throw IOException(UiText.t("sws_getContext 失败", "sws_getContext failed"))
            swsCtx = sws

            val planes = avutil.av_pix_fmt_count_planes(srcFmt).coerceAtLeast(1)
            val srcSlice = PointerPointer<Pointer>(planes.toLong())
            val srcStride = IntPointer(planes)
            try {
                for (i in 0 until planes) srcSlice.put(i.toLong(), frm.data(i))
                for (i in 0 until planes) srcStride.put(i, frm.linesize(i))
                val outSize = w.toLong() * h * 4
                val out = BytePointer(outSize)
                outBuf = out
                val dst = PointerPointer<Pointer>(1)
                val dstStride = IntPointer(1)
                try {
                    dst.put(0, out)
                    dstStride.put(0, w * 4)
                    val ret = swscale.sws_scale(sws, srcSlice, srcStride, 0, h, dst, dstStride)
                    if (ret < 0) throw IOException(UiText.t("sws_scale 失败($ret)", "sws_scale failed ($ret)"))
                    val rgba = ByteArray(outSize.toInt())
                    out.get(rgba)
                    return RgbaImage(rgba, w, h)
                } finally {
                    dst.deallocate()
                    dstStride.deallocate()
                }
            } finally {
                srcSlice.deallocate()
                srcStride.deallocate()
            }
        }

        // ---------------- 释放 ----------------

        fun close() {
            runCatching { avcodec.avcodec_free_context(codecCtx) }
            swsCtx?.let { runCatching { swscale.sws_freeContext(it) } }
            runCatching { avformat.avformat_close_input(fmt) }
            val av = avio
            if (av != null && !av.isNull) runCatching { avformat.avio_context_free(av) }
            val buf = avioBuffer
            if (buf != null && !buf.isNull) runCatching { avutil.av_free(buf) }
            runCatching { avcodec.av_packet_free(packet) }
            runCatching { avutil.av_frame_free(frame) }
            runCatching { outBuf?.deallocate() }
            runCatching { readCb?.deallocate() }
            runCatching { seekCb?.deallocate() }
            fmt = null
            avio = null
            readCb = null
            seekCb = null
            codecCtx = null
            swsCtx = null
            packet = null
            frame = null
            outBuf = null
            avioBuffer = null
        }
    }
}

// ---------------- 内存 AVIO 回调(javacpp 运行时 Java 回调,机制同 FfmpegDecoder) ----------------

/** read 回调:从内存 ByteArray 读入 FFmpeg 请求的 bufSize 字节;EOF 返回 AVERROR_EOF */
private class ImageReadCb(private val session: ImageDecoder.DecodeSession) : Read_packet_Pointer_BytePointer_int() {
    override fun call(opaque: Pointer?, buf: BytePointer?, bufSize: Int): Int {
        if (buf == null) return FfmpegDecoder.AVERROR_EOF
        return try {
            session.read(buf, bufSize)
        } catch (t: Throwable) {
            FfmpegDecoder.AVERROR_EOF
        }
    }
}

/** seek 回调:定位并按 whence 返回绝对位置(负值=错误);AVSEEK_SIZE 返回总字节数 */
private class ImageSeekCb(private val session: ImageDecoder.DecodeSession) : Seek_Pointer_long_int() {
    override fun call(opaque: Pointer?, offset: Long, whence: Int): Long {
        return try {
            session.seek(offset, whence)
        } catch (t: Throwable) {
            -1L
        }
    }
}