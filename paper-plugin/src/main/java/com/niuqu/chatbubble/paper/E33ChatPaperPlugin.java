package com.niuqu.chatbubble.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E33Chat 的 Paper 侧薄服务端（引用跨玩家同步 + @提及同步）。
 *
 * <p>协议（已由现有 Fabric 客户端固定，插件侧逐字节复刻，客户端一行不改）：
 * <pre>
 *   客户端 --C2S e33chat:quote_sync--> 本插件 --S2C e33chat:chat_meta--> 所有在线玩家（含发送者）
 *   聊天事件 --（本地解析 @名字）---------------------------------------^
 * </pre>
 * 也就是说插件只做这几件事：解码 4 个字符串、按 {@code <uuid>|<msgHash>|<kind>} 做 10 秒去重、
 * 从聊天明文里解析 {@code @名字}、编码 7 个字段后广播。编码细节见
 * {@link McCodec} / {@link QuoteSyncPayload} / {@link ChatMetaPayload}，提及解析见 {@link MentionParser}。
 *
 * <p>为什么不是「移植整个模组」：Fabric 服务端侧的逻辑只有「收 payload → 广播 payload」，
 * 不碰世界数据/实体/命令树，所以同一套线格式在 Bukkit 的插件通道 API 上可以直接复用。
 */
public final class E33ChatPaperPlugin extends JavaPlugin implements PluginMessageListener, Listener {

    /** C2S：客户端右键「引用」时上传被引用消息的发送者/正文与两个指纹。 */
    public static final String CHANNEL_QUOTE_SYNC = "e33chat:quote_sync";

    /** S2C：服务端广播「这条消息的引用块 / @ 列表 / 指纹」。 */
    public static final String CHANNEL_CHAT_META = "e33chat:chat_meta";

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
            // 宣告给客户端，Fabric 客户端的 ClientPlayNetworking.canSend(quote_sync) 因此为 true，
            // 客户端才会把本服写进 config/e33chat/e33chat-servers.txt（下次连接起正式开联动）。
            messenger.registerIncomingPluginChannel(this, CHANNEL_QUOTE_SYNC, this);
            // 服务端「能发」的通道：没有注册的话 CraftPlayer#sendPluginMessage 会直接抛 IllegalArgumentException。
            messenger.registerOutgoingPluginChannel(this, CHANNEL_CHAT_META);
        } catch (Throwable t) {
            getLogger().severe("[E33Chat] failed to register plugin channels: " + t);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // 聊天事件监听：@提及解析（Paper 1.21.x 的 AsyncChatEvent；不能用已废弃的
        // org.bukkit.event.player.AsyncPlayerChatEvent —— 在 Paper 上它不再携带完整语义）。
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("[E33Chat] E33ChatPaper enabled | in=" + CHANNEL_QUOTE_SYNC
            + " | out=" + CHANNEL_CHAT_META + " | dedupeWindow=" + QUOTE_TTL_MS + "ms"
            + " | mentions=on(" + AsyncChatEvent.class.getSimpleName() + ")");
    }

    @Override
    public void onDisable() {
        Messenger messenger = getServer().getMessenger();
        try {
            messenger.unregisterIncomingPluginChannel(this, CHANNEL_QUOTE_SYNC, this);
        } catch (Throwable ignored) {
            // 卸载阶段尽力而为
        }
        try {
            messenger.unregisterOutgoingPluginChannel(this, CHANNEL_CHAT_META);
        } catch (Throwable ignored) {
            // 卸载阶段尽力而为
        }
        broadcastDedupe.clear();
        getLogger().info("[E33Chat] E33ChatPaper disabled");
    }

    /**
     * Bukkit 的插件消息入口。只处理 {@code e33chat:quote_sync}，其余通道（包括服务端自己广播出去的
     * {@code chat_meta}）一律忽略。
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL_QUOTE_SYNC.equals(channel)) {
            return;
        }
        if (player == null || message == null || message.length == 0) {
            return;
        }
        try {
            if (getServer().isPrimaryThread()) {
                handleSafely(player, message);
            } else {
                // 目前 Paper 在主线程派发插件消息；万一将来改了派发线程，切回主线程再广播/发包。
                byte[] copy = message.clone();
                getServer().getScheduler().runTask(this, () -> handleSafely(player, copy));
            }
        } catch (Throwable t) {
            getLogger().warning("[E33Chat] quote_sync dispatch failed | player=" + safeName(player)
                + " | " + t);
        }
    }

    /** 兜住一切异常：协议不一致 / 畸形包只记日志丢弃，**绝不让异常冒泡到网络层**（那会直接踢人）。 */
    private void handleSafely(Player player, byte[] message) {
        try {
            handleQuoteSync(player, message);
        } catch (Throwable t) {
            getLogger().warning("[E33Chat] quote_sync dropped | player=" + safeName(player)
                + " | bytes=" + (message == null ? 0 : message.length)
                + " | " + t.getClass().getSimpleName() + ": " + t.getMessage());
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
