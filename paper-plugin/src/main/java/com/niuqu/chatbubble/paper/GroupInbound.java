package com.niuqu.chatbubble.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 群聊 **C2S**（客户端 → 服务端）8 条载荷的解码器 —— 字段顺序逐条对应 Fabric 的
 * {@code PacketCodec} 编码器（{@code com.niuqu.chatbubble.network.Group*Payload}）。
 *
 * <pre>
 *   channel                       字段（顺序固定，只允许末尾追加）
 *   e33chat:group_create          String name; VarInt n + n × UUID memberUuid
 *   e33chat:group_message         String groupId; String content; String msgHash
 *   e33chat:group_invite          String groupId; VarInt n + n × UUID memberUuid
 *   e33chat:group_invite_respond  String groupId; boolean accept
 *   e33chat:group_kick            String groupId; UUID target
 *   e33chat:group_leave           String groupId
 *   e33chat:group_rename          String groupId; String name
 *   e33chat:group_disband         String groupId
 * </pre>
 *
 * <p>几个容易搞错、但有 Fabric 源码为据的点：
 * <ul>
 *   <li>{@code group_message} 是 **3** 个字符串（还有 {@code msgHash}），不是 2 个 ——
 *       少读一个会让后续字段错位。</li>
 *   <li>UUID 不是字符串：{@code PacketByteBuf.writeUuid} = 两个大端 long（16 字节）。</li>
 *   <li>UUID 名单是 {@code VarInt(元素数) + 逐个 UUID}；服务端读的时候把 nil UUID 过滤掉
 *       （与 Fabric {@code GroupWire.readUuidList} 一致）。</li>
 *   <li>{@code boolean} 是单字节 0/1。</li>
 * </ul>
 *
 * <p>每条 {@code decode} 都整体 try/catch：坏包/截断包只让这一条变成「无效载荷」
 * （{@code isValid() == false}），**绝不抛异常到网络层**。
 */
final class GroupInbound {

    private GroupInbound() {
    }

    // ---------------------------------------------------------------- 记录类型

    /** {@code group_create}：建群。成员表是「被邀请者」，创建者由服务端自动加入。 */
    record Create(String groupName, List<UUID> members) {
        String name() {
            return GroupWire.str(groupName);
        }
    }

    /** {@code group_message}：往群里发一条消息。 */
    record Message(String groupId, String content, String msgHash) {
        boolean isValid() {
            return GroupWire.parseUuid(groupId) != null
                && content != null && !content.isBlank();
        }
    }

    /** {@code group_invite}：邀请玩家（服务端只登记 pending 并发邀请，不直接拉人）。 */
    record Invite(String groupId, List<UUID> members) {
        boolean isValid() {
            return GroupWire.parseUuid(groupId) != null && members != null && !members.isEmpty();
        }
    }

    /** {@code group_invite_respond}：被邀请者接受/拒绝。 */
    record InviteRespond(String groupId, boolean accept) {
        boolean isValid() {
            return GroupWire.parseUuid(groupId) != null;
        }
    }

    /** {@code group_kick}：踢人。 */
    record Kick(String groupId, UUID target) {
        boolean isValid() {
            return GroupWire.parseUuid(groupId) != null && !GroupWire.isNil(target);
        }
    }

    /** {@code group_leave}：主动退群。 */
    record Leave(String groupId) {
        boolean isValid() {
            return GroupWire.parseUuid(groupId) != null;
        }
    }

    /** {@code group_rename}：改群名（空串 = 恢复默认「【群聊】」）。 */
    record Rename(String groupId, String name) {
        boolean isValid() {
            return GroupWire.parseUuid(groupId) != null;
        }
    }

    /** {@code group_disband}：解散群（仅创建者）。 */
    record Disband(String groupId) {
        boolean isValid() {
            return GroupWire.parseUuid(groupId) != null;
        }
    }

    // ---------------------------------------------------------------- 解码

    static Create decodeCreate(byte[] data) {
        try {
            McCodec.Reader in = new McCodec.Reader(data);
            String name = in.isReadable() ? in.readString() : "";
            return new Create(name, readUuidList(in));
        } catch (Throwable t) {
            return new Create("", List.of());
        }
    }

    static Message decodeMessage(byte[] data) {
        try {
            McCodec.Reader in = new McCodec.Reader(data);
            return new Message(readString(in), readString(in), readString(in));
        } catch (Throwable t) {
            return new Message("", "", "");
        }
    }

    static Invite decodeInvite(byte[] data) {
        try {
            McCodec.Reader in = new McCodec.Reader(data);
            return new Invite(readString(in), readUuidList(in));
        } catch (Throwable t) {
            return new Invite("", List.of());
        }
    }

    static InviteRespond decodeInviteRespond(byte[] data) {
        try {
            McCodec.Reader in = new McCodec.Reader(data);
            String groupId = readString(in);
            boolean accept = in.isReadable() && in.readBoolean();
            return new InviteRespond(groupId, accept);
        } catch (Throwable t) {
            return new InviteRespond("", false);
        }
    }

    static Kick decodeKick(byte[] data) {
        try {
            McCodec.Reader in = new McCodec.Reader(data);
            String groupId = readString(in);
            UUID target = in.isReadable() ? in.readUuid() : null;
            return new Kick(groupId, target);
        } catch (Throwable t) {
            return new Kick("", null);
        }
    }

    static Leave decodeLeave(byte[] data) {
        try {
            return new Leave(readString(new McCodec.Reader(data)));
        } catch (Throwable t) {
            return new Leave("");
        }
    }

    static Rename decodeRename(byte[] data) {
        try {
            McCodec.Reader in = new McCodec.Reader(data);
            return new Rename(readString(in), readString(in));
        } catch (Throwable t) {
            return new Rename("", "");
        }
    }

    static Disband decodeDisband(byte[] data) {
        try {
            return new Disband(readString(new McCodec.Reader(data)));
        } catch (Throwable t) {
            return new Disband("");
        }
    }

    // ---------------------------------------------------------------- 原语

    /** 读不到（旧端少字段）→ 空串；读到一半截断 → 抛（由外层 decode 吞成无效载荷）。 */
    private static String readString(McCodec.Reader in) {
        return in.isReadable() ? in.readString() : "";
    }

    /** {@code VarInt n + n × UUID}，nil UUID 丢弃，数量硬夹到 {@link GroupWire#MAX_LIST}。 */
    private static List<UUID> readUuidList(McCodec.Reader in) {
        List<UUID> out = new ArrayList<>();
        if (!in.isReadable()) {
            return out;
        }
        int n = Math.min(Math.max(0, in.readVarInt()), GroupWire.MAX_LIST);
        for (int i = 0; i < n; i++) {
            UUID id = in.readUuid();
            if (!GroupWire.isNil(id)) {
                out.add(id);
            }
        }
        return out;
    }
}
