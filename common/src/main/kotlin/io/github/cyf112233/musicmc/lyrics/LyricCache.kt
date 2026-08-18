package io.github.cyf112233.musicmc.lyrics

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.cyf112233.musicmc.model.LyricLine
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.platform.PlatformHolder
import io.github.cyf112233.musicmc.util.Lrc
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 歌词本地缓存:按歌曲持久化到 <configDir>/musicmc/lyrics/<key>.json。
 *
 * JSON 只存原始 LRC 文本与元数据,歌词行始终由 [CachedLyric.lines] 经 [Lrc.parseLrc]
 * 恢复(保证跨版本可读,不依赖数据类字段演化):
 *   {"id","updateTime","lrc","userOffsetSec","manual","source"}
 */
data class CachedLyric(
    val id: String,
    val updateTime: Long,
    val lrc: String,
    val userOffsetSec: Float,
    val manual: Boolean,
    val source: String?,
) {
    /** 由 lrc 文本恢复歌词行 */
    fun lines(): List<LyricLine> = Lrc.parseLrc(lrc)
}

object LyricCache {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** key 白名单(与 Hub 服务端一致:[A-Za-z0-9:_-],其余替换为 '-') */
    private val SANITIZE_KEEP = Regex("[A-Za-z0-9:_\\-]")

    /** 缓存目录:config/musicmc/lyrics */
    val dir: Path get() = PlatformHolder.require().configDirectory().resolve("musicmc").resolve("lyrics")

    /** 歌曲 → 缓存 key:"bilibili--<sanitized 视频 id>"(Hub 客户端也用它) */
    fun keyFor(song: Song): String = "bilibili--" + sanitize(song.id)

    fun sanitize(id: String): String = buildString {
        for (c in id) append(if (SANITIZE_KEEP.matches(c.toString())) c else '-')
    }

    private fun fileFor(key: String): Path = dir.resolve("$key.json")

    /** 读取缓存;文件缺失 / 损坏 / lrc 为空返回 null */
    fun load(key: String): CachedLyric? {
        return try {
            val file = fileFor(key)
            if (!Files.isReadable(file)) return null
            val cached = gson.fromJson(Files.readString(file), CachedLyric::class.java) ?: return null
            if (cached.lrc.isBlank()) null else cached
        } catch (e: Exception) {
            null
        }
    }

    /** 保存歌词缓存(lines 序列化为 LRC 文本);任何失败静默(缓存失败不影响歌词使用) */
    fun save(key: String, lines: List<LyricLine>, offsetSec: Float, manual: Boolean, source: String?) {
        try {
            Files.createDirectories(dir)
            val cached = CachedLyric(
                id = key,
                updateTime = System.currentTimeMillis(),
                lrc = Lrc.toLrc(lines),
                userOffsetSec = offsetSec,
                manual = manual,
                source = source,
            )
            atomicWrite(fileFor(key), gson.toJson(cached))
        } catch (e: Exception) {
            // 容错
        }
    }

    /** 只更新偏移(即点即存);无缓存时忽略 */
    fun saveOffset(key: String, offsetSec: Float) {
        val cached = load(key) ?: return
        try {
            atomicWrite(fileFor(key), gson.toJson(cached.copy(userOffsetSec = offsetSec)))
        } catch (e: Exception) {
            // 容错
        }
    }

    /** 原子写:先写 .tmp 再 rename(与 Hub 服务端策略一致,避免半写文件) */
    private fun atomicWrite(file: Path, content: String) {
        val tmp = dir.resolve("${file.fileName}.tmp")
        Files.writeString(tmp, content)
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
