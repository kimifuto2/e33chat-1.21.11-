package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S：改群名（空串 = 恢复默认「【群聊】」）。服务端校验调用者是群成员，成功后把新的
 * {@link GroupSyncPayload} 广播给全体成员，并附 {@link GroupEventPayload}(kind=RENAMED)。
 *
 * <p>线格式：{@code String groupId; String name}。
 */
public record GroupRenamePayload(String groupId, String name) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GroupRenamePayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "group_rename"));

    public static final StreamCodec<FriendlyByteBuf, GroupRenamePayload> CODEC = StreamCodec.ofMember(
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
    public Type<GroupRenamePayload> type() { return ID; }

    @Override
    public String toString() {
        return "GroupRenamePayload{group=" + groupId + ", name='" + name + "'}";
    }
}
