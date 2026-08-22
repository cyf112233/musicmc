package io.github.cyf112233.musicmc.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * 播放器配置,序列化到 <configDirectory>/musicmc.json。
 */
data class ModConfig(
    val volume: Float = 0.8f,
    val bitrate: Int = 320000,
    val playMode: String = "SEQUENCE",
    /** 播放时禁用 Minecraft 环境音乐(true=播放歌曲期间持续抑制原版环境音乐) */
    val pauseGameMusicOnPlay: Boolean = true,
    /** 歌词显示总开关(false=默认禁用;开启后播放页可查看歌词,CC 字幕优先) */
    val lyricsEnabled: Boolean = false,
    /** HUD 歌词显示开关(true=游戏内 HUD 显示歌词;独立于 lyricsEnabled,关掉不影响播放页歌词) */
    val hudLyricEnabled: Boolean = true,
    /** 聊天栏歌词开关(true=每句歌词同步输出到玩家聊天栏一条;false 默认,避免刷屏) */
    val chatLyricEnabled: Boolean = false,
    /** 无 CC 字幕时按标题匹配歌词(BBPlayer 风格;经网易云公开歌词库匹配,仅作歌词来源,非音源) */
    val lyricTitleFallback: Boolean = false,
    /** B 站登录 cookie(扫码登录后全量持久化;含 SESSDATA 即视为已登录) */
    val biliCookie: String = "",
    /** 歌词 Hub 服务地址(自建,协议见 hub/README.md;为空表示不启用同步) */
    val hubUrl: String = "",
    /** 游戏内 HUD 悬浮音乐面板总开关 */
    val hudEnabled: Boolean = true,
    /** HUD 面板左上角锚点(屏幕归一化坐标 0..1) */
    val hudX: Float = 0.92f,
    val hudY: Float = 0.86f,
    /** HUD 整体缩放(0.5..2.0,设置页 50..200% 映射) */
    val hudScale: Float = 1.0f,
    /**
     * FFmpeg 原生平台强制覆盖(PojavLauncher 等容器内 JVM 检测不到 android 架构的场景):
     * 非空时在 NetMusic.init 里设置 System property "org.bytedeco.javacpp.platform"。
     * javacpp Loader 的平台是 static final(首次加载即固化),必须在任何 FFmpeg 加载前设好;
     * 取值如 "android-arm64" / "android-x86_64" / "linux-x86_64"(见 native/STATUS.md 平台矩阵)。
     */
    val nativePlatformOverride: String = "",
    /**
     * 原生库解包缓存目录强制覆盖(非 FCL 启动器 / 非常规 tmpdir 场景):
     * Android 上 javacpp Loader 与 AAudio .so 的解包目录默认取
     * java.io.tmpdir → user.home → user.dir 中**可写且可执行**(dlopen 需要 exec,
     * noexec 挂载如共享存储会被拒绝)的第一个目录;若启动器把这些属性指到
     * noexec 路径导致"原生库加载失败",可在此手动指定一个 app 私有可执行目录
     * (如 /data/user/0/<包名>/cache/musicmc-native)。留空 = 自动判定。
     */
    val nativeCacheDir: String = "",
    /**
     * UI 方案:AUTO(默认,装了哪个用哪个 —— PC:ModernUI > YACL > 原版;
     * Android:YACL > 原版,ModernUI 永不用于 Android)/ MODERN_UI / YACL / VANILLA。
     * Android(FCL)上 ModernUI 3.13 的文字渲染依赖 Java2D(java.awt),而 NDK OpenJDK
     * 的 Java2D 字形光栅化为空转 → 界面文字全空白;YACL/原版 MC 界面走位图字体,不受影响。
     */
    val uiMode: String = "AUTO",
) {
    companion object {
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        /** 加载配置;文件缺失 / 解析失败时回退默认值(旧配置残留字段由 Gson 忽略) */
        fun load(dir: Path): ModConfig {
            return try {
                val file = dir.resolve("musicmc.json")
                if (!Files.isReadable(file)) return ModConfig()
                val text = Files.readString(file)
                val parsed = gson.fromJson(text, ModConfig::class.java) ?: ModConfig()
                // 旧配置兼容:ModConfig 是 data class,无参构造器不存在(Kotlin data class 即使
                // 全部参数有默认值也不生成),Gson 走 Unsafe 实例化 —— 缺失字段 = JVM 默认值
                // (Boolean=false / Float=0f / String=null),不会套用 Kotlin 默认值。
                // 旧配置(在 HUD 字段引入前保存的 musicmc.json)没有 hudX 字段 → Gson 给 0,
                // 命中即回填 HUD 默认,保证老配置升级后 HUD 默认开启且位置/缩放正确。
                // 注意:不能用"hudX==0f && hudY==0f"当哨兵 —— 用户在 HUD 编辑器把面板拖到
                // 屏幕左上角(锚点 0,0)保存后,hudX/hudY 就是 0f,下次启动会被误判为
                // "旧配置缺字段"而重置回默认位置(HUD 位置每次启动自动重置的根因)。
                // 改为直接检查原始 JSON 是否含 hudX 字段:只有字段**不存在**才算旧配置。
                val hasHudField = runCatching {
                    JsonParser.parseString(text).let { el -> el.isJsonObject && el.asJsonObject.has("hudX") }
                }.getOrDefault(true)
                if (!hasHudField) {
                    parsed.copy(
                        hudEnabled = true,
                        hudX = 0.92f,
                        hudY = 0.86f,
                        hudScale = 1.0f,
                    )
                } else {
                    parsed
                }
            } catch (e: Exception) {
                ModConfig()
            }
        }
    }

    /** 保存配置,任何失败都静默忽略(容错) */
    fun save(dir: Path) {
        try {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("musicmc.json"), gson.toJson(this))
        } catch (e: Exception) {
            // 容错:保存失败不影响播放
        }
    }
}
