package com.niuqu.chatbubble;
import com.niuqu.chatbubble.config.ChatBubbleConfigScreen;
import com.niuqu.chatbubble.render.ChatBubbleHudOverlay;
import com.niuqu.chatbubble.render.RoundRectRenderer;

import com.niuqu.chatbubble.config.ChatBubbleConfig;
import com.niuqu.chatbubble.store.ChatMessageStore;
import com.niuqu.chatbubble.config.ServerConfigScreen;
import com.niuqu.chatbubble.config.ConfigManager;
import com.niuqu.chatbubble.image.ImageLoader;
import com.niuqu.chatbubble.mixin.ClientCommonNetworkHandlerAccessor;
import com.niuqu.chatbubble.network.ChatMetaPayload;
import com.niuqu.chatbubble.network.ConfigSyncPayload;
import com.niuqu.chatbubble.network.ConfigSyncV2Payload;
import com.niuqu.chatbubble.network.HistoryPayload;
import com.niuqu.chatbubble.network.MediaCapPayload;
import com.niuqu.chatbubble.network.MediaResponsePayload;
import com.niuqu.chatbubble.network.MediaUploadAckPayload;
import com.niuqu.chatbubble.network.ServerConfigScreenPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.PackType;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;

public class ChatBubbleClientSetup implements ClientModInitializer {
    private static ChatBubbleConfig config = ChatBubbleConfig.defaults();
    private static Path configPath;
    private static boolean leftWasDown;

    /** Main-thread heartbeat, read by the stall watchdog below. */
    private static volatile long lastTickMs;
    private static final long STALL_LOG_MS = 2000L;
    private static boolean watchdogStarted;

    /** Milliseconds since the last client tick (diagnostics only). */
    public static long lastTickAgeMs() {
        long t = lastTickMs;
        return t == 0L ? -1L : System.currentTimeMillis() - t;
    }

    /** 开关文件名：任何情况下都不声明自定义通道。 */
    private static final String NO_CUSTOM_CHANNELS_FLAG = "no-custom-channels.flag";
    /** 记住「服务端也装了 E33Chat」的服务器地址；只对它们声明通道。自动维护，玩家无需手改。 */
    private static final String E33_SERVERS_FILE = "e33chat-servers.txt";

    // ── 自定义载荷通道的「按连接」注册状态 ────────────────────────────────────
    // 旧实现用一个进程级一次性闩锁（注册过就永不再注销），单机/LAN 注册过的接收器会残留到
    // 之后的每一个连接；连 Paper 时 Fabric 会把它们塞进 minecraft:register 声明出去，被
    // "Invalid custom payload payload!" 踢掉。现在改为：进入配置阶段先全部注销，再按本次
    // 连接的目标决定是否注册；离开世界/服务器时注销。activeChannelIds = 本连接真正注册的 id。
    /** 本连接已注册的 GlobalReceiver id（注销后清空）；所有读写都在类锁内，见下面两个方法。 */
    private static final java.util.Set<Identifier> activeChannelIds = new java.util.LinkedHashSet<>();
    /** 有一次连接正在进行（配置阶段开始 ~ 落地成 PLAY 之前）：此时 world 为 null 但**不是**退世界。 */
    private static volatile boolean connectionPending;
    /** 已经成功 JOIN 过（真的进过世界/服务器），用于识别「退世界没收到 DISCONNECT」的兜底。 */
    private static volatile boolean playSessionSeen;
    private static java.util.Set<String> rememberedServers;

    // ── 「服务端是否也装了本模组」的探测状态 ──────────────────────────────────
    // JOIN 时用 ClientPlayNetworking.canSend(QuoteSyncPayload.ID) 判定，连接边界重置。
    // 私聊「增强功能不可用」的提示只在确认为 ABSENT 时出现：UNKNOWN（还没探测 / 探测
    // 抛异常 / 单机）一律不提示 —— 宁可晚一点，也不要误报。
    private enum ServerModState { UNKNOWN, PRESENT, ABSENT }

    private static volatile ServerModState serverModState = ServerModState.UNKNOWN;

    /** 本次连接内已经提示过「服务器没装 E33Chat」的私聊对象（归一化后的名字）。 */
    private static final java.util.Set<String> whisperUnavailableNotices =
        new java.util.LinkedHashSet<>();

    /** 服务端是否也装了本模组（联动功能可用）。**未探测时返回 false** —— 想判「服务端
     *  没装」必须走 {@link #claimWhisperUnavailableNotice(String)}（它还要求状态已确认为
     *  ABSENT），不要写成 {@code !serverHasE33Chat()}，否则首次入服会误报。 */
    public static boolean serverHasE33Chat() {
        return serverModState == ServerModState.PRESENT;
    }

    /** 探测是否已有结果（false = 尚未探测 / 探测失败）。 */
    public static boolean serverModStateKnown() {
        return serverModState != ServerModState.UNKNOWN;
    }

