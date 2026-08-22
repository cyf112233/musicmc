package io.github.cyf112233.musicmc.client

import io.github.cyf112233.musicmc.platform.PlatformHolder

/**
 * 界面文本 i18n 辅助:按当前游戏语言返回中文或英文。
 *
 * 设计:
 * - [t](中文, 英文) 返回当前语言对应的文案;游戏语言为中文(zh_cn / zh_tw /
 *   zh_hk 等语言代码含 "zh")时返回中文,否则返回英文(默认 en_us)。
 * - 语言判断经 [PlatformHolder.require().isChinese] 委托 loader(fabric/neoforge)
 *   侧实现 —— common 模块不直接依赖 net.minecraft(MC 版本差异隔离在平台层)。
 * - **加载期安全**:loader 侧 isChinese 读 Minecraft 语言时可能抛
 *   IllegalStateException(游戏启动早期),平台实现负责 try/catch 兜底;
 *   本对象再次 runCatching,取不到语言时按英文处理,绝不因 i18n 导致启动崩溃。
 * - **缓存**:检测结果缓存 [CACHE_TTL_MS] 毫秒(默认 30s),渲染回调每帧调用
 *   [t] 也不会反复访问 Minecraft;切语言后最多 30s 内界面文案跟随(重启即
 *   立即生效)。[invalidateCache] 供需要立即刷新的场景调用。
 */
object UiText {

    /** 语言检测缓存有效期(ms):切语言后最多这么久界面文案跟随 */
    private const val CACHE_TTL_MS = 30_000L

    @Volatile
    private var cachedChinese: Boolean? = null

    @Volatile
    private var cachedAt: Long = 0L

    /** 清除语言缓存(切换语言后立即刷新界面文案时调用) */
    fun invalidateCache() {
        cachedChinese = null
        cachedAt = 0L
    }

    /** 当前游戏语言是否为中文(语言代码如 zh_cn / zh_tw / zh_hk 均命中) */
    fun isChinese(): Boolean {
        val now = System.currentTimeMillis()
        val cached = cachedChinese
        if (cached != null && now - cachedAt < CACHE_TTL_MS) return cached
        val detected = detectChinese()
        cachedChinese = detected
        cachedAt = now
        return detected
    }

    /** 实际检测(带兜底:任何异常按英文处理,保证加载期 / 非客户端环境不崩) */
    private fun detectChinese(): Boolean {
        return runCatching {
            PlatformHolder.require().isChinese()
        }.getOrDefault(false)
    }

    /** 按游戏语言选择文案:[zh] 中文,[en] 英文(默认英文,与 MC 默认语言一致) */
    fun t(zh: String, en: String): String = if (isChinese()) zh else en
}
