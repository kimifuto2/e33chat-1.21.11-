package com.niuqu.chatbubble.paper;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

/**
 * 群聊 **S2C**（服务端 → 客户端）4 条载荷的编码器 —— 字段顺序逐条对应 Fabric 的
 * {@code PacketCodec} 编码器（{@code com.niuqu.chatbubble.network.Group*Payload}）。
 *
 * <pre>
 *   channel                         字段（顺序固定，只允许末尾追加）
 *   e33chat:group_sync              String groupId; String name; String creator;
 *                                   VarInt n + n × (UUID uuid; String name)
 *   e33chat:group_message_relay     String groupId; UUID senderUUID; String senderName;
 *                                   String content; String msgHash
 *   e33chat:group_event             String groupId; String kind; String text
 *   e33chat:group_invite_event      String groupId; String groupName; String inviterName;
 *                                   String kind; String actorName
 * </pre>
 *
 * <p>夹断规则与 Fabric 端**同一个位置、同一个上限**（不对齐的话客户端会读到不同长度的字段，
 * 整条载荷错位）：group_sync 的 name/成员名 → {@link GroupWire#MAX_NAME}，
 * group_event 的 kind → 16、text → {@link GroupWire#MAX_NAME}，
 * group_message_relay 的 content → {@link GroupWire#MAX_CONTENT}、msgHash → 64。
 * 这些上限同时也是防「广播放大器」的手段：群名/内容都来自客户端输入。
 */
final class GroupOutbound {

    private GroupOutbound() {
    }

    /** {@code group_sync} 里的一个成员：UUID + 显示名（离线成员用最后已知的名字）。 */
    record Member(UUID uuid, String name) {
    }

    /** {@code group_sync}：一个群的完整快照（建群/变更广播、登录补发都用它）。 */
    static byte[] sync(String groupId, String name, String creator, List<Member> members) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        McCodec.writeString(out, GroupWire.str(groupId));
        McCodec.writeString(out, GroupWire.clamp(name, GroupWire.MAX_NAME));
        McCodec.writeString(out, GroupWire.str(creator));
        List<Member> list = members == null ? List.of() : members;
        int n = Math.min(list.size(), GroupWire.MAX_LIST);
        McCodec.writeVarInt(out, n);
        for (int i = 0; i < n; i++) {
            Member m = list.get(i);
            McCodec.writeUuid(out, m == null ? null : m.uuid());
            McCodec.writeString(out, GroupWire.clamp(m == null ? "" : m.name(), GroupWire.MAX_NAME));
        }
        return out.toByteArray();
    }

    /** {@code group_message_relay}：一条群消息（**含发送者自己**，客户端按 msgHash 去重）。 */
    static byte[] messageRelay(String groupId, UUID senderUUID, String senderName,
                               String content, String msgHash) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(96);
        McCodec.writeString(out, GroupWire.str(groupId));
        McCodec.writeUuid(out, senderUUID);
        McCodec.writeString(out, GroupWire.clamp(senderName, GroupWire.MAX_NAME));
        McCodec.writeString(out, GroupWire.clamp(content, GroupWire.MAX_CONTENT));
        McCodec.writeString(out, GroupWire.clamp(msgHash, GroupWire.MAX_HASH));
        return out.toByteArray();
    }

    /**
     * {@code group_event}：群事件通知。
     *
     * <p>kind 用字符串而不是枚举序数（{@code INVITED | KICKED | LEFT | DISBANDED | RENAMED |
     * INVITE_DENIED}）：以后加事件类型不会因为两端枚举不一致而串味，客户端不认识的一律忽略。
     */
    static byte[] event(String groupId, String kind, String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        McCodec.writeString(out, GroupWire.str(groupId));
        McCodec.writeString(out, GroupWire.clamp(kind, GroupWire.MAX_KIND));
        McCodec.writeString(out, GroupWire.clamp(text, GroupWire.MAX_NAME));
        return out.toByteArray();
    }

    /**
     * {@code group_invite_event}：进群邀请的三态下行（{@code INVITE | ACCEPTED | DECLINED}）。
     *
     * <ul>
     *   <li>{@code INVITE} → 被邀请者：某人在私聊里邀请你进群，客户端落一条带「接受/拒绝」按钮的消息；</li>
     *   <li>{@code ACCEPTED} / {@code DECLINED} → 创建者：被邀请者回应了，客户端在创建者 ↔ 被邀请者的
     *       私聊里落一条结果消息。</li>
     * </ul>
     */
    static byte[] inviteEvent(String groupId, String groupName, String inviterName,
                              String kind, String actorName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(96);
        McCodec.writeString(out, GroupWire.str(groupId));
        McCodec.writeString(out, GroupWire.clamp(groupName, GroupWire.MAX_NAME));
        McCodec.writeString(out, GroupWire.clamp(inviterName, GroupWire.MAX_NAME));
        McCodec.writeString(out, GroupWire.clamp(kind, GroupWire.MAX_KIND));
        McCodec.writeString(out, GroupWire.clamp(actorName, GroupWire.MAX_NAME));
        return out.toByteArray();
    }
}
