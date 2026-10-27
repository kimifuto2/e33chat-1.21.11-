package com.niuqu.chatbubble.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E33Chat 的 Paper 侧薄服务端（引用跨玩家同步 + @提及同步 + **群聊** + **动作消息转发**）。
 *
 * <p>协议（已由现有 Fabric 客户端固定，插件侧逐字节复刻，客户端一行不改）：
 * <pre>
 *   客户端 --C2S e33chat:quote_sync--> 本插件 --S2C e33chat:chat_meta--> 所有在线玩家（含发送者）
 *   聊天事件 --（本地解析 @名字）---------------------------------------^
 *
 *   客户端 --C2S e33chat:group_*（8 条）--> GroupRegistry --S2C e33chat:group_sync / group_message_relay /
 *                                                                    group_event / group_invite_event--> 目标玩家
 *   客户端 --C2S e33chat:player_action--> PlayerActionRelay --S2C e33chat:player_action_relay--> 范围内的其它玩家
 * </pre>
 * 也就是说插件只做这几件事：解/编码自定义载荷、维护服务端权威的群状态、按范围广播。编码细节见
 * {@link McCodec} / {@link GroupInbound} / {@link GroupOutbound} / {@link PlayerActionPayloads}，
 * 群行为见 {@link GroupRegistry}，动作行为见 {@link PlayerActionRelay}。
 *
 * <p>为什么不是「移植整个模组」：Fabric 服务端侧的逻辑只有「收 payload → 校验 → 广播 payload」，
 * 不碰世界数据/实体/命令树，所以同一套线格式在 Bukkit 的插件通道 API 上可以直接复用。
 */
public final class E33ChatPaperPlugin extends JavaPlugin implements PluginMessageListener, Listener {

    /** C2S：客户端右键「引用」时上传被引用消息的发送者/正文与两个指纹。 */
    public static final String CHANNEL_QUOTE_SYNC = E33Channels.QUOTE_SYNC;

    /** S2C：服务端广播「这条消息的引用块 / @ 列表 / 指纹」。 */
    public static final String CHANNEL_CHAT_META = E33Channels.CHAT_META;

    /** 与 Fabric 版一致的 10 秒去重窗口：同一去重键只广播一次。 */
    private static final long QUOTE_TTL_MS = 10_000L;

    /** 去重键的种类后缀：让「引用广播」和「@提及广播」互不吞并（同一 msgHash 可以各发一次）。 */
    private static final String DEDUPE_QUOTE = "quote";
    private static final String DEDUPE_MENTION = "mention";

    /** 指纹字段的长度护栏（正常值就是 int 的十进制串，最多 11 个字符；Fabric 侧同为 64）。 */
    private static final int MAX_HASH_CHARS = 64;

    /**
     * 单条 payload 的硬上限。4 个字符串的理论上限是 4 * 98301 字节，但正常的引用远小于 1KB；
     * 这里给一个宽松的绝对上限，超了直接丢（防 DoS，避免有人拿超大包打服务端）。
     */
    private static final int MAX_PAYLOAD_BYTES = 128 * 1024;

