package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S：玩家互动动作（拍了拍等）。服务端校验后按 {@code scope} 广播，并回
 * {@link PlayerActionRelayPayload} 给范围内的其它玩家（**不回给发送者**：发送者本地
 * 立即回显同一句话，不需要二次去重）。
 *
 * <p>线格式（固定顺序，只在末尾追加新字段）：
 * <pre>
 *   String targetUUID   // 被操作玩家的 UUID 字符串（右键的那个头像）
 *   String targetName   // 客户端拿到的玩家名（目标离线时服务端用它兜底显示）
 *   String verbId       // pat/poke/.../custom（服务端按白名单解析权威词条）
 *   String customText   // verbId=custom 时的自定义词条（一次性，服务端夹长度+清洗）
 *   String content      // 玩家输入的正文（系统样式行里的「说：」之后）
 *   String scope        // public/group/private
 *   String groupId      // scope=group 时的群 UUID 字符串，其它范围传空串
 * </pre>
 *
 * <p>与其它 e33chat 载荷一致：字段顺序写死、读取全部有上限与 try/catch，坏包只让这一条
 * 失效，绝不让异常冒到网络层（服务端会直接断开玩家）。
 */
public record PlayerActionPayload(String targetUUID, String targetName, String verbId,
                                  String customText, String content, String scope,
                                  String groupId) implements CustomPayload {

    public static final CustomPayload.Id<PlayerActionPayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "player_action"));

    public static final PacketCodec<PacketByteBuf, PlayerActionPayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            GroupWire.writeString(buf, value.targetUUID);
            GroupWire.writeString(buf, value.targetName);
            GroupWire.writeString(buf, value.verbId);
            GroupWire.writeString(buf, value.customText);
            GroupWire.writeString(buf, value.content);
            GroupWire.writeString(buf, value.scope);
            GroupWire.writeString(buf, value.groupId);
        },
        buf -> {
            try {
                String targetUUID = GroupWire.readString(buf);
                String targetName = GroupWire.readString(buf);
                String verbId = GroupWire.readString(buf);
                String customText = GroupWire.readString(buf);
                String content = GroupWire.readString(buf);
                String scope = GroupWire.readString(buf);
                String groupId = GroupWire.readString(buf);
                return new PlayerActionPayload(targetUUID, targetName, verbId, customText,
                    content, scope, groupId);
            } catch (Throwable t) {
                return new PlayerActionPayload("", "", "", "", "", "", "");
            }
        }
    );

    /** 最低有效性：有词条 id、正文非空、并且能定位目标（UUID 或玩家名至少有一个）。
     *  完整校验在服务端（白名单/成员/范围）。 */
    public boolean isValid() {
        if (content == null || content.isBlank()) return false;
        if (verbId == null || verbId.isBlank()) return false;
        boolean hasUuid = GroupWire.parseUuid(targetUUID) != null;
        return hasUuid || (targetName != null && !targetName.isBlank());
    }

    @Override
    public Id<PlayerActionPayload> getId() {
        return ID;
    }

    @Override
    public String toString() {
        return "PlayerActionPayload{target=" + targetName + ", verb=" + verbId
            + ", scope=" + scope + ", group=" + groupId
            + ", len=" + (content == null ? 0 : content.length()) + "}";
    }
}
