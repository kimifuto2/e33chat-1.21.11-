package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S：往某个群聊发一条消息。服务端校验「发送者确实是群成员」后，用
 * {@link GroupMessageRelayPayload} 转发给**所有在线成员**（含发送者，用于回执/去重）。
 *
 * <p>线格式（固定顺序）：
 * <pre>
 *   String groupId     // 群 UUID 的字符串形式
 *   String content     // 明文（图片/表情仍走 [[CICode,...]] 括号码，服务端不解释）
 *   String msgHash     // 明文 String.hashCode() 的十进制串；客户端本地回显去重用
 * </pre>
 */
public record GroupMessagePayload(String groupId, String content, String msgHash) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GroupMessagePayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "group_message"));

    public static final StreamCodec<FriendlyByteBuf, GroupMessagePayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> {
            GroupWire.writeString(buf, value.groupId);
            GroupWire.writeString(buf, GroupWire.clamp(value.content, GroupWire.MAX_CONTENT));
            GroupWire.writeString(buf, GroupWire.clamp(value.msgHash, 64));
        },
        buf -> {
            try {
                String groupId = GroupWire.readString(buf);
                String content = GroupWire.readString(buf);
                String msgHash = GroupWire.readString(buf);
                return new GroupMessagePayload(groupId, content, msgHash);
            } catch (Throwable t) {
                return new GroupMessagePayload("", "", "");
            }
        }
    );

    /** 有效载荷：groupId 能解析成非零 UUID 且内容非空白。 */
    public boolean isValid() {
        return GroupWire.parseUuid(groupId) != null && content != null && !content.isBlank();
    }

    @Override
    public Type<GroupMessagePayload> type() { return ID; }

    @Override
    public String toString() {
        return "GroupMessagePayload{group=" + groupId + ", hash=" + msgHash
            + ", len=" + (content == null ? 0 : content.length()) + "}";
    }
}
