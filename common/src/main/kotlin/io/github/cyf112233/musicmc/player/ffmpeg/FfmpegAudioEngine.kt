package io.github.cyf112233.musicmc.player.ffmpeg

import io.github.cyf112233.musicmc.net.Http
import io.github.cyf112233.musicmc.player.AudioCache
import io.github.cyf112233.musicmc.player.AudioEngine
import io.github.cyf112233.musicmc.player.PcmBatcher
import io.github.cyf112233.musicmc.player.StreamOptions
import io.github.cyf112233.musicmc.player.audio.AudioOutput
import io.github.cyf112233.musicmc.player.audio.AAudioOutput
import io.github.cyf112233.musicmc.player.audio.SourceDataLineOutput
import io.github.cyf112233.musicmc.client.UiText
import java.io.IOException

/**
 * FFmpeg 播放引擎(唯一播放引擎):[FfmpegDecoder](avformat 解封装 + avcodec 解码 +
 * swr 转 s16)→ 输出管线(桌面 javax.sound / Android OpenAL,见 AudioOutput)。
 *
 * 线程/暂停锁/音量/会话守卫/攒批写模式与既有引擎约定一致:
 * - 每次会话一条播放线程(NetMusic-Ffmpeg),sessionId 递增丢弃旧线程过期回调;
 * - paused + lock.wait 阻塞解码循环(resume notifyAll);暂停前 flush 攒批缓冲;
 * - 音量走 MASTER_GAIN → VOLUME,不支持忽略;
 * - PCM 输出经 [PcmBatcher] 攒满 64KB 才写 line 一次;
 * - line 在首帧解码后按实际采样率/声道创建(open(..., 131072));
 * - seekTo:停旧会话 → 新会话 open → av_seek_frame 定位(FFmpeg 侧按流 time_base 精确锚定,
 *   positionMs 立即报解码器报告位置);
 * - load 时若 FFmpeg 原生库不可用([FfmpegDecoder.nativeAvailable]==false)则**同步抛出**
 *   [FfmpegUnavailableException](不触碰回调;MusicPlayer.loadUrl 已前置该检查直接报错,
 *   此处抛出为兜底防御);其余错误(网络/格式/解码)均照常经 onError 回调。
 */
class FfmpegAudioEngine : AudioEngine {

    private val lock = Object()

    /** 停止标志(由 stop/load/seekTo 设置) */
    @Volatile
    private var stopped = true

    /** 暂停标志(pause/resume) */
    @Volatile
    private var paused = false

    /** 目标音量 0..1 */
    @Volatile
    private var volume = 1f

    /** 会话号:每次 start 递增,用于丢弃旧线程的过期回调(同 M4a 引擎语义) */
    @Volatile
    private var sessionId = 0

    private var playbackThread: Thread? = null
    private var line: AudioOutput? = null

    /** 当前会话的解码器(playLoop 内创建并独占;仅解码线程 finally close,引擎绝不跨线程 close) */
    @Volatile
    private var decoder: FfmpegDecoder? = null

    @Volatile
    private var activeUrl: String? = null

    private var onStarted: () -> Unit = {}
    private var onFinished: () -> Unit = {}
    private var onError: (String) -> Unit = {}

    /** CDN Referer(B 站要求 https://www.bilibili.com/) */
    private var referer: String? = null

    /** 备用直链(B 站 playurl 的 backupUrl,主链打开失败时逐个重试) */
    private var backupUrls: List<String> = emptyList()

    /** 音频缓存 key(非空时边播边落盘,整首读完标记完整供下次本地播放) */
    private var cacheKey: String? = null

    /** 预加载线程当前打开的独立下载流(stop/seek 时关闭打断阻塞读) */
    @Volatile
    private var prefetchStream: java.io.InputStream? = null

    /** 预加载线程是否存活(播放线程 EOF 判定完整时,若预加载还在补空洞则保留 partial) */
    @Volatile
    private var prefetchAlive = false

