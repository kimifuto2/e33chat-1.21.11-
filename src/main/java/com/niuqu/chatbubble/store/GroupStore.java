package com.niuqu.chatbubble.store;

import com.niuqu.chatbubble.ChatBubbleClientSetup;
import com.niuqu.chatbubble.chat.MentionDetector;
import com.niuqu.chatbubble.chat.notification.MentionNotificationController;
import com.niuqu.chatbubble.chat.group.GroupInvites;
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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端群聊状态（**唯一数据源**：群列表 / 群名 / 成员）。
 *
 * <p>数据流（方案 A：服务端中继）：
 * <pre>
 *   服务端 JOIN 或任何变更 → GroupSyncPayload  → {@link #applySync}
 *   服务端转发消息         → GroupMessageRelayPayload → {@link #receiveMessage}
 *   服务端事件（拉群/被踢/解散/改名）→ GroupEventPayload → {@link #applyEvent}
 * </pre>
 *
 * <p>设计取舍（MVP）：
 * <ul>
 *   <li><b>成员表只以服务端下发的快照为准</b>，本地不落盘 —— 免得服务端已经解散的群在
 *       客户端一直残留。每次进服/换服由 JOIN 的 GroupSync 全部重建。</li>
 *   <li><b>群消息直接复用私聊的消息通道</b>：{@link ChatMessageStore#groupPartnerKey(UUID)}
 *       生成的 {@code "group:<uuid>"} 当作私聊的 whisperPartner，因此气泡渲染、引用、
 *       图片、未读红点、历史落地（{@code HistoryStore} 会连 whisperPartner 一起写）全部复用，
 *       不需要第二套消息模型。</li>
 *   <li>群聊不可用（服务端没装 E33Chat）时 {@link #available()} 为 false，UI 显示提示并禁用入口。</li>
 * </ul>
 */
public final class GroupStore {
    private GroupStore() {}

    /** 会话键前缀：私聊的 whisperPartner 字段被复用来承载群会话键。
     *  唯一事实来源在 {@link ChatMessageStore#GROUP_PARTNER_PREFIX}（消息模型那边定义）。 */
    public static final String PARTNER_PREFIX = ChatMessageStore.GROUP_PARTNER_PREFIX;

    /** 群成员（uuid + 显示名）。顺序 = 入群顺序（侧边栏九宫格取前 9 个）。 */
    public record Member(UUID uuid, String name) {}

    /** 一个群的客户端快照。 */
    public static final class Group {
        private final UUID id;
        private String name;
        private UUID creator;
        private final List<Member> members;

        Group(UUID id, String name, UUID creator, List<Member> members) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.creator = creator;
            this.members = members == null ? new ArrayList<>() : new ArrayList<>(members);
        }

        public UUID id() { return id; }
        public String rawName() { return name; }
        public UUID creator() { return creator; }
        public List<Member> members() { return members; }

        /** 群名（未设置/空 → 「【群聊】」）。 */
        public String displayName() {
            String n = name == null ? "" : name.trim();
            return n.isEmpty()
                ? Text.translatable("e33chat.group.default_name").getString()
                : n;
        }

        /** 九宫格用的头像成员：**最先的 9 个**。 */
        public List<Member> avatarMembers() {
            return members.size() <= 9 ? members : members.subList(0, 9);
        }

        /** 本群的免打扰状态（客户端本地开关，见 {@link GroupStore#isDnd(UUID)}）。 */
        public boolean dnd() {
            return GroupStore.isDnd(id);
        }

        public boolean isMember(UUID uuid) {
            if (uuid == null) return false;
            for (Member m : members) if (uuid.equals(m.uuid())) return true;
            return false;
        }

        public int size() { return members.size(); }

        @Override
        public String toString() {
            return "Group{id=" + id + ", name='" + name + "', members=" + members.size() + "}";
        }
    }

    /** groupId → 群快照（保持服务端下发顺序 = 建群顺序）。 */
    private static final Map<UUID, Group> groups = new LinkedHashMap<>();

    // ===== 群免打扰（DND）=====
    // 只在本机生效、也只在本机持久化（config/e33chat/group-dnd.txt，一行一个 groupId）：
    //   * GroupSync 会整体替换 Group 快照，所以 DND **不能**存在 Group 里（进服/改名后会被洗掉）；
    //   * 服务端不参与这个开关（纯客户端静默偏好），换服后按 uuid 继续生效。
    // 开启后该群：不弹「[群聊]」横幅、不出非 @ 提示音，新消息静默入列表；
    // **未读照常累计、红点照常亮**（第 6 轮修正：与私聊 DND 完全一致 —— 私聊那边
    // markWhisperUnread 从不看 isWhisperDnd；红点与月亮可以同时出现）。
    // **例外**：有人在群里 @我 时横幅照弹（@ 优先级高于免打扰，见 receiveMessage / mentionsSelf）。
    // 关闭即恢复。横幅/提示音的抑制点在 GroupStore#receiveMessage 的 dnd 分支，
    // 与未读无关（ChatMessageStore#addGroupMessage 不再有 quiet 参数）。
    private static final java.util.Set<UUID> dndGroups = new java.util.LinkedHashSet<>();
    private static boolean dndLoaded;

    private static java.nio.file.Path dndFilePath() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.runDirectory == null) return null;
            return mc.runDirectory.toPath().resolve("config/e33chat/group-dnd.txt");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void ensureDndLoaded() {
        if (dndLoaded) return;
        dndLoaded = true;
        java.nio.file.Path p = dndFilePath();
        if (p == null || !java.nio.file.Files.exists(p)) return;
        try {
            for (String line : java.nio.file.Files.readAllLines(p, java.nio.charset.StandardCharsets.UTF_8)) {
                String s = line == null ? "" : line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                try {
                    dndGroups.add(UUID.fromString(s));
                } catch (Throwable ignored) {
                    // 单行坏数据不影响其余群
                }
            }
            ChatMessageStore.debugLog("[e33chat] group dnd loaded | count=" + dndGroups.size());
        } catch (Throwable t) {
            ChatMessageStore.debugLog("[e33chat] group dnd load failed | " + t);
        }
    }

    private static void saveDnd() {
        java.nio.file.Path p = dndFilePath();
        if (p == null) return;
        try {
            if (p.getParent() != null) java.nio.file.Files.createDirectories(p.getParent());
            List<String> out = new ArrayList<>();
            for (UUID id : dndGroups) out.add(id.toString());
            java.nio.file.Files.write(p, out, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            ChatMessageStore.debugLog("[e33chat] group dnd save failed | " + t);
        }
    }

    /** 该群是否开了免打扰（客户端本地开关）。 */
    public static synchronized boolean isDnd(UUID groupId) {
        if (groupId == null) return false;
        ensureDndLoaded();
        return dndGroups.contains(groupId);
    }

    /** 设置/取消某个群的免打扰并立即落盘。
     *  不再顺手清未读（第 6 轮）：DND 已不影响红点，清掉反而会表现为「一开 DND 红点就消失」
     *  —— 与私聊 DND 的切换行为对齐（那边 toggleWhisperDnd 也不动未读）。 */
    public static synchronized void setDnd(UUID groupId, boolean enabled) {
        if (groupId == null) return;
        ensureDndLoaded();
        boolean changed = enabled ? dndGroups.add(groupId) : dndGroups.remove(groupId);
        if (changed) {
            saveDnd();
            ChatMessageStore.debugLog("[e33chat] group dnd set | group=" + groupId + " | enabled=" + enabled);
        }
    }

    // ===== 查询 =====

    /** 群聊功能是否可用：服务端也装了**支持群聊的** E33Chat 时才为 true。
     *
     *  <p>判定口径（客户端据此置灰「+」/ 拦下所有群操作）：
     *  {@code ClientPlayNetworking.canSend(GroupCreatePayload.ID)} —— 服务端只有注册了群聊
     *  C2S 接收器（{@code ChatBubbleMod#registerGroupReceivers}）才会把这个通道宣告给客户端，
     *  所以 false 同时覆盖「服务端没装 E33Chat」与「装的是不支持群聊的旧版」。
     *
     *  <p>例外：单机 / 自己是局域网主机（{@code client.getServer() != null}）时集成服务器跑的
     *  就是本 jar 的服务端入口，群聊必然可用 —— 直接返回 true，免得集成服务器在登录/落地
     *  时序上晚一个 tick 让「+」闪一下灰。 */
    public static boolean available() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.getServer() != null) return true;
            return ClientPlayNetworking.canSend(GroupCreatePayload.ID);
        } catch (Throwable t) {
            return false;
        }
    }

    public static synchronized List<Group> groups() {
        return new ArrayList<>(groups.values());
    }

    public static synchronized Group byId(UUID id) {
        return id == null ? null : groups.get(id);
    }

    public static synchronized int count() {
        return groups.size();
    }

    /** 群会话键（= 私聊的 whisperPartner 字段值）。 */
    public static String groupPartnerKey(UUID groupId) {
        return ChatMessageStore.groupPartnerKey(groupId);
    }

    public static boolean isGroupPartner(String partner) {
        return ChatMessageStore.isGroupPartner(partner);
    }

    /** 从会话键里取出 groupId；不是群会话键/格式非法 → null。 */
    public static UUID groupIdFromPartner(String partner) {
        return ChatMessageStore.groupIdFromPartner(partner);
    }

    public static UUID currentSelf() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            return mc != null && mc.player != null ? mc.player.getUuid() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ===== 网络入口（都在客户端线程调用）=====

    /** 连接边界：清空全部群（服务端随后会按 JOIN 的 GroupSync 重建）。 */
    public static synchronized void clear(String reason) {
        int n = groups.size();
        groups.clear();
        GroupInvites.clear();
        if (n > 0) ChatMessageStore.debugLog("[e33chat] group store cleared | reason=" + reason + " | was=" + n);
    }

    /** 收到群快照：新增或整体替换该群（成员表/群名/创建者）。 */
    public static synchronized void applySync(GroupSyncPayload payload) {
        if (payload == null || !payload.isValid()) {
            ChatMessageStore.debugLog("[e33chat] group_sync ignored | invalid payload");
            return;
        }
        UUID id = GroupWire.parseUuid(payload.groupId());
        if (id == null) return;
        List<Member> members = new ArrayList<>();
        if (payload.members() != null) {
            for (GroupSyncPayload.Member m : payload.members()) {
                if (m == null || m.uuid() == null) continue;
                members.add(new Member(m.uuid(), m.name() == null ? "" : m.name()));
            }
        }
        UUID creator = GroupWire.parseUuid(payload.creator());
        Group g = new Group(id, payload.nameOrEmpty(), creator, members);
        groups.put(id, g);
        ChatMessageStore.debugLog(() -> "[e33chat] group_sync applied | " + g);
    }

    /** 收到群事件：本地状态变更 + 系统提示 + 横幅。 */
    public static synchronized void applyEvent(GroupEventPayload payload) {
        if (payload == null || !payload.isValid()) {
            ChatMessageStore.debugLog("[e33chat] group_event ignored | invalid payload");
            return;
        }
        UUID id = GroupWire.parseUuid(payload.groupId());
        if (id == null) return;
        String kind = payload.kind() == null ? "" : payload.kind();
        String text = payload.text() == null ? "" : payload.text();
        Group g = groups.get(id);
        String shownName = g != null ? g.displayName()
            : (text.trim().isEmpty() ? Text.translatable("e33chat.group.default_name").getString() : text.trim());
        ChatMessageStore.debugLog(() -> "[e33chat] group_event | kind=" + kind + " | group=" + id
            + " | name=" + shownName + " | known=" + (g != null));

        switch (kind) {
            case GroupEventPayload.KIND_INVITED -> {
                // 需求：被拉进群时弹横幅「你已被加入群聊 xxx」。GroupSync 会紧随其后到达，
                // 这里用事件自带的群名（GroupSync 里也有名字，两边一致）。
                ChatMessageStore.addGroupNotice(id, Text.translatable("e33chat.group.joined", shownName));
                MentionNotificationController.INSTANCE.onGroupNotice(
                    Text.translatable("e33chat.group.joined", shownName));
            }
            case GroupEventPayload.KIND_RENAMED ->
                ChatMessageStore.addGroupNotice(id, Text.translatable("e33chat.group.renamed", shownName));
            case GroupEventPayload.KIND_KICKED -> {
                ChatMessageStore.addGroupNotice(id, Text.translatable("e33chat.group.kicked_out"));
                MentionNotificationController.INSTANCE.onGroupNotice(
                    Text.translatable("e33chat.group.kicked_out.banner", shownName));
                groups.remove(id);
            }
            case GroupEventPayload.KIND_LEFT -> {
                ChatMessageStore.addGroupNotice(id, Text.translatable("e33chat.group.left"));
                groups.remove(id);
            }
            case GroupEventPayload.KIND_DISBANDED -> {
                ChatMessageStore.addGroupNotice(id, Text.translatable("e33chat.group.disbanded"));
                MentionNotificationController.INSTANCE.onGroupNotice(
                    Text.translatable("e33chat.group.disbanded.banner", shownName));
                groups.remove(id);
            }
            case GroupEventPayload.KIND_INVITE_DENIED -> {
                // 第 4 轮：服务端拒绝邀请（只有创建者能邀请）。群与自己都没被改动，
                // 只在群会话里落一条提示，免得点了「完成」看起来毫无反应。
                ChatMessageStore.addGroupNotice(id,
                    Text.translatable("e33chat.group.invite.denied"));
                ChatMessageStore.debugLog("[e33chat] group_invite denied by server | group=" + id);
            }
            default -> ChatMessageStore.debugLog("[e33chat] group_event unknown kind ignored | " + kind);
        }
    }

    /**
     * 收到进群邀请类事件（第 4 轮，S2C {@code group_invite_event}）。
     *
     * <ul>
     *   <li>{@code INVITE}（我是被邀请者）：在「邀请者 ↔ 我」的私聊会话里落一条系统消息
     *       {@code xxx 邀请你加入群聊【群名】}，并把这条消息登记到 {@link GroupInvites}
     *       —— 聊天面板据此在消息下面画「接受 / 拒绝」两个按钮。同时弹一条横幅。</li>
     *   <li>{@code ACCEPTED} / {@code DECLINED}（我是创建者）：在「我 ↔ 回应者」的私聊里落
     *       一条系统提示（{@code X 已加入群聊 / X 拒绝了邀请}）+ 横幅。群状态本身由服务端的
     *       GroupSync 负责，这里不动 {@link #groups}。</li>
     * </ul>
     */
    public static synchronized void applyInviteEvent(GroupInviteEventPayload payload) {
        if (payload == null || !payload.isValid()) {
            ChatMessageStore.debugLog("[e33chat] group_invite_event ignored | invalid payload");
            return;
        }
        // 客户端把群聊功能关了 → 邀请 / 回执直接丢弃：否则会冒出一个只能看不能用的
        // 「私聊邀请」会话，点了接受也会因为侧边栏不画群行而"没有反应"。
        if (!ChatBubbleClientSetup.config().groupChatEnabledResolved()) {
            ChatMessageStore.debugLog("[e33chat] group_invite_event ignored | group chat disabled in settings");
            return;
        }
        UUID id = GroupWire.parseUuid(payload.groupId());
        if (id == null) return;
        String kind = payload.kind() == null ? "" : payload.kind();
        String inviter = payload.inviterName() == null ? "" : payload.inviterName().trim();
        String actor = payload.actorName() == null ? "" : payload.actorName().trim();
        String name = payload.groupName() == null ? "" : payload.groupName().trim();
        String groupName = name.isEmpty()
            ? Text.translatable("e33chat.group.default_name").getString() : name;
        ChatMessageStore.debugLog(() -> "[e33chat] group_invite_event | kind=" + kind + " | group=" + id
            + " | name=" + groupName + " | inviter=" + inviter + " | actor=" + actor);

        switch (kind) {
            case GroupInviteEventPayload.KIND_INVITE -> {
                if (inviter.isEmpty()) return;       // 不知道是谁邀请的 → 没法定位私聊会话，丢弃
                String text = Text.translatable("e33chat.group.invite.message", inviter, groupName).getString();
                ChatMessageStore.ChatMessage msg = ChatMessageStore.addWhisperNotice(
                    inviter, Text.literal(text), true);
                GroupInvites.attach(msg, id, groupName, inviter);
                MentionNotificationController.INSTANCE.onGroupNotice(
                    Text.translatable("e33chat.group.invite.banner", inviter, groupName));
            }
            case GroupInviteEventPayload.KIND_ACCEPTED -> {
                if (actor.isEmpty()) return;
                ChatMessageStore.addWhisperNotice(actor,
                    Text.literal(Text.translatable("e33chat.group.invite.accepted_notice", actor, groupName).getString()),
                    false);
                MentionNotificationController.INSTANCE.onGroupNotice(
                    Text.translatable("e33chat.group.invite.accepted_banner", actor, groupName));
            }
            case GroupInviteEventPayload.KIND_DECLINED -> {
                if (actor.isEmpty()) return;
                ChatMessageStore.addWhisperNotice(actor,
                    Text.literal(Text.translatable("e33chat.group.invite.declined_notice", actor, groupName).getString()),
                    false);
                MentionNotificationController.INSTANCE.onGroupNotice(
                    Text.translatable("e33chat.group.invite.declined_banner", actor, groupName));
            }
            default -> ChatMessageStore.debugLog(
                "[e33chat] group_invite_event unknown kind ignored | " + kind);
        }
    }

    /** 收到转发的群消息：写进「群会话」的消息列表 + 未读 + 横幅。 */
    public static synchronized void receiveMessage(GroupMessageRelayPayload payload) {
        if (payload == null || !payload.isValid()) {
            ChatMessageStore.debugLog("[e33chat] group_relay ignored | invalid payload");
            return;
        }
        UUID id = GroupWire.parseUuid(payload.groupId());
        if (id == null) return;
        Group g = groups.get(id);
        String groupName = g != null ? g.displayName()
            : Text.translatable("e33chat.group.default_name").getString();
        UUID self = currentSelf();
        boolean own = self != null && self.equals(payload.senderUUID());
        // 群免打扰：只静默这一个群的横幅/提示音（消息照常入列表，**未读照常累计**）。
        // **例外（第 5 轮）**：@ 优先级高于免打扰 —— DND 群里有人 @我 仍然弹横幅，
        // 见下面的 mentionsSelf 分支；未读红点与月亮互不影响（第 6 轮起红点照常亮）。
        boolean dnd = !own && isDnd(id);
        Text senderName = Text.literal(payload.senderName() == null ? "" : payload.senderName());
        Text content = ChatMessageStore.renderContent(payload.content());
        ChatMessageStore.ChatMessage created = ChatMessageStore.addGroupMessage(
            id, payload.senderUUID(), senderName, content, payload.senderName(),
            false, payload.msgHash());
        if (created == null) return;          // 本地回显已存在（自己去重）/ 空白内容
        if (!own && !dnd) {
            MentionNotificationController.INSTANCE.onGroupMessage(
                id, groupName, payload.senderUUID(), senderName, content);
        } else if (dnd) {
            // @ 穿透免打扰：@我 = 强提醒，横幅照弹（文案沿用 [群聊]，与普通群消息一致）；
            // 普通群消息仍然静默。
            if (mentionsSelf(content)) {
                ChatMessageStore.debugLog(() -> "[e33chat] group message @self overrides DND | group=" + id);
                MentionNotificationController.INSTANCE.onGroupMessage(
                    id, groupName, payload.senderUUID(), senderName, content);
            } else {
                ChatMessageStore.debugLog(() -> "[e33chat] group message muted by DND | group=" + id);
            }
        }
    }

    /** 这条群消息是否 @ 了**我自己**（免打扰群里 @ 仍然弹横幅的唯一判据）。
     *
     *  <p>判据与公屏 @ 完全同源（{@link MentionDetector#isMentioned}），但这里**强制要求带
     *  「@」**（requireAt=true）：群聊人少、正文里碰巧出现自己的名字很常见，只有 @ 才算
     *  「找我」；config.mentionRequireAt 是公屏的宽松开关，不应该让群聊的强提醒退化。
     *  用户名与**显示名**都试一遍（服务器/插件给的显示名常带前后缀，但玩家习惯 @ 的是用户名；
     *  两边的 § 颜色码都先剥掉）。 */
    private static boolean mentionsSelf(Text content) {
        if (content == null) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;
        String text = content.getString();
        String name = mc.player.getName().getString();
        if (MentionDetector.isMentioned(text, name, true, null)) return true;
        String display = mc.player.getDisplayName() == null ? ""
            : mc.player.getDisplayName().getString().replaceAll("§.", "").trim();
        return !display.isEmpty() && !display.equals(name)
            && MentionDetector.isMentioned(text, display, true, null);
    }

    // ===== 操作（客户端 → 服务端）=====

    /** 客户端本地回显用的会话键：{@code "group:<uuid>"}。 */
    public static void create(String name, List<UUID> members) {
        if (!available()) return;
        try {
            ClientPlayNetworking.send(new GroupCreatePayload(name == null ? "" : name,
                members == null ? List.of() : members));
            ChatMessageStore.debugLog(() -> "[e33chat] group_create send | name='" + name
                + "' | members=" + (members == null ? 0 : members.size()));
        } catch (Throwable t) {
            ChatMessageStore.debugLog("[e33chat] group_create send failed | " + t);
        }
    }

    /** 发一条群消息：先本地回显（与私聊一致），再交给服务端转发。 */
    public static void sendMessage(UUID groupId, String text) {
        if (groupId == null || text == null || text.trim().isEmpty()) return;
        if (!available()) {
            ChatMessageStore.debugLog("[e33chat] group_message skipped | server has no group support");
            return;
        }
        String msgHash = String.valueOf(text.hashCode());
        UUID self = currentSelf();
        Text own = ChatMessageStore.ownDisplayName();
        ChatMessageStore.ChatMessage created = ChatMessageStore.addGroupMessage(
            groupId, self, own, ChatMessageStore.renderContent(text), null, true, msgHash);
        try {
            ClientPlayNetworking.send(new GroupMessagePayload(groupId.toString(), text, msgHash));
            ChatMessageStore.debugLog(() -> "[e33chat] group_message send | group=" + groupId
                + " | hash=" + msgHash + " | len=" + text.length() + " | local=" + (created != null));
        } catch (Throwable t) {
            ChatMessageStore.debugLog("[e33chat] group_message send failed | " + t);
        }
    }

    /**
     * 邀请玩家进群（第 4 轮：服务端不再直接拉人，只发「进群邀请」）。
     *
     * <p>客户端只负责发请求：真正的权限校验（必须创建者）、pending 登记、离线忽略都在
     * 服务端 {@code GroupRegistry.invite} 里做；被邀请者接受后才会收到 GroupSync。
     */
    public static void invite(UUID groupId, List<UUID> members) {
        if (groupId == null || members == null || members.isEmpty()) return;
        if (!available()) {
            ChatMessageStore.debugLog("[e33chat] group_invite skipped | server has no group support");
            return;
        }
        try {
            ClientPlayNetworking.send(new GroupInvitePayload(groupId.toString(), members));
            ChatMessageStore.debugLog(() -> "[e33chat] group_invite send | group=" + groupId
                + " | targets=" + members.size());
        } catch (Throwable t) {
            ChatMessageStore.debugLog("[e33chat] group_invite send failed | " + t);
        }
    }

    /**
     * 回应进群邀请（第 4 轮）：被邀请者点「接受 / 拒绝」。
     *
     * <p>这里只发 C2S；真正的成员变更由服务端在 {@code respondInvite} 里做并广播 GroupSync。
     * 群/邀请是否还有效完全以服务端为准 —— 客户端即使点到过期按钮，服务端也只是忽略。
     */
    public static void respondInvite(UUID groupId, boolean accept) {
        if (groupId == null) return;
        if (!available()) {
            ChatMessageStore.debugLog("[e33chat] group_invite_respond skipped | server has no group support");
            return;
        }
        try {
            ClientPlayNetworking.send(new GroupInviteRespondPayload(groupId.toString(), accept));
            ChatMessageStore.debugLog(() -> "[e33chat] group_invite_respond send | group=" + groupId
                + " | accept=" + accept);
        } catch (Throwable t) {
            ChatMessageStore.debugLog("[e33chat] group_invite_respond send failed | " + t);
        }
    }

    public static void kick(UUID groupId, UUID target) {
        if (groupId == null || target == null) return;
        try {
            ClientPlayNetworking.send(new GroupKickPayload(groupId.toString(), target));
        } catch (Throwable t) {
            ChatMessageStore.debugLog("[e33chat] group_kick send failed | " + t);
        }
    }

    public static void leave(UUID groupId) {
        if (groupId == null) return;
        try {
            ClientPlayNetworking.send(new GroupLeavePayload(groupId.toString()));
        } catch (Throwable t) {
            ChatMessageStore.debugLog("[e33chat] group_leave send failed | " + t);
        }
    }

    public static void rename(UUID groupId, String name) {
        if (groupId == null) return;
        try {
            ClientPlayNetworking.send(new GroupRenamePayload(groupId.toString(), name == null ? "" : name));
        } catch (Throwable t) {
            ChatMessageStore.debugLog("[e33chat] group_rename send failed | " + t);
        }
    }

    public static void disband(UUID groupId) {
        if (groupId == null) return;
        try {
            ClientPlayNetworking.send(new GroupDisbandPayload(groupId.toString()));
        } catch (Throwable t) {
            ChatMessageStore.debugLog("[e33chat] group_disband send failed | " + t);
        }
    }

    /** 在线玩家（侧边栏「添加新群聊 / 添加玩家」勾选列表用）：uuid + profile 名。 */
    public static List<Member> onlinePlayers() {
        List<Member> out = new ArrayList<>();
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null || mc.player.networkHandler == null) return out;
            UUID self = mc.player.getUuid();
            for (var info : mc.player.networkHandler.getPlayerList()) {
                var profile = info.getProfile();
                if (profile == null || profile.id() == null) continue;
                if (profile.id().equals(self)) continue;
                String name = profile.name();
                if (name == null || name.isBlank()) continue;
                out.add(new Member(profile.id(), name));
            }
        } catch (Throwable t) {
            ChatMessageStore.debugLog("[e33chat] group onlinePlayers failed | " + t);
        }
        return out;
    }

    /** 成员名字（找不到时回退 uuid 前 8 位）。 */
    public static String memberName(Group g, UUID uuid) {
        if (g != null && uuid != null) {
            for (Member m : g.members()) {
                if (uuid.equals(m.uuid())) {
                    return m.name() == null || m.name().isBlank() ? uuid.toString().substring(0, 8) : m.name();
                }
            }
        }
        return uuid == null ? "?" : uuid.toString().substring(0, 8);
    }
}
