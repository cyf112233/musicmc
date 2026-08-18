package io.github.cyf112233.musicmc.player

/**
 * 播放器状态。
 */
enum class PlayerState {
    /** 空闲(未加载任何歌曲) */
    IDLE,

    /** 正在获取播放地址 / 加载音频 */
    LOADING,

    /** 播放中 */
    PLAYING,

    /** 暂停 */
    PAUSED,

    /** 出错(无法获取地址 / 解码失败等) */
    ERROR,
}
