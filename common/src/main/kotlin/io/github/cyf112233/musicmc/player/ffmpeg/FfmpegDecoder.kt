package io.github.cyf112233.musicmc.player.ffmpeg

import io.github.cyf112233.musicmc.net.Http
import io.github.cyf112233.musicmc.player.DecodedAudio
import java.io.IOException
import java.io.InputStream
import kotlin.jvm.Synchronized
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer

/**
 * FFmpeg 解码核心(纯解码,不依赖 javax.sound,可离线冒烟)。
 *
 * 职责:URL/本地路径 → avformat demux → avcodec decode → swr_convert 转 s16 交错 PCM。
 * - HTTP 走**自定义 AVIO**(avio_alloc_context + Java 回调):read_packet 用
 *   [io.github.cyf112233.musicmc.net.Http] 读 Java HTTP 流(带 Referer);
 *   seek 回调按 offset 关闭旧流、重新 openStreamInfo(CDN 支持 Range 的 206 直接定位,
 *   忽略 Range 的 200 全量则丢弃 offset 字节);Range 重开失败(异常/403)时回退
 *   全量流(Http.openStream + 丢弃 offset 字节),seek 回调返回 0;全量流也失败返回 -1,
 *   由上层"重开+丢弃"兜底。64KB 缓冲 AVIO 由 FFmpeg 管理。
 * - 本地路径(冒烟 / 无网络场景)直接 avformat_open_input(路径),FFmpeg 原生 file 协议
 *   (native 构建已 --enable-protocol=file),不需要 AVIO。
 * - 原生库不可用(未打包平台 jar 等)→ [nativeAvailable] 返回 false,
 *   上层(MusicPlayer.loadUrl)据此直接报"平台不支持播放"并跳过引擎加载。
 *
 * 线程安全(加固):全部对外原生操作(open/decodeFrame/seekTo/close 及查询)以
 * **synchronized(this) 互斥**,与 closeNative 的释放串行化 —— 防止 seek 线程/
 * seek 回调在 close 释放后仍触碰已 free 的原生指针(实测连续快拖下 use-after-free
 * 在 avcodec_free_context 处 SIGSEGV)。锁方向恒为 this → httpLock(回调内
 * synchronized(httpLock)),无反向获取,无死锁。阻塞中的 av_read_frame 仍可被
 * [interruptRead](仅 httpLock,不取 this)打断解锁。
 *
 * 生命周期:open→(decodeFrame|seekTo)*→close;close 可重入(置 closed)。
 *
 * 原生资源生命周期(javacpp 手动管理,不依赖 PointerScope,防泄漏):
 *   avformat_close_input(对 CUSTOM_IO 不负责任 pb,见 FFmpeg 7.1 demux.c)
 *   → avio_context_free(仅释放 AVIOContext 结构体,不释放 buffer/opaque)
 *   → av_free(avio.buffer())(FFmpeg 探测时可能内部 realloc 替换 buffer
 *   —— avio.buffer() 恒为当前存活的那个,av_free 它要么是我们的原缓冲要么是 FFmpeg 的,
 *   被替换掉的旧缓冲已由 ffio_realloc_buf 的 av_free 释放,不会双释放)。
 */
class FfmpegDecoder {

    companion object {
        // AVERROR(EAGAIN)/AVERROR_EOF 常量(经 javacpp 绑定数值核验)
        const val AVERROR_EAGAIN = -11
        const val AVERROR_EOF = -541478725 // MKTAG('E','O','F',' ')
        const val AVERROR_INVALIDDATA = -1094995529 // MKTAG('I','N','D','A'),实测日志值
        const val AVSEEK_SIZE = 0x10000
        const val AVSEEK_FLAG_BACKWARD = 1

        /** 连续坏包超过该阈值即视为流彻底损坏,报错中止(防止跳过坏包导致无限静音) */
        private const val MAX_CONSECUTIVE_BAD_PACKETS = 64

        /** 连续坏包达到该阈值先 avcodec_flush_buffers 重置解码器(丢弃半解码状态)再继续 */
        private const val BAD_PACKET_FLUSH_THRESHOLD = 16

        /** AVMEDIA_TYPE_AUDIO(avutil media_type 枚举,实测=1) */
        const val AVMEDIA_TYPE_AUDIO = 1

        /** AV_SAMPLE_FMT_S16(交错 16bit,swr 输出格式;枚举实测=1) */
        const val AV_SAMPLE_FMT_S16 = 1

        /** AV_CHANNEL_ORDER_NATIVE */
        const val AV_CHANNEL_ORDER_NATIVE = 0

        /** AVIO 缓冲 64KB(av_malloc 分配,avio_context_free 不释放,须自行 av_free) */
        private const val AVIO_BUFFER_SIZE = 65536

        const val SEEK_SET = 0
        const val SEEK_CUR = 1
        const val SEEK_END = 2

        @Volatile
        private var available: Boolean? = null

        private val availableLock = Object()

        /**
         * 静态初始化四个 global 类(触发 Loader 加载 libjni{avcodec,avformat,avutil,swresample}.so
         * 与四兄弟 libav*.so);任一失败(未打包平台 / JDK 限制 / 架构不符)一律视为不可用。
         * 幂等;首次调用缓存结果。注意:必须在首次 Loader.load 前由调用方把
         * System property "org.bytedeco.javacpp.platform" 设为平台名(ModConfig.nativePlatformOverride)。
         *
         * windows-arm64 例外:javacpp Loader 无该平台映射,不经过 Loader;可用性取决于
         * [NativeLibBridge.preloadIfNeeded](NetMusic.init 时执行)的结果 [NativeLibBridge.manualLoaded]。
         */
        fun nativeAvailable(): Boolean {
            // windows-arm64:仅当手动桥接全部加载成功才算可用(直接读标志,不再触发 Loader)
            if (NativeLibBridge.isWindowsArm64()) return NativeLibBridge.manualLoaded
            val cached = available
            if (cached != null) return cached
            synchronized(availableLock) {
                if (available != null) return available!!
                available = try {
                    Loader.load(avutil::class.java)
                    Loader.load(avformat::class.java)
                    Loader.load(avcodec::class.java)
                    Loader.load(swresample::class.java)
                    true
                } catch (t: Throwable) {
                    // UnsatisfiedLinkError / NoClassDefFoundError / ExceptionInInitializerError /
                    // IOException(Loader) 等:全部视为"原生库不可用",由调用方提示平台不支持播放
                    io.github.cyf112233.musicmc.NetMusic.logger.info("FFmpeg native libs failed to load: ${t.javaClass.simpleName}: ${t.message}")
                    false
                }
                return available!!
            }
        }
    }

