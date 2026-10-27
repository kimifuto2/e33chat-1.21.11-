package com.niuqu.chatbubble.paper;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

/**
 * 动作消息的两条载荷：解码 C2S {@code e33chat:player_action}、编码 S2C
 * {@code e33chat:player_action_relay}。字段顺序逐条对应 Fabric 端
 * {@code PlayerActionPayload} / {@code PlayerActionRelayPayload}。
 *
 * <pre>
 *   e33chat:player_action（C2S，7 个字符串，顺序固定）
 *     String targetUUID   // 被操作玩家的 UUID 字符串
 *     String targetName   // 客户端拿到的玩家名（目标离线时服务端用它兜底显示）
 *     String verbId       // pat/poke/.../custom
 *     String customText   // verbId=custom 时的自定义词条
 *     String content      // 玩家输入的正文
 *     String scope        // public/group/private
 *     String groupId      // scope=group 时的群 UUID 字符串，其它范围传空串
 *
 *   e33chat:player_action_relay（S2C，顺序固定）
 *     UUID   senderUUID   // 两个大端 long
 *     String senderName
 *     String targetUUID
 *     String targetName
 *     String verbText     // 服务端解析出的权威词条
 *     String content
 *     String scope
 *     String groupId
 * </pre>
 *
 * <p>注意 relay 的**第 1 个字段是 UUID 而不是字符串**（Fabric 用的是
 * {@code buf.writeUuid(...)}）——写成 UUID 字符串会让客户端从第 1 字节起全部错位。
 */
final class PlayerActionPayloads {

    private PlayerActionPayloads() {
    }

    /** C2S 解码结果。{@code isValid()} 只做最低限度校验，完整校验在 {@link PlayerActionRelay}。 */
    record Inbound(String targetUUID, String targetName, String verbId, String customText,
                   String content, String scope, String groupId) {

        /** 最低有效性：有词条 id、正文非空、并且能定位目标（UUID 或玩家名至少有一个）。 */
        boolean isValid() {
            if (content == null || content.isBlank()) {
                return false;
            }
            if (verbId == null || verbId.isBlank()) {
                return false;
            }
            boolean hasUuid = GroupWire.parseUuid(targetUUID) != null;
            return hasUuid || (targetName != null && !targetName.isBlank());
        }
    }

    /** 解码 {@code player_action}；坏包/截断包整体降级成空载荷（不抛异常、不踢人）。 */
    static Inbound decode(byte[] data) {
        try {
            McCodec.Reader in = new McCodec.Reader(data);
            return new Inbound(
                readString(in), readString(in), readString(in), readString(in),
                readString(in), readString(in), readString(in));
        } catch (Throwable t) {
            return new Inbound("", "", "", "", "", "", "");
        }
    }

    /** 编码 {@code player_action_relay}（发送者本地已回显，这里不回给发送者）。 */
    static byte[] encodeRelay(UUID senderUUID, String senderName, String targetUUID,
                              String targetName, String verbText, String content,
                              String scope, String groupId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        McCodec.writeUuid(out, senderUUID);
        McCodec.writeString(out, GroupWire.str(senderName));
        McCodec.writeString(out, GroupWire.str(targetUUID));
        McCodec.writeString(out, GroupWire.str(targetName));
        McCodec.writeString(out, GroupWire.str(verbText));
        McCodec.writeString(out, GroupWire.str(content));
        McCodec.writeString(out, GroupWire.str(scope));
        McCodec.writeString(out, GroupWire.str(groupId));
        return out.toByteArray();
    }

    private static String readString(McCodec.Reader in) {
        return in.isReadable() ? in.readString() : "";
    }
}
