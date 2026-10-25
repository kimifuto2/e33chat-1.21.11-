package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: open the server-config GUI with the current server settings
 * snapshot. Triggered by /e33chat gui (OP). The handler (opening the client
 * Screen) lives in ChatBubbleClientSetup so a dedicated server never loads the
 * client-only Screen class.
 */
public record ServerConfigScreenPayload(boolean useTpa, boolean historyEnabled, boolean templateDebug,
                                        boolean mediaEnabled, boolean mediaAutoClean,
                                        List<String> chatTemplates, List<String> whisperTemplates)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServerConfigScreenPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "server_config_screen"));

    public static final StreamCodec<FriendlyByteBuf, ServerConfigScreenPayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> ServerConfigDto.encode(new ServerConfigDto(
            value.useTpa, value.historyEnabled, value.templateDebug, value.mediaEnabled,
            value.mediaAutoClean, value.chatTemplates, value.whisperTemplates), buf),
        buf -> {
            ServerConfigDto d = ServerConfigDto.decode(buf);
            return new ServerConfigScreenPayload(d.useTpa(), d.historyEnabled(), d.templateDebug(),
                d.mediaEnabled(), d.mediaAutoClean(), d.chatTemplates(), d.whisperTemplates());
        }
    );

    @Override
    public Type<ServerConfigScreenPayload> type() { return ID; }
}
