package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S：被邀请者在私聊里点「接受 / 拒绝」时发上来（第 4 轮邀请流程）。
 *
 * <p>服务端不信任这里的任何语义：只按 {@code groupId} 找回群、按当前登录玩家身份去
 * {@code pendingInvites} 里核对「这个玩家确实被邀请过」，核对不上就只记日志（不改群、
 * 不回执）—— 客户端伪造一条 accept 也进不来别人的群。
 *
 * <p>线格式（固定顺序，只在末尾追加）：
 * <pre>
 *   String  groupId
 *   boolean accept    // true = 接受进群，false = 拒绝
 * </pre>
 */
public record GroupInviteRespondPayload(String groupId, boolean accept) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GroupInviteRespondPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "group_invite_respond"));

    public static final StreamCodec<FriendlyByteBuf, GroupInviteRespondPayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> {
            GroupWire.writeString(buf, value.groupId);
            buf.writeBoolean(value.accept);
        },
        buf -> {
            try {
                String groupId = GroupWire.readString(buf);
                boolean accept = buf.isReadable() && buf.readBoolean();
                return new GroupInviteRespondPayload(groupId, accept);
            } catch (Throwable t) {
                return new GroupInviteRespondPayload("", false);
            }
        }
    );

    public boolean isValid() {
        return GroupWire.parseUuid(groupId) != null;
    }

    @Override
    public Type<GroupInviteRespondPayload> type() { return ID; }

    @Override
    public String toString() {
        return "GroupInviteRespondPayload{group=" + groupId + ", accept=" + accept + "}";
    }
}
