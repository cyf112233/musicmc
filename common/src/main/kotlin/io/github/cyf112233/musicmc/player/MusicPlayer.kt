package io.github.cyf112233.musicmc.player

import io.github.cyf112233.musicmc.api.MusicSource
import io.github.cyf112233.musicmc.config.ModConfig
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.player.ffmpeg.FfmpegAudioEngine
import io.github.cyf112233.musicmc.player.ffmpeg.FfmpegDecoder
import io.github.cyf112233.musicmc.util.Async
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 播放器。由 [io.github.cyf112233.musicmc.NetMusic] 持有(单例)。
 *
 * 线程模型:
 * - 播放器状态 / 队列由 UI 线程读写;
 * - [io.github.cyf112233.musicmc.api.MusicSource] 回调在后台线程,进入后统一 postToUiThread 通知监听器;
 * - 引擎回调(onStarted/onFinished/onError)发生在播放线程,同样统一转发;
 * - 进度轮询线程每 500ms 上报一次进度(仅在 PLAYING 时)。
 */
class MusicPlayer(
    val source: MusicSource,
    config: ModConfig,
    // 唯一播放引擎:FFmpeg 解码(avformat/avcodec/swresample);平台无原生库时不支持播放
    val engine: AudioEngine = FfmpegAudioEngine(),
) {
    /** 当前配置引用(设置页开关经 [NetMusic.updateConfig] 更新;play() 内 songUrl 读取最新 bitrate) */
    var config: ModConfig = config
        private set

    /**
     * 更新配置引用(由 [io.github.cyf112233.musicmc.NetMusic.updateConfig] 调用;音量初始化 / 播放模式
     * 等由各自入口读取,此处仅同步引用)。
     */
    fun updateConfig(c: ModConfig) {
        config = c
    }

    /**
     * 播放队列。用 CopyOnWriteArrayList:播放线程(handleFinished 自动切歌)会并发读,
     * UI 线程可能同时 clear/addAll,保证读写安全。
     */
    val queue: MutableList<Song> = CopyOnWriteArrayList()

    var index = -1
        private set

    var mode = PlayMode.SEQUENCE
        private set

    @Volatile
    var state = PlayerState.IDLE
        private set

    var current: Song? = null
        private set

    private val listeners = CopyOnWriteArrayList<PlayerListener>()
    private val progressExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NetMusic-Progress").apply { isDaemon = true }
    }
    private val random = Random.Default

    /** 播放会话号:防止过期的 songUrl 回调覆盖新一次的播放(UI 线程写,后台线程读) */
    @Volatile
    private var playSession = 0

    init {
        mode = runCatching { PlayMode.valueOf(config.playMode) }.getOrDefault(PlayMode.SEQUENCE)
        progressExecutor.scheduleAtFixedRate({ pollProgress() }, 500, 500, TimeUnit.MILLISECONDS)
    }

    // ---------------- listeners ----------------

    fun addListener(l: PlayerListener) = listeners.add(l)
    fun removeListener(l: PlayerListener) = listeners.remove(l)

    private fun notifyStateChanged() {
        Async.onUi { for (l in listeners) l.onStateChanged(state, current) }
    }

    private fun notifySongChanged() {
        Async.onUi { for (l in listeners) l.onSongChanged(current) }
    }

    private fun notifyProgress(posMs: Int, durationMs: Int) {
        Async.onUi { for (l in listeners) l.onProgress(posMs, durationMs) }
    }

    /** 用户提示:统一经监听器 [PlayerListener.onToast] 在 UI 线程弹出(播放线程 / 后台回调线程均可调用) */
    private fun toast(msg: String) {
        Async.onUi { for (l in listeners) l.onToast(msg) }
    }

    // ---------------- control ----------------

    /**
     * 播放歌曲。可附带替换整个队列([queue])与指定下标([index])。
     */
    fun play(song: Song, queue: List<Song>? = null, index: Int? = null) {
        val session = ++playSession
        if (queue != null) {
            this.queue.clear()
            this.queue.addAll(queue)
        }
        this.index = index ?: this.queue.indexOf(song).let { if (it < 0) 0 else it }
        this.current = song
        this.state = PlayerState.LOADING
        notifyStateChanged()
        notifySongChanged()

        engine.stop()
        // 会话内重试标记:开播即失败(URL 失效)与播放中失败(CDN 文件损坏/流被掐)各允许
        // 重新取一次地址(playurl 每次返回的 CDN 组合可能不同,坏 mirrorcos 换成好 estgcos),
        // 第二次才报错/切歌 —— 根治"放到一半就下一曲"。
        var startupRetried = false
        var midRetried = false
        // 本次会话是否走了本地缓存(缓存文件损坏时需作废,改走网络重下)
        var usedCached = false
        // 缓存 key:歌曲 id + 码率;完整缓存存在时直接本地播放
        val cacheKey = "${song.id}_${config.bitrate}"
        // 取地址并加载。成功/失败回调都在后台线程,统一用 session 防过期
        // (重试 / 自动切歌不得越权到新的 play 会话)。
        // loadUrl 与 startEngine 互相引用(Kotlin 局部函数禁止前向引用),故声明为函数类型变量
        lateinit var loadUrl: () -> Unit
        lateinit var startEngine: (String, StreamOptions) -> Unit

        loadUrl = loadUrl@{
            // 平台原生库检查:FFmpeg 不可用(未打包平台 jar / 加载失败)→ 直接报错,不触碰引擎
            if (!FfmpegDecoder.nativeAvailable()) {
                state = PlayerState.ERROR
                notifyStateChanged()
                val msg = "当前平台不支持播放(缺少 FFmpeg 原生库)"
                toast(msg)
                io.github.cyf112233.musicmc.NetMusic.logger.warn(msg)
                return@loadUrl
            }
            source.songUrl(song, config.bitrate) { result, err ->
                if (session != playSession) return@songUrl // 已被新的 play 取代
                if (result == null) {
                    state = PlayerState.ERROR
                    notifyStateChanged()
                    val msg = if (err.isNullOrBlank()) "播放失败" else "播放失败: $err"
                    toast(msg)
                    io.github.cyf112233.musicmc.NetMusic.logger.warn(msg)
                } else {
                    // 完整缓存命中:本地直接播放(离线可听、不依赖 CDN 稳定性),不再取网络流
                    val cached = AudioCache.completeFile(cacheKey)
                    if (cached != null) {
                        usedCached = true
                        io.github.cyf112233.musicmc.NetMusic.logger.info("[Player] 缓存命中,本地播放 ${cached.absolutePath}")
                        startEngine(cached.absolutePath, StreamOptions())
                    } else {
                        usedCached = false
                        // 网络播放:带缓存 key 边播边落盘(整首读完自动标完整)
                        startEngine(
                            result.url,
                            StreamOptions(
                                referer = result.referer,
                                backupUrls = result.backupUrls,
                                cacheKey = cacheKey,
                            ),
                        )
                    }
                }
            }
        }

        /** 统一入口:音量下发 + 加载播放(回调共用,缓存/网络两条路径行为一致) */
        startEngine = { uri, options ->
            // 音量下发:play 前把 config.volume 推给引擎(引擎内持目标音量,
            // 直接换引擎 / 切歌后音量保持,不再重置回默认 1f)
            engine.setVolume(config.volume)
            engine.load(
                uri,
                options,
                onStarted = {
                    if (session == playSession) {
                        state = PlayerState.PLAYING
                        notifyStateChanged()
                    }
                },
                onFinished = {
                    if (session == playSession) handleFinished()
                },
                onError = { msg ->
                    if (session == playSession) {
                        if (engine.positionMs() > 5000) {
                            // 已播 5 秒以上才断(CDN 文件损坏/流被掐):先重新取一次地址
                            // (playurl 换一批 CDN,坏 mirrorcos 可能换成好 estgcos)续播,
                            // 第二次仍失败才自动切歌
                            if (!midRetried) {
                                midRetried = true
                                toast("播放中断,正在重新获取地址…")
                                loadUrl()
                            } else {
                                toast("播放中断,已自动切歌")
                                handleFinished()
                            }
                        } else if (!startupRetried) {
                            // 刚开播就失败:大概率 URL 失效,同曲目重试一次;
                            // 本地缓存播放失败则作废坏缓存,重试时改走网络重新下载
                            startupRetried = true
                            if (usedCached) {
                                AudioCache.invalidate(cacheKey)
                                io.github.cyf112233.musicmc.NetMusic.logger.warn("[Player] 本地缓存损坏,已作废,改走网络: $cacheKey")
                            }
                            loadUrl()
                        } else {
                            state = PlayerState.ERROR
                            notifyStateChanged()
                            toast(msg)
                            io.github.cyf112233.musicmc.NetMusic.logger.warn(msg)
                        }
                    }
                    // 会话已过期:交给新的 play 处理,此处不做任何动作
                },
            )
        }
        loadUrl()
    }

    /** 播放 / 暂停切换 */
    fun toggle() {
        when (state) {
            PlayerState.PLAYING -> {
                engine.pause()
                state = PlayerState.PAUSED
                notifyStateChanged()
            }
            PlayerState.PAUSED -> {
                engine.resume()
                state = PlayerState.PLAYING
                notifyStateChanged()
            }
            else -> current?.let { play(it) }
        }
    }

    /** 下一首(手动) */
    fun next() {
        if (queue.isEmpty()) return
        val nextIndex = when (mode) {
            PlayMode.SHUFFLE -> if (queue.size <= 1) 0 else random.nextInt(queue.size)
            else -> (index + 1) % queue.size
        }
        play(queue[nextIndex])
    }

    /** 上一首(手动) */
    fun prev() {
        if (queue.isEmpty()) return
        // Kotlin 2.4.0 中 Int.floorMod 已被移除,改用 java.lang.Math.floorMod(语义一致)
        play(queue[java.lang.Math.floorMod(index - 1, queue.size)])
    }

    fun seekTo(positionMs: Int) {
        // LOADING 时曲目地址还没定(引擎可能还停在旧歌),此时 seek 会作用到旧歌上,直接忽略
        if (state == PlayerState.LOADING) return
        engine.seekTo(positionMs.coerceAtLeast(0), current?.durationMs ?: 0)
    }

    fun setVolume(v: Float) {
        engine.setVolume(v.coerceIn(0f, 1f))
    }

    /** 循环切换播放模式(顺序 → 列表循环 → 单曲循环 → 随机 → ...) */
    fun cycleMode() {
        mode = PlayMode.entries[(mode.ordinal + 1) % PlayMode.entries.size]
        notifyStateChanged()
    }

    /** 清空队列并停止 */
    fun clearQueue() {
        playSession++
        engine.stop()
        queue.clear()
        index = -1
        current = null
        state = PlayerState.IDLE
        notifyStateChanged()
        notifySongChanged()
    }

    // ---------------- internal ----------------

    private fun pollProgress() {
        if (state == PlayerState.PLAYING) {
            notifyProgress(engine.positionMs(), current?.durationMs ?: 0)
        }
    }

    /**
     * 播放完毕(引擎回调,播放线程):按播放模式自动切歌。
     * 单曲队列(size<=1)时 next() 会重播同一首,SEQUENCE 模式走 stopPlayback 直接停止。
     * 注意这里会调用 [play],其中 engine.stop() 有"播放线程自身调用"保护,不会死锁。
     */
    private fun handleFinished() {
        when (mode) {
            PlayMode.LOOP_ONE -> current?.let { play(it) }
            PlayMode.SHUFFLE -> next()
            PlayMode.SEQUENCE -> if (index < queue.size - 1) play(queue[index + 1]) else stopPlayback()
            PlayMode.LOOP_ALL -> if (queue.isNotEmpty()) play(queue[(index + 1) % queue.size])
        }
    }

    private fun stopPlayback() {
        engine.stop()
        state = PlayerState.IDLE
        notifyStateChanged()
    }
}
