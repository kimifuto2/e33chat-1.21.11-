package com.niuqu.chatbubble.paper;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 群聊注册表（服务端权威）：**所有群消息都走服务端中继**，与 Fabric 端
 * {@code com.niuqu.chatbubble.server.GroupRegistry} 行为逐条对齐。
 *
 * <p>职责：
 * <ul>
 *   <li>保存「群 = id + 名字 + 成员表（按入群顺序、带名字，离线成员也能显示昵称）+ 创建者」；</li>
 *   <li>校验每个 C2S 操作：发送者必须是群成员；**邀请与解散只有创建者能做**，
 *       改名/踢人所有成员可做，但**创建者不可被踢**；任何异常只记日志、绝不抛到网络层；</li>
 *   <li>变更以 {@code group_sync} 广播给全体**在线**成员；消息以 {@code group_message_relay}
 *       转发给全体在线成员（**含发送者**，客户端按 msgHash 去重）；</li>
 *   <li>玩家登录时把他的全部群逐个补发（客户端不需要自己持久化成员表）。</li>
 * </ul>
 *
 * <p><b>状态是纯内存态</b>：Paper 侧不做落盘，服务端重启后所有群消失（README 的已知限制）。
 * 与 Fabric 端不同（那边落 {@code <world>/serverconfig/e33chat-groups.json}），这是本插件的
 * 有意取舍：插件不碰世界目录、不引入 gson 依赖，行为在当次运行内完全一致。
 *
 * <p><b>进群邀请是 pending 语义</b>（与 Fabric 第 4 轮一致）：建群/加人**不直接把人拉进群**，
 * 只把被邀请者记进 {@code pendingInvites} 并给他发 {@code group_invite_event}(kind=INVITE)；
 * 对方回 {@code group_invite_respond} 且 accept=true 才真正进群。pending 同样是内存态，
 * 被邀请者离线时直接忽略（不排离线队列）。
 */
final class GroupRegistry {

    private GroupRegistry() {
    }

    /** 群人数上限（含 pending），与 Fabric {@code GroupRegistry.MAX_MEMBERS} 同值。 */
    static final int MAX_MEMBERS = GroupWire.MAX_LIST;

    /** 群事件 kind —— 与 Fabric {@code GroupEventPayload} 的常量逐字一致。 */
    static final String KIND_INVITED = "INVITED";
    static final String KIND_KICKED = "KICKED";
    static final String KIND_LEFT = "LEFT";
    static final String KIND_DISBANDED = "DISBANDED";
    static final String KIND_RENAMED = "RENAMED";
    static final String KIND_INVITE_DENIED = "INVITE_DENIED";

    /** 进群邀请事件的 kind —— 与 Fabric {@code GroupInviteEventPayload} 一致。 */
    static final String INVITE_INVITE = "INVITE";
    static final String INVITE_ACCEPTED = "ACCEPTED";
    static final String INVITE_DECLINED = "DECLINED";

    /** 一个群：id、名字（空串 = 未命名）、成员（UUID → 名字，LinkedHashMap 保序）、创建者。 */
    static final class Group {
        private final UUID id;
        private String name;
        private final LinkedHashMap<UUID, String> members = new LinkedHashMap<>();
        private final UUID creator;

        Group(UUID id, String name, UUID creator) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.creator = creator;
        }

        UUID id() {
            return id;
        }

        String name() {
            return name;
        }

        UUID creator() {
            return creator;
        }

        boolean hasMember(UUID uuid) {
            return uuid != null && members.containsKey(uuid);
        }

        Collection<UUID> memberIds() {
            return new LinkedHashSet<>(members.keySet());
        }

        int size() {
            return members.size();
        }

        List<GroupOutbound.Member> memberList() {
            List<GroupOutbound.Member> out = new ArrayList<>(members.size());
            for (Map.Entry<UUID, String> e : members.entrySet()) {
                out.add(new GroupOutbound.Member(e.getKey(), e.getValue()));
            }
            return out;
        }

