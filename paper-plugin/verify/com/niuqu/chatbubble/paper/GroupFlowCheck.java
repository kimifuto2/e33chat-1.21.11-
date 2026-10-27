package com.niuqu.chatbubble.paper;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * <b>群聊 / 动作后端的离线状态机校验</b>（不需要真实服务器、不需要客户端）。
 *
 * <p>为什么需要它：Paper 端的全部逻辑都在 {@link GroupRegistry} / {@link PlayerActionRelay} 里，
 * 它们只依赖 Bukkit 的三个接口（{@code Plugin} / {@code Server} / {@code Player}）。用
 * {@link Proxy} 造出假玩家/假服务端，就能把「建群 → 邀请(pending) → 接受 → 发消息 → 改名 →
 * 踢人 → 退群/解散」这条状态机以及动作的三种 scope **真跑一遍**，并用本插件自己的解码器
 * 把发出去的字节读回来断言字段。
 *
 * <p>覆盖的语义细则（每一条都对应 Fabric {@code GroupRegistry} / {@code PlayerActionRelay}
 * 的一个分支，不是自己发明的规则）：
 * <ul>
 *   <li>建群后成员表**只有创建者**，被勾选的人是 pending 邀请（不是直接进群）；</li>
 *   <li>被邀请者 accept 才进群；未在 pending 里的第三方伪造 accept 无效；</li>
 *   <li>**仅创建者**能邀请/解散；**任何成员**能改名/踢人；**创建者不可被踢**；</li>
 *   <li>群消息 relay 发给全体在线成员（含发送者），msgHash 不是十进制串时按正文哈希补齐；</li>
 *   <li>非成员收发群消息一律丢弃（不广播、不改状态）；</li>
 *   <li>动作：已知 verbId 用服务端权威词条（忽略客户端 customText）、**不回给发送者**、
 *       scope=public/group/private 各自只发给范围内的人。</li>
 * </ul>
 */
public final class GroupFlowCheck {

    private static int checks = 0;
    private static final List<String> failures = new ArrayList<>();
    /** 服务端日志（假 Logger 收进来，便于断言"拒绝时只记日志不改状态"）。 */
    private static final List<String> serverLog = new ArrayList<>();

    // ---------------------------------------------------------------- 假服务端

    /** 一条已发出的插件消息。 */
    static final class Sent {
        final String channel;
        final byte[] data;

        Sent(String channel, byte[] data) {
            this.channel = channel;
            this.data = data;
        }
    }

    /** 假玩家：UUID + 名字 + 收到的消息列表。 */
    static final class FakePlayer {
        final UUID id;
        final String name;
        final List<Sent> inbox = new ArrayList<>();

        FakePlayer(String name) {
            this.id = UUID.nameUUIDFromBytes(("e33chat-test:" + name).getBytes());
            this.name = name;
        }

        List<Sent> of(String channel) {
            List<Sent> out = new ArrayList<>();
            for (Sent s : inbox) {
                if (s.channel.equals(channel)) {
                    out.add(s);
                }
            }
            return out;
        }

        void clear() {
            inbox.clear();
        }
    }

