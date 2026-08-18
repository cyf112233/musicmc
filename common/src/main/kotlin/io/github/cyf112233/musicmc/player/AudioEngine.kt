package io.github.cyf112233.musicmc.player

/**
 * 打开音频流时的附加选项(2026-08 新增,哔哩哔哩 fMP4 音源用):
 * - [referer]:CDN 需要的 Referer(如 B 站要求 https://www.bilibili.com/);null 用默认头;
 * - [backupUrls]:主 URL 打开失败时逐个重试的备用直链(B 站 playurl 的 backupUrl);
 * - [cacheKey]:音频缓存 key(如 "BVxxxx_320");非空时引擎边播边把网络流写入缓存,
 *   播放自然结束(整首读完)后标记完整供下次本地播放;中断/失败自动作废。
 * 网易云音源 referer/backupUrls 均为空;本地缓存播放(完整缓存命中)时三者皆空。
 */
data class StreamOptions(
    val referer: String? = null,
    val backupUrls: List<String> = emptyList(),
    val cacheKey: String? = null,
)

/**
 * 音频引擎抽象(唯一实现:ffmpeg/FfmpegAudioEngine —— avformat 解封装 + avcodec 解码 +
 * swresample 转 s16 的 FFmpeg 解码播放;平台无原生库时不支持播放)。
 *
 * 回调约定:onStarted / onFinished / onError 在播放线程调用,
 * 调用方([io.github.cyf112233.musicmc.player.MusicPlayer])负责切回 UI 线程。
 */
interface AudioEngine {
    val isPlaying: Boolean

    /** 加载并播放;load 幂等(会先停掉旧流);seek 到 0 从头播 */
    fun load(url: String, options: StreamOptions, onStarted: () -> Unit, onFinished: () -> Unit, onError: (String) -> Unit)

    fun pause()
    fun resume()
    fun stop()

    /**
     * 跳到 [positionMs] 毫秒处。[totalMs] 为歌曲总时长(毫秒),引擎可按预算位
     * 定位目标(FFmpeg 引擎走 av_seek_frame 按流 time_base 精确锚定);未知时传 0。
     */
    fun seekTo(positionMs: Int, totalMs: Int)

    /** 当前播放位置(毫秒,近似值) */
    fun positionMs(): Int

    /** 音量 0..1 */
    fun setVolume(v: Float)

    fun release()
}
