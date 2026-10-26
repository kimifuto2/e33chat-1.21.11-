package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C：群聊事件通知（「你被拉进群」「你被踢了」「群解散了」「群改名了」「你退群了」）。
 *
 * <p>线格式（固定顺序）：
 * <pre>
 *   String groupId
 *   String kind     // INVITED | KICKED | LEFT | DISBANDED | RENAMED | INVITE_DENIED
 *   String text     // 群名（RENAMED 时是新群名）——弹横幅、写系统提示都用它
 * </pre>
 *
 * <p>kind 用字符串而不是枚举序数：以后加事件类型不会因为两端枚举不一致而串味。
 * 客户端**不认识的 kind 一律忽略**。
 */
public record GroupEventPayload(String groupId, String kind, String text) implements CustomPayload {

    public static final CustomPayload.Id<GroupEventPayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "group_event"));

    public static final String KIND_INVITED = "INVITED";
    public static final String KIND_KICKED = "KICKED";
    public static final String KIND_LEFT = "LEFT";
    public static final String KIND_DISBANDED = "DISBANDED";
    public static final String KIND_RENAMED = "RENAMED";
    /** 邀请被服务端拒绝（第 4 轮：只有创建者能邀请）。只发给操作者本人，群不变。 */
    public static final String KIND_INVITE_DENIED = "INVITE_DENIED";

    public static final PacketCodec<PacketByteBuf, GroupEventPayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            GroupWire.writeString(buf, value.groupId);
            GroupWire.writeString(buf, GroupWire.clamp(value.kind, 16));
            GroupWire.writeString(buf, GroupWire.clamp(value.text, GroupWire.MAX_NAME));
        },
        buf -> {
            try {
                String groupId = GroupWire.readString(buf);
                String kind = GroupWire.readString(buf);
                String text = GroupWire.readString(buf);
                return new GroupEventPayload(groupId, kind, text);
            } catch (Throwable t) {
                return new GroupEventPayload("", "", "");
            }
        }
    );

    public boolean isValid() {
        return GroupWire.parseUuid(groupId) != null && kind != null && !kind.isBlank();
    }

    @Override
    public Id<GroupEventPayload> getId() { return ID; }

    @Override
    public String toString() {
        return "GroupEventPayload{group=" + groupId + ", kind=" + kind + ", text='" + text + "'}";
    }
}
