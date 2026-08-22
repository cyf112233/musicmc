package io.github.cyf112233.musicmc.util

import io.github.cyf112233.musicmc.model.LyricLine

/**
 * LRC 歌词解析。
 */
object Lrc {

    // 匹配 [mm:ss.xx] / [mm:ss.xxx] / [h:mm:ss.xx] 形式的时间戳
    // (分钟可 1-2 位;小时段可选,避免 [1:02:03] 被误解析成 1 分 02.03 秒)
    private val TIME_PATTERN = Regex(
        """\[(\d{1,2}):(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]|\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""",
    )

    /**
     * 解析 LRC 文本:
     * - 支持 [mm:ss.xx] / [mm:ss.xxx] 两种精度,以及 [h:mm:ss.xx] 小时制;
     * - 同一行多个时间戳(如 "[00:01.00][00:03.50]歌词")会展开为多行;
     * - 秒 / 分 ≥ 60 视为非法时间戳整条丢弃(防 [00:75.00] 被当 75 秒);
     * - 注意:[offset:+500] 等元数据标签不解析(全局偏移由 userOffsetSec 体系承担,
     *   此处有意忽略);带负时间戳(如 [-00:05.00])的行因不匹配正则而被跳过,
     *   与"无时间戳行不产生歌词"的既有语义一致;
     * - 结果按时间升序排序。
     */
    fun parseLrc(raw: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        for (line in raw.lines()) {
            val matches = TIME_PATTERN.findAll(line).toList()
            if (matches.isEmpty()) continue
            val text = line.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val g = m.groupValues
                // 两分支:g[1..4] 为 [h:mm:ss(.xxx)],g[5..7] 为 [mm:ss(.xxx)];
                // 未参与匹配的分支其 group 为空串,toIntOrNull 返回 null
                val hour = g[1].toIntOrNull()
                val min = (if (hour != null) g[2] else g[5]).toIntOrNull() ?: 0
                val sec = (if (hour != null) g[3] else g[6]).toIntOrNull() ?: 0
                val fracRaw = if (hour != null) g[4] else g[7]
                // 秒 / 分越界(≥60):格式非法,丢弃该时间戳(防御 [00:99.00] 类脏数据)
                if (sec >= 60 || min >= 60) continue
                val ms = when (fracRaw.length) {
                    1 -> (fracRaw.toIntOrNull() ?: 0) * 100   // .x  → 100ms
                    2 -> (fracRaw.toIntOrNull() ?: 0) * 10    // .xx → 10ms
                    3 -> fracRaw.toIntOrNull() ?: 0           // .xxx → ms
                    else -> 0
                }
                result += LyricLine(
                    (hour ?: 0) * 3_600_000 + min * 60_000 + sec * 1_000 + ms,
                    text,
                )
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
