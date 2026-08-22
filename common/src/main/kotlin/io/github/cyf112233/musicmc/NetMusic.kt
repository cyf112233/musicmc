package io.github.cyf112233.musicmc

import io.github.cyf112233.musicmc.api.MusicSource
import io.github.cyf112233.musicmc.bilibili.BiliHttp
import io.github.cyf112233.musicmc.bilibili.BilibiliSource
import io.github.cyf112233.musicmc.config.ModConfig
import io.github.cyf112233.musicmc.lyrics.LyricManager
import io.github.cyf112233.musicmc.model.LyricLine
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.platform.ModPlatform
import io.github.cyf112233.musicmc.platform.PlatformHolder
import io.github.cyf112233.musicmc.player.MusicPlayer
import io.github.cyf112233.musicmc.player.ffmpeg.NativeLibBridge
import io.github.cyf112233.musicmc.client.UiText

/**
 * 模块入口。loader(fabric/neoforge)在客户端初始化时调用 [init] 注入平台实现。
 *
 * 纯哔哩哔哩音源:无音源切换,source 为唯一实例;登录态(扫码)可选。
 */
object NetMusic {
    /** @Volatile:ModernUI 模式下 MUI UI 线程写(updateConfig)、MC 渲染线程
     *  (MusicHudRenderer.onFrame 每帧)读 —— 无 volatile 时渲染线程可能长期看到
     *  旧配置(HUD 不刷新 / 开关失效),是跨线程数据竞争 */
    @Volatile
    lateinit var config: ModConfig
        private set

    /** 唯一音源实例(哔哩哔哩,匿名可用;登录后附带 SESSDATA) */
    val source: MusicSource = BilibiliSource()

    lateinit var player: MusicPlayer
        private set

    /**
     * 初始化(幂等):PlatformHolder.set → config 加载 → 登录 cookie 注入 → 播放器创建。
     */
    @Synchronized
    fun init(platform: ModPlatform) {
        if (::config.isInitialized) return
        PlatformHolder.set(platform)
        // 先加载配置:原生库缓存目录覆盖(nativeCacheDir)与平台覆盖(nativePlatformOverride)
        // 都来自配置,必须在 NativeLibBridge.preloadIfNeeded(任何 bytedeco 类静态初始化)
        // 之前读到 —— config 只是 Gson 读 JSON,不触碰 bytedeco 类,顺序安全。
        config = ModConfig.load(platform.configDirectory())
        // FFmpeg 原生平台强制覆盖(Pojav 等场景;javacpp Loader 平台为 static final,
        // 必须在任何 FFmpeg/Javacpp 加载前设置,此处是播放器创建前的唯一时机)
        if (!config.nativePlatformOverride.isNullOrBlank()) {
            System.setProperty("org.bytedeco.javacpp.platform", config.nativePlatformOverride.trim())
        } else {
            // Android(FCL 等标准 OpenJDK 容器):javacpp 的 isAndroid() 依赖 ART 特征检测不到,
            // platform 会误判为 linux-arm64 → Loader 找不到 android-arm64 资源。这里按系统
            // 特征显式钉死(与 NativeLibBridge 手动加载的平台一致,兜底直接走 Loader 的调用)。
            NativeLibBridge.androidPlatform()?.let {
                System.setProperty("org.bytedeco.javacpp.platform", it)
            }
        }
        // windows-arm64:javacpp Loader 无平台映射(loader/windows-arm64 分支缺失),无法自动
        // 解包/加载。这里手动解包 + System.load 桥接(内部最先置 loadlibraries=false 禁自动加载);
        // Android:这里把 javacpp cachedir 指向 app 私有可执行区并钉死 platform,再触发
        // Loader.load(avutil) 自动加载(手动 System.load 有 classloader 隔离问题,见
        // NativeLibBridge 类注释);其余平台此调用是纯空操作,不影响 Loader 正常路径。
        NativeLibBridge.preloadIfNeeded(platform.configDirectory(), config.nativeCacheDir)
        // 恢复持久化的 B 站登录态(空串即未登录)
        BiliHttp.setCookie(config.biliCookie)
        player = MusicPlayer(source, config)
        logger.info("MusicMC common 初始化完成(音源=哔哩哔哩)")
    }

    // ---------------- B 站登录 ----------------

    /** 保存 B 站登录 cookie:注入 BiliHttp + 持久化到配置(退出登录传空串) */
    fun setBilibiliCookie(cookie: String) {
        BiliHttp.setCookie(cookie)
        updateConfig { it.copy(biliCookie = cookie) }
    }

    /** 是否已登录(cookie 含 SESSDATA 即视为已登录) */
    fun bilibiliLoggedIn(): Boolean = config.biliCookie.contains("SESSDATA=")

    /** B 站昵称(navProfile 有 5 分钟缓存;网络失败 / 未登录返回 null)。应在后台线程调用 */
    fun bilibiliNickname(): String? = try {
        val profile = BiliHttp.navProfile()
        if (profile.isLogin) profile.uname else null
    } catch (e: Exception) {
        null
    }

    // ---------------- 歌词 ----------------

    /**
     * 获取歌词(统一入口,供歌词页使用):
     * - 总开关关闭 → (emptyList, "歌词功能已禁用");
     * - 开启 → LyricManager.load(本地缓存 → Hub → CC 字幕 → 标题自动匹配三源)。
     * CC 优先等语义在 LyricManager 内;回调在后台线程执行,调用方自行切 UI 线程。
     */
    fun getLyrics(song: Song, callback: (List<LyricLine>, String?) -> Unit) {
        if (!config.lyricsEnabled) {
            callback(emptyList(), UiText.t("歌词功能已禁用", "Lyrics are disabled"))
            return
        }
        LyricManager.load(song) { result, err ->
            callback(result.lines, err)
        }
    }

    // ---------------- 配置 ----------------

    /**
     * 更新配置(设置页开关调用):transform → 落盘 → 同步播放器持有的配置引用。
     */
    fun updateConfig(transform: (ModConfig) -> ModConfig) {
        config = transform(config)
        saveConfig()
        player.updateConfig(config)
    }

    /**
     * 打开音乐界面。按 uiMode 分流:
     * MODERN_UI → ModernUI 界面;VANILLA → 原版 MC 回退界面
     * (Android/FCL 上 ModernUI 文字渲染依赖 Java2D 不可用,回退界面走位图字体)。
     */
    fun openScreen() {
        // UI 后端统一由 openMusicScreen 按 UiBackendResolver 分派:
        // ModernUI(装了且非 Android)/ YACL(Android 或未装 ModernUI)/ 原版。
        // uiMode 显式 VANILLA 时 Resolver 也返回原版,行为与旧分流一致;
        // 旧实现在此处按 uiMode 分流会绕过 YACL 分派(配置残留 VANILLA 时永远走原版)。
        PlatformHolder.require().openMusicScreen()
    }

    /** 打开配置界面(Cloth Config;uiMode 等设置入口) */
    fun openConfigScreen() {
        PlatformHolder.require().openConfigScreen()
    }

    val logger get() = PlatformHolder.require().logger()

    /** 把当前配置(音量/码率/播放模式/登录 cookie/hubUrl 等)落盘;失败静默 */
    fun saveConfig() {
        runCatching { config.save(PlatformHolder.require().configDirectory()) }
    }
}
