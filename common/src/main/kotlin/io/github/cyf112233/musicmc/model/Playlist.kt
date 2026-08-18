package io.github.cyf112233.musicmc.model

/**
 * 歌单。songs 为空时表示只加载了歌单信息(如首页推荐)。
 */
data class Playlist(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val songs: List<Song>,
    /** 歌单歌曲总数(user/playlist、personalized 响应带该字段;toplist 等兜底为 0) */
    val trackCount: Int = 0,
)
