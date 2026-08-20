package io.github.cyf112233.musicmc.player.ffmpeg

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 特殊平台原生库桥接:windows-arm64 手动加载 + Android 走 javacpp Loader。
 *
 * windows-arm64:bytedeco 1.5.12 官方平台矩阵**没有 windows-arm64**,Loader 无平台映射,
 * 无法自动加载。机制:
 *   1. **最先**置 `org.bytedeco.javacpp.loadlibraries=false`,禁用 Loader 的自动加载
 *      (必须在任何 bytedeco 类的静态初始化前 —— 否则其静态块里已触发 Loader 尝试);
 *   2. 从类路径(classloader)按 javacpp 资源布局 `org/bytedeco/<lib>/windows-arm64/`
 *      用 getResourceAsStream 提取全部 dll 到 `configDir/musicmc-native/windows-arm64/`
 *      (写 .stamp 标记,避免重复解包);
 *   3. 按**依赖序**逐个 `System.load(绝对路径)` 手动加载:
 *        libc++/libunwind(LLVM 运行库)→ libav*.dll(FFmpeg 本体)→ libjnijavacpp.dll
 *        (javacpp 核心,提供 JNI 蹦床)→ libjniav*.dll(绑定库,依赖前两者);
 *   4. 全部成功 → [manualLoaded]=true,[FfmpegDecoder.nativeAvailable] 据此放行;
 *      任一失败(解包缺资源 / UnsatisfiedLinkError 等)→ 记日志且保持 false。
 *
 * Android:不再手动加载。手动 System.load 的库注册在**调用者类的 classloader**
 * (musicmc 的 mod 类加载器),而 avutil 等绑定类由 FML Plugins 类加载器加载,
 * JVM 的 native 符号查找按 classloader 隔离 → 手动加载即使 8 个库全成功,
 * avutil.<clinit> 仍报 UnsatisfiedLinkError。解法:把 javacpp Loader 的解包缓存目录
 * (org.bytedeco.javacpp.cachedir)指向 app 私有可执行区,并主动触发 Loader.load(avutil),
 * 由 Loader 自身完成"找资源(musicmc-native-<platform>.jar)→ 解包 → System.load",
 * 库注册到 Loader 所在的 FML Plugins 类加载器,与 avutil 同加载器,native 符号可解析。
 *
 * 日志经 NetMusic.logger(NetMusic.init 时 platform 已注入,try/catch 兜底防时序问题)。
 */
object NativeLibBridge {

    /** 解包标记:内容/目录变更时递增,触发重新解包(仅 windows-arm64 手动桥接使用) */
    private const val STAMP = "v3"

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
     * 当前 JVM 是否运行在 Android 系统。
     * 判定依据:ANDROID_ROOT 环境变量(Android 系统层必有,值为 /system)+ os.name 为 Linux。
     * 不能用 javacpp 的 isAndroid() —— 它依赖 ART/Dalvik 特征,FCL 等容器用的是标准 OpenJDK,
     * 检测不到 → platform 误判为 linux-arm64 → Loader 找 android-arm64 资源失败。
     */
    fun isAndroid(): Boolean {
        if (System.getenv("ANDROID_ROOT").isNullOrEmpty()) return false
        val os = System.getProperty("os.name").lowercase()
        return os.contains("linux")
    }

    /**
     * Android 目标平台名(android-arm64 / android-x86_64);非 Android 或架构未知返回 null。
     */
    fun androidPlatform(): String? {
        if (!isAndroid()) return null
        return when (System.getProperty("os.arch").lowercase()) {
            "aarch64", "arm64" -> "android-arm64"
            "x86_64", "amd64" -> "android-x86_64"
            else -> null
        }
    }

    /**
     * 特殊平台原生库预加载(幂等;NetMusic.init 中 PlatformHolder.set 之后调用一次)。
     *
     * 覆盖两类 javacpp Loader 无法自动加载的平台:
     *  - windows-arm64:bytedeco 1.5.12 官方平台矩阵没有它,Loader 无平台映射 →
     *    手动桥接(禁自动加载 → 解包 → 按依赖序 System.load),结果落 [manualLoaded];
     *  - android-*:FCL 等 Android 容器跑标准 OpenJDK,javacpp 的 isAndroid() 检测不到
     *    (依赖 ART 特征),platform 误判为 linux-arm64。**修复:不手动加载**,把 Loader
     *    的解包缓存目录指向 app 私有可执行区并主动触发 Loader.load(avutil),由 Loader
     *    自身加载库 —— 手动 System.load 注册在 mod 类加载器,avutil 由 FML Plugins
     *    类加载器加载,native 符号查找按 classloader 隔离,手动加载必然 UnsatisfiedLinkError。
     *
     * @param baseDir 配置目录兜底(仅当所有 JVM 属性目录都不可用时使用)
     * @param cacheDirOverride 用户显式指定的解包目录(ModConfig.nativeCacheDir;
     *   非空则优先于自动判定;指定目录不可用时会告警并回退自动判定)
     *
     * 其他平台直接返回(不干预 Loader 正常路径)。
     */
    fun preloadIfNeeded(baseDir: Path, cacheDirOverride: String? = null) {
        if (preloaded) return
        synchronized(this) {
            if (preloaded) return
            preloaded = true

            val isWinArm = isWindowsArm64()
            val androidP = androidPlatform()
            if (!isWinArm && androidP == null) return

            if (isWinArm) {
                preloadWindowsArm64(baseDir)
            } else {
                prepareAndroidLoader(androidP!!, baseDir, cacheDirOverride)
            }
        }
    }

