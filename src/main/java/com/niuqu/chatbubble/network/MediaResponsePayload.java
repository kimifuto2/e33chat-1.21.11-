package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: one chunk of a media download. The special form
 * (index 0, totalChunks 1, empty chunk) signals "not found" so the client can
 * fail the fetch instead of hanging.
 */
public record MediaResponsePayload(String mediaId, int index, int totalChunks, byte[] chunk)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MediaResponsePayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "media_response"));

    public static final StreamCodec<FriendlyByteBuf, MediaResponsePayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> {
            buf.writeUtf(value.mediaId);
            buf.writeInt(value.index);
            buf.writeInt(value.totalChunks);
            buf.writeByteArray(value.chunk);
        },
        buf -> new MediaResponsePayload(
            buf.readUtf(),
            buf.readInt(),
            buf.readInt(),
            buf.readByteArray()
        )
    );

    @Override
    public Type<MediaResponsePayload> type() { return ID; }
}
