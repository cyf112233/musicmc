package io.github.cyf112233.musicmc.model

/**
 * 一首歌。哔哩哔哩音源下 id 即视频 bvid,title/artist 取自搜索或排行榜接口实测字段。
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val picUrl: String?,
    val durationMs: Int,
)
