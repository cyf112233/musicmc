package io.github.cyf112233.musicmc.player

import io.github.cyf112233.musicmc.player.audio.AudioOutput

/**
 * PCM 攒批写入缓冲(修复长播卡顿:解码循环逐帧小粒度 write 改为累积到内部缓冲、
 * 攒满 64KB 才向 [AudioOutput] 写一次,大幅减少小粒度 write 的同步/阻塞开销)。
 *
 * 使用约定(与三个引擎的解码循环配合):
 * - [write] 只累积不阻塞;攒满 [FLUSH_THRESHOLD] 或单次超过阈值时落一次;
 * - [flush] 把剩余内容写出去 —— 循环自然结束 / 暂停前调用;
 * - 停止 / seek 打断时调用方不要 flush,未写内容随 [AudioOutput] 关闭而丢弃。
 */
internal class PcmBatcher(private val output: AudioOutput) {

    companion object {
        /** 内部缓冲容量(256KB) */
        const val CAPACITY = 256 * 1024

        /** 攒满阈值:达到即向 line 写一次(64KB) */
        const val FLUSH_THRESHOLD = 64 * 1024
    }

    private val buf = ByteArray(CAPACITY)
    private var size = 0

    /** 累积一段 PCM;攒满阈值时落盘(写 line) */
    fun write(data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (len >= FLUSH_THRESHOLD) {
            // 单帧就够大:先清空已累积再直接写,避免一次多余拷贝
            flush()
            output.write(data, off, len)
            return
        }
        if (size + len > CAPACITY) flush()
        System.arraycopy(data, off, buf, size, len)
        size += len
        if (size >= FLUSH_THRESHOLD) flush()
    }

    /** 把剩余内容写入 line(自然结束 / 暂停前调用);无累积时为空操作 */
    fun flush() {
        if (size > 0) {
            output.write(buf, 0, size)
            size = 0
        }
    }
}
