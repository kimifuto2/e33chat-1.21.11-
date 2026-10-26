package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * C2S：群主在「群聊设置」里添加玩家。服务端校验调用者是群成员（MVP：任何成员都可加人，
 * 与微信群一致），把新成员并进群后广播新的 {@link GroupSyncPayload}，并对**新成员**额外
 * 发一条 {@link GroupEventPayload}（kind=INVITED，text=群名）用于弹出「你已被加入群聊 xxx」。
 *
 * <p>线格式（固定顺序）：
 * <pre>
 *   String groupId
 *   VarInt n + n × UUID
 * </pre>
 */
public record GroupInvitePayload(String groupId, List<UUID> members) implements CustomPayload {

    public static final CustomPayload.Id<GroupInvitePayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "group_invite"));

    public static final PacketCodec<PacketByteBuf, GroupInvitePayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            GroupWire.writeString(buf, value.groupId);
            GroupWire.writeUuidList(buf, value.members);
        },
        buf -> {
            try {
                String groupId = GroupWire.readString(buf);
                List<UUID> members = GroupWire.readUuidList(buf);
                return new GroupInvitePayload(groupId, members);
            } catch (Throwable t) {
                return new GroupInvitePayload("", List.of());
            }
        }
    );

    public boolean isValid() {
        return GroupWire.parseUuid(groupId) != null && members != null && !members.isEmpty();
    }

    @Override
    public Id<GroupInvitePayload> getId() { return ID; }

    @Override
    public String toString() {
        return "GroupInvitePayload{group=" + groupId + ", members=" + (members == null ? 0 : members.size()) + "}";
    }
}
