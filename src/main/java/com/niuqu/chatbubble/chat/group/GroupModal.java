package com.niuqu.chatbubble.chat.group;

import com.niuqu.chatbubble.render.ChatBubbleTheme;
import com.niuqu.chatbubble.render.RoundRectRenderer;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

/**
 * 「群聊二级界面」的**面板内模态层**基类（新建群聊 / 群聊设置 / 添加玩家）。
 *
 * <p>为什么不再是 {@code Screen}：以前这两个界面各自 {@code client.setScreen(...)}，
 * 结果是聊天面板被整块替换掉（打开新建群聊后看不到聊天内容，关掉还要再开一次聊天）。
 * 现在改成——
 * <ul>
 *   <li>由 {@link com.niuqu.chatbubble.ChatBubbleScreen} 持有唯一一个 {@code activeGroupModal}；</li>
 *   <li>每帧在**聊天面板画完之后**，在面板矩形内（{@link Host#modalAreaX()} 起、宽
 *       {@link Host#modalAreaW()}）先铺一层只在面板范围内生效的半透明遮罩，再把卡片
 *       **在面板内居中**画出来 —— 侧边栏永不被覆盖，卡片也不会越出面板边界；</li>
 *   <li>输入事件（点击 / 滚轮 / 键盘 / 字符 / 拖拽 / 释放）在 {@code ChatBubbleScreen} 的
 *       同名方法**最前面**优先分发给这里，只有返回 true 才拦截（模态层实际总是吞掉输入，
 *       点卡片外 = 关闭）；ESC 只关模态，不关聊天面板。</li>
 * </ul>
 *
 * <p>子类负责：卡片尺寸（{@link #preferredWidth()}/{@link #preferredHeight()}）、
 * 子控件排布（{@link #layout()}）、绘制（{@link #render}）与事件处理。
 * 几何唯一来源是 {@link #cardX}/{@link #cardY}/{@link #cardW}/{@link #cardH}，
 * 绘制与命中都用它们，不会错位。
 */
public abstract class GroupModal {

    /** 模态层的宿主（由 {@code ChatBubbleScreen} 实现）：字体 + 面板矩形 + 开关模态。 */
    public interface Host {
        TextRenderer font();

        /** 可用区域的左上角 X（= 聊天面板左边缘，已排除侧边栏）。 */
        int modalAreaX();

        int modalAreaY();

        /** 可用区域宽（= 聊天面板宽）。 */
        int modalAreaW();

        int modalAreaH();

        /** 打开一个模态层（替换当前的那个）。 */
        void openGroupModal(GroupModal modal);

        /** 关闭当前模态层（回到普通聊天面板）。 */
        void closeGroupModal();
    }

    /** 面板范围内遮罩的透明度：够暗能看出"下面是禁用的"，又不至于看不清面板本身。 */
    protected static final int SCRIM_COLOR = 0x88000000;
    /** 卡片与面板边缘之间至少留出的空隙。 */
    protected static final int EDGE_PAD = 8;

    protected final Host host;
    protected final TextRenderer tr;

    /** 卡片矩形（屏幕坐标，每帧由 {@link #ensureLayout()} 重算）。 */
    protected int cardX, cardY, cardW, cardH;

    protected GroupModal(Host host) {
        this.host = host;
        this.tr = host.font();
    }

    // ===== 尺寸 / 布局 =====

    /** 卡片期望宽度（最终会被夹到 面板宽 - 2*EDGE_PAD 以内）。 */
    protected abstract int preferredWidth();

    /** 卡片期望高度（最终会被夹到 面板高 - 2*EDGE_PAD 以内）。 */
    protected abstract int preferredHeight();

    /** 卡片矩形确定后重排子控件（TextFieldWidget 的 setX/setY/setWidth）。 */
    protected abstract void layout();

