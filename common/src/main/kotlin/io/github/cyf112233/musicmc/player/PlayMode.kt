package io.github.cyf112233.musicmc.player

/**
 * 播放模式。
 */
enum class PlayMode {
    /** 顺序播放,队列末尾停止 */
    SEQUENCE,

    /** 列表循环 */
    LOOP_ALL,

    /** 单曲循环 */
    LOOP_ONE,

    /** 随机播放 */
    SHUFFLE,
}
