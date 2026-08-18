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

/**
 * 模块入口。loader(fabric/neoforge)在客户端初始化时调用 [init] 注入平台实现。
 *
 * 纯哔哩哔哩音源:无音源切换,source 为唯一实例;登录态(扫码)可选。
 */
object NetMusic {
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
        // windows-arm64:javacpp Loader 无平台映射(loader/windows-arm64 分支缺失),无法自动
        // 解包/加载。这里(PlatformHolder 注入后、config 加载前 —— 任何 bytedeco 类静态初始化
        // 之前的唯一时机)手动解包 + System.load 桥接(内部最先置 loadlibraries=false 禁自动加载);
        // 非 windows-arm64 平台此调用是纯空操作,不影响 Loader 正常路径。
        NativeLibBridge.preloadIfNeeded(platform.configDirectory())
        config = ModConfig.load(platform.configDirectory())
        // FFmpeg 原生平台强制覆盖(Pojav 等场景;javacpp Loader 平台为 static final,
        // 必须在任何 FFmpeg/Javacpp 加载前设置,此处是播放器创建前的唯一时机)
        if (!config.nativePlatformOverride.isNullOrBlank()) {
            System.setProperty("org.bytedeco.javacpp.platform", config.nativePlatformOverride.trim())
        }
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
            callback(emptyList(), "歌词功能已禁用")
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

    fun openScreen() = PlatformHolder.require().openMusicScreen()

    val logger get() = PlatformHolder.require().logger()

    /** 把当前配置(音量/码率/播放模式/登录 cookie/hubUrl 等)落盘;失败静默 */
    fun saveConfig() {
        runCatching { config.save(PlatformHolder.require().configDirectory()) }
    }
}
