package com.niuqu.chatbubble.render;

import java.util.Map;

public enum ChatBubbleTheme {
    DARK,
    LIGHT;

    /** 主题明暗模式：DARK=暗色，LIGHT=浅色。 */
    public enum ColorMode {
        DARK,
        LIGHT;

        public static ColorMode parse(String s) {
            if (s == null) return DARK;
            return s.equalsIgnoreCase("light") ? LIGHT : DARK;
        }

        public String key() {
            return name().toLowerCase();
        }
    }

    /** 强调色变体：只给强调色相关字段上色，纹理仍用明暗版。 */
    public enum Accent {
        BLUE(0x1E90FF),
        PURPLE(0x9966CC),
        CYAN(0x00BBD4),
        ORANGE(0xFF8A00),
        GREEN(0x2ECC71),
        PINK(0xFF6EC7);

        public final int rgb;

        Accent(int rgb) {
            this.rgb = rgb;
        }

        public static Accent parse(String s) {
            if (s == null) return BLUE;
            for (Accent a : values()) {
                if (a.name().equalsIgnoreCase(s)) return a;
            }
            return BLUE;
        }

        public String key() {
            return name().toLowerCase();
        }
    }

    public record Colors(
        int panelBg, int titleBg, int barBg,
        int sidebarBg, int sidebarItemHover, int sidebarItemSelected, int sidebarDivider,
        int divider, int inputBg,
        int textPrimary, int textSecondary, int textMuted,
        int nameColor, int timeColor,
        int popupBg, int popupHover, int popupText,
        int contextBg, int contextHover, int contextText,
        int iconHover,
        int notificationText, int whisperBar,
        int toastBg, int toastText,
        int scrollbar, int scrollbarHover,
        int closeBg, int closeHoverBg, int closeText,
        int systemText, int quoteBar, int duplicateLabel,
        int redDot, int redDotMention,
        int configTitle, int configSection, int configLabel, int configBg,
        int bannerBg, int bannerText, int bannerBar
    ) {}

    public Colors colors() {
        return switch (this) {
            case DARK -> new Colors(
                0xD01E1E1E, 0xFF242424, 0xFF242424,
                0xFF1A1A1A, 0xFF2A2A2A, 0xFF3A3A3A, 0xFF333333,
                0xFF333333, 0xFF2A2A2A,
                0xFFFFFFFF, 0xFFAAAAAA, 0xFF888888,
                0xFFCCCCCC, 0xFF999999,
                0xB31E1E1E, 0xB3444444, 0xFFFFFFFF,
                0xEE2A2A2A, 0xFF4A4A4A, 0xFFFFFFFF,
                0xFF444444,
                0xFFFFFF55, 0xAA7B2D8B,
                0xCC000000, 0xFFFFFFFF,
                0x00FFFFFF, 0x00FFFFFF,
                0xFF333333, 0xFF555555, 0xFFCCCCCC,
                0xFF888888, 0xFFFFFFFF, 0xFFFFAA00,
                0xFFFF0000, 0xFFFF4444,
                0xFFFFFFFF, 0xFFFFAA00, 0xFFFFFFFF, 0xC0101010,
                0xEE333333, 0xFFFFFF55, 0xFFFFFFFF
            );
            case LIGHT -> new Colors(
                0x9AE8DFC8, 0xFFF0EDD8, 0xFFF0EDD8,
                0xFFD8D5C2, 0xFFDED9C4, 0xFFC9C6B3, 0xFFC8C3AC,
                0xFFC8C3AC, 0xFFD8CEB5,
                0xFF2D2D1F, 0xFF5A5A45, 0xFF787860,
                0xFF2D2D1F, 0xFF787860,
                0xDDF0EDD8, 0xDDE0DBC8, 0xFF2D2D1F,
                0xFFF0EDD8, 0xFFDDD8C0, 0xFF2D2D1F,
                0xFFD5D0B8,
                0xFFFFAA00, 0xAA9966CC,
                0xCC2D2D1F, 0xFFF5F0E0,
                0x60706858, 0x90505848,
                0xFFD5D0B8, 0xFFC0BBA3, 0xFF5A5A45,
                0xFF787860, 0xFFCC6600, 0xFFCC6600,
                0xFFCC0000, 0xFFFF2222,
                0xFF2D2D1F, 0xFFCC6600, 0xFF2D2D1F, 0xC0EDE8D3,
                0xEEF0EDD8, 0xFFCC6600, 0xFF471900
            );
        };
    }