    /**
     * {@code <uuid>|<msgHash>|<kind>} -> 上次广播的毫秒时间戳。
     * {@code kind} 区分引用（{@code quote}）与 @提及（{@code mention}）两条路径：
     * 同一条消息既可能被引用广播、也可能被提及广播，共用一张表而不带 kind 会互相吞掉。
     */
    private final Map<String, Long> broadcastDedupe = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        Messenger messenger = getServer().getMessenger();
        try {
            // 服务端「能收」的通道：注册后 Bukkit 会在玩家登录时通过 minecraft:register 把通道名
            // 宣告给客户端，Fabric 客户端的 ClientPlayNetworking.canSend(...) 因此为 true，
            // 客户端才会把本服写进 config/e33chat/e33chat-servers.txt（下次连接起正式开联动）。
            for (String channel : E33Channels.INCOMING) {
                messenger.registerIncomingPluginChannel(this, channel, this);
            }
            // 服务端「能发」的通道：没有注册的话 CraftPlayer#sendPluginMessage 会直接抛 IllegalArgumentException。
            for (String channel : E33Channels.OUTGOING) {
                messenger.registerOutgoingPluginChannel(this, channel);
            }
        } catch (Throwable t) {
            getLogger().severe("[E33Chat] failed to register plugin channels: " + t);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // 群聊后端是纯内存态（重启丢群），这里把状态挂到本插件实例上，卸载时统一清空。
        GroupRegistry.init(this);
        // 聊天事件监听：@提及解析（Paper 1.21.x 的 AsyncChatEvent；不能用已废弃的
        // org.bukkit.event.player.AsyncPlayerChatEvent —— 在 Paper 上它不再携带完整语义）。
        // 同一个 Listener 里还挂了 PlayerJoinEvent（登录补发群快照）。
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("[E33Chat] E33ChatPaper enabled | in=" + E33Channels.INCOMING.size()
            + " | out=" + E33Channels.OUTGOING.size()
            + " | dedupeWindow=" + QUOTE_TTL_MS + "ms"
            + " | mentions=on(" + AsyncChatEvent.class.getSimpleName() + ")"
            + " | groups=on(in-memory, invites=pending)"
            + " | actions=on(verbs=" + PlayerActionVerbs.IDS.length + ", scopes=public/group/private)");
        // 通道清单单独打两行：排「为什么客户端的 canSend 为 false」时，一眼看到底注册了哪些。
        getLogger().info("[E33Chat] channels in  = " + String.join(", ", E33Channels.INCOMING));
        getLogger().info("[E33Chat] channels out = " + String.join(", ", E33Channels.OUTGOING));
    }

    @Override
    public void onDisable() {
        Messenger messenger = getServer().getMessenger();
        for (String channel : E33Channels.INCOMING) {
            try {
                messenger.unregisterIncomingPluginChannel(this, channel, this);
            } catch (Throwable ignored) {
                // 卸载阶段尽力而为
            }
        }
        for (String channel : E33Channels.OUTGOING) {
            try {
                messenger.unregisterOutgoingPluginChannel(this, channel);
            } catch (Throwable ignored) {
                // 卸载阶段尽力而为
            }
        }
        broadcastDedupe.clear();
        try {
            GroupRegistry.reset();
        } catch (Throwable ignored) {
            // 卸载阶段尽力而为
        }
        getLogger().info("[E33Chat] E33ChatPaper disabled");
    }

