package com.niuqu.chatbubble.channeldiet;

import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「通道节食」的统计快照：每次 PLAY 阶段构造 {@code minecraft:register} 时记下
 * **全部通道 Identifier → 按命名空间分组计数**，供游戏内 GUI 读取。
 *
 * <p>写入发生在网络/客户端线程，读取发生在渲染线程，所以：
 * <ul>
 *   <li>快照对象完全不可变（{@code List.copyOf} + record），发布用 {@code volatile}；</li>
 *   <li>不做「读-改-写」增量更新，每次整体替换引用，天然线程安全；</li>
 *   <li>时间戳用 {@code System.currentTimeMillis()}，GUI 只用来显示"上次连接"。</li>
 * </ul>
 *
 * <p>快照有 {@code measured} 标记：true = 真的发生过一次连接（数据来自注册包）；
 * false = GUI 在未连接时用「客户端全局接收器」估算出来的预览值。两者在界面上文案不同，
 * 避免把估算数字当成实测。
 */
public final class ChannelDietStats {
    private ChannelDietStats() {}

    /** 一个命名空间的通道条数。 */
    public record NamespaceCount(String namespace, int count) {}

    /**
     * 一次统计快照。
     *
     * @param timestampMs      记录时刻（毫秒）
     * @param measured         true = 来自真实连接的注册包；false = GUI 预览估算
     * @param mode             记录时的模式（{@code off}/{@code countOnly}/{@code active}）
     * @param brand            服务端 brand（可能为 null/空）
     * @param integratedServer 是否是集成服务器（单机 / 局域网主机）
     * @param total            通道总数 N
     * @param limit            记录时的上限
     * @param namespaces       按通道数从多到少排好的分组
     * @param disabledNamespaces 记录时生效的禁用名单
     */
    public record Snapshot(long timestampMs, boolean measured, String mode, String brand, boolean integratedServer,
                           int total, int limit, List<NamespaceCount> namespaces,
                           List<String> disabledNamespaces) {
        /** 该命名空间的通道数（不存在 → 0）。 */
        public int count(String namespace) {
            for (NamespaceCount nc : namespaces) {
                if (nc.namespace().equals(namespace)) return nc.count();
            }
            return 0;
        }

        /** 命名空间 → 通道数（有序，便于裁剪计划复用）。 */
        public Map<String, Integer> asMap() {
            Map<String, Integer> m = new LinkedHashMap<>();
            for (NamespaceCount nc : namespaces) m.put(nc.namespace(), nc.count());
            return m;
        }
    }

    /** 最近一次真实连接记录下来的快照；从未连接过 → null。 */
    private static volatile Snapshot last;

    /** 最近一次连接记录的快照（可能为 null）。 */
    public static Snapshot last() { return last; }

    /**
     * 记录一次 PLAY 阶段注册包的通道集合，并发布为「最近快照」。
     *
     * <p>任何模式都会调用（{@code off} 也记录），这样 GUI 在关闭节食时仍能显示真实数字。
     * 传入的集合可能是 Fabric 的 {@code HashSet} 副本，也可能是 {@code List.of()}，
     * 这里只读不持有引用。
     */
    public static Snapshot record(Collection<Identifier> channels, String mode, String brand,
                                  boolean integratedServer, int limit, Collection<String> disabled) {
        Snapshot s = build(channels, mode, brand, integratedServer, limit, disabled, true);
        last = s;
        return s;
    }

    /**
     * GUI 预览：还没连接过服务器时，用客户端全局接收器估算同一批通道
     * （Fabric 在配置阶段发的正是这个集合，PLAY 阶段是它的超集）。
     */
    public static Snapshot preview(Collection<Identifier> channels, int limit, Collection<String> disabled) {
        return build(channels, ChannelDietFilter.MODE_OFF, null, false, limit, disabled, false);
    }

    /** 完全取不到通道时给 GUI 一个空快照（不显示「未知」而是「没有任何声明」）。 */
    public static Snapshot empty(int limit, Collection<String> disabled) {
        return build(List.of(), ChannelDietFilter.MODE_OFF, null, false, limit, disabled, false);
    }

    private static Snapshot build(Collection<Identifier> channels, String mode, String brand,
                                  boolean integratedServer, int limit, Collection<String> disabled,
                                  boolean measured) {
        Map<String, Integer> counts = new HashMap<>();
        if (channels != null) {
            for (Identifier id : channels) {
                if (id == null) continue;
                String ns;
                try {
                    ns = id.getNamespace();
                } catch (Throwable t) {
                    continue;   // 极端情况下（被其它模组替换过的 Identifier）不要影响连接
                }
                if (ns == null || ns.isEmpty()) continue;
                counts.merge(ns, 1, Integer::sum);
            }
        }
        List<NamespaceCount> list = new ArrayList<>(counts.size());
        int total = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            list.add(new NamespaceCount(e.getKey(), e.getValue()));
            total += e.getValue();
        }
        list.sort(Comparator.comparingInt(NamespaceCount::count).reversed()
            .thenComparing(NamespaceCount::namespace));
        List<String> disabledList = disabled == null ? List.of() : List.copyOf(disabled);
        return new Snapshot(System.currentTimeMillis(), measured, mode, brand, integratedServer,
            total, limit, List.copyOf(list), disabledList);
    }

    /**
     * 「当前声明通道数」：总通道数减去被禁用命名空间的通道数。
     * {@code e33chat} 永远计入（它是必需通道）。
     */
    public static int declaredTotal(Snapshot snapshot, Collection<String> disabled) {
        if (snapshot == null) return 0;
        Set<String> off = new HashSet<>();
        if (disabled != null) {
            for (String ns : disabled) {
                if (ns != null && !ns.isEmpty()) off.add(ns.toLowerCase(Locale.ROOT));
            }
        }
        int n = 0;
        for (NamespaceCount nc : snapshot.namespaces()) {
            if (!ChannelDietFilter.REQUIRED_NAMESPACE.equals(nc.namespace()) && off.contains(nc.namespace())) continue;
            n += nc.count();
        }
        return n;
    }
}