    /** 连接边界（JOIN 开头 / DISCONNECT）重置：探测结果失效，私聊提示去重表清空。 */
    private static synchronized void resetConnectionState() {
        serverModState = ServerModState.UNKNOWN;
        whisperUnavailableNotices.clear();
    }

    /** 玩家名归一化：去掉 § 色码（§ 与其后紧跟的 1 个字符）、首尾空白，统一小写。 */
    static String normalizePlayerName(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\u00a7') { i++; continue; }   // § 后紧跟的格式码一起丢掉
            sb.append(c);
        }
        return sb.toString().trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 私聊「增强功能不可用」提示的闸门：**本次连接内**首次进入某个私聊会话、且已确认
     * 服务端没装 E33Chat 时返回 true（并记为已提示）。未探测 / 服务端有模组 / 名字为空 → false。
     */
    public static synchronized boolean claimWhisperUnavailableNotice(String partner) {
        String key = normalizePlayerName(partner);
        if (key.isEmpty()) return false;
        if (serverModState != ServerModState.ABSENT) return false;
        return whisperUnavailableNotices.add(key);
    }

    /**
     * 从网络处理器上直接读目标服务器地址 —— **配置阶段与游戏阶段共用的唯一取地址入口**。
     *
     * <p>为什么不能只靠 {@code client.getCurrentServerEntry()}：1.21.11 里它的字节码是
     * {@code Nullables.map(getNetworkHandler(), h -> h.getServerInfo())}，而
     * {@code getNetworkHandler()} 只在 PLAY 阶段才有值。自定义载荷通道的声明发生在
     * **配置阶段**，那一刻它恒为 null —— 所以按地址的白名单从未真正生效过（每次都退化成
     * 「地址未知 → 不注册」或更糟的「地址未知 → 放行」）。{@code serverInfo} 是
     * {@code ClientCommonNetworkHandler} 的 protected final 字段，在构造函数里由
     * {@code ClientConnectionState.serverInfo()} 赋值，配置阶段就已经有值；同一实例随后被
     * 传入 PLAY 阶段的 {@code ClientConnectionState}（见
     * {@code ClientConfigurationNetworkHandler.onReady}），因此两个阶段读到的地址完全一致。
     *
     * @param handler 配置阶段的 {@code ClientConfigurationNetworkHandler} 或 PLAY 阶段的
     *                {@code ClientPlayNetworkHandler}，null 表示没有连接
     * @return 小写去空白的地址；拿不到（单机/未连接/accessor 未生效）返回 null
     */
    private static String serverAddressFromHandler(ClientCommonPacketListenerImpl handler) {
        if (handler == null) return null;
        try {
            ServerData info = ((ClientCommonNetworkHandlerAccessor) handler).e33chat$getServerInfo();
            if (info == null) return null;
            String a = info.ip;
            return (a == null || a.isBlank()) ? null : a.trim().toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable t) {
            // 安全默认：取不到就当「未知」，调用方一律按「不注册」处理，绝不放宽成放行
            ChatMessageStore.debugLog("[e33chat] server address lookup failed | " + t);
            return null;
        }
    }

