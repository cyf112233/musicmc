package io.github.cyf112233.musicmc.platform

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * MC 版本自适应的"当前屏幕"访问桥(26.1 / 26.2 双版本单 jar 兼容)。
 *
 * 背景:MC 26.2 重构了屏幕持有者 —— `Minecraft.screen` 字段与
 * `Minecraft.setScreen(Screen)` 方法被移除,屏幕状态迁入
 * `Minecraft.gui`(net.minecraft.client.gui.Gui)的 `screen` 字段与
 * `Gui.setScreen(Screen)` 方法(已对 26.1.0.19-beta / 26.2.0.64 两个
 * patched client jar 做 javap 核实:26.2 的 Minecraft 类已无 `screen`
 * 字段与 `setScreen` 方法;Gui 类则持有 `screen` 字段、`screen()` 取值
 * 方法与 `setScreen(Screen)` 方法)。
 *
 * 由于这是本 mod 使用的全部 MC API 中**唯一**的 26.1→26.2 差异
 * (其余 GuiGraphicsExtractor / Screen / GuiEventListener / EditBox /
 * KeyMapping / Identifier / NativeImage / Font / MusicManager 等签名
 * 逐一 javap 比对过,两版本一致),无需把 common 拆成"版本模块"——
 * 一个运行期自适应的桥接即可让同一 jar 同时跑在 26.1 与 26.2 上。
 *
 * 实现:首次访问时用反射探测 Minecraft 类是否存在 `screen` 字段,
 * 存在 → 26.1 模式(字段/方法都在 Minecraft 实例上);
 * 不存在 → 26.2 模式(经 Minecraft.gui 拿到 Gui 实例,再取其 screen)。
 * 解析出的 Field/Method 句柄一次性缓存,之后每次调用只是
 * Field.get / Method.invoke(毫微秒级),帧循环里 `current()` 也不会有
 * 可感知开销。
 */
object McScreens {

    // ---- 解析结果缓存(首次访问时惰性填充)----
    /** 26.1 模式:Minecraft.screen 字段(存在=26.1;不存在=26.2) */
    private var legacyScreenField: Field? = null

    /** 26.1 模式:Minecraft.setScreen(Screen) 方法 */
    private var legacySetScreenMethod: Method? = null

    /** 两版本都有的 Minecraft.gui 字段(26.2 模式经它拿 Gui 实例) */
    private var guiField: Field? = null

    /** 26.2 模式:Gui.screen() 取值方法 */
    private var guiScreenMethod: Method? = null

    /** 26.2 模式:Gui.setScreen(Screen) 方法 */
    private var guiSetScreenMethod: Method? = null

    /** 是否已完成解析 */
    @Volatile
    private var resolved = false

    private fun ensureResolved() {
        if (resolved) return
        synchronized(this) {
            if (resolved) return
            val mcClass = Minecraft::class.java
            // 26.2 的 Gui 类;26.1 也有该类(只是 screen 字段/方法在 Minecraft 上),
            // 解析失败时退化为 mcClass(反正 26.1 模式不会用到 gui 句柄)
            val guiClass: Class<*> = runCatching {
                Class.forName("net.minecraft.client.gui.Gui")
            }.getOrDefault(mcClass)
            // 探测版本:26.1 有 Minecraft.screen 字段,26.2 没有
            legacyScreenField = runCatching { mcClass.getDeclaredField("screen") }.getOrNull()
            if (legacyScreenField != null) {
                legacySetScreenMethod = runCatching {
                    mcClass.getMethod("setScreen", Screen::class.java)
                }.getOrNull()
            } else {
                // 26.2:Minecraft.gui → Gui.screen() / Gui.setScreen(Screen)
                guiField = runCatching { mcClass.getDeclaredField("gui") }.getOrNull()
                guiScreenMethod = runCatching {
                    guiClass.getMethod("screen")
                }.getOrNull()
                guiSetScreenMethod = runCatching {
                    guiClass.getMethod("setScreen", Screen::class.java)
                }.getOrNull()
            }
            resolved = true
        }
    }

    /** 当前打开的屏幕(26.1 读 Minecraft.screen;26.2 读 Minecraft.gui.screen()) */
    fun current(): Screen? {
        ensureResolved()
        val mc = Minecraft.getInstance()
        legacyScreenField?.let { f ->
            return runCatching { f.get(mc) as? Screen }.getOrNull()
        }
        val gui = guiField?.let { runCatching { it.get(mc) }.getOrNull() } ?: return null
        val m = guiScreenMethod ?: return null
        return runCatching { m.invoke(gui) as? Screen }.getOrNull()
    }

    /** 打开屏幕(null = 关闭回到游戏;26.1 调 Minecraft.setScreen;26.2 调 Gui.setScreen) */
    fun open(screen: Screen?) {
        ensureResolved()
        val mc = Minecraft.getInstance()
        val legacy = legacySetScreenMethod
        if (legacy != null) {
            runCatching { legacy.invoke(mc, screen) }
            return
        }
        val gui = guiField?.let { runCatching { it.get(mc) }.getOrNull() } ?: return
        val m = guiSetScreenMethod ?: return
        runCatching { m.invoke(gui, screen) }
    }
}
