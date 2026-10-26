package com.niuqu.chatbubble.chat.action;

/**
 * 动作消息的会话范围（协议字段 {@code scope}）——决定服务端把这条动作消息广播给谁，
 * 也决定收端把它插进哪个会话视图：
 *
 * <ul>
 *   <li>{@link #PUBLIC} 公屏（世界频道）：全服在线玩家；</li>
 *   <li>{@link #GROUP} 群聊：该群的全部在线成员（需带 groupId）；</li>
 *   <li>{@link #PRIVATE} 私聊：只有发送者与被操作玩家。</li>
 * </ul>
 *
 * <p>范围在**发送时**按当前会话推导（公屏 / 群聊 / 私聊三种视图），三个会话互相独立：
 * 在私聊里做动作不会出现在公屏或群聊里。
 *
 * <p>线格式用固定的英文小写串（{@link #wire()}），不用序号：以后增删枚举不改变
 * 已有值的含义，旧客户端/服务端只会把不认识的值当成无效载荷丢弃。
 */
public enum PlayerActionScope {
    PUBLIC("public"),
    GROUP("group"),
    PRIVATE("private");

    private final String wire;

    PlayerActionScope(String wire) {
        this.wire = wire;
    }

    /** 协议里传的字符串（永远非空、小写）。 */
    public String wire() {
        return wire;
    }

    /** 解析协议值；未知/空 → null（调用方按无效载荷处理，不抛异常）。 */
    public static PlayerActionScope fromWire(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        for (PlayerActionScope scope : values()) {
            if (scope.wire.equalsIgnoreCase(v)) return scope;
        }
        return null;
    }
}