    /**
     * 挑选 Android 原生库解包根目录(launcher 无关;不假设 FCL 路径)。
     *
     * 候选顺序:用户显式覆盖(config.nativeCacheDir)→ java.io.tmpdir →
     * user.home → user.dir → 兜底 baseDir。每个候选都要通过 [isExecCapable]
     * 探测(可写 **且** 文件系统支持执行 —— dlopen 需要 exec,共享存储等 noexec
     * 挂载会被 Android linker 拒绝,只有 app 私有可执行区可用)。
     */
    private fun pickExtractRoot(baseDir: Path, cacheDirOverride: String?): Path {
        val candidates = buildList {
            cacheDirOverride?.takeIf { it.isNotBlank() }?.let { add(Path.of(it)) }
            addAll(
                listOfNotNull(
                    System.getProperty("java.io.tmpdir"),
                    System.getProperty("user.home"),
                    System.getProperty("user.dir"),
                ).mapNotNull { c ->
                    runCatching { Path.of(c).toAbsolutePath() }.getOrNull()
                },
            )
        }
        for (c in candidates) {
            if (isExecCapable(c)) return c
        }
        if (cacheDirOverride?.isNotBlank() == true) {
            warn("Specified native lib dir unavailable (missing/not writable/not executable): $cacheDirOverride, falling back to auto detection")
        }
        return baseDir
    }

    /**
     * 目录是否可作原生库解包区:存在 + 可写 + **可执行**。
     * 可执行性用「写一个探针文件并检查 X_OK」验证:Linux 的 access(path, X_OK)
     * 会同时反映挂载的 noexec 标志 —— noexec 挂载(共享存储)上即使文件带
     * 执行位,isExecutable 也返回 false,从而把这类目录排除在候选之外。
     */
    private fun isExecCapable(dir: Path): Boolean {
        return runCatching {
            if (!Files.isDirectory(dir)) return false
            val probe = Files.createTempFile(dir, ".musicmc_exec_probe", ".so")
            try {
                probe.toFile().setExecutable(true, false)
                Files.isExecutable(probe)
            } finally {
                runCatching { Files.deleteIfExists(probe) }
            }
        }.getOrDefault(false)
    }

