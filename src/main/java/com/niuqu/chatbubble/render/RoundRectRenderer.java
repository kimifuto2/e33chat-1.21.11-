package com.niuqu.chatbubble.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Rounded-rectangle fill.
 *
 * 1.21.1 used a custom SDF shader (rendertype_round_rect). 1.21.9+ replaced the
 * ShaderProgram/BufferRenderer path with the RenderPipeline system and makes per-draw
 * shader uniforms (u_Rect / u_Radius) awkward for mods, so we instead bake the rounded
 * rectangle into a white anti-aliased MASK texture per (w,h,radius) and draw it as a
 * colour-tinted texture via DrawContext.drawTexture(..., argb) (GUITextured pipeline tints
 * the texture with the vertex colour). This uses only public API and degrades to a plain
 * square g.fill on any failure — it never crashes the render.
 */
public class RoundRectRenderer {
    private static final int MAX_CACHE = 96;
    private static final Map<String, Identifier> maskCache = new HashMap<>();
    private static final ArrayDeque<String> cacheOrder = new ArrayDeque<>();
    private static final Map<String, NativeImage> maskImages = new HashMap<>();

    private RoundRectRenderer() {}

    /** Resource-reload hook (kept for parity with the old resetShader). */
    public static void resetShader() {
        clearCache();
    }

    private static void clearCache() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (Identifier id : maskCache.values()) tm.release(id);
        maskCache.clear();
        cacheOrder.clear();
        maskImages.clear();
    }

    private static Identifier maskFor(int w, int h, int radius) {
        w = Math.max(2, w);
        h = Math.max(2, h);
        radius = Math.max(1, Math.min(radius, Math.min(w, h) / 2));
        String key = w + "_" + h + "_" + radius;
        Identifier cached = maskCache.get(key);
        if (cached != null) return cached;
        if (maskCache.size() >= MAX_CACHE) clearCache();

        Identifier id = Identifier.fromNamespaceAndPath("e33chat", "round_" + key);
        NativeImage img = new NativeImage(w, h, true);
        float rx = w / 2f, ry = h / 2f, r = radius, rr = radius;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Signed distance to a rounded rectangle (Inigo Quilez sdRoundedBox).
                float px = x + 0.5f - rx;
                float py = y + 0.5f - ry;
                float qx = Math.abs(px) - (rx - r);
                float qy = Math.abs(py) - (ry - r);
                float dx = Math.max(qx, 0f);
                float dy = Math.max(qy, 0f);
                float dist = (float) Math.sqrt(dx * dx + dy * dy) + Math.min(Math.max(qx, qy), 0f) - r;
                float alpha = Math.max(0f, Math.min(1f, 0.5f - dist));
                int a = Math.round(alpha * 255f);
                img.setPixel(x, y, (a << 24) | 0x00FFFFFF);
            }
        }
        Minecraft.getInstance().getTextureManager()
            .register(id, new DynamicTexture(() -> "e33chat_round_rect", img));
        maskCache.put(key, id);
        maskImages.put(key, img);
        cacheOrder.addLast(key);
        return id;
    }

    public static void fill(UiCanvas g, int x1, int y1, int x2, int y2, float radius, int argb) {
        int w = x2 - x1, h = y2 - y1;
        if (w <= 0 || h <= 0) return;
        int rad = Math.max(1, Math.round(radius));
        if (rad <= 0 || radius <= 0.01f) {
            g.fill(x1, y1, x2, y2, argb);
            return;
        }
        try {
            Identifier mask = maskFor(w, h, rad);
            g.drawTexture(RenderPipelines.GUI_TEXTURED, mask, x1, y1, 0f, 0f, w, h, w, h, argb);
        } catch (Throwable t) {
            g.fill(x1, y1, x2, y2, argb);
        }
    }

    /**
     * Draw a rounded popup/panel with a thin rounded border ring.
     * @param x,y,w,h  the panel rect (top-left + size)
     * @param bg      interior colour (already alpha-blended)
     * @param border  border ring colour (already alpha-blended)
     */
    public static void panel(UiCanvas g, int x, int y, int w, int h, float radius, int bg, int border) {
        if (w <= 0 || h <= 0) return;
        RoundRectRenderer.fill(g, x, y, x + w, y + h, radius, border);
        RoundRectRenderer.fill(g, x + 1, y + 1, x + w - 1, y + h - 1, Math.max(1f, radius - 1), bg);
    }
}
