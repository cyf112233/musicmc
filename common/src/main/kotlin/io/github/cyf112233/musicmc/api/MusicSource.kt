package io.github.cyf112233.musicmc.api

import io.github.cyf112233.musicmc.model.LyricLine
import io.github.cyf112233.musicmc.model.Playlist
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.model.SongUrl

/**
 * 音乐源抽象(当前唯一实现:哔哩哔哩,匿名可用)。
 *
 * 所有回调都在后台线程执行(不会阻塞 UI 线程),调用方如需更新 UI,
 * 请自行通过 [io.github.cyf112233.musicmc.util.Async.onUi] 切回 UI 线程。
 *
 * 回调约定:err 为 null 表示成功;失败时 err 为中文错误信息,结果为空。
 */
interface MusicSource {
    val id: String
    val displayName: String

    /** 搜索歌曲 */
    fun search(keyword: String, limit: Int, offset: Int, callback: (List<Song>, String?) -> Unit)

    /** 获取歌曲播放地址(失败时返回 null 与错误信息) */
    fun songUrl(song: Song, bitrate: Int, callback: (SongUrl?, String?) -> Unit)

    /** 获取歌词(无歌词时返回空列表,err 说明原因) */
    fun lyric(songId: String, callback: (List<LyricLine>, String?) -> Unit)

    /** 歌单详情(含歌曲列表) */
    fun playlistDetail(playlistId: String, callback: (Playlist, String?) -> Unit)

    /** 首页推荐歌单(排行榜) */
    fun homePlaylists(callback: (List<Playlist>, String?) -> Unit)
}
