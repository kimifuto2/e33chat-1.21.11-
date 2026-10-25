package com.niuqu.chatbubble.chat.notification;

import com.niuqu.chatbubble.ChatBubbleClientSetup;
import com.niuqu.chatbubble.ChatBubbleScreen;
import com.niuqu.chatbubble.store.ChatMessageStore;
import com.niuqu.chatbubble.chat.MentionDetector;
import com.niuqu.chatbubble.chat.notification.MentionNotificationBanner.NotificationType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;

import java.util.*;

public class MentionNotificationController {
    public static final MentionNotificationController INSTANCE = new MentionNotificationController();

    private final Map<String, Long> recentFingerprints = new LinkedHashMap<>() {
        protected boolean removeEldestEntry(Map.Entry<String, Long> e) {
            return size() > 32;
        }
    };

    private MentionNotificationController() {}

    public void onMessageCaptured(Component content, ChatMessageStore.SenderMeta meta,
                                   int messageIndex, String replySender) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        String localName = mc.player.getName().getString();
        boolean requireAt = ChatBubbleClientSetup.config().mentionRequireAt();
        String text = content.getString();

        if (!MentionDetector.isMentioned(text, localName, requireAt, replySender)) return;

        boolean isOwn = (meta.senderUUID() != null && meta.senderUUID().equals(mc.player.getUUID()))
            || (meta.rawPlayerName() != null && meta.rawPlayerName().equals(localName));
        boolean chatOpen = mc.gui.screen() instanceof ChatBubbleScreen;
        NotificationType type = (replySender != null && replySender.equals(localName))
            ? NotificationType.QUOTE : NotificationType.MENTION;
        boolean selfNotify = isOwn && (type == NotificationType.QUOTE
            ? ChatBubbleClientSetup.config().ownQuoteNotify()
            : ChatBubbleClientSetup.config().ownMentionNotify());

        ChatMessageStore.debugLog(() -> "[e33chat] Mention | sender="
            + (meta.rawPlayerName() != null ? meta.rawPlayerName() : "?")
            + " | chatOpen=" + chatOpen
            + " | own=" + isOwn
            + " | type=" + type
            + " | sound=" + ChatBubbleClientSetup.config().mentionSoundEnabled()
            + " | banner=" + ChatBubbleClientSetup.config().mentionBannerEnabled()
            + " | selfNotify=" + selfNotify
            + " | preview=" + text.substring(0, Math.min(40, text.length())));

