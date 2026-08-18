package io.github.cyf112233.musicmc.lyrics

/**
 * 一首来自第三方歌词库(网易云 / QQ音乐 / 酷狗)的候选歌曲。
 *
 * 三源手动搜索与自动匹配的通用载体:
 * - [source] 为 LyricProviders 的名称常量(netease / qq / kugou);
 * - [remoteId] 为取词所需标识(网易云 song id / QQ songmid / 酷狗 hash);
 * - [durationMs] 用于自动匹配时的 duration±3s 过滤(未知为 0)。
 */
data class LyricCandidate(
    val source: String,
    val remoteId: String,
    val title: String,
    val artist: String,
    val durationMs: Int,
)
