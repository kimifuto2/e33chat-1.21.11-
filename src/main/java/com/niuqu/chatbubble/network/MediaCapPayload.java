package com.niuqu.chatbubble.network;

import com.niuqu.chatbubble.image.MediaClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: media hosting capability (2.3.13). A separate type on
 * purpose: old clients drop unknown payloads harmlessly, so a mixed-version
 * client/server never desyncs (an appended field inside ConfigSyncV2 would
 * break old clients decoding a shorter body). Absent payload = disabled.
 */
public record MediaCapPayload(boolean mediaEnabled) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MediaCapPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "media_cap"));

    public static final StreamCodec<FriendlyByteBuf, MediaCapPayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> buf.writeBoolean(value.mediaEnabled),
        buf -> new MediaCapPayload(buf.readBoolean())
    );

    @Override
    public Type<MediaCapPayload> type() { return ID; }

    public static void handle(MediaCapPayload payload) {
        MediaClient.setServerEnabled(payload.mediaEnabled());
    }
}
