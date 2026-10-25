package com.niuqu.chatbubble.ui;
import com.mojang.blaze3d.platform.InputConstants;
import com.niuqu.chatbubble.ChatBubbleScreen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.chat.Component;

public class BedScreen extends Screen {

    private static Screen screenBeforeSleep;

    public BedScreen() {
        super(Component.translatable("multiplayer.stopSleeping"));
    }

    public static void setScreenBeforeSleep(Screen screen) {
        screenBeforeSleep = screen;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("multiplayer.stopSleeping"), b -> sendWakeUp())
            .bounds(width / 2 - 100, height - 40, 200, 20).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor raw, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void tick() {
        if (minecraft == null || minecraft.player == null || !minecraft.player.isSleeping()) {
            minecraft.gui.setScreen(null);
            if (screenBeforeSleep instanceof ChatBubbleScreen) {
                minecraft.gui.setScreen(screenBeforeSleep);
            }
            screenBeforeSleep = null;
        }
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int keyCode = input.key();
        int scanCode = input.keycode();
        if (keyCode == InputConstants.KEY_ESCAPE) {
            sendWakeUp();
            return true;
        }
        if (minecraft.options.keyChat.matches(input)) {
            minecraft.gui.setScreen(new ChatBubbleScreen(""));
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void sendWakeUp() {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.connection.send(
                new ServerboundPlayerCommandPacket(minecraft.player, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
        }
    }
}
