package io.github.cyf112233.musicmc.model

/**
 * 播放地址结果。
 *
 * [referer] 与 [backupUrls] 为哔哩哔哩音源使用:
 * - [referer]:打开音频流时所需的 Referer(B 站 CDN 要求 https://www.bilibili.com/);
 * - [backupUrls]:主 URL 打开失败时逐个重试的备用直链(B 站 playurl 的 backupUrl 列表)。
 */
data class SongUrl(
    val url: String,
    val referer: String? = null,
    val backupUrls: List<String> = emptyList(),
)
