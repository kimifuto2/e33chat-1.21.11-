package com.niuqu.chatbubble.paper;

import java.util.UUID;

/**
 * 群聊 / 动作协议共用的常量与小工具 —— 与 Fabric 端 {@code GroupWire} 逐项对齐。
 *
 * <p>三条硬约束（照抄 Fabric 端的注释，因为它们在 Paper 侧同样成立）：
 * <ul>
 *   <li><b>字段顺序写死</b>，只在末尾追加；旧端读不到就当空值。</li>
 *   <li>所有长度都有上限：客户端送来的名字/内容在服务端**再夹一次**，
 *       否则一条群消息可以被用来放大广播（Paper 的插件消息没有原版聊天那种 256 字符限制）。</li>
 *   <li>解析/异常一律吞掉并降级成「无效载荷」，绝不让异常冒到网络层（Paper 会直接断开玩家）。</li>
 * </ul>
 */
final class GroupWire {

    private GroupWire() {
    }

    /** 单条载荷里列表元素的硬上限（群成员 / UUID 名单）—— 与 Fabric {@code GroupWire.MAX_LIST} 同值。 */
    static final int MAX_LIST = 64;
    /** 群名最大字符数。 */
    static final int MAX_NAME = 32;
    /** 单条群消息最大字符数（原版聊天上限 256 的一倍余量）。 */
    static final int MAX_CONTENT = 512;
    /** msgHash / kind 这类短串的护栏（Fabric 侧为 64 / 16）。 */
    static final int MAX_HASH = 64;
    static final int MAX_KIND = 16;

    static String str(String s) {
        return s == null ? "" : s;
    }

    /** 按**字符数**夹断（与 Fabric {@code String.substring} 语义一致，不是按字节）。 */
    static String clamp(String s, int max) {
        String v = str(s);
        if (max <= 0) {
            return "";
        }
        return v.length() <= max ? v : v.substring(0, max);
    }

    /** 解析 UUID 字符串；空/非法/全零（nil）都返回 {@code null}。 */
    static UUID parseUuid(String s) {
        String v = str(s).trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            UUID id = UUID.fromString(v);
            return id.equals(new UUID(0, 0)) ? null : id;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean isNil(UUID id) {
        return id == null || id.equals(new UUID(0, 0));
    }

    /**
     * 归一化群消息的去重指纹：契约是「明文 {@link String#hashCode()} 的十进制串」。
     *
     * <p>与 Fabric {@code GroupRegistry.relayMessage} 完全同义：客户端送来的不是十进制串
     * （旧端/别家实现直接发明文）就自己哈希一遍，保证两端算出同一个值、客户端能对上本地回显。
     */
    static String normalizeHash(String msgHash, String content) {
        String hash = clamp(msgHash, MAX_HASH);
        if (hash.isEmpty()) {
            return String.valueOf(str(content).hashCode());
        }
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            if (i == 0 && c == '-') {
                continue;
            }
            if (c < '0' || c > '9') {
                return String.valueOf(str(content).hashCode());
            }
        }
        return hash;
    }
}
