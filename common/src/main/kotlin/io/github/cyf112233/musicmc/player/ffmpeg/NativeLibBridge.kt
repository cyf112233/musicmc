package io.github.cyf112233.musicmc.player.ffmpeg

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * windows-arm64 原生库手动桥接。
 *
 * 背景:bytedeco 1.5.12 官方平台矩阵**没有 windows-arm64** —— javacpp Loader 的
 * "org/bytedeco/ffmpeg/windows-arm64/" 资源路径无对应加载分支(platform 无映射)。
 * 本 mod 的 windows-arm64 原生产物虽是真实 ARM64 PE 且按 Loader 资源布局打包,
 * 但 Loader 无法自动解包/加载(Loader.java 对未映射平台会抛或短路)。
 *
 * 机制(仅 windows-arm64 生效,其他平台完全不干预,仍走 javacpp Loader 自动加载):
 *   1. **最先**置 `org.bytedeco.javacpp.loadlibraries=false`,禁用 Loader 的自动加载
 *      (必须在任何 bytedeco 类的静态初始化前 —— 否则其静态块里已触发 Loader 尝试);
 *   2. 从类路径(classloader)按 javacpp 资源布局 `org/bytedeco/<lib>/windows-arm64/`
 *      用 getResourceAsStream 提取全部 dll 到 `configDir/musicmc-native/windows-arm64/`
 *      (写 .stamp 标记 "v1",避免重复解包);
 *   3. 按**依赖序**逐个 `System.load(绝对路径)` 手动加载:
 *        libc++/libunwind(LLVM 运行库)→ libav*.dll(FFmpeg 本体)→ libjnijavacpp.dll
 *        (javacpp 核心,提供 JNI 蹦床)→ libjniav*.dll(绑定库,依赖前两者);
 *   4. 全部成功 → [manualLoaded]=true,[FfmpegDecoder.nativeAvailable] 据此放行;
 *      任一失败(解包缺资源 / UnsatisfiedLinkError 等)→ 记日志且保持 false,
 *      上层按"平台不支持播放"处理。
 *
 * 日志经 NetMusic.logger(NetMusic.init 时 platform 已注入,try/catch 兜底防时序问题)。
 */
object NativeLibBridge {

    /** 解包标记:内容变更时递增,触发重新解包 */
    private const val STAMP = "v1"

    /**
     * 资源清单(**顺序即 System.load 依赖序**):
     * 1-2 libc++/libunwind(LLVM libc++ 运行库,libav* 依赖)→
     * 3-6 avutil/swresample/avcodec/avformat(FFmpeg 本体,依赖 libc++/libunwind)→
     * 7 libjnijavacpp(javacpp 核心,JNI 蹦床;排在 avformat 之后、jni 绑定库之前,
     *    因为 libjni* 绑定 JNI 调用依赖它)→
     * 8-11 libjniavutil/libjniavcodec/libjniswresample/libjniavformat
     *    (bytedeco 绑定库,动态依赖 libav* 与 libjnijavacpp)。
     */
    private val RESOURCES: List<String> = listOf(
        "org/bytedeco/ffmpeg/windows-arm64/libc++.dll",
        "org/bytedeco/ffmpeg/windows-arm64/libunwind.dll",
        "org/bytedeco/ffmpeg/windows-arm64/avutil-59.dll",
        "org/bytedeco/ffmpeg/windows-arm64/swresample-5.dll",
        "org/bytedeco/ffmpeg/windows-arm64/avcodec-61.dll",
        "org/bytedeco/ffmpeg/windows-arm64/avformat-61.dll",
        "org/bytedeco/javacpp/windows-arm64/libjnijavacpp.dll",
        "org/bytedeco/ffmpeg/windows-arm64/libjniavutil.dll",
        "org/bytedeco/ffmpeg/windows-arm64/libjniavcodec.dll",
        "org/bytedeco/ffmpeg/windows-arm64/libjniswresample.dll",
        "org/bytedeco/ffmpeg/windows-arm64/libjniavformat.dll",
    )

    @Volatile
    private var preloaded = false

