package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: result of a media upload. mediaId is a 32-hex UUID when
 * successful (URL becomes e33chat://media/<mediaId>); otherwise error holds a
 * short reason and mediaId is null.
 */
public record MediaUploadAckPayload(long uploadId, String mediaId, String error)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MediaUploadAckPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "media_upload_ack"));

    public static final StreamCodec<FriendlyByteBuf, MediaUploadAckPayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> {
            buf.writeLong(value.uploadId);
            buf.writeUtf(value.mediaId != null ? value.mediaId : "");
            buf.writeUtf(value.error != null ? value.error : "");
        },
        buf -> new MediaUploadAckPayload(
            buf.readLong(),
            nullOrEmpty(buf.readUtf()),
            nullOrEmpty(buf.readUtf())
        )
    );

    private static String nullOrEmpty(String s) { return s == null || s.isEmpty() ? null : s; }

    @Override
    public Type<MediaUploadAckPayload> type() { return ID; }
}
