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
        // MC 26.1 九参 blit 的四个 int 是「两个对角点 (x0, y0, x1, y1)」,
        // 不是旧版的 (x, y, w, h)!javap 核实:blit(id, x, y, w, h, ...) 的
        // 第 4/5 个 int 被原样填进 BlitRenderState 的 x1 / y1。
        // 若按旧语义传 (x, y, w, h):HUD 在左上角时 x0≈x1、y0≈y1 看着"正常"
        // (实际已缩成 w-x × h-y),挪到右下角后矩形 (x,y)~(w,h) 倒置 →
        // 倒立放大 / 小孔成像 / 钉点不动(用户实测现象)。这里换算成对角点。
        // UV 语义同理是 (u0, u1, v0, v1),全图 = (0, 1, 0, 1)。
        graphics.blit(texture as Identifier, x, y, x + w, y + h, 0f, 1f, 0f, 1f)
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
