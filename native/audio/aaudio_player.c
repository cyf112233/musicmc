// native/audio/aaudio_player.c
// Android AAudio 音频输出(API 26+ = Android 8.0+,独立于 OpenSL/OpenAL,无 Engine/设备
// 共享冲突 —— MC SoundEngine 的 OpenAL(OpenSL 后端)与 OpenSL ES 直调冲突(SIGSEGV)、
// 自建 OpenAL 设备会破坏 MC SoundEngine,AAudio 是唯一确定稳定的原生通道)。
//
// Java 侧 io.github.cyf112233.musicmc.player.audio.AAudioPlayer 通过 JNI 调用:
//   nativeInit(owner, sampleRate, channels)  打开 AAudioStream 并开始
//   nativeWrite(data, off, len)              阻塞写入 PCM(s16le 交织;内部缓冲,语义同 SourceDataLine)
//   nativeStop()                             停止(幂等,可任意线程)
//   nativeRelease(owner)                     关闭流(幂等,仅 owner 匹配时生效)
//   nativeSetVolume(v)                       音量 0..1
//
// 线程安全(2026-08 修复连续 seek 崩溃):mod 的 seek 是非阻塞切会话 —— 新会话立即
// start,旧解码线程稍后才在自身 finally 释放。此前 nativeRelease 无锁地
// AAudioStream_close,可能与旧线程仍阻塞的 AAudioStream_write 并发,AAudio 内部
// sp<> 强指针计数错乱 → "decStrong() called too many times" + SIGABRT
// (OpenJDK exit code 6)。
//
// 锁设计(读写锁 pthread_rwlock):
//   * nativeWrite 持**读锁**阻塞写入 —— 可被 nativeStop(同样读锁)并发唤醒
//     (若用互斥锁,write 持锁阻塞、stop 拿不到锁调 requestStop → 死锁);
//   * nativeRelease / nativeInit 持**写锁**关闭/重建 —— 自动等待在写中的
//     nativeWrite 返回后才会 close,close 永不与 write 并发;
//   * owner token:每次 nativeInit 分配新 owner,release 只关闭属于自己的流,
//     旧会话迟到 release 不会误关新会话刚建立的流;nativeInit 若发现残留旧流
//     (上一会话异常未释放),在写锁内安全接管(关旧开新)。
#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <pthread.h>
#include <stdint.h>

#define LOG_TAG "MusicMCAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static AAudioStream *g_stream = NULL;
static float g_volume = 1.0f;
/* 当前流的 owner token:由 Java 侧每次会话生成;release 必须匹配才关闭 */
static int64_t g_owner = 0;
/* 读写锁:write/stop 读锁并发,release/init 写锁独占(见文件头注释) */
static pthread_rwlock_t g_lock = PTHREAD_RWLOCK_INITIALIZER;

JNIEXPORT jint JNICALL
Java_io_github_cyf112233_musicmc_player_audio_AAudioPlayer_nativeInit(
    JNIEnv *env, jclass clazz, jlong owner, jint sampleRate, jint channels) {
    if (sampleRate <= 0 || channels <= 0) return -1;

    pthread_rwlock_wrlock(&g_lock);
    // 残留旧流(上一会话异常未释放):写锁内安全接管 —— 此刻没有任何 write 持读锁,
    // 关旧开新不会与并发 write 冲突。
    if (g_stream) {
        LOGE("nativeInit 发现残留旧流,先关闭再重建(owner=%lld -> %lld)",
             (long long)g_owner, (long long)owner);
        AAudioStream_requestStop(g_stream);
        AAudioStream_close(g_stream);
        g_stream = NULL;
        g_owner = 0;
    }

    AAudioStreamBuilder *builder = NULL;
    aaudio_result_t res = AAudio_createStreamBuilder(&builder);
    if (res != AAUDIO_OK) {
        pthread_rwlock_unlock(&g_lock);
        LOGE("AAudio_createStreamBuilder failed %d", res);
        return -1;
    }
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    // 用阻塞写模式;NONE 性能模式(兼容性与稳定性优先,低配设备不追极低延迟)
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);

    res = AAudioStreamBuilder_openStream(builder, &g_stream);
    AAudioStreamBuilder_delete(builder);
    if (res != AAUDIO_OK) {
        g_stream = NULL;
        pthread_rwlock_unlock(&g_lock);
        LOGE("AAudioStreamBuilder_openStream failed %d", res);
        return -1;
    }
    res = AAudioStream_requestStart(g_stream);
    if (res != AAUDIO_OK) {
        LOGE("AAudioStream_requestStart failed %d", res);
        AAudioStream_close(g_stream);
        g_stream = NULL;
        pthread_rwlock_unlock(&g_lock);
        return -1;
    }
    g_owner = owner;
    pthread_rwlock_unlock(&g_lock);
    LOGI("AAudio 初始化完成 rate=%d ch=%d owner=%lld", sampleRate, channels, (long long)owner);
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_cyf112233_musicmc_player_audio_AAudioPlayer_nativeWrite(
    JNIEnv *env, jclass clazz, jbyteArray data, jint off, jint len) {
    if (len <= 0) return -1;
    jbyte *src = (*env)->GetByteArrayElements(env, data, NULL);
    if (!src) return -1;

    // 读锁:可被 nativeStop(读锁)并发 requestStop 唤醒;release(写锁)会等待本
    // write 返回后才 close —— 这是修复 "decStrong too many times" 崩溃的关键。
    pthread_rwlock_rdlock(&g_lock);
    if (!g_stream) {
        pthread_rwlock_unlock(&g_lock);
        (*env)->ReleaseByteArrayElements(env, data, src, JNI_ABORT);
        return -1;
    }
    int32_t ch = AAudioStream_getChannelCount(g_stream);
    if (ch <= 0) {
        pthread_rwlock_unlock(&g_lock);
        (*env)->ReleaseByteArrayElements(env, data, src, JNI_ABORT);
        return -1;
    }
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
    pthread_rwlock_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, data, src, JNI_ABORT);
    if (written < 0) {
        LOGE("AAudioStream_write failed %lld", (long long)written);
        return -1;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_cyf112233_musicmc_player_audio_AAudioPlayer_nativeStop(JNIEnv *env, jclass clazz) {
    pthread_rwlock_rdlock(&g_lock);
    if (g_stream) AAudioStream_requestStop(g_stream);
    pthread_rwlock_unlock(&g_lock);
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_cyf112233_musicmc_player_audio_AAudioPlayer_nativeRelease(
    JNIEnv *env, jclass clazz, jlong owner) {
    // 写锁:等待在写中的 nativeWrite 返回后,才关闭流 —— close 永不与 write 并发
    pthread_rwlock_wrlock(&g_lock);
    // 仅关闭属于自己的流:旧会话迟到 release 不得误关新会话刚建立的流
    if (g_stream && g_owner == owner) {
        AAudioStream_requestStop(g_stream);
        AAudioStream_close(g_stream);
        g_stream = NULL;
        g_owner = 0;
    }
    pthread_rwlock_unlock(&g_lock);
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
