package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * C2S：把某个玩家踢出群聊。服务端校验调用者是群成员（MVP：任何成员都可踢，与微信群一致；
 * 创建者不可被踢），成功后广播新的 {@link GroupSyncPayload}，对被踢者发
 * {@link GroupEventPayload}(kind=KICKED, text=群名)。
 *
 * <p>线格式：{@code String groupId; UUID target}（target 用 writeUuid）。
 */
public record GroupKickPayload(String groupId, UUID target) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GroupKickPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "group_kick"));

    public static final StreamCodec<FriendlyByteBuf, GroupKickPayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> {
            GroupWire.writeString(buf, value.groupId);
            buf.writeUUID(value.target == null ? new UUID(0, 0) : value.target);
        },
        buf -> {
            try {
                String groupId = GroupWire.readString(buf);
                UUID target = buf.isReadable() ? buf.readUUID() : null;
                return new GroupKickPayload(groupId, target);
            } catch (Throwable t) {
                return new GroupKickPayload("", null);
            }
        }
    );

    public boolean isValid() {
        return GroupWire.parseUuid(groupId) != null && target != null;
    }

    @Override
    public Type<GroupKickPayload> type() { return ID; }

    @Override
    public String toString() {
        return "GroupKickPayload{group=" + groupId + ", target=" + target + "}";
    }
}
