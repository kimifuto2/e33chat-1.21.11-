package com.niuqu.chatbubble.chat.group;

import com.niuqu.chatbubble.store.ChatMessageStore;

import java.util.IdentityHashMap;
import java.util.UUID;

/**
 * 「进群邀请」消息的客户端状态（第 4 轮）：把一条**私聊里的邀请消息**和它的
 * 群 / 邀请者 / 是否已回应绑在一起，供聊天面板绘制「接受 / 拒绝」按钮与命中测试使用。
 *
 * <p>为什么用消息对象身份（{@link IdentityHashMap}）而不是给 {@code ChatMessage} 加字段：
 * <ul>
 *   <li>{@code ChatMessage} 是 14 个组件的 record，且被历史落盘、引用匹配、去重等多处按
 *       字段消费；为一次性 UI 状态扩字段要同步 toLine/fromJsonLine/测试与所有 with* 方法，
 *       风险远大于收益。</li>
 *   <li>邀请消息只在本连接内有意义（服务端 pendingInvites 也是内存态）：重启后旧消息即使
 *       还在历史里也不该再给按钮，映射为空正好自动失效。</li>
 *   <li>渲染时按对象身份查表是 O(1)，不会因为消息列表上万条而变慢。</li>
 * </ul>
 *
 * <p>容量护栏：邀请是低频事件，映射超过 {@link #MAX_ENTRIES} 直接清空 —— 宁可丢掉超老的
 * 按钮（历史里本来就不可再响应），也不让长会话把内存攒住。
 */
public final class GroupInvites {
    private GroupInvites() {}

    /** 待回应/已回应的邀请。可变量只在客户端线程读写（渲染 + 点击同一线程）。 */
    public static final class Invite {
        private final UUID groupId;
        private final String groupName;
        private final String inviterName;
        private boolean handled;
        private boolean accepted;

        private Invite(UUID groupId, String groupName, String inviterName) {
            this.groupId = groupId;
            this.groupName = groupName == null ? "" : groupName;
            this.inviterName = inviterName == null ? "" : inviterName;
        }

        public UUID groupId() { return groupId; }
        public String groupName() { return groupName; }
        public String inviterName() { return inviterName; }
        /** 是否已经点过接受/拒绝（点过的按钮只做灰显，不再响应）。 */
        public boolean handled() { return handled; }
        /** 回应的结果（仅在 {@link #handled()} 为 true 时有意义）。 */
        public boolean accepted() { return accepted; }
    }

    private static final int MAX_ENTRIES = 128;

    private static final IdentityHashMap<ChatMessageStore.ChatMessage, Invite> byMessage =
        new IdentityHashMap<>();

    /**
     * 把邀请状态挂到一条刚落库的私聊消息上（消息为 null 时直接忽略：调用方
     * {@code addWhisperNotice} 可能因为参数非法返回 null）。
     */
    public static synchronized Invite attach(ChatMessageStore.ChatMessage message, UUID groupId,
                                             String groupName, String inviterName) {
        if (message == null || groupId == null) return null;
        if (byMessage.size() > MAX_ENTRIES) byMessage.clear();
        Invite invite = new Invite(groupId, groupName, inviterName);
        byMessage.put(message, invite);
        return invite;
    }

    /** 该消息是不是一条待回应的邀请（不是 → null）。 */
    public static synchronized Invite of(ChatMessageStore.ChatMessage message) {
        return message == null ? null : byMessage.get(message);
    }

    /** 标记已回应（按钮转为灰显；同一邀请不会二次发送）。 */
    public static synchronized void resolve(Invite invite, boolean accepted) {
        if (invite == null) return;
        invite.handled = true;
        invite.accepted = accepted;
    }

    /** 连接边界清空（邀请都是本连接内的临时交互）。 */
    public static synchronized void clear() {
        byMessage.clear();
    }
}