    public static void main(String[] args) throws Exception {
        Map<UUID, FakePlayer> byId = new LinkedHashMap<>();
        Map<String, FakePlayer> byName = new LinkedHashMap<>();
        FakePlayer alice = new FakePlayer("Alice");
        FakePlayer bob = new FakePlayer("Bob");
        FakePlayer carol = new FakePlayer("Carol");
        for (FakePlayer p : List.of(alice, bob, carol)) {
            byId.put(p.id, p);
            byName.put(p.name, p);
        }

        Player aliceP = playerProxy(alice);
        Player bobP = playerProxy(bob);
        Player carolP = playerProxy(carol);
        Plugin plugin = pluginProxy(byId, byName);

        GroupRegistry.reset();
        // 第 1~6 阶段只验状态机：storeFile=null → 纯内存，行为必须与 3.1.0（无持久化）完全一致。
        GroupRegistry.init(plugin, null);

        UUID gid = createAndInvite(alice, aliceP, bob, bobP, carol);
        acceptInvite(alice, bob, bobP, carol, carolP, gid);
        relayMessage(alice, aliceP, bob, carol, gid);
        permissions(alice, aliceP, bob, bobP, carol, gid);
        kickAndDisband(alice, aliceP, bob, bobP, gid);
        actions(alice, aliceP, bob, bobP, carol, carolP);
        persistence(alice, aliceP, bob, bobP, plugin);

        System.out.println();
        System.out.println("checks run : " + checks);
        System.out.println("failures   : " + failures.size());
        for (String failure : failures) {
            System.out.println("  FAIL " + failure);
        }
        System.out.println(failures.isEmpty()
            ? "RESULT: ALL GROUP/ACTION FLOW CHECKS PASSED"
            : "RESULT: FAILED");
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 1. 建群 + 邀请

    private static UUID createAndInvite(FakePlayer alice, Player aliceP,
                                         FakePlayer bob, Player bobP, FakePlayer carol) {
        bob.clear();
        carol.clear();
        GroupRegistry.create(aliceP, new GroupInbound.Create("周末开黑群", List.of(bob.id, carol.id)));

        check("create -> one group", GroupRegistry.groupCount() == 1);
        GroupRegistry.Group g = onlyGroup();
        check("create -> creator is the only member", g.size() == 1 && g.hasMember(alice.id));
        check("create -> creator recorded", alice.id.equals(g.creator()));
        check("create -> name kept", "周末开黑群".equals(g.name()));
        check("create -> bob is pending (not a member)", !g.hasMember(bob.id));
        check("create -> pending count == 2", GroupRegistry.pendingCount() == 2);

        // 创建者收到 group_sync（1 名成员）
        List<Sent> syncs = alice.of(E33Channels.GROUP_SYNC);
        check("create -> creator got 1 group_sync", syncs.size() == 1);
        Sync sync = Sync.decode(syncs.get(0).data);
        check("create -> group_sync fields",
            sync.groupId.equals(g.id().toString()) && "周末开黑群".equals(sync.name)
                && alice.id.toString().equals(sync.creator) && sync.members.size() == 1
                && alice.id.equals(sync.members.get(0)[0]));

        // 被邀请者收到 group_invite_event(INVITE)，不带 group_sync
        check("create -> bob got 0 group_sync", bob.of(E33Channels.GROUP_SYNC).isEmpty());
        List<Sent> invites = bob.of(E33Channels.GROUP_INVITE_EVENT);
        check("create -> bob got 1 invite event", invites.size() == 1);
        InviteEvent ie = InviteEvent.decode(invites.get(0).data);
        check("create -> INVITE kind + inviter + name",
            "INVITE".equals(ie.kind) && "Alice".equals(ie.inviterName)
                && "周末开黑群".equals(ie.groupName) && "Bob".equals(ie.actorName)
                && ie.groupId.equals(g.id().toString()));

        // 离线/非成员场景：carol 也在线所以各一条
        check("create -> carol got 1 invite event", carol.of(E33Channels.GROUP_INVITE_EVENT).size() == 1);

        // 登录补发：Alice 重新 JOIN 应收到自己所属群的 sync；Bob（还没进群）收到 0 条
        alice.clear();
        GroupRegistry.syncAllTo(aliceP);
        GroupRegistry.syncAllTo(bobP);
        check("join sync -> member gets 1 group_sync", alice.of(E33Channels.GROUP_SYNC).size() == 1);
        check("join sync -> pending player gets 0 group_sync", bob.of(E33Channels.GROUP_SYNC).isEmpty());
        return g.id();
    }

    // ---------------------------------------------------------------- 2. 接受邀请

    private static void acceptInvite(FakePlayer alice, FakePlayer bob, Player bobP,
                                     FakePlayer carol, Player carolP, UUID gid) {
        alice.clear();
        bob.clear();
        GroupRegistry.respondInvite(bobP, new GroupInbound.InviteRespond(gid.toString(), true));

        GroupRegistry.Group g = onlyGroup();
        check("accept -> bob is a member", g.hasMember(bob.id) && g.size() == 2);
        check("accept -> pending cleared for bob", GroupRegistry.pendingCount() == 1);
        check("accept -> bob got 1 group_sync", bob.of(E33Channels.GROUP_SYNC).size() == 1);
        Sync sync = Sync.decode(bob.of(E33Channels.GROUP_SYNC).get(0).data);
        check("accept -> sync carries both members in join order",
            sync.members.size() == 2 && sync.members.get(0)[0].equals(alice.id)
                && sync.members.get(1)[0].equals(bob.id));
        check("accept -> bob got INVITED event",
            "INVITED".equals(Event.decode(bob.of(E33Channels.GROUP_EVENT).get(0).data).kind));
        check("accept -> creator got ACCEPTED invite event",
            "ACCEPTED".equals(InviteEvent.decode(
                alice.of(E33Channels.GROUP_INVITE_EVENT).get(0).data).kind));
        check("accept -> other member also got group_sync", alice.of(E33Channels.GROUP_SYNC).size() == 1);

        // 伪造 accept：从未被邀请的 Dave 拿到 groupId 也无法进群
        FakePlayer dave = new FakePlayer("Dave");
        Player daveP = playerProxy(dave);
        GroupRegistry.respondInvite(daveP, new GroupInbound.InviteRespond(gid.toString(), true));
        check("forged accept from never-invited player -> still 2 members", onlyGroup().size() == 2);
        check("forged accept -> no packets", dave.of(E33Channels.GROUP_SYNC).isEmpty());

        // 重复 accept（已是成员）幂等
        GroupRegistry.respondInvite(bobP, new GroupInbound.InviteRespond(gid.toString(), true));
        check("repeat accept is idempotent", onlyGroup().size() == 2);

        // Carol 拒绝创建时的那次邀请 -> 群不变，pending 清空，创建者收到 DECLINED
        alice.clear();
        carol.clear();
        GroupRegistry.respondInvite(carolP, new GroupInbound.InviteRespond(gid.toString(), false));
        check("decline -> group unchanged", onlyGroup().size() == 2);
        check("decline -> pending now empty", GroupRegistry.pendingCount() == 0);
        check("decline -> creator got DECLINED invite event",
            "DECLINED".equals(InviteEvent.decode(
                alice.of(E33Channels.GROUP_INVITE_EVENT).get(0).data).kind));
        check("decline -> declined player gets no group_sync", carol.of(E33Channels.GROUP_SYNC).isEmpty());
    }

    // ---------------------------------------------------------------- 3. 群消息

    private static void relayMessage(FakePlayer alice, Player aliceP, FakePlayer bob, FakePlayer carol, UUID gid) {
        alice.clear();
        bob.clear();
        carol.clear();
        // msgHash 不是十进制串 -> 服务端必须按正文自己算（与 Fabric 同一条归一化逻辑）
        GroupRegistry.relayMessage(aliceP, new GroupInbound.Message(gid.toString(), "你好 ✅", "not-a-number"));

        check("message -> sender also receives relay (dedupe by hash)", alice.of(E33Channels.GROUP_MESSAGE_RELAY).size() == 1);
        check("message -> other member receives relay", bob.of(E33Channels.GROUP_MESSAGE_RELAY).size() == 1);
        check("message -> non-member receives nothing", carol.of(E33Channels.GROUP_MESSAGE_RELAY).isEmpty());

        Relay relay = Relay.decode(bob.of(E33Channels.GROUP_MESSAGE_RELAY).get(0).data);
        check("message -> relay fields",
            relay.groupId.equals(gid.toString()) && alice.id.equals(relay.senderUUID)
                && "Alice".equals(relay.senderName) && "你好 ✅".equals(relay.content));
        check("message -> msgHash normalized to content.hashCode()",
            relay.msgHash.equals(String.valueOf("你好 ✅".hashCode())));

        // 合法十进制 hash 原样保留
        alice.clear();
        GroupRegistry.relayMessage(aliceP, new GroupInbound.Message(gid.toString(), "hi", "-12345"));
        check("message -> decimal hash kept as-is",
            "-12345".equals(Relay.decode(alice.of(E33Channels.GROUP_MESSAGE_RELAY).get(0).data).msgHash));

        // 非成员发群消息 -> 直接丢弃（成员侧不应多出任何 relay）
        alice.clear();
        bob.clear();
        carol.clear();
        GroupRegistry.relayMessage(playerProxy(carol), new GroupInbound.Message(gid.toString(), "外人也来", "1"));
        check("message -> non-member sender denied (no relay)",
            carol.of(E33Channels.GROUP_MESSAGE_RELAY).isEmpty()
                && bob.of(E33Channels.GROUP_MESSAGE_RELAY).isEmpty()
                && alice.of(E33Channels.GROUP_MESSAGE_RELAY).isEmpty());

        // 空白正文 -> 丢弃
        bob.clear();
        GroupRegistry.relayMessage(aliceP, new GroupInbound.Message(gid.toString(), "   ", "2"));
        check("message -> blank content dropped", bob.of(E33Channels.GROUP_MESSAGE_RELAY).isEmpty());
    }

    // ---------------------------------------------------------------- 4. 权限

    private static void permissions(FakePlayer alice, Player aliceP, FakePlayer bob, Player bobP,
                                    FakePlayer carol, UUID gid) {
        // 非创建者邀请 -> INVITE_DENIED 回执，群状态不变
        bob.clear();
        GroupRegistry.invite(bobP, new GroupInbound.Invite(gid.toString(),
            List.of(UUID.nameUUIDFromBytes("e33chat-test:Carol".getBytes()))));
        check("invite -> non-creator denied with INVITE_DENIED event",
            bob.of(E33Channels.GROUP_INVITE_EVENT).isEmpty()
                && "INVITE_DENIED".equals(Event.decode(
                    bob.of(E33Channels.GROUP_EVENT).get(0).data).kind));
        check("invite -> non-creator did not add pending", GroupRegistry.pendingCount() == 0);

        // 创建者邀请 -> 只为在线的 Carol 登记 pending 并发 INVITE
        carol.clear();
        GroupRegistry.invite(aliceP, new GroupInbound.Invite(gid.toString(),
            List.of(UUID.nameUUIDFromBytes("e33chat-test:Carol".getBytes()))));
        check("invite -> creator can invite", carol.of(E33Channels.GROUP_INVITE_EVENT).size() == 1);
        check("invite -> pending now 1", GroupRegistry.pendingCount() == 1);

        // 离开 pending：Carol 拒绝，回到 0
        GroupRegistry.respondInvite(playerProxy(carol), new GroupInbound.InviteRespond(gid.toString(), false));
        check("invite -> decline clears pending", GroupRegistry.pendingCount() == 0);

        // 改名：任何成员都可以
        alice.clear();
        bob.clear();
        GroupRegistry.rename(bobP, new GroupInbound.Rename(gid.toString(), "改名了"));
        check("rename -> any member can rename", "改名了".equals(onlyGroup().name()));
        check("rename -> both members get group_sync",
            alice.of(E33Channels.GROUP_SYNC).size() == 1 && bob.of(E33Channels.GROUP_SYNC).size() == 1);
        check("rename -> both get RENAMED event",
            "RENAMED".equals(Event.decode(alice.of(E33Channels.GROUP_EVENT).get(0).data).kind)
                && "改名了".equals(Event.decode(bob.of(E33Channels.GROUP_EVENT).get(0).data).text));
        check("rename -> sync name is the new one",
            "改名了".equals(Sync.decode(alice.of(E33Channels.GROUP_SYNC).get(0).data).name));

        // 非成员改名 / 踢人 / 解散 -> 全部无效
        carol.clear();
        GroupRegistry.rename(playerProxy(carol), new GroupInbound.Rename(gid.toString(), "外人改名"));
        check("rename -> non-member denied", "改名了".equals(onlyGroup().name()));

        GroupRegistry.kick(playerProxy(carol), new GroupInbound.Kick(gid.toString(), bob.id));
        check("kick -> non-member denied", onlyGroup().hasMember(bob.id));

        GroupRegistry.disband(playerProxy(carol), new GroupInbound.Disband(gid.toString()));
        check("disband -> non-member denied", GroupRegistry.groupCount() == 1);

        // 非创建者解散 -> 拒绝
        bob.clear();
        GroupRegistry.disband(bobP, new GroupInbound.Disband(gid.toString()));
        check("disband -> non-creator denied", GroupRegistry.groupCount() == 1);

        // 创建者不可被踢
        alice.clear();
        bob.clear();
        GroupRegistry.kick(bobP, new GroupInbound.Kick(gid.toString(), alice.id));
        check("kick -> creator cannot be kicked", onlyGroup().hasMember(alice.id));
        check("kick -> creator denied silently (no packets)", alice.of(E33Channels.GROUP_EVENT).isEmpty());
    }

    // ---------------------------------------------------------------- 5. 踢人 / 退群 / 解散

    private static void kickAndDisband(FakePlayer alice, Player aliceP, FakePlayer bob, Player bobP, UUID gid) {
        alice.clear();
        bob.clear();
        GroupRegistry.kick(aliceP, new GroupInbound.Kick(gid.toString(), bob.id));
        check("kick -> target removed", onlyGroup().size() == 1 && !onlyGroup().hasMember(bob.id));
        check("kick -> target gets KICKED event",
            "KICKED".equals(Event.decode(bob.of(E33Channels.GROUP_EVENT).get(0).data).kind));
        check("kick -> remaining member gets group_sync", alice.of(E33Channels.GROUP_SYNC).size() == 1);
        check("kick -> kicked player gets no group_sync", bob.of(E33Channels.GROUP_SYNC).isEmpty());

        // Bob 已不在群里 -> 退群是无操作
        bob.clear();
        GroupRegistry.leave(bobP, new GroupInbound.Leave(gid.toString()));
        check("leave -> non-member no-op", GroupRegistry.groupCount() == 1 && bob.of(E33Channels.GROUP_EVENT).isEmpty());

        // 解散（创建者）
        alice.clear();
        GroupRegistry.disband(aliceP, new GroupInbound.Disband(gid.toString()));
        check("disband -> group removed", GroupRegistry.groupCount() == 0);
        check("disband -> creator gets DISBANDED event",
            "DISBANDED".equals(Event.decode(alice.of(E33Channels.GROUP_EVENT).get(0).data).kind));

        // 群空自动删除：新建群 -> 创建者退群
        alice.clear();
        GroupRegistry.create(aliceP, new GroupInbound.Create("临时群", List.of()));
        UUID tmp = onlyGroup().id();
        GroupRegistry.leave(aliceP, new GroupInbound.Leave(tmp.toString()));
        check("leave -> empty group auto-removed", GroupRegistry.groupCount() == 0);
        check("leave -> leaver gets LEFT event",
            "LEFT".equals(Event.decode(alice.of(E33Channels.GROUP_EVENT).get(0).data).kind));
    }

    // ---------------------------------------------------------------- 6. 动作

    private static void actions(FakePlayer alice, Player aliceP, FakePlayer bob, Player bobP,
                                FakePlayer carol, Player carolP) {
        // 先重建一个包含 Alice+Bob 的群，用于 group scope
        alice.clear();
        bob.clear();
        carol.clear();
        GroupRegistry.create(aliceP, new GroupInbound.Create("动作群", List.of(bob.id)));
        UUID gid = onlyGroup().id();
        GroupRegistry.respondInvite(bobP, new GroupInbound.InviteRespond(gid.toString(), true));
        alice.clear();
        bob.clear();
        carol.clear();

        // ---- public：除发送者外全服
        PlayerActionRelay.handle(aliceP, new PlayerActionPayloads.Inbound(
            bob.id.toString(), "Bob", "pat", "伪造词条", "拍了拍的正文", "public", ""));
        check("action public -> target receives", bob.of(E33Channels.PLAYER_ACTION_RELAY).size() == 1);
        check("action public -> bystander receives", carol.of(E33Channels.PLAYER_ACTION_RELAY).size() == 1);
        check("action public -> sender does NOT receive", alice.of(E33Channels.PLAYER_ACTION_RELAY).isEmpty());
        ActionRelay relay = ActionRelay.decode(bob.of(E33Channels.PLAYER_ACTION_RELAY).get(0).data);
        check("action -> senderUUID/senderName authoritative",
            alice.id.equals(relay.senderUUID) && "Alice".equals(relay.senderName));
        check("action -> verb is server-authoritative (customText ignored)", "拍了拍".equals(relay.verbText));
        check("action -> target resolved by UUID", bob.id.toString().equals(relay.targetUUID)
            && "Bob".equals(relay.targetName));
        check("action -> scope + empty groupId", "public".equals(relay.scope) && relay.groupId.isEmpty());

        // ---- private：只有目标
        alice.clear();
        bob.clear();
        carol.clear();
        PlayerActionRelay.handle(aliceP, new PlayerActionPayloads.Inbound(
            bob.id.toString(), "Bob", "hug", "", "私聊抱一下", "private", ""));
        check("action private -> target only",
            bob.of(E33Channels.PLAYER_ACTION_RELAY).size() == 1
                && carol.of(E33Channels.PLAYER_ACTION_RELAY).isEmpty()
                && alice.of(E33Channels.PLAYER_ACTION_RELAY).isEmpty());

        // ---- group：只有群成员（不含发送者）
        alice.clear();
        bob.clear();
        carol.clear();
        PlayerActionRelay.handle(aliceP, new PlayerActionPayloads.Inbound(
            bob.id.toString(), "Bob", "poke", "", "群里戳一下", "group", gid.toString()));
        check("action group -> member receives", bob.of(E33Channels.PLAYER_ACTION_RELAY).size() == 1);
        check("action group -> non-member receives nothing", carol.of(E33Channels.PLAYER_ACTION_RELAY).isEmpty());
        check("action group -> groupId echoed",
            gid.toString().equals(ActionRelay.decode(
                bob.of(E33Channels.PLAYER_ACTION_RELAY).get(0).data).groupId));

        // ---- 非成员在 group scope 里发动作 -> 拒绝（只记日志）
        bob.clear();
        carol.clear();
        PlayerActionRelay.handle(carolP, new PlayerActionPayloads.Inbound(
            bob.id.toString(), "Bob", "pat", "", "外人动作", "group", gid.toString()));
        check("action group -> non-member sender denied",
            bob.of(E33Channels.PLAYER_ACTION_RELAY).isEmpty());

        // ---- 未知 verbId -> 拒绝
        bob.clear();
        PlayerActionRelay.handle(aliceP, new PlayerActionPayloads.Inbound(
            bob.id.toString(), "Bob", "nope", "", "未知动作", "public", ""));
        check("action -> unknown verb rejected", bob.of(E33Channels.PLAYER_ACTION_RELAY).isEmpty());

        // ---- 自定义词条 + 空正文 -> 拒绝
        bob.clear();
        PlayerActionRelay.handle(aliceP, new PlayerActionPayloads.Inbound(
            bob.id.toString(), "Bob", "custom", "摸了摸", "  ", "public", ""));
        check("action -> blank content rejected", bob.of(E33Channels.PLAYER_ACTION_RELAY).isEmpty());

        // ---- 自定义词条正常路径
        bob.clear();
        PlayerActionRelay.handle(aliceP, new PlayerActionPayloads.Inbound(
            bob.id.toString(), "Bob", "custom", "摸了摸", "自定义正文", "public", ""));
        check("action custom -> sanitized custom verb",
            "摸了摸".equals(ActionRelay.decode(
                bob.of(E33Channels.PLAYER_ACTION_RELAY).get(0).data).verbText));

        // ---- 目标 UUID 无效时按名字兜底
        bob.clear();
        PlayerActionRelay.handle(aliceP, new PlayerActionPayloads.Inbound(
            "00000000-0000-0000-0000-000000000000", "Bob", "rub", "", "名字兜底", "private", ""));
        check("action -> nil UUID falls back to exact name",
            bob.of(E33Channels.PLAYER_ACTION_RELAY).size() == 1);
    }

    // ---------------------------------------------------------------- 7. 持久化

    /**
     * 落盘层（{@link GroupStore}）的离线校验：用临时目录当「世界目录」，把
     * 「加载 → 变更即保存 → 模拟重启（reset + init 同一个文件）→ 再读回」整条链路真跑一遍。
     *
     * <p>覆盖：
     * <ul>
     *   <li>文件不存在 = 空（不报错、不创建文件）；</li>
     *   <li>每次状态变更后文件立刻更新，且原子写用的临时文件不残留；</li>
     *   <li>JSON 字段名与 Fabric Gson 输出一致（id/name/creator/members/uuid），
     *       中文与 {@code < & >} 原样落盘（对应 Gson 的 disableHtmlEscaping）；</li>
     *   <li>改名 / 加人 / 踢人 / 解散在「重启」后都保持（解散不复活）；</li>
     *   <li>pending 邀请跨重启存活，玩家上线时补发（且不重复发 group_sync）；</li>
     *   <li>能读 Fabric 写出的文件（没有 pendingInvites 键、creator 是空串、空成员群被跳过）；</li>
     *   <li>坏文件只记日志、不崩、不覆盖；保存失败时旧文件不被破坏、内存态仍然更新。</li>
     * </ul>
     */
    private static void persistence(FakePlayer alice, Player aliceP, FakePlayer bob, Player bobP,
                                    Plugin plugin) throws Exception {
        Path dir = Files.createTempDirectory("e33chat-groups");
        Path file = dir.resolve(GroupStore.DIR_NAME).resolve(GroupStore.FILE_NAME);
        Path tmp = file.resolveSibling(GroupStore.FILE_NAME + GroupStore.TEMP_SUFFIX);
        try {
            // ---- 1. 文件不存在 = 空
            GroupRegistry.reset();
            GroupRegistry.init(plugin, file);
            check("store -> missing file loads empty",
                GroupRegistry.groupCount() == 0 && GroupRegistry.pendingCount() == 0);
            check("store -> load does not create the file", !Files.exists(file));

            // ---- 2. 建群后立刻落盘（原子写的临时文件不残留）
            alice.clear();
            bob.clear();
            GroupRegistry.create(aliceP, new GroupInbound.Create("持久<&>化群", List.of(bob.id)));
            UUID gid = onlyGroup().id();
            check("store -> create writes the file", Files.isRegularFile(file));
            check("store -> no temp file left", !Files.exists(tmp));
            String json = readText(file);
            check("store -> json has version + groups",
                json.contains("\"version\": 1") && json.contains("\"groups\": ["));
            check("store -> fabric field names id/name/creator/members/uuid",
                json.contains("\"id\": \"") && json.contains("\"name\": \"")
                    && json.contains("\"creator\": \"") && json.contains("\"members\": [")
                    && json.contains("\"uuid\": \""));
            check("store -> utf8 raw + no html escaping (Gson disableHtmlEscaping)",
                json.contains("持久<&>化群") && !json.contains("\\u003c"));
            check("store -> pending invites persisted",
                json.contains("\"pendingInvites\": [") && json.contains(bob.id.toString()));
            check("store -> save is logged after create", logged("store saved | via=create"));
            System.out.println("---- sample store file (after create) ----");
            System.out.println(json);

            // ---- 3. 模拟重启：清内存 → 同一个文件重新加载
            GroupRegistry.reset();
            GroupRegistry.init(plugin, file);
            GroupRegistry.Group g = onlyGroup();
            check("store -> reload keeps name/creator",
                "持久<&>化群".equals(g.name()) && alice.id.equals(g.creator()));
            check("store -> reload keeps member name",
                g.size() == 1 && "Alice".equals(g.memberList().get(0).name()));
            check("store -> pending survives restart", GroupRegistry.pendingCount() == 1);

            // ---- 4. pending 在登录时补发（重启后邀请按钮还能回来）
            bob.clear();
            GroupRegistry.syncAllTo(bobP);
            List<Sent> invites = bob.of(E33Channels.GROUP_INVITE_EVENT);
            check("store -> pending invite replayed on join", invites.size() == 1);
            InviteEvent replay = InviteEvent.decode(invites.get(0).data);
            check("store -> replayed invite fields",
                "INVITE".equals(replay.kind) && "Alice".equals(replay.inviterName)
                    && "持久<&>化群".equals(replay.groupName) && "Bob".equals(replay.actorName)
                    && gid.toString().equals(replay.groupId));
            check("store -> replay sends no group_sync", bob.of(E33Channels.GROUP_SYNC).isEmpty());

            // ---- 5. 接受邀请 + 改名 → 重启后都保持
            GroupRegistry.respondInvite(bobP, new GroupInbound.InviteRespond(gid.toString(), true));
            GroupRegistry.rename(aliceP, new GroupInbound.Rename(gid.toString(), "改名后"));
            GroupRegistry.reset();
            GroupRegistry.init(plugin, file);
            g = onlyGroup();
            check("store -> accept + rename survive restart",
                g.size() == 2 && g.hasMember(bob.id) && "改名后".equals(g.name()));
            check("store -> accepted invite dropped from pending", GroupRegistry.pendingCount() == 0);

            // ---- 6. 踢人 / 解散在重启后不复活
            GroupRegistry.kick(aliceP, new GroupInbound.Kick(gid.toString(), bob.id));
            GroupRegistry.reset();
            GroupRegistry.init(plugin, file);
            check("store -> kick survives restart",
                onlyGroup().size() == 1 && !onlyGroup().hasMember(bob.id));
            GroupRegistry.disband(aliceP, new GroupInbound.Disband(gid.toString()));
            GroupRegistry.reset();
            GroupRegistry.init(plugin, file);
            check("store -> disband does not resurrect", GroupRegistry.groupCount() == 0);

            // ---- 7. 读 Fabric 写出来的文件（它的 Gson 输出没有 pendingInvites，creator 可能是 ""）
            UUID fabricGroup = UUID.nameUUIDFromBytes(
                "e33chat-test:fabric-group".getBytes(StandardCharsets.UTF_8));
            UUID noCreatorGroup = UUID.nameUUIDFromBytes(
                "e33chat-test:no-creator".getBytes(StandardCharsets.UTF_8));
            UUID emptyGroup = UUID.nameUUIDFromBytes(
                "e33chat-test:empty".getBytes(StandardCharsets.UTF_8));
            UUID creator = alice.id;
            UUID offline = UUID.nameUUIDFromBytes(
                "e33chat-test:Offline".getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(file.getParent());
            writeText(file, ""
                + "{\n"
                + "  \"version\": 1,\n"
                + "  \"groups\": [\n"
                + "    {\n"
                + "      \"id\": \"" + fabricGroup + "\",\n"
                + "      \"name\": \"Fabric 写的群\",\n"
                + "      \"creator\": \"" + creator + "\",\n"
                + "      \"members\": [\n"
                + "        {\n"
                + "          \"uuid\": \"" + creator + "\",\n"
                + "          \"name\": \"Alice\"\n"
                + "        },\n"
                + "        {\n"
                + "          \"uuid\": \"" + offline + "\",\n"
                + "          \"name\": \"离线成员\"\n"
                + "        }\n"
                + "      ]\n"
                + "    },\n"
                + "    {\n"
                + "      \"id\": \"" + noCreatorGroup + "\",\n"
                + "      \"name\": \"\",\n"
                + "      \"creator\": \"\",\n"
                + "      \"members\": [\n"
                + "        {\n"
                + "          \"uuid\": \"" + creator + "\",\n"
                + "          \"name\": \"Alice\"\n"
                + "        }\n"
                + "      ]\n"
                + "    },\n"
                + "    {\n"
                + "      \"id\": \"" + emptyGroup + "\",\n"
                + "      \"name\": \"空成员群\",\n"
                + "      \"creator\": \"" + creator + "\",\n"
                + "      \"members\": []\n"
                + "    }\n"
                + "  ]\n"
                + "}\n");
            GroupRegistry.reset();
            GroupRegistry.init(plugin, file);
            check("store -> reads a Fabric-written file (2 of 3 groups; empty group dropped)",
                GroupRegistry.groupCount() == 2);
            GroupRegistry.Group fabric = GroupRegistry.byId(fabricGroup.toString());
            check("store -> fabric group name/creator/members",
                fabric != null && "Fabric 写的群".equals(fabric.name())
                    && creator.equals(fabric.creator()) && fabric.size() == 2);
            check("store -> offline member name preserved",
                fabric != null && "离线成员".equals(fabric.memberList().get(1).name()));
            GroupRegistry.Group noCreator = GroupRegistry.byId(noCreatorGroup.toString());
            check("store -> empty creator string -> null creator (Fabric semantics)",
                noCreator != null && noCreator.creator() == null && "".equals(noCreator.name()));
            check("store -> missing pendingInvites key is fine", GroupRegistry.pendingCount() == 0);

            // 未知顶层键（未来版本 / 别家实现）不破坏解析
            writeText(file, readText(file).replace("\"version\": 1,",
                "\"version\": 1,\n  \"schema\": 7,"));
            GroupRegistry.reset();
            GroupRegistry.init(plugin, file);
            check("store -> unknown top-level keys ignored", GroupRegistry.groupCount() == 2);

            // ---- 8. 坏文件：不崩、不覆盖（方便运维手工修）
            String broken = "{ this is definitely not json";
            writeText(file, broken);
            GroupRegistry.reset();
            GroupRegistry.init(plugin, file);
            check("store -> malformed file -> empty, no crash", GroupRegistry.groupCount() == 0);
            check("store -> malformed file left untouched", broken.equals(readText(file)));

            // ---- 9. 保存失败：旧文件不被破坏、内存态照常更新
            GroupRegistry.reset();
            GroupRegistry.init(plugin, file);
            GroupRegistry.create(aliceP, new GroupInbound.Create("写盘失败测试", List.of()));
            UUID failing = onlyGroup().id();
            String good = readText(file);
            boolean readOnlySet = file.toFile().setWritable(false);
            alice.clear();
            GroupRegistry.rename(aliceP, new GroupInbound.Rename(failing.toString(), "失败了也不能崩"));
            check("store -> failed save keeps previous file byte-identical",
                readOnlySet && good.equals(readText(file)));
            check("store -> failed save leaves no temp file", !Files.exists(tmp));
            check("store -> failed save still updates memory",
                "失败了也不能崩".equals(onlyGroup().name()));
            check("store -> failure is logged (no exception escapes)",
                logged("store save failed | via=rename"));
            file.toFile().setWritable(true);
        } finally {
            // 尽力清理临时目录（文件句柄都已关闭，Windows 上可以直接删）
            try {
                Files.deleteIfExists(tmp);
                Files.deleteIfExists(file);
                Path serverconfig = file.getParent();
                if (serverconfig != null) {
                    Files.deleteIfExists(serverconfig);
                }
                Files.deleteIfExists(dir);
            } catch (Throwable ignored) {
                // 清理失败不影响校验结论
            }
            GroupRegistry.reset();
        }
    }

    private static String readText(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void writeText(Path path, String text) throws Exception {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- 解码器（用本插件自己的 Reader）

    static final class Sync {
        String groupId;
        String name;
        String creator;
        final List<Object[]> members = new ArrayList<>();

        static Sync decode(byte[] data) {
            Sync s = new Sync();
            McCodec.Reader in = new McCodec.Reader(data);
            s.groupId = in.readString();
            s.name = in.readString();
            s.creator = in.readString();
            int n = in.readVarInt();
            for (int i = 0; i < n; i++) {
                s.members.add(new Object[] {in.readUuid(), in.readString()});
            }
            if (in.isReadable()) {
                throw new IllegalStateException("group_sync has trailing bytes");
            }
            return s;
        }
    }

    static final class Relay {
        String groupId;
        UUID senderUUID;
        String senderName;
        String content;
        String msgHash;

        static Relay decode(byte[] data) {
            Relay r = new Relay();
            McCodec.Reader in = new McCodec.Reader(data);
            r.groupId = in.readString();
            r.senderUUID = in.readUuid();
            r.senderName = in.readString();
            r.content = in.readString();
            r.msgHash = in.readString();
            return r;
        }
    }

    static final class Event {
        String groupId;
        String kind;
        String text;

        static Event decode(byte[] data) {
            Event e = new Event();
            McCodec.Reader in = new McCodec.Reader(data);
            e.groupId = in.readString();
            e.kind = in.readString();
            e.text = in.readString();
            return e;
        }
    }

    static final class InviteEvent {
        String groupId;
        String groupName;
        String inviterName;
        String kind;
        String actorName;

        static InviteEvent decode(byte[] data) {
            InviteEvent e = new InviteEvent();
            McCodec.Reader in = new McCodec.Reader(data);
            e.groupId = in.readString();
            e.groupName = in.readString();
            e.inviterName = in.readString();
            e.kind = in.readString();
            e.actorName = in.readString();
            return e;
        }
    }

    static final class ActionRelay {
        UUID senderUUID;
        String senderName;
        String targetUUID;
        String targetName;
        String verbText;
        String content;
        String scope;
        String groupId;

        static ActionRelay decode(byte[] data) {
            ActionRelay a = new ActionRelay();
            McCodec.Reader in = new McCodec.Reader(data);
            a.senderUUID = in.readUuid();
            a.senderName = in.readString();
            a.targetUUID = in.readString();
            a.targetName = in.readString();
            a.verbText = in.readString();
            a.content = in.readString();
            a.scope = in.readString();
            a.groupId = in.readString();
            return a;
        }
    }

    // ---------------------------------------------------------------- 假对象工厂

    private static GroupRegistry.Group onlyGroup() {
        List<GroupRegistry.Group> all = GroupRegistry.allGroups();
        if (all.size() != 1) {
            throw new IllegalStateException("expected exactly 1 group, got " + all.size());
        }
        return all.get(0);
    }

    private static Player playerProxy(FakePlayer fake) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getUniqueId":
                    return fake.id;
                case "getName":
                    return fake.name;
                case "sendPluginMessage":
                    fake.inbox.add(new Sent((String) args[1], (byte[]) args[2]));
                    return null;
                case "toString":
                    return "FakePlayer[" + fake.name + "]";
                case "hashCode":
                    return fake.id.hashCode();
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (Player) Proxy.newProxyInstance(
            GroupFlowCheck.class.getClassLoader(), new Class<?>[] {Player.class}, h);
    }

    private static Plugin pluginProxy(Map<UUID, FakePlayer> byId, Map<String, FakePlayer> byName) {
        Logger logger = loggerProxy();
        Server server = (Server) Proxy.newProxyInstance(
            GroupFlowCheck.class.getClassLoader(), new Class<?>[] {Server.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getPlayer":
                        return byId.containsKey(args[0]) ? playerProxy(byId.get(args[0])) : null;
                    case "getPlayerExact":
                        return byName.containsKey(args[0]) ? playerProxy(byName.get(args[0])) : null;
                    case "getOnlinePlayers":
                        List<Player> online = new ArrayList<>();
                        for (FakePlayer p : byId.values()) {
                            online.add(playerProxy(p));
                        }
                        return online;
                    case "toString":
                        return "FakeServer";
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
        return (Plugin) Proxy.newProxyInstance(
            GroupFlowCheck.class.getClassLoader(), new Class<?>[] {Plugin.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getServer":
                        return server;
                    case "getLogger":
                        return logger;
                    case "getName":
                        return "E33ChatPaper";
                    case "toString":
                        return "FakePlugin";
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
    }

    /** 真 Logger + 收集 Handler（{@code java.util.logging.Logger} 是类不是接口，不能用 Proxy）。 */
    private static Logger loggerProxy() {
        Logger logger = Logger.getLogger("E33ChatPaper-flow");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                serverLog.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return 0;
    }

    // ---------------------------------------------------------------- 工具

    private static void check(String name, boolean condition) {
        checks++;
        if (!condition) {
            failures.add(name);
        }
        System.out.println((condition ? "[ok]   " : "[FAIL] ") + name);
    }

    /** 假 Logger 收下来的服务端日志里是否出现过某段文字（用来断言「只记日志、不崩」）。 */
    private static boolean logged(String fragment) {
        for (String line : serverLog) {
            if (line != null && line.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
