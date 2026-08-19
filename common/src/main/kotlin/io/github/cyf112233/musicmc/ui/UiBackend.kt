package io.github.cyf112233.musicmc.ui

/**
 * UI 后端(common 纯逻辑,无 MC 依赖)。原版 UI 已移除,只剩两套:
 *
 * - MODERN_UI:ModernUI(仅 PC;Android(FCL)禁用 —— ModernUI 3.13 文字渲染依赖
 *   Java2D,NDK OpenJDK 无真 Java2D → 文字空白);
 * - YACL:YACL(YetAnotherConfigLib)现代化界面(Android 首选;YACL 为必装依赖,
 *   双平台 fabric+neoforge)。
 *
 * 解析规则:
 * - Android:恒 YACL(ModernUI 不可用);
 * - PC:ModernUI 装了且配置为 MODERN_UI/AUTO → ModernUI;否则 YACL;
 * - 旧配置值 VANILLA(原版 UI 时代遗留)→ 映射到 YACL。
 */
enum class UiBackend { MODERN_UI, YACL }

object UiBackendResolver {

    /**
     * @param cfgMode 配置值(ModConfig.uiMode,小写无关)
     * @param isAndroid 是否 Android(FCL)运行时
     * @param modernUiLoaded ModernUI 模组是否加载
     */
    fun resolve(cfgMode: String, isAndroid: Boolean, modernUiLoaded: Boolean): UiBackend {
        if (isAndroid) return UiBackend.YACL
        val mode = cfgMode.uppercase()
        return when {
            // 显式 ModernUI 且已加载 → ModernUI;显式 VANILLA(旧值)或 YACL → YACL
            mode == "MODERN_UI" && modernUiLoaded -> UiBackend.MODERN_UI
            mode == "AUTO" && modernUiLoaded -> UiBackend.MODERN_UI
            else -> UiBackend.YACL
        }
    }
}
