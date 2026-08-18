package io.github.cyf112233.musicmc.player

/** FFmpeg 解码输出:一帧 s16 交错 PCM(小端)+ 格式信息(采样率/声道原样) */
data class DecodedAudio(
    val samples: ByteArray,
    val rate: Int,
    val channels: Int,
    /** 帧时间戳(毫秒);无效时按已解码样本数估算 */
    val ptsMs: Long,
)