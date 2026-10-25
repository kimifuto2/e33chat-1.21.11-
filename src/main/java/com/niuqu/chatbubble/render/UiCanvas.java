package com.niuqu.chatbubble.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fStack;

import java.util.List;

/**
 * 绘制层适配（MC 26.3）。
 *
 * <p>26.3 把原先的 {@code DrawContext/GuiGraphics} 换成了 {@link GuiGraphicsExtractor}，并且
 * 大量绘制方法改了名字（{@code getMatrices→pose}、{@code drawText→text}、
 * {@code drawTexture→blit}、{@code drawTooltip→setTooltipForNextFrame}、
 * {@code applyBlur→blurBeforeThisStratum} …）。本类把这套差异收在一个地方，
 * 对外保留项目内部一直使用的调用形态，这样各屏幕/面板的绘制代码不需要整体重写：
 *
 * <ul>
 *   <li>{@link #getMatrices()} / {@link #pose()} → {@code GuiGraphicsExtractor.pose()}
 *       （{@code org.joml.Matrix3x2fStack}，pushMatrix/popMatrix/translate/scale 不变）</li>
 *   <li>{@link #drawText(Font, String, int, int, int, boolean)} 等 3 种文本类型 →
 *       {@code GuiGraphicsExtractor.text(Font, …)}；{@code shadow} 参数语义不变</li>
 *   <li>{@link #drawTexture} 的 4 种参数形态 → {@code blit(…)}；{@code u,v,width,height,
 *       regionWidth,regionHeight,textureWidth,textureHeight,argb} 的语义逐一对应
 *       （见 {@code blit} 字节码：x2=x+w、u1=(u+regionW)/texW）</li>
 *   <li>{@link #fill} / {@link #enableScissor} / {@link #disableScissor} 名字未变，仅转发</li>
 *   <li>{@link #width()} / {@link #height()} → 26.3 的 {@code guiWidth()} / {@code guiHeight()}</li>
 * </ul>
 *
 * <p>插在屏幕/控件的渲染入口处包一层即可：{@code UiCanvas g = UiCanvas.of(raw);}。
 * 需要交还给原版 API（{@code super.extractRenderState}、{@code Widget.extractRenderState}、
 * mixin 注入点）时用 {@link #raw()} 取回原始对象。
 *
 * <p>行为等价性说明：所有方法都只做转发，不做颜色/透明度归一化，也不改变绘制顺序，
 * 因此既有视觉（圆角、气泡、侧边栏、选区、右键菜单、tooltip、模糊）保持原样。
 */
public final class UiCanvas {

    private final GuiGraphicsExtractor extractor;

    private UiCanvas(GuiGraphicsExtractor extractor) {
        this.extractor = extractor;
    }

    /** 包装一个 {@code GuiGraphicsExtractor}；null 透传（调用方无需判空）。 */
    public static UiCanvas of(GuiGraphicsExtractor extractor) {
        return extractor == null ? null : new UiCanvas(extractor);
    }

    /** 取回原始绘制对象（交给原版 API / mixin 注入点时用）。 */
    public GuiGraphicsExtractor raw() {
        return extractor;
    }

    // ===================== 矩阵 =====================

    /** 26.3 名字：{@code GuiGraphicsExtractor.pose()}。 */
    public Matrix3x2fStack pose() {
        return extractor.pose();
    }

    /** 旧名字（{@code DrawContext.getMatrices()}）：同一个 {@code Matrix3x2fStack}。 */
    public Matrix3x2fStack getMatrices() {
        return extractor.pose();
    }

    // ===================== 尺寸 =====================

    /** 缩放后的 GUI 宽度（旧 {@code getScaledWidth()}）。 */
    public int width() {
        return extractor.guiWidth();
    }

    /** 缩放后的 GUI 高度（旧 {@code getScaledHeight()}）。 */
    public int height() {
        return extractor.guiHeight();
    }

    public int guiWidth() {
        return extractor.guiWidth();
    }

    public int guiHeight() {
        return extractor.guiHeight();
    }

    // ===================== 填充 / 裁剪 =====================

