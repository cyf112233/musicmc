package io.github.cyf112233.musicmc.player.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine

/**
 * 桌面 javax.sound 输出(原 FfmpegAudioEngine.createLine/applyVolume 逻辑独立出来)。
 * 构造即打开并 start(调用方在首帧解码后创建;128KB line 缓冲减少 write 阻塞与碎片化)。
 */
class SourceDataLineOutput(rate: Int, channels: Int) : AudioOutput {

    private var line: SourceDataLine? = null

    init {
        if (rate <= 0 || channels <= 0) throw java.io.IOException("Invalid FFmpeg output format")
        val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
        val l = AudioSystem.getSourceDataLine(format)
        l.open(format, 131072) // 128KB line 缓冲,减少 write 阻塞与碎片化
        l.start()
        line = l
    }

    override val isActive: Boolean get() = line?.isActive ?: false

    override fun write(data: ByteArray, off: Int, len: Int) {
        val l = line ?: return
        l.write(data, off, len)
    }

    override fun stop() {
        runCatching { line?.stop() }
    }

    override fun close() {
        runCatching { line?.close() }
        line = null
    }

    /** 音量:优先 MASTER_GAIN,其次 VOLUME;不支持则忽略(同原有引擎约定) */
    override fun setVolume(v: Float) {
        val out = line ?: return
        try {
            val gain: FloatControl? = try {
                out.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl
            } catch (_: IllegalArgumentException) {
                null
            } ?: try {
                out.getControl(FloatControl.Type.VOLUME) as? FloatControl
            } catch (_: IllegalArgumentException) {
                null
            }
            if (gain != null) {
                val db = if (v < 0.001f) -80.0 else 20.0 * Math.log10(v.toDouble())
                gain.value = db.toFloat().coerceIn(gain.minimum, gain.maximum)
            }
        } catch (_: Exception) {
            // 不支持音量控制则忽略
        }
    }
}
