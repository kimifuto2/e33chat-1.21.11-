package com.niuqu.chatbubble.chat.action;

import com.niuqu.chatbubble.ChatBubbleScreen;
import com.niuqu.chatbubble.network.GroupWire;
import com.niuqu.chatbubble.network.PlayerActionRelayPayload;
import com.niuqu.chatbubble.store.ChatMessageStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.UUID;

/**
 * 动作消息的**收端**落地（客户端）：把服务端转发的 {@link PlayerActionRelayPayload}
 * 插进对应会话，渲染成系统信息样式的一行 {@code A 拍了拍 B 说：正文}。
 *
 * <p>会话归属（scope → 会话键）：
 * <ul>
 *   <li>PUBLIC → 公屏（partner = null）；</li>
 *   <li>GROUP  → {@code "group:<groupId>"}；</li>
 *   <li>PRIVATE → 与**对方**的私聊（自己发的 → 被操作玩家；别人发的 → 发送者）。
 *       私聊的动作消息因此只出现在两个人的会话里，不会串到公屏/群聊。</li>
 * </ul>
 *
 * <p>不走 {@code addMessage} 的副作用链（不弹横幅/不发声/不参与去重合并）：它是系统样式的
 * 提示行，不是一条玩家聊天。正在看那个会话时不算未读，否则给该会话打未读点。
 */
public final class ActionMessages {
    private ActionMessages() {}

    /** 收到服务端转发的一条动作消息。 */
    public static void receive(PlayerActionRelayPayload payload) {
        if (payload == null || !payload.isValid()) return;
        String verb = PlayerActionVerbs.sanitizeVerb(payload.verbText());
        String content = PlayerActionVerbs.sanitizeContent(payload.content());
        if (verb.isEmpty() || content.isEmpty()) return;

        PlayerActionScope parsed = PlayerActionScope.fromWire(payload.scope());
        final PlayerActionScope scope = parsed == null ? PlayerActionScope.PUBLIC : parsed;

        MinecraftClient client = MinecraftClient.getInstance();
        UUID myId = client.player != null ? client.player.getUuid() : null;
        boolean mine = myId != null && myId.equals(payload.senderUUID());

        String partner;
        switch (scope) {
            case GROUP -> {
                UUID groupId = GroupWire.parseUuid(payload.groupId());
                if (groupId == null) return;
                partner = ChatMessageStore.groupPartnerKey(groupId);
            }
            case PRIVATE -> partner = mine ? payload.targetName() : payload.senderName();
            default -> partner = null;
        }
        if (scope == PlayerActionScope.PRIVATE && (partner == null || partner.isEmpty())) return;

        String line = PlayerActionVerbs.composeLine(
            payload.senderName(), verb, payload.targetName(), content);
        boolean viewing = client.currentScreen instanceof ChatBubbleScreen screen
            && screen.isViewingConversation(partner);
        ChatMessageStore.addActionMessage(Text.literal(line), partner, !mine && !viewing);
        ChatMessageStore.debugLog(() -> "[e33chat] action recv | scope=" + scope.wire()
            + " | sender=" + payload.senderName() + " | target=" + payload.targetName()
            + " | partner=" + partner + " | viewing=" + viewing + " | line=" + line);
    }

    /** 发送者本地回显：与收端同一句话、同一个会话键（scope 用**当前会话**推导）。 */
    public static void echoLocal(String line, String partner, PlayerActionScope scope) {
        if (line == null || line.isBlank()) return;
        ChatMessageStore.addActionMessage(Text.literal(line), partner, false);
        ChatMessageStore.debugLog(() -> "[e33chat] action echo | scope="
            + (scope == null ? "?" : scope.wire()) + " | partner=" + partner + " | line=" + line);
    }

    /**
     * 退化路径的正文段形态：{@code 词条 目标 说：正文}（不含发送者名）。
     *
     * <p>服务端没有 {@code e33chat:player_action} 通道时（Paper + 旧插件 / 旧版服务端模组），
     * 动作消息只能作为普通聊天发出去；这一段的形态固定，收端据此把它还原成系统行。
     */
    private static final java.util.regex.Pattern PLAIN_BODY =
        java.util.regex.Pattern.compile("^(\\S+) (\\S+) 说：([\\s\\S]+)$");

    /**
     * 退化路径的**收端识别**：把一句普通聊天 {@code 拍了拍 B 说：正文} 还原成
     * {@code A 拍了拍 B 说：正文}（发送者名由聊天管线提供）。
     *
     * <p>只认 8 个内置词条（{@link PlayerActionVerbs#isKnownText}），自定义词条不参与：
     * 词条是任意文本时，普通聊天很容易被误判成动作、从而被"吃掉"成系统行。
     *
     * @return 还原后的整行文本；不是动作文本时 null
     */
    public static String plainActionLine(String senderName, String body) {
        if (senderName == null || senderName.isBlank() || body == null) return null;
        java.util.regex.Matcher m = PLAIN_BODY.matcher(body.trim());
        if (!m.matches()) return null;
        String verb = m.group(1);
        if (!PlayerActionVerbs.isKnownText(verb)) return null;
        String content = PlayerActionVerbs.sanitizeContent(m.group(3));
        if (content.isEmpty()) return null;
        return PlayerActionVerbs.composeLine(senderName,
            verb, m.group(2), content);
    }

    /**
     * 退化路径收到一条动作聊天 → 插系统行（与 payload 路径同款：居中、不进气泡）。
     *
     * @param line     {@link #plainActionLine} 的返回值（整行文本）
     * @param partner  会话键：null = 公屏，其它 = 私聊对象（退化路径不会有群聊）
     */
    public static void receivePlain(String line, String partner) {
        if (line == null || line.isBlank()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        boolean viewing = client.currentScreen instanceof ChatBubbleScreen screen
            && screen.isViewingConversation(partner);
        ChatMessageStore.addActionMessage(Text.literal(line), partner, !viewing);
        ChatMessageStore.debugLog(() -> "[e33chat] action recv(plain) | partner=" + partner
            + " | viewing=" + viewing + " | line=" + line);
    }
}
