package com.niuqu.chatbubble.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.BossBarHud;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * 原版 Boss 血条（顶部居中的 BossBar HUD）软兼容桥：读出「当前显示了几条血条」，
 * 换算成它在屏幕顶部占用的底边，供音乐岛 / @ 横幅避让（否则灵动岛会压在血条上）。
 *
 * <p>为什么不直接用公开 API：{@link BossBarHud} 没有任何「当前显示了几条」的公开接口，
 * 唯一载体是包内可见的 {@code bossBars} 字段（{@code Map<UUID, ClientBossBar>}）。
 * 本模组不为一个纯装饰性避让再加一个 mixin accessor（多一个启动期注入点，版本一变
 * 就可能 Bootstrap 崩溃），所以按「这个类里唯一一个 Map 字段」反射定位；读不到就
 * 当没有血条（行为与改动前完全一致），任何异常都吞掉，不影响本体功能。
 *
 * <p>布局常量来自原版 {@code BossBarHud.render} 字节码（1.21.11 与 26.x 完全一致）：
 * 第一条从 {@code y = 12} 开始，{@code x = 屏宽/2 - 91}（宽 182，正好水平居中）；
 * 每条血条占 10px，行距 {@code y += 10 + 9}（9 = 字体高）= 19；血条名画在 {@code y - 9}。
 * 因此 n 条血条的底边 = {@code 12 + (n-1)*19 + 10}（1 条 22、2 条 41、3 条 60）。
 * 原版在 {@code y >= 屏高/3} 时停止继续画后面的血条，这里用同一口径封顶。
 */
public final class BossBarCompat {

    /** 第一条血条的 y。 */
    private static final int BAR_TOP = 12;
    /** 行距：单条 10px + 名称字体高 9px。 */
    private static final int ROW_STEP = 19;
    /** 单条血条占用的高度（原版 {@code y += 10 + 9} 里的 10）。 */
    private static final int BAND_H = 10;

    /** 反射拿到的血条 Map 字段；{@code null} 表示不可用（未初始化 / 结构不符）。 */
    private static Field barsField;
    /** 查找已失败过，不再重试（避免每帧反射扫描）。 */
    private static boolean fieldFailed;

    private BossBarCompat() {}

    /** 当前显示的 Boss 血条数量；读不到返回 0。 */
    public static int count() {
        Map<?, ?> bars = bars();
        return bars == null ? 0 : bars.size();
    }

    /**
     * Boss 血条在屏幕上占用的底边（缩放后 GUI 像素，与 {@code getScaledHeight} 同一空间）；
     * 没有血条时返回 0。
     */
    public static int bottomPx() {
        int n = count();
        if (n <= 0) return 0;
        int bottom = BAR_TOP + (n - 1) * ROW_STEP + BAND_H;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getWindow() != null) {
            int cap = mc.getWindow().getScaledHeight() / 3 + BAND_H;
            if (bottom > cap) bottom = cap;
        }
        return bottom;
    }

    private static Map<?, ?> bars() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.inGameHud == null) return null;
        BossBarHud hud = mc.inGameHud.getBossBarHud();
        if (hud == null) return null;
        Field f = field(hud);
        if (f == null) return null;
        try {
            Object value = f.get(hud);
            return value instanceof Map<?, ?> map ? map : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field field(BossBarHud hud) {
        if (barsField != null || fieldFailed) return barsField;
        for (Class<?> c = hud.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    barsField = f;
                    return barsField;
                } catch (Throwable t) {
                    // 继续找下一个 Map 字段
                }
            }
        }
        fieldFailed = true;
        return null;
    }
}