    /** 实心矩形；26.3 参数语义未变（x1,y1,x2,y2,argb）。 */
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        extractor.fill(x1, y1, x2, y2, argb);
    }

    public void fillGradient(int x1, int y1, int x2, int y2, int topArgb, int bottomArgb) {
        extractor.fillGradient(x1, y1, x2, y2, topArgb, bottomArgb);
    }

    public void horizontalLine(int x1, int x2, int y, int argb) {
        extractor.horizontalLine(x1, x2, y, argb);
    }

    public void verticalLine(int x, int y1, int y2, int argb) {
        extractor.verticalLine(x, y1, y2, argb);
    }

    public void enableScissor(int x1, int y1, int x2, int y2) {
        extractor.enableScissor(x1, y1, x2, y2);
    }

    public void disableScissor() {
        extractor.disableScissor();
    }

    // ===================== 文字 =====================

    public void drawText(Font font, String text, int x, int y, int color, boolean shadow) {
        extractor.text(font, text, x, y, color, shadow);
    }

    public void drawText(Font font, Component text, int x, int y, int color, boolean shadow) {
        extractor.text(font, text, x, y, color, shadow);
    }

    public void drawText(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        extractor.text(font, text, x, y, color, shadow);
    }

    /** 旧 4 参重载：带阴影（26.3 的 {@code text(…)} 默认 dropShadow=true）。 */
    public void drawText(Font font, String text, int x, int y, int color) {
        extractor.text(font, text, x, y, color);
    }

    public void drawText(Font font, Component text, int x, int y, int color) {
        extractor.text(font, text, x, y, color);
    }

    public void drawText(Font font, FormattedCharSequence text, int x, int y, int color) {
        extractor.text(font, text, x, y, color);
    }

    // ===================== 纹理 =====================

    /** (x,y,u,v,w,h,texW,texH,argb)：采样区域 = w×h。 */
    public void drawTexture(RenderPipeline pipeline, Identifier texture,
                            int x, int y, float u, float v, int width, int height,
                            int textureWidth, int textureHeight, int argb) {
        extractor.blit(pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight, argb);
    }

    /** 同上但不着色（26.3 blit 用 -1 = 白色 tint）。 */
    public void drawTexture(RenderPipeline pipeline, Identifier texture,
                            int x, int y, float u, float v, int width, int height,
                            int textureWidth, int textureHeight) {
        extractor.blit(pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    /** (…,regionW,regionH,texW,texH)：从 (u,v) 采样 regionW×regionH 铺到 w×h。 */
    public void drawTexture(RenderPipeline pipeline, Identifier texture,
                            int x, int y, float u, float v, int width, int height,
                            int regionWidth, int regionHeight,
                            int textureWidth, int textureHeight) {
        extractor.blit(pipeline, texture, x, y, u, v, width, height,
            regionWidth, regionHeight, textureWidth, textureHeight);
    }

    /** (…,regionW,regionH,texW,texH,argb)：带 tint（纹理 × argb）。 */
    public void drawTexture(RenderPipeline pipeline, Identifier texture,
                            int x, int y, float u, float v, int width, int height,
                            int regionWidth, int regionHeight,
                            int textureWidth, int textureHeight, int argb) {
        extractor.blit(pipeline, texture, x, y, u, v, width, height,
            regionWidth, regionHeight, textureWidth, textureHeight, argb);
    }

    // ===================== tooltip / hover =====================

    public void drawTooltip(List<FormattedCharSequence> lines, int mouseX, int mouseY) {
        extractor.setTooltipForNextFrame(lines, mouseX, mouseY);
    }

    public void drawTooltip(Font font, Component text, int mouseX, int mouseY) {
        extractor.setTooltipForNextFrame(font, text, mouseX, mouseY);
    }

    /**
     * 悬停提示（旧 {@code drawHoverEvent(Font, Style, int, int)}）。
     *
     * <p>26.3 的 hover 提示改成「延迟到 {@code extractDeferredElements} 统一绘制」，
     * 入口是 {@code setTooltipForNextFrame}；我们自绘的文字不登记 ActiveTextCollector，
     * 所以仍然由这里按 Style 的 HoverEvent 显式登记一次，可见行为与旧版一致。
     */
    public void drawHoverEvent(Font font, Style style, int mouseX, int mouseY) {
        if (style == null) return;
        HoverEvent hover = style.getHoverEvent();
        if (hover instanceof HoverEvent.ShowText showText && showText.value() != null) {
            extractor.setTooltipForNextFrame(font, showText.value(), mouseX, mouseY);
        }
    }

    // ===================== 模糊 =====================

    /** 旧 {@code applyBlur()}：26.3 改名为 {@code blurBeforeThisStratum()}。 */
    public void applyBlur() {
        extractor.blurBeforeThisStratum();
    }
}