    override val isPlaying: Boolean
        get() = !stopped && !paused && line != null && line!!.isActive

    override fun load(url: String, options: StreamOptions, onStarted: () -> Unit, onFinished: () -> Unit, onError: (String) -> Unit) {
        if (!FfmpegDecoder.nativeAvailable()) {
            // 同步抛出不触碰回调(MusicPlayer 已前置检查拦截,此处为兜底防御)
            throw FfmpegUnavailableException(UiText.t("FFmpeg 原生库不可用", "FFmpeg native libs unavailable"))
        }
        this.onStarted = onStarted
        this.onFinished = onFinished
        this.onError = onError
        referer = options.referer
        backupUrls = options.backupUrls
        cacheKey = options.cacheKey
        start(url, 0, fireStarted = true)
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        synchronized(lock) {
            paused = false
            lock.notifyAll()
        }
    }

    override fun stop() {
        stopInternal(join = true)
    }

    override fun seekTo(positionMs: Int, totalMs: Int) {
        io.github.cyf112233.musicmc.NetMusic.logger.info("[Seek] seekTo(pos=$positionMs, total=$totalMs) thread=${Thread.currentThread().name}")
        val url = activeUrl ?: return
        val wasPaused = paused
        // 非阻塞切会话:start 内部 stopInternal(join=false) 不 join 旧解码线程,
        // 旧会话靠 stopped 标志 + interruptRead 打断阻塞读自行退出,在自己线程 finally 自清;
        // 连续两次快速 seek 时,前一个会话按 sessionId 失效,最后一次立即生效。
        start(url, positionMs.coerceAtLeast(0), fireStarted = false, initialPaused = wasPaused)
    }

    override fun positionMs(): Int = decoder?.positionMs() ?: 0

