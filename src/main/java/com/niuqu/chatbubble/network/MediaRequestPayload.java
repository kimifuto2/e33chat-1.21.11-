package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server: request to download a server-hosted media file. */
public record MediaRequestPayload(String mediaId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MediaRequestPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "media_request"));

    public static final StreamCodec<FriendlyByteBuf, MediaRequestPayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> buf.writeUtf(value.mediaId),
        buf -> new MediaRequestPayload(buf.readUtf())
    );

    @Override
    public Type<MediaRequestPayload> type() { return ID; }
}
