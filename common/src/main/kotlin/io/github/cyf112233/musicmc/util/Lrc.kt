package io.github.cyf112233.musicmc.util

import io.github.cyf112233.musicmc.model.LyricLine

/**
 * LRC 歌词解析。
 */
object Lrc {

    // 匹配 [mm:ss.xx] / [mm:ss.xxx] 形式的时间戳
    private val TIME_PATTERN = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /**
     * 解析 LRC 文本:
     * - 支持 [mm:ss.xx] 与 [mm:ss.xxx] 两种精度
     * - 同一行多个时间戳(如 "[00:01.00][00:03.50]歌词")会展开为多行
     * - 结果按时间升序排序
     */
    fun parseLrc(raw: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        for (line in raw.lines()) {
            val matches = TIME_PATTERN.findAll(line).toList()
            if (matches.isEmpty()) continue
            val text = line.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val min = m.groupValues[1].toIntOrNull() ?: 0
                val sec = m.groupValues[2].toIntOrNull() ?: 0
                val fracRaw = m.groupValues[3]
                val ms = when (fracRaw.length) {
                    1 -> (fracRaw.toIntOrNull() ?: 0) * 100   // .x  → 100ms
                    2 -> (fracRaw.toIntOrNull() ?: 0) * 10    // .xx → 10ms
                    3 -> fracRaw.toIntOrNull() ?: 0           // .xxx → ms
                    else -> 0
                }
                result += LyricLine(min * 60_000 + sec * 1_000 + ms, text)
            }
        }
        result.sortBy { it.timeMs }
        return result
    }

    /**
     * 把歌词行序列化为 LRC 文本([mm:ss.xx] 精度,与 [parseLrc] 双向可逆)。
     * 用于歌词本地缓存 / Hub 同步时以原始 LRC 文本保存,保证跨版本可读。
     */
    fun toLrc(lines: List<LyricLine>): String = buildString {
        for (line in lines) {
            val ms = line.timeMs.coerceAtLeast(0)
            append("[%02d:%02d.%02d]".format(ms / 60_000, (ms % 60_000) / 1_000, (ms % 1_000) / 10))
            append(line.text)
            append('\n')
        }
    }

    /**
     * 二分查找当前应显示的行:最后一个 timeMs <= 目标时间的行下标;
     * 还没有到第一行时返回 -1。
     */
    fun findLineIndex(lines: List<LyricLine>, timeMs: Int): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= timeMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