    override fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        applyVolume(volume)
    }

    override fun release() {
        stopInternal(join = true)
        onStarted = {}
        onFinished = {}
        onError = {}
    }

    /**
     * @param fireStarted 是否触发 onStarted(load true / seek false,语义同现有引擎)。
     * @param initialPaused 新会话的初始暂停态(seek 时保持)。
     */
    private fun start(
        url: String,
        seekMs: Int,
        fireStarted: Boolean = true,
        initialPaused: Boolean = false,
    ) {
        // 停旧会话走非阻塞路径(不 join):新会话立即开始;旧线程自清理
        stopInternal(join = false)
        activeUrl = url
        stopped = false
        paused = initialPaused
        val mySession = ++sessionId
        io.github.cyf112233.musicmc.NetMusic.logger.info("[Seek] 新会话启动 session=$mySession")
        playbackThread = Thread(
            { playLoop(url, seekMs, mySession, fireStarted) },
            "NetMusic-Ffmpeg",
        ).apply {
            isDaemon = true
            // 兜底:playLoop 已用 catch(Throwable) 包住整个循环体,此处仅防循环外代码意外逃逸
            setUncaughtExceptionHandler { _, e ->
                io.github.cyf112233.musicmc.NetMusic.logger.error("[Engine] 线程未捕获异常", e)
            }
            start()
        }
    }

    /**
     * 停止当前会话。
     * @param join true(默认,stop/clearQueue/release):join 旧线程(至多 1.5s)确保立即静音、
     *   资源同步释放;false(seek/切会话):只置标志 + 打断阻塞读 + stop line 立即静音,
     *   不等待旧线程退出 —— 旧线程在自身 finally 关闭自己的 line/stream/decoder。
     * 要点:
     *  - 此处**绝不** close 共享 line / decoder:原生指针(AudioOutput / FfmpegDecoder)
     *    只能由持有它的解码线程在 finally 自清。跨线程 close 与阻塞中的
     *    AAudioStream_write 并发是 "decStrong() too many times" + SIGABRT 的根因
     *    (2026-08 实测),native 层虽已加读写锁兜底(close 等待 write 返回),Java 侧
     *    仍坚持"谁创建谁释放",避免渲染线程阻塞在 close 上;
     *  - line.stop() 是幂等唤醒(AAudio requestStop / SourceDataLine stop),可任意线程调用,
     *    立即静音并让阻塞 write 返回。
     */
    private fun stopInternal(join: Boolean = true) {
        // 分步计时(置标志/打断读/line.stop/join);任一步 >100ms 时整条升为 warn
        val t0 = System.currentTimeMillis()
        stopped = true
        paused = false
        synchronized(lock) { lock.notifyAll() }
        val t1 = System.currentTimeMillis()
        // 打断旧会话阻塞的 AVIO read(仅关 Java HTTP 流,不碰原生资源);
        // 解码线程随后因 EOF/EIO 退出,并在 finally 里 close 自己的解码器
        runCatching { decoder?.interruptRead() }
        // 打断预加载线程的阻塞读(独立下载流)
        runCatching { prefetchStream?.close() }
        val t2 = System.currentTimeMillis()
        // 只 stop 不 close:唤醒阻塞 write + 立即静音;close 由解码线程 finally 执行
        try {
            line?.stop()
        } catch (_: Exception) {
        }
        val t3 = System.currentTimeMillis()
        val t = playbackThread
        if (join && t != null && t !== Thread.currentThread()) {
            try {
                t.join(1500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val t4 = System.currentTimeMillis()
        playbackThread = null
        val steps = "置标志=${t1 - t0}ms 打断读=${t2 - t1}ms line.stop=${t3 - t2}ms join=${t4 - t3}ms total=${t4 - t0}ms"
        val hasSlowStep = (t1 - t0) > 100 || (t2 - t1) > 100 || (t3 - t2) > 100 || (t4 - t3) > 100
        val log = io.github.cyf112233.musicmc.NetMusic.logger
        if (hasSlowStep) log.warn("[Engine] stopInternal 慢步骤($steps)") else log.info("[Engine] stopInternal $steps")
    }

    private fun playLoop(
        url: String,
        seekMs: Int,
        mySession: Int,
        fireStarted: Boolean,
    ) {
        // 本会话打开的 line/解码器(供 finally 清理与引用相等保护;新会话启动后不误清)
        val slot = FfmpegLineSlot()
        var myDecoder: FfmpegDecoder? = null
        io.github.cyf112233.musicmc.NetMusic.logger.info("[Engine] session=$mySession 开始 url=${url.take(80)}")
        // 播放候选:主链 + 备用(去重)。打开失败与播放中断(CDN 文件损坏/网络抖动)
        // 都按此顺序逐个尝试,全部失败才经 onError 报错;换源后从上次位置续播。
        val candidates = (listOf(url) + backupUrls).distinct()
        var resumeMs = seekMs
        // line 跨候选复用(同一首歌):首个候选首帧创建后,后续候选不再重建
        var lineOpened = false
        // 音频缓存:网络播放且带 key 时启用,边播边落盘(候选级复用同一 writer)
        val cacheWriter = if (cacheKey != null && url.startsWith("http")) AudioCache.writer(cacheKey!!, -1) else null
        // EOF 自然结束时置 true:完整缓存已由 finishCache 处理,finally 不再无条件作废
        var eofFinished = false
        try {
            for (attempt in candidates.indices) {
                // 会话已过期(连续 seek/切歌竞态):不 open,直接走 finally 自清自己的解码器
                if (stopped || mySession != sessionId) return
                val u = candidates[attempt]
                val dec = FfmpegDecoder()
                myDecoder = dec
                // 共享 field 仅在会话仍是当前会话时移交(过期会话不得覆盖新会话的 decoder 指针)
                if (mySession == sessionId) decoder = dec
                if (stopped || mySession != sessionId) return
                var eof = false
                try {
                    // 候选由本层管理,open 内部不再轮询 backup(传空列表);
                    // cacheSink:AVIO read 回调把读到的字节写入缓存(线程安全,乱序写)
                    dec.open(u, referer, emptyList()) { off, bytes, len -> cacheWriter?.write(off, bytes, len) }
                    io.github.cyf112233.musicmc.NetMusic.logger.info("[Engine] session=$mySession open 成功 url=${u.take(80)}")
                    val info = dec.formatInfo()
                    if (info.sampleRate <= 0 || info.channels <= 0) throw RuntimeException(UiText.t("FFmpeg 音频参数无效", "Invalid FFmpeg audio parameters"))
                    // 已知流长注入缓存(Content-Length);随后启动预加载线程提前下载剩余部分
                    cacheWriter?.setTotal(dec.avioSize())
                    if (cacheWriter != null) startPrefetch(u, cacheWriter!!, mySession)

                    if (resumeMs > 0) dec.seekTo(resumeMs)

                    while (!stopped) {
                        // 暂停前把已解码剩余 flush 出去(锁外写,不阻塞 resume/stop 的同步块)
                        if (lineOpened && paused) slot.batcher?.flush()
                        synchronized(lock) {
                            while (paused && !stopped) lock.wait()
                        }
                        if (stopped) break

                        val audio = try {
                            dec.decodeFrame()
                        } catch (e: IOException) {
                            // 播放中断(网络抖动 / CDN 文件损坏):有剩余候选则换源续播
                            // (保持进度),否则向上抛(全部候选耗尽 → 报错)。
                            if (attempt + 1 >= candidates.size) throw e
                            io.github.cyf112233.musicmc.NetMusic.logger.warn(
                                "[Engine] session=$mySession 播放中断(${e.message}),切换备用源 ${attempt + 2}/${candidates.size}",
                            )
                            resumeMs = dec.positionMs()
                            break
                        }
                        if (audio == null) {
                            eof = true
                            break // EOF:自然结束
                        }
                        if (audio.samples.isEmpty()) continue

                        if (!lineOpened) {
                            // 首帧定格式后建 line;创建前确认本会话仍有效(与 M4a/Mp3 同一泄漏窗口防御)
                            if (stopped || mySession != sessionId) return
                            val audioLine = createOutput(audio.rate, audio.channels)
                            // 会话守卫:createOutput 耗时窗口(SourceDataLine open 等)内
                            // 新会话可能已把共享 field line 指到自己的 line —— 过期会话
                            // 不得覆盖,否则 stopInternal/applyVolume 会作用到新会话的 line 上
                            if (mySession == sessionId) line = audioLine
                            slot.line = audioLine
                            slot.batcher = PcmBatcher(audioLine)
                            lineOpened = true
                            applyVolume(volume)
                            if (!stopped && mySession == sessionId && fireStarted) onStarted()
                            // 创建与写入之间的缝隙:已被 stop/切歌打断则立即停掉,由 finally 兜底 close
                            if (stopped || mySession != sessionId) {
                                runCatching { audioLine.stop() }
                                runCatching { audioLine.close() }
                                return
                            }
                        }
                        if (lineOpened && !stopped && mySession == sessionId) {
                            slot.batcher?.write(audio.samples, 0, audio.samples.size)
                        }
                    }

                    if (stopped) {
                        io.github.cyf112233.musicmc.NetMusic.logger.info("[Engine] session=$mySession 退出 reason=stopped")
                        return
                    }
                    if (eof) {
                        // 自然结束(EOF):把攒批缓冲剩余写出去;整首读完 → 缓存收尾
                        if (lineOpened) slot.batcher?.flush()
                        eofFinished = true
                        cacheWriter?.let { finishCache(it, mySession) }
                        io.github.cyf112233.musicmc.NetMusic.logger.info("[Engine] session=$mySession 退出 reason=EOF")
                        if (mySession == sessionId) onFinished()
                        return
                    }
                    // 播放中断换源:释放当前解码器与旧预加载流,进入下一候选(decoder 字段下轮覆盖)
                    runCatching { prefetchStream?.close() }
                    runCatching { dec.close() }
                    myDecoder = null
                } catch (e: Throwable) {
                    // 异常全栈留痕,绝不吃掉;ThreadDeath 照常传播(不拦 JVM 线程自杀语义)
                    if (e is ThreadDeath) throw e
                    // 候选打开失败/参数无效:换下一个;全部失败向上抛由外层统一报错
                    if (attempt + 1 < candidates.size) {
                        io.github.cyf112233.musicmc.NetMusic.logger.warn(
                            "[Engine] session=$mySession 候选源 ${attempt + 1}/${candidates.size} 失败(${e.javaClass.simpleName}: ${e.message}),尝试下一个",
                        )
                        runCatching { prefetchStream?.close() }
                        runCatching { dec.close() }
                        myDecoder = null
                        continue
                    }
                    throw e
                }
            }
            // 理论不可达:最后一个候选抛错会走外层 catch
        } catch (e: Throwable) {
            // 异常全栈留痕,绝不吃掉;ThreadDeath 照常传播(不拦 JVM 线程自杀语义)
            if (e is ThreadDeath) throw e
            io.github.cyf112233.musicmc.NetMusic.logger.error("[Engine] session=$mySession 退出 reason=error(${e.javaClass.simpleName}: ${e.message})", e)
            if (!stopped && mySession == sessionId) {
                onError(
                    when (e) {
                        is java.io.IOException -> UiText.t("网络错误或音频流中断(${e.message ?: e.javaClass.simpleName})", "Network error or audio stream interrupted (${e.message ?: e.javaClass.simpleName})")
                        else -> UiText.t("音频格式不支持或解码失败(${e.javaClass.simpleName})", "Unsupported audio format or decode failure (${e.javaClass.simpleName})")
                    },
                )
            }
        } finally {
            // 会话守卫:仅当前会话才 close 共享 prefetchStream —— 过期会话的预加载
            // 线程已在自身 finally 用局部引用关闭自己的 body;此处若无条件 close 共享
            // 字段会误关**新会话**正在下载的流(预加载静默中断)
            if (mySession == sessionId) runCatching { prefetchStream?.close() }
            // 会话结束收尾:EOF 路径已由 finishCache 处理(complete 或保留 partial 等预加载补齐);
            // 其余退出(手动停止/切歌/seek/全部候选失败)一律作废,不留垃圾 partial。
            // 注意:seek/切歌是非阻塞切会话 —— 旧会话线程此刻可能已不是当前会话,
            // 若仍无条件 discard() 会删除**新会话刚创建的 partial 文件**(旧 writer 的
            // delete 删的是新 writer 正在写的路径)→ 新会话继续写已 unlink 的 inode,
            // 结束时 rename 失败 → 缓存永远无法标完整。过期会话只 close() 句柄不删文件。
            val cw = cacheWriter
            if (cw != null && !eofFinished) {
                if (mySession == sessionId) cw.discard() else cw.close()
            }
            val myLine = slot.line
            try {
                myLine?.stop()
            } catch (_: Exception) {
            }
            try {
                myLine?.close()
            } catch (_: Exception) {
            }
            // 解码器释放(HTTP 流随之关闭);引用保护:新会话的解码器不误清
            val dec = myDecoder
            if (dec != null) {
                runCatching { dec.close() }
                if (decoder === dec) decoder = null
            }
            if (line === myLine) line = null
        }
    }

    /**
     * 会话结束(播放线程 EOF)时的缓存收尾:
     * - 从 0 连续写到了流尾(或未知流长但确实读到了流尾)→ 标记完整,下次本地播放;
     * - 预加载线程还活着且在顺序补齐(seek 空洞/下载中)→ 保留 partial,等它自行判定;
     * - 否则(流被截断 / CDN 文件损坏 / 预加载已死)→ 作废删除,绝不把坏文件当完整缓存。
     */
    private fun finishCache(writer: AudioCache.CacheWriter, mySession: Int) {
        val log = io.github.cyf112233.musicmc.NetMusic.logger
        if (writer.isComplete() || (writer.totalBytes <= 0 && writer.contiguousLength() > 0)) {
            writer.complete()
            log.info("[Engine] session=$mySession 缓存完成(下次本地播放,${writer.progress()} 字节)")
        } else if (prefetchAlive) {
            // 预加载线程还在补空洞:保留 partial,待其 EOF 后自行 complete/discard
            log.info("[Engine] session=$mySession EOF 但缓存未写满,等待预加载补齐")
        } else {
            writer.discard()
            log.warn("[Engine] session=$mySession 缓存不完整(流截断/损坏),已作废")
        }
    }

    /**
     * 后台预加载线程:独立 HTTP 连接**从 0 顺序下载到流尾**(覆盖探测/播放已写区段,
     * 并补全 seek 空洞),把"整首提前下载完成"变成常态。播放线程 AVIO 写与预加载写
     * 并发,由 CacheWriter 内部锁串行;失败/中断不影响播放。
     *
     * 资源纪律(2026-08 加固):
     * - body 用**局部引用**并在线程 finally 关闭:正常 EOF(整首下载完成)路径此前
     *   不会 close body → 每次预加载完成留一个挂起的 HTTP 连接;
     * - 共享字段 [prefetchStream] 赋值带会话守卫:过期线程不得覆盖新会话的引用
     *   (否则旧线程 finally 的 close 会误伤新会话正在下载的流 → 预加载静默中断)。
     */
    private fun startPrefetch(url: String, writer: AudioCache.CacheWriter, mySession: Int) {
        val log = io.github.cyf112233.musicmc.NetMusic.logger
        log.info("[Engine] session=$mySession 预加载启动 url=${url.take(80)}")
        prefetchAlive = true
        Thread({
            var off = 0L
            var body: java.io.InputStream? = null
            try {
                body = Http.openStreamInfo(url, 0, referer).body
                if (mySession == sessionId) prefetchStream = body
                val tmp = ByteArray(64 * 1024)
                while (!stopped && mySession == sessionId) {
                    val n = body.read(tmp)
                    if (n < 0) break
                    writer.write(off, tmp, n)
                    off += n
                }
                if (!stopped && mySession == sessionId && off > 0) {
                    if (writer.isComplete() || writer.totalBytes <= 0) {
                        writer.complete()
                        log.info("[Engine] session=$mySession 预加载完成,整首已就绪")
                    }
                    // 有 CL 但没写满:流截断(CDN 坏)→ 不标完整,播放线程 EOF 后由 finishCache 作废
                }
            } catch (e: Exception) {
                // 预加载失败(CDN 坏/断流):不影响播放,播放线程 AVIO 继续写缓存
                log.warn("[Engine] session=$mySession 预加载中断(${e.javaClass.simpleName}: ${e.message})")
            } finally {
                runCatching { body?.close() }
                // 会话守卫:过期线程不置 null/清 alive(那些状态属于新会话)
                if (mySession == sessionId) {
                    prefetchStream = null
                    prefetchAlive = false
                }
            }
        }, "NetMusic-Prefetch").apply { isDaemon = true; start() }
    }

    /** 创建输出:桌面 javax.sound / Android AAudio(API 26+,独立于 OpenSL/OpenAL,不冲突 MC SoundEngine) */
    private fun createOutput(rate: Int, channels: Int): AudioOutput {
        if (rate <= 0 || channels <= 0) throw java.io.IOException(UiText.t("FFmpeg 解码输出格式无效", "Invalid FFmpeg output format"))
        return if (NativeLibBridge.isAndroid()) AAudioOutput(rate, channels) else SourceDataLineOutput(rate, channels)
    }

    /** 音量:桌面走 MASTER_GAIN/VOLUME,javax.sound;Android 走 AAudio setVolume */
    private fun applyVolume(v: Float) {
        line?.setVolume(v)
    }
}

/** 本会话打开的 line/解码器引用(供 playLoop 写输出与 finally 清理) */
private class FfmpegLineSlot {
    var line: AudioOutput? = null
    /** 攒批写入缓冲(line 创建时一并创建,随会话清理) */
    var batcher: PcmBatcher? = null
}