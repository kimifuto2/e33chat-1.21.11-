package com.niuqu.chatbubble.paper;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 动作消息的服务端校验 + 广播（C2S {@code e33chat:player_action} →
 * S2C {@code e33chat:player_action_relay}），与 Fabric 端
 * {@code com.niuqu.chatbubble.server.PlayerActionRelay} 逐条对齐。
 *
 * <p>职责边界：
 * <ul>
 *   <li>词条**只信自己**：已知 {@code verbId} 一律用 {@link PlayerActionVerbs} 的固定文本，
 *       客户端送来的 customText 只在 {@code verbId=custom} 时生效，并做长度/字符清洗 ——
 *       否则任何人都能借动作消息广播任意长文本/任意样式。</li>
 *   <li>正文夹到 {@link PlayerActionVerbs#MAX_CONTENT}，空白丢弃。</li>
 *   <li>范围校验：PUBLIC → 全服；GROUP → 群必须存在且**发送者是成员**，只发在线成员；
 *       PRIVATE → 只发被操作玩家（离线就只剩发送者本地回显）。</li>
 *   <li>**不回给发送者**：发送者本地立即回显，回给他是重复消息（这里不做去重表）。</li>
 *   <li>整体 try/catch 由调用方（{@link E33ChatPaperPlugin}）兜住：动作消息坏掉不能踢人。</li>
 * </ul>
 */
final class PlayerActionRelay {

    private PlayerActionRelay() {
    }

    private static void log(String msg) {
        Plugin p = E33ChatPaperPlugin.self();
        if (p != null) {
            p.getLogger().info("[e33chat] srv | action | " + msg);
        }
    }

    static synchronized void handle(Player sender, PlayerActionPayloads.Inbound payload) {
        if (sender == null || payload == null) {
            return;
        }
        Plugin plugin = E33ChatPaperPlugin.self();
        if (plugin == null) {
            return;
        }

        String content = PlayerActionVerbs.sanitizeContent(payload.content());
        if (content.isEmpty()) {
            log("ignored | empty content | from=" + sender.getName());
            return;
        }
        String verb = PlayerActionVerbs.resolveVerb(payload.verbId(), payload.customText());
        if (verb == null) {
            log("rejected | unknown verb | from=" + sender.getName()
                + " | verbId=" + payload.verbId());
            return;
        }
        UUID targetId = GroupWire.parseUuid(payload.targetUUID());
        if (targetId == null) {
            // 名字兜底：客户端可能拿不到发送者 UUID（nil），只剩显示名；
            // 按在线玩家名唯一匹配，匹配不到就拒绝（不猜）。
            String byName = PlayerActionVerbs.sanitizeTargetName(payload.targetName());
            Player online = byName.isEmpty() ? null : plugin.getServer().getPlayerExact(byName);
            if (online != null) {
                targetId = online.getUniqueId();
            }
        }
        if (targetId == null) {
            log("rejected | bad target | from=" + sender.getName()
                + " | uuid=" + payload.targetUUID() + " | name=" + payload.targetName());
            return;
        }
        PlayerActionScope scope = PlayerActionScope.fromWire(payload.scope());
        if (scope == null) {
            scope = PlayerActionScope.PUBLIC;
        }

        String targetName = resolveTargetName(plugin, targetId, payload.targetName());
        String groupId = "";
        List<Player> recipients = new ArrayList<>();

        switch (scope) {
            case GROUP -> {
                GroupRegistry.Group group = GroupRegistry.byId(payload.groupId());
                if (group == null) {
                    log("group ignored | unknown group | group=" + payload.groupId()
                        + " | from=" + sender.getName());
                    return;
                }
                if (!group.hasMember(sender.getUniqueId())) {
                    log("group denied | not a member | from=" + sender.getName()
                        + " | " + group);
                    return;
                }
                groupId = group.id().toString();
                for (UUID uuid : group.memberIds()) {
                    Player p = plugin.getServer().getPlayer(uuid);
                    if (p != null && !p.getUniqueId().equals(sender.getUniqueId())) {
                        recipients.add(p);
                    }
                }
            }
            case PRIVATE -> {
                Player target = plugin.getServer().getPlayer(targetId);
                if (target != null && !target.getUniqueId().equals(sender.getUniqueId())) {
                    recipients.add(target);
                }
            }
            case PUBLIC -> {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (!p.getUniqueId().equals(sender.getUniqueId())) {
                        recipients.add(p);
                    }
                }
            }
        }

        byte[] relay = PlayerActionPayloads.encodeRelay(
            sender.getUniqueId(), sender.getName(),
            targetId.toString(), targetName, verb, content, scope.wire(), groupId);
        int sent = 0;
        for (Player p : recipients) {
            try {
                p.sendPluginMessage(plugin, E33Channels.PLAYER_ACTION_RELAY, relay);
                sent++;
            } catch (Throwable t) {
                log("relay failed | player=" + p.getName() + " | " + t);
            }
        }
        log("relay | from=" + sender.getName()
            + " | target=" + targetName + " | verb=" + verb
            + " | scope=" + scope.wire() + (groupId.isEmpty() ? "" : " | group=" + groupId)
            + " | recipients=" + sent
            + " | preview=" + McCodec.preview(content));
    }

    /** 在线玩家用服务端解析出的正式名字；离线目标只能用客户端带上来的名字（已清洗/夹断）。 */
    private static String resolveTargetName(Plugin plugin, UUID targetId, String fromClient) {
        Player online = plugin.getServer().getPlayer(targetId);
        if (online != null) {
            return online.getName();
        }
        return PlayerActionVerbs.sanitizeTargetName(fromClient);
    }
}
