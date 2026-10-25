package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record HistoryPayload(List<HistoryPayload.HistoryEntry> entries)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<HistoryPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "chat_history"));

    public record HistoryEntry(
        UUID senderUUID,
        String senderName,
        String content,
        long time,
        boolean isSystem,
        String replyContent,
        String replySender
    ) {}

    /** 历史条目数的读侧上限（DoS 防护）。 */
    private static final int MAX_ENTRIES = 4096;

    public static final StreamCodec<FriendlyByteBuf, HistoryPayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> {
            List<HistoryEntry> out = value.entries != null ? value.entries : List.of();
            buf.writeVarInt(out.size());
            for (HistoryEntry e : out) {
                buf.writeUtf(e.senderUUID().toString());
                buf.writeUtf(e.senderName());
                buf.writeUtf(e.content());
                buf.writeLong(e.time());
                buf.writeBoolean(e.isSystem());
                buf.writeUtf(e.replyContent() != null ? e.replyContent() : "");
                buf.writeUtf(e.replySender() != null ? e.replySender() : "");
            }
        },
        buf -> {
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_ENTRIES) {
                throw new IllegalArgumentException("bad history size " + count);
            }
            List<HistoryEntry> entries = new ArrayList<>(Math.min(count, 128));
            for (int i = 0; i < count; i++) {
                entries.add(new HistoryEntry(
                    UUID.fromString(buf.readUtf()),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readLong(),
                    buf.readBoolean(),
                    nullOrEmpty(buf.readUtf()),
                    nullOrEmpty(buf.readUtf())
                ));
            }
            return new HistoryPayload(entries);
        }
    );

    private static String nullOrEmpty(String s) { return s == null || s.isEmpty() ? null : s; }

    @Override
    public Type<HistoryPayload> type() { return ID; }
}
