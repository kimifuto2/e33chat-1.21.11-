package com.niuqu.chatbubble.ui;

import com.niuqu.chatbubble.render.ChatBubbleTheme;
import com.niuqu.chatbubble.render.RoundRectRenderer;

import net.minecraft.client.gui.Font;
import com.niuqu.chatbubble.render.UiCanvas;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.Mth;

/**
 * 「自定义动作词条」小卡片：点动作菜单最底下的「自定义」后，在**聊天输入框上方**弹出，
 * 一次性输入一个词（不保存），确定后聊天输入框前缀变成 {@code 【自定义】}。
 *
 * <p>视觉与其它弹层同一套：{@link RoundRectRenderer#panel} 圆角 9f + 1px 描边；打开/关闭
 * 动画复用 Screen 的 {@code renderPopupWithAnim}（与设置菜单/表情/快捷聊天完全同一条曲线）。
 * 输入框是 Screen 持有的 {@link EditBox}（无背景），坐标在 {@link #render} 里按本卡片
 * 的矩形回填 —— 绘制与命中判定因此永远同源。
 */
public class PlayerActionCustomPopup {
    public static final int W = 176;
    public static final int H = 32;
    /** 输入框高度（与底栏输入框一致）。 */
    public static final int INPUT_H = 14;
    /**
     * 输入框里文字行的顶部相对**框顶**的偏移（垂直居中）。
     *
     * <p>1.21.11 的 {@code EditBox} 在 {@code drawsBackground=false} 时把文字画在
     * {@code textY = getY()} 上（{@code updateTextPosition()} 只有 drawsBackground 为真才加
     * {@code (height - 8) / 2}），所以"文字在框内垂直居中"只能由我们传给 {@code setY} 的值决定：
     * {@code y = 框顶 + (框高 - fontHeight) / 2}。框高 14、原版 fontHeight 9 →
     * {@code (14 - 9 + 1) / 2 = 3}（向上取整，视觉上更贴中线）。
     *
     * <p>底栏聊天输入框用的是同一条规则（{@code ChatBubbleScreen.layoutInputField} 里的
     * {@code chatField.setY(ibY + 3)}，框顶/框高与本卡片完全相同），两处必须同值，
     * 否则同一个控件在底栏与卡片里的文字高度会差 1~2px（以前这里是 {@code +0}，
     * 所以卡片里的文字贴着上边缘）。
     */
    private static final int INPUT_TEXT_OFFSET = 3;
    private static final int PAD = 6;
    private static final int BTN_W = 30;
    private static final int BTN_H = 14;
    private static final int CANCEL_S = 12;
    private static final int GAP = 4;

    /** {@link #handleClick} 的返回值。 */
    public static final int CLICK_OUTSIDE = -1;
    public static final int CLICK_NONE = 0;
    public static final int CLICK_CONFIRM = 1;
    public static final int CLICK_CANCEL = 2;

    public boolean visible;

    /** 卡片矩形（屏幕坐标，打开时算好）。 */
    public int x;
    public int y;

    /** 打开前的布局：面板内水平居中、贴聊天输入框上方。返回 {x, y}。 */
    public static int[] layout(int panelX, int panelW, int barTop) {
        int minX = panelX + 2;
        int maxX = Math.max(minX, panelX + panelW - W - 2);
        int px = Mth.clamp(panelX + panelW / 2 - W / 2, minX, maxX);
        int py = Math.max(0, barTop - H - 6);
        return new int[]{px, py};
    }

    public void open(int px, int py) {
        this.x = px;
        this.y = py;
        this.visible = true;
    }

    public int inputX() {
        return x + PAD;
    }

    public int inputY() {
        return y + (H - INPUT_H) / 2;
    }

    public int inputW() {
        return confirmX() - GAP - inputX();
    }

    public int confirmX() {
        return cancelX() - GAP - BTN_W;
    }

    public int confirmY() {
        return y + (H - BTN_H) / 2;
    }

    public int cancelX() {
        return x + W - PAD - CANCEL_S;
    }

    public int cancelY() {
        return y + (H - CANCEL_S) / 2;
    }

    public boolean isInsideInput(int mouseX, int mouseY) {
        if (!visible) return false;
        return mouseX >= inputX() - 2 && mouseX <= inputX() + inputW() + 2
            && mouseY >= inputY() - 2 && mouseY <= inputY() + INPUT_H + 2;
    }

    /**
     * 点击归类：确定 / 取消 / 卡片内部其它位置 / 卡片外（点外面 = 取消）。
     * 输入框区域的点击由调用方转给 EditBox（本方法只回 {@link #CLICK_NONE}）。
     */
    public int handleClick(int mouseX, int mouseY) {
        if (!visible) return CLICK_OUTSIDE;
        if (mouseX >= cancelX() && mouseX <= cancelX() + CANCEL_S
            && mouseY >= cancelY() && mouseY <= cancelY() + CANCEL_S) return CLICK_CANCEL;
        if (mouseX >= confirmX() && mouseX <= confirmX() + BTN_W
            && mouseY >= confirmY() && mouseY <= confirmY() + BTN_H) return CLICK_CONFIRM;
        boolean inside = mouseX >= x && mouseX <= x + W && mouseY >= y && mouseY <= y + H;
        return inside ? CLICK_NONE : CLICK_OUTSIDE;
    }

