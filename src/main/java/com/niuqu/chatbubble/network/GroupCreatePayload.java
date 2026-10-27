package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * C2S：客户端「添加新群聊」完成时发一条，服务端负责建群 + 把 {@link GroupSyncPayload}
 * 广播给全体成员（含创建者自己，路径统一）。
 *
 * <p>线格式（固定顺序，只在末尾追加）：
 * <pre>
 *   String groupName              // 可空串，服务端落成默认「【群聊】」
 *   VarInt n                      // 成员数量（服务端再夹到 MAX_LIST 以内）
 *   n × UUID memberUuid           // 被勾选的成员（**不含**发起者；服务端自动把发起者加进去）
 * </pre>
 */
public record GroupCreatePayload(String groupName, List<UUID> members) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GroupCreatePayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "group_create"));

    public static final StreamCodec<FriendlyByteBuf, GroupCreatePayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> {
            GroupWire.writeString(buf, GroupWire.clamp(value.groupName, GroupWire.MAX_NAME));
            GroupWire.writeUuidList(buf, value.members);
        },
        buf -> {
            try {
                String name = GroupWire.readString(buf);
                List<UUID> members = GroupWire.readUuidList(buf);
                return new GroupCreatePayload(name, members);
            } catch (Throwable t) {
                return new GroupCreatePayload("", List.of());
            }
        }
    );

    @Override
    public Type<GroupCreatePayload> type() { return ID; }

    @Override
    public String toString() {
        return "GroupCreatePayload{name='" + groupName + "', members=" + (members == null ? 0 : members.size()) + "}";
    }
}
