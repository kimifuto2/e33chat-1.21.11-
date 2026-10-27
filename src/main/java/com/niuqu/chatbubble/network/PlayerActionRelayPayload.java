package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * S2C：服务端转发的动作消息（拍了拍等）。收端按 {@code scope} 把它插进对应会话
 * （公屏 / 指定群 / 与对方的私聊），并渲染成系统信息样式的一行：
 * {@code A 拍了拍 B 说：正文}。
 *
 * <p>线格式（固定顺序）：
 * <pre>
 *   UUID   senderUUID
 *   String senderName   // 服务端用的正式玩家名（不是客户端自报的，防冒名）
 *   String targetUUID
 *   String targetName   // 在线目标用服务端解析出的名字，离线才用客户端带来的名字
 *   String verbText     // 服务端解析出的权威词条（"拍了拍" / 自定义词）
 *   String content
 *   String scope        // public/group/private
 *   String groupId      // scope=group 才有意义
 * </pre>
 */
public record PlayerActionRelayPayload(UUID senderUUID, String senderName, String targetUUID,
                                       String targetName, String verbText, String content,
                                       String scope, String groupId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PlayerActionRelayPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "player_action_relay"));

    public static final StreamCodec<FriendlyByteBuf, PlayerActionRelayPayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> {
            buf.writeUUID(value.senderUUID == null ? new UUID(0, 0) : value.senderUUID);
            GroupWire.writeString(buf, value.senderName);
            GroupWire.writeString(buf, value.targetUUID);
            GroupWire.writeString(buf, value.targetName);
            GroupWire.writeString(buf, value.verbText);
            GroupWire.writeString(buf, value.content);
            GroupWire.writeString(buf, value.scope);
            GroupWire.writeString(buf, value.groupId);
        },
        buf -> {
            try {
                UUID senderUUID = buf.isReadable() ? buf.readUUID() : null;
                String senderName = GroupWire.readString(buf);
                String targetUUID = GroupWire.readString(buf);
                String targetName = GroupWire.readString(buf);
                String verbText = GroupWire.readString(buf);
                String content = GroupWire.readString(buf);
                String scope = GroupWire.readString(buf);
                String groupId = GroupWire.readString(buf);
                return new PlayerActionRelayPayload(senderUUID, senderName, targetUUID, targetName,
                    verbText, content, scope, groupId);
            } catch (Throwable t) {
                return new PlayerActionRelayPayload(null, "", "", "", "", "", "", "");
            }
        }
    );

    public boolean isValid() {
        return senderUUID != null && !senderUUID.equals(new UUID(0, 0))
            && senderName != null && !senderName.isEmpty()
            && verbText != null && !verbText.isEmpty()
            && content != null && !content.isBlank();
    }

    @Override
    public Type<PlayerActionRelayPayload> type() {
        return ID;
    }

    @Override
    public String toString() {
        return "PlayerActionRelayPayload{sender=" + senderName + ", target=" + targetName
            + ", verb=" + verbText + ", scope=" + scope + ", group=" + groupId
            + ", len=" + (content == null ? 0 : content.length()) + "}";
    }
}
