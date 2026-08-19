// native/audio/aaudio_player.c
// Android AAudio 音频输出(API 26+ = Android 8.0+,独立于 OpenSL/OpenAL,无 Engine/设备
// 共享冲突 —— MC SoundEngine 的 OpenAL(OpenSL 后端)与 OpenSL ES 直调冲突(SIGSEGV)、
// 自建 OpenAL 设备会破坏 MC SoundEngine,AAudio 是唯一确定稳定的原生通道)。
//
// Java 侧 io.github.cyf112233.musicmc.player.audio.AAudioPlayer 通过 JNI 调用:
//   nativeInit(sampleRate, channels)   打开 AAudioStream 并开始
//   nativeWrite(data, off, len)        阻塞写入 PCM(s16le 交织;内部缓冲,语义同 SourceDataLine)
//   nativeStop()                       停止(幂等,可任意线程)
//   nativeRelease()                    关闭流(幂等)
//   nativeSetVolume(v)                 音量 0..1
#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <stdint.h>

#define LOG_TAG "MusicMCAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static AAudioStream *g_stream = NULL;
static float g_volume = 1.0f;

JNIEXPORT jint JNICALL
Java_io_github_cyf112233_musicmc_player_audio_AAudioPlayer_nativeInit(
    JNIEnv *env, jclass clazz, jint sampleRate, jint channels) {
    if (g_stream) return -1;
    if (sampleRate <= 0 || channels <= 0) return -1;

    AAudioStreamBuilder *builder = NULL;
    aaudio_result_t res = AAudio_createStreamBuilder(&builder);
    if (res != AAUDIO_OK) { LOGE("AAudio_createStreamBuilder failed %d", res); return -1; }
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    // 用阻塞写模式;NONE 性能模式(兼容性与稳定性优先,低配设备不追极低延迟)
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);

    res = AAudioStreamBuilder_openStream(builder, &g_stream);
    AAudioStreamBuilder_delete(builder);
    if (res != AAUDIO_OK) {
        LOGE("AAudioStreamBuilder_openStream failed %d", res);
        g_stream = NULL;
        return -1;
    }
    res = AAudioStream_requestStart(g_stream);
    if (res != AAUDIO_OK) {
        LOGE("AAudioStream_requestStart failed %d", res);
        AAudioStream_close(g_stream);
        g_stream = NULL;
        return -1;
    }
    LOGI("AAudio 初始化完成 rate=%d ch=%d", sampleRate, channels);
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_cyf112233_musicmc_player_audio_AAudioPlayer_nativeWrite(
    JNIEnv *env, jclass clazz, jbyteArray data, jint off, jint len) {
    if (!g_stream || len <= 0) return -1;
    jbyte *src = (*env)->GetByteArrayElements(env, data, NULL);
    if (!src) return -1;
    int32_t ch = AAudioStream_getChannelCount(g_stream);
    if (ch <= 0) { (*env)->ReleaseByteArrayElements(env, data, src, JNI_ABORT); return -1; }
    // 音量:AAudio 无 setVolume API,写入前对 s16 样本缩放
    // (GetByteArrayElements 指针可直接改,后续 JNI_ABORT 不写回 Java 数组,安全)
    if (g_volume < 1.0f) {
        int16_t *pcm = (int16_t *)(src + off);
        int32_t count = len / 2;
        for (int32_t i = 0; i < count; i++) {
            pcm[i] = (int16_t)(pcm[i] * g_volume);
        }
    }
    int64_t frames = (int64_t)len / (ch * 2);
    int64_t written = AAudioStream_write(g_stream, src + off, frames, (int64_t)INT64_MAX);
    (*env)->ReleaseByteArrayElements(env, data, src, JNI_ABORT);
    if (written < 0) {
        LOGE("AAudioStream_write failed %lld", (long long)written);
        return -1;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_cyf112233_musicmc_player_audio_AAudioPlayer_nativeStop(JNIEnv *env, jclass clazz) {
    if (g_stream) AAudioStream_requestStop(g_stream);
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_cyf112233_musicmc_player_audio_AAudioPlayer_nativeRelease(JNIEnv *env, jclass clazz) {
    if (g_stream) {
        AAudioStream_requestStop(g_stream);
        AAudioStream_close(g_stream);
        g_stream = NULL;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_cyf112233_musicmc_player_audio_AAudioPlayer_nativeSetVolume(
    JNIEnv *env, jclass clazz, jfloat v) {
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    g_volume = v;
    return 0;
}