    /**
     * windows-arm64 手动桥接(该平台 bytedeco 1.5.12 无映射,Loader 无法自动加载):
     * 禁自动加载 → 解包 → 按依赖序 System.load,结果落 [manualLoaded]。
     */
    private fun preloadWindowsArm64(baseDir: Path) {
        val subDir = "windows-arm64"
        try {
            // 必须最先执行:禁用 javacpp Loader 自动加载(在任何 bytedeco 类静态初始化前)
            System.setProperty("org.bytedeco.javacpp.loadlibraries", "false")

            val extractDir = baseDir.resolve("musicmc-native").resolve(subDir)
            Files.createDirectories(extractDir)
            info("$subDir 原生库解包目录: $extractDir")

            // 已解包过(.stamp 命中)则跳过拷贝;未命中/缺 stamp 重新全量解包
            val stampFile = extractDir.resolve(".stamp")
            val extracted = Files.exists(stampFile) && runCatching { Files.readString(stampFile) == STAMP }.getOrDefault(false)
            if (!extracted) {
                for (res in RESOURCES) {
                    val out = extractDir.resolve(res.substringAfterLast('/'))
                    val input = openResource(res) ?: run {
                        warn("Missing native lib resource for $subDir: $res")
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
            info("$subDir 原生库手动加载成功(${RESOURCES.size} 个库)")
        } catch (t: Throwable) {
            // UnsatisfiedLinkError / IOException / 权限等:一律视为不可用,
            // 上层(FfmpegDecoder.nativeAvailable)报"平台不支持播放"
            warn("Manual native lib load failed for $subDir: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Android:不手动加载,让 javacpp Loader 走自动加载路径。
     *
     * 关键认知(第 4 轮修复):手动 System.load 的库注册在**调用者类的 classloader**
     * (musicmc 的 mod 类加载器),而 avutil 等绑定类由 FML Plugins 类加载器加载,
     * JVM 的 native 符号查找按 classloader 隔离 —— 手动加载虽成功(8 个库全过),
     * avutil.<clinit> 仍报 UnsatisfiedLinkError。解法:让 Loader 自己加载
     * (System.load 由 Loader 类发起,注册到 FML Plugins,与 avutil 同加载器)。
     *
     * 前提两件事,本方法负责其一,其二由构建保证:
     *  - 解包缓存目录指向 app 私有可执行区(共享存储 noexec,dlopen 被拒);
     *  - 库资源按 javacpp 布局存在于 musicmc-native-<platform>.jar
     *    (PC 实证 Loader 会从该 jar 找到、解包并加载;preload 列表不含
     *    libjnijavacpp.so —— 1.5.12 的 libjni*.so 已静态包含 javacpp 运行时,
     *    且 -static-libstdc++ 静态链接 libc++,不触碰 FCL JVM 已占用的 libc++_shared.so)。
     */
    private fun prepareAndroidLoader(platform: String, baseDir: Path, cacheDirOverride: String? = null) {
        try {
            // 解包根目录必须是 app 私有可执行区(launcher 无关,不假设 FCL)。
            // Android linker 对共享存储执行 noexec —— 从那里 dlopen 一律报
            // "is not accessible for the namespace clns-6"。候选根目录按
            // 用户覆盖 → java.io.tmpdir → user.home → user.dir 依次探测
            // (要求可写且可执行,见 [pickExtractRoot] / [isExecCapable]),
            // 全部不可用才回退 baseDir(配置目录,通常也在共享存储,此时仅告警)。
            val extractRoot = pickExtractRoot(baseDir, cacheDirOverride)

            // javacpp Loader 的解包缓存目录(Loader.getCacheDir 静态缓存,必须在本进程
            // 首次触碰 bytedeco 类之前设置):Loader 默认候选 user.home/.javacpp/cache,
            // Android 上 user.home 可能落在共享存储(noexec),Java 层能读但 dlopen 被拒。
            val cacheDir = extractRoot.resolve("musicmc-native").resolve("javacpp-cache")
            Files.createDirectories(cacheDir)
            System.setProperty("org.bytedeco.javacpp.cachedir", cacheDir.toString())
            // platform 属性同样必须在首次触碰 bytedeco 类之前钉死(javacpp Loader 静态缓存)。
            // NetMusic.init 已先按 nativePlatformOverride(若配置)设置过该属性 —— 这里
            // 只在属性仍为空时补钉系统自动判定的 android 平台,避免覆盖用户的显式覆盖。
            if (System.getProperty("org.bytedeco.javacpp.platform").isNullOrBlank()) {
                System.setProperty("org.bytedeco.javacpp.platform", platform)
            }
            info("$platform javacpp cachedir: $cacheDir")

            // AAudio 音频输出库(独立资源路径 musicmc/audio/<platform>/,不混入 javacpp
            // 布局)。System.load 注册到本类(musicmc mod)加载器,与 AAudioPlayer 同加载器,
            // JNI 方法可解析 —— mod 内部库,无跨加载器问题。
            val audioRes = "musicmc/audio/$platform/libmusicmc_audio.so"
            val audioFile = cacheDir.resolve("libmusicmc_audio.so")
            // 总是覆盖解包(历史坑:旧 OpenSL 版同名校验文件曾留在缓存,exists 检查会跳过
            // 更新导致加载旧库 → AAudioPlayer.nativeInit UnsatisfiedLinkError)。
            // 2026-08 再修复:若资源缺失(如旧版 CI 产物漏打 musicmc/audio/),必须删除
            // 缓存残留的旧 .so —— 否则会加载**旧 JNI 签名**的库(nativeInit(rate,ch) 无
            // owner 参数),新 Java 调用参数错位 → "AAudio 初始化失败" 且无原生层错误日志。
            var audioLoaded = false
            runCatching {
                val input = openResource(audioRes)
                if (input != null) {
                    input.use { Files.copy(it, audioFile, StandardCopyOption.REPLACE_EXISTING) }
                    audioLoaded = true
                }
            }
            if (!audioLoaded) {
                // 资源缺失:清掉可能残留的旧库,宁可不加载也不加载错签名
                runCatching { Files.deleteIfExists(audioFile) }
                warn("Missing audio output lib for $platform: $audioRes (Android will have no sound)")
            } else if (Files.exists(audioFile)) {
                System.load(audioFile.toString())
                info("$platform 音频输出库已加载(AAudio)")
            } else {
                warn("Missing audio output lib for $platform: $audioRes (Android will have no sound)")
            }

            // 主动触发 avutil <clinit>(→ Loader.load()):让库在 mod 加载阶段就绪,
            // 播放时零延迟。loader 探测:本类 loader 与线程上下文 loader 中任一个能
            // 加载到 avutil(经 parent 链委托到 FML Plugins)即可。失败仅告警——
            // 播放时 FfmpegDecoder.nativeAvailable 会再触发并给出明确结果。
            var done = false
            for (l in listOfNotNull(NativeLibBridge::class.java.classLoader, Thread.currentThread().contextClassLoader)) {
                try {
                    Class.forName("org.bytedeco.ffmpeg.global.avutil", true, l)
                    done = true
                    break
                } catch (_: Throwable) {
                }
            }
            if (done) info("$platform 原生库已由 javacpp Loader 加载")
            else warn("$platform failed to trigger javacpp Loader load (avutil class unreachable), will retry at playback time")
        } catch (t: Throwable) {
            warn("$platform javacpp Loader preparation failed: ${t.javaClass.simpleName}: ${t.message}")
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