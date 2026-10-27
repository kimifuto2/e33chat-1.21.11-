package com.niuqu.chatbubble.mixin;
import com.niuqu.chatbubble.store.EchoTracker;
import com.niuqu.chatbubble.store.BlockList;

import com.niuqu.chatbubble.ChatBubbleClientSetup;
import com.niuqu.chatbubble.ChatBubbleScreen;
import com.niuqu.chatbubble.store.ChatMessageStore;
import com.niuqu.chatbubble.store.ChatMessageStore.SenderMeta;
import com.niuqu.chatbubble.image.BracketCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(value = ChatComponent.class, priority = 500)
public class ChatComponentMixin {
    private Component lastComponent;
    private boolean e33chat$shifted;
    private boolean e33chat$reposting;
    private String lastRepostText;
    private long lastRepostTime;

    // 26.3 splits GUI rendering into "extract -> deferred submit", so ChatComponent.render(...)
    // became extractRenderState(GuiGraphicsExtractor, Font, int, int, int, ChatComponent$DisplayMode, boolean)
    // (the last boolean is focused; the old `hidden` flag is folded into DisplayMode).
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V", at = @At("HEAD"), cancellable = true)
    private void onRender(GuiGraphicsExtractor context, Font font, int tickDelta, int mouseX, int mouseY,
                          ChatComponent.DisplayMode mode, boolean focused, CallbackInfo ci) {
        // The mod replaces the vanilla chat surface with its own bubble panel + brief toasts,
        // so hide the vanilla ChatHud entirely whenever the mod is enabled (no duplicate chat).
        if (ChatBubbleClientSetup.config().enabled()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V", at = @At("RETURN"))
    private void onRenderReturn(GuiGraphicsExtractor context, Font font, int tickDelta, int mouseX, int mouseY,
                                ChatComponent.DisplayMode mode, boolean focused, CallbackInfo ci) {
        if (e33chat$shifted) {
            context.pose().popMatrix();
        }
    }

    // 26.3 keeps a single funnel: the private addMessage(Component, MessageSignature,
    // GuiMessageSource, GuiMessageTag) called by addClientSystemMessage / addServerSystemMessage /
    // addPlayerMessage. The old 1-arg and 3-arg overloads are gone, so this is the only capture point.
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"), cancellable = true)
    private void onAddMessageFull(Component message, MessageSignature signature, GuiMessageSource source,
                                  GuiMessageTag indicator, CallbackInfo ci) {
        captureMessage(message, ci);
    }

    // Vanilla chat gets a unified player-style format for whispers/quotes:
    //   <sender>[私聊] content   (whisper in/out, incl. self-whisper)
    //   <sender>[引用] content   (quote reply, detected via the echo's quoted flag)
    // The sender component keeps its style so colored nicknames/prefixes survive.
    private void repostToVanilla(Component name, String content, boolean quoting) {
        // banner.quote/whisper carry a trailing space (banner prefix convention),
        // so content is appended without an extra separator.
        Component tag = (quoting
            ? Component.translatable("e33chat.banner.quote").withStyle(ChatFormatting.YELLOW)
            : Component.translatable("e33chat.banner.whisper").withStyle(ChatFormatting.LIGHT_PURPLE));
        Component reformatted = Component.empty()
            .append(Component.literal("<")).append(name).append(Component.literal(">")).append(tag)
            .append(Component.literal(content));
        String repostStr = reformatted.getString();
        long nowMs = System.currentTimeMillis();
        // Server echoes a whisper twice (signed outgoing + incoming) within ~15ms;
        // both would rewrite to the same line without this guard.
        if (ChatMessageStore.isRepostDuplicate(lastRepostText, lastRepostTime, repostStr, nowMs)) {
            ChatMessageStore.debugLog(() -> "[e33chat] Repost deduped | '" + repostStr + "'");
            return;
        }
        lastRepostText = repostStr;
        lastRepostTime = nowMs;
        ChatMessageStore.debugLog(() -> "[e33chat] Repost to vanilla | '" + repostStr + "' | quoting=" + quoting);
        e33chat$reposting = true;
        // 3-arg addMessage with a null indicator: the 1-arg overload forces
        // GuiMessageTag.system(), which logs "[System] [CHAT]" and styles the line
        ((ChatComponent) (Object) this).addPlayerMessage(reformatted, null, null);
        e33chat$reposting = false;
    }

    // The vanilla chat gets the raw [[CICode,url=...]] line (long URL → spammy).
    // Rewrite it to a "[图片]" placeholder so the bubble renders the image while
    // the vanilla surface stays compact, independent of ChatImage being installed.
    private void rewriteVanillaImageCode(Component finalComponent, CallbackInfo ci) {
        Component placeholder = BracketCodec.toPlaceholderText(finalComponent);
        if (placeholder == finalComponent) return; // no image code, nothing to do
        ci.cancel();
        e33chat$reposting = true;
        ((ChatComponent) (Object) this).addPlayerMessage(placeholder, null, null);
        e33chat$reposting = false;
    }

    private void captureMessage(Component finalComponent, CallbackInfo ci) {
        if (!ChatBubbleClientSetup.config().enabled()) return;
        if (e33chat$reposting) return;

        // 1-arg addMessage calls 3-arg internally with the SAME Component object —
        // dedupe on object identity so two genuinely identical messages (same text,
        // different objects) are never swallowed
        if (finalComponent == lastComponent) return;
        lastComponent = finalComponent;
        String text = finalComponent.getString();

        // Outgoing whisper echo via the system channel ("你悄悄对 Steve 说: hi"):
        // suppress the vanilla line and repost it as <me>[私聊] hi. Checked BEFORE
        // consumePendingMeta: this path never sets pending meta, so consuming it here
        // would eat a stale residue and misattribute the next real message.
        if (ChatMessageStore.consumeSuppressCapture()) {
            ci.cancel();
            // Decorated name from the line itself, so this path matches the signed
            // echo path's meta.senderName() — otherwise the repost dedup guard sees
            // different strings (tab name vs chat-decorated name) and shows both
            Component name = ChatMessageStore.extractWhisperDisplayName(finalComponent,
                ChatMessageStore.ownDisplayName());
            // Vanilla outgoing lines carry only the target ("你悄悄地对X说" / "You
            // whisper to X") — ownDisplayName() then supplies our name. Either way
            // the local bubble was created with a bare name: patch it now that the
            // echo reveals the real self display name.
            ChatMessageStore.cacheOwnDecoratedName(name);
            ChatMessageStore.updateLatestOwnSenderName(name);
            repostToVanilla(name, ChatMessageStore.extractWhisperContent(text, null),
                ChatMessageStore.consumeSuppressQuoted());
            return;
        }

        SenderMeta meta = ChatMessageStore.consumePendingMeta();
        if (meta == null) {
            if (ChatMessageStore.isRecentDuplicate(text)) return;
            meta = new SenderMeta(
                new UUID(0, 0),
                Component.translatable("e33chat.sender.system"),
                finalComponent,
                true,
                null,
                false, null
            );
        }

        // Blocked sender: vanish completely — no vanilla line, no bubble, no
        // banner/sound (addMessage below never runs). Checked before the echo and
        // whisper-repost branches so a blocked player's whisper can't resurface
        // as a [私聊] rewrite.
        if (BlockList.isPlayerBlocked(meta.rawPlayerName(), meta.senderName(),
                ChatBubbleClientSetup.config().blockedPlayers())) {
            final String blockedName = meta.senderName().getString();
            ci.cancel();
            ChatMessageStore.debugLog(() -> "[e33chat] Blocked message dropped | sender='" + blockedName + "'");
            return;
        }

        // Self-sent echo on the signed channel: plain chat keeps the vanilla line;
        // whisper echoes get the [私聊] rewrite, quote replies get the [引用] rewrite
        // (quote replies travel as plain chat, so the echo's quoted flag is their
        // only rewrite signal). meta is trusted here (freshly consumed) and carries
        // the server-decorated name + content, e.g. "[称号]E33EPUS" / "1234533425".
        EchoTracker.EchoMatch echo = ChatMessageStore.consumeEchoIfSenderMatches(meta.senderUUID(), meta.senderName(), text);
        if (echo.matched()) {
            if (meta.whisper() || echo.quoted()) {
                ci.cancel();
                repostToVanilla(meta.senderName(), ChatMessageStore.extractWhisperContent(text, meta), echo.quoted());
            } else {
                rewriteVanillaImageCode(finalComponent, ci);
            }
            return;
        }
        if (ChatMessageStore.consumeEchoBySystemChat(text).matched()) {
            rewriteVanillaImageCode(finalComponent, ci);
            return;
        }

        // 玩家互动动作的**退化路径**（服务端没有 e33chat:player_action 通道，例如 Paper +
        // 只实现引用同步的插件）：动作消息只能作为普通聊天到达，形如「拍了拍 B 说：正文」。
        // 这里把它还原成 `A 拍了拍 B 说：正文` 并落成 SYSTEM 行（居中、无气泡），
        // 与走 payload 的收端渲染完全一致 —— 否则同一句话在发送者那边是系统行、
        // 在接收者那边却是普通气泡（用户实测反馈的不一致）。
        // 判据只认 8 个内置词条（PlayerActionVerbs.isKnownText），不会吞掉普通聊天。
        String actionBody = meta.whisper()
            ? ChatMessageStore.extractWhisperContent(text, meta)
            : meta.rawContent().getString();
        String actionLine = com.niuqu.chatbubble.chat.action.ActionMessages.plainActionLine(
            meta.senderName().getString(), actionBody);
        if (actionLine != null) {
            ci.cancel();
            // 自己发出去的那条回显：本地在发送时**已经插过**同一条系统行（sendActionMessage
            // 无条件插），这里只吞掉、不再插第二次 —— pending echo 万一被清理掉（>10s 过期）
            // 也不会出现「一条动作两行字」。
            var selfPlayer = Minecraft.getInstance().player;
            boolean fromSelf = selfPlayer != null && meta.senderUUID() != null
                && meta.senderUUID().equals(selfPlayer.getUUID());
            if (!fromSelf) {
                com.niuqu.chatbubble.chat.action.ActionMessages.receivePlain(
                    actionLine, meta.whisper() ? meta.whisperPartner() : null);
            } else {
                ChatMessageStore.debugLog(() -> "[e33chat] action echo(plain) swallowed | " + actionLine);
            }
            return;
        }

        // Incoming whisper (someone whispers you): same unified format, sender's name
        if (meta.whisper()) {
            ci.cancel();
            repostToVanilla(meta.senderName(), ChatMessageStore.extractWhisperContent(text, meta), false);
        }

        String rawStr = meta.rawContent().getString();
        String finalStr = finalComponent.getString();
        Component content;
        if (finalStr.contains(rawStr)) {
            content = meta.rawContent();
        } else {
            content = finalComponent;
        }
        // 2.3.10+: image bracket codes are kept raw in storage; the bubble
        // renders them natively (BracketCodec strips the code, ImageLoader
        // draws the picture). The vanilla chat still gets ChatImage's own
        // conversion via ChatImage's mixins, so both surfaces agree.

        Component logComp = finalComponent, logContent = content;
        SenderMeta logMeta = meta;
        ChatMessageStore.debugLog(() -> "[e33chat] Capture | final='" + logComp.getString() + "' | content='" + logContent.getString() + "' | whisper=" + logMeta.whisper() + " | partner=" + logMeta.whisperPartner() + " | isSystem=" + logMeta.isSystem());
        rewriteVanillaImageCode(finalComponent, ci);
        ChatMessageStore.addMessage(content, meta.senderUUID(), meta.senderName(), meta.isSystem(), meta.rawPlayerName(), meta.whisper(), meta.whisperPartner(), false);
    }
}
