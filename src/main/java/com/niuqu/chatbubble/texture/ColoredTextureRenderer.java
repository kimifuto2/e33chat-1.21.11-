package com.niuqu.chatbubble.texture;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;

/**
 * 带整体透明度的纹理渲染：纹理色 × 白 (1,1,1,alpha)。
 * 用于动态 alpha 的元素（面板开屏淡入、滚动条淡入淡出）——普通 drawTexture 无法携带运行时透明度。
 *
 * 1.21.11 迁移：不再使用 Tessellator/BufferRenderer/自定义 shader，全部改走 DrawContext 高层
 * drawTexture(RenderPipeline, ...)，由 GUI_TEXTURED 管线用顶点颜色对纹理做整体 tint（纹理 × 颜色）。
 */
public final class ColoredTextureRenderer {

    private ColoredTextureRenderer() {}

    /** 由运行时 alpha 合成一个白色 tint（1,1,1,alpha）。 */
    private static int argbAlpha(float alpha) {
        int a = (int) (alpha * 255f);
        if (a > 255) a = 255;
        if (a < 0) a = 0;
        return (a << 24) | 0x00FFFFFF;
    }

    public static void drawWithAlpha(DrawContext g, Identifier tex,
                                     int x, int y, int w, int h, float alpha) {
        if (w <= 0 || h <= 0 || alpha <= 0.003f) return;
        g.drawTexture(RenderPipelines.GUI_TEXTURED, tex, x, y, 0f, 0f, w, h, w, h, argbAlpha(alpha));
    }

    /** 带整体 tint 色的纹理渲染：纹理色 × tint(r,g,b,a)。用于白色默认纹理 × 主题色动态着色。 */
    public static void drawTinted(DrawContext g, Identifier tex,
                                  int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0) return;
        g.drawTexture(RenderPipelines.GUI_TEXTURED, tex, x, y, 0f, 0f, w, h, w, h, argb);
    }

    /** 带整体 tint 色 + UV 采样的纹理渲染：等价 drawTexture 的
     *  (u,v,regionWidth,regionHeight,textureWidth,textureHeight) 语义，但用 tint 着色。
     *  图标染色（例如把白色剪影的月亮贴图染成紫色）用这个，采样语义与 drawWithAlpha 的重载一致。 */
    public static void drawTinted(DrawContext g, Identifier tex,
                                  int x, int y, int w, int h,
                                  float u, float v, int regionW, int regionH,
                                  int texW, int texH, int argb) {
        if (w <= 0 || h <= 0) return;
        g.drawTexture(RenderPipelines.GUI_TEXTURED, tex, x, y,
            u, v, w, h, regionW, regionH, texW, texH, argb);
    }

    /**
     * 带整体透明度 + UV 采样的纹理渲染：等价 drawTexture 的
     * (u,v,regionWidth,regionHeight,textureWidth,textureHeight) 语义，但带动态 alpha。
     * 图标/带采样区域的元素淡入用。
     */
    public static void drawWithAlpha(DrawContext g, Identifier tex,
                                     int x, int y, int w, int h,
                                     float u, float v, int regionW, int regionH,
                                     int texW, int texH, float alpha) {
        if (w <= 0 || h <= 0 || alpha <= 0.003f) return;
        g.drawTexture(RenderPipelines.GUI_TEXTURED, tex, x, y,
            u, v, w, h, regionW, regionH, texW, texH, argbAlpha(alpha));
    }
}