        if ((!isOwn || selfNotify) && ChatBubbleClientSetup.config().mentionSoundEnabled()) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                SoundEvents.EXPERIENCE_ORB_PICKUP, 0.25f,
                0.25f * ChatBubbleClientSetup.config().soundVolume() / 100f));
        }

        if ((!isOwn || selfNotify) && ChatBubbleClientSetup.config().mentionBannerEnabled()) {
            enqueueDeduped(meta.senderUUID(), meta.senderName(), content, messageIndex, type, null);
        }
    }

    public void onWhisperReceived(UUID senderUUID, Component senderName, Component content,
                                   int messageIndex, String partnerName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        boolean chatOpen = mc.gui.screen() instanceof ChatBubbleScreen;
        String senderStr = senderName.getString().replaceAll("§.", "");
        boolean isOwn = (senderUUID != null && senderUUID.equals(mc.player.getUUID()))
            || mc.player.getName().getString().equals(senderStr);
        // 免打扰按「这条私聊的对话对象」判定（不是发送者自己）：自己发出的私聊回声
        // 也要看对方那条私聊有没有开免打扰。partnerName 由 store 的 whisperPartner 传下来。
        String dndPartner = partnerName != null && !partnerName.isBlank() ? partnerName : senderStr;

        ChatMessageStore.debugLog(() -> "[e33chat] Whisper banner | sender=" + senderStr
            + " | partner=" + dndPartner
            + " | chatOpen=" + chatOpen
            + " | own=" + isOwn
            + " | soundWhisper=" + ChatBubbleClientSetup.config().soundWhisper()
            + " | banner=" + ChatBubbleClientSetup.config().mentionWhisperBanner());

        boolean selfNotify = isOwn && ChatBubbleClientSetup.config().ownWhisperNotify();
        if ((!isOwn || selfNotify) && ChatBubbleClientSetup.config().soundWhisper()) {
            // 免打扰静默私聊提示音：只看这个私聊对象自己的开关（A 的免打扰不影响 B）。
            // @提及走 onMessageCaptured，不受这里影响。
            if (ChatMessageEffects.dndMutesWhisper(dndPartner)) {
                ChatMessageStore.debugLog(() -> "[e33chat] sound suppressed by DND（whisper） | partner=" + dndPartner);
            } else {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.EXPERIENCE_ORB_PICKUP, 0.25f,
                    0.25f * ChatBubbleClientSetup.config().soundVolume() / 100f));
            }
        }

        if ((!isOwn || selfNotify) && ChatBubbleClientSetup.config().mentionWhisperBanner()) {
            enqueueDeduped(senderUUID, senderName, content, messageIndex, NotificationType.WHISPER, dndPartner);
        }
    }

    // System messages (server broadcasts/deaths/joins) pop the same banner as
    // @/whisper/quote; no sender name — the [系统] label is the name row.
    public void onSystemMessage(Component content, int messageIndex) {
        if (Minecraft.getInstance().player == null) return;
        if (!ChatBubbleClientSetup.config().systemBannerEnabled()) return;
        enqueueDeduped(new UUID(0, 0), Component.empty(), content, messageIndex,
            NotificationType.SYSTEM, null);
    }

    /** 免打扰只压「被动接收」类横幅，而且**按会话分别判定**：
     *  <ul>
     *    <li>WHISPER —— 看这条私聊的对话对象（partnerName）自己有没有开免打扰；</li>
     *    <li>SYSTEM（公屏/服务器播报）—— 看「公屏免打扰」开关 dndEnabled；</li>
     *    <li>MENTION / QUOTE —— 「找我」的强提醒，永远穿透免打扰。</li>
     *  </ul>
     *  partnerName 为 null（旧调用 / 拿不到对象）时退回「公屏开关」，
     *  避免因名字缺失导致私聊横幅在开了公屏免打扰时仍然弹出。 */
    private static boolean isSuppressedByDnd(NotificationType type, String partnerName) {
        if (type == NotificationType.WHISPER) {
            if (partnerName != null && !partnerName.isBlank())
                return ChatBubbleClientSetup.config().isWhisperDnd(partnerName);
            return ChatBubbleClientSetup.config().dndEnabled();
        }
        if (type == NotificationType.SYSTEM) return ChatBubbleClientSetup.config().dndEnabled();
        return false;
    }

    private void enqueueDeduped(UUID uuid, Component name, Component content, int index,
                                 NotificationType type, String partnerName) {
        // 免打扰（DND）唯一拦截点：横幅「入库」之前。@提及（MENTION）与引用回复（QUOTE）
        // 优先级高于免打扰，不在抑制集合里，因此照常弹出（需求：有人 @我时仍要提醒）。
        // 红点/未读计数在 ChatMessageStore 里独立维护，与本方法无关，故免打扰不影响红点。
        if (isSuppressedByDnd(type, partnerName)) {
            ChatMessageStore.debugLog(() -> "[e33chat] Banner suppressed by DND | type=" + type
                + " | partner=" + (partnerName != null ? partnerName : "-")
                + " | preview=" + content.getString().substring(0, Math.min(40, content.getString().length())));
            return;
        }
        String fp = uuid + "\0" + content.getString();
        long now = System.currentTimeMillis();
        Long last = recentFingerprints.get(fp);
        if (last != null && now - last < 1000) {
            ChatMessageStore.debugLog(() -> "[e33chat] Banner deduped | fp="
                + fp.substring(0, Math.min(40, fp.length())));
            return;
        }
        recentFingerprints.put(fp, now);
        MentionNotificationBanner.INSTANCE.enqueue(uuid, name, content, index, type);
        ChatMessageStore.debugLog(() -> "[e33chat] Banner enqueued | queueSize="
            + MentionNotificationBanner.INSTANCE.pendingCount());
    }
}
