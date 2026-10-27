package com.niuqu.chatbubble.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C：**进群邀请**的三个下行事件（第 4 轮邀请流程）。
 *
 * <p>为什么不是「邀请 + 结果」两条 payload：邀请与结果描述的是同一件事（某个群的一次邀请），
 * 字段完全相同，只有语义不同，所以用一条 payload + {@code kind} 字符串区分（与
 * {@link GroupEventPayload} 同一套路，未来加 kind 也不会因为枚举序数不一致而串味）。
 *
 * <p>kind 语义：
 * <ul>
 *   <li>{@link #KIND_INVITE} —— 发给**被邀请者**：某人在私聊里邀请你进群。
 *       客户端在「邀请者 ↔ 我」的私聊会话里落一条带「接受/拒绝」按钮的消息。</li>
 *   <li>{@link #KIND_ACCEPTED} —— 发给**创建者**：{@code actorName} 接受了邀请并已进群。</li>
 *   <li>{@link #KIND_DECLINED} —— 发给**创建者**：{@code actorName} 拒绝了邀请，群不变。</li>
 * </ul>
 *
 * <p>线格式（固定顺序，只在末尾追加）：
 * <pre>
 *   String groupId      // 群 UUID 字符串
 *   String groupName    // 群名（空串 = 未命名，客户端渲染成「【群聊】」）
 *   String inviterName  // 邀请者（创建者）名字；接受/拒绝回执里也带上，便于客户端定位会话
 *   String kind         // INVITE | ACCEPTED | DECLINED
 *   String actorName    // 被邀请者名字（ACCEPTED/DECLINED 时是回应者；INVITE 时可为空）
 * </pre>
 *
 * <p>读取侧整体 try/catch：截断/版本不一致只让这一条失效，绝不让异常冒到网络层。
 */
public record GroupInviteEventPayload(String groupId, String groupName, String inviterName,
                                      String kind, String actorName) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GroupInviteEventPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "group_invite_event"));

    /** 有人邀请你进群（发给被邀请者）。 */
    public static final String KIND_INVITE = "INVITE";
    /** 被邀请者接受了邀请（发给创建者）。 */
    public static final String KIND_ACCEPTED = "ACCEPTED";
    /** 被邀请者拒绝了邀请（发给创建者）。 */
    public static final String KIND_DECLINED = "DECLINED";

    public static final StreamCodec<FriendlyByteBuf, GroupInviteEventPayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> {
            GroupWire.writeString(buf, value.groupId);
            GroupWire.writeString(buf, GroupWire.clamp(value.groupName, GroupWire.MAX_NAME));
            GroupWire.writeString(buf, GroupWire.clamp(value.inviterName, GroupWire.MAX_NAME));
            GroupWire.writeString(buf, GroupWire.clamp(value.kind, 16));
            GroupWire.writeString(buf, GroupWire.clamp(value.actorName, GroupWire.MAX_NAME));
        },
        buf -> {
            try {
                String groupId = GroupWire.readString(buf);
                String groupName = GroupWire.readString(buf);
                String inviterName = GroupWire.readString(buf);
                String kind = GroupWire.readString(buf);
                String actorName = GroupWire.readString(buf);
                return new GroupInviteEventPayload(groupId, groupName, inviterName, kind, actorName);
            } catch (Throwable t) {
                return new GroupInviteEventPayload("", "", "", "", "");
            }
        }
    );

    public boolean isValid() {
        return GroupWire.parseUuid(groupId) != null && kind != null && !kind.isBlank();
    }

    @Override
    public Type<GroupInviteEventPayload> type() { return ID; }

    @Override
    public String toString() {
        return "GroupInviteEventPayload{group=" + groupId + ", name='" + groupName
            + "', inviter='" + inviterName + "', kind=" + kind + ", actor='" + actorName + "'}";
    }
}
