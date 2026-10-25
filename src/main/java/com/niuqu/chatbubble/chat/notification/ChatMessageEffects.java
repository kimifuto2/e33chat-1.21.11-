package com.niuqu.chatbubble.chat.notification;

import com.niuqu.chatbubble.ChatBubbleClientSetup;
import com.niuqu.chatbubble.store.ChatMessageStore;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 真实副作用实现（B4 上移）：横幅转发给 MentionNotificationController，
 * 公共/引用提示音直接播放。由 ChatBubbleClientSetup.onInitializeClient()
 * 注册为 ChatMessageStore 的观察者——store 不再直接依赖 Minecraft 单例。
 */
public class ChatMessageEffects implements ChatMessageStore.MessageEffectObserver {
    @Override
    public void onMentionOrQuote(Component content, ChatMessageStore.SenderMeta meta,
                                 int index, String replySender) {
        MentionNotificationController.INSTANCE.onMessageCaptured(content, meta, index, replySender);
    }

    @Override
    public void onWhisperReceived(UUID senderUUID, Component senderName, Component content, int index, String partnerName) {
        MentionNotificationController.INSTANCE.onWhisperReceived(senderUUID, senderName, content, index, partnerName);
    }

    @Override
    public void onSystemMessage(Component content, int index) {
        MentionNotificationController.INSTANCE.onSystemMessage(content, index);
    }

    @Override
    public void onPublicChatSound() {
        playChime();
    }

    @Override
    public void onQuoteSound() {
        playChime();
    }

    private static void playChime() {
        var player = Minecraft.getInstance().player;
        if (player != null)
            player.playSound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.6F * ChatBubbleClientSetup.config().soundVolume() / 100f, 1.0F);
    }

    /**
     * 免打扰（DND）对「非 @ 类」发声的统一开关（**公屏/系统侧**）：为 true 时公共/系统提示音静默。
     *
     * <p>免打扰已按会话独立：本方法只看「公屏免打扰」(config.dndEnabled)；
     * 私聊提示音改由 {@link #dndMutesWhisper(String)} 按该私聊对象单独判定。
     *
     * <p>刻意**不**覆盖 @提及：{@code MentionNotificationController.onMessageCaptured} 的
     * mention 音效不经过本判断，DND 打开时「弹横幅 + 响一声」仍然照常（需求：@我仍然提醒）。
     * 提及型引用（{@link #onQuoteSound()}）同属「找我」的强提醒，也不静默。
     */
    public static boolean dndMutesNonMention() {
        return ChatBubbleClientSetup.config().dndEnabled();
    }

    /**
     * 指定私聊对象的提示音是否静默：只看「该私聊对象」自己的免打扰开关
     * （config.dndWhisperPlayers 里有没有这个名字），A 的私聊免打扰不影响 B。
     */
    public static boolean dndMutesWhisper(String partnerName) {
        return ChatBubbleClientSetup.config().isWhisperDnd(partnerName);
    }
}
