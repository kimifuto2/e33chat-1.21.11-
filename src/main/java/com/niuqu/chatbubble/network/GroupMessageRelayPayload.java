package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * S2C：服务端把某条群消息转发给群成员（**含发送者自己**，客户端按 msgHash 去重）。
 *
 * <p>线格式（固定顺序）：
 * <pre>
 *   String groupId
 *   UUID senderUUID
 *   String senderName
 *   String content
 *   String msgHash
 * </pre>
 */
public record GroupMessageRelayPayload(String groupId, UUID senderUUID, String senderName,
                                       String content, String msgHash) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GroupMessageRelayPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "group_message_relay"));

    public static final StreamCodec<FriendlyByteBuf, GroupMessageRelayPayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> {
            GroupWire.writeString(buf, value.groupId);
            buf.writeUUID(value.senderUUID == null ? new UUID(0, 0) : value.senderUUID);
            GroupWire.writeString(buf, GroupWire.clamp(value.senderName, GroupWire.MAX_NAME));
            GroupWire.writeString(buf, GroupWire.clamp(value.content, GroupWire.MAX_CONTENT));
            GroupWire.writeString(buf, GroupWire.clamp(value.msgHash, 64));
        },
        buf -> {
            try {
                String groupId = GroupWire.readString(buf);
                UUID sender = buf.isReadable() ? buf.readUUID() : null;
                String senderName = GroupWire.readString(buf);
                String content = GroupWire.readString(buf);
                String msgHash = GroupWire.readString(buf);
                return new GroupMessageRelayPayload(groupId, sender, senderName, content, msgHash);
            } catch (Throwable t) {
                return new GroupMessageRelayPayload("", null, "", "", "");
            }
        }
    );

    public boolean isValid() {
        return GroupWire.parseUuid(groupId) != null && senderUUID != null
            && content != null && !content.isBlank();
    }

    @Override
    public Type<GroupMessageRelayPayload> type() { return ID; }

    @Override
    public String toString() {
        return "GroupMessageRelayPayload{group=" + groupId + ", sender=" + senderName
            + ", hash=" + msgHash + ", len=" + (content == null ? 0 : content.length()) + "}";
    }
}
