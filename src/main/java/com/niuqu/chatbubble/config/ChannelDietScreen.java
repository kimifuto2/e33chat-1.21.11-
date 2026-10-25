package com.niuqu.chatbubble.config;

import com.mojang.logging.LogUtils;
import com.niuqu.chatbubble.ChatBubbleClientSetup;
import com.niuqu.chatbubble.channeldiet.ChannelDietFilter;
import com.niuqu.chatbubble.channeldiet.ChannelDietNames;
import com.niuqu.chatbubble.channeldiet.ChannelDietStats;
import com.niuqu.chatbubble.render.Appearance;
import com.niuqu.chatbubble.render.ChatBubbleTheme;
import com.niuqu.chatbubble.render.RoundRectRenderer;
import com.niuqu.chatbubble.render.SmoothScrollPane;
import com.niuqu.chatbubble.texture.ColoredTextureRenderer;
import com.niuqu.chatbubble.texture.UiElement;
import com.niuqu.chatbubble.texture.UiTextureManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 「通道节食设置」二级界面（配置屏：高级 → 网络与服务端 → 通道节食设置…）。
 *
 * <p>界面分三块：
 * <ol>
 *   <li><b>顶部</b>：{@code 当前声明通道：N / 120} —— N 超过上限时红色（{@code 0xFFFF5555}），
 *       否则绿色（{@code 0xFF55FF55}）；下面两行小字是说明与统计来源（上次连接的实测值，
 *       或未连接时用客户端全局接收器估算的预览值）。</li>
 *   <li><b>中段</b>：可滚动列表，一行一个命名空间/模组：显示名（中文优先）在左，
 *       {@code +N} 通道数与开关在右。点整行即可切换「是否声明这个模组的通道」，
 *       顶部数字与颜色立刻变化，并且**立即写盘**（{@link ChatBubbleClientSetup#saveConfig}）。</li>
 *   <li><b>底部</b>：全部启用、完成、上限 {@code −/+}，以及一行提示
 *       「总开关在上一级页面 / 改动下次连接生效」。</li>
 * </ol>
 *
 * <p><b>3.0.3 起没有三态模式</b>（off/仅统计/生效）：是否裁剪只由上一级页面的
 * 「通道节食（实验性）」开关决定，本界面不再有自己的模式按钮。开关关闭时本界面仍是
 * 只统计数据源，数字与勾选效果照常变化，但发送时原样放行。
 *
 * <p>e33chat 自己的命名空间固定启用、不可关闭（行上标注「必需」并把开关置灰）。
 * 列表排序：必需项最前，然后能映射到真实模组的按通道数从多到少，最后是映射不到模组的
 * 系统/未知命名空间（按通道数从多到少）——用户先看到"最占地方"的模组。
 *
 * <p>统计来源：{@link ChannelDietStats#last()}（每次连服务器构造注册包时记录）。
 * 还没连过服务器时退回 {@link ClientPlayNetworking#getGlobalReceivers()} 估算，
 * 界面上明确标注"尚未连接"，不把估算值伪装成实测值。
 */
public class ChannelDietScreen extends Screen {
    private static final int MARGIN = 24;
    private static final int HEAD_Y = 36;
    private static final int LIST_TOP = 92;
    private static final int ROW_H = 26;
    private static final int ROW_GAP = 4;
    private static final int SCROLLBAR_W = 6;
    private static final int SWITCH_W = 28;
    private static final int SWITCH_H = 14;
    private static final int FOOTER_H = 56;
    /** 超限红 / 达标绿（需求指定的两种状态色）。 */
    private static final int COLOR_OVER = 0xFFFF5555;
    private static final int COLOR_OK = 0xFF55FF55;
    private static final int COLOR_LOCKED = 0xFF6B6B6B;

    private final Screen lastScreen;
    private final Consumer<List<String>> disabledSink;
    private final Consumer<Integer> limitSink;

    private final SmoothScrollPane pane = new SmoothScrollPane();
    private final List<Row> rows = new ArrayList<>();

    private ChannelDietStats.Snapshot snapshot;
    private boolean measured;
    private int limit = ChannelDietFilter.LIMIT_DEFAULT;
    private final List<String> disabled = new ArrayList<>();

    /** 列表行：命名空间、通道数、显示名。 */
    private record Row(String namespace, int count, String display) {}

    public ChannelDietScreen(Screen lastScreen,
                             Consumer<List<String>> disabledSink, Consumer<Integer> limitSink) {
        super(Text.translatable("e33chat.channeldiet.title"));
        this.lastScreen = lastScreen;
        this.disabledSink = disabledSink;
        this.limitSink = limitSink;
    }

    /**
     * 配色快照缓存：列表每行要问好几次颜色（≈40 行 × 7 次/帧），
     * {@code ChatBubbleTheme.resolve} 会新建 42 字段的 record，不能每帧解析几百次。
     * 配置记录是不可变的，任何保存都会换成新实例 → 用实例身份做失效判断即可。
     */
    private ChatBubbleTheme.Colors cachedColors;
    private ChatBubbleConfig cachedColorsFor;

    private ChatBubbleTheme.Colors c() {
        ChatBubbleConfig cfg = ChatBubbleClientSetup.config();
        if (cachedColors == null || cachedColorsFor != cfg) {
            cachedColors = Appearance.snapshot();
            cachedColorsFor = cfg;
        }
        return cachedColors;
    }

    // ===== 几何 =====

    private int listBottom() { return height - FOOTER_H; }
    private int listHeight() { return Math.max(0, listBottom() - LIST_TOP); }
    private int contentHeight() {
        return rows.isEmpty() ? 0 : rows.size() * (ROW_H + ROW_GAP) - ROW_GAP;
    }
    private int maxScroll() { return Math.max(0, contentHeight() - listHeight()); }
    private int trackX() { return width - MARGIN - SCROLLBAR_W; }
    private int rowRight() { return width - MARGIN - SCROLLBAR_W - 6; }

    // ===== 生命周期 =====

    @Override
    protected void init() {
        ChatBubbleConfig cfg = ChatBubbleClientSetup.config();
        limit = cfg.channelDietLimitResolved();
        disabled.clear();
        disabled.addAll(cfg.channelDietDisabledResolved());
        rebuildRows();
        pane.setOffset(MathHelper.clamp(pane.offset(), 0, maxScroll()));

        addDrawableChild(ButtonWidget.builder(Text.translatable("e33chat.channeldiet.enable_all"), b -> enableAll())
            .dimensions(MARGIN, height - 40, 120, 20).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> doClose())
            .dimensions(width / 2 - 100, height - 40, 200, 20).build());
        // 上限微调（步进 5，范围 32~127）：与顶部 N / 上限 联动
        addDrawableChild(ButtonWidget.builder(Text.literal("\u2212"), b -> changeLimit(-5))
            .dimensions(width - MARGIN - 42, height - 40, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> changeLimit(5))
            .dimensions(width - MARGIN - 20, height - 40, 20, 20).build());
    }

    /** 从统计快照生成行（排序 + 显示名解析都在这里做一次，渲染时不再遍历模组表）。 */
    private void rebuildRows() {
        rows.clear();
        snapshot = ChannelDietStats.last();
        measured = snapshot != null;
        if (snapshot == null) snapshot = livePreview();
        List<ChannelDietStats.NamespaceCount> list = new ArrayList<>(snapshot.namespaces());
        list.sort(Comparator
            .comparingInt((ChannelDietStats.NamespaceCount nc) -> rank(nc.namespace()))
            .thenComparing(Comparator.comparingInt(ChannelDietStats.NamespaceCount::count).reversed())
            .thenComparing(ChannelDietStats.NamespaceCount::namespace));
        for (ChannelDietStats.NamespaceCount nc : list) {
            rows.add(new Row(nc.namespace(), nc.count(), ChannelDietNames.display(nc.namespace())));
        }
    }

    /** 必需项 0、能映射到模组 1、系统/未知 2（排最后）。 */
    private static int rank(String namespace) {
        if (ChannelDietFilter.REQUIRED_NAMESPACE.equals(namespace)) return 0;
        return ChannelDietNames.isKnownMod(namespace) ? 1 : 2;
    }

    /** 未连接过服务器时的估算：Fabric 配置阶段发的正是这批全局接收器。 */
    private ChannelDietStats.Snapshot livePreview() {
        try {
            return ChannelDietStats.preview(ClientPlayNetworking.getGlobalReceivers(), limit, disabled);
        } catch (Throwable t) {
            return ChannelDietStats.empty(limit, disabled);
        }
    }

    // ===== 状态读写（改一下就立即写盘，下次连接生效） =====

    private boolean isDisabled(String namespace) {
        return !ChannelDietFilter.REQUIRED_NAMESPACE.equals(namespace) && disabled.contains(namespace);
    }

    /** 顶部「当前声明通道」数字 = 所有未被禁用命名空间的通道数之和（e33chat 永远计入）。 */
    private int declaredTotal() {
        int n = 0;
        for (Row row : rows) {
            if (!isDisabled(row.namespace())) n += row.count();
        }
        return n;
    }

    private void toggle(String namespace) {
        if (ChannelDietFilter.REQUIRED_NAMESPACE.equals(namespace)) return;   // 必需项不可关
        if (!disabled.remove(namespace)) disabled.add(namespace);
        saveDisabled();
    }

    private void enableAll() {
        if (disabled.isEmpty()) return;
        disabled.clear();
        saveDisabled();
    }

    private void saveDisabled() {
        List<String> next = ChannelDietFilter.normalizeNamespaces(disabled);
        disabled.clear();
        disabled.addAll(next);
        disabledSink.accept(next);
        ChatBubbleClientSetup.saveConfig(
            ChatBubbleClientSetup.config().withChannelDietDisabledNamespaces(next));
    }

    /** 上限微调（步进 5，夹在 32~127）：立即写盘，顶部 N / 上限 与红绿立刻跟着变。 */
    private void changeLimit(int delta) {
        int next = MathHelper.clamp(limit + delta, ChannelDietFilter.LIMIT_MIN, ChannelDietFilter.LIMIT_MAX);
        if (next == limit) return;
        limit = next;
        limitSink.accept(next);
        ChatBubbleClientSetup.saveConfig(ChatBubbleClientSetup.config().withChannelDietLimit(next));
    }

    /** 顶部统计来源：上次真实连接（数字 + 服务端 brand），或未连接时的估算值。 */
    private Text statsLine() {
        if (measured) {
            String brand = snapshot.brand() == null || snapshot.brand().isBlank()
                ? Text.translatable("e33chat.channeldiet.brand_unknown").getString()
                : snapshot.brand().trim();
            return Text.translatable("e33chat.channeldiet.stats", snapshot.total(), brand);
        }
        return Text.translatable("e33chat.channeldiet.stats_preview", snapshot.total());
    }

    // ===== 渲染 =====

    @Override
    public void render(DrawContext g, int mouseX, int mouseY, float partialTick) {
        // CONFIG_BG 烘焙为 75% 不透明，走带 alpha 顶点的绘制恢复半透明（同配置屏）
        ColoredTextureRenderer.drawWithAlpha(g,
            UiTextureManager.rl(UiElement.CONFIG_BG, Appearance.resolvedTheme()),
            0, 0, width, height, 0xC0 / 255f);
        pane.tick(maxScroll());

        g.drawText(textRenderer, title, width / 2 - textRenderer.getWidth(title) / 2, 14, c().configTitle(), false);

        // 顶部：当前声明通道 N / 上限 —— 超限红、达标绿
        int declared = declaredTotal();
        Text headline = Text.translatable("e33chat.channeldiet.current", declared, limit);
        g.drawText(textRenderer, headline, MARGIN, HEAD_Y, declared > limit ? COLOR_OVER : COLOR_OK, false);
        g.drawText(textRenderer, Text.translatable("e33chat.channeldiet.hint"),
            MARGIN, HEAD_Y + 16, c().textMuted(), false);
        g.drawText(textRenderer, statsLine(), MARGIN, HEAD_Y + 30, c().textSecondary(), false);
        // 底部一行提示：总开关在上一级页面（本界面没有模式按钮了），改动下次连接生效。
        // 窄窗口下按可用宽度截断，避免压到左侧「全部启用」与中间「完成」。
        String footer = Text.translatable("e33chat.channeldiet.footer").getString();
        g.drawText(textRenderer, Text.literal(truncate(footer, width - 2 * MARGIN)),
            MARGIN, listBottom() + 6, c().textMuted(), false);
        // 右下角：上限标签紧贴 − / + 两个按钮（按钮在 init 里创建）；窄窗口下省略避免压到「完成」
        if (width >= 420) {
            Text limitLabel = Text.translatable("e33chat.channeldiet.limit", limit);
            g.drawText(textRenderer, limitLabel,
                width - MARGIN - 48 - textRenderer.getWidth(limitLabel), height - 34, c().textSecondary(), false);
        }

        drawDivider(g, MARGIN, width - MARGIN, LIST_TOP - 6);

        // 列表（硬裁剪到视口）
        int viewBottom = listBottom();
        g.enableScissor(MARGIN - 2, LIST_TOP, width - MARGIN + 2, viewBottom);
        int y = LIST_TOP - pane.offset();
        for (Row row : rows) {
            if (y + ROW_H > LIST_TOP && y < viewBottom) {
                drawRow(g, row, y, mouseX, mouseY);
            }
            y += ROW_H + ROW_GAP;
        }
        if (rows.isEmpty()) {
            g.drawText(textRenderer, Text.translatable("e33chat.channeldiet.empty"),
                MARGIN + 4, LIST_TOP + 6, c().textMuted(), false);
        }
        g.disableScissor();

        drawScrollbar(g, mouseX, mouseY);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawRow(DrawContext g, Row row, int y, int mouseX, int mouseY) {
        int x1 = MARGIN;
        int x2 = rowRight();
        boolean required = ChannelDietFilter.REQUIRED_NAMESPACE.equals(row.namespace());
        boolean disabledRow = !required && isDisabled(row.namespace());
        boolean hover = mouseX >= x1 && mouseX < x2 && mouseY >= y && mouseY < y + ROW_H;

        RoundRectRenderer.fill(g, x1, y, x2, y + ROW_H, 4, hover ? c().sidebarItemHover() : c().inputBg());
        if (required) {
            // 必需项左侧强调条（强调色，强制不透明）
            RoundRectRenderer.fill(g, x1, y, x1 + 2, y + ROW_H, 2, opaque(c().configSection()));
        }

        int textColor = required ? c().configTitle() : (disabledRow ? c().textMuted() : c().textPrimary());
        int switchX = x2 - 8 - SWITCH_W;
        int labelX = x1 + 10;
        int labelRight = switchX - 64;
        g.drawText(textRenderer, Text.literal(truncate(row.display(), Math.max(20, labelRight - labelX))),
            labelX, y + (ROW_H - 8) / 2, textColor, false);

        // 右侧：+N [必需] [开关]
        String count = "+" + row.count();
        int countW = textRenderer.getWidth(count);
        int countRight = switchX - 10;
        if (required) {
            Text need = Text.translatable("e33chat.channeldiet.required");
            int needW = textRenderer.getWidth(need);
            g.drawText(textRenderer, need, countRight - needW, y + (ROW_H - 8) / 2, c().textMuted(), false);
            countRight -= needW + 8;
        }
        g.drawText(textRenderer, Text.literal(count), countRight - countW, y + (ROW_H - 8) / 2,
            disabledRow ? c().textMuted() : c().textSecondary(), false);

        drawSwitch(g, switchX, y + (ROW_H - SWITCH_H) / 2, !disabledRow, required);
    }

    /**
     * 开关（圆角轨道 + 圆点滑块）：开 = 强调色 + 滑块靠右；关 = 灰色 + 滑块靠左；
     * 必需项统一灰色（"置灰"）。
     */
    private void drawSwitch(DrawContext g, int x, int y, boolean on, boolean locked) {
        int radius = SWITCH_H / 2;
        int track = locked ? COLOR_LOCKED : (on ? opaque(c().configSection()) : opaque(c().scrollbar()));
        RoundRectRenderer.fill(g, x, y, x + SWITCH_W, y + SWITCH_H, radius, track);
        int knobSize = SWITCH_H - 4;
        // 滑块位置只跟开关状态走：必需项是「开但锁定」，滑块仍然靠右，只是轨道变灰
        int knobX = on ? x + SWITCH_W - 2 - knobSize : x + 2;
        RoundRectRenderer.fill(g, knobX, y + 2, knobX + knobSize, y + 2 + knobSize, knobSize / 2, 0xFFFFFFFF);
    }

    private void drawScrollbar(DrawContext g, int mouseX, int mouseY) {
        int max = maxScroll();
        if (max <= 0) return;
        int top = LIST_TOP;
        int trackH = listHeight();
        int totalH = max + trackH;
        int thumbH = Math.max(8, (int) ((long) trackH * trackH / totalH));
        int thumbY = top + (int) ((long) (trackH - thumbH) * pane.offset() / max);
        boolean hovering = mouseX >= trackX() && mouseX < trackX() + SCROLLBAR_W
            && mouseY >= thumbY && mouseY < thumbY + thumbH;
        ColoredTextureRenderer.drawWithAlpha(g,
            UiTextureManager.rl(UiElement.SCROLLBAR_TRACK, Appearance.resolvedTheme()),
            trackX(), top, SCROLLBAR_W, trackH, 0x40 / 255f);
        int alpha = pane.dragging() ? 0xCC : (hovering ? 0xAA : 0x88);
        ColoredTextureRenderer.drawWithAlpha(g,
            UiTextureManager.rl(UiElement.SCROLLBAR_THUMB, Appearance.resolvedTheme()),
            trackX(), thumbY, SCROLLBAR_W, thumbH, alpha / 255f);
    }

    private void drawDivider(DrawContext g, int x1, int x2, int y) {
        if (x2 <= x1) return;
        g.drawTexture(RenderPipelines.GUI_TEXTURED,
            UiTextureManager.rl(UiElement.DIVIDER, Appearance.resolvedTheme()),
            x1, y, 0f, 0f, x2 - x1, 1, 16, 16, 16, 16);
    }

    /** 主题色里强调色系字段的 alpha 可能是 0（Accent.rgb 不带 alpha），fill 前强制不透明。 */
    private static int opaque(int argb) {
        return 0xFF000000 | (argb & 0x00FFFFFF);
    }

    private String truncate(String s, int maxWidth) {
        if (s == null) return "";
        if (textRenderer.getWidth(s) <= maxWidth) return s;
        return textRenderer.trimToWidth(s, Math.max(6, maxWidth - 6)) + "…";
    }

    @Override
    public void renderBackground(DrawContext g, int mouseX, int mouseY, float partialTick) {
        // no-op：背景已在 render() 开头画（同配置屏，避免叠暗文字）
    }

    @Override
    public boolean shouldPause() { return true; }

    // ===== 输入 =====

    private int rowIndexAt(double mouseX, double mouseY) {
        if (mouseX < MARGIN || mouseX >= rowRight()) return -1;
        if (mouseY < LIST_TOP || mouseY >= listBottom()) return -1;
        int y = LIST_TOP - pane.offset();
        for (int i = 0; i < rows.size(); i++) {
            if (mouseY >= y && mouseY < y + ROW_H) return i;
            y += ROW_H + ROW_GAP;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        double mouseX = click.x();
        double mouseY = click.y();
        int max = maxScroll();
        if (max > 0 && mouseX >= trackX() && mouseX < trackX() + SCROLLBAR_W
                && mouseY >= LIST_TOP && mouseY < listBottom()) {
            int trackH = listHeight();
            int totalH = max + trackH;
            int thumbH = Math.max(8, (int) ((long) trackH * trackH / totalH));
            int thumbY = LIST_TOP + (int) ((long) (trackH - thumbH) * pane.offset() / max);
            if (mouseY < thumbY) pane.animateTo(pane.offset() - trackH, max, 120);
            else if (mouseY > thumbY + thumbH) pane.animateTo(pane.offset() + trackH, max, 120);
            else pane.dragStart((int) mouseY, pane.offset());
            return true;
        }
        if (click.button() == 0) {
            int idx = rowIndexAt(mouseX, mouseY);
            if (idx >= 0) {
                toggle(rows.get(idx).namespace());
                return true;
            }
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() <= 0) return false;
        pane.wheel(scrollY, maxScroll(), 120);
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (pane.dragging()) {
            int trackH = listHeight();
            int max = maxScroll();
            int totalH = max + trackH;
            // 与配置屏一致：按滑块行程换算偏移，80ms 平滑
            pane.dragTo((int) click.y(), trackH, totalH, max, 80);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        pane.dragEnd();
        return super.mouseReleased(click);
    }

    // ===== 关闭 =====

    private void doClose() {
        // 每次改动都已写盘；这里补一行日志，告诉用户"下次连接生效"。
        // 是否真正裁剪由上一级页面的总开关决定（本界面只写禁用名单 / 上限）。
        LogUtils.getLogger().info("[e33chat] channel diet settings | enabled="
            + Boolean.TRUE.equals(ChatBubbleClientSetup.config().channelDietEnabled())
            + " | limit=" + limit + " | disabled=" + disabled + " | applies on next join");
        if (client != null) client.setScreen(lastScreen);
    }

    @Override
    public void close() { doClose(); }
}