    /**
     * 画卡片并把输入框摆到卡片里（{@code input.setVisible(true)} 由这里负责）。
     *
     * @param confirmLabel 语言文件里的「确定」文案
     */
    public void render(UiCanvas g, Font font, ChatBubbleTheme.Colors c,
                       EditBox input, String confirmLabel,
                       int mouseX, int mouseY, float alpha) {
        if (!visible) return;
        int a255 = Math.max(0, Math.min(255, (int) (255 * alpha)));
        if (a255 <= 2) {
            input.setVisible(false);
            return;
        }

        RoundRectRenderer.panel(g, x, y, W, H, 9f,
            ChatBubbleTheme.alphaBlend(c.popupBg(), a255),
            ChatBubbleTheme.alphaBlend(c.divider(), a255));

        // 输入框底：始终画底色，聚焦/悬停时再套一圈圆角描边（与底栏输入框同款观感）。
        int ix = inputX();
        int iy = inputY();
        int iw = inputW();
        RoundRectRenderer.fill(g, ix - 2, iy - 2, ix + iw + 2, iy + INPUT_H + 2, 5f,
            ChatBubbleTheme.alphaBlend(c.inputBg(), a255));
        boolean hoverInput = mouseX >= ix - 2 && mouseX <= ix + iw + 2
            && mouseY >= iy - 2 && mouseY <= iy + INPUT_H + 2;
        if (hoverInput || input.isFocused()) {
            RoundRectRenderer.fill(g, ix - 2, iy - 2, ix + iw + 2, iy + INPUT_H + 2, 5f,
                ChatBubbleTheme.alphaBlend(c.textMuted(), a255));
            RoundRectRenderer.fill(g, ix - 1, iy - 1, ix + iw + 1, iy + INPUT_H + 1, 4f,
                ChatBubbleTheme.alphaBlend(c.inputBg(), a255));
        }

        boolean hoverConfirm = mouseX >= confirmX() && mouseX <= confirmX() + BTN_W
            && mouseY >= confirmY() && mouseY <= confirmY() + BTN_H;
        drawButton(g, font, c, confirmX(), confirmY(), BTN_W, BTN_H, confirmLabel, a255, hoverConfirm);

        boolean hoverCancel = mouseX >= cancelX() && mouseX <= cancelX() + CANCEL_S
            && mouseY >= cancelY() && mouseY <= cancelY() + CANCEL_S;
        RoundRectRenderer.fill(g, cancelX(), cancelY(), cancelX() + CANCEL_S, cancelY() + CANCEL_S, 4f,
            ChatBubbleTheme.alphaBlend(hoverCancel ? c.popupHover() : c.inputBg(), a255));
        String cross = "✕";
        g.drawText(font, cross, cancelX() + (CANCEL_S - font.width(cross)) / 2,
            cancelY() + (CANCEL_S - font.lineHeight) / 2 + 1,
            ChatBubbleTheme.alphaBlend(c.textMuted(), a255), false);

        input.setX(ix);
        // 文字行的 y = 框顶 + INPUT_TEXT_OFFSET（框顶 = inputY()，与底栏同一规则）：
        // drawsBackground=false 时 widget 的 textY 恒等于 widget.y，所以居中只能在这里给。
        input.setY(inputY() + INPUT_TEXT_OFFSET);
        input.setWidth(iw);
        input.setHeight(INPUT_H);
        input.setVisible(true);
    }

    /** 与底栏图标按钮同一套：外圈 1px 描边 + 内缩 1px 底色 + 悬停叠一层。 */
    private static void drawButton(UiCanvas g, Font font, ChatBubbleTheme.Colors c,
                                   int bx, int by, int bw, int bh, String label,
                                   int a255, boolean hover) {
        RoundRectRenderer.fill(g, bx, by, bx + bw, by + bh, 6f,
            ChatBubbleTheme.alphaBlend(c.textMuted(), a255));
        RoundRectRenderer.fill(g, bx + 1, by + 1, bx + bw - 1, by + bh - 1, 5f,
            ChatBubbleTheme.alphaBlend(c.inputBg(), a255));
        if (hover) {
            RoundRectRenderer.fill(g, bx, by, bx + bw, by + bh, 6f,
                ChatBubbleTheme.alphaBlend(c.popupHover(), a255));
        }
        int tw = font.width(label);
        g.drawText(font, label, bx + (bw - tw) / 2, by + (bh - font.lineHeight) / 2 + 1,
            ChatBubbleTheme.alphaBlend(c.textPrimary(), a255), false);
    }
}
