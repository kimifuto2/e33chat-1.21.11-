package com.niuqu.chatbubble.channeldiet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「通道节食」的上限 / 裁剪计划——**纯逻辑**，不引用任何 Minecraft 类。
 *
 * <p>拆成「先算计划、再由 mixin 落地」两步，好处是这一步可以脱离游戏做穷举验证：
 * 输入「命名空间 → 通道数」的计数表，输出「保留哪些命名空间、因配置剔除了哪些、
 * 因超限又剔除了哪些」。mixin 只需要按计划过滤 {@code Identifier} 集合。
 *
 * <p><b>3.0.3 起没有「三态模式」了</b>：是否裁剪只看配置里的唯一总开关
 * {@code ChatBubbleConfig#channelDietEnabled}（见 {@code ChannelRegistrationDietMixin}）。
 * 这里只剩两个历史取值常量，仅用于给 {@code ChannelDietStats.Snapshot#mode} 填值（不参与判定）。
 *
 * <h2>背景数字</h2>
 * Paper（PluginMessageBridgeImpl）对客户端注册的自定义通道有硬上限 128 且判定是
 * 严格小于（{@code registeredChannels.size() >= 128} 就踢），即**实际最多 127 条**。
 * 所以本类的合法上限范围是 32~127，默认 120 留出余量。
 */
public final class ChannelDietFilter {
    private ChannelDietFilter() {}

    /** 统计快照里的「未裁剪」取值（总开关关闭 → 只统计不裁剪）。 */
    public static final String MODE_OFF = "off";
    /** 统计快照里的「裁剪」取值（总开关开启 → 按禁用命名空间 + 上限裁剪）。 */
    public static final String MODE_ACTIVE = "active";

    /** Paper 的通道上限（严格 {@code < 128}，即最多 127 条）。 */
    public static final int PAPER_HARD_LIMIT = 128;
    public static final int LIMIT_DEFAULT = 120;
    public static final int LIMIT_MIN = 32;
    public static final int LIMIT_MAX = 127;

    /** 本模组自己的命名空间：永远保留、不可被禁用。 */
    public static final String REQUIRED_NAMESPACE = "e33chat";

    /** 上限夹到 32~127；null 用默认 120。 */
    public static int clampLimit(Integer raw) {
        if (raw == null) return LIMIT_DEFAULT;
        return Math.max(LIMIT_MIN, Math.min(LIMIT_MAX, raw));
    }

    /**
     * 命名空间名单清洗：去空白、转小写、去重、丢弃空项与 {@code e33chat}。
     * 返回的列表保持首次出现顺序。
     */
    public static List<String> normalizeNamespaces(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String ns = s.trim().toLowerCase(Locale.ROOT);
            if (ns.isEmpty() || REQUIRED_NAMESPACE.equals(ns)) continue;
            out.add(ns);
        }
        return List.copyOf(out);
    }

    /** 归一后的禁用集合（便于 O(1) 查询）。 */
    public static Set<String> namespaceSet(Collection<String> raw) {
        List<String> list = normalizeNamespaces(raw);
        return list.isEmpty() ? Set.of() : Collections.unmodifiableSet(new HashSet<>(list));
    }

    /**
     * 一次裁剪计划。
     *
     * @param total          裁剪前的通道总数
     * @param limit          生效上限（已夹取）
     * @param kept           保留的命名空间（顺序 = 必需优先、其余按通道数从多到少）
     * @param droppedByConfig 因 {@code channelDietDisabledNamespaces} 被整族剔除的命名空间
     * @param droppedByLimit  配置剔除后仍超限，按「通道数从多到少」继续剔除的命名空间
     * @param keptTotal      计划保留的通道数（未做单条截断前）
     * @param truncated      true = 连「只留 e33chat」都还超限，调用方需按条数兜底截断
     */
    public record Plan(int total, int limit, List<String> kept,
                       List<String> droppedByConfig, List<String> droppedByLimit,
                       int keptTotal, boolean truncated) {
        /** 该命名空间是否被计划保留。 */
        public boolean keeps(String namespace) {
            return kept.contains(namespace);
        }
    }

    /**
     * 计算裁剪计划。
     *
     * @param counts      命名空间 → 通道数（null / 非正数条目被忽略）
     * @param limit       上限（调用方已 {@link #clampLimit} 过）
     * @param disabledRaw 配置里禁用的命名空间
     */
    public static Plan plan(Map<String, Integer> counts, int limit, Collection<String> disabledRaw) {
        Map<String, Integer> c = new HashMap<>();
        if (counts != null) {
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getKey() == null || e.getKey().isEmpty()) continue;
                Integer v = e.getValue();
                if (v == null || v <= 0) continue;
                c.put(e.getKey(), v);
            }
        }
        int total = 0;
        for (int v : c.values()) total += v;
        int cap = Math.max(1, limit);
        Set<String> disabled = namespaceSet(disabledRaw);

        // 1) 配置禁用：整族剔除（e33chat 例外）
        List<String> kept = new ArrayList<>();
        List<String> droppedByConfig = new ArrayList<>();
        for (String ns : orderedNamespaces(c)) {
            if (!REQUIRED_NAMESPACE.equals(ns) && disabled.contains(ns)) droppedByConfig.add(ns);
            else kept.add(ns);
        }
        int keptTotal = sum(kept, c);

        // 2) 超限：按通道数从多到少继续整族剔除（e33chat 永不剔除）
        List<String> droppedByLimit = new ArrayList<>();
        if (keptTotal > cap) {
            List<String> trimmable = new ArrayList<>(kept);
            trimmable.remove(REQUIRED_NAMESPACE);
            trimmable.sort(byCountDesc(c));
            for (String ns : trimmable) {
                if (keptTotal <= cap) break;
                keptTotal -= c.getOrDefault(ns, 0);
                kept.remove(ns);
                droppedByLimit.add(ns);
            }
        }
        boolean truncated = keptTotal > cap;
        return new Plan(total, cap, List.copyOf(kept), List.copyOf(droppedByConfig),
            List.copyOf(droppedByLimit), keptTotal, truncated);
    }

    /** 单个命名空间的通道数（不存在 → 0）。 */
    public static int countOf(Map<String, Integer> counts, String namespace) {
        if (counts == null || namespace == null) return 0;
        Integer v = counts.get(namespace);
        return v == null ? 0 : v;
    }

    /** 排序：e33chat 第一，其后通道数从多到少，再按名字（保证每次一致）。 */
    private static List<String> orderedNamespaces(Map<String, Integer> counts) {
        List<String> all = new ArrayList<>(counts.keySet());
        all.sort((a, b) -> {
            boolean ra = REQUIRED_NAMESPACE.equals(a);
            boolean rb = REQUIRED_NAMESPACE.equals(b);
            if (ra != rb) return ra ? -1 : 1;
            int ca = counts.getOrDefault(a, 0);
            int cb = counts.getOrDefault(b, 0);
            if (ca != cb) return Integer.compare(cb, ca);
            return a.compareTo(b);
        });
        return all;
    }

    private static java.util.Comparator<String> byCountDesc(Map<String, Integer> counts) {
        return (a, b) -> {
            int ca = counts.getOrDefault(a, 0);
            int cb = counts.getOrDefault(b, 0);
            if (ca != cb) return Integer.compare(cb, ca);
            return a.compareTo(b);
        };
    }

    private static int sum(Collection<String> namespaces, Map<String, Integer> counts) {
        int n = 0;
        for (String ns : namespaces) n += counts.getOrDefault(ns, 0);
        return n;
    }
}
