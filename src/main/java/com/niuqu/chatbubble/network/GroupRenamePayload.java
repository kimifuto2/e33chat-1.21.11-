package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S：改群名（空串 = 恢复默认「【群聊】」）。服务端校验调用者是群成员，成功后把新的
 * {@link GroupSyncPayload} 广播给全体成员，并附 {@link GroupEventPayload}(kind=RENAMED)。
 *
 * <p>线格式：{@code String groupId; String name}。
 */
public record GroupRenamePayload(String groupId, String name) implements CustomPayload {

    public static final CustomPayload.Id<GroupRenamePayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "group_rename"));

    public static final PacketCodec<PacketByteBuf, GroupRenamePayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            GroupWire.writeString(buf, value.groupId);
            GroupWire.writeString(buf, GroupWire.clamp(value.name, GroupWire.MAX_NAME));
        },
        buf -> {
            try {
                String groupId = GroupWire.readString(buf);
                String name = GroupWire.readString(buf);
                return new GroupRenamePayload(groupId, name);
            } catch (Throwable t) {
                return new GroupRenamePayload("", "");
            }
        }
    );

    public boolean isValid() {
        return GroupWire.parseUuid(groupId) != null;
    }

    @Override
    public Id<GroupRenamePayload> getId() { return ID; }

    @Override
    public String toString() {
        return "GroupRenamePayload{group=" + groupId + ", name='" + name + "'}";
    }
}
