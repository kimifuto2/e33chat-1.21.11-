package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S：主动退出群聊。服务端把自己从成员表移除；群空了就解散。其余成员收到新的
 * {@link GroupSyncPayload}，自己收到 {@link GroupEventPayload}(kind=LEFT, text=群名)。
 *
 * <p>线格式：{@code String groupId}。
 */
public record GroupLeavePayload(String groupId) implements CustomPayload {

    public static final CustomPayload.Id<GroupLeavePayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "group_leave"));

    public static final PacketCodec<PacketByteBuf, GroupLeavePayload> CODEC = PacketCodec.of(
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
    public Id<GroupLeavePayload> getId() { return ID; }

    @Override
    public String toString() {
        return "GroupLeavePayload{group=" + groupId + "}";
    }
}
