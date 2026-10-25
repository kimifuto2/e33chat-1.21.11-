package com.niuqu.chatbubble.mixin;

import com.niuqu.chatbubble.ui.BedScreen;
import com.niuqu.chatbubble.ChatBubbleClientSetup;
import com.niuqu.chatbubble.ChatBubbleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.InBedChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * 26.3 moved screen switching off {@code Minecraft.setScreen} (that method no longer exists; the
 * remaining {@code Minecraft.setScreenAndShow} only does {@code gui.setScreen(x) + renderFrame()}).
 * {@code Gui.setScreen(Screen)} is now the single funnel: {@code ChatComponent.openScreen} (the chat
 * key) calls it, {@code Gui.openChatScreen} passes {@code InBedChatScreen::new} as the ChatConstructor,
 * and {@code Minecraft.setScreenAndShow} delegates to it. Budget/vanilla screens never match the two
 * instanceof guards below, and our own ChatBubbleScreen/BedScreen are excluded, so re-entering
 * {@code gui.setScreen} from this handler terminates after one extra hop.
 */
@Mixin(Gui.class)
public class MinecraftClientMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        var cfg = ChatBubbleClientSetup.config();
        if (cfg == null || !cfg.enabled()) return;

        if (screen instanceof InBedChatScreen) {
            ci.cancel();
            BedScreen.setScreenBeforeSleep(Minecraft.getInstance().gui.screen());
            Minecraft.getInstance().gui.setScreen(new BedScreen());
        } else if (screen instanceof ChatScreen chatScreen
                && !(chatScreen instanceof ChatBubbleScreen)) {
            ci.cancel();
            String initial = getChatInitialText(chatScreen);
            Minecraft.getInstance().gui.setScreen(new ChatBubbleScreen(initial));
        }
    }

    private static String getChatInitialText(ChatScreen chatScreen) {
        try {
            Field f = ChatScreen.class.getDeclaredField("initial");
            f.setAccessible(true);
            String val = (String) f.get(chatScreen);
            return val != null ? val : "";
        } catch (Exception ignored) {}
        for (Field f : ChatScreen.class.getDeclaredFields()) {
            if (f.getType() == String.class) {
                f.setAccessible(true);
                try {
                    String val = (String) f.get(chatScreen);
                    if (val != null && !val.isEmpty()) return val;
                } catch (Exception ignored) {}
            }
        }
        return "";
    }
}
