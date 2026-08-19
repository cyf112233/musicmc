package io.github.cyf112233.musicmc.player.audio

import java.io.IOException

/**
 * Android 专用音频输出:AAudio(NDK 原生通道,Android 8.0+ / API 26+)。
 *
 * 为什么不用 OpenSL ES / OpenAL:
 * - OpenSL ES 直调与 MC OpenAL 的 OpenSL Engine 冲突(SIGSEGV,实测崩溃);
 * - OpenAL 自建设备/上下文破坏 MC SoundEngine("Creating buffer: Invalid operation",
 *   SoundEngine 反复重启);
 * - AAudio 独立于 OpenSL/OpenAL,无 Engine/设备/上下文共享,阻塞式写入语义与
 *   SourceDataLine 一致,确定稳定。
 *
 * 线程安全:JNI 调用无线程亲和要求(nativeStop/nativeRelease 可任意线程调用,
 * 会唤醒阻塞中的 nativeWrite),因此无需 owner 线程限定。
 */
class AAudioOutput(private val rate: Int, private val channels: Int) : AudioOutput {

    @Volatile
    private var closed = false

    @Volatile
    private var active = false

    @Volatile
    private var targetVolume = 1f

    private var inited = false

    override val isActive: Boolean get() = active && !closed

    override fun write(data: ByteArray, off: Int, len: Int) {
        if (closed || len <= 0) return
        ensureInited()
        if (AAudioPlayer.nativeWrite(data, off, len) != 0) {
            throw IOException("AAudio 音频写入失败")
        }
        active = true
        AAudioPlayer.nativeSetVolume(targetVolume)
    }

    /** 首次写数据时(播放线程)打开 AAudioStream */
    private fun ensureInited() {
        if (inited) return
        if (closed) throw IOException("音频输出已关闭")
        if (AAudioPlayer.nativeInit(rate, channels) != 0) {
            throw IOException("AAudio 初始化失败(rate=$rate channels=$channels)")
        }
        inited = true
        AAudioPlayer.nativeSetVolume(targetVolume)
    }

    override fun stop() {
        closed = true
        active = false
        runCatching { AAudioPlayer.nativeStop() }
    }

    override fun close() {
        closed = true
        active = false
        runCatching { AAudioPlayer.nativeStop() }
        runCatching { AAudioPlayer.nativeRelease() }
        inited = false
    }

    override fun setVolume(v: Float) {
        targetVolume = v.coerceIn(0f, 1f)
        if (inited) runCatching { AAudioPlayer.nativeSetVolume(targetVolume) }
    }
}
