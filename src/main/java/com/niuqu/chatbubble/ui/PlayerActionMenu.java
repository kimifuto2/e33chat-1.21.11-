package com.niuqu.chatbubble.ui;

import com.niuqu.chatbubble.chat.action.PlayerActionVerbs;
import com.niuqu.chatbubble.render.ChatBubbleTheme;
import com.niuqu.chatbubble.render.RoundRectRenderer;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * 玩家头像右键的「互动动作」二级菜单：8 个内置动作 + **末尾（最底下）**「自定义」。
 *
 * <p>视觉与其它二级界面完全一致（{@link RoundRectRenderer#panel} 外框 9f + 内缩 1px 的行高亮 5f，
 * 与设置菜单 / 表情 / 快捷聊天 / 消息右键菜单同一套调用），位置**以被右键的玩家头像为锚**：
 * 优先挂在头像右侧并与头像顶对齐，右侧/下方放不开就翻到左侧/向上展开，最后夹进消息区。
 *
 * <p>点击与绘制共用 {@link #rowAt(int, int)} 与打开时算好的矩形（{@link #x}/{@link #y}），
 * 不存在「画在这里、点在那里」的两个公式。打开/关闭动画由 Screen 侧的
 * {@code renderPopupWithAnim} 同款曲线驱动（ZOOM 以头像中心 {@link #pivotX}/{@link #pivotY}
 * 为支点，而不是屏幕中心）。
 */
public class PlayerActionMenu {
    /** 一行的固定高度（与消息右键菜单 CTX_ITEM_H 一致）。 */
    public static final int ROW_H = 18;
    /** 菜单宽度：8 个三字词条 + 「自定义」都放得下。 */
    public static final int W = 84;
    /** 面板上下内边距（整块圆角面板，不是每行一个药丸）。 */
    public static final int PAD_Y = 2;
    /** 行数 = 8 个内置动作 + 「自定义」。 */
    public static final int ROWS = PlayerActionVerbs.count() + 1;

    public boolean visible;

    /** 菜单矩形（**屏幕坐标**，打开时算好；面板滑动/缩放期间不打开菜单，所以无需每帧重算）。 */
    public int x;
    public int y;
    public final int height = ROWS * ROW_H + PAD_Y * 2;

    /** 动画支点 = 头像中心（需求：以玩家头像为原点弹出）。 */
    public int pivotX;
    public int pivotY;

    /** 目标玩家（打开时从被右键的消息上取）。**关闭菜单不清空**：选「自定义」后
     *  卡片确认时还要用它组 payload，所以这两个字段的生命周期是「到下一次打开为止」。 */
    public String targetName = "";
    public java.util.UUID targetUUID;

    /** 打开前的布局计算，返回 {x, y}（屏幕坐标）。 */
    public static int[] layout(int avatarX, int avatarY, int avatarSize,
                               int panelX, int panelW, int msgTop, int msgBottom) {
        int h = ROWS * ROW_H + PAD_Y * 2;
        int minX = panelX + 2;
        int maxX = Math.max(minX, panelX + panelW - W - 2);
        // 优先贴在头像右侧（与被右键的头像同一水平带）；右侧放不开就翻到左侧。
        int px = avatarX + avatarSize + 4;
        if (px > maxX) px = avatarX - W - 4;
        px = Math.max(minX, Math.min(px, maxX));

        int minY = msgTop;
        int maxY = Math.max(minY, msgBottom - h);
        // 与头像顶对齐；底部越界时以头像底为基准向上展开，再夹进消息区。
        int py = avatarY;
        if (py + h > msgBottom) py = avatarY + avatarSize - h;
        py = Math.max(minY, Math.min(py, maxY));
        return new int[]{px, py};
    }

    public void open(int px, int py, int pivotX, int pivotY, String targetName, java.util.UUID targetUUID) {
        this.x = px;
        this.y = py;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
        this.targetName = targetName == null ? "" : targetName;
        this.targetUUID = targetUUID;
        this.visible = true;
    }

    /** 命中行号（0..7 = 内置动作，8 = 自定义）；未命中返回 -1。 */
    public int rowAt(int mouseX, int mouseY) {
        if (!visible) return -1;
        if (mouseX < x || mouseX > x + W) return -1;
        int top = y + PAD_Y;
        int bottom = top + ROWS * ROW_H;
        if (mouseY < top || mouseY >= bottom) return -1;
        int row = (mouseY - top) / ROW_H;
        return row >= 0 && row < ROWS ? row : -1;
    }

    /**
     * 画菜单。{@code customLabel} 由调用方给（走语言文件）；内置动作用
     * {@link PlayerActionVerbs#textAt(int)} 的固定中文词条（与消息正文同一份数据）。
     */
    public void render(DrawContext g, TextRenderer font, ChatBubbleTheme.Colors c,
                       String customLabel, int mouseX, int mouseY, float alpha) {
        if (!visible) return;
        int a255 = Math.max(0, Math.min(255, (int) (255 * alpha)));
        if (a255 <= 2) return;

        RoundRectRenderer.panel(g, x, y, W, height, 9f,
            ChatBubbleTheme.alphaBlend(c.popupBg(), a255),
            ChatBubbleTheme.alphaBlend(c.divider(), a255));

        for (int i = 0; i < ROWS; i++) {
            int rowY = y + PAD_Y + i * ROW_H;
            boolean hover = mouseX >= x && mouseX <= x + W
                && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hover) {
                RoundRectRenderer.fill(g, x + 1, rowY, x + W - 1, rowY + ROW_H, 5f,
                    ChatBubbleTheme.alphaBlend(c.popupHover(), a255));
            }
            String label = i < PlayerActionVerbs.count()
                ? PlayerActionVerbs.textAt(i)
                : (customLabel == null ? "" : customLabel);
            g.drawText(font, label, x + 10, rowY + (ROW_H - font.fontHeight) / 2 + 1,
                ChatBubbleTheme.alphaBlend(c.textPrimary(), a255), false);
        }
    }
}
