package com.niuqu.chatbubble.chat.group;

import com.niuqu.chatbubble.render.Appearance;
import com.niuqu.chatbubble.render.ChatBubbleTheme;
import com.niuqu.chatbubble.render.RoundRectRenderer;
import com.niuqu.chatbubble.render.SkinResolver;
import com.niuqu.chatbubble.render.UiTokens;
import com.niuqu.chatbubble.texture.ColoredTextureRenderer;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * 群聊二级界面（「添加新群聊」/「群聊设置」）共用的绘制小工具。
 *
 * <p>风格约定（与聊天面板保持一致，见 {@link UiTokens} 与 {@link ChatBubbleTheme}）：
 * <ul>
 *   <li>面板：{@link UiTokens#SHADOW_ALPHA_PANEL} 硬阴影 + {@link UiTokens#RADIUS_LARGE} 圆角；
 *       底色用主题 {@code panelBg}，1px 描边用 {@code textMuted} 半透明。</li>
 *   <li>按钮：外圈 1px 描边圆角 6 + 内层圆角 5（与聊天面板的图标按钮/免打扰按钮同一套弧度），
 *       悬停叠一层 {@code popupHover}；主按钮（完成/保存）用强调色填充。</li>
 *   <li>头像：3:3 采样头部（8,8）→ 与聊天面板/横幅完全同一种画法。</li>
 * </ul>
 */
public final class GroupUi {
    private GroupUi() {}

    /** 面板圆角（与 UiTokens 的大档一致）。 */
    public static final int PANEL_RADIUS = UiTokens.RADIUS_LARGE;
    /** 按钮外圈圆角 / 内层圆角（与聊天面板图标按钮一致）。 */
    public static final float BTN_RADIUS = 6f;
    public static final float BTN_INNER_RADIUS = 5f;
    /** 屏幕遮罩：半透明黑，让居中面板"浮"起来。 */
    public static final int DIM_COLOR = 0x99000000;

    public static ChatBubbleTheme.Colors colors() {
        return Appearance.snapshot();
    }

    public static ChatBubbleTheme theme() {
        return Appearance.resolvedTheme();
    }

    /** 半透明遮罩（整屏）。 */
    public static void dim(DrawContext g, int width, int height) {
        g.fill(0, 0, width, height, DIM_COLOR);
    }

    /** 居中面板：硬阴影 + 圆角底色 + 1px 描边。 */
    public static void panel(DrawContext g, int x, int y, int w, int h) {
        ChatBubbleTheme.Colors c = colors();
        int off = UiTokens.SHADOW_OFFSET_PANEL;
        int shadowA = UiTokens.SHADOW_ALPHA_PANEL;
        // 描边：比面板大 1px 的半透明 textMuted 圆角，再由面板底色盖住内部
        RoundRectRenderer.fill(g, x - 1, y - 1, x + w + 1, y + h + 1,
            PANEL_RADIUS + 1, ChatBubbleTheme.alphaBlend(c.textMuted(), 0x55));
        RoundRectRenderer.fill(g, x + off, y + off, x + w + off, y + h + off,
            PANEL_RADIUS, (shadowA << 24));
        RoundRectRenderer.fill(g, x, y, x + w, y + h, PANEL_RADIUS, c.panelBg());
    }

    /** 一条分隔线（1px，主题 divider 色）。 */
    public static void divider(DrawContext g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, ChatBubbleTheme.alphaBlend(colors().divider(), 0xCC));
    }

    /** 表单标签（小号灰字，画在输入框上方 —— 明确告诉玩家这一行填的是什么）。 */
    public static void label(DrawContext g, TextRenderer tr, Text text, int x, int y) {
        g.drawText(tr, text.getString(), x, y,
            ChatBubbleTheme.alphaBlend(colors().textMuted(), 0xFF), false);
    }

    /** 标题（居中）。 */
    public static void title(DrawContext g, TextRenderer tr, Text text, int cx, int y, int color) {
        String s = text.getString();
        g.drawText(tr, s, cx - tr.getWidth(s) / 2, y, color, false);
    }

    /**
     * 画一个圆角按钮（不负责命中测试，调用方自己算矩形）。
     *
     * @param primary true = 强调色填充（主操作），false = 与聊天面板图标按钮同款的描边按钮
     * @param danger  true = 文字用红色（解散群聊这类破坏性操作）
     */
    public static void button(DrawContext g, TextRenderer tr, int x, int y, int w, int h,
                              Text label, boolean hover, boolean primary, boolean danger,
                              boolean enabled) {
        ChatBubbleTheme.Colors c = colors();
        int border = primary
            ? ChatBubbleTheme.alphaBlend(c.iconHover(), enabled ? 0xFF : 0x66)
            : ChatBubbleTheme.alphaBlend(c.textMuted(), enabled ? 0xFF : 0x55);
        int bg = primary
            ? ChatBubbleTheme.alphaBlend(c.iconHover(), enabled ? 0xFF : 0x55)
            : ChatBubbleTheme.alphaBlend(c.inputBg(), enabled ? 0xFF : 0x88);
        RoundRectRenderer.fill(g, x, y, x + w, y + h, BTN_RADIUS, border);
        RoundRectRenderer.fill(g, x + 1, y + 1, x + w - 1, y + h - 1, BTN_INNER_RADIUS, bg);
        if (hover && enabled) {
            RoundRectRenderer.fill(g, x, y, x + w, y + h, BTN_RADIUS,
                ChatBubbleTheme.alphaBlend(primary ? 0xFFFFFFFF : c.popupHover(), primary ? 0x33 : 0xFF));
        }
        int textColor = !enabled ? ChatBubbleTheme.alphaBlend(c.textMuted(), 0xAA)
            : primary ? 0xFFFFFFFF
            : danger ? c.redDot()
            : c.textPrimary();
        String s = label.getString();
        g.drawText(tr, s, x + (w - tr.getWidth(s)) / 2, y + (h - tr.fontHeight) / 2 + 1, textColor, false);
    }

    /** 列表行悬停高亮（圆角 5，只铺一层主题 hover 色，不画描边）。 */
    public static void rowHighlight(DrawContext g, int x, int y, int w, int h) {
        RoundRectRenderer.fill(g, x, y, x + w, y + h, 5f,
            ChatBubbleTheme.alphaBlend(colors().popupHover(), 0xFF));
    }

    /** 输入框圆角底（外圈 5 + 内圈 4，与聊天面板输入框同一套弧度）。 */
    public static void fieldBox(DrawContext g, int x, int y, int w, int h) {
        ChatBubbleTheme.Colors c = colors();
        RoundRectRenderer.fill(g, x, y, x + w, y + h, 5f,
            ChatBubbleTheme.alphaBlend(c.textMuted(), 0x66));
        RoundRectRenderer.fill(g, x + 1, y + 1, x + w - 1, y + h - 1, 4f, c.inputBg());
    }

    /** 头像（3:3 采样头部 + 帽子层），alpha 固定 1。 */
    public static void head(DrawContext g, UUID uuid, String name, int x, int y, int size) {
        Identifier skin = SkinResolver.getSkin(uuid, name);
        head(g, skin, x, y, size);
    }

    public static void head(DrawContext g, Identifier skin, int x, int y, int size) {
        if (size <= 0) return;
        int hat = size + 1;
        int off = (hat - size) / 2;
        ColoredTextureRenderer.drawWithAlpha(g, skin, x, y, size, size, 8.0F, 8.0F, 8, 8, 64, 64, 1f);
        ColoredTextureRenderer.drawWithAlpha(g, skin, x - off, y - off, hat, hat, 40.0F, 8.0F, 8, 8, 64, 64, 1f);
    }

    /** 勾选框：**选中 = 实心蓝填充，不画 ✓**（用户明确要求）；未选 = 空心圆角描边框。
     *
     *  <p>画法只有两层圆角：外圈 1px 描边（选中时用强调色、未选时用 textMuted）+ 内缩 1px 的填充
     *  （选中 = 强调色，未选 = 输入框底色）。不用字符 `✓` 是因为默认字体里它既不居中、
     *  在 10px 的框里还会糊成一团；纯色块反而更干净，也和聊天面板的按钮同构。
     *  {@code tr} 保留在签名里（调用点很多），当前实现已不再绘制任何文字。 */
    public static void checkbox(DrawContext g, TextRenderer tr, int x, int y, int size, boolean checked) {
        ChatBubbleTheme.Colors c = colors();
        int accent = c.iconHover();
        int border = ChatBubbleTheme.alphaBlend(checked ? accent : c.textMuted(), 0xFF);
        RoundRectRenderer.fill(g, x, y, x + size, y + size, 3f, border);
        RoundRectRenderer.fill(g, x + 1, y + 1, x + size - 1, y + size - 1, 2.5f,
            ChatBubbleTheme.alphaBlend(checked ? accent : c.inputBg(), 0xFF));
    }

    /** 文本按像素宽截断并补 "..."。 */
    public static String ellipsize(TextRenderer tr, String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) return "";
        if (tr.getWidth(text) <= maxWidth) return text;
        String dots = "...";
        int dotsW = tr.getWidth(dots);
        if (maxWidth <= dotsW) return tr.trimToWidth(text, maxWidth);
        return tr.trimToWidth(text, maxWidth - dotsW) + dots;
    }
}