    /**
     * 按宿主面板矩形在**面板内居中**重算卡片矩形；矩形变化时同步重排子控件。
     * 每帧渲染 / 每次事件分发前调用（幂等，开销只有几次整数比较）。
     */
    public final void ensureLayout() {
        int ax = host.modalAreaX();
        int ay = host.modalAreaY();
        int aw = Math.max(40, host.modalAreaW());
        int ah = Math.max(40, host.modalAreaH());
        int w = Math.max(80, Math.min(preferredWidth(), aw - EDGE_PAD * 2));
        int h = Math.max(60, Math.min(preferredHeight(), ah - EDGE_PAD * 2));
        int x = ax + (aw - w) / 2;
        int y = ay + (ah - h) / 2;
        if (x == cardX && y == cardY && w == cardW && h == cardH) return;
        cardX = x;
        cardY = y;
        cardW = w;
        cardH = h;
        layout();
    }

    // ===== 绘制小工具（子类复用，保证两个界面同一套观感）=====

    /** 只在聊天面板矩形内铺半透明遮罩（侧边栏保持不变）。 */
    protected final void dimHost(DrawContext g) {
        g.fill(host.modalAreaX(), host.modalAreaY(),
            host.modalAreaX() + host.modalAreaW(),
            host.modalAreaY() + host.modalAreaH(), SCRIM_COLOR);
    }

    /** 卡片：硬阴影 + 圆角底 + 1px 描边（与聊天面板同款，见 {@link GroupUi#panel}）。 */
    protected final void drawCard(DrawContext g) {
        GroupUi.panel(g, cardX, cardY, cardW, cardH);
    }

    /** 鼠标是否落在卡片内。 */
    protected final boolean insideCard(double mx, double my) {
        return mx >= cardX && mx <= cardX + cardW && my >= cardY && my <= cardY + cardH;
    }

    /** 卡片内的一条底部分隔线（画在按钮行上方）。 */
    protected final void drawFooterLine(DrawContext g, int btnY) {
        GroupUi.divider(g, cardX + 8, btnY - 6, cardW - 16);
    }

    /** 卡片标题（居中，带底部细分隔线）。 */
    protected final void drawTitle(DrawContext g, Text title, int titleH) {
        ChatBubbleTheme.Colors c = GroupUi.colors();
        GroupUi.title(g, tr, title, cardX + cardW / 2, cardY + 8, c.textPrimary());
        GroupUi.divider(g, cardX + 8, cardY + titleH - 5, cardW - 16);
    }

    /** 简单文字按钮的命中判定（绘制和点击共用同一份矩形算式的便捷入口）。 */
    protected static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /** 空列表 / 空状态提示（居中灰字）。 */
    protected final void drawEmptyHint(DrawContext g, Text text, int y) {
        String s = text.getString();
        g.drawText(tr, s, cardX + (cardW - tr.getWidth(s)) / 2, y,
            ChatBubbleTheme.alphaBlend(GroupUi.colors().textMuted(), 0xFF), false);
    }

    /** 圆角填充的快捷入口（子类画细线/小条时用）。 */
    protected static void fill(DrawContext g, int x1, int y1, int x2, int y2, float radius, int argb) {
        RoundRectRenderer.fill(g, x1, y1, x2, y2, radius, argb);
    }

    // ===== 生命周期 =====

    /** 模态打开（子类可在这里聚焦输入框 / 重新拉一次在线玩家）。 */
    public void onOpened() {}

    /** 模态关闭（子类可在这里取消聚焦、丢弃编辑态）。 */
    public void onClosed() {}

    // ===== 渲染 / 输入（子类覆盖）=====

    public abstract void render(DrawContext g, int mouseX, int mouseY, float delta);

    /**
     * 点击。返回 true = 拦截（聊天面板不再处理这次点击）。
     * 约定：模态打开时**总是**返回 true —— 点卡片外视为"取消"，点卡片内的空白也吞掉，
     * 绝不能漏给下面的聊天面板（否则会顺手点到关闭键/发送键）。
     */
    public boolean mouseClicked(Click click, boolean doubleClick) {
        return true;
    }

    public boolean mouseReleased(Click click) {
        return true;
    }

    public boolean mouseDragged(Click click, double dragX, double dragY) {
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return true;
    }

    public boolean keyPressed(KeyInput input) {
        return true;
    }

    public boolean charTyped(CharInput input) {
        return true;
    }
}
