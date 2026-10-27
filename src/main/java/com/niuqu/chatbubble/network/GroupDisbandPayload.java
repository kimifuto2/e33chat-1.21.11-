package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S：解散群聊。服务端把群整体删掉，给**所有在线成员**发
 * {@link GroupEventPayload}(kind=DISBANDED, text=群名)；离线成员下次登录时已查不到该群。
 *
 * <p>线格式：{@code String groupId}。
 */
public record GroupDisbandPayload(String groupId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GroupDisbandPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "group_disband"));

    public static final StreamCodec<FriendlyByteBuf, GroupDisbandPayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> GroupWire.writeString(buf, value.groupId),
        buf -> {
            try {
                return new GroupDisbandPayload(GroupWire.readString(buf));
            } catch (Throwable t) {
                return new GroupDisbandPayload("");
            }
        }
    );

    public boolean isValid() {
        return GroupWire.parseUuid(groupId) != null;
    }

    @Override
    public Type<GroupDisbandPayload> type() { return ID; }

    @Override
    public String toString() {
        return "GroupDisbandPayload{group=" + groupId + "}";
    }
}
