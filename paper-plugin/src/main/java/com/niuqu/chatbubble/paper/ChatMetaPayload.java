package com.niuqu.chatbubble.paper;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

/**
 * S2C {@code e33chat:chat_meta} 的编码器。
 *
 * <p>字段顺序与 Fabric 侧 {@code ChatMetaPayload.CODEC} 的编码器逐字段一致：
 * <pre>
 *   #  字段            类型                       编码
 *   1  senderUUID      string（UUID.toString()）  VarInt(UTF-8 字节数) + UTF-8，36 个 ASCII 字符
 *   2  senderName      string                    同上
 *   3  messageHash     string                    同上
 *   4  quoteSender     string                    同上
 *   5  quoteContent    string                    同上
 *   6  mentionTargets  列表                      VarInt(元素个数) + 每个元素一个 string
 *   7  quotedHash      string                    同上
 * </pre>
 * 第 6 项对应 {@code PacketByteBuf.writeCollection(list, PacketByteBuf::writeString)}：先
 * {@code writeVarInt(size)} 再逐个元素编码，MVP 恒为空列表（只写一个 VarInt 0）。
 *
 * <p>注意：客户端用 {@code UUID.fromString(readString())} 解析第 1 项，为空/畸形会让整条 meta
 * 被判为 invalid 而丢弃，所以这里永远写标准 UUID 字符串。
 */
record ChatMetaPayload(UUID senderUUID, String senderName, String messageHash, String quoteSender,
                       String quoteContent, List<String> mentionTargets, String quotedHash) {

    /** @return 可直接交给 {@code Player#sendPluginMessage} 的 payload 字节（不含通道名/长度前缀） */
    byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        McCodec.writeString(out, senderUUID == null ? "" : senderUUID.toString());
        McCodec.writeString(out, senderName);
        McCodec.writeString(out, messageHash);
        McCodec.writeString(out, quoteSender);
        McCodec.writeString(out, quoteContent);

        List<String> mentions = mentionTargets == null ? List.of() : mentionTargets;
        McCodec.writeVarInt(out, mentions.size());
        for (String mention : mentions) {
            McCodec.writeString(out, mention);
        }

        McCodec.writeString(out, quotedHash);
        return out.toByteArray();
    }
}
