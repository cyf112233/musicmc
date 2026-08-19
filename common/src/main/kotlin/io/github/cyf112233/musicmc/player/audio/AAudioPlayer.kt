package io.github.cyf112233.musicmc.player.audio

/**
 * AAudio 音频输出 JNI 绑定(Android 8.0+ / API 26+)。
 *
 * 原生实现:native/audio/aaudio_player.c;库由 [io.github.cyf112233.musicmc.player.ffmpeg.NativeLibBridge]
 * 从 classpath 解包到 app 私有区并 System.load(注册到 musicmc mod 加载器,与本类同加载器)。
 *
 * 为什么 AAudio:独立于 OpenSL/OpenAL —— MC SoundEngine 的 OpenAL(OpenSL 后端)已占用
 * OpenSL Engine,直调 OpenSL 会 SIGSEGV;自建 OpenAL 设备会破坏 MC SoundEngine。
 * AAudio 是系统原生、无 Engine/设备/上下文共享的原生通道,阻塞式写入(语义同 SourceDataLine),
 * 确定稳定。
 */
object AAudioPlayer {

    /** 打开 AAudioStream 并开始播放;0 成功,非 0 失败 */
    @JvmStatic
    external fun nativeInit(sampleRate: Int, channels: Int): Int

    /** 阻塞写入 PCM(s16le 交织);0 成功,非 0 失败/已停止 */
    @JvmStatic
    external fun nativeWrite(data: ByteArray, off: Int, len: Int): Int

    /** 停止(幂等;唤醒阻塞中的 nativeWrite) */
    @JvmStatic
    external fun nativeStop(): Int

    /** 关闭并释放流(幂等) */
    @JvmStatic
    external fun nativeRelease(): Int

    /** 音量 0..1 */
    @JvmStatic
    external fun nativeSetVolume(v: Float): Int
}
