package com.niuqu.chatbubble.mixin;

import com.niuqu.chatbubble.ChatBubbleClientSetup;
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

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 「通道节食」：Fabric 会把客户端能收的全部 play 通道打包成 {@code minecraft:register}
 * 发给服务端（{@code AbstractChanneledNetworkAddon.sendInitialChannelRegistrationPacket()}
 * → {@code createRegistrationPayload(REGISTER, getReceivableChannels())}），而 Paper 对通道
 * 总数有硬上限，超限直接以 "Cannot register channel. Too many channels registered!" 踢人。
 *
 * <p>本 mixin 只在**开启配置开关**、**PLAY 阶段的那次初始申报**、且**服务端 brand 已知且
 * 不含 "fabric"**时，把 {@code createRegistrationPayload} 的 channels 实参换成空集合
 * （该方法的实现里空集合直接返回 null → 一个 {@code minecraft:register} 都不发）。
 *
 * <p>为什么只影响 PLAY 阶段：目标方法 {@code sendInitialChannelRegistrationPacket} 在
 * {@code AbstractChanneledNetworkAddon} 上，配置阶段由
 * {@code ClientConfigurationNetworkAddon} 调用、PLAY 阶段由 {@code ClientPlayNetworkAddon}
 * 调用（其 {@code onServerReady()} 里，紧跟 {@code ClientPlayConnectionEvents.JOIN} 之后）。
 * 注入点用 {@code instanceof ClientPlayNetworkAddon} 把配置阶段的调用整条放行，行为与旧版
 * 完全一致。
 *
 * <p>brand 从 PLAY 阶段的 {@code ClientPlayNetworkHandler} 上读：{@code ClientCommonNetworkHandler}
 * 在构造时就用 {@code ClientConnectionState.serverBrand()} 填好 {@code brand} 字段，而配置
 * 阶段处理器把配置阶段收到的 brand 原样带进 {@code ClientConnectionState}（{@code onReady}
 * 的字节码已核实），所以 JOIN 之后这里一定能拿到配置阶段就到达的服务端 brand。读不到
 * （null/空白）时**一律不节食**。
 */
@Mixin(AbstractChanneledNetworkAddon.class)
public abstract class ChannelRegistrationDietMixin {
    /**
     * 把即将写进 {@code minecraft:register} 的通道集合换成空集合。
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
        // 只有 PLAY 阶段的 ClientPlayNetworkAddon 才允许节食。
        if (!((Object) this instanceof ClientPlayNetworkAddon)) return channels;

        ChatBubbleConfig cfg = ChatBubbleClientSetup.config();
        Boolean enabled = cfg != null ? cfg.channelDietEnabled() : null;
        if (enabled == null || !enabled) return channels;   // 默认关闭 = 行为完全不变

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return channels;
        // 集成服务器（单机 / 自己是局域网主机）：服务端就是这个 jar 自己，节食只会砍掉联动，
        // 而且它不可能是 Paper —— 永远不节食。
        if (client.getServer() != null) return channels;

        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        String brand = handler != null ? handler.getBrand() : null;
        if (brand == null || brand.isBlank()) {
            // brand 未知 → 安全默认：不节食
            ChatMessageStore.debugLog("[e33chat] channel diet skipped | brand unknown");
            return channels;
        }
        if (brand.toLowerCase(Locale.ROOT).contains("fabric")) {
            // Fabric 服务端没有 Paper 的通道上限，且节食会让联动失效 → 不节食
            ChatMessageStore.debugLog(() -> "[e33chat] channel diet skipped | brand=" + brand);
            return channels;
        }

        int announced = channels == null ? 0 : channels.size();
        if (announced == 0) return channels;
        com.mojang.logging.LogUtils.getLogger().info(
            "[e33chat] channel diet | brand=" + brand + " | announced=" + announced + " -> 0");
        return List.of();
    }
}
