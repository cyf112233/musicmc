package io.github.cyf112233.musicmc.player

import io.github.cyf112233.musicmc.model.Song

/**
 * 播放器事件监听。
 *
 * 实现注意:所有回调都已在 UI 线程(由 MusicPlayer 统一 postToUiThread),可以直接更新视图。
 * 方法带空默认实现,按需覆写。
 */
interface PlayerListener {
    /** 状态变化(state + 当前歌曲) */
    fun onStateChanged(state: PlayerState, song: Song?) {}

    /** 播放进度(仅播放中轮询上报) */
    fun onProgress(posMs: Int, durationMs: Int) {}

    /** 切换了歌曲 */
    fun onSongChanged(song: Song?) {}

    /** 用户提示(Toast 等轻提示,已切 UI 线程;播放器内部统一经此上报) */
    fun onToast(msg: String) {}
}
