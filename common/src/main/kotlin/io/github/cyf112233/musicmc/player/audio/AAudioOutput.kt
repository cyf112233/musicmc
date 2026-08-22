package io.github.cyf112233.musicmc.player.audio

import io.github.cyf112233.musicmc.client.UiText
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

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
 * 线程安全(2026-08 修复连续 seek 崩溃):每次创建分配唯一 [owner] token,经
 * nativeInit / nativeRelease 传给原生层 —— 原生互斥锁保证 close 永不与并发 write
 * 冲突,owner 保证旧会话迟到 release 不会误关新会话的流。JNI 调用无线程亲和
 * (nativeStop 可任意线程,唤醒阻塞中的 nativeWrite)。
 */
class AAudioOutput(private val rate: Int, private val channels: Int) : AudioOutput {

    companion object {
        private val NEXT_OWNER = AtomicLong(1)

        /** 分配唯一会话 token(每创建一个 AAudioOutput 递增一次,不回绕) */
        private fun nextOwner(): Long = NEXT_OWNER.getAndIncrement()
    }

    /** 本输出实例的会话 token(原生层据此隔离新旧会话的流) */
    private val owner: Long = nextOwner()

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
            throw IOException(UiText.t("AAudio 音频写入失败", "AAudio write failed"))
        }
        active = true
        // 音量已由 ensureInited/setVolume 下发,不在每次 write 重复调 JNI(高频路径)
    }

    /** 首次写数据时(播放线程)打开 AAudioStream */
    private fun ensureInited() {
        if (inited) return
        if (closed) throw IOException(UiText.t("音频输出已关闭", "Audio output closed"))
        if (AAudioPlayer.nativeInit(owner, rate, channels) != 0) {
            throw IOException(UiText.t("AAudio 初始化失败(rate=$rate channels=$channels)", "AAudio init failed (rate=$rate channels=$channels)"))
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
        runCatching { AAudioPlayer.nativeRelease(owner) }
        inited = false
    }

    override fun setVolume(v: Float) {
        targetVolume = v.coerceIn(0f, 1f)
        if (inited) runCatching { AAudioPlayer.nativeSetVolume(targetVolume) }
    }
}