    /** 当前连接的目标服务器地址；单机/局域网（集成服务器）返回 null。 */
    private static String currentServerAddress(Minecraft client) {
        try {
            if (client == null) return null;
            // PLAY 阶段：先读网络处理器上的 serverInfo（与配置阶段同一个字段、同一个值），
            // 这样「JOIN 时记住的地址」和「配置阶段比对的地址」一定是同一个字符串。
            String fromHandler = serverAddressFromHandler(client.getConnection());
            if (fromHandler != null) return fromHandler;
            if (client.getCurrentServer() == null) return null;
            String a = client.getCurrentServer().ip;
            return (a == null || a.isBlank()) ? null : a.trim().toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 配置阶段的「本连接要不要声明自定义通道」决定。INIT 与 START 共用（幂等）：先无条件注销
     * 上一次连接的残留，再按 {@code 集成服务器 / 本地网段 / 白名单} 决定是否重新注册。
     *
     * <p>**安全默认**：地址拿不到、或拿到了但既不是本地也不在白名单里 → 什么都不注册。
     * 绝不能因为 {@code addr == null} 就退化成「放行」。
     */
    private static void decideConfigurationChannels(ClientConfigurationPacketListenerImpl handler,
                                                    Minecraft client,
                                                    String phase) {
        try {
            unregisterCustomChannels("configuration " + phase);
            // 集成服务器（单机 / 自己是局域网主机）：用 client.getServer() 判定（不看地址），
            // 这类目标一定是同一个 JVM 里的服务端，声明通道零风险。注意 START 一定晚于
            // SERVER_STARTING，所以这里必须重新登记（刚被上面的注销清掉了）。
            boolean integrated = client.getSingleplayerServer() != null;
            // 远程连接的地址**只能**从配置阶段自己的网络处理器上取：getCurrentServerEntry()
            // 由 player 派生，此刻恒为 null（这正是白名单长期失效的根因）。
            String addr = serverAddressFromHandler(handler);
            if (addr == null) addr = currentServerAddress(client);   // 兜底（正常不会命中）
            // 局域网/私有网段 = 玩家自己的房间：等同单机待遇，配置阶段就注册，
            // 不再依赖白名单（否则别人的局域网房间要「第二次进房」才有 interop）。
            boolean local = addr != null && isLocalAddress(addr);
            boolean remembered = addr != null && rememberedServers().contains(addr);
            boolean allowed = integrated || local || remembered;
            if (allowed) registerCustomChannels();
            com.mojang.logging.LogUtils.getLogger().info(
                "[e33chat] custom payload channels | phase={} | integrated={} | local={} | remote={} | addr='{}' | channels={}",
                phase,
                integrated ? "yes" : "no",
                local ? "on" : "off",
                remembered ? "whitelist" : "not-whitelisted",
                addr,
                channelsActive() ? "on" : "off");
        } catch (Throwable t) {
            // 绝不影响连接：注册失败最多是联动不可用
            ChatMessageStore.debugLog("[e33chat] channel decision at configuration " + phase + " failed | " + t);
        }
    }

    /**
     * 从「服务器地址」里取出主机部分：{@code host:port} / {@code [::1]:port} / 裸 IPv6 都能处理。
     * 解析失败返回空串（调用方按「非本地」处理，绝不放宽判定）。
     */
    private static String hostOf(String address) {
        if (address == null) return "";
        String a = address.trim().toLowerCase(java.util.Locale.ROOT);
        if (a.isEmpty()) return "";
        if (a.startsWith("[")) {                       // [::1]:25565
            int end = a.indexOf(']');
            return end > 0 ? a.substring(0, end + 1) : a;
        }
        int first = a.indexOf(':');
        int last = a.lastIndexOf(':');
        // 恰好一个冒号 = host:port；零个或多个 = 主机名/裸 IPv6
        return (first > 0 && first == last) ? a.substring(0, first) : a;
    }

    /**
     * 「本地」地址判定：单机 / 局域网 / 私有网段。这类目标要么就是本机，要么在同一个
     * 内网里（玩家自己开的房间），对它们声明自定义通道既显然安全又必须**首次进房即生效**
     * （否则要「第二次进服」才有 interop）。
     *
     * <p>覆盖：{@code 127.0.0.0/8}（含 127.0.0.1）、{@code localhost}、{@code ::1}、
     * {@code 10.0.0.0/8}、{@code 172.16.0.0/12}、{@code 192.168.0.0/16}、
     * {@code *.local}（mDNS）、{@code fe80::/10}（IPv6 链路本地）。
     * 其余一律当远程（继续走白名单 + JOIN 自愈），不做任何放宽。
     */
    static boolean isLocalAddress(String address) {
        String host = hostOf(address);
        if (host.isEmpty()) return false;
        if (host.startsWith("[") && host.endsWith("]"))
            host = host.substring(1, host.length() - 1);
        if (host.isEmpty()) return false;
        if (host.equals("localhost") || host.endsWith(".localhost")) return true;
        if (host.equals("::1") || host.equals("0:0:0:0:0:0:0:1")) return true;
        if (host.endsWith(".local")) return true;
        // IPv6 字面量才有冒号：fe80::/10 = 首段 fe80..febf
        if (host.indexOf(':') >= 0 && host.length() >= 3 && host.startsWith("fe")) {
            char c = host.charAt(2);
            if (c == '8' || c == '9' || c == 'a' || c == 'b') return true;
        }
        String[] p = host.split("\\.", -1);
        if (p.length != 4) return false;               // 不是点分 IPv4（主机名）→ 远程
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                o[i] = Integer.parseInt(p[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (o[i] < 0 || o[i] > 255) return false;
        }
        if (o[0] == 127) return true;                  // 环回
        if (o[0] == 10) return true;                   // 10.0.0.0/8
        if (o[0] == 192 && o[1] == 168) return true;   // 192.168.0.0/16
        if (o[0] == 172 && o[1] >= 16 && o[1] <= 31) return true;   // 172.16.0.0/12
        return false;
    }

    private static Path e33ServersPath() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) return null;
        return mc.gameDirectory.toPath().resolve("config/e33chat").resolve(E33_SERVERS_FILE);
    }

    /** 已记住的服务器列表（自动维护）。 */
    private static synchronized java.util.Set<String> rememberedServers() {
        if (rememberedServers != null) return rememberedServers;
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try {
            Path p = e33ServersPath();
            if (p != null && Files.exists(p)) {
                for (String line : Files.readAllLines(p, java.nio.charset.StandardCharsets.UTF_8)) {
                    String s = line.trim().toLowerCase(java.util.Locale.ROOT);
                    if (!s.isEmpty() && !s.startsWith("#")) out.add(s);
                }
            }
        } catch (Throwable ignored) {
        }
        rememberedServers = out;
        return out;
    }

    private static synchronized void rememberServer(String addr) {
        try {
            java.util.Set<String> set = rememberedServers();
            if (!set.add(addr)) return;
            writeServersFile();
        } catch (Throwable ignored) {
        }
    }

    /** 服务器已不再装 E33Chat 时把它从白名单移除（自我修复）。返回是否真的移除了。 */
    private static synchronized boolean forgetServer(String addr) {
        try {
            java.util.Set<String> set = rememberedServers();
            if (!set.remove(addr)) return false;
            writeServersFile();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void writeServersFile() throws java.io.IOException {
        Path p = e33ServersPath();
        if (p == null) return;
        Files.createDirectories(p.getParent());
        String body = "# servers that also run E33Chat: interop channels are announced only to these.\n"
            + "# maintained automatically (added on detection, removed when the server no longer has E33Chat)\n"
            + String.join("\n", rememberedServers()) + "\n";
        Files.write(p, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** config/e33chat/<name> 是否存在（诊断/兼容开关，见 {@link #registerCustomChannels()}）。 */
    private static boolean flagFileExists(String name) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameDirectory == null) return false;
            return Files.exists(mc.gameDirectory.toPath().resolve("config/e33chat").resolve(name));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 我们为「服务器联动」注册的全部 play 阶段通道：引用同步、消息元数据、入服历史回填、
     * 配置下发（V1/V2）、服务器配置界面、服务器图床（上传回执 / 分块下载）、图床能力。
     *
     * <p>顺序 = 注册顺序，注销时按同一张表来，方便日志对照。注意 {@code MediaClient} 的两个
     * 通道是在它自己的 {@code registerReceivers()} 里注册的，但 id 归这里统一管 ——
     * 注销必须能覆盖到它们，否则图床通道会残留（同样会被 Paper 踢）。
     */
    private static java.util.List<Identifier> channelIds() {
        return java.util.List.of(
            ChatMetaPayload.ID.id(),
            HistoryPayload.ID.id(),
            ConfigSyncPayload.ID.id(),
            ConfigSyncV2Payload.ID.id(),
            ServerConfigScreenPayload.ID.id(),
            MediaUploadAckPayload.ID.id(),
            MediaResponsePayload.ID.id(),
            MediaCapPayload.ID.id());
    }

    /** 本连接当前是否注册着我们的接收器。 */
    private static synchronized boolean channelsActive() {
        return !activeChannelIds.isEmpty();
    }

    /**
     * 注册「服务器联动」用的自定义载荷接收器（引用同步、服务器配置界面、服务器图床、
     * 入服历史回填、消息元数据、配置下发）。
     *
     * <p>Fabric 会把 {@code ClientPlayNetworking.getGlobalReceivers()}（即这里注册的集合）
     * 声明给服务器：对 Fabric 服务端是配置阶段收到 {@code c:register} 后回一份 PLAY 相位的
     * {@code c:register}；对 Paper/vanilla 是 PLAY 阶段刚拿到 GameJoin 时发一条
     * {@code minecraft:register}。Paper 这类严格校验自定义载荷的服务端会直接以
     * "Invalid custom payload" 踢人。客户端在配置阶段无法得知服务端装了什么模组，因此
     * 默认只在**集成服务器**（单机 / 自己是局域网主机）启动时注册，以及连**局域网/私有
     * 网段地址**（见 {@link #isLocalAddress(String)}）时在配置阶段直接注册；连公网远程
     * 服务器要启用联动时，按服务器地址白名单（自动维护）放行。
     *
     * <p><b>幂等</b>：本次连接已经注册过就直接返回；注册失败会回滚（把已登记的 id 全部注销），
     * 绝不让半套接收器留下来。
     */
    private static synchronized void registerCustomChannels() {
        if (!activeChannelIds.isEmpty()) return;
        // 先登记 id 再逐个注册：万一中途某个 registerGlobalReceiver 抛异常，注销路径也能
        // 把我们登记过的 id 全部清干净（unregisterGlobalReceiver 对没注册过的 id 只是返回 null）。
        activeChannelIds.addAll(channelIds());
        try {
            ClientPlayNetworking.registerGlobalReceiver(ChatMetaPayload.ID, (payload, context) -> {
                // 版本不一致/截断时解码器返回占位载荷（senderUUID=null）：整条忽略，只记诊断日志。
                // 绝不能让半解码的 meta 进 store（会 NPE 或把引用块贴到错的气泡上）。
                if (!payload.isValid()) {
                    ChatMessageStore.debugLog("[e33chat] chat_meta ignored | undecodable payload (version mismatch?)");
                    return;
                }
                context.client().execute(() -> ChatMessageStore.applyChatMeta(
                    payload.senderUUID(), payload.senderName(), payload.messageHash(),
                    payload.quoteSender(), payload.quoteContent(), payload.mentionTargets(),
                    payload.quotedHash()));
            });
            ClientPlayNetworking.registerGlobalReceiver(HistoryPayload.ID, (payload, context) -> {
                context.client().execute(() -> ChatMessageStore.addHistoryMessages(payload.entries()));
            });
            ClientPlayNetworking.registerGlobalReceiver(ConfigSyncPayload.ID, (payload, context) -> {
                context.client().execute(() -> ConfigSyncPayload.handle(payload));
            });
            ClientPlayNetworking.registerGlobalReceiver(ConfigSyncV2Payload.ID, (payload, context) -> {
                context.client().execute(() -> ConfigSyncV2Payload.handle(payload));
            });
            // Server-config GUI: opened on the client only (server never loads the Screen)
            ClientPlayNetworking.registerGlobalReceiver(ServerConfigScreenPayload.ID, (payload, context) -> {
                context.client().execute(() -> Minecraft.getInstance().gui.setScreen(new ServerConfigScreen(
                    Minecraft.getInstance().gui.screen(),
                    payload.useTpa(), payload.historyEnabled(), payload.templateDebug(),
                    payload.mediaEnabled(), payload.mediaAutoClean(),
                    payload.chatTemplates(), payload.whisperTemplates())));
            });
            com.niuqu.chatbubble.image.MediaClient.registerReceivers();
            ClientPlayNetworking.registerGlobalReceiver(MediaCapPayload.ID, (payload, context) -> {
                context.client().execute(() -> MediaCapPayload.handle(payload));
            });
            ChatMessageStore.debugLog("[e33chat] channels registered for this connection | count="
                + activeChannelIds.size());
        } catch (Throwable t) {
            ChatMessageStore.debugLog("[e33chat] channel registration failed, rolling back | " + t);
            unregisterCustomChannels("registration failed");
        }
    }

    /**
     * 注销我们注册过的全部接收器并清空登记表。**幂等**：没有登记过就直接返回（重复调用、
     * 对已注销的连接再调一次都不会抛异常）；单个 id 注销失败只记日志，继续处理其余 id。
     *
     * <p>每次调用都是一条 {@code debugLog}，方便排查「连 Paper 时通道到底有没有残留」。
     */
    private static synchronized void unregisterCustomChannels(String reason) {
        if (activeChannelIds.isEmpty()) {
            ChatMessageStore.debugLog("[e33chat] channels unregistered | reason=" + reason + " | none registered");
            return;
        }
        int requested = activeChannelIds.size();
        int removed = 0;
        for (Identifier id : activeChannelIds) {
            try {
                if (ClientPlayNetworking.unregisterGlobalReceiver(id) != null) removed++;
            } catch (Throwable t) {
                ChatMessageStore.debugLog("[e33chat] channel unregister failed | id=" + id + " | " + t);
            }
        }
        activeChannelIds.clear();
        ChatMessageStore.debugLog("[e33chat] channels unregistered | reason=" + reason
            + " | count=" + requested + " | removed=" + removed);
    }

    /**
     * 退世界后的兜底清理：{@code ClientPlayConnectionEvents.DISCONNECT} 由 Fabric 的
     * {@code ConnectionMixin} 注入到 {@code ClientConnection.channelInactive} /
     * {@code handleDisconnection}，单机退世界要等本地连接真的关闭才会到（异步）。万一没到，
     * 这里在「进过世界、世界已经没了、也没有正在进行的连接」时补一刀。
     *
     * <p>空闲时每帧只做一次空集合判断，开销可忽略。
     */
    private static synchronized void cleanupChannelsAfterWorldExit(Minecraft client) {
        if (activeChannelIds.isEmpty()) return;
        if (connectionPending || !playSessionSeen) return;   // 正在连接 / 还没进过世界
        if (client.level != null || client.getConnection() != null) return;  // 还在世界里
        playSessionSeen = false;
        unregisterCustomChannels("left world (tick fallback)");
    }

    /** Logs when the client (main) thread stops ticking — the symptom behind
     *  "server reset the connection" reports: while the client is stuck it stops
     *  answering the server, which then drops it. */
    private static void startStallWatchdog() {
        if (watchdogStarted) return;
        watchdogStarted = true;
        Thread t = new Thread(() -> {
            long lastReport = 0L;
            while (true) {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.currentTimeMillis();
                long gap = now - lastTickMs;
                if (lastTickMs != 0L && gap >= STALL_LOG_MS && now - lastReport >= STALL_LOG_MS) {
                    lastReport = now;
                    com.mojang.logging.LogUtils.getLogger().warn(
                        "[e33chat] MAIN THREAD STALL | no client tick for " + gap + " ms");
                }
            }
        }, "e33chat-watchdog");
        t.setDaemon(true);
        t.start();
    }

    public static ChatBubbleConfig config() { return config; }

    public static void saveConfig(ChatBubbleConfig newConfig) {
        config = newConfig;
        com.mojang.logging.LogUtils.getLogger().info("[e33chat] Saving config | soundPublic=" + newConfig.soundPublic() + " | soundSystem=" + newConfig.soundSystem());
        ConfigManager.save(configPath, config);
    }

    @Override
    public void onInitializeClient() {
        Path configDir = Minecraft.getInstance().gameDirectory.toPath().resolve("config/e33chat");
        configPath = configDir.resolve("e33chat-client.json");
        // Migration chain for the client config path (most recent first):
        // config/e33chat/client.json -> config/e33chat/e33chat-client.json
        // config/e33chat-client.json (2.3.1+) and config/e33chat.json (legacy) also move here.
        var logger = com.mojang.logging.LogUtils.getLogger();
        Path recentDirPath = configDir.resolve("client.json");
        Path legacyPath = Minecraft.getInstance().gameDirectory.toPath().resolve("config/e33chat-client.json");
        Path oldFlatPath = Minecraft.getInstance().gameDirectory.toPath().resolve("config/e33chat.json");
        if (!Files.exists(configPath)) {
            if (Files.exists(recentDirPath)) {
                try {
                    Files.move(recentDirPath, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    logger.info("[e33chat] Migrated config from config/e33chat/client.json to config/e33chat/e33chat-client.json");
                } catch (Exception e) {
                    logger.warn("[e33chat] Config migration failed", e);
                }
            } else if (Files.exists(legacyPath)) {
                try {
                    Files.createDirectories(configDir);
                    Files.move(legacyPath, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    logger.info("[e33chat] Migrated config from config/e33chat-client.json to config/e33chat/e33chat-client.json");
                } catch (Exception e) {
                    logger.warn("[e33chat] Config migration failed", e);
                }
            } else if (Files.exists(oldFlatPath)) {
                config = ConfigManager.load(oldFlatPath);
                ConfigManager.save(configPath, config);
                try {
                    Files.delete(oldFlatPath);
                } catch (Exception e) {
                    logger.warn("[e33chat] Legacy config cleanup failed", e);
                }
                logger.info("[e33chat] Migrated config from config/e33chat.json to config/e33chat/e33chat-client.json");
            }
        }
        // Load is unconditional once the new path exists — the static field
        // starts as defaults() (non-null), so a null check can never trigger.
        if (Files.exists(configPath)) {
            config = ConfigManager.load(configPath);
        } else if (Files.exists(recentDirPath)) {
            // Migration move failed earlier (locked/IO) — read in place so
            // settings are never silently replaced by defaults.
            config = ConfigManager.load(recentDirPath);
        } else if (Files.exists(legacyPath)) {
            config = ConfigManager.load(legacyPath);
        } else if (Files.exists(oldFlatPath)) {
            config = ConfigManager.load(oldFlatPath);
        } else {
            config = ConfigManager.load(configPath);
        }

        // 自定义载荷通道 = 服务端联动功能的前提，但 Fabric 会把「客户端能收哪些通道」的声明
        // 发给服务端（Fabric 服：配置阶段收到 c:register 后回一份 PLAY 相位的 c:register；
        // Paper/vanilla：PLAY 阶段刚拿到 GameJoin 时发 minecraft:register）。Paper 这类严格
        // 校验的服务端会直接以 "Invalid custom payload" 踢人，而客户端在配置阶段无法得知
        // 服务端装了什么模组，所以只在「有用且安全」的目标上声明：集成服务器（单机 / 自己是
        // 局域网主机）以及白名单里的远程服务器。
        //   config/e33chat/no-custom-channels.flag -> 任何情况下都不声明（硬关）
        //
        // 关键：声明集合是**进程级全局的**（ClientPlayNetworking.getGlobalReceivers()），所以
        // 必须在每个连接边界先把自己注册的东西全部注销，否则单机/LAN 注册过的接收器会残留到
        // 之后的 Paper 连接里 → 被踢。下面三个钩子配合完成「按连接」的生命周期：
        //   START   : 先无条件注销，再按本次目标决定是否注册（幂等）
        //   DISCONNECT（PLAY）: 注销
        //   帧兜底  : 单机退世界时 DISCONNECT 是异步的，没等到就在这里补注销
        boolean forceOff = flagFileExists(NO_CUSTOM_CHANNELS_FLAG);
        if (forceOff) {
            com.mojang.logging.LogUtils.getLogger().info(
                "[e33chat] custom payload channels: OFF (no-custom-channels.flag)");
        } else {
            // 进入配置阶段的第一时刻（配置网络处理器刚构造完，**早于任何配置包**）：远程连接先把
            // 上一次连接残留的接收器清掉，并立刻按白名单做决定。这是「连 Paper 时不存在任何我们
            // 的接收器」的第一道保险 —— 它不依赖服务端是否发包，因此比 START 更早、更确定。
            // 单机/LAN 必须放过：SERVER_STARTING 的注册早于这里（集成服务器先启动再连接），
            // 清了会把单机联动自己关掉（config START 会再补回来，但没必要先砸一次）。
            net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents.INIT
                .register((handler, client) -> {
                    try {
                        connectionPending = true;
                        if (client.getSingleplayerServer() == null) {
                            decideConfigurationChannels(handler, client, "init");
                        }
                    } catch (Throwable t) {
                        ChatMessageStore.debugLog("[e33chat] channel cleanup at configuration init failed | " + t);
                    }
                });
            // 单机 / 局域网：集成服务器启动即注册，联动功能全保留（同一个 JVM，配置阶段就生效）。
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING
                .register(server -> {
                    connectionPending = true;
                    registerCustomChannels();
                });
            // 配置阶段 START：**先无条件注销**（幂等，重复注销不抛异常），再决定本连接是否注册。
            // 时序（已按 Fabric API 5.1.6 字节码核实）：START 由配置阶段的 minecraft:brand
            // 或 c:version 触发，而本模组的通道申报包只在收到服务端 c:register 时由
            // ClientConfigurationNetworkAddon.receiveRegistration 里的
            // sendInitialChannelRegistrationPacket() 发出 —— 服务端先发 version 再发 register
            // （CommonPacketsImpl.init 里两个 Task 的注册顺序），所以 START 的决定一定早于申报。
            net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents.START
                .register((handler, client) -> decideConfigurationChannels(handler, client, "start"));
            com.mojang.logging.LogUtils.getLogger().info(
                "[e33chat] custom payload channels | single player/LAN=on | "
                + "remote=only for remembered E33Chat servers | per-connection registration");
        }

        // 服务器也装了 E33Chat 时会为我们的 C2S 通道注册接收器，canSend() 因此为 true。
        // 远程服务器默认不声明通道（严格服务端会拒），这里**自动**记住这样的服务器，
        // 玩家不需要手动建任何文件；下次连接即带联动。首次连接因协议时序无法联动。
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
            (handler, sender, client) -> {
                // 连接边界：探测结果与「私聊提示已经出过」的去重表都重新开始
                // （换服 / 重连后要重新判断，提示也重新计一次）。
                resetConnectionState();
                // 已经落地成 PLAY：连接不再「pending」，并且本机确实进过世界（帧兜底用）。
                // 注意 Fabric 是在 onServerReady() 里先发 JOIN、再发 minecraft:register 的，
                // 所以这里（以及 START 里的决定）一定早于通道声明被组装。
                connectionPending = false;
                playSessionSeen = true;
                // 硬保证（第一道兜底）：到了 PLAY 就能拿到真实地址了。如果这次其实是**未白名单的
                // 远程服务器**（说明配置阶段那个「是不是集成服务器」的判断被极端情况骗了），立刻
                // 把残留注销——Fabric 是「先发 JOIN、再组装 minecraft:register」，所以这里还来得及。
                try {
                    String joinAddr = currentServerAddress(client);
                    // 集成服务器（单机 / 自己是局域网主机）永远不按地址注销：它的 serverInfo
                    // 不是「远程服务器地址」语义，拿去比对远程白名单只会误杀单机联动。
                    boolean joinIntegrated = client.getSingleplayerServer() != null;
                    if (!joinIntegrated
                        && joinAddr != null
                        && !isLocalAddress(joinAddr)
                        && !rememberedServers().contains(joinAddr)
                        && channelsActive()) {
                        unregisterCustomChannels("play join (remote server not whitelisted)");
                    }
                } catch (Throwable t) {
                    ChatMessageStore.debugLog("[e33chat] channel cleanup at join failed | " + t);
                }
                try {
                    String addr = currentServerAddress(client);
                    // 「服务端是否也装了本模组」：集成服务器（单机 / 自己是局域网主机）跑的
                    // 就是这个 jar 的服务端入口，一定装了；远程靠 canSend（服务端也装了本模组
                    // 才会为 QuoteSyncPayload 注册接收器）。该探测与「是否声明通道」
                    // （forceOff / 白名单）无关，所以必须放在 forceOff 早退之前 —— 否则开着
                    // forceOff 的玩家永远拿不到结果，私聊提示也就永远不出现。
                    boolean integrated = client.getSingleplayerServer() != null;
                    boolean serverHasE33 = integrated
                        || net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                            com.niuqu.chatbubble.network.QuoteSyncPayload.ID);
                    serverModState = serverHasE33 ? ServerModState.PRESENT : ServerModState.ABSENT;
                    if (integrated) return;   // 单机 / 自己是局域网主机：集成服务器那条路径
                    // 远程但地址拿不到（理论上不该发生：PLAY 阶段 handler 必有 serverInfo）：
                    // 无法比对白名单 → 既不记住也不遗忘，保持现状（安全默认，不写 "null" 进文件）。
                    if (addr == null) {
                        ChatMessageStore.debugLog("[e33chat] join: remote address unknown, whitelist untouched");
                        return;
                    }
                    if (forceOff) return;
                    // 局域网/私有网段在配置阶段就已注册（见 START 钩子），这里只需复查
                    // 「服务器是否真的装了 E33Chat」并维护白名单，不再是首次生效的分界点。
                    boolean local = isLocalAddress(addr);
                    // canSend 反映的是「服务器是否声明支持该通道」，因此即使本次没声明也能
                    // 用它复查服务器当前是否真的还装着 E33Chat —— 白名单靠这个自我修复。
                    if (!serverHasE33) {
                        // 服务器已移除 E33Chat：忘掉它，之后连它不再声明通道（避免被严格服务端踢）
                        if (forgetServer(addr)) {
                            com.mojang.logging.LogUtils.getLogger().info(
                                "[e33chat] server no longer runs E33Chat -> forgot '{}'", addr);
                        }
                        return;
                    }
                    if (rememberedServers().contains(addr)) return;
                    rememberServer(addr);
                    com.mojang.logging.LogUtils.getLogger().info(
                        local
                            ? "[e33chat] server runs E33Chat -> remembered '{}' (local network: interop already on)"
                            : "[e33chat] server runs E33Chat -> remembered '{}' (interop engages from the next join)",
                        addr);
                    client.execute(() -> {
                        if (client.player != null) {
                            client.player.sendSystemMessage(
                                net.minecraft.network.chat.Component.translatable("e33chat.channels.auto_enabled"));
                        }
                    });
                } catch (Throwable ignored) {
                    // best effort: never disturb the connection
                }
            });

        // 断开（回主菜单 / 换服 / 退出单机世界）时：
        //   ① 注销本次连接注册的接收器 —— 否则会残留到下一次连接；连 Paper 时这些通道会被
        //      塞进声明里，正是 "Invalid custom payload" 踢人的来源。
        //   ② 清掉连接级状态：探测结果作废、私聊提示去重表清空。
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
            (handler, client) -> {
                try {
                    unregisterCustomChannels("play disconnect");
                } catch (Throwable t) {
                    ChatMessageStore.debugLog("[e33chat] channel cleanup at disconnect failed | " + t);
                } finally {
                    connectionPending = false;
                    playSessionSeen = false;
                    resetConnectionState();
                }
            });

        // 配置阶段连接失败（被拒 / 握手失败）：同样要清干净，否则为这次连接注册的通道会留到
        // 下一个服务端。（成功的配置→PLAY 切换不会触发这个事件，只会触发上面的 PLAY 事件。）
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents.DISCONNECT
            .register((handler, client) -> {
                try {
                    unregisterCustomChannels("configuration disconnect");
                } catch (Throwable t) {
                    ChatMessageStore.debugLog("[e33chat] channel cleanup at configuration disconnect failed | " + t);
                } finally {
                    connectionPending = false;
                    playSessionSeen = false;
                }
            });

        startStallWatchdog();

        ChatMessageStore.setMessageEffectObserver(
            new com.niuqu.chatbubble.chat.notification.ChatMessageEffects());

        // 26.3: HudRenderCallback 已删除，HUD 改由 fabric-rendering-v1 的 HudElementRegistry 注册。
        // addLast = 排到所有原版 HUD 元素之后，与旧 HudRenderCallback.EVENT（HUD 绘制末尾）等价。
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(ChatBubbleMod.MOD_ID, "chat_icon"),
            (drawContext, deltaTracker) -> {
                if (!config.enabled()) return;
                ChatBubbleHudOverlay.render(drawContext);
            });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            lastTickMs = System.currentTimeMillis();
            // 退世界兜底：DISCONNECT 要等本地连接真正关闭（异步），没等到就在这里注销。
            cleanupChannelsAfterWorldExit(client);
            ImageLoader.tick();
            // 纹理全部走 drawTexture(Identifier) 懒加载（getTexture 自动 new ResourceTexture），F3+T 重载后自动重读资源包新 PNG
            if (!config.enabled()) return;

            String key;
            if (client.level == null || client.player == null) {
                key = null;
            } else if (client.getSingleplayerServer() != null) {
                key = "SP:" + client.getSingleplayerServer().getWorldData().getLevelName();
            } else if (client.getCurrentServer() != null) {
                key = "MP:" + client.getCurrentServer().name;
            } else {
                key = "world";
            }
            ChatMessageStore.setCurrentWorld(key);
            ChatMessageStore.maybeAutoSave();

            if (client.gui.screen() == null) {
                boolean leftDown = client.mouseHandler.isLeftPressed();
                if (leftDown && !leftWasDown) {
                    double mx = client.mouseHandler.xpos() * (double)client.getWindow().getGuiScaledWidth() / (double)client.getWindow().getWidth();
                    double my = client.mouseHandler.ypos() * (double)client.getWindow().getGuiScaledHeight() / (double)client.getWindow().getHeight();
                    if (ChatBubbleHudOverlay.isMouseOverIcon(mx, my)) {
                        client.gui.setScreen(new ChatBubbleScreen(""));
                    }
                }
                leftWasDown = leftDown;
            } else {
                leftWasDown = false;
            }
        });

        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) ->
            ScreenEvents.afterExtract(screen).register((scr, g, mouseX, mouseY, delta) -> {
                if (config.enabled()) ChatBubbleHudOverlay.renderBannerForScreen(g);
            })
        );

        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            new SimpleSynchronousResourceReloadListener() {
                @Override
                public Identifier getFabricId() {
                    return Identifier.fromNamespaceAndPath(ChatBubbleMod.MOD_ID, "shader_reload");
                }
                @Override
                public void onResourceManagerReload(ResourceManager manager) {
                    RoundRectRenderer.resetShader();
                }
            }
        );
    }
}
