package com.niuqu.chatbubble.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import com.niuqu.chatbubble.network.GroupCreatePayload;
import com.niuqu.chatbubble.network.GroupDisbandPayload;
import com.niuqu.chatbubble.network.GroupEventPayload;
import com.niuqu.chatbubble.network.GroupInviteEventPayload;
import com.niuqu.chatbubble.network.GroupInvitePayload;
import com.niuqu.chatbubble.network.GroupInviteRespondPayload;
import com.niuqu.chatbubble.network.GroupKickPayload;
import com.niuqu.chatbubble.network.GroupLeavePayload;
import com.niuqu.chatbubble.network.GroupMessagePayload;
import com.niuqu.chatbubble.network.GroupMessageRelayPayload;
import com.niuqu.chatbubble.network.GroupRenamePayload;
import com.niuqu.chatbubble.network.GroupSyncPayload;
import com.niuqu.chatbubble.network.GroupWire;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端群聊注册表（方案 A：**所有群消息都走服务端中继**）。
 *
 * <p>职责：
 * <ul>
 *   <li>保存「群 = 名字 + 成员表（按入群顺序）」；成员表带名字，离线成员也能显示昵称。</li>
 *   <li>校验每个 C2S 操作（发送者必须在群里；踢人还要「目标不是创建者」），
 *       任何异常只记日志、**绝不影响连接**（调用方全 try/catch）。</li>
 *   <li>把变更以 {@link GroupSyncPayload} 广播给全体在线成员；把消息以
 *       {@link GroupMessageRelayPayload} 转发给全体在线成员（含发送者，客户端按 msgHash 去重）。</li>
 *   <li>玩家登录时把「他所属的全部群」逐个 {@code GroupSync} 补发 —— 客户端不需要自己存成员表。</li>
 * </ul>
 *
 * <p>状态落盘在 <b>&lt;world&gt;/serverconfig/e33chat-groups.json</b>（与本项目其它服务端配置同目录、
 * 同 per-world 语义）。落盘失败只在日志里告警，不影响功能（当次运行内仍然是内存态正确）。
 *
 * <p>已知限制（MVP，见 README）：成员表只在服务端，客户端以服务端下发的快照为准；离线玩家收不到
 * 群消息（无离线补发）；跨服/代理不共享状态；群人数上限 {@link GroupWire#MAX_LIST}。
 *
 * <p><b>第 4 轮：进群邀请（pendingInvites）</b>
 * <ul>
 *   <li>建群 / 添加玩家都**不再直接把对方拉进群**：只把被邀请者记进
 *       {@code pendingInvites}（groupId → 待回应者集合），并给他发
 *       {@link GroupInviteEventPayload#KIND_INVITE}，客户端在「邀请者 ↔ 被邀请者」的私聊里
 *       落一条带「接受 / 拒绝」按钮的消息。</li>
 *   <li>被邀请者回 {@link GroupInviteRespondPayload}：接受才真正进群（广播 GroupSync +
 *       给自己发 INVITED 事件），拒绝只回执给创建者，群不变。</li>
 *   <li>**只有创建者能邀请**（{@link #invite} 里校验 sender == creator），普通成员无邀请权；
 *       违规请求回一条 {@link GroupEventPayload#KIND_INVITE_DENIED} 回执，群与其状态都不动。</li>
 *   <li>pendingInvites 是**纯内存态**：服务端重启即丢（被邀请者拿不到按钮响应 = 邀请失效），
 *       这是 MVP 可接受的取舍（需求明确允许）；被邀请者离线时直接忽略，不排离线队列。</li>
 * </ul>
 */
public final class GroupRegistry {
    private GroupRegistry() {}

    /** 群名默认显示值由客户端渲染，这里存空串表示「未命名」。 */
    public static final int MAX_MEMBERS = GroupWire.MAX_LIST;

    /** 一个群：id、名字（空串=未命名）、成员（UUID→名字，LinkedHashMap 保序）、创建者。 */
    public static final class Group {
        final UUID id;
        String name;
        final LinkedHashMap<UUID, String> members = new LinkedHashMap<>();
        final UUID creator;

        Group(UUID id, String name, UUID creator) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.creator = creator;
        }

        public UUID id() { return id; }
        public String name() { return name; }
        public UUID creator() { return creator; }
        public boolean hasMember(UUID uuid) { return uuid != null && members.containsKey(uuid); }
        public Collection<UUID> memberIds() { return new LinkedHashSet<>(members.keySet()); }
        public int size() { return members.size(); }

        @Override
        public String toString() {
            return "Group{id=" + id + ", name='" + name + "', members=" + members.size() + ", creator=" + creator + "}";
        }
    }

    private static final Map<UUID, Group> groups = new LinkedHashMap<>();
    /** groupId → 还没回应的被邀请者（第 4 轮邀请流程；纯内存态，见类注释）。 */
    private static final Map<UUID, LinkedHashSet<UUID>> pendingInvites = new LinkedHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Path storePath;
    private static boolean loaded;

    private static void log(String msg) {
        LogUtils.getLogger().info("[e33chat] srv | group | " + msg);
    }

    // ===== 生命周期 =====

    /** 首次 JOIN 时按当前世界的 serverconfig 目录把群状态读进内存（只读一次）。 */
    public static synchronized void ensureLoaded(MinecraftServer server) {
        if (loaded) return;
        loaded = true;
        try {
            storePath = server.getWorldPath(LevelResource.ROOT)
                .resolve("serverconfig").resolve("e33chat-groups.json");
            loadFromDisk();
        } catch (Throwable t) {
            log("load failed | " + t);
        }
    }

    /** 服务器停止：清空内存态，让下一个世界按自己的文件重新加载。 */
    public static synchronized void reset() {
        groups.clear();
        pendingInvites.clear();
        loaded = false;
        storePath = null;
    }

    @SuppressWarnings("unchecked")
    private static void loadFromDisk() {
        if (storePath == null || !Files.exists(storePath)) return;
        try (Reader r = Files.newBufferedReader(storePath, StandardCharsets.UTF_8)) {
            Map<String, Object> root = GSON.fromJson(r, new TypeToken<Map<String, Object>>() {}.getType());
            if (root == null) return;
            Object listObj = root.get("groups");
            if (!(listObj instanceof List<?> list)) return;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                try {
                    UUID id = UUID.fromString(String.valueOf(m.get("id")));
                    String name = m.get("name") == null ? "" : String.valueOf(m.get("name"));
                    UUID creator = null;
                    try {
                        creator = UUID.fromString(String.valueOf(m.get("creator")));
                    } catch (Throwable ignored) {
                    }
                    Group g = new Group(id, name, creator);
                    Object memObj = m.get("members");
                    if (memObj instanceof List<?> mems) {
                        for (Object mo : mems) {
                            if (!(mo instanceof Map<?, ?> mm)) continue;
                            try {
                                UUID mid = UUID.fromString(String.valueOf(mm.get("uuid")));
                                String mname = mm.get("name") == null ? "" : String.valueOf(mm.get("name"));
                                if (g.members.size() >= MAX_MEMBERS) break;
                                g.members.put(mid, mname);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    if (!g.members.isEmpty()) groups.put(id, g);
                } catch (Throwable ignored) {
                    // 单条损坏只丢这个群
                }
            }
            log("loaded | groups=" + groups.size() + " | file=" + storePath);
        } catch (Throwable t) {
            log("read failed | file=" + storePath + " | " + t);
        }
    }

    private static synchronized void saveToDisk() {
        if (storePath == null) return;
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Group g : groups.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", g.id.toString());
                m.put("name", g.name);
                m.put("creator", g.creator == null ? "" : g.creator.toString());
                List<Map<String, Object>> mems = new ArrayList<>();
                for (Map.Entry<UUID, String> e : g.members.entrySet()) {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("uuid", e.getKey().toString());
                    mm.put("name", e.getValue());
                    mems.add(mm);
                }
                m.put("members", mems);
                list.add(m);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", 1);
            root.put("groups", list);
            Files.createDirectories(storePath.getParent());
            try (Writer w = Files.newBufferedWriter(storePath, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (IOException | RuntimeException t) {
            log("save failed | file=" + storePath + " | " + t);
        }
    }

    // ===== 查询 =====

    public static synchronized Group byId(String groupId) {
        UUID id = GroupWire.parseUuid(groupId);
        return id == null ? null : groups.get(id);
    }

    /** 某个玩家所属的全部群（按创建顺序）。 */
    public static synchronized List<Group> groupsOf(UUID player) {
        List<Group> out = new ArrayList<>();
        if (player == null) return out;
        for (Group g : groups.values()) if (g.hasMember(player)) out.add(g);
        return out;
    }

    public static synchronized int groupCount() { return groups.size(); }

    // ===== 发送工具 =====

    private static void sendSync(ServerPlayer player, Group g) {
        if (player == null || g == null) return;
        List<GroupSyncPayload.Member> members = new ArrayList<>(g.members.size());
        for (Map.Entry<UUID, String> e : g.members.entrySet()) {
            members.add(new GroupSyncPayload.Member(e.getKey(), e.getValue()));
        }
        GroupSyncPayload payload = new GroupSyncPayload(g.id.toString(),
            g.name == null ? "" : g.name,
            g.creator == null ? "" : g.creator.toString(),
            members);
        try {
            // 无条件发送：客户端没注册接收器时 Fabric 的安全路径是「丢弃」（返回 false，不进
            // 原版处理器），不会断开连接。这里不用 canSend 守卫 —— 集成服务器/局域网下
            // canSend 的时序依赖客户端注册声明，漏发会让单机群聊直接不可用。
            ServerPlayNetworking.send(player, payload);
        } catch (Throwable t) {
            log("sendSync failed | player=" + player.getName().getString() + " | " + payload + " | " + t);
        }
    }

    private static void sendEvent(ServerPlayer player, Group g, String kind, String text) {
        if (player == null || g == null) return;
        GroupEventPayload payload = new GroupEventPayload(g.id.toString(), kind, text == null ? "" : text);
        try {
            ServerPlayNetworking.send(player, payload);
        } catch (Throwable t) {
            log("sendEvent failed | player=" + player.getName().getString() + " | " + payload + " | " + t);
        }
    }

    /** 发一条进群邀请事件（kind = INVITE / ACCEPTED / DECLINED）。 */
    private static void sendInviteEvent(ServerPlayer player, Group g, String kind,
                                        String inviterName, String actorName) {
        if (player == null || g == null) return;
        GroupInviteEventPayload payload = new GroupInviteEventPayload(g.id.toString(),
            g.name == null ? "" : g.name, inviterName == null ? "" : inviterName,
            kind == null ? "" : kind, actorName == null ? "" : actorName);
        try {
            ServerPlayNetworking.send(player, payload);
        } catch (Throwable t) {
            log("sendInviteEvent failed | player=" + player.getName().getString()
                + " | " + payload + " | " + t);
        }
    }

    /**
     * 把一批玩家登记成「待回应的被邀请者」并逐个发邀请（第 4 轮）。
     *
     * <p>规则：跳过 null / 邀请者自己 / 已是成员 / 已经在 pending 里的人；**离线玩家直接忽略**
     * （不进 pending、不排队，MVP 明确接受）；人数上限按「成员 + pending」一起算。
     *
     * @return 真正发出邀请的玩家（在线且成功登记）
     */
    private static List<UUID> deliverInvites(MinecraftServer server, Group g, ServerPlayer inviter,
                                             Collection<UUID> targets) {
        List<UUID> sent = new ArrayList<>();
        if (server == null || g == null || targets == null) return sent;
        LinkedHashSet<UUID> pending =
            pendingInvites.computeIfAbsent(g.id, k -> new LinkedHashSet<>());
        UUID inviterId = inviter == null ? null : inviter.getUUID();
        String inviterName = inviter == null ? "" : inviter.getName().getString();
        for (UUID uuid : targets) {
            if (uuid == null || uuid.equals(inviterId)) continue;
            if (g.hasMember(uuid)) continue;
            if (pending.contains(uuid)) continue;
            if (g.size() + pending.size() >= MAX_MEMBERS) break;
            ServerPlayer target = server.getPlayerList().getPlayer(uuid);
            if (target == null) {
                // 离线：忽略（不发送、不排队）—— 需求允许的 MVP 取舍
                log("invite skipped (offline) | group=" + g.id + " | target=" + uuid);
                continue;
            }
            pending.add(uuid);
            sendInviteEvent(target, g, GroupInviteEventPayload.KIND_INVITE, inviterName,
                target.getName().getString());
            sent.add(uuid);
        }
        log("invites dispatched | group=" + g.id + " | inviter=" + inviterName
            + " | sent=" + sent.size() + " | pending=" + pending.size());
        return sent;
    }

    /** 把群快照广播给全体**在线**成员。 */
    private static void broadcastSync(MinecraftServer server, Group g) {
        if (server == null || g == null) return;
        int n = 0;
        for (UUID uuid : g.members.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                sendSync(p, g);
                n++;
            }
        }
        log("broadcast sync | " + g + " | online=" + n);
    }

    /** 玩家名字：在线用当前名字，离线沿用成员表里最后已知的名字。 */
    private static String nameOf(MinecraftServer server, UUID uuid, String fallback) {
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(uuid);
        if (p != null) return p.getName().getString();
        String f = fallback == null ? "" : fallback.trim();
        return f.isEmpty() ? "?" : f;
    }

    // ===== 操作 =====

    /**
     * 建群：发起者自动进群并成为创建者；其余是客户端勾选的人 —— 但**不再直接拉进群**，
     * 一律走「进群邀请」（第 4 轮）。群创建后立刻只含创建者，被邀请者接受后才陆续进来。
     */
    public static synchronized void create(MinecraftServer server, ServerPlayer creator,
                                           GroupCreatePayload payload) {
        if (server == null || creator == null || payload == null) return;
        UUID id = UUID.randomUUID();
        String name = GroupWire.clamp(payload.groupName(), GroupWire.MAX_NAME).trim();
        Group g = new Group(id, name, creator.getUUID());
        g.members.put(creator.getUUID(), creator.getName().getString());
        groups.put(id, g);
        saveToDisk();
        log("create | creator=" + creator.getName().getString() + " | " + g);

        List<UUID> sent = deliverInvites(server, g, creator, payload.members());
        log("create | invites sent=" + sent.size() + " | " + g);
        broadcastSync(server, g);
    }

    public static synchronized void rename(MinecraftServer server, ServerPlayer actor,
                                           GroupRenamePayload payload) {
        Group g = byId(payload == null ? null : payload.groupId());
        if (g == null) { log("rename ignored | unknown group"); return; }
        if (actor == null || !g.hasMember(actor.getUUID())) {
            log("rename denied | not a member | actor=" + name(actor));
            return;
        }
        g.name = GroupWire.clamp(payload.name(), GroupWire.MAX_NAME).trim();
        saveToDisk();
        log("rename | actor=" + name(actor) + " | " + g);
        broadcastSync(server, g);
        for (UUID uuid : g.members.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) sendEvent(p, g, GroupEventPayload.KIND_RENAMED, g.name);
        }
    }

    /**
     * 邀请玩家进群（第 4 轮）：**只有创建者能邀请**，普通成员无邀请权。
     *
     * <p>不再直接把人并进成员表 —— 只登记 pending 并发 {@link GroupInviteEventPayload#KIND_INVITE}，
     * 等对方在私聊里点「接受」才真正进群（见 {@link #respondInvite}）。
     * 权限不符时回一条 {@link GroupEventPayload#KIND_INVITE_DENIED} 给操作者，群与其状态都不动。
     */
    public static synchronized void invite(MinecraftServer server, ServerPlayer actor,
                                            GroupInvitePayload payload) {
        Group g = byId(payload == null ? null : payload.groupId());
        if (g == null) { log("invite ignored | unknown group"); return; }
        if (actor == null || !g.hasMember(actor.getUUID())) {
            log("invite denied | not a member | actor=" + name(actor));
            return;
        }
        // 权限：只有创建者能邀请（需求 3 明确：群成员无邀请权）
        if (g.creator == null || !g.creator.equals(actor.getUUID())) {
            log("invite denied | actor is not creator | actor=" + name(actor) + " | " + g);
            sendEvent(actor, g, GroupEventPayload.KIND_INVITE_DENIED, g.name);
            return;
        }
        List<UUID> sent = deliverInvites(server, g, actor, payload.members());
        if (sent.isEmpty()) log("invite no-op | actor=" + name(actor) + " | " + g);
    }

    /**
     * 被邀请者回应邀请（第 4 轮）：{@code accept=true} 才真正进群。
     *
     * <p>校验链（任一不满足都只记日志、不改任何状态）：群存在 → 我**不是**成员（幂等，已进群
     * 就当重复包丢弃）→ 我确实在 pendingInvites 里。因此客户端伪造 accept 也进不来别人的群。
     */
    public static synchronized void respondInvite(MinecraftServer server, ServerPlayer player,
                                                   GroupInviteRespondPayload payload) {
        if (server == null || player == null || payload == null) return;
        Group g = byId(payload.groupId());
        if (g == null) { log("invite_respond ignored | unknown group | " + payload); return; }
        UUID me = player.getUUID();
        LinkedHashSet<UUID> pending = pendingInvites.get(g.id);
        if (g.hasMember(me)) {
            if (pending != null) pending.remove(me);
            log("invite_respond ignored | already member | player=" + name(player) + " | " + g.id);
            return;
        }
        if (pending == null || !pending.remove(me)) {
            log("invite_respond ignored | not invited | player=" + name(player) + " | " + payload);
            return;
        }
        String inviterName = nameOf(server, g.creator, "");
        ServerPlayer creator = g.creator == null ? null
            : server.getPlayerList().getPlayer(g.creator);

        if (!payload.accept()) {
            log("invite declined | player=" + name(player) + " | group=" + g.id
                + " | pending=" + pending.size());
            if (creator != null) {
                sendInviteEvent(creator, g, GroupInviteEventPayload.KIND_DECLINED,
                    inviterName, player.getName().getString());
            }
            return;
        }

        if (g.size() >= MAX_MEMBERS) {
            log("invite accept ignored | group full | player=" + name(player) + " | " + g);
            return;
        }
        g.members.put(me, player.getName().getString());
        saveToDisk();
        log("invite accepted | player=" + name(player) + " | " + g);
        broadcastSync(server, g);
        // 给加入者自己一条「你已被加入群聊 xxx」（复用既有 KIND_INVITED 路径）
        sendEvent(player, g, GroupEventPayload.KIND_INVITED, g.name);
        if (creator != null) {
            sendInviteEvent(creator, g, GroupInviteEventPayload.KIND_ACCEPTED,
                inviterName, player.getName().getString());
        }
    }

    public static synchronized void kick(MinecraftServer server, ServerPlayer actor,
                                          GroupKickPayload payload) {
        Group g = byId(payload == null ? null : payload.groupId());
        if (g == null) { log("kick ignored | unknown group"); return; }
        if (actor == null || !g.hasMember(actor.getUUID())) {
            log("kick denied | not a member | actor=" + name(actor));
            return;
        }
        UUID target = payload.target();
        if (target == null || !g.hasMember(target)) { log("kick no-op | target not in group"); return; }
        if (target.equals(g.creator)) {
            // 创建者不可被踢（否则群会失去归属）；想让他走只能他自己退出或解散
            log("kick denied | target is creator | " + g);
            return;
        }
        g.members.remove(target);
        saveToDisk();
        log("kick | actor=" + name(actor) + " | target=" + target + " | " + g);
        ServerPlayer kicked = server.getPlayerList().getPlayer(target);
        if (kicked != null) sendEvent(kicked, g, GroupEventPayload.KIND_KICKED, g.name);
        if (g.members.isEmpty()) {
            groups.remove(g.id);
            pendingInvites.remove(g.id);
            saveToDisk();
            log("kick -> group empty, removed | " + g.id);
            return;
        }
        broadcastSync(server, g);
    }

    public static synchronized void leave(MinecraftServer server, ServerPlayer actor,
                                           GroupLeavePayload payload) {
        Group g = byId(payload == null ? null : payload.groupId());
        if (g == null || actor == null) return;
        if (!g.hasMember(actor.getUUID())) return;
        g.members.remove(actor.getUUID());
        log("leave | actor=" + name(actor) + " | " + g);
        sendEvent(actor, g, GroupEventPayload.KIND_LEFT, g.name);
        if (g.members.isEmpty()) {
            groups.remove(g.id);
            pendingInvites.remove(g.id);
            saveToDisk();
            log("leave -> group empty, removed | " + g.id);
            return;
        }
        saveToDisk();
        broadcastSync(server, g);
    }

    public static synchronized void disband(MinecraftServer server, ServerPlayer actor,
                                             GroupDisbandPayload payload) {
        Group g = byId(payload == null ? null : payload.groupId());
        if (g == null) { log("disband ignored | unknown group"); return; }
        if (actor == null || !g.hasMember(actor.getUUID())) {
            log("disband denied | not a member | actor=" + name(actor));
            return;
        }
        // 只有创建者能解散（其它成员请用 group_leave 退出）：避免"任何人一键删掉别人的群"
        if (g.creator != null && !g.creator.equals(actor.getUUID())) {
            log("disband denied | actor is not creator | actor=" + name(actor) + " | " + g);
            return;
        }
        groups.remove(g.id);
        pendingInvites.remove(g.id);
        saveToDisk();
        log("disband | actor=" + name(actor) + " | " + g);
        for (UUID uuid : g.members.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) sendEvent(p, g, GroupEventPayload.KIND_DISBANDED, g.name);
        }
    }

    /** 转发一条群消息给全体在线成员（含发送者）。 */
    public static synchronized void relayMessage(MinecraftServer server, ServerPlayer sender,
                                                  GroupMessagePayload payload) {
        Group g = byId(payload == null ? null : payload.groupId());
        if (g == null) { log("message ignored | unknown group | group=" + (payload == null ? "null" : payload.groupId())); return; }
        if (sender == null || !g.hasMember(sender.getUUID())) {
            log("message denied | not a member | sender=" + name(sender) + " | " + g);
            return;
        }
        String content = GroupWire.clamp(payload.content(), GroupWire.MAX_CONTENT);
        if (content.isBlank()) return;
        String msgHash = GroupWire.clamp(payload.msgHash(), 64);
        // 客户端可能送明文（旧/别家实现）：不是十进制串就自己哈希，保证两端算法一致
        if (msgHash.isEmpty() || !msgHash.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '-')) {
            msgHash = String.valueOf(content.hashCode());
        }
        GroupMessageRelayPayload relay = new GroupMessageRelayPayload(g.id.toString(),
            sender.getUUID(), sender.getName().getString(), content, msgHash);
        int n = 0;
        for (UUID uuid : g.members.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p == null) continue;
            try {
                ServerPlayNetworking.send(p, relay);
                n++;
            } catch (Throwable t) {
                log("relay failed | player=" + p.getName().getString() + " | " + t);
            }
        }
        log("message | from=" + sender.getName().getString() + " | " + g
            + " | recipients=" + n + " | hash=" + msgHash
            + " | preview=" + preview(content));
    }

    /** 登录补发：把该玩家所属的全部群逐个同步下去（客户端因此不需要本地保存成员表）。 */
    public static synchronized void syncAllTo(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) return;
        ensureLoaded(server);
        List<Group> mine = groupsOf(player.getUUID());
        for (Group g : mine) sendSync(player, g);
        if (!mine.isEmpty()) {
            log("join sync | player=" + player.getName().getString() + " | groups=" + mine.size());
        }
    }

    private static String name(ServerPlayer p) {
        return p == null ? "?" : p.getName().getString();
    }

    private static String preview(String s) {
        if (s == null || s.isEmpty()) return "";
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= 40 ? one : one.substring(0, 40) + "...";
    }
}