    /**
     * 根据（明暗模式、强调色、自定义覆盖）解析出最终可用的配色。
     * AUTO 由调用方先解析成 DARK/LIGHT（BaseThemeResolver）后传入；
     * custom 为字段名（Colors record 的 component 名）→ ARGB 覆盖表。
     */
    public static Colors resolve(ColorMode mode, Accent accent, Map<String, Integer> custom) {
        ChatBubbleTheme base = (mode == ColorMode.LIGHT) ? LIGHT : DARK;
        Colors b = base.colors();
        // 自定义强调色优先（custom["accent"]），否则用预设 accent。
        int a = accent.rgb;
        if (custom != null && custom.containsKey("accent")) {
            a = custom.get("accent");
        }

        // 强调色相关字段：向所选强调色靠拢，保留各自本色明度，观感协调。
        Colors out = new Colors(
            b.panelBg(), b.titleBg(), b.barBg(),
            b.sidebarBg(), mix(b.sidebarItemHover(), a, 0.45), mix(b.sidebarItemSelected(), a, 0.55), b.sidebarDivider(),
            b.divider(), b.inputBg(),
            b.textPrimary(), b.textSecondary(), b.textMuted(),
            mix(b.nameColor(), a, 0.85), b.timeColor(),
            b.popupBg(), b.popupHover(), b.popupText(),
            b.contextBg(), mix(b.contextHover(), a, 0.35), b.contextText(),
            a,
            mix(b.notificationText(), a, 0.8), b.whisperBar(),
            b.toastBg(), b.toastText(),
            b.scrollbar(), b.scrollbarHover(),
            b.closeBg(), mix(b.closeHoverBg(), a, 0.5), b.closeText(),
            b.systemText(), mix(b.quoteBar(), a, 0.8), mix(b.duplicateLabel(), a, 0.85),
            b.redDot(), b.redDotMention(),
            b.configTitle(), mix(b.configSection(), a, 0.85), b.configLabel(), b.configBg(),
            b.bannerBg(), bannerText(a), b.bannerBar()
        );

        if (custom != null && !custom.isEmpty()) {
            out = applyCustom(out, custom);
        }
        return out;
    }

    /** 按 fieldName→ARGB 覆盖，忽略未知字段。 */
    private static Colors applyCustom(Colors c, Map<String, Integer> custom) {
        return new Colors(
            custom.getOrDefault("panelBg", c.panelBg()),
            custom.getOrDefault("titleBg", c.titleBg()),
            custom.getOrDefault("barBg", c.barBg()),
            custom.getOrDefault("sidebarBg", c.sidebarBg()),
            custom.getOrDefault("sidebarItemHover", c.sidebarItemHover()),
            custom.getOrDefault("sidebarItemSelected", c.sidebarItemSelected()),
            custom.getOrDefault("sidebarDivider", c.sidebarDivider()),
            custom.getOrDefault("divider", c.divider()),
            custom.getOrDefault("inputBg", c.inputBg()),
            custom.getOrDefault("textPrimary", c.textPrimary()),
            custom.getOrDefault("textSecondary", c.textSecondary()),
            custom.getOrDefault("textMuted", c.textMuted()),
            custom.getOrDefault("nameColor", c.nameColor()),
            custom.getOrDefault("timeColor", c.timeColor()),
            custom.getOrDefault("popupBg", c.popupBg()),
            custom.getOrDefault("popupHover", c.popupHover()),
            custom.getOrDefault("popupText", c.popupText()),
            custom.getOrDefault("contextBg", c.contextBg()),
            custom.getOrDefault("contextHover", c.contextHover()),
            custom.getOrDefault("contextText", c.contextText()),
            custom.getOrDefault("iconHover", c.iconHover()),
            custom.getOrDefault("notificationText", c.notificationText()),
            custom.getOrDefault("whisperBar", c.whisperBar()),
            custom.getOrDefault("toastBg", c.toastBg()),
            custom.getOrDefault("toastText", c.toastText()),
            custom.getOrDefault("scrollbar", c.scrollbar()),
            custom.getOrDefault("scrollbarHover", c.scrollbarHover()),
            custom.getOrDefault("closeBg", c.closeBg()),
            custom.getOrDefault("closeHoverBg", c.closeHoverBg()),
            custom.getOrDefault("closeText", c.closeText()),
            custom.getOrDefault("systemText", c.systemText()),
            custom.getOrDefault("quoteBar", c.quoteBar()),
            custom.getOrDefault("duplicateLabel", c.duplicateLabel()),
            custom.getOrDefault("redDot", c.redDot()),
            custom.getOrDefault("redDotMention", c.redDotMention()),
            custom.getOrDefault("configTitle", c.configTitle()),
            custom.getOrDefault("configSection", c.configSection()),
            custom.getOrDefault("configLabel", c.configLabel()),
            custom.getOrDefault("configBg", c.configBg()),
            custom.getOrDefault("bannerBg", c.bannerBg()),
            custom.getOrDefault("bannerText", c.bannerText()),
            custom.getOrDefault("bannerBar", c.bannerBar())
        );
    }

    /** 把 base 朝 tint 靠拢比例 f（0..1），保留 base 的 alpha。 */
    public static int mix(int base, int tint, double f) {
        int a = (base >>> 24) & 0xFF;
        int br = (base >>> 16) & 0xFF;
        int bg = (base >>> 8) & 0xFF;
        int bb = base & 0xFF;
        int tr = (tint >>> 16) & 0xFF;
        int tg = (tint >>> 8) & 0xFF;
        int tb = tint & 0xFF;
        int r = (int) Math.round(br + (tr - br) * f);
        int g = (int) Math.round(bg + (tg - bg) * f);
        int b = (int) Math.round(bb + (tb - bb) * f);
        return argb(a, r, g, b);
    }

    /** 横幅文字：取强调色与白色偏亮混合，保证在深色横幅上可读。 */
    private static int bannerText(int accent) {
        return mix(0xFFFFFFFF, accent, 0.55);
    }

    public static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int alphaBlend(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