    // ---------------- 输出 ----------------

    /**
     * 打开后的格式信息(open 成功后有效)。
     */
    class FormatInfo(
        val sampleRate: Int,
        val channels: Int,
        /** 总时长毫秒(avformat 估算,未知为 0) */
        val durationMs: Int,
    )

    // ---------------- 状态 ----------------

    private var fmtCtx: AVFormatContext? = null
    private var codecCtx: AVCodecContext? = null
    private var swrCtx: SwrContext? = null
    private var avio: AVIOContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null

    /** Java 回调强引用(回调对象本身是 native 函数对象,须防 GC;close 时 deallocate) */
    private var avioReadCb: Read_packet_Pointer_BytePointer_int? = null
    private var avioSeekCb: Seek_Pointer_long_int? = null

    private var audioStreamIndex = -1

    /** 音频流 time_base(pts 换算毫秒用) */
    private var tbNum = 1
    private var tbDen = 48000

    /** @Volatile:UI 线程可能经 positionMs 无锁读取(解码线程写) */
    @Volatile
    private var sampleRate = 0
    private var channels = 0
    private var durationMs = 0

    /** 已解码样本数(单声道样本;含 seek 丢弃帧),positionMs 的样本估算法数据源 */
    @Volatile
    private var samplesDecoded = 0L

    /** 最近一帧 pts(毫秒);AV_NOPTS_VALUE 时用样本估算 */
    @Volatile
    private var lastPtsMs = -1L

    /**
     * 连续坏包计数(avcodec_send_packet 返回 INVALIDDATA 等解码拒绝错误时累加,
     * 收到正常包/缓冲满时清零)。部分 CDN(mirrorcos 等)偶发损坏/截断包:
     * 跳过单包而不是中止整首播放;连续超限才报错。
     */
    private var consecutiveBadPackets = 0

    // ---- AVIO HTTP 状态(仅 HTTP 打开路径使用)----
    private val httpLock = Object()

    /**
     * 当前 HTTP 流。@Volatile:interruptRead 无锁直接读取并 close,打断阻塞中的
     * avioRead(见 interruptRead 的锁说明)。锁内(avioRead/avioSeekTo/openHttpStream/
     * close)赋值。
     */
    @Volatile
    private var httpStream: InputStream? = null
    private var httpPos = 0L
    private var httpTotal = -1L
    private var candidates: List<String> = emptyList()
    private var referer: String? = null

    /**
     * 幂等关闭保护(close 可被 stop 打断与解码线程 finally 双路径调用;
     * AtomicBoolean CAS 保证并发重入时仅首个线程执行释放)。
     */
    private val closedFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    private val closed: Boolean
        get() = closedFlag.get()

    /** 当前 URL(open 成功后设置;重开/丢弃回退用) */
    @Volatile
    private var activeUrl: String? = null

    /** seek 回退全量流是否已告警(@Volatile:每解码器会话只记一次,防刷屏) */
    @Volatile
    private var seekFallbackWarned = false

    /** 全量流回退也失败时仅 warn 一次(防刷屏) */
    @Volatile
    private var seekTotalFailWarned = false

    /** read 回调首次进入仅记一次信息日志(高频路径不打) */
    @Volatile
    private var readEnteredLogged = false

    /** read 回调 EOF 仅记一次(打断后 FFmpeg 可能反复读 EOF,防刷屏) */
    @Volatile
    private var readEofLogged = false

    // ---------------- 打开 ----------------

