package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S2C：服务端把「谁发的 / 这条消息的指纹 / 引用块 / @ 列表 / 被引用消息的指纹」
 * 广播给所有客户端。
 *
 * <p>{@code messageHash}＝这条消息自身明文内容的 hashCode（用于把 meta 对回聊天
 * 回显）；{@code quotedHash}＝**被引用消息**明文内容的 hashCode（新增，用于跨客户端
 * 精确定位原文，见 {@link QuoteSyncPayload}）。
 *
 * <p>线格式兼容：{@code quotedHash} 追加在末尾。新客户端连旧服务端时第四个之后
 * 没有字节 → 读成空串；解码过程中的任何异常（截断 / 版本不一致 / UUID 损坏）都被
 * 捕获并返回 {@link #invalid()}，由接收端整体忽略，**绝不让异常冒到网络层**。
 */
public record ChatMetaPayload(UUID senderUUID, String senderName, String messageHash,
                               String quoteSender, String quoteContent, List<String> mentionTargets,
                               String quotedHash)
        implements CustomPayload {

    public static final CustomPayload.Id<ChatMetaPayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "chat_meta"));

    /** 解码失败时的占位载荷：senderUUID 为 null，接收端据此整体忽略。 */
    public static ChatMetaPayload invalid() {
        return new ChatMetaPayload(null, "", "", "", "", List.of(), "");
    }

    /** 解码是否成功（false = 版本不一致/截断，调用方必须忽略该载荷）。 */
    public boolean isValid() {
        return senderUUID != null;
    }

    public static final PacketCodec<PacketByteBuf, ChatMetaPayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            buf.writeString(value.senderUUID == null ? "" : value.senderUUID.toString());
            buf.writeString(str(value.senderName));
            buf.writeString(str(value.messageHash));
            buf.writeString(str(value.quoteSender));
            buf.writeString(str(value.quoteContent));
            buf.writeCollection(value.mentionTargets != null ? value.mentionTargets : List.of(),
                PacketByteBuf::writeString);
            buf.writeString(str(value.quotedHash));
        },
        buf -> {
            try {
                UUID sender = null;
                try {
                    sender = UUID.fromString(buf.readString());
                } catch (Throwable ignored) {
                    // 损坏的 UUID 只让这条 meta 失效，不影响连接
                }
                String senderName = buf.readString();
                String messageHash = buf.readString();
                String quoteSender = buf.readString();
                String quoteContent = buf.readString();
                List<String> mentions = buf.readList(PacketByteBuf::readString);
                // 追加字段：旧版服务端不发 → 读不到就当空（退化成内容兜底）
                String quotedHash = buf.isReadable() ? buf.readString() : "";
                if (sender == null) return invalid();
                return new ChatMetaPayload(sender, senderName, messageHash, quoteSender, quoteContent,
                    mentions != null ? mentions : List.of(), quotedHash);
            } catch (Throwable t) {
                // 版本不一致 / 截断：返回占位载荷，接收端忽略并记日志；抛出去会直接断开连接
                return invalid();
            }
        }
    );

    private static String str(String s) { return s == null ? "" : s; }

    @Override
    public Id<ChatMetaPayload> getId() { return ID; }
}
