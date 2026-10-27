package com.niuqu.chatbubble.paper;

import java.util.List;

/**
 * 本插件注册的**全部**自定义载荷通道名 —— 协议常量的唯一出处。
 *
 * <p>名字必须与 Fabric 客户端 {@code CustomPayload.Id<>(Identifier.of("e33chat", "..."))} 里的
 * path 完全一致，否则 Paper 的 {@code registerIncoming/OutgoingPluginChannel} 与客户端的
 * {@code canSend} 判定对不上：客户端不会发、也不会收（而且**不会报错**，表现为「功能静默不生效」）。
 *
 * <p>方向约定（与 Fabric 端一致）：
 * <ul>
 *   <li>{@link #INCOMING} = C2S，服务端「能收」→ {@code registerIncomingPluginChannel}；
 *       注册后 Paper 会在玩家登录时通过 {@code minecraft:register} 把通道名宣告给客户端，
 *       Fabric 客户端的 {@code ClientPlayNetworking.canSend(id)} 因此为 true。</li>
 *   <li>{@link #OUTGOING} = S2C，服务端「能发」→ {@code registerOutgoingPluginChannel}；
 *       不注册的话 {@code Player#sendPluginMessage} 会直接抛 {@link IllegalArgumentException}。</li>
 * </ul>
 *
 * <p>注意 Paper 的通道数量上限：本插件 10 进 6 出，加上客户端自带模组的通道声明，
 * 大型整合包可能触到 Paper 的限制（{@code Invalid custom payload payload!} 踢人），
 * 需要服务端加 {@code -Dpaper.disableChannelLimit=true}（见 README）。
 */
final class E33Channels {

    private E33Channels() {
    }

    // ---------------------------------------------------------------- 3.0.x 既有

    /** C2S：引用一条消息的发送者/正文与两个指纹。 */
    static final String QUOTE_SYNC = "e33chat:quote_sync";

    /** S2C：引用块 / @提及列表 / 指纹。 */
    static final String CHAT_META = "e33chat:chat_meta";

    // ---------------------------------------------------------------- 群聊 C2S（9 条）

    static final String GROUP_CREATE = "e33chat:group_create";
    static final String GROUP_MESSAGE = "e33chat:group_message";
    static final String GROUP_INVITE = "e33chat:group_invite";
    static final String GROUP_INVITE_RESPOND = "e33chat:group_invite_respond";
    static final String GROUP_KICK = "e33chat:group_kick";
    static final String GROUP_LEAVE = "e33chat:group_leave";
    static final String GROUP_RENAME = "e33chat:group_rename";
    static final String GROUP_DISBAND = "e33chat:group_disband";

    // ---------------------------------------------------------------- 群聊 S2C（4 条）

    static final String GROUP_SYNC = "e33chat:group_sync";
    static final String GROUP_MESSAGE_RELAY = "e33chat:group_message_relay";
    static final String GROUP_EVENT = "e33chat:group_event";
    /**
     * 进群邀请的三个下行事件（INVITE / ACCEPTED / DECLINED）。
     *
     * <p>需求文档里 S2C 一栏只列了 4 条，但 Fabric 端的邀请流程是**pending（待回应）**语义：
     * 建群/加人只把对方记进 pending 并给**被邀请者**发这条 {@code group_invite_event}(kind=INVITE)，
     * 对方点「接受」后才有 {@code group_invite_respond} 上行。少了这条通道，被邀请者永远收不到
     * 按钮 → 群永远只有创建者一个人。所以它必须实现（字段顺序见 {@link GroupOutbound#inviteEvent}）。
     */
    static final String GROUP_INVITE_EVENT = "e33chat:group_invite_event";

    // ---------------------------------------------------------------- 玩家动作（1 进 1 出）

    static final String PLAYER_ACTION = "e33chat:player_action";
    static final String PLAYER_ACTION_RELAY = "e33chat:player_action_relay";

    /** 需要 {@code registerIncomingPluginChannel} 的全部通道（C2S）。 */
    static final List<String> INCOMING = List.of(
        QUOTE_SYNC,
        GROUP_CREATE, GROUP_MESSAGE, GROUP_INVITE, GROUP_INVITE_RESPOND,
        GROUP_KICK, GROUP_LEAVE, GROUP_RENAME, GROUP_DISBAND,
        PLAYER_ACTION);

    /** 需要 {@code registerOutgoingPluginChannel} 的全部通道（S2C）。 */
    static final List<String> OUTGOING = List.of(
        CHAT_META,
        GROUP_SYNC, GROUP_MESSAGE_RELAY, GROUP_EVENT, GROUP_INVITE_EVENT,
        PLAYER_ACTION_RELAY);

    /** 是否是本插件关心的上行通道（其余通道一律忽略）。 */
    static boolean isIncoming(String channel) {
        return channel != null && INCOMING.contains(channel);
    }
}
