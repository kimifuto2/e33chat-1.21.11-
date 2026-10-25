package com.niuqu.chatbubble.render;
import com.niuqu.chatbubble.ChatBubbleClientSetup;
import com.niuqu.chatbubble.store.ChatMessageStore;
import com.niuqu.chatbubble.ChatBubbleScreen;
import com.niuqu.chatbubble.ChatBubbleMod;
import com.niuqu.chatbubble.config.ChatBubbleConfig;
import com.niuqu.chatbubble.render.ChatBubbleTheme;
import com.niuqu.chatbubble.render.Appearance;

import com.mojang.blaze3d.systems.RenderSystem;
import com.niuqu.chatbubble.chat.notification.MentionNotificationBanner;
import com.niuqu.chatbubble.config.ChatBubbleConfig;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class ChatBubbleHudOverlay {

    private static final int ICON_S = 16;
    private static final int SRC_U = 6;
    private static final int SRC_V = 6;
    private static final int SRC_S = 4;
    private static final int TIP_DISP = 4;

    private static ChatBubbleConfig cfg() { return ChatBubbleClientSetup.config(); }

    private static Identifier chatIconTex() {
        String theme = Appearance.resolvedTheme().name().toLowerCase();
        return Identifier.fromNamespaceAndPath("e33chat", "textures/gui/" + theme + "/chat_icon.png");
    }

    private static ChatBubbleTheme theme() {
        return Appearance.resolvedTheme();
    }

    private static ChatBubbleTheme.Colors c() { return Appearance.snapshot(); }

    public static void render(GuiGraphicsExtractor raw) {
        UiCanvas g = UiCanvas.of(raw);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options == null) return;
        // F1 隐藏 HUD 时，本模组的聊天图标/提示条/通知横幅也一起隐藏（与原生 HUD 行为一致）
        if (mc.gui.hud.isHidden()) return;

        g.getMatrices().pushMatrix();
        g.getMatrices().translate(0, 0);

        MentionNotificationBanner.INSTANCE.tick();
        if (mc.gui.screen() == null) {
            MentionNotificationBanner.INSTANCE.render(g,
                mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight());
        }

        if (mc.gui.screen() != null) { g.getMatrices().popMatrix(); return; }

        String keyName = mc.options.keyChat.getTranslatedKeyMessage().getString();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int x = 3;
        int iconY = screenH - ICON_S - 20;
        int textY = iconY + ICON_S + 1;

        // 公屏消息小窗：只有聊天面板关闭时才画（上面已对 currentScreen 早退，F1 也在入口早退），
        // 位置钉在聊天图标正上方、与图标左对齐，先画面板再画图标，两者不会互相遮挡
        PublicChatOverlay.render(g, x, iconY);

        if (!cfg().hideChatIcon()) {
            drawIcon(g, x, iconY);

            if (cfg().redDotEnabled() && ChatMessageStore.getUnreadCount() > 0) {
                double wave = Math.abs(Math.sin(System.currentTimeMillis() / 300.0)) * 3;
                int tipX = x + ICON_S - TIP_DISP / 2;
                int tipY = iconY - TIP_DISP / 2 + (int) wave;
                drawScaledTip(g, tipX, tipY, TIP_DISP);
            }

            String keyDisplay = "[" + keyName + "]";
            int keyW = mc.font.width(keyDisplay);
            int keyX = keyW > ICON_S ? x : x + (ICON_S - keyW) / 2;
            g.drawText(mc.font, keyDisplay, keyX, textY, 0xFFFFFFFF, false);
        }

        g.getMatrices().popMatrix();
    }

    // Fabric's HUD layer draws behind the screen batch; screens that render over
    // it re-invoke this so the banner stays visible on top
    public static void renderBannerForScreen(GuiGraphicsExtractor raw) {
        UiCanvas g = UiCanvas.of(raw);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options == null) return;
        if (mc.gui.screen() instanceof ChatBubbleScreen) {
            MentionNotificationBanner.INSTANCE.render(g,
                mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight());
        }
    }

    public static boolean isMouseOverIcon(double mx, double my) {
        if (cfg().hideChatIcon()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) return false;
        int screenH = mc.getWindow().getGuiScaledHeight();
        int iconY = screenH - ICON_S - 20;
        return mx >= 3 && mx <= 3 + ICON_S && my >= iconY && my <= iconY + ICON_S + mc.font.lineHeight + 2;
    }


    private static void drawIcon(UiCanvas g, int x, int y) {
        // getTexture 无缓存时自动 new ResourceTexture 懒加载（资源包可覆盖，F3+T 即时生效）
        g.drawTexture(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, chatIconTex(), x, y, 0.0F, 0.0F, ICON_S, ICON_S, ICON_S, ICON_S);
    }

    private static void drawScaledTip(UiCanvas g, int x, int y, int disp) {
        Identifier tex = ChatBubbleScreen.iconTex("private_tip");
        g.drawTexture(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, x, y, (float) SRC_U, (float) SRC_V, disp, disp, SRC_S, SRC_S, 16, 16);
    }
}