        @Override
        public String toString() {
            return "Group{id=" + id + ", name='" + name + "', members=" + members.size()
                + ", creator=" + creator + "}";
        }
    }

    private static final Map<UUID, Group> groups = new LinkedHashMap<>();
    /** groupId → 还没回应的被邀请者（纯内存态）。 */
    private static final Map<UUID, LinkedHashSet<UUID>> pendingInvites = new LinkedHashMap<>();

    /** 由 {@link E33ChatPaperPlugin#onEnable()} 注入；卸载时置回 null。 */
    private static Plugin plugin;

    static void init(Plugin owner) {
        plugin = owner;
    }

    /** 本插件实例（{@link PlayerActionRelay} 也用它拿 Server/Logger）—— 卸载后为 null。 */
    static Plugin plugin() {
        return plugin;
    }

    /** 服务器停止/插件卸载：清空内存态（下一次加载是全新的世界）。 */
    static synchronized void reset() {
        groups.clear();
        pendingInvites.clear();
        plugin = null;
    }

    private static void log(String msg) {
        Plugin p = plugin;
        if (p != null) {
            p.getLogger().info("[e33chat] srv | group | " + msg);
        }
    }

    // ================================================================ 查询

    static synchronized Group byId(String groupId) {
        UUID id = GroupWire.parseUuid(groupId);
        return id == null ? null : groups.get(id);
    }

    /** 某个玩家所属的全部群（按创建顺序）。 */
    static synchronized List<Group> groupsOf(UUID player) {
        List<Group> out = new ArrayList<>();
        if (player == null) {
            return out;
        }
        for (Group g : groups.values()) {
            if (g.hasMember(player)) {
                out.add(g);
            }
        }
        return out;
    }

    static synchronized int groupCount() {
        return groups.size();
    }

    /** 全部群（按创建顺序）的只读快照 —— 供诊断/离线校验使用，别拿它改状态。 */
    static synchronized List<Group> allGroups() {
        return new ArrayList<>(groups.values());
    }

    static synchronized int pendingCount() {
        int n = 0;
        for (LinkedHashSet<UUID> set : pendingInvites.values()) {
            n += set.size();
        }
        return n;
    }

    // ================================================================ 发送工具

    /** 单播一条插件消息；玩家刚断线/通道未注册只告警，不影响其他人。 */
    private static void send(Player player, String channel, byte[] payload) {
        if (player == null || payload == null) {
            return;
        }
        Plugin p = plugin;
        if (p == null) {
            return;
        }
        try {
            player.sendPluginMessage(p, channel, payload);
        } catch (Throwable t) {
            log("send failed | channel=" + channel + " | player=" + name(player) + " | " + t);
        }
    }

    private static void sendSync(Player player, Group g) {
        if (player == null || g == null) {
            return;
        }
        send(player, E33Channels.GROUP_SYNC, GroupOutbound.sync(
            g.id().toString(),
            g.name() == null ? "" : g.name(),
            g.creator() == null ? "" : g.creator().toString(),
            g.memberList()));
    }

    private static void sendEvent(Player player, Group g, String kind, String text) {
        if (player == null || g == null) {
            return;
        }
        send(player, E33Channels.GROUP_EVENT, GroupOutbound.event(
            g.id().toString(), kind, text == null ? "" : text));
    }

    private static void sendInviteEvent(Player player, Group g, String kind,
                                       String inviterName, String actorName) {
        if (player == null || g == null) {
            return;
        }
        send(player, E33Channels.GROUP_INVITE_EVENT, GroupOutbound.inviteEvent(
            g.id().toString(),
            g.name() == null ? "" : g.name(),
            inviterName == null ? "" : inviterName,
            kind == null ? "" : kind,
            actorName == null ? "" : actorName));
    }

    /**
     * 把一批玩家登记成「待回应的被邀请者」并逐个发邀请。
     *
     * <p>规则（与 Fabric {@code deliverInvites} 逐条一致）：跳过 null / 邀请者自己 / 已是成员 /
     * 已在 pending 里的人；**离线玩家直接忽略**（不进 pending、不排队）；人数上限按
     * 「成员 + pending」一起算。
     *
     * @return 真正发出邀请的玩家 UUID
     */
    private static List<UUID> deliverInvites(Group g, Player inviter, Collection<UUID> targets) {
        List<UUID> sent = new ArrayList<>();
        if (g == null || targets == null) {
            return sent;
        }
        LinkedHashSet<UUID> pending = pendingInvites.computeIfAbsent(g.id(), k -> new LinkedHashSet<>());
        UUID inviterId = inviter == null ? null : inviter.getUniqueId();
        String inviterName = inviter == null ? "" : inviter.getName();
        for (UUID uuid : targets) {
            if (uuid == null || uuid.equals(inviterId)) {
                continue;
            }
            if (g.hasMember(uuid)) {
                continue;
            }
            if (pending.contains(uuid)) {
                continue;
            }
            if (g.size() + pending.size() >= MAX_MEMBERS) {
                break;
            }
            Player target = online(uuid);
            if (target == null) {
                log("invite skipped (offline) | group=" + g.id() + " | target=" + uuid);
                continue;
            }
            pending.add(uuid);
            sendInviteEvent(target, g, INVITE_INVITE, inviterName, target.getName());
            sent.add(uuid);
        }
        log("invites dispatched | group=" + g.id() + " | inviter=" + inviterName
            + " | sent=" + sent.size() + " | pending=" + pending.size());
        return sent;
    }

    /** 把群快照广播给全体**在线**成员。 */
    private static void broadcastSync(Group g) {
        if (g == null) {
            return;
        }
        int n = 0;
        for (UUID uuid : g.memberIds()) {
            Player p = online(uuid);
            if (p != null) {
                sendSync(p, g);
                n++;
            }
        }
        log("broadcast sync | " + g + " | online=" + n);
    }

    // ================================================================ 操作

    /**
     * 建群：发起者自动进群并成为创建者；其余勾选的人**不再直接进群**，一律走 pending 邀请。
     * 群创建后立刻只含创建者，被邀请者接受后才陆续进来（与 Fabric 同）。
     */
    static synchronized void create(Player creator, GroupInbound.Create payload) {
        if (creator == null || payload == null) {
            return;
        }
        UUID id = UUID.randomUUID();
        String name = GroupWire.clamp(payload.name(), GroupWire.MAX_NAME).trim();
        Group g = new Group(id, name, creator.getUniqueId());
        g.members.put(creator.getUniqueId(), creator.getName());
        groups.put(id, g);
        log("create | creator=" + creator.getName() + " | " + g);

        List<UUID> sent = deliverInvites(g, creator, payload.members());
        log("create | invites sent=" + sent.size() + " | " + g);
        broadcastSync(g);
    }

    static synchronized void rename(Player actor, GroupInbound.Rename payload) {
        Group g = byId(payload == null ? null : payload.groupId());
        if (g == null) {
            log("rename ignored | unknown group");
            return;
        }
        if (actor == null || !g.hasMember(actor.getUniqueId())) {
            log("rename denied | not a member | actor=" + name(actor));
            return;
        }
        g.name = GroupWire.clamp(payload.name(), GroupWire.MAX_NAME).trim();
        log("rename | actor=" + name(actor) + " | " + g);
        broadcastSync(g);
        for (UUID uuid : g.memberIds()) {
            Player p = online(uuid);
            if (p != null) {
                sendEvent(p, g, KIND_RENAMED, g.name());
            }
        }
    }

    /**
     * 邀请玩家进群：**只有创建者能邀请**，普通成员无邀请权。
     *
     * <p>不直接把人并进成员表 —— 只登记 pending 并发 {@code group_invite_event}(kind=INVITE)，
     * 等对方点「接受」才真正进群。权限不符时回一条 {@code group_event}(kind=INVITE_DENIED)
     * 给操作者，群与其状态都不动。
     */
    static synchronized void invite(Player actor, GroupInbound.Invite payload) {
        Group g = byId(payload == null ? null : payload.groupId());
        if (g == null) {
            log("invite ignored | unknown group");
            return;
        }
        if (actor == null || !g.hasMember(actor.getUniqueId())) {
            log("invite denied | not a member | actor=" + name(actor));
            return;
        }
        if (g.creator() == null || !g.creator().equals(actor.getUniqueId())) {
            log("invite denied | actor is not creator | actor=" + name(actor) + " | " + g);
            sendEvent(actor, g, KIND_INVITE_DENIED, g.name());
            return;
        }
        List<UUID> sent = deliverInvites(g, actor, payload.members());
        if (sent.isEmpty()) {
            log("invite no-op | actor=" + name(actor) + " | " + g);
        }
    }

    /**
     * 被邀请者回应邀请：{@code accept=true} 才真正进群。
     *
     * <p>校验链（任一不满足都只记日志、不改任何状态）：群存在 → 我**不是**成员（幂等，
     * 已进群就当重复包丢弃）→ 我确实在 pendingInvites 里。因此客户端伪造 accept
     * 也进不来别人的群。
     */
    static synchronized void respondInvite(Player player, GroupInbound.InviteRespond payload) {
        if (player == null || payload == null) {
            return;
        }
        Group g = byId(payload.groupId());
        if (g == null) {
            log("invite_respond ignored | unknown group | group=" + payload.groupId());
            return;
        }
        UUID me = player.getUniqueId();
        LinkedHashSet<UUID> pending = pendingInvites.get(g.id());
        if (g.hasMember(me)) {
            if (pending != null) {
                pending.remove(me);
            }
            log("invite_respond ignored | already member | player=" + name(player) + " | " + g.id());
            return;
        }
        if (pending == null || !pending.remove(me)) {
            log("invite_respond ignored | not invited | player=" + name(player)
                + " | group=" + g.id() + " | accept=" + payload.accept());
            return;
        }
        String inviterName = nameOf(g.creator(), "");
        Player creator = g.creator() == null ? null : online(g.creator());

        if (!payload.accept()) {
            log("invite declined | player=" + name(player) + " | group=" + g.id()
                + " | pending=" + pending.size());
            if (creator != null) {
                sendInviteEvent(creator, g, INVITE_DECLINED, inviterName, player.getName());
            }
            return;
        }

        if (g.size() >= MAX_MEMBERS) {
            log("invite accept ignored | group full | player=" + name(player) + " | " + g);
            return;
        }
        g.members.put(me, player.getName());
        log("invite accepted | player=" + name(player) + " | " + g);
        broadcastSync(g);
        // 给加入者自己一条「你已被加入群聊 xxx」（复用既有 KIND_INVITED 路径）
        sendEvent(player, g, KIND_INVITED, g.name());
        if (creator != null) {
            sendInviteEvent(creator, g, INVITE_ACCEPTED, inviterName, player.getName());
        }
    }

    static synchronized void kick(Player actor, GroupInbound.Kick payload) {
        Group g = byId(payload == null ? null : payload.groupId());
        if (g == null) {
            log("kick ignored | unknown group");
            return;
        }
        if (actor == null || !g.hasMember(actor.getUniqueId())) {
            log("kick denied | not a member | actor=" + name(actor));
            return;
        }
        UUID target = payload.target();
        if (target == null || !g.hasMember(target)) {
            log("kick no-op | target not in group");
            return;
        }
        if (target.equals(g.creator())) {
            // 创建者不可被踢（否则群会失去归属）；想让他走只能他自己退出或解散
            log("kick denied | target is creator | " + g);
            return;
        }
        g.members.remove(target);
        log("kick | actor=" + name(actor) + " | target=" + target + " | " + g);
        Player kicked = online(target);
        if (kicked != null) {
            sendEvent(kicked, g, KIND_KICKED, g.name());
        }
        if (g.members.isEmpty()) {
            groups.remove(g.id());
            pendingInvites.remove(g.id());
            log("kick -> group empty, removed | " + g.id());
            return;
        }
        broadcastSync(g);
    }

    static synchronized void leave(Player actor, GroupInbound.Leave payload) {
        Group g = byId(payload == null ? null : payload.groupId());
        if (g == null || actor == null) {
            return;
        }
        if (!g.hasMember(actor.getUniqueId())) {
            return;
        }
        g.members.remove(actor.getUniqueId());
        log("leave | actor=" + name(actor) + " | " + g);
        sendEvent(actor, g, KIND_LEFT, g.name());
        if (g.members.isEmpty()) {
            groups.remove(g.id());
            pendingInvites.remove(g.id());
            log("leave -> group empty, removed | " + g.id());
            return;
        }
        broadcastSync(g);
    }

    static synchronized void disband(Player actor, GroupInbound.Disband payload) {
        Group g = byId(payload == null ? null : payload.groupId());
        if (g == null) {
            log("disband ignored | unknown group");
            return;
        }
        if (actor == null || !g.hasMember(actor.getUniqueId())) {
            log("disband denied | not a member | actor=" + name(actor));
            return;
        }
        // 只有创建者能解散（其它成员请用 group_leave 退出）：避免"任何人一键删掉别人的群"
        if (g.creator() != null && !g.creator().equals(actor.getUniqueId())) {
            log("disband denied | actor is not creator | actor=" + name(actor) + " | " + g);
            return;
        }
        groups.remove(g.id());
        pendingInvites.remove(g.id());
        log("disband | actor=" + name(actor) + " | " + g);
        for (UUID uuid : g.memberIds()) {
            Player p = online(uuid);
            if (p != null) {
                sendEvent(p, g, KIND_DISBANDED, g.name());
            }
        }
    }

    /** 转发一条群消息给全体在线成员（**含发送者**）。 */
    static synchronized void relayMessage(Player sender, GroupInbound.Message payload) {
        Group g = byId(payload == null ? null : payload.groupId());
        if (g == null) {
            log("message ignored | unknown group | group="
                + (payload == null ? "null" : payload.groupId()));
            return;
        }
        if (sender == null || !g.hasMember(sender.getUniqueId())) {
            log("message denied | not a member | sender=" + name(sender) + " | " + g);
            return;
        }
        String content = GroupWire.clamp(payload.content(), GroupWire.MAX_CONTENT);
        if (content.isBlank()) {
            return;
        }
        // 客户端可能送明文（旧/别家实现）：不是十进制串就自己哈希，保证两端算法一致
        String msgHash = GroupWire.normalizeHash(payload.msgHash(), content);
        byte[] relay = GroupOutbound.messageRelay(g.id().toString(), sender.getUniqueId(),
            sender.getName(), content, msgHash);
        int n = 0;
        for (UUID uuid : g.memberIds()) {
            Player p = online(uuid);
            if (p == null) {
                continue;
            }
            send(p, E33Channels.GROUP_MESSAGE_RELAY, relay);
            n++;
        }
        log("message | from=" + sender.getName() + " | " + g
            + " | recipients=" + n + " | hash=" + msgHash
            + " | preview=" + McCodec.preview(content));
    }

    /** 登录补发：把该玩家所属的全部群逐个同步下去（客户端因此不需要本地保存成员表）。 */
    static synchronized void syncAllTo(Player player) {
        if (player == null) {
            return;
        }
        List<Group> mine = groupsOf(player.getUniqueId());
        for (Group g : mine) {
            sendSync(player, g);
        }
        log("join sync | player=" + player.getName() + " | groups=" + mine.size()
            + " | totalGroups=" + groups.size());
    }

    // ================================================================ 工具

    private static Player online(UUID uuid) {
        Plugin p = plugin;
        if (p == null || uuid == null) {
            return null;
        }
        try {
            return p.getServer().getPlayer(uuid);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 玩家名字：在线用当前名字，离线沿用成员表里最后已知的名字。 */
    private static String nameOf(UUID uuid, String fallback) {
        Player p = online(uuid);
        if (p != null) {
            return p.getName();
        }
        String f = fallback == null ? "" : fallback.trim();
        return f.isEmpty() ? "?" : f;
    }

    private static String name(Player p) {
        return p == null ? "?" : p.getName();
    }
}