    /**
     * windows-arm64 手动加载是否成功。
     * 其他平台恒 false(那些平台走 javacpp Loader 自动加载路径,与此无关)。
     */
    @Volatile
    var manualLoaded: Boolean = false
        private set

    /**
     * 当前 JVM 是否运行在 windows-arm64(Windows 系统 + AArch64 架构)。
     * os.arch 取小写:JDK 上常为 "aarch64",个别 JVM 报 "arm64",两者都认。
     */
    fun isWindowsArm64(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("windows")) return false
        val arch = System.getProperty("os.arch").lowercase()
        return arch == "aarch64" || arch == "arm64"
    }

    /**
     * windows-arm64 原生库预加载(幂等;NetMusic.init 中 PlatformHolder.set 之后调用一次)。
     *
     * 非 windows-arm64 直接返回(不干预 Loader 正常路径);windows-arm64 执行
     * "禁自动加载 → 解包 → 按依赖序 System.load" 全流程,结果落 [manualLoaded]。
     */
    fun preloadIfNeeded(baseDir: Path) {
        if (preloaded) return
        synchronized(this) {
            if (preloaded) return
            preloaded = true
            if (!isWindowsArm64()) return

            try {
                // 必须最先执行:禁用 javacpp Loader 自动加载。Loader.java 在此属性为 true
                // 时短路,不再尝试从 classpath 找平台映射资源 —— 否则它会在未映射平台
                // 上抛异常(或加载失败),且此属性必须在任何 bytedeco 类静态初始化之前设置。
                System.setProperty("org.bytedeco.javacpp.loadlibraries", "false")

                val extractDir = baseDir.resolve("musicmc-native").resolve("windows-arm64")
                Files.createDirectories(extractDir)

                // 已解包过(.stamp 命中)则跳过拷贝;未命中/缺 stamp 重新全量解包
                val stampFile = extractDir.resolve(".stamp")
                val extracted = Files.exists(stampFile) && runCatching { Files.readString(stampFile) == STAMP }.getOrDefault(false)
                if (!extracted) {
                    for (res in RESOURCES) {
                        val out = extractDir.resolve(res.substringAfterLast('/'))
                        val input = openResource(res) ?: run {
                            warn("缺少 windows-arm64 原生库资源: $res")
                            return
                        }
                        input.use { Files.copy(it, out, StandardCopyOption.REPLACE_EXISTING) }
                    }
                    Files.writeString(stampFile, STAMP)
                }

                // 按依赖序手动加载(重复 load 已加载库为无害空操作,幂等)
                for (res in RESOURCES) {
                    System.load(extractDir.resolve(res.substringAfterLast('/')).toString())
                }
                manualLoaded = true
                info("windows-arm64 原生库手动加载成功(${RESOURCES.size} 个 dll)")
            } catch (t: Throwable) {
                // UnsatisfiedLinkError / IOException / 权限等:一律视为不可用,
                // 上层(FfmpegDecoder.nativeAvailable)报"平台不支持播放"
                warn("windows-arm64 原生库手动加载失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /** 从类路径读取资源:优先本类加载器(承载 include/jarJar 嵌套 jar 的 mod 类加载器),回退线程上下文加载器 */
    private fun openResource(path: String): InputStream? {
        val cls = NativeLibBridge::class.java.classLoader
        cls?.let { runCatching { it.getResourceAsStream(path) }.getOrNull()?.let { return it } }
        val ctx = Thread.currentThread().contextClassLoader
        if (ctx != null && ctx !== cls) {
            runCatching { ctx.getResourceAsStream(path) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** 防时序:platform 尚未注入或注入失败时静默(NetMusic.init 中通常在注入后调用,此处兜底) */
    private fun info(msg: String) {
        try {
            io.github.cyf112233.musicmc.NetMusic.logger.info(msg)
        } catch (_: Throwable) {
        }
    }

    private fun warn(msg: String) {
        try {
            io.github.cyf112233.musicmc.NetMusic.logger.warn(msg)
        } catch (_: Throwable) {
        }
    }
}