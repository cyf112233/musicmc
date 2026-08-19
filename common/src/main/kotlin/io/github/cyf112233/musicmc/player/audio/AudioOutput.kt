package io.github.cyf112233.musicmc.player.audio

/**
 * 音频输出抽象(PCM s16le 交织)。
 *
 * 两条实现,按平台分派:
 * - 桌面:[SourceDataLineOutput] —— javax.sound,成熟稳定;
 * - Android:[AAudioOutput] —— AAudio(NDK 原生,Android 8.0+/API 26+)。Android 的
 *   OpenJDK 没有 javax.sound 设备;OpenSL ES 直调与 MC OpenAL 的 OpenSL Engine 冲突
 *   (SIGSEGV);OpenAL 自建设备会破坏 MC SoundEngine。AAudio 独立于 OpenSL/OpenAL,
 *   阻塞式写入,确定稳定。
 *
 * 线程约定(与 FfmpegAudioEngine 一致):write 只在播放线程调用;
 * stop/close/setVolume 可在任意线程调用,实现需自行保证线程安全
 * (非 owner 线程仅置标志,实际清理由播放线程完成)。
 */
interface AudioOutput {

    /** 输出是否处于活动状态(UI 播放状态显示用;线程安全) */
    val isActive: Boolean

    /** 写入一段 PCM(可能阻塞至设备可消费;closed 后调用为空操作) */
    fun write(data: ByteArray, off: Int, len: Int)

    /** 停止输出(立即静音;幂等,可被 [close] 前的播放线程 finally 兜底) */
    fun stop()

    /** 关闭并释放全部资源(幂等;仅 owner 线程执行实际清理) */
    fun close()

    /** 音量 0..1 */
    fun setVolume(v: Float)
}
