package io.github.cyf112233.musicmc.platform

/**
 * 游戏内 HUD 绘制接口(版本无关)。
 *
 * 由 fabric / neoforge 平台层把各自的 GUI 绘制对象适配成本接口,
 * common 渲染逻辑只依赖本接口,不直接触碰 MC 的 GuiGraphics / blit / fill
 * 等内部 API —— 这些方法在 MC 各版本间签名 / 参数语义差异很大
 * (如 26.1 的 blit 九参重载对 UV 参数顺序的定义就与旧版不同)。
 *
 * 坐标单位:GUI 缩放坐标(逻辑像素),与 [guiWidth] / [guiHeight] 同单位。
 */
interface HudGui {

    /** GUI 缩放坐标下的屏幕宽度(逻辑像素) */
    fun guiWidth(): Int

    /** GUI 缩放坐标下的屏幕高度(逻辑像素) */
    fun guiHeight(): Int

    /** 纯色填充矩形(x1,y1 左上,x2,y2 右下,含 x2,y2 所在行列) */
    fun fill(x1: Int, y1: Int, x2: Int, y2: Int, color: Int)

    /**
     * 把整张纹理绘制到目标矩形 (x, y, w, h)。
     *
     * [texture] 是平台纹理句柄(common 只透传,不感知其类型)。
     * 实现方必须保证:全图 UV(0,0,1,1)、不裁剪、不翻转、保持方向正确 ——
     * 不要依赖具体 MC 版本对 blit UV 参数顺序的定义(那是版本差异的重灾区)。
     */
    fun drawTexture(texture: Any, x: Int, y: Int, w: Int, h: Int)

    /** 文本宽度(未缩放,像素) */
    fun textWidth(text: String): Int

    /** 文本行高(像素) */
    fun textHeight(): Int

    /**
     * 在 (x, y) 以 [sizePx] 逻辑字号绘制文本。
     * [scale] 为布局统一缩放系数(common 已算好,内部与字号共同决定最终字形大小)。
     */
    fun drawText(text: String, x: Int, y: Int, sizePx: Float, scale: Float, color: Int)
}
