package com.niuqu.chatbubble.paper;

/**
 * 动作消息的**会话范围**（协议字段 {@code scope}）—— 决定服务端把动作广播给谁，
 * 也决定收端把它插进哪个会话视图。与 Fabric 端 {@code PlayerActionScope} 逐值一致。
 *
 * <ul>
 *   <li>{@link #PUBLIC} 公屏（世界频道）：全服在线玩家；</li>
 *   <li>{@link #GROUP} 群聊：该群的全部在线成员（需带 groupId，且发送者必须是群成员）；</li>
 *   <li>{@link #PRIVATE} 私聊：只有发送者与被操作玩家。</li>
 * </ul>
 *
 * <p>线格式用固定的英文小写串（{@link #wire()}），不用序号：以后增删枚举不改变已有值的含义。
 * 未知/空值按 {@link #PUBLIC} 兜底（与 Fabric {@code PlayerActionRelay} 的
 * {@code if (scope == null) scope = PUBLIC} 同义）。
 */
enum PlayerActionScope {
    PUBLIC("public"),
    GROUP("group"),
    PRIVATE("private");

    private final String wire;

    PlayerActionScope(String wire) {
        this.wire = wire;
    }

    /** 协议里传的字符串（永远非空、小写）。 */
    String wire() {
        return wire;
    }

    /** 解析协议值；未知/空 → {@code null}。 */
    static PlayerActionScope fromWire(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        if (v.isEmpty()) {
            return null;
        }
        for (PlayerActionScope scope : values()) {
            if (scope.wire.equalsIgnoreCase(v)) {
                return scope;
            }
        }
        return null;
    }
}