    /**
     * Bukkit 的插件消息入口（**主线程**）。
     *
     * <p>只处理 {@link E33Channels#INCOMING} 里登记的通道，其余（包括服务端自己广播出去的 S2C）
     * 一律忽略。每个通道的处理器都整体 try/catch —— 异常绝不允许冒泡到网络层（那会直接踢人）。
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!E33Channels.isIncoming(channel)) {
            return;
        }
        if (player == null || message == null || message.length == 0) {
            return;
        }
        // 所有通道共用的 DoS 护栏：正常载荷远小于 1KB，超大包直接丢（连解析都不进）。
        if (message.length > MAX_PAYLOAD_BYTES) {
            getLogger().warning("[E33Chat] " + channel + " dropped | player=" + safeName(player)
                + " | oversized payload: " + message.length + " bytes > " + MAX_PAYLOAD_BYTES);
            return;
        }
        try {
            if (getServer().isPrimaryThread()) {
                handleSafely(channel, player, message);
            } else {
                // 目前 Paper 在主线程派发插件消息；万一将来改了派发线程，切回主线程再广播/发包。
                byte[] copy = message.clone();
                getServer().getScheduler().runTask(this, () -> handleSafely(channel, player, copy));
            }
        } catch (Throwable t) {
            getLogger().warning("[E33Chat] " + channel + " dispatch failed | player=" + safeName(player)
                + " | " + t);
        }
    }

    /** 兜住一切异常：协议不一致 / 畸形包只记日志丢弃，**绝不让异常冒泡到网络层**（那会直接踢人）。 */
    private void handleSafely(String channel, Player player, byte[] message) {
        try {
            if (E33Channels.QUOTE_SYNC.equals(channel)) {
                handleQuoteSync(player, message);
                return;
            }
            if (E33Channels.PLAYER_ACTION.equals(channel)) {
                handlePlayerAction(player, message);
                return;
            }
            handleGroup(channel, player, message);
        } catch (Throwable t) {
            getLogger().warning("[E33Chat] " + channel + " dropped | player=" + safeName(player)
                + " | bytes=" + (message == null ? 0 : message.length)
                + " | " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // --------------------------------------------------------------- 群聊 / 动作派发

    /**
     * 群聊 8 条 C2S 的派发：解析 + 派发 + 记日志（与 Fabric {@code registerGroupReceivers} 的
     * 日志文字同形，方便两端日志对照）。状态校验全部在 {@link GroupRegistry} 里做。
     */
    private void handleGroup(String channel, Player player, byte[] message) {
        if (E33Channels.GROUP_CREATE.equals(channel)) {
            GroupInbound.Create payload = GroupInbound.decodeCreate(message);
            getLogger().info("[E33Chat] group_create recv | player=" + safeName(player)
                + " | name='" + McCodec.preview(payload.name())
                + "' | members=" + payload.members().size());
            GroupRegistry.create(player, payload);
            return;
        }
        if (E33Channels.GROUP_MESSAGE.equals(channel)) {
            GroupInbound.Message payload = GroupInbound.decodeMessage(message);
            if (!payload.isValid()) {
                getLogger().info("[E33Chat] group_message ignored | invalid payload | player="
                    + safeName(player) + " | group=" + McCodec.preview(payload.groupId()));
                return;
            }
            getLogger().info("[E33Chat] group_message recv | player=" + safeName(player)
                + " | group=" + McCodec.preview(payload.groupId())
                + " | hash=" + McCodec.preview(payload.msgHash())
                + " | len=" + (payload.content() == null ? 0 : payload.content().length()));
            GroupRegistry.relayMessage(player, payload);
            return;
        }
        if (E33Channels.GROUP_INVITE.equals(channel)) {
            GroupInbound.Invite payload = GroupInbound.decodeInvite(message);
            if (!payload.isValid()) {
                getLogger().info("[E33Chat] group_invite ignored | invalid payload | player="
                    + safeName(player));
                return;
            }
            getLogger().info("[E33Chat] group_invite recv | player=" + safeName(player)
                + " | group=" + McCodec.preview(payload.groupId())
                + " | targets=" + payload.members().size());
            GroupRegistry.invite(player, payload);
            return;
        }
        if (E33Channels.GROUP_INVITE_RESPOND.equals(channel)) {
            GroupInbound.InviteRespond payload = GroupInbound.decodeInviteRespond(message);
            if (!payload.isValid()) {
                getLogger().info("[E33Chat] group_invite_respond ignored | invalid payload | player="
                    + safeName(player));
                return;
            }
            getLogger().info("[E33Chat] group_invite_respond recv | player=" + safeName(player)
                + " | group=" + McCodec.preview(payload.groupId()) + " | accept=" + payload.accept());
            GroupRegistry.respondInvite(player, payload);
            return;
        }
        if (E33Channels.GROUP_KICK.equals(channel)) {
            GroupInbound.Kick payload = GroupInbound.decodeKick(message);
            if (!payload.isValid()) {
                getLogger().info("[E33Chat] group_kick ignored | invalid payload | player=" + safeName(player));
                return;
            }
            getLogger().info("[E33Chat] group_kick recv | player=" + safeName(player)
                + " | group=" + McCodec.preview(payload.groupId()) + " | target=" + payload.target());
            GroupRegistry.kick(player, payload);
            return;
        }
        if (E33Channels.GROUP_LEAVE.equals(channel)) {
            GroupInbound.Leave payload = GroupInbound.decodeLeave(message);
            if (!payload.isValid()) {
                return;
            }
            getLogger().info("[E33Chat] group_leave recv | player=" + safeName(player)
                + " | group=" + McCodec.preview(payload.groupId()));
            GroupRegistry.leave(player, payload);
            return;
        }
        if (E33Channels.GROUP_RENAME.equals(channel)) {
            GroupInbound.Rename payload = GroupInbound.decodeRename(message);
            if (!payload.isValid()) {
                getLogger().info("[E33Chat] group_rename ignored | invalid payload | player=" + safeName(player));
                return;
            }
            getLogger().info("[E33Chat] group_rename recv | player=" + safeName(player)
                + " | group=" + McCodec.preview(payload.groupId())
                + " | name='" + McCodec.preview(payload.name()) + "'");
            GroupRegistry.rename(player, payload);
            return;
        }
        if (E33Channels.GROUP_DISBAND.equals(channel)) {
            GroupInbound.Disband payload = GroupInbound.decodeDisband(message);
            if (!payload.isValid()) {
                return;
            }
            getLogger().info("[E33Chat] group_disband recv | player=" + safeName(player)
                + " | group=" + McCodec.preview(payload.groupId()));
            GroupRegistry.disband(player, payload);
        }
    }

    /** 动作消息：解码 → 最低有效性 → 交给 {@link PlayerActionRelay} 做权威词条/范围校验。 */
    private void handlePlayerAction(Player player, byte[] message) {
        PlayerActionPayloads.Inbound payload = PlayerActionPayloads.decode(message);
        if (!payload.isValid()) {
            getLogger().info("[E33Chat] player_action ignored | invalid payload | player="
                + safeName(player) + " | verb=" + McCodec.preview(payload.verbId())
                + " | target=" + McCodec.preview(payload.targetName()));
            return;
        }
        getLogger().info("[E33Chat] player_action recv | player=" + safeName(player)
            + " | target=" + McCodec.preview(payload.targetName())
            + " | verb=" + McCodec.preview(payload.verbId())
            + " | scope=" + McCodec.preview(payload.scope())
            + " | len=" + (payload.content() == null ? 0 : payload.content().length()));
        PlayerActionRelay.handle(player, payload);
    }

    /**
     * 玩家登录：把该玩家所属的**全部群**补发一遍（{@code group_sync}）。
     *
     * <p>客户端因此不需要自己持久化成员表；Paper 端群状态是内存态，重启后这里补发的就是空列表
     * （客户端那边也会因为服务端重启而收不到旧群，属已知限制）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            GroupRegistry.syncAllTo(event.getPlayer());
        } catch (Throwable t) {
            getLogger().warning("[E33Chat] group join sync failed | player="
                + safeName(event.getPlayer()) + " | " + t);
        }
    }

    private void handleQuoteSync(Player player, byte[] message) {
        if (message.length > MAX_PAYLOAD_BYTES) {
            getLogger().warning("[E33Chat] quote_sync dropped | player=" + safeName(player)
                + " | oversized payload: " + message.length + " bytes > " + MAX_PAYLOAD_BYTES);
            return;
        }

        QuoteSyncPayload quote = QuoteSyncPayload.decode(message);

        // 与 Fabric 版完全相同的归一化/限长：messageHash 的契约是「明文 hashCode 的十进制串」，
        // 旧客户端（≤2.4.2）曾直接发明文 —— 不像 hashCode 形态就地哈希，混版本时客户端才对得上回显。
        String messageHash = normalizeMessageHash(quote.messageHash());
        String quotedHash = clampHash(quote.quotedHash());

        getLogger().info("[E33Chat] quote_sync recv | player=" + safeName(player)
            + " | msgHash=" + McCodec.preview(messageHash)
            + " | quotedHash=" + McCodec.preview(quotedHash)
            + " | quoteSender=" + McCodec.preview(quote.quotedSenderName())
            + " | quote=" + McCodec.preview(quote.quotedContent()));

        String dedupeKey = player.getUniqueId() + "|" + messageHash + "|" + DEDUPE_QUOTE;
        long now = System.currentTimeMillis();
        broadcastDedupe.entrySet().removeIf(entry -> now - entry.getValue() > QUOTE_TTL_MS);
        Long previous = broadcastDedupe.put(dedupeKey, now);
        if (previous != null && now - previous <= QUOTE_TTL_MS) {
            getLogger().info("[E33Chat] chat_meta skipped (dup) | player=" + safeName(player)
                + " | msgHash=" + McCodec.preview(messageHash)
                + " (same msgHash already broadcast)");
            return;
        }

        // 与 @提及广播共用同一个 broadcaster，靠去重键的 kind 后缀区分（quote / mention）。
        broadcastChatMeta(player.getUniqueId(), player.getName(), messageHash, quotedHash,
            quote.quotedSenderName(), quote.quotedContent(), List.of(), "quote_sync");
    }

    // --------------------------------------------------------------- @提及同步

    /**
     * 聊天事件入口（<b>异步线程</b>）。
     *
     * <p>这里只做两件线程安全的事：把 {@code event.message()} 序列化成纯文本快照、取发送者的
     * UUID/名字；**在线玩家列表、去重表、发包全部推迟到主线程**（{@link org.bukkit.scheduler.BukkitScheduler#runTask}），
     * 因为 Bukkit 的 {@code sendPluginMessage} / 在线列表都要求主线程。
     *
     * <p>优先 {@link EventPriority#MONITOR}：让其它聊天插件先改完 {@code event.message()}，我们读到的是
     * 最终要发出去的明文 —— 这也正是客户端算 {@code messageHash} 用的那份文本（客户端
     * {@code ChatMessageStore.addMessage} 里 {@code String.valueOf(content.getString().hashCode())}）。
     * 若还有插件在 MONITOR 阶段（且注册顺序在我们之后）改写消息，或走 {@code ChatRenderer}
     * 追加了正文之外的前后缀，hash 就可能与客户端收到的正文不一致 —— 那种情况下客户端会忽略这条
     * meta（引用块不贴），但**@提及本身仍按正文文本工作**，不会误报/漏报提醒。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        if (sender == null) {
            return;
        }
        String plain;
        try {
            plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        } catch (Throwable t) {
            // 序列化失败只影响本次提及同步，绝不让异常冒到聊天管线里
            getLogger().warning("[E33Chat] chat message not serializable | player=" + safeName(sender)
                + " | " + t);
            return;
        }
        // 没有任何 '@' 就提前返回：绝大多数公屏不产生任何跨线程任务
        if (plain.isEmpty() || plain.indexOf('@') < 0) {
            return;
        }
        final UUID senderId = sender.getUniqueId();
        final String senderName = sender.getName();
        final String text = plain;
        try {
            getServer().getScheduler().runTask(this, () -> {
                try {
                    handleMentions(senderId, senderName, text);
                } catch (Throwable t) {
                    getLogger().warning("[E33Chat] mention broadcast failed | from=" + senderName
                        + " | " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            // 插件正在卸载等情况：当作本次不提及，不影响聊天
            getLogger().warning("[E33Chat] mention schedule failed | from=" + senderName + " | " + t);
        }
    }

    /** 主线程：解析 {@code @名字} → 去重 → 广播 {@code chat_meta}。 */
    private void handleMentions(UUID senderId, String senderName, String plain) {
        List<String> onlineNames = new ArrayList<>();
        for (Player online : getServer().getOnlinePlayers()) {
            String name = safeName(online);
            if (!name.isEmpty()) {
                onlineNames.add(name);
            }
        }
        List<String> targets = MentionParser.parse(plain, onlineNames);
        if (targets.isEmpty()) {
            return;
        }
        // messageHash 的契约：这条消息**明文**的 hashCode 十进制串（客户端拿收到的正文算同一个值）
        String messageHash = String.valueOf(plain.hashCode());
        String dedupeKey = senderId + "|" + messageHash + "|" + DEDUPE_MENTION;
        long now = System.currentTimeMillis();
        broadcastDedupe.entrySet().removeIf(entry -> now - entry.getValue() > QUOTE_TTL_MS);
        Long previous = broadcastDedupe.put(dedupeKey, now);
        if (previous != null && now - previous <= QUOTE_TTL_MS) {
            getLogger().info("[E33Chat] mention skipped (dup) | from=" + senderName
                + " | msgHash=" + McCodec.preview(messageHash));
            return;
        }
        getLogger().info("[E33Chat] mention broadcast | from=" + senderName
            + " | targets=" + targets
            + " | msgHash=" + McCodec.preview(messageHash));
        // 纯 @提及广播：quote* 三个字段留空（客户端 quoteContent 为空 → 不贴引用块，只记 mention 列表）
        broadcastChatMeta(senderId, senderName, messageHash, "", "", "", targets,
            "chat_message_mentions");
    }

    /**
     * 唯一的 {@code chat_meta} 广播出口（引用 / @提及两条路径共用）。
     *
     * <p>调用方必须先在自己的去重键上 claim 过；这里只负责编码 + 发给所有在线玩家（含发送者自己）
     * + 打日志。单个玩家发送失败（刚断线）只告警，不影响其他人。
     *
     * @return 成功发出的玩家数（编码失败时为 0）
     */
    private int broadcastChatMeta(UUID senderId, String senderName, String messageHash,
                                  String quotedHash, String quoteSender, String quoteContent,
                                  List<String> mentions, String source) {
        ChatMetaPayload meta = new ChatMetaPayload(senderId, senderName, messageHash,
            quoteSender, quoteContent, mentions == null ? List.of() : mentions, quotedHash);
        byte[] payload;
        try {
            payload = meta.encode();
        } catch (Throwable t) {
            getLogger().warning("[E33Chat] chat_meta encode failed | from=" + senderName
                + " | " + t);
            return 0;
        }
        int recipients = 0;
        for (Player target : new ArrayList<>(getServer().getOnlinePlayers())) {
            try {
                target.sendPluginMessage(this, CHANNEL_CHAT_META, payload);
                recipients++;
            } catch (Throwable t) {
                getLogger().warning("[E33Chat] chat_meta send failed | to=" + safeName(target)
                    + " | " + t);
            }
        }
        getLogger().info("[E33Chat] chat_meta broadcast | from=" + senderName
            + " | msgHash=" + McCodec.preview(messageHash)
            + " | mentions=" + (mentions == null ? 0 : mentions.size())
            + " | via=" + source + " | recipients=" + recipients);
        return recipients;
    }

    /** 长度护栏：只保留前 {@value #MAX_HASH_CHARS} 个字符（Fabric 侧 {@code clampHash} 同义）。 */
    private static String clampHash(String hash) {
        if (hash == null || hash.isEmpty()) {
            return "";
        }
        return hash.length() <= MAX_HASH_CHARS ? hash : hash.substring(0, MAX_HASH_CHARS);
    }

    /**
     * {@code messageHash} 必须长得像「十进制 hashCode」。像就原样放行；不像（旧客户端发明文）
     * 就用 {@link String#hashCode()} 自己算一遍，与 Fabric 版 {@code normalizeMessageHash} 语义一致。
     */
    private static String normalizeMessageHash(String fromClient) {
        String hash = clampHash(fromClient);
        if (hash.isEmpty()) {
            return hash;
        }
        boolean numeric = hash.length() <= 11;
        for (int i = 0; numeric && i < hash.length(); i++) {
            char c = hash.charAt(i);
            if (i == 0 && c == '-') {
                continue;
            }
            if (c < '0' || c > '9') {
                numeric = false;
            }
        }
        return numeric ? hash : String.valueOf(hash.hashCode());
    }

    private static String safeName(Player player) {
        try {
            return player == null ? "<null>" : player.getName();
        } catch (Throwable t) {
            return "<?>";
        }
    }
}
