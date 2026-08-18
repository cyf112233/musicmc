package io.github.cyf112233.musicmc.client

import io.github.cyf112233.musicmc.platform.HudGui
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

/**
 * [HudGui] 的 MC 实现:把 26.1 的 [GuiGraphicsExtractor] 适配为版本无关绘制接口。
 *
 * 这是 common 中唯一直接操作 MC 渲染 API(GuiGraphicsExtractor.blit / fill / text /
 * pose)的文件 —— MC 版本升级导致这些方法签名 / 语义变化时,只改这里。
 *
 * 关键设计:封面纹理在 CoverTextureCache 里已 CPU 端预裁剪成方形,
 * 这里 [drawTexture] 恒用全图对称 UV (0, 0, 1, 1) 调用 blit。对称值对
 * "MC 26.1 blit 九参 UV 字段顺序是 (u0, u1, v0, v1) 而非旧版 (u0, v0, u1, v1)"
 * 这类版本差异完全免疫:无论底层按哪种顺序解释,渲染结果都是"整张纹理、
 * 不翻转、不变形"。目标矩形 (x, y, w, h) 按常规语义传。
 */
class GuiGraphicsHudGui(private val graphics: GuiGraphicsExtractor) : HudGui {

    private val mc: Minecraft get() = Minecraft.getInstance()

    override fun guiWidth(): Int = graphics.guiWidth()

    override fun guiHeight(): Int = graphics.guiHeight()

    override fun fill(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        graphics.fill(x1, y1, x2, y2, color)
    }

    override fun drawTexture(texture: Any, x: Int, y: Int, w: Int, h: Int) {
        // 全图 UV (u0, u1, v0, v1) = (0, 1, 0, 1)。
        // 已 javap 三路核对 MC 26.1.2 的九参 blit:blit(id, x, y, w, h, u0, u1, v0, v1)
        // → innerBlit 把 int 重排为 (x, y, w, h)、float 原样传入 → BlitRenderState 字段
        // (x0, y0, x1, y1, u0, u1, v0, v1) 依次接收 → buildVertices 按 水平 [u0..u1]、
        // 垂直 [v0..v1] 生成顶点 UV。即 UV 参数顺序是 (u0, u1, v0, v1),与旧版
        // (u0, v0, u1, v1) 不同 —— 封面已 CPU 预裁剪为方形,这里恒用全图对称值,
        // 不再计算非对称 UV,彻底绕开该版本差异。
        graphics.blit(texture as Identifier, x, y, w, h, 0f, 1f, 0f, 1f)
    }

    override fun textWidth(text: String): Int = mc.font.width(text)

    override fun textHeight(): Int = mc.font.lineHeight

    override fun drawText(text: String, x: Int, y: Int, sizePx: Float, scale: Float, color: Int) {
        if (text.isEmpty()) return
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(sizePx / mc.font.lineHeight * scale)
        graphics.text(mc.font, text, 0, 0, color)
        pose.popMatrix()
    }
}
