package com.niuqu.chatbubble;
import com.niuqu.chatbubble.config.ChatBubbleConfigScreen;
import com.niuqu.chatbubble.render.ChatBubbleHudOverlay;
import com.niuqu.chatbubble.render.RoundRectRenderer;

import com.niuqu.chatbubble.config.ChatBubbleConfig;
import com.niuqu.chatbubble.store.ChatMessageStore;
import com.niuqu.chatbubble.config.ServerConfigScreen;
import com.niuqu.chatbubble.config.ConfigManager;
import com.niuqu.chatbubble.image.ImageLoader;
import com.niuqu.chatbubble.network.ChatMetaPayload;
import com.niuqu.chatbubble.network.ConfigSyncPayload;
import com.niuqu.chatbubble.network.ConfigSyncV2Payload;
import com.niuqu.chatbubble.network.HistoryPayload;
import com.niuqu.chatbubble.network.MediaCapPayload;
import com.niuqu.chatbubble.network.ServerConfigScreenPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

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

    private static boolean customChannelsRegistered;
    private static java.util.Set<String> rememberedServers;

    /** 当前连接的目标服务器地址；单机/局域网（集成服务器）返回 null。 */
    private static String currentServerAddress(MinecraftClient client) {
        try {
            if (client == null || client.getCurrentServerEntry() == null) return null;
            String a = client.getCurrentServerEntry().address;
            return (a == null || a.isBlank()) ? null : a.trim().toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Path e33ServersPath() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.runDirectory == null) return null;
        return mc.runDirectory.toPath().resolve("config/e33chat").resolve(E33_SERVERS_FILE);
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
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.runDirectory == null) return false;
            return Files.exists(mc.runDirectory.toPath().resolve("config/e33chat").resolve(name));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 注册「服务器联动」用的自定义载荷接收器（引用同步、服务器配置界面、服务器图床、
     * 入服历史回填、消息元数据、配置下发）。
     *
     * <p>Fabric 会把 {@code ClientPlayNetworking.getGlobalReceivers()}（即这里注册的集合）
     * 在**配置阶段**声明给服务器；Paper 这类严格校验自定义载荷的服务端会直接以
     * "Invalid custom payload" 踢人。客户端在配置阶段无法得知服务端装了什么模组，因此
     * 默认只在**集成服务器**（单机 / 局域网）启动时注册；连远程服务器要启用联动时，
     * 用 config/e33chat/custom-channels.flag 显式打开。重复调用无副作用。
     */
    private static synchronized void registerCustomChannels() {
        if (customChannelsRegistered) return;
        customChannelsRegistered = true;
        ClientPlayNetworking.registerGlobalReceiver(ChatMetaPayload.ID, (payload, context) -> {
            context.client().execute(() -> ChatMessageStore.applyChatMeta(
                payload.senderUUID(), payload.senderName(), payload.messageHash(),
                payload.quoteSender(), payload.quoteContent(), payload.mentionTargets()));
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
            context.client().execute(() -> MinecraftClient.getInstance().setScreen(new ServerConfigScreen(
                MinecraftClient.getInstance().currentScreen,
                payload.useTpa(), payload.historyEnabled(), payload.templateDebug(),
                payload.mediaEnabled(), payload.mediaAutoClean(),
                payload.chatTemplates(), payload.whisperTemplates())));
        });
        com.niuqu.chatbubble.image.MediaClient.registerReceivers();
        ClientPlayNetworking.registerGlobalReceiver(MediaCapPayload.ID, (payload, context) -> {
            context.client().execute(() -> MediaCapPayload.handle(payload));
        });
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
        Path configDir = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/e33chat");
        configPath = configDir.resolve("e33chat-client.json");
        // Migration chain for the client config path (most recent first):
        // config/e33chat/client.json -> config/e33chat/e33chat-client.json
        // config/e33chat-client.json (2.3.1+) and config/e33chat.json (legacy) also move here.
        var logger = com.mojang.logging.LogUtils.getLogger();
        Path recentDirPath = configDir.resolve("client.json");
        Path legacyPath = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/e33chat-client.json");
        Path oldFlatPath = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/e33chat.json");
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

        // Custom payload channels are what the server-interop features need, but Fabric
        // announces the client's channel set to the server during the configuration phase,
        // and strict servers (Paper) reject that announcement and kick the client
        // ("Invalid custom payload"). The client cannot know the server's mods that early,
        // so by default we announce only where it is both useful and safe: the integrated
        // server (single player / LAN), registered when that server starts.
        //   config/e33chat/custom-channels.flag    -> also announce to remote servers
        //                                             (needed when the server runs E33Chat)
        //   config/e33chat/no-custom-channels.flag -> never announce
        boolean forceOff = flagFileExists(NO_CUSTOM_CHANNELS_FLAG);
        if (forceOff) {
            com.mojang.logging.LogUtils.getLogger().info(
                "[e33chat] custom payload channels: OFF (no-custom-channels.flag)");
        } else {
            // 单机 / 局域网：集成服务器启动即注册，联动功能全保留。
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING
                .register(server -> registerCustomChannels());
            // 远程：只对「已记住的、确认装了 E33Chat 的服务器」注册（按服务器地址记忆，
            // 见下面的 JOIN 钩子）。这样绝不会因为某个服务器而被永久打开，也就不会
            // 在之后的其它服务器上重复引发 "Invalid custom payload" 踢人。
            net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents.START
                .register((handler, client) -> {
                    String addr = currentServerAddress(client);
                    if (addr != null && rememberedServers().contains(addr)) registerCustomChannels();
                });
            com.mojang.logging.LogUtils.getLogger().info(
                "[e33chat] custom payload channels | single player/LAN=on | "
                + "remote=only for remembered E33Chat servers");
        }

        // 服务器也装了 E33Chat 时会为我们的 C2S 通道注册接收器，canSend() 因此为 true。
        // 远程服务器默认不声明通道（严格服务端会拒），这里**自动**记住这样的服务器，
        // 玩家不需要手动建任何文件；下次连接即带联动。首次连接因协议时序无法联动。
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
            (handler, sender, client) -> {
                if (forceOff) return;
                try {
                    String addr = currentServerAddress(client);
                    if (addr == null) return;   // 单机 / 局域网：走集成服务器那条路径
                    // canSend 反映的是「服务器是否声明支持该通道」，因此即使本次没声明也能
                    // 用它复查服务器当前是否真的还装着 E33Chat —— 白名单靠这个自我修复。
                    boolean serverHasE33 =
                        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                            com.niuqu.chatbubble.network.QuoteSyncPayload.ID);
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
                        "[e33chat] server runs E33Chat -> remembered '{}' (interop engages from the next join)",
                        addr);
                    client.execute(() -> {
                        if (client.player != null) {
                            client.player.sendMessage(
                                net.minecraft.text.Text.translatable("e33chat.channels.auto_enabled"), false);
                        }
                    });
                } catch (Throwable ignored) {
                    // best effort: never disturb the connection
                }
            });

        startStallWatchdog();

        ChatMessageStore.setMessageEffectObserver(
            new com.niuqu.chatbubble.chat.notification.ChatMessageEffects());

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (!config.enabled()) return;
            ChatBubbleHudOverlay.render(drawContext);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            lastTickMs = System.currentTimeMillis();
            ImageLoader.tick();
            // 纹理全部走 drawTexture(Identifier) 懒加载（getTexture 自动 new ResourceTexture），F3+T 重载后自动重读资源包新 PNG
            if (!config.enabled()) return;

            String key;
            if (client.world == null || client.player == null) {
                key = null;
            } else if (client.getServer() != null) {
                key = "SP:" + client.getServer().getSaveProperties().getLevelName();
            } else if (client.getCurrentServerEntry() != null) {
                key = "MP:" + client.getCurrentServerEntry().name;
            } else {
                key = "world";
            }
            ChatMessageStore.setCurrentWorld(key);
            ChatMessageStore.maybeAutoSave();

            if (client.currentScreen == null) {
                boolean leftDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                    client.getWindow().getHandle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_1) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                if (leftDown && !leftWasDown) {
                    double mx = client.mouse.getX() * (double)client.getWindow().getScaledWidth() / (double)client.getWindow().getWidth();
                    double my = client.mouse.getY() * (double)client.getWindow().getScaledHeight() / (double)client.getWindow().getHeight();
                    if (ChatBubbleHudOverlay.isMouseOverIcon(mx, my)) {
                        client.setScreen(new ChatBubbleScreen(""));
                    }
                }
                leftWasDown = leftDown;
            } else {
                leftWasDown = false;
            }
        });

        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) ->
            ScreenEvents.afterRender(screen).register((scr, g, mouseX, mouseY, delta) -> {
                if (config.enabled()) ChatBubbleHudOverlay.renderBannerForScreen(g);
            })
        );

        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
            new SimpleSynchronousResourceReloadListener() {
                @Override
                public Identifier getFabricId() {
                    return Identifier.of(ChatBubbleMod.MOD_ID, "shader_reload");
                }
                @Override
                public void reload(ResourceManager manager) {
                    RoundRectRenderer.resetShader();
                }
            }
        );
    }
}
