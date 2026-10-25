package com.niuqu.chatbubble.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code ClientCommonNetworkHandler.serverInfo} (protected final, no public getter).
 *
 * <p>Why this is needed: in 1.21.11 {@code MinecraftClient.getCurrentServerEntry()} is
 * implemented as {@code Nullables.map(getNetworkHandler(), h -> h.getServerInfo())}, and
 * {@code getNetworkHandler()} only has a value once the connection reaches PLAY. The custom
 * payload channels are announced during the <em>configuration</em> phase, where that call is
 * always null -- which is why the per-server allow list never took effect. This field is
 * assigned in the {@code ClientCommonNetworkHandler} constructor from
 * {@code ClientConnectionState.serverInfo()}, so it is already populated during configuration;
 * both {@code ClientConfigurationNetworkHandler} and {@code ClientPlayNetworkHandler} inherit it.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface ClientCommonNetworkHandlerAccessor {
    @Accessor("serverData")
    ServerData e33chat$getServerInfo();
}
