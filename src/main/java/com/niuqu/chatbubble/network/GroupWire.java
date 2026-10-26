package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 群聊协议（group_*）共用的线格式小工具。
 *
 * <p>设计约束（与既有 payload 一致）：
 * <ul>
 *   <li><b>字段顺序写死</b>，只在**末尾**追加字段，旧端读不到就当空值 → 混版本不会踢人。</li>
 *   <li>所有读取都有上限（{@link #MAX_LIST}）与 try/catch：坏包/截断包只让这条载荷失效，
 *       绝不让异常冒到网络层（服务端会直接断开玩家）。</li>
 *   <li>长度上限同时防「广播放大器」：客户端送来的名字/内容在服务端会再夹一次。</li>
 * </ul>
 */
public final class GroupWire {
    private GroupWire() {}

    /** 单条载荷里列表元素的硬上限（群成员 / UUID 名单）。 */
    public static final int MAX_LIST = 64;
    /** 群名最大字符数（服务端会再夹一次）。 */
    public static final int MAX_NAME = 32;
    /** 单条群消息最大字符数（原版聊天上限是 256，这里留一倍余量）。 */
    public static final int MAX_CONTENT = 512;

    public static String str(String s) {
        return s == null ? "" : s;
    }

    /** 夹长度（按字符数），null → 空串。 */
    public static String clamp(String s, int max) {
        String v = str(s);
        return v.length() <= max ? v : v.substring(0, max);
    }

    public static void writeString(PacketByteBuf buf, String s) {
        buf.writeString(clamp(s, MAX_CONTENT));
    }

    /** 读字符串：读不到（旧端少字段）或损坏时返回空串。 */
    public static String readString(PacketByteBuf buf) {
        try {
            return buf.isReadable() ? buf.readString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** UUID 列表：VarInt 数量 + 逐个 writeUuid。 */
    public static void writeUuidList(PacketByteBuf buf, List<UUID> ids) {
        List<UUID> list = ids == null ? List.of() : ids;
        int n = Math.min(list.size(), MAX_LIST);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            UUID id = list.get(i);
            buf.writeUuid(id == null ? new UUID(0, 0) : id);
        }
    }

    public static List<UUID> readUuidList(PacketByteBuf buf) {
        List<UUID> out = new ArrayList<>();
        try {
            int n = Math.min(Math.max(0, buf.readVarInt()), MAX_LIST);
            for (int i = 0; i < n; i++) {
                UUID id = buf.readUuid();
                if (id != null && !id.equals(new UUID(0, 0))) out.add(id);
            }
        } catch (Throwable ignored) {
            // 截断/版本不一致：已读到的部分照常返回
        }
        return out;
    }

    /** 解析 UUID 字符串；非法返回 null（调用方按「无效载荷」处理）。 */
    public static UUID parseUuid(String s) {
        String v = str(s).trim();
        if (v.isEmpty()) return null;
        try {
            UUID id = UUID.fromString(v);
            return id.equals(new UUID(0, 0)) ? null : id;
        } catch (Throwable t) {
            return null;
        }
    }
}