    /**
     * URL 打开(HTTP 走自定义 AVIO,带 referer + backupUrls 逐个重试)。
     * 非 http(s) 开头的参数视为本地文件路径(缓存命中 / 离线冒烟),直接走
     * FFmpeg file 协议([openViaPath]),不建 AVIO、不写缓存。
     * @param cacheSink 边播边写缓存回调(仅 HTTP 播放路径注入;本地路径为 null)
     * @throws IOException 全部候选打开失败 / 无音频流 / 解码器缺失
     */
    @Synchronized
    fun open(url: String, referer: String?, backupUrls: List<String>, cacheSink: ((Long, ByteArray, Int) -> Unit)? = null) {
        if (!nativeAvailable()) throw FfmpegUnavailableException("FFmpeg native libs unavailable")
        synchronized(this) {
            if (closed) throw IOException("decoder closed")
        }
        this.cacheSink = cacheSink
        // 本地文件路径(完整缓存 / 冒烟):直接 file 协议打开,无需 referer/备用/缓存
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            io.github.cyf112233.musicmc.NetMusic.logger.info("[Decoder] open 本地路径 path=${url.take(120)}")
            openViaPath(url, null)
            activeUrl = url
            io.github.cyf112233.musicmc.NetMusic.logger.info("[Decoder] open 完成 path=${url.take(120)}")
            return
        }
        io.github.cyf112233.musicmc.NetMusic.logger.info("[Decoder] open 入口 url=${url.take(80)} 备用数=${backupUrls.size}")
        this.referer = referer
        candidates = listOf(url) + backupUrls
        var last: Exception? = null
        for (u in listOf(url) + backupUrls) {
            try {
                tryOpenHttp(u)
                activeUrl = u
                io.github.cyf112233.musicmc.NetMusic.logger.info("[Decoder] open 完成 url=${u.take(80)}")
                return
            } catch (e: Exception) {
                last = e
                io.github.cyf112233.musicmc.NetMusic.logger.warn("FFmpeg open failed, trying backup URL: ${u.take(80)} (${e.javaClass.simpleName}: ${e.message})")
                closeNative()
            }
        }
        throw IOException("Failed to open FFmpeg audio stream (${last?.message ?: "no available URL"})")
    }

    /**
     * 本地文件打开(离线冒烟 / 无网络场景;FFmpeg file 协议原生 seek)。
     * 本地文件不经过自定义 AVIO(无 referer 需求)。
     */
    @Synchronized
    fun openLocal(path: String) {
        if (!nativeAvailable()) throw FfmpegUnavailableException("FFmpeg native libs unavailable")
        io.github.cyf112233.musicmc.NetMusic.logger.info("[Decoder] openLocal 入口 path=$path")
        openViaPath(path, null)
        activeUrl = path
        io.github.cyf112233.musicmc.NetMusic.logger.info("[Decoder] openLocal 完成 path=$path")
    }

    private fun openViaPath(path: String?, avioToUse: AVIOContext?) {
        val fmt = avformat.avformat_alloc_context() ?: throw IOException("avformat_alloc_context failed")
        fmtCtx = fmt
        if (avioToUse != null) fmt.pb(avioToUse)
        var ret = avformat.avformat_open_input(fmt, path, null, null)
        if (ret < 0) throw IOException("avformat_open_input failed ($ret, AVERROR_${-ret})")
        ret = avformat.avformat_find_stream_info(fmt, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)
        if (ret < 0) {
            avformat.avformat_close_input(fmt)
            fmtCtx = null
            throw IOException("avformat_find_stream_info failed ($ret)")
        }
        finishOpen(fmt)
    }

    /** HTTP 打开:建 AVIO(自定义读/seek 回调)→ openViaPath(null 路径 + 预设 pb) */
    private fun tryOpenHttp(url: String) {
        synchronized(httpLock) {
            runCatching { httpStream?.close() }
            httpStream = null
            httpStream = openHttpStream(url, 0)
        }
        val bufRaw = avutil.av_malloc(AVIO_BUFFER_SIZE.toLong())
            ?: throw IOException("av_malloc failed")
        val avioBuffer = BytePointer(bufRaw)
        val reader = FfmpegAvioRead(this)
        val seeker = FfmpegAvioSeek(this)
        val ctx = avformat.avio_alloc_context(avioBuffer, AVIO_BUFFER_SIZE, 0, null, reader, null, seeker)
        if (ctx == null || ctx.isNull) {
            runCatching { avioBuffer.deallocate(false) } // 断 GC 队列防二次 free
            avutil.av_free(avioBuffer)
            throw IOException("avio_alloc_context failed")
        }
        avio = ctx
        avioReadCb = reader
        avioSeekCb = seeker
        openViaPath(null, ctx)
    }

    /** 打开成功共用收尾:找音频流 + 启动解码器 + 格式信息 */
    private fun finishOpen(fmt: AVFormatContext) {
        val idx = avformat.av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, null as AVCodec?, 0)
        if (idx < 0) throw IOException("No audio stream found ($idx)")
        audioStreamIndex = idx
        val st: AVStream = fmt.streams(idx) ?: throw IOException("Audio stream does not exist")
        val par = st.codecpar() ?: throw IOException("Missing codec parameters")
        val tb = st.time_base()
        if (tb != null && tb.num() > 0 && tb.den() > 0) {
            tbNum = tb.num()
            tbDen = tb.den()
        }
        val rate = par.sample_rate()
        val ch = par.ch_layout()?.nb_channels() ?: 0
        if (rate <= 0 || ch <= 0) throw IOException("Invalid audio parameters (rate=$rate ch=$ch)")
        sampleRate = rate
        channels = ch
        durationMs = (fmt.duration() / 1000).toInt().coerceAtLeast(0) // AV_TIME_BASE 微秒→毫秒

        val codec: AVCodec = avcodec.avcodec_find_decoder(par.codec_id())
            ?: throw IOException("No decoder available (codec_id=${par.codec_id()})")
        val cc = avcodec.avcodec_alloc_context3(codec) ?: throw IOException("avcodec_alloc_context3 failed")
        codecCtx = cc
        var ret = avcodec.avcodec_parameters_to_context(cc, par)
        if (ret < 0) throw IOException("avcodec_parameters_to_context failed ($ret)")
        ret = avcodec.avcodec_open2(cc, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)
        if (ret < 0) throw IOException("avcodec_open2 failed ($ret, AVERROR_${-ret})")

        packet = avcodec.av_packet_alloc() ?: throw IOException("av_packet_alloc failed")
        frame = avutil.av_frame_alloc() ?: throw IOException("av_frame_alloc failed")
    }

    // ---------------- 解码 ----------------

    /**
     * 解码一帧 → s16 交错 PCM(采样率/声道原样,不重采样)。
     * 内部循环:av_read_frame → 丢弃非音频流 packet → avcodec_send_packet/
     * avcodec_receive_frame 直到拿到一帧;首帧惰性建 swr。
     * @return null 流结束(EOF)
     * @throws IOException 解码/网络错误
     */
    @Synchronized
    fun decodeFrame(): DecodedAudio? {
        val fmt = fmtCtx ?: throw IOException("decoder not open")
        val cc = codecCtx ?: throw IOException("decoder not open")
        val pkt = packet ?: throw IOException("decoder not open")
        val frm = frame ?: throw IOException("decoder not open")
        while (!closed) {
            val r = avformat.av_read_frame(fmt, pkt)
            if (r == AVERROR_EOF) {
                // 流结束:flush 解码器残留帧(部分流最后一帧在末包后)
                avcodec.avcodec_send_packet(cc, null)
                val fr = avcodec.avcodec_receive_frame(cc, frm)
                return if (fr >= 0) convertFrame(frm) else null
            }
            if (r < 0) throw IOException("av_read_frame failed ($r, AVERROR_${-r})")
            if (pkt.stream_index() != audioStreamIndex) {
                avcodec.av_packet_unref(pkt)
                continue
            }
            val sr = avcodec.avcodec_send_packet(cc, pkt)
            avcodec.av_packet_unref(pkt)
            if (sr < 0 && sr != AVERROR_EAGAIN) {
                // 部分 CDN(mirrorcos 等)流偶发损坏/截断包:跳过单包继续,而非中止整首播放;
                // 连续坏包达阈值先 flush 解码器(丢弃半解码状态),超限才判定流彻底损坏报错
                consecutiveBadPackets++
                if (consecutiveBadPackets == BAD_PACKET_FLUSH_THRESHOLD) {
                    avcodec.avcodec_flush_buffers(cc)
                }
                if (consecutiveBadPackets > MAX_CONSECUTIVE_BAD_PACKETS) {
                    throw IOException("avcodec_send_packet failed repeatedly ($sr, skipped $MAX_CONSECUTIVE_BAD_PACKETS bad packets)")
                }
                continue
            }
            consecutiveBadPackets = 0
            while (true) {
                val rr = avcodec.avcodec_receive_frame(cc, frm)
                if (rr == AVERROR_EAGAIN) break // 需要更多包
                if (rr == AVERROR_EOF) return null
                if (rr < 0) throw IOException("avcodec_receive_frame failed ($rr)")
                if (closed) return null
                return convertFrame(frm)
            }
        }
        return null
    }

    /** 帧 → s16 交错字节(swr 惰性初始化;帧参数变化时重建) */
    private fun convertFrame(frm: AVFrame): DecodedAudio {
        val nb = frm.nb_samples()
        if (nb <= 0) throw IOException("Frame has no samples")
        val rate = frm.sample_rate()
        val ch = frm.ch_layout()?.nb_channels() ?: 0
        val fmt = frm.format()
        if (rate <= 0 || ch <= 0) throw IOException("Invalid frame audio parameters (rate=$rate ch=$ch)")
        val swr = ensureSwr(frm, ch, rate, fmt)
        samplesDecoded += nb.toLong()

        val isPlanar = avutil.av_sample_fmt_is_planar(fmt) != 0
        val planes = if (isPlanar) ch else 1
        val inPtrs = PointerPointer<Pointer>(planes.toLong())
        try {
            for (i in 0 until planes) inPtrs.put(i.toLong(), frm.data(i))
            val outBytes = nb * 2L * ch
            val outBuf = BytePointer(outBytes)
            val outPtrs = PointerPointer<Pointer>(1)
            try {
                outPtrs.put(0L, outBuf)
                val conv = swresample.swr_convert(swr, outPtrs, nb, inPtrs, nb)
                if (conv < 0) throw IOException("swr_convert failed ($conv)")
                if (conv == 0) {
                    // 理论不会发生(输入==输出样本数);空帧防御
                    return DecodedAudio(ByteArray(0), rate, ch, calcPtsMs(frm, rate))
                }
                val bytes = ByteArray(conv * 2 * ch)
                outBuf.get(bytes)
                return DecodedAudio(bytes, rate, ch, calcPtsMs(frm, rate))
            } finally {
                outPtrs.deallocate()
                outBuf.deallocate()
            }
        } finally {
            inPtrs.deallocate()
        }
    }

    /** 帧时间戳(流 time_base 单位)→ 毫秒;无效则按已解码样本数估算 */
    private fun calcPtsMs(frm: AVFrame, rate: Int): Long {
        val pts = frm.pts()
        // AV_NOPTS_VALUE == Long.MIN_VALUE(FFmpeg 惯例)
        val ms = if (pts != Long.MIN_VALUE) {
            pts * 1000L * tbNum / tbDen
        } else {
            samplesDecoded * 1000L / rate
        }
        lastPtsMs = ms
        return ms
    }

    /** swr 上下文惰性创建;帧参数(率/声道/采样格式)变化时重新创建 */
    private fun ensureSwr(frm: AVFrame, ch: Int, rate: Int, fmt: Int): SwrContext {
        val cur = swrCtx
        if (cur != null && !cur.isNull) return cur
        // 输出:原采样率/原声道,s16 交错(AV_CHANNEL_ORDER_NATIVE 布局)
        val outLay = AVChannelLayout()
        outLay.order(AV_CHANNEL_ORDER_NATIVE)
        outLay.nb_channels(ch)
        val swr = SwrContext()
        val ret = swresample.swr_alloc_set_opts2(
            swr, outLay, AV_SAMPLE_FMT_S16, rate,
            frm.ch_layout(), fmt, rate, 0, null,
        )
        if (ret < 0) throw IOException("swr_alloc_set_opts2 failed ($ret)")
        val init = swresample.swr_init(swr)
        if (init < 0) throw IOException("swr_init failed ($init)")
        swrCtx = swr
        return swr
    }

    // ---------------- seek ----------------

    /**
     * 跳到 [positionMs] 毫秒。优先 av_seek_frame(按音频流 time_base 换算,
     * AVSEEK_FLAG_BACKWARD),失败回退"重开流 + 丢弃到目标"。
     * seek 后 samplesDecoded 预置为目标样本数,positionMs() 立即返回目标附近。
     */
    @Synchronized
    fun seekTo(positionMs: Int) {
        if (closed) return // close 已释放原生指针:直接忽略(防止触碰已 free 的上下文)
        val fmt = fmtCtx ?: return
        val idx = audioStreamIndex
        if (idx < 0) return
        val target = positionMs.toLong() * tbDen / (1000L * tbNum) // 流 time_base 单位
        val ret = avformat.av_seek_frame(fmt, idx, target, AVSEEK_FLAG_BACKWARD)
        if (ret < 0) {
            io.github.cyf112233.musicmc.NetMusic.logger.warn("av_seek_frame failed ($ret), falling back to reopen+skip: ${activeUrl?.take(80)}")
            reopenAndSkip(positionMs)
            return
        }
        avcodec.avcodec_flush_buffers(codecCtx ?: return)
        samplesDecoded = positionMs.toLong() * sampleRate / 1000
        lastPtsMs = -1
    }

    /** av_seek_frame 失败回退:完整重开同一 URL + 丢弃解码直到目标位置 */
    private fun reopenAndSkip(positionMs: Int) {
        val url = activeUrl ?: return
        val ref = referer
        val cands = candidates
        closeNative()
        try {
            open(url, ref, cands)
        } catch (e: Exception) {
            io.github.cyf112233.musicmc.NetMusic.logger.warn("seek fallback reopen failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        val target = positionMs.toLong() * sampleRate / 1000
        var guard = 0
        while (!closed && samplesDecoded < target) {
            if (decodeFrame() == null) break
            if (++guard > 10_000_000) break // 防御:异常循环
        }
    }

    // ---------------- 状态 ----------------

    /**
     * 当前播放位置(毫秒):最近一帧 pts;无 pts 时按已解码样本数估算。
     * **不取 this 锁**:decodeFrame 在网络挂起(切换代理)时可能长时间阻塞持 this 锁,
     * UI 线程的进度轮询/拖条若在此等锁会卡死界面。本方法只读 volatile 字段,无锁安全。
     */
    fun positionMs(): Int {
        val ms = lastPtsMs
        if (ms >= 0) return ms.toInt()
        return if (sampleRate > 0) (samplesDecoded * 1000 / sampleRate).toInt() else 0
    }

    @Synchronized
    fun formatInfo(): FormatInfo = FormatInfo(sampleRate, channels, durationMs)

    // ---------------- AVIO HTTP 流 ---------------

    /**
     * 按 [offset] 打开 HTTP 流:206(支持 Range)→ 直接定位;200(忽略 Range)→ 丢弃 offset 字节。
     * 成功更新 [httpPos](逻辑位置)与 [httpTotal]。
     * 200 全量流丢弃时若提前 EOF(offset 超出流实际长度,如 seek 到越界偏移)→ 判失败并抛出,
     * 防止调用方把 httpPos 记成"假装丢弃成功"的越界值(那会污染后续 Range 偏移,详见 avioSeekTo)。
     */
    private fun openHttpStream(url: String, offset: Long): InputStream {
        var last: Exception? = null
        for (u in candidates) {
            if (closed) throw IOException("decoder closed")
            try {
                val info = Http.openStreamInfo(u, offset, referer)
                val body = info.body
                if (info.partial) {
                    httpPos = offset
                } else {
                    val skipped = discard(body, offset)
                    if (skipped < offset) {
                        runCatching { body.close() }
                        throw IOException("Offset beyond stream length (expected to discard $offset, actually discarded $skipped)")
                    }
                    httpPos = offset
                }
                if (info.totalSize > 0) httpTotal = info.totalSize
                return body
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("HTTP stream open failed (${last?.message ?: "no available URL"})")
    }

    /** 丢弃 [n] 字节到 EOF,返回实际丢弃数(提前 EOF 时 < n) */
    private fun discard(body: InputStream, n: Long): Long {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) break
            left -= r
        }
        return n - left
    }

    /**
     * seek 回调实现:关闭旧流并重开定位到 [offset];失败返回负值。
     *
     * 分级策略(修复 av_seek_frame 返回 -5 的 CDN Range 拒绝问题):
     * - Range 重开成功(206 定位 / 200 全量+丢弃)→ 返回 httpPos(=offset),与现行一致;
     * - Range 重开失败(异常 / HTTP 403 等)→ 回退**全量流**:
     *   Http.openStream(url, referer) 重开 + 丢弃 offset 字节(读跳),seek 回调返回 0
     *   (FFmpeg 成功语义,read 回调后续照常供数;httpPos 同步为 offset);
     *   每次回退仅在首个失败时 logger.warn 一次(防刷屏);
     * - 全量流也失败 → 返回 -1(av_seek_frame 报错 → 上层走现有"重开+丢弃"兜底路径)。
     */
    internal fun avioSeekTo(offset: Long): Long {
        if (offset < 0) return -22L // EINVAL
        io.github.cyf112233.musicmc.NetMusic.logger.info("[Decoder] seek 回调调用 offset=$offset")
        return synchronized(httpLock) {
            if (closed) return -1L
            val url = activeUrl ?: return -1L
            // 已知总长且偏移越界:快速失败(避免 416 → 全量流+丢弃的无效往返),
            // 返回 -1 让 av_seek_frame 报错 → 上层走"重开+丢弃"兜底
            val total = httpTotal
            if (total > 0 && offset > total) {
                if (!seekTotalFailWarned) {
                    seekTotalFailWarned = true
                    io.github.cyf112233.musicmc.NetMusic.logger.warn("[Decoder] seek 回调 偏移($offset)>流长($total) 快速失败:${url.take(80)}")
                }
                return -1L
            }
            httpStream?.let { runCatching { it.close() } }
            try {
                // 206 / 200 两态已由 openHttpStream 处理(看 body 丢弃逻辑)
                httpStream = openHttpStream(url, offset)
                io.github.cyf112233.musicmc.NetMusic.logger.info("[Decoder] seek 回调 Range成功 offset=$offset url=${url.take(80)}")
                httpPos
            } catch (e: Exception) {
                // Range 重开失败(异常/403/越界)→ 回退全量流 + 丢弃 offset 字节
                if (!seekFallbackWarned) {
                    seekFallbackWarned = true
                    io.github.cyf112233.musicmc.NetMusic.logger.warn(
                        "[Decoder] seek 回调 403/异常回退全量流(丢弃 $offset 字节):${url.take(80)} (${e.javaClass.simpleName}: ${e.message})",
                    )
                }
                try {
                    val full = Http.openStream(url, referer)
                    val skipped = discard(full, offset)
                    if (skipped < offset) {
                        // 丢弃提前 EOF(offset 超流长):无法定位 → 判失败;绝不把 httpPos 写成越界值
                        runCatching { full.close() }
                        if (httpStream != null) {
                            runCatching { httpStream?.close() }
                            httpStream = null
                        }
                        if (!seekTotalFailWarned) {
                            seekTotalFailWarned = true
                            io.github.cyf112233.musicmc.NetMusic.logger.warn("[Decoder] seek 回调 全量流丢弃不完整($skipped/$offset) 判失败:${url.take(80)}")
                        }
                        -1L
                    } else {
                        httpStream = full
                        httpPos = offset
                        0L // 成功语义:seek 回调返回 0,read 回调继续从该位置供数
                    }
                } catch (_: Exception) {
                    runCatching { httpStream?.close() }
                    httpStream = null
                    if (!seekTotalFailWarned) {
                        seekTotalFailWarned = true
                        io.github.cyf112233.musicmc.NetMusic.logger.warn("[Decoder] seek 回调全量流也失败(返回 -1):${url.take(80)}")
                    }
                    -1L // 全量流也失败 → av_seek_frame 报错 → 上层"重开+丢弃"兜底
                }
            }
        }
    }

    internal fun avioRead(buf: BytePointer, bufSize: Int): Int {
        return synchronized(httpLock) {
            if (!readEnteredLogged) {
                readEnteredLogged = true
                io.github.cyf112233.musicmc.NetMusic.logger.info("[Decoder] read 回调首次进入 url=${activeUrl?.take(80)}")
            }
            if (closed) return AVERROR_EOF
            val s = httpStream ?: return AVERROR_EOF
            try {
                val tmp = ByteArray(bufSize)
                val n = s.read(tmp)
                if (n < 0) {
                    if (!readEofLogged) {
                        readEofLogged = true
                        io.github.cyf112233.musicmc.NetMusic.logger.info("[Decoder] read 回调 EOF url=${activeUrl?.take(80)}")
                    }
                    AVERROR_EOF
                } else if (n == 0) {
                    0
                } else {
                    buf.put(tmp, 0, n)
                    val offset = httpPos
                    httpPos += n
                    cacheSink?.invoke(offset, tmp, n)
                    n
                }
            } catch (e: Exception) {
                -5L.toInt() // AVERROR(EIO)
            }
        }
    }

    /** 当前已知流总字节(未知 -1;SEEK_END / AVSEEK_SIZE 用) */
    internal fun avioSize(): Long = httpTotal

    internal fun avioPos(): Long = httpPos

    /**
     * 缓存写回调(AVIO read 回调读到 [n] 字节后,以逻辑流偏移 [offset] 调用;
     * 由 FfmpegAudioEngine 注入 AudioCache.CacheWriter,边播边落盘)。
     * 只从解码线程(avioRead 内)调用;offset 为流内逻辑位置(seek 后乱序写,writer 内部加锁)。
     */
    @Volatile
    internal var cacheSink: ((offset: Long, bytes: ByteArray, len: Int) -> Unit)? = null

    // ---------------- 关闭 ----------------

    /**
     * 打断阻塞读(仅由 [io.github.cyf112233.musicmc.player.ffmpeg.FfmpegAudioEngine] 在
     * stop/seek 时从任意线程调用):只关 Java 侧 HTTP 流,使 AVIO read 回调尽快返回
     * EOF/EIO,阻塞中的 av_read_frame 立即退出并让解码线程走 [FfmpegDecoder.close] 自清。
     * **不触碰任何原生资源**(fmtCtx/codecCtx/swr/avio 等只能由解码线程自己的 close 释放,
     * 跨线程 close 别人的指针是本引擎原生崩溃的头号来源)。
     * 本地文件(无 httpStream)为空操作;对已关闭/未打开实例也安全。
     *
     * **不取 httpLock**:httpLock 可能被阻塞中的 avioRead(网络挂起,如切换代理节点)
     * 长时间占用;若在此取锁,seek/stop 的调用线程(UI 线程)会无限等待锁 → 界面卡死。
     * 改为 volatile 读当前流引用直接 close:Java HttpClient 响应体流的 close() 立即
     * 返回并中断阻塞中的 read(抛异常/EOF),avioRead 随后拿到锁 catch 返回 EIO,
     * 解码线程退出。不置 httpStream=null(置空属于锁内状态变更,避免与换流竞争;
     * 已关闭的流在 avioRead 中 read 直接抛异常,语义等价)。
     */
    fun interruptRead() {
        val s = httpStream ?: return
        io.github.cyf112233.musicmc.NetMusic.logger.info("[Decoder] interruptRead 调用 url=${activeUrl?.take(80)}")
        runCatching { s.close() }
    }

    /**
     * 释放全部原生资源(幂等,可重入)。顺序(经 FFmpeg 7.1 源码 + 探针验证):
     * 关 HTTP 流 → avcodec_free_context → swr_free → avformat_close_input
     * (CUSTOM_IO 下不负责任 pb)→ avio_context_free → av_free(当前 buffer)
     * → av_packet_free / av_frame_free → 回调 deallocate(释放 C 函数对象)。
     * 必须由解码线程本身调用(参照 [interruptRead] 的线程约定)。
     */
    @Synchronized
    fun close() {
        if (!closedFlag.compareAndSet(false, true)) {
            io.github.cyf112233.musicmc.NetMusic.logger.info("[Decoder] close 重入(幂等跳过)")
            return
        }
        io.github.cyf112233.musicmc.NetMusic.logger.info("[Decoder] close 入口 url=${activeUrl?.take(80)}")
        val t0 = System.currentTimeMillis()
        synchronized(httpLock) {
            runCatching { httpStream?.close() }
            httpStream = null
        }
        closeNative()
        io.github.cyf112233.musicmc.NetMusic.logger.info("[Decoder] close 完成 elapsed=${System.currentTimeMillis() - t0}ms url=${activeUrl?.take(80)}")
    }

    private fun closeNative() {
        // 关键:每次手动 C 层释放(avcodec_free_context 等)后,立即对同一 Java 对象调
        // deallocate(false) —— 从 javacpp GC 队列 DeallocatorReference 摘除并停止 Ghost 回放,
        // 防止会话结束后对象被 GC 回收时 DeallocatorThread 对已释放地址二次 free
        // (实测:解码线程 finally close 中 avcodec_free_context 与 GC deallocator 并发 → SIGSEGV)。
        // deallocate(false) 不执行 deallocator(不二次 free),仅断开队列引用,幂等且安全。
        val cc = codecCtx
        if (cc != null && !cc.isNull) {
            runCatching { avcodec.avcodec_free_context(cc) }
            runCatching { cc.deallocate(false) }
        }
        val swr = swrCtx
        if (swr != null && !swr.isNull) {
            runCatching { swresample.swr_free(swr) }
            runCatching { swr.deallocate(false) }
        }
        val fmt = fmtCtx
        if (fmt != null && !fmt.isNull) {
            runCatching { avformat.avformat_close_input(fmt) }
            runCatching { fmt.deallocate(false) }
        }
        val av = avio
        if (av != null && !av.isNull) {
            val cur = av.buffer()
            runCatching { avformat.avio_context_free(av) }
            runCatching { av.deallocate(false) }
            if (cur != null && !cur.isNull) {
                runCatching { avutil.av_free(cur) }
                runCatching { cur.deallocate(false) }
            }
        }
        val pkt = packet
        if (pkt != null && !pkt.isNull) {
            runCatching { avcodec.av_packet_free(pkt) }
            runCatching { pkt.deallocate(false) }
        }
        val frm = frame
        if (frm != null && !frm.isNull) {
            runCatching { avutil.av_frame_free(frm) }
            runCatching { frm.deallocate(false) }
        }
        // 回调对象:生成类自带 deallocator(其所归属的 native function),deallocate() 即
        // 执行释放并断 GC,无需 deallocate(false)(与任务"释放后置空引用"要求一致)
        runCatching { avioReadCb?.deallocate() }
        runCatching { avioSeekCb?.deallocate() }
        fmtCtx = null
        codecCtx = null
        swrCtx = null
        avio = null
        packet = null
        frame = null
        avioReadCb = null
        avioSeekCb = null
        audioStreamIndex = -1
    }
}

/**
 * FFmpeg 原生库不可用(未打包平台 jar / 加载失败)。FfmpegAudioEngine.load 同步抛出
 * (MusicPlayer.loadUrl 已前置 [nativeAvailable] 检查拦截,此处为兜底防御)。
 */
class FfmpegUnavailableException(message: String) : RuntimeException(message)

// ---------------- 自定义 AVIO 回调(javacpp 运行时 Java 回调) ----------------
// 机制:回调类的 protected 无参构造器调用 private native allocate(),该原生函数对象
// 由 libjniavformat.so 内置 JNI 蹦床,被 C 调用时虚分发回本子类的 call() 覆写 ——
// 无需 javacpp Builder/编译器参与(已用 javacpp 1.5.12 + 7.1.1 绑定实测验证)。

/** read_packet 回调:读入 FFmpeg 请求的 buf_size 字节;EOF 返回 AVERROR_EOF。
 * 加固:整个 call() 包 try/catch(Throwable) —— **绝不让异常穿越 JNI**(万一异常
 * 越过 native 蹦床会直接打崩进程/悬垂;catch 后仅首次 warn 一次,返回 AVERROR_EOF)。 */
private class FfmpegAvioRead(private val decoder: FfmpegDecoder) : Read_packet_Pointer_BytePointer_int() {
    @Volatile
    private var exceptionWarned = false

    override fun call(opaque: Pointer?, buf: BytePointer?, bufSize: Int): Int {
        try {
            if (buf == null) return FfmpegDecoder.AVERROR_EOF
            return decoder.avioRead(buf, bufSize)
        } catch (t: Throwable) {
            if (!exceptionWarned) {
                exceptionWarned = true
                io.github.cyf112233.musicmc.NetMusic.logger.warn("[Decoder] read 回调异常(已拦截,返回 AVERROR_EOF): ${t.javaClass.name}: ${t.message}")
            }
            return FfmpegDecoder.AVERROR_EOF
        }
    }
}

/** seek 回调:定位并按 whence 返回绝对位置(负值=错误)。
 * 加固同 read 回调:catch(Throwable) 防异常穿越 JNI,仅首次 warn,失败返回 -1。 */
private class FfmpegAvioSeek(private val decoder: FfmpegDecoder) : Seek_Pointer_long_int() {
    @Volatile
    private var exceptionWarned = false

    override fun call(opaque: Pointer?, offset: Long, whence: Int): Long {
        try {
            return when (whence) {
                FfmpegDecoder.SEEK_SET -> decoder.avioSeekTo(offset)
                FfmpegDecoder.SEEK_CUR -> decoder.avioSeekTo(decoder.avioPos() + offset)
                FfmpegDecoder.SEEK_END -> {
                    val size = decoder.avioSize()
                    if (size >= 0) decoder.avioSeekTo(size + offset) else -38L // ENOSYS
                }
                FfmpegDecoder.AVSEEK_SIZE -> decoder.avioSize().let { if (it >= 0) it else -38L }
                else -> -22L // EINVAL
            }
        } catch (t: Throwable) {
            if (!exceptionWarned) {
                exceptionWarned = true
                io.github.cyf112233.musicmc.NetMusic.logger.warn("[Decoder] seek 回调异常(已拦截,返回 -1): ${t.javaClass.name}: ${t.message}")
            }
            return -1L
        }
    }
}