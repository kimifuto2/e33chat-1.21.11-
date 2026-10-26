package com.niuqu.chatbubble;

import com.niuqu.chatbubble.config.ServerConfig;
import com.niuqu.chatbubble.config.ServerConfigManager;
import com.niuqu.chatbubble.network.ChatMetaPayload;
import com.niuqu.chatbubble.network.ConfigSyncPayload;
import com.niuqu.chatbubble.network.ConfigSyncV2Payload;
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
import com.niuqu.chatbubble.network.HistoryPayload;
import com.niuqu.chatbubble.network.MediaRequestPayload;
import com.niuqu.chatbubble.network.MediaResponsePayload;
import com.niuqu.chatbubble.network.MediaUploadAckPayload;
import com.niuqu.chatbubble.network.MediaUploadPayload;
import com.niuqu.chatbubble.network.MediaCapPayload;
import com.niuqu.chatbubble.network.PlayerActionPayload;
import com.niuqu.chatbubble.network.PlayerActionRelayPayload;
import com.niuqu.chatbubble.network.QuoteSyncPayload;
import com.niuqu.chatbubble.network.ServerConfigSavePayload;
import com.niuqu.chatbubble.network.ServerConfigScreenPayload;
import com.niuqu.chatbubble.server.DiskMediaStore;
import com.niuqu.chatbubble.server.PlayerActionRelay;
import net.fabricmc.api.ModInitializer;
import com.mojang.brigadier.ParseResults;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatBubbleMod implements ModInitializer {
    public static final String MOD_ID = "e33chat";

    // Align with Forge/Neo: \p{L}\p{N} covers non-ASCII names (cracked servers allow
    // Chinese player names); @(\w+) only matched ASCII and missed them
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([\\p{L}\\p{N}_]+)");
    private static final int HISTORY_MAX = 50;

    private static final Map<UUID, QuotePending> pendingQuotes = new HashMap<>();
    private static final Deque<HistoryPayload.HistoryEntry> historyBuffer = new ArrayDeque<>();

    /** 引用块广播的去重标记：{@code <玩家 UUID>|<msgHash>} → 广播时刻。
     *
     *  <p>实机日志显示客户端是「先聊天、后 quote_sync」，所以两条广播路径都可能拿到同一条
     *  引用（{@code CHAT_MESSAGE} 兜底可能在 receiver 之前或之后）。两条路径共用这个短寿命
     *  集合，同一个 {@code msgHash} 只广播一次 —— 窗口与 {@link QuotePending} 的 10s 一致。 */
    private static final Map<String, Long> broadcastQuotes = new HashMap<>();
    private static final long QUOTE_TTL_MS = 10_000L;

    // Server-side settings (loaded per-world from <world>/serverconfig/e33chat-server.json)
    private static boolean historyEnabled;
    private static boolean useTpa;
    private static boolean templateDebug;
    private static boolean mediaEnabled;
    private static boolean mediaAutoClean = true;
    private static List<String> chatTemplates = List.of();
    private static List<String> whisperTemplates = List.of();
    private static boolean configLoaded;
    private static volatile com.niuqu.chatbubble.server.DiskMediaStore mediaStore;

    /** Lazily-created per-world media store (next to the server config). */
    private static com.niuqu.chatbubble.server.DiskMediaStore mediaStore(net.minecraft.server.MinecraftServer server) {
        com.niuqu.chatbubble.server.DiskMediaStore s = mediaStore;
        if (s == null) {
            synchronized (ChatBubbleMod.class) {
                s = mediaStore;
                if (s == null) {
                    s = new com.niuqu.chatbubble.server.DiskMediaStore(
                        server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                            .resolve("serverconfig").resolve("e33chat-media"));
                    mediaStore = s;
                }
            }
        }
        return s;
    }

    /** 客户端引用时同步上来的待定引用：{@code messageHash} 是「即将发出的那条消息」的指纹，
     *  {@code quotedHash} 是「被引用消息」的指纹（服务端只转发，不解释它）。 */
    private record QuotePending(String quotedSenderName, String quotedContent, String messageHash,
                                String quotedHash, long time) {}

    // A quote that never made it into a sent message (e.g. an anti-spam plugin blocked
    // it) must not tag a later unrelated message — expire after 10s (parity with Forge)
    private static QuotePending takeQuote(UUID playerUUID) {
        QuotePending quote = pendingQuotes.remove(playerUUID);
        if (quote != null && System.currentTimeMillis() - quote.time() > QUOTE_TTL_MS) return null;
        return quote;
    }

    /**
     * 登记「等聊天事件来取」的兼容状态（quote_sync 路径仍然保留，供能触发
     * {@code CHAT_MESSAGE} 的服务器在历史记录里带出 replyContent/replySender）。
     *
     * <p>顺手清掉过期的条目：离线服的聊天事件不触发，{@code takeQuote()} 永远没机会消费，
     * 不清就会随在线过的玩家数一直涨。
     */
    private static void rememberPendingQuote(ServerPlayerEntity player, String messageHash,
                                              String quotedHash, String quotedSender,
                                              String quotedContent) {
        long now = System.currentTimeMillis();
        pendingQuotes.entrySet().removeIf(e -> now - e.getValue().time() > QUOTE_TTL_MS);
        pendingQuotes.put(player.getUuid(),
            new QuotePending(quotedSender, quotedContent, messageHash, quotedHash, now));
    }

    /**
     * 唯一的 {@link ChatMetaPayload} 广播入口（quote_sync 即时广播 / CHAT_MESSAGE 兜底 /
     * 私聊命令三条路径都走这里），发给**所有**在线玩家（含发送者自己，路径统一）。
     *
     * @param dedupeQuote true = 这次真的带引用块，用 {@code <UUID>|<msgHash>} 做短期去重，
     *                    保证同一 {@code msgHash} 只广播一次；false = 纯 @ 提到广播，不占
     *                    去重标记（否则会把随后到达的 quote_sync 误判成重复而丢掉引用块）。
     */
    private static void broadcastChatMeta(MinecraftServer server, ServerPlayerEntity sender,
                                          String messageHash, String quotedHash,
                                          String quotedSender, String quotedContent,
                                          List<String> mentions, String source,
                                          boolean dedupeQuote) {
        String msgHash = messageHash == null ? "" : messageHash;
        String qHash = quotedHash == null ? "" : quotedHash;
        String qSender = quotedSender == null ? "" : quotedSender;
        String qContent = quotedContent == null ? "" : quotedContent;
        String from = sender.getName().getString();

        if (dedupeQuote) {
            String key = sender.getUuid() + "|" + msgHash;
            long now = System.currentTimeMillis();
            broadcastQuotes.entrySet().removeIf(e -> now - e.getValue() > QUOTE_TTL_MS);
            Long prev = broadcastQuotes.put(key, now);
            if (prev != null && now - prev <= QUOTE_TTL_MS) {
                srvLog("chat_meta skipped (dup) | from=" + from + " | msgHash=" + preview(msgHash)
                    + " | via=" + source + " (same msgHash already broadcast)");
                return;
            }
        }

        ChatMetaPayload meta = new ChatMetaPayload(sender.getUuid(), from, msgHash, qSender, qContent,
            mentions == null ? Collections.<String>emptyList() : mentions, qHash);
        var recipients = server.getPlayerManager().getPlayerList();
        for (ServerPlayerEntity p : recipients) ServerPlayNetworking.send(p, meta);
        srvLog("chat_meta broadcast | from=" + from + " | msgHash=" + preview(msgHash)
            + " | quotedHash=" + preview(qHash)
            + " | quoteSender=" + preview(qSender) + " | quote=" + preview(qContent)
            + " | mentions=" + (mentions == null ? 0 : mentions.size())
            + " | via=" + source + " | recipients=" + recipients.size());
    }

    /** 客户端送来的指纹仅作为字符串转发：限长避免它被当成「广播放大器」
     *  （正常值就是 int 的十进制串，最多 11 个字符）。 */
    private static String clampHash(String hash) {
        if (hash == null || hash.isEmpty()) return "";
        return hash.length() <= 64 ? hash : hash.substring(0, 64);
    }

    /**
     * {@code messageHash} 的契约是「本次消息明文 {@link String#hashCode()} 的十进制串」
     * （接收端 {@code ChatMessageStore.addMessage} 用同一算法对回聊天回显）。
     *
     * <p>旧版客户端（≤2.4.2）曾直接发明文，于是服务端原样广播后所有客户端都 match 不上。
     * 这里做一次兜底归一化：不像 hashCode 的形态（非十进制串）就自己哈希掉 —— 混版本时
     * 也能对上；新版客户端送来的就是十进制串，原样放行。
     */
    private static String normalizeMessageHash(String fromClient) {
        String hash = clampHash(fromClient);
        if (hash.isEmpty()) return hash;
        boolean numeric = hash.length() <= 11;
        for (int i = 0; numeric && i < hash.length(); i++) {
            char c = hash.charAt(i);
            if (i == 0 && c == '-') continue;
            if (c < '0' || c > '9') numeric = false;
        }
        return numeric ? hash : String.valueOf(hash.hashCode());
    }

    /** 服务端诊断日志：专用服务器读不到客户端的 debugLog 配置，所以直接打 INFO。
     *  只在引用/元数据这类低频事件上打，避免刷屏。 */
    private static void srvLog(String msg) {
        com.mojang.logging.LogUtils.getLogger().info("[e33chat] srv | " + msg);
    }

    /** 日志里只放前 40 个字符（换行压成空格），别把整段引用原文/长消息写进日志。 */
    private static String preview(String s) {
        if (s == null || s.isEmpty()) return "";
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= 40 ? one : one.substring(0, 40) + "...";
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(QuoteSyncPayload.ID, QuoteSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ChatMetaPayload.ID, ChatMetaPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HistoryPayload.ID, HistoryPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigSyncPayload.ID, ConfigSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigSyncV2Payload.ID, ConfigSyncV2Payload.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerConfigScreenPayload.ID, ServerConfigScreenPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ServerConfigSavePayload.ID, ServerConfigSavePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MediaUploadPayload.ID, MediaUploadPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MediaRequestPayload.ID, MediaRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MediaUploadAckPayload.ID, MediaUploadAckPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MediaResponsePayload.ID, MediaResponsePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MediaCapPayload.ID, MediaCapPayload.CODEC);

        // ── 群聊（group_*，方案 A：全部走服务端中继）──────────────────────────────
        // C2S：建群 / 发言 / 邀请 / 回应邀请 / 踢人 / 退群 / 改名 / 解散
        PayloadTypeRegistry.playC2S().register(GroupCreatePayload.ID, GroupCreatePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GroupMessagePayload.ID, GroupMessagePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GroupInvitePayload.ID, GroupInvitePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GroupInviteRespondPayload.ID, GroupInviteRespondPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GroupKickPayload.ID, GroupKickPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GroupLeavePayload.ID, GroupLeavePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GroupRenamePayload.ID, GroupRenamePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GroupDisbandPayload.ID, GroupDisbandPayload.CODEC);
        // S2C：群快照 / 消息转发 / 事件（被拉群、被踢、被解散…）/ 进群邀请与回应回执
        PayloadTypeRegistry.playS2C().register(GroupSyncPayload.ID, GroupSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(GroupMessageRelayPayload.ID, GroupMessageRelayPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(GroupEventPayload.ID, GroupEventPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(GroupInviteEventPayload.ID, GroupInviteEventPayload.CODEC);

        registerGroupReceivers();

        // ── 玩家互动动作（拍了拍等）────────────────────────────────────────────
        // C2S：目标 + 词条 id/自定义词 + 正文 + scope(+groupId)；S2C：服务端解析出的
        // 权威词条与发送者正式名，按 scope 只发给范围内的人（不回给发送者）。
        PayloadTypeRegistry.playC2S().register(PlayerActionPayload.ID, PlayerActionPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PlayerActionRelayPayload.ID, PlayerActionRelayPayload.CODEC);

        // 动作消息接收器：与群聊接收器同一套约定（整体 try/catch —— 异常绝不允许冒到
        // 网络层，否则服务端会直接断开玩家；payload receiver 已在服务端线程执行）。
        ServerPlayNetworking.registerGlobalReceiver(PlayerActionPayload.ID, (payload, context) -> {
            try {
                if (!payload.isValid()) {
                    srvLog("player_action ignored | invalid payload | " + payload);
                    return;
                }
                srvLog("player_action recv | player=" + context.player().getName().getString()
                    + " | " + payload);
                PlayerActionRelay.handle(context.server(), context.player(), payload);
            } catch (Throwable t) {
                srvLog("player_action failed | " + t);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(MediaUploadPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> com.niuqu.chatbubble.server.MediaService.handleUpload(
                player, mediaStore(context.server()), mediaEnabled, mediaAutoClean,
                payload.uploadId(), payload.index(), payload.totalChunks(),
                payload.totalBytes(), payload.contentType(), payload.chunk()));
        });

        ServerPlayNetworking.registerGlobalReceiver(MediaRequestPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> com.niuqu.chatbubble.server.MediaService.handleRequest(
                player, mediaStore(context.server()), payload.mediaId()));
        });

        ServerPlayNetworking.registerGlobalReceiver(QuoteSyncPayload.ID, (payload, context) -> {
            // 必须**就地**处理，不能再套一层 context.server().execute()：
            // Fabric 保证 payload receiver 已经在服务端线程跑（ServerPlayNetworking.PlayPayloadHandler
            // 的约定 + AbstractChanneledNetworkAddon 自己会 schedule 到主线程），多一跳只会推迟广播。
            ServerPlayerEntity player = context.player();
            // 归一化：messageHash 的契约是「本次消息明文的十进制 hashCode」，旧客户端（≤2.4.2）
            // 曾直接发明文 —— 归一化后客户端才 match 得上自己的聊天回显。
            String messageHash = normalizeMessageHash(payload.messageHash());
            String quotedHash = clampHash(payload.quotedHash());

            // 兼容兜底：仍登记 pending，让能触发 CHAT_MESSAGE 的服务器（单机 / 正版签名服）
            // 照旧取得到引用（历史记录里的 replyContent/replySender 也靠它）。
            rememberPendingQuote(player, messageHash, quotedHash,
                payload.quotedSenderName(), payload.quotedContent());
            srvLog("quote_sync recv | player=" + player.getName().getString()
                + " | msgHash=" + preview(messageHash)
                + " | quotedHash=" + preview(payload.quotedHash())
                + " | quoteSender=" + preview(payload.quotedSenderName())
                + " | quote=" + preview(payload.quotedContent()));

            // 核心修复：**收到就广播，不再等聊天事件**。
            // 离线服（online-mode=false）的聊天是未签名的 [Not Secure] 消息，
            // ServerMessageEvents.CHAT_MESSAGE 根本不触发（拿不到 sender/message）→ 旧的
            // 「聊天时 takeQuote() 再广播」模型永远不广播；而且客户端实测是「先聊天、
            // 后 quote_sync」，那个模型在时序上本来就不可靠。
            // 广播给所有在线玩家（含发送者自己，路径统一）：客户端按 messageHash 匹配本地
            // 回显，匹配不上就先挂 pending（10s TTL）再丢弃，跨端行为安全。
            // QuoteSyncPayload 没有私聊目标字段，引用一律广播；私聊的引用块本来也是
            // 由 receive 端按 messageHash 自行匹配，不会串到公屏气泡上。
            broadcastChatMeta(context.server(), player, messageHash, quotedHash,
                payload.quotedSenderName(), payload.quotedContent(),
                Collections.emptyList(), "quote_sync", true);
        });

        // Server-config GUI save: validate, persist to JSON, rebroadcast
        ServerPlayNetworking.registerGlobalReceiver(ServerConfigSavePayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                if (!player.getPermissions().hasPermission(new net.minecraft.command.permission.Permission.Level(
                        net.minecraft.command.permission.PermissionLevel.GAMEMASTERS))) {
                    player.sendMessage(Text.translatable("e33chat.server.op_required")
                        .formatted(Formatting.RED), false);
                    return;
                }
                ServerConfigSavePayload.handleServer(payload, player, cfg -> {
                    var path = context.server().getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                        .resolve("serverconfig").resolve("e33chat-server.json");
                    ServerConfigManager.save(path, cfg);
                    loadConfig(cfg);
                    broadcastServerConfig(context.server());
                });
            });
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String rawText = message.getContent().getString();
            int playerCount = sender.getEntityWorld().getServer() != null
                ? sender.getEntityWorld().getServer().getPlayerManager().getPlayerList().size() : 1;
            List<String> mentions = extractMentions(rawText, playerCount);

            QuotePending quote = takeQuote(sender.getUuid());
            String messageHash = quote != null
                ? normalizeMessageHash(quote.messageHash())
                : String.valueOf(rawText.hashCode());
            String quoteSender = quote != null ? quote.quotedSenderName() : "";
            String quoteContent = quote != null ? quote.quotedContent() : "";
            // 被引用消息的指纹：客户端算好一起发上来，服务端只做长度护栏后原样广播
            String quotedHash = quote != null ? quote.quotedHash() : "";

            if (quote != null || !mentions.isEmpty()) {
                var metaServer = sender.getEntityWorld().getServer();
                if (metaServer != null) {
                    if (quote != null) {
                        // 兼容兜底路径：与 quote_sync 的即时广播共用去重集合 —— quote_sync
                        // 已经广播过同一个 msgHash 时这里直接跳过，不会重复发；反过来
                        // （聊天事件先到、quote_sync 后到）则由 receiver 补发。
                        broadcastChatMeta(metaServer, sender, messageHash, quotedHash,
                            quoteSender, quoteContent, mentions, "chat_message", true);
                    } else {
                        // 纯 @ 提到广播：不带引用块，且**不占**去重标记（否则会把随后到达的
                        // quote_sync 误判成重复，真正的引用块就丢了）
                        broadcastChatMeta(metaServer, sender, messageHash, "", "", "",
                            mentions, "chat_message_mentions", false);
                    }
                }
            } else if (templateDebug) {
                // 只在服务器配置开 template_debug 时打（否则每条公屏都多一行）：
                // 有它就能区分「CHAT_MESSAGE 钩子没跑」和「引用没送到服务端」
                srvLog("chat_meta skipped | from=" + sender.getName().getString()
                    + " | msgHash=" + messageHash
                    + " | no pending quote and no mentions (quote_sync 没收到 / 已过期 / 被别的聊天处理抢先消费)");
            }

            addToHistory(new HistoryPayload.HistoryEntry(
                sender.getUuid(), sender.getName().getString(), rawText,
                System.currentTimeMillis(), false,
                quote != null ? quote.quotedContent() : null,
                quote != null ? quote.quotedSenderName() : null));
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // Load server config from <world>/serverconfig/ on first join (matching
            // NeoForge's per-world ModConfig.Type.SERVER convention)
            if (!configLoaded) {
                configLoaded = true;
                var configPath = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                    .resolve("serverconfig").resolve("e33chat-server.json");
                ServerConfig config = ServerConfigManager.load(configPath);
                loadConfig(config);
                if (mediaAutoClean) mediaStore(server).cleanupExpired();
            }

            // Always sync server-side settings so the client head menu matches the server
            sendServerConfigTripleTo(handler.player);

            // 群聊：把该玩家所属的**全部群**补发一遍（GroupSync）。客户端因此不需要自己
            // 持久化成员表；服务端重启丢群是已知 MVP 限制（见 README）。
            try {
                com.niuqu.chatbubble.server.GroupRegistry.syncAllTo(server, handler.player);
            } catch (Throwable t) {
                srvLog("group join sync failed | " + t);
            }

            if (!historyEnabled) return;
            if (historyBuffer.isEmpty()) return;
            ServerPlayNetworking.send(handler.player,
                new HistoryPayload(new ArrayList<>(historyBuffer)));
        });

        // /e33chat template commands + /e33chat gui
        com.niuqu.chatbubble.command.E33ChatCommands.register();

        // Drop the per-world media store on server stop so the next world (which may
        // be a different save directory) lazily rebuilds it against its own path;
        // also discard any in-flight upload sessions and their temp files.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            DiskMediaStore s = mediaStore;
            if (s != null) s.discardAllUploads();
            mediaStore = null;
            // 群聊：丢掉本世界的内存态（下一个世界按自己的 serverconfig 文件重新加载）
            try {
                com.niuqu.chatbubble.server.GroupRegistry.reset();
            } catch (Throwable ignored) {
            }
        });

        // Discard a leaving player's in-flight upload session (and temp file).
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DiskMediaStore s = mediaStore;
            if (s != null) s.discardUploadsFor(handler.player.getName().getString());
        });
    }

    /**
     * 群聊 C2S 接收器。
     *
     * <p>约定：
     * <ul>
     *   <li>每个 handler 整体 try/catch —— **服务端处理异常绝不允许踢人**（异常冒到网络层
     *       会直接断开玩家）。</li>
     *   <li>状态校验（是不是群成员 / 群是否存在）全部在 {@link com.niuqu.chatbubble.server.GroupRegistry}
     *       里做，这里只做「解析 + 派发 + 记日志」。</li>
     *   <li>payload receiver 已经在服务端线程执行（见 QuoteSync 的注释），不再套
     *       {@code context.server().execute()}。</li>
     * </ul>
     */
    private static void registerGroupReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(GroupCreatePayload.ID, (payload, context) -> {
            try {
                srvLog("group_create recv | player=" + context.player().getName().getString() + " | " + payload);
                com.niuqu.chatbubble.server.GroupRegistry.create(context.server(), context.player(), payload);
            } catch (Throwable t) {
                srvLog("group_create failed | " + t);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(GroupMessagePayload.ID, (payload, context) -> {
            try {
                if (!payload.isValid()) {
                    srvLog("group_message ignored | invalid payload | " + payload);
                    return;
                }
                com.niuqu.chatbubble.server.GroupRegistry.relayMessage(context.server(), context.player(), payload);
            } catch (Throwable t) {
                srvLog("group_message failed | " + t);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(GroupInvitePayload.ID, (payload, context) -> {
            try {
                if (!payload.isValid()) {
                    srvLog("group_invite ignored | invalid payload | " + payload);
                    return;
                }
                srvLog("group_invite recv | player=" + context.player().getName().getString() + " | " + payload);
                com.niuqu.chatbubble.server.GroupRegistry.invite(context.server(), context.player(), payload);
            } catch (Throwable t) {
                srvLog("group_invite failed | " + t);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(GroupInviteRespondPayload.ID, (payload, context) -> {
            try {
                if (!payload.isValid()) {
                    srvLog("group_invite_respond ignored | invalid payload | " + payload);
                    return;
                }
                srvLog("group_invite_respond recv | player=" + context.player().getName().getString()
                    + " | " + payload);
                com.niuqu.chatbubble.server.GroupRegistry.respondInvite(context.server(), context.player(), payload);
            } catch (Throwable t) {
                srvLog("group_invite_respond failed | " + t);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(GroupKickPayload.ID, (payload, context) -> {
            try {
                if (!payload.isValid()) {
                    srvLog("group_kick ignored | invalid payload | " + payload);
                    return;
                }
                srvLog("group_kick recv | player=" + context.player().getName().getString() + " | " + payload);
                com.niuqu.chatbubble.server.GroupRegistry.kick(context.server(), context.player(), payload);
            } catch (Throwable t) {
                srvLog("group_kick failed | " + t);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(GroupLeavePayload.ID, (payload, context) -> {
            try {
                if (!payload.isValid()) return;
                srvLog("group_leave recv | player=" + context.player().getName().getString() + " | " + payload);
                com.niuqu.chatbubble.server.GroupRegistry.leave(context.server(), context.player(), payload);
            } catch (Throwable t) {
                srvLog("group_leave failed | " + t);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(GroupRenamePayload.ID, (payload, context) -> {
            try {
                if (!payload.isValid()) {
                    srvLog("group_rename ignored | invalid payload | " + payload);
                    return;
                }
                srvLog("group_rename recv | player=" + context.player().getName().getString() + " | " + payload);
                com.niuqu.chatbubble.server.GroupRegistry.rename(context.server(), context.player(), payload);
            } catch (Throwable t) {
                srvLog("group_rename failed | " + t);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(GroupDisbandPayload.ID, (payload, context) -> {
            try {
                if (!payload.isValid()) return;
                srvLog("group_disband recv | player=" + context.player().getName().getString() + " | " + payload);
                com.niuqu.chatbubble.server.GroupRegistry.disband(context.server(), context.player(), payload);
            } catch (Throwable t) {
                srvLog("group_disband failed | " + t);
            }
        });
        srvLog("group receivers registered | c2s=8 | s2c=4");
    }

    // Called from CommandManagerMixin.execute (parity with Forge ChatServerListener.onCommand):    // /msg /tell /w /whisper carry a quote the client synced (QuoteSyncPayload); consume it
    // here and broadcast the quote meta, because vanilla private messages never hit
    // ServerMessageEvents.CHAT_MESSAGE
    public static void consumePrivateMessageQuote(ParseResults<ServerCommandSource> parseResults, String command) {
        String[] parts = command.split(" ");
        if (parts.length < 3) return;
        String label = parts[0];
        if (label.startsWith("/")) label = label.substring(1);
        if (!label.equals("msg") && !label.equals("tell") && !label.equals("w") && !label.equals("whisper")) return;
        ServerCommandSource source = parseResults.getContext().getSource();
        ServerPlayerEntity sender = source.getPlayer();
        if (sender == null) return;
        QuotePending quote = takeQuote(sender.getUuid());
        if (quote == null) return;
        String messageHash = normalizeMessageHash(quote.messageHash());
        // 同一入口：私聊的 /msg 若它的 quote_sync 已经先广播过（客户端发命令前就发 quote_sync），
        // 这里会被 msgHash 去重，不会重复发两遍。
        broadcastChatMeta(source.getServer(), sender, messageHash, quote.quotedHash(),
            quote.quotedSenderName(), quote.quotedContent(),
            Collections.emptyList(), "private_message", true);
    }

    private static void loadConfig(ServerConfig config) {
        useTpa = config.use_tpa;
        historyEnabled = config.history_enabled;
        templateDebug = config.template_debug;
        mediaEnabled = config.media_enabled;
        mediaAutoClean = config.media_auto_clean == null || config.media_auto_clean;
        chatTemplates = config.chat_templates != null ? config.chat_templates : List.of();
        whisperTemplates = config.whisper_templates != null ? config.whisper_templates : List.of();
    }

    public static void broadcastServerConfig(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            sendServerConfigTripleTo(p);
        }
    }

    // 三 payload 组合（use_tpa + templates + media cap）：JOIN 与 broadcast 共用。
    // media 是独立能力 type——旧客户端安全丢未知 payload，mediaEnabled 不会在混版本时 desync。
    private static void sendServerConfigTripleTo(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player,
            new ConfigSyncPayload(useTpa));
        ServerPlayNetworking.send(player, buildConfigV2());
        ServerPlayNetworking.send(player,
            new MediaCapPayload(mediaEnabled));
    }

    private static ConfigSyncV2Payload buildConfigV2() {
        return new ConfigSyncV2Payload(useTpa, new ArrayList<>(chatTemplates),
            new ArrayList<>(whisperTemplates), templateDebug);
    }

    // Server-side state accessors for the command handler
    public static boolean useTpa() { return useTpa; }
    public static boolean historyEnabled() { return historyEnabled; }
    public static boolean templateDebug() { return templateDebug; }
    public static boolean mediaEnabled() { return mediaEnabled; }
    public static boolean mediaAutoClean() { return mediaAutoClean; }
    public static List<String> chatTemplates() { return chatTemplates; }
    public static List<String> whisperTemplates() { return whisperTemplates; }
    public static void setTemplates(List<String> chat, List<String> whisper, boolean debug) {
        chatTemplates = chat;
        whisperTemplates = whisper;
        templateDebug = debug;
    }

    private static void addToHistory(HistoryPayload.HistoryEntry entry) {
        historyBuffer.addLast(entry);
        while (historyBuffer.size() > HISTORY_MAX)
            historyBuffer.removeFirst();
    }

    private static List<String> extractMentions(String text, int playerCount) {
        if (playerCount <= 1) return Collections.emptyList();
        List<String> mentions = new ArrayList<>();
        Matcher m = MENTION_PATTERN.matcher(text);
        while (m.find()) mentions.add(m.group(1));
        return mentions;
    }
}
