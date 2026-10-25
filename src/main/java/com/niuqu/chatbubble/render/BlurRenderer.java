package com.niuqu.chatbubble.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Panel background blur.
 *
 * 1.21.1 used a custom GL blit multi-pass (GlStateManager framebuffer + blur). 1.21.9+
 * removed that GL frontend; instead we use the vanilla screen-blur API
 * (DrawContext.applyBlur()), which blurs whatever was drawn behind the current element.
 * Called right before the translucent panel background is painted, so the world/HUD behind
 * the chat panel gets blurred. Guarded by try/catch so a framework state mismatch can never
 * crash the render.
 */
public class BlurRenderer {

    private BlurRenderer() {}

    public static void blurPanel(UiCanvas g, int x, int y, int w, int h) {
        try {
            g.applyBlur();
        } catch (Throwable t) {
            // best-effort; vanilla blur unsupported in this DrawContext state -> stay crisp
        }
    }
}
