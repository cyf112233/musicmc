package io.github.cyf112233.musicmc.player

import io.github.cyf112233.musicmc.platform.PlatformHolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 音频缓存:播放时把网络音频流边播边落盘,整首完整下载后下次直接本地播放(离线可听、
 * 不依赖 CDN 稳定性)。
 *
 * 文件布局(config/musicmc/audio_cache/):
 * - `key.m4s.partial`:正在下载的临时文件(播放/预加载线程按 offset 乱序写入);
 * - `key.m4s`:完整缓存(写入量达到 Content-Length,或播放线程/预加载线程读到流尾后
 *   由 [CacheWriter.complete] 原子 rename 而来);存在即视为可本地播放。
 *
 * key 由调用方(MusicPlayer)用 `歌曲id_bitrate` 构造;损坏/不完整的 partial 不会被
 * 当作完整缓存使用,重播同一首时 [writer] 会删除旧 partial 重新下载。
 *
 * 线程安全:CacheWriter 全部方法 @Synchronized(解码线程 AVIO 写、预加载线程写、播放
 * 线程 complete/discard 并发);RandomAccessFile 的 seek+write 在锁内串行。
 */
object AudioCache {

    private val dir: File by lazy {
        PlatformHolder.require().configDirectory().resolve("audio_cache").toFile().apply { mkdirs() }
    }

    /** 缓存 key 对应的完整缓存文件;未缓存(或损坏)返回 null */
    fun completeFile(key: String): File? {
        val f = File(dir, key + ".m4s")
        return if (f.isFile && f.length() > 0) f else null
    }

    /** 删除指定 key 的完整缓存(本地缓存播放失败时作废坏缓存) */
    fun invalidate(key: String) {
        runCatching { File(dir, key + ".m4s").delete() }
        runCatching { File(dir, key + ".m4s.partial").delete() }
    }

    /**
     * 打开写入器(删除旧 partial 重写)。
     * @param totalBytes 已知流总长(Content-Length);-1 表示未知。
     */
    fun writer(key: String, totalBytes: Long): CacheWriter {
        dir.mkdirs()
        val f = File(dir, key + ".m4s.partial")
        runCatching { f.delete() } // 覆盖旧 partial
        return CacheWriter(f, totalBytes)
    }

    /** 全部缓存文件总字节数(含 partial) */
    fun totalSize(): Long = runCatching {
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    /** 删除全部缓存,返回释放的字节数 */
    fun clear(): Long {
        val freed = totalSize()
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        return freed
    }

    /**
     * 缓存写入器:按逻辑流偏移写入(RandomAccessFile),供 AVIO read 回调与预加载
     * 线程并发写同一文件的不同区段。
     *
     * 完整性判定用"从 0 连续的已写前缀长度([contiguousEnd])":顺序播放写 0..EOF、
     * 预加载线程从 0 顺序下载都会连续延伸前缀;seek 造成的空洞不会虚标完整 ——
     * 有空洞时 [isComplete] 保持 false,直到预加载线程顺序写把它补齐。
     */
    class CacheWriter(
        private val file: File,
        totalBytes: Long,
    ) {
        private val raf = RandomAccessFile(file, "rw")
        @Volatile
        private var total = totalBytes
        @Volatile
        private var maxWritten = 0L
        @Volatile
        private var contiguousEnd = 0L
        @Volatile
        private var done = false

        /** 更新已知流总长(open 完成后由引擎注入 Content-Length) */
        @Synchronized
        fun setTotal(t: Long) {
            total = t
        }

        /** 已知流总长(-1 未知);完整判定用 */
        val totalBytes: Long
            get() = total

        /** 写入 [len] 字节到 [offset] 处;写盘失败(磁盘满等)自动放弃缓存,不影响播放 */
        @Synchronized
        fun write(offset: Long, bytes: ByteArray, len: Int) {
            if (done) return
            try {
                raf.seek(offset)
                raf.write(bytes, 0, len)
                val end = offset + len
                if (end > maxWritten) maxWritten = end
                if (offset <= contiguousEnd && end > contiguousEnd) contiguousEnd = end
                // offset > contiguousEnd:seek 造成的空洞,等预加载线程从 0 顺序写来补
            } catch (e: Exception) {
                done = true
                runCatching { raf.close() }
                runCatching { file.delete() }
            }
        }

        /** 已写入的最大逻辑位置(日志/进度展示用) */
        @Synchronized
        fun progress(): Long = maxWritten

        /** 从 0 连续的已写前缀长度(预加载线程顺序写会补全空洞) */
        @Synchronized
        fun contiguousLength(): Long = contiguousEnd

        /** 是否已完整:从 0 连续写到了已知流尾(Content-Length) */
        @Synchronized
        fun isComplete(): Boolean = totalBytes > 0 && contiguousEnd >= totalBytes

        /**
         * 标记完整:关闭文件并把 partial 原子 rename 为正式缓存。
         * 之后 [write] 一律忽略(播放线程可能仍会读到旧数据)。
         */
        @Synchronized
        fun complete() {
            if (done) return
            done = true
            runCatching { raf.close() }
            val target = File(file.parentFile, file.name.removeSuffix(".partial"))
            runCatching { Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }

        /** 放弃缓存:关闭并删除 partial 文件(播放中断 / 全部候选失败时) */
        @Synchronized
        fun discard() {
            if (done) return
            done = true
            runCatching { raf.close() }
            runCatching { file.delete() }
        }

        /** 释放(不删文件,不标完整;仅关闭句柄) */
        @Synchronized
        fun close() {
            if (done) return
            done = true
            runCatching { raf.close() }
        }
    }
}
