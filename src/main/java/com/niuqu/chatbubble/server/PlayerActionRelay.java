package com.niuqu.chatbubble.server;

import com.niuqu.chatbubble.chat.action.PlayerActionScope;
import com.niuqu.chatbubble.chat.action.PlayerActionVerbs;
import com.niuqu.chatbubble.network.GroupWire;
import com.niuqu.chatbubble.network.PlayerActionPayload;
import com.niuqu.chatbubble.network.PlayerActionRelayPayload;
import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 动作消息的服务端校验 + 广播（{@code e33chat:player_action}）。
 *
 * <p>职责边界（与 {@link GroupRegistry#relayMessage} 同构）：
 * <ul>
 *   <li>词条**只信自己**：已知 verbId 一律用 {@link PlayerActionVerbs} 的固定文本，
 *       客户端送来的 customText 只在 verbId=custom 时生效，并做长度/字符清洗 ——
 *       否则任何人都能借动作消息广播任意长文本/任意样式。</li>
 *   <li>正文夹到 {@link PlayerActionVerbs#MAX_CONTENT}，空白丢弃。</li>
 *   <li>范围校验：PUBLIC → 全服；GROUP → 群必须存在且**发送者是成员**，只发在线成员；
 *       PRIVATE → 只发被操作玩家（离线就只剩发送者本地回显）。</li>
 *   <li>**不回给发送者**：发送者本地立即回显，回给他是重复消息（这里不做去重表）。</li>
 *   <li>整体 try/catch 由调用方（{@code ChatBubbleMod}）兜住：动作消息坏掉不能踢人。</li>
 * </ul>
 */
public final class PlayerActionRelay {
    private PlayerActionRelay() {}

    private static void log(String msg) {
        LogUtils.getLogger().info("[e33chat] srv | action | " + msg);
    }

    public static synchronized void handle(MinecraftServer server, ServerPlayer sender,
                                           PlayerActionPayload payload) {
        if (server == null || sender == null || payload == null) return;

        String content = PlayerActionVerbs.sanitizeContent(payload.content());
        if (content.isEmpty()) {
            log("ignored | empty content | from=" + sender.getName().getString());
            return;
        }
        String verb = PlayerActionVerbs.resolveVerb(payload.verbId(), payload.customText());
        if (verb == null) {
            log("rejected | unknown verb | from=" + sender.getName().getString()
                + " | verbId=" + payload.verbId());
            return;
        }
        UUID targetId = GroupWire.parseUuid(payload.targetUUID());
        if (targetId == null) {
            // 名字兜底：NCR 等把玩家消息也变成系统行的服务器上，客户端可能拿不到发送者
            // UUID（nil），只剩显示名；按在线玩家名唯一匹配，匹配不到就拒绝（不猜）。
            String byName = PlayerActionVerbs.sanitizeTargetName(payload.targetName());
            ServerPlayer online = byName.isEmpty() ? null : server.getPlayerList().getPlayer(byName);
            if (online != null) targetId = online.getUUID();
        }
        if (targetId == null) {
            log("rejected | bad target | from=" + sender.getName().getString()
                + " | uuid=" + payload.targetUUID() + " | name=" + payload.targetName());
            return;
        }
        PlayerActionScope scope = PlayerActionScope.fromWire(payload.scope());
        if (scope == null) scope = PlayerActionScope.PUBLIC;

        String targetName = resolveTargetName(server, targetId, payload.targetName());
        String groupId = "";
        List<ServerPlayer> recipients = new ArrayList<>();

        switch (scope) {
            case GROUP -> {
                GroupRegistry.ensureLoaded(server);
                GroupRegistry.Group group = GroupRegistry.byId(payload.groupId());
                if (group == null) {
                    log("group ignored | unknown group | group=" + payload.groupId()
                        + " | from=" + sender.getName().getString());
                    return;
                }
                if (!group.hasMember(sender.getUUID())) {
                    log("group denied | not a member | from=" + sender.getName().getString()
                        + " | " + group);
                    return;
                }
                groupId = group.id().toString();
                for (UUID uuid : group.memberIds()) {
                    ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                    if (p != null && !p.getUUID().equals(sender.getUUID())) recipients.add(p);
                }
            }
            case PRIVATE -> {
                ServerPlayer target = server.getPlayerList().getPlayer(targetId);
                if (target != null && !target.getUUID().equals(sender.getUUID())) recipients.add(target);
            }
            case PUBLIC -> {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (!p.getUUID().equals(sender.getUUID())) recipients.add(p);
                }
            }
        }

        PlayerActionRelayPayload relay = new PlayerActionRelayPayload(
            sender.getUUID(), sender.getName().getString(),
            targetId.toString(), targetName, verb, content, scope.wire(), groupId);
        int sent = 0;
        for (ServerPlayer p : recipients) {
            try {
                ServerPlayNetworking.send(p, relay);
                sent++;
            } catch (Throwable t) {
                log("relay failed | player=" + p.getName().getString() + " | " + t);
            }
        }
        log("relay | from=" + sender.getName().getString()
            + " | target=" + targetName + " | verb=" + verb
            + " | scope=" + scope.wire() + (groupId.isEmpty() ? "" : " | group=" + groupId)
            + " | recipients=" + sent
            + " | preview=" + preview(content));
    }

    /** 在线玩家用服务端解析出的正式名字；离线目标只能用客户端带上来的名字（已清洗/夹断）。 */
    private static String resolveTargetName(MinecraftServer server, UUID targetId, String fromClient) {
        ServerPlayer online = server.getPlayerList().getPlayer(targetId);
        if (online != null) return online.getName().getString();
        return PlayerActionVerbs.sanitizeTargetName(fromClient);
    }

    private static String preview(String s) {
        if (s == null || s.isEmpty()) return "";
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= 40 ? one : one.substring(0, 40) + "...";
    }
}
