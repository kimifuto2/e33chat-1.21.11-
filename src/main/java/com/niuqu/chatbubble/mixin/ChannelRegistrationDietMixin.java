package com.niuqu.chatbubble.mixin;

import com.niuqu.chatbubble.ChatBubbleClientSetup;
import com.niuqu.chatbubble.channeldiet.ChannelDietFilter;
import com.niuqu.chatbubble.channeldiet.ChannelDietStats;
import com.niuqu.chatbubble.config.ChatBubbleConfig;
import com.niuqu.chatbubble.store.ChatMessageStore;
import net.fabricmc.fabric.impl.networking.AbstractChanneledNetworkAddon;
import net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「通道节食」：Fabric 会把客户端能收的全部 play 通道打包成 {@code minecraft:register}
 * 发给服务端（{@code AbstractChanneledNetworkAddon.sendInitialChannelRegistrationPacket()}
 * → {@code createRegistrationPayload(REGISTER, getReceivableChannels())}），而 Paper 对通道
 * 总数有硬上限 128（严格小于，即最多 127 条），超限直接踢人。
 *
 * <p>本 mixin 在 **PLAY 阶段的那次初始申报**上做三件事（配置阶段 / 服务端 addon 整条放行）：
 * <ol>
 *   <li><b>统计</b>：无论开关开着还是关着，都把这次要发的通道集合按命名空间
 *       分组计数后发布到 {@link ChannelDietStats}，供游戏内 GUI（{@code ChannelDietScreen}）读取；</li>
 *   <li><b>日志</b>：开关开着时打一行
 *       {@code [e33chat] channel diet | enabled=true | brand=.. | total=N | limit=120 | ...}；</li>
 *   <li><b>裁剪</b>：只看唯一总开关 {@code ChatBubbleConfig#channelDietEnabled}
 *       （3.0.3 起没有三态模式）。开关关闭 = **只统计不裁剪**，原样放行；
 *       开关打开时仍只在「非集成服务器 + brand 已知且不含 fabric」时才执行
 *       （Fabric 服务端/单机一律不裁剪，保持与旧版一致的安全语义）。</li>
 * </ol>
 *
 * <p>为什么只影响 PLAY 阶段：目标方法 {@code sendInitialChannelRegistrationPacket} 在
 * {@code AbstractChanneledNetworkAddon} 上，配置阶段由
 * {@code ClientConfigurationNetworkAddon} 调用、PLAY 阶段由 {@code ClientPlayNetworkAddon}
 * 调用（其 {@code onServerReady()} 里，紧跟 {@code ClientPlayConnectionEvents.JOIN} 之后）。
 * 注入点用 {@code instanceof ClientPlayNetworkAddon} 把配置阶段的调用整条放行。
 *
 * <p>brand 从 PLAY 阶段的 {@code ClientPlayNetworkHandler} 上读：{@code ClientCommonNetworkHandler}
 * 在构造时就用 {@code ClientConnectionState.serverBrand()} 填好 {@code brand} 字段，而配置
 * 阶段处理器把配置阶段收到的 brand 原样带进 {@code ClientConnectionState}（{@code onReady}
 * 的字节码已核实），所以 JOIN 之后这里一定能拿到配置阶段就到达的服务端 brand。
 * 读不到（null/空白）时**一律不裁剪**。
 */
@Mixin(AbstractChanneledNetworkAddon.class)
public abstract class ChannelRegistrationDietMixin {
    /**
     * 把即将写进 {@code minecraft:register} 的通道集合换成节食后的集合。
     *
     * <p>注入点（与 javap 核对的真实调用点一致）：{@code sendInitialChannelRegistrationPacket()}
     * 偏移 8 的 {@code invokevirtual createRegistrationPayload(CustomPayload$Id, Collection)}
     * —— 第 1 个实参就是 {@code getReceivableChannels()} 的结果，也是唯一会写进
     * {@code RegistrationPayload} 的集合。
     */
    @ModifyArg(
        method = "sendInitialChannelRegistrationPacket",
        at = @At(
            value = "INVOKE",
            target = "Lnet/fabricmc/fabric/impl/networking/AbstractChanneledNetworkAddon;createRegistrationPayload(Lnet/minecraft/network/packet/CustomPayload$Id;Ljava/util/Collection;)Lnet/fabricmc/fabric/impl/networking/RegistrationPayload;"),
        index = 1)
    private Collection<Identifier> e33chat$dietChannelRegistration(Collection<Identifier> channels) {
        try {
            return e33chat$diet(channels);
        } catch (Throwable t) {
            // 安全兜底：任何异常都按「不节食」放行，绝不影响连接
            ChatMessageStore.debugLog(() -> "[e33chat] channel diet skipped | " + t);
            return channels;
        }
    }

    private Collection<Identifier> e33chat$diet(Collection<Identifier> channels) {
        // 配置阶段（ClientConfigurationNetworkAddon）与服务端 addon 的调用原样放行：
        // 只有 PLAY 阶段的 ClientPlayNetworkAddon 才允许统计/节食。
        if (!((Object) this instanceof ClientPlayNetworkAddon)) return channels;

        ChatBubbleConfig cfg = ChatBubbleClientSetup.config();
        if (cfg == null) return channels;

        // 唯一总开关：开 = 按设置页裁剪；关 = 只统计不裁剪（发送时原样放行）。
        boolean dietEnabled = Boolean.TRUE.equals(cfg.channelDietEnabled());
        int limit = cfg.channelDietLimitResolved();
        List<String> disabled = cfg.channelDietDisabledResolved();

        MinecraftClient client = MinecraftClient.getInstance();
        boolean integrated = client != null && client.getServer() != null;
        String brand = null;
        if (client != null) {
            ClientPlayNetworkHandler handler = client.getNetworkHandler();
            brand = handler != null ? handler.getBrand() : null;
        }

        // 1) 统计（开关关闭也记录：GUI 在只统计模式下同样需要看到真实数字）
        ChannelDietStats.Snapshot snap;
        try {
            snap = ChannelDietStats.record(channels,
                dietEnabled ? ChannelDietFilter.MODE_ACTIVE : ChannelDietFilter.MODE_OFF,
                brand, integrated, limit, disabled);
        } catch (Throwable t) {
            ChatMessageStore.debugLog(() -> "[e33chat] channel diet stats failed | " + t);
            return channels;
        }
        int total = snap.total();
        int over = Math.max(0, total - limit);

        // 2) 总开关关闭 = 只统计不裁剪：通道声明原样发给服务端
        if (!dietEnabled) return channels;

        // 3) 安全语义：集成服务器（单机/局域网主机）与 Fabric / brand 未知一律不裁剪。
        //    集成服务器不可能是 Paper；Fabric 服务端没有 Paper 的通道上限，且裁剪会让联动失效。
        boolean fabricLike = integrated
            || brand == null || brand.isBlank()
            || brand.toLowerCase(Locale.ROOT).contains("fabric");
        if (fabricLike) {
            String reason = integrated ? "integrated-server"
                : (brand == null || brand.isBlank() ? "unknown-brand" : "fabric-brand");
            log("[e33chat] channel diet | enabled=true | brand=" + brandOf(brand)
                + " | total=" + total + " | limit=" + limit + " | over=" + over
                + " | skipped=" + reason);
            return channels;
        }

        if (channels == null || channels.isEmpty()) return channels;

        // 4) 裁剪：先按禁用命名空间整族剔除，仍超限再按通道数从多到少继续剔除。
        Map<String, Integer> counts = snap.asMap();
        ChannelDietFilter.Plan plan = ChannelDietFilter.plan(counts, limit, disabled);
        Set<String> keep = new HashSet<>(plan.kept());
        List<Identifier> kept = new ArrayList<>(channels.size());
        for (Identifier id : channels) {
            if (id == null) continue;
            if (keep.contains(id.getNamespace())) kept.add(id);
        }

        // 兜底：连「只留 e33chat」都还超限（127 条都压不住）时按条数截断，但 e33chat 一条不少。
        boolean truncated = false;
        if (plan.truncated() && kept.size() > limit) {
            truncated = true;
            List<Identifier> required = new ArrayList<>();
            List<Identifier> others = new ArrayList<>();
            for (Identifier id : kept) {
                if (ChannelDietFilter.REQUIRED_NAMESPACE.equals(id.getNamespace())) required.add(id);
                else others.add(id);
            }
            List<Identifier> capped = new ArrayList<>(Math.min(limit, kept.size()));
            capped.addAll(required);
            for (Identifier id : others) {
                if (capped.size() >= limit) break;
                capped.add(id);
            }
            kept = capped;
        }

        StringBuilder extra = new StringBuilder();
        if (!plan.droppedByConfig().isEmpty()) extra.append(" | disabled=").append(plan.droppedByConfig());
        if (!plan.droppedByLimit().isEmpty()) extra.append(" | autoTrim=").append(plan.droppedByLimit());
        if (truncated) extra.append(" | truncatedTo=").append(kept.size());
        log("[e33chat] channel diet | enabled=true | brand=" + brandOf(brand)
            + " | total=" + total + " | limit=" + limit + " | over=" + over
            + " | declared=" + kept.size() + extra);

        // 没有可裁剪的通道（例如全部被保留）时直接放行原集合，避免多复制一次
        if (kept.size() == channels.size()) return channels;
        return kept;
    }

    /** 日志里的 brand：null/空白写成 unknown，避免 `brand=null` / 行尾空白。 */
    private static String brandOf(String brand) {
        return brand == null || brand.isBlank() ? "unknown" : brand.trim();
    }

    private static void log(String line) {
        com.mojang.logging.LogUtils.getLogger().info(line);
    }
}
