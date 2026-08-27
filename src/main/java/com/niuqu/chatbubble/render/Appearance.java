package com.niuqu.chatbubble.render;
import com.niuqu.chatbubble.ChatBubbleClientSetup;
import com.niuqu.chatbubble.config.ChatBubbleConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * 外观快照：当前主题的颜色快照（取色统一入口）+ 布局参数宿主。
 */
public final class Appearance {
    private Appearance() {}

    /** 解析出的基础明暗主题（DARK/LIGHT），纹理目录据此选择。 */
    public static ChatBubbleTheme resolvedTheme() {
        ChatBubbleConfig cfg = ChatBubbleClientSetup.config();
        return ChatBubbleTheme.ColorMode.parse(cfg.theme()) == ChatBubbleTheme.ColorMode.LIGHT
            ? ChatBubbleTheme.LIGHT : ChatBubbleTheme.DARK;
    }

    /** 当前主题的颜色快照（含强调色与自定义覆盖）。 */
    public static ChatBubbleTheme.Colors snapshot() {
        ChatBubbleConfig cfg = ChatBubbleClientSetup.config();
        ChatBubbleTheme.ColorMode mode = ChatBubbleTheme.ColorMode.parse(cfg.theme());
        ChatBubbleTheme.Accent accent = ChatBubbleTheme.Accent.parse(cfg.themeAccent());
        Map<String, Integer> custom = parseCustom(cfg.themeCustom());
        return ChatBubbleTheme.resolve(mode, accent, custom);
    }

    /** 自定义配色：字段名→ARGB，非法值丢弃。 */
    private static Map<String, Integer> parseCustom(Map<String, String> custom) {
        if (custom == null || custom.isEmpty()) return Map.of();
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, String> e : custom.entrySet()) {
            Integer v = parseHex(e.getValue());
            if (v != null) out.put(e.getKey(), v);
        }
        return out;
    }

    /** 支持 "RRGGBB"（不透明）或 "AARRGGBB"；非法返回 null。 */
    private static Integer parseHex(String hex) {
        if (hex == null) return null;
        try {
            String h = hex.replace("#", "").trim();
            if (h.length() == 6) return 0xFF000000 | Integer.parseInt(h, 16);
            if (h.length() == 8) return (int) Long.parseLong(h, 16);
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    /** 消息之间的垂直间距（手改配置文件也可能越界，钳制到 UI 范围）。 */
    public static int messageGap() {
        Integer g = ChatBubbleClientSetup.config().messageGap();
        return g == null ? 6 : Math.max(0, Math.min(12, g));
    }


    /** 消息气泡头像尺寸（像素，钳制到 UI 范围）。 */
    public static int avatarSize() {
        Integer a = ChatBubbleClientSetup.config().avatarSize();
        return a == null ? 20 : Math.max(12, Math.min(32, a));
    }

    /** 气泡文字目标高度 px（钳制到 5-14，默认 9 = 原版字高）。 */
    public static int bubbleSizePx() {
        Integer s = ChatBubbleClientSetup.config().bubbleSize();
        return s == null ? 9 : Math.max(5, Math.min(14, s));
    }

    /** 指定字高下气泡缩放系数（px / 字高）。 */
    public static float bubbleScale(int fontHeight) {
        return Math.max(5, Math.min(14, bubbleSizePx())) / (float) fontHeight;
    }

    /** 指定缩放下气泡文字换行宽度（设计单位，更大气泡每行容纳更少字符；钳制保证可读）。 */
    public static int scaledWrapWidth(int bubbleMaxW, float scale) {
        return Math.max(16, (int) (bubbleMaxW / scale));
    }

    /** 当前缩放下气泡文字换行宽度。 */
    public static int bubbleWrapWidth(int bubbleMaxW, int fontHeight) {
        return scaledWrapWidth(bubbleMaxW, bubbleScale(fontHeight));
    }
}
