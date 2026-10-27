package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S：主动退出群聊。服务端把自己从成员表移除；群空了就解散。其余成员收到新的
 * {@link GroupSyncPayload}，自己收到 {@link GroupEventPayload}(kind=LEFT, text=群名)。
 *
 * <p>线格式：{@code String groupId}。
 */
public record GroupLeavePayload(String groupId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GroupLeavePayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "group_leave"));

    public static final StreamCodec<FriendlyByteBuf, GroupLeavePayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> GroupWire.writeString(buf, value.groupId),
        buf -> {
            try {
                return new GroupLeavePayload(GroupWire.readString(buf));
            } catch (Throwable t) {
                return new GroupLeavePayload("");
            }
        }
    );

    public boolean isValid() {
        return GroupWire.parseUuid(groupId) != null;
    }

    @Override
    public Type<GroupLeavePayload> type() { return ID; }

    @Override
    public String toString() {
        return "GroupLeavePayload{group=" + groupId + "}";
    }
}
