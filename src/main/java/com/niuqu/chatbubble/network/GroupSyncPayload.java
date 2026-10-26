package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * S2C：把「一个群的完整快照」推给某个客户端。三个时机都会发：
 * <ol>
 *   <li>建群 / 加人 / 踢人 / 改名 / 有人退出后的变更广播（发给全体在线成员）；</li>
 *   <li>玩家登录（JOIN）时补发他所属的全部群 —— 客户端不需要自己持久化成员表；</li>
 *   <li>玩家新建群成功后（与建群广播同一轮）。</li>
 * </ol>
 *
 * <p>线格式（固定顺序，只在末尾追加）：
 * <pre>
 *   String groupId
 *   String name                 // 空串 = 未命名，客户端显示「【群聊】」
 *   String creator              // 创建者 UUID 字符串（空串 = 未知）
 *   VarInt n
 *   n × (UUID uuid, String name)
 * </pre>
 *
 * <p>成员顺序 = 入群顺序（LinkedHashSet），客户端侧边栏九宫格取「最先的 9 个」即按这个顺序。
 */
public record GroupSyncPayload(String groupId, String name, String creator, List<Member> members)
        implements CustomPayload {

    /** 群成员：UUID + 显示名（成员离线时服务端用最后已知的名字）。 */
    public record Member(java.util.UUID uuid, String name) {
        @Override
        public String toString() {
            return uuid + "/" + name;
        }
    }

    public static final CustomPayload.Id<GroupSyncPayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "group_sync"));

    public static final PacketCodec<PacketByteBuf, GroupSyncPayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            GroupWire.writeString(buf, value.groupId);
            GroupWire.writeString(buf, GroupWire.clamp(value.name, GroupWire.MAX_NAME));
            GroupWire.writeString(buf, value.creator);
            java.util.List<Member> list = value.members == null ? java.util.List.of() : value.members;
            int n = Math.min(list.size(), GroupWire.MAX_LIST);
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                Member m = list.get(i);
                buf.writeUuid(m == null || m.uuid() == null ? new java.util.UUID(0, 0) : m.uuid());
                GroupWire.writeString(buf, m == null ? "" : GroupWire.clamp(m.name(), GroupWire.MAX_NAME));
            }
        },
        buf -> {
            try {
                String groupId = GroupWire.readString(buf);
                String name = GroupWire.readString(buf);
                String creator = GroupWire.readString(buf);
                java.util.List<Member> members = new java.util.ArrayList<>();
                int n = 0;
                try {
                    n = Math.min(Math.max(0, buf.readVarInt()), GroupWire.MAX_LIST);
                } catch (Throwable ignored) {
                }
                for (int i = 0; i < n; i++) {
                    java.util.UUID id = buf.readUuid();
                    String memberName = GroupWire.readString(buf);
                    if (id != null && !id.equals(new java.util.UUID(0, 0))) {
                        members.add(new Member(id, memberName));
                    }
                }
                return new GroupSyncPayload(groupId, name, creator, members);
            } catch (Throwable t) {
                return new GroupSyncPayload("", "", "", java.util.List.of());
            }
        }
    );

    public boolean isValid() {
        return GroupWire.parseUuid(groupId) != null;
    }

    /** 本地判定的群名（空 → 【群聊】，由 GroupStore 统一处理，这里只做 trim）。 */
    public String nameOrEmpty() {
        return name == null ? "" : name.trim();
    }

    @Override
    public Id<GroupSyncPayload> getId() { return ID; }

    @Override
    public String toString() {
        return "GroupSyncPayload{group=" + groupId + ", name='" + name + "', creator=" + creator
            + ", members=" + (members == null ? 0 : members.size()) + "}";
    }
}
