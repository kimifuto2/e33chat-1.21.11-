package com.niuqu.chatbubble.chat.group;

import com.niuqu.chatbubble.store.ChatMessageStore;
import com.niuqu.chatbubble.store.GroupStore;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 模态层：「群聊设置」（标题栏齿轮按钮打开）。
 *
 * <p><b>不再是独立 Screen</b>：见 {@link GroupModal} —— 聊天面板照常画在下面，
 * 卡片只在面板矩形内居中，所有输入优先给这里。
 *
 * <p>布局：
 * <pre>
 *   ┌──────────────────────────────────┐
 *   │           群名（大标题）           │
 *   │         群成员 N 人                │
 *   │ 群聊名称                          │   ← 明确的 label（第 1/4 轮：按钮与输入框留 4px）
 *   │ [ 当前群名（只读，点「修改」才可编辑） ] 4px [修改] │
 *   │ [ 搜索成员                 ] 4px [搜索] │   ← 第 4 轮新增：过滤成员列表
 *   │  头像 玩家名              [踢出]   │
 *   │  头像 玩家名（创建者）             │
 *   │     [添加玩家] [解散群聊] [完成]    │   ← 等距 8px、整体居中（第 2 轮）
 *   └──────────────────────────────────┘
 * </pre>
 *
 * <p>第 4 轮的三处调整：
 * <ol>
 *   <li><b>4px 间距</b>：群名行 / 搜索行都是「输入框右缘 → 4px → 按钮左缘」。之前是 0 间距，
 *       输入框与按钮各自 1px 描边贴在一起，看起来像"边框重叠"（{@link #FIELD_BTN_GAP}）。</li>
 *   <li><b>底部按钮等距居中</b>：按角色给出按钮集合（创建者 3 个 / 普通成员 2 个），
 *       统一 {@link #BOTTOM_GAP} 间距、整体在卡片内水平居中（{@link #computeFooterButtons}）。</li>
 *   <li><b>搜索成员</b>：成员列表上方一个搜索框 + 「搜索」按钮，过滤**当前群成员**（不区分
 *       大小写 contains，空 = 全部），与「添加玩家」页的搜索完全同一套绘制/交互（点按钮或
 *       回车生效，清空即恢复全部）。</li>
 * </ol>
 *
 * <p>角色权限（第 4 轮）：**创建者**才看得到「添加玩家」与「解散群聊」；普通成员只有
 * 「退出群聊 / 完成」。服务端同样只允许创建者邀请（{@code group_invite} 校验 sender==creator），
 * 界面上不画按钮只是第一层，绕过界面也拿不到越权能力。
 *
 * <p>群名区域是「先看后改」的两段式：默认**直接显示当前群名**（只读，不是占位符），
 * 右侧按钮是「修改」；点「修改」后输入框解锁、光标落到末尾，按钮变成「保存」，
 * 点「保存」（或回车）才提交 {@link GroupStore#rename}，随后回到只读态。
 */
public class GroupSettingsScreen extends GroupModal {
    private static final int TITLE_H = 32;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 2;
    private static final int BTN_H = 18;
    private static final int FIELD_H = 20;
    private static final int SAVE_W = 48;
    /** 搜索按钮宽度（与「添加玩家」页一致：同高 20、文字「搜索/搜索成员」放得下）。 */
    private static final int SEARCH_BTN_W = 40;
    /**
     * 输入框与右侧按钮之间的间距（第 4 轮）：0 会让两个圆角描边直接贴住、看起来像边框重叠。
     * 群名行与搜索行共用这一个常量，所以"所有同类布局"自动一致。
     */
    private static final int FIELD_BTN_GAP = 4;
    /** 底部按钮之间的统一间距（第 2 轮：三个按钮等距均分）。 */
    private static final int BOTTOM_GAP = 8;
    /** 底部按钮宽度：添加玩家 / 退出群聊 / 完成 = 74，解散群聊 = 82（中文字更宽）。 */
    private static final int BTN_W = 74;
    private static final int BTN_W_WIDE = 82;

    /** 底部按钮动作编号（footerBtns 里第 5 个元素）。 */
    private static final int ACT_ADD = 0;
    private static final int ACT_DISBAND_OR_LEAVE = 1;
    private static final int ACT_DONE = 2;

    private static final long CONFIRM_MS = 4000L;

    private final UUID groupId;

    private int listTop, listBottom, btnY, fieldY, fieldX, fieldW, saveX;
    private int searchY, searchBtnX, searchFieldW;
    private TextFieldWidget nameField;
    /** 第 4 轮：成员搜索框（「搜索」按钮 / 回车才应用，清空即恢复全部）。 */
    private TextFieldWidget searchField;
    /** 已生效的成员过滤词（空串 = 不过滤）。 */
    private String appliedQuery = "";
    /** 群名是否处于"可编辑"状态（默认 false = 只读显示当前群名）。 */
    private boolean editing;
    /** 已提交、等服务端回显的群名（乐观显示用；null = 没有在途改名）。 */
    private String pendingName;
    private long pendingAt;
    private int scrollRow;

    private boolean hoverEdit, hoverSearch;
    /** 每行「踢出」按钮命中矩形：[x, y, w, h, 过滤后成员下标]。 */
    private final List<int[]> kickRects = new ArrayList<>();
    /** 底部按钮命中矩形：[x, y, w, h, action]。渲染与点击共用同一份算式。 */
    private final List<int[]> footerBtns = new ArrayList<>();

    /** 解散两段确认的起始时刻（0 = 未进入确认态）。 */
    private long disbandConfirmAt;

    public GroupSettingsScreen(Host host, UUID groupId) {
        super(host);
        this.groupId = groupId;
        nameField = new TextFieldWidget(tr, 0, 0, 120, 12, Text.empty());
        nameField.setMaxLength(32);
        nameField.setDrawsBackground(false);
        nameField.setEditableColor(GroupUi.colors().textPrimary());
        // 只读态**直接显示当前群名**（不是占位符/空框），所以用正文色而不是 muted ——
        // 玩家一眼就能确认"这就是本群的名字"，配合左侧「群聊名称」label + 右侧「修改」按钮。
        nameField.setUneditableColor(GroupUi.colors().textPrimary());
        nameField.setFocusUnlocked(true);
        nameField.setEditable(false);

        // 成员搜索框（第 4 轮）：与「添加玩家」页的搜索框同款（无背景底、纯文字，底框自己画）
        searchField = new TextFieldWidget(tr, 0, 0, 120, 12, Text.empty());
        searchField.setMaxLength(32);
        searchField.setDrawsBackground(false);
        searchField.setEditableColor(GroupUi.colors().textPrimary());
        searchField.setUneditableColor(GroupUi.colors().textMuted());
        searchField.setFocusUnlocked(true);
        // 清空输入框 = 立刻恢复完整成员列表（否则删完字还得再点一次「搜索」才看得到全部）
        searchField.setChangedListener(s -> {
            if (s == null || s.isEmpty()) {
                if (!appliedQuery.isEmpty()) {
                    appliedQuery = "";
                    scrollRow = 0;
                }
            }
        });
    }

    @Override
    protected int preferredWidth() {
        return 280;
    }

    @Override
    protected int preferredHeight() {
        // 比上一轮高 20px：新增「搜索成员」一行（FIELD_H + 6），成员列表可见行数基本不变。
        return 310;
    }

    @Override
    protected void layout() {
        // 群名一行：label 在输入框上方，输入框与「修改/保存」按钮**同一行、同高，间隔 4px**
        fieldY = cardY + TITLE_H + 18;
        saveX = cardX + cardW - 8 - SAVE_W;
        fieldX = cardX + 8;
        fieldW = Math.max(40, saveX - FIELD_BTN_GAP - fieldX);
        nameField.setX(fieldX + 4);
        // 底框高 20：文字行 y = 框顶 + (20 - 9 + 1) / 2 = +6（只读态与编辑态同一套几何，
        // 以前写死 +4 → 文字贴框顶、下方留白多 3px）
        GroupUi.centerFieldText(nameField, tr, fieldY, FIELD_H);
        nameField.setWidth(Math.max(20, fieldW - 8));

        // 搜索成员一行：输入框占满左侧，搜索按钮同高、与输入框留 4px（与群名行同一个常量）
        searchY = fieldY + FIELD_H + 6;
        int rowX = cardX + 8;
        int rowRight = cardX + cardW - 8;
        searchBtnX = rowRight - SEARCH_BTN_W;
        searchFieldW = Math.max(20, searchBtnX - FIELD_BTN_GAP - rowX);
        searchField.setX(rowX + 4);
        // 与群名框同一条规则：框顶 + (FIELD_H - fontHeight + 1) / 2 = +6
        GroupUi.centerFieldText(searchField, tr, searchY, FIELD_H);
        searchField.setWidth(Math.max(20, searchFieldW - 8));

        listTop = searchY + FIELD_H + 6;
        btnY = cardY + cardH - BTN_H - 8;
        listBottom = btnY - 6;
        clampScroll();
    }

    @Override
    public void onOpened() {
        ensureLayout();
        editing = false;
        pendingName = null;
        scrollRow = 0;
        disbandConfirmAt = 0L;
        appliedQuery = "";
        if (!searchField.getText().isEmpty()) searchField.setText("");
        searchField.setFocused(false);
        syncNameField(true);
    }

    @Override
    public void onClosed() {
        editing = false;
        nameField.setFocused(false);
        searchField.setFocused(false);
        nameField.setEditable(false);
    }

    /** 只读态下把群名同步进输入框（服务端改名/首次打开都会走到）。
     *
     *  <p>「保存」发出后服务端要过一会儿才用 GroupSync 把新名字回传；这段时间里如果直接按
     *  服务端快照回填，玩家会看到自己刚改的名字**闪回旧名**。所以提交后先记下
     *  {@link #pendingName} 做乐观显示，等服务端回显同名（或 5 秒超时）再恢复成"跟服务端走"。 */
    private void syncNameField(boolean force) {
        GroupStore.Group g = GroupStore.byId(groupId);
        String current = g == null ? "" : (g.rawName() == null ? "" : g.rawName());
        if (pendingName != null) {
            if (current.equals(pendingName) || Util.getMeasuringTimeMs() - pendingAt > 5000L) {
                pendingName = null;          // 服务端已确认 / 超时（改名失败时允许回退显示真名）
            } else {
                if (!pendingName.equals(nameField.getText())) nameField.setText(pendingName);
                if (!editing) {
                    nameField.setEditable(false);
                    nameField.setFocused(false);
                }
                return;
            }
        }
        if (force || !current.equals(nameField.getText())) {
            // TextFieldWidget.setText 不比较新旧文本（无 equals 分支），因此这里自己先判等，
            // 免得每次渲染都触发一次 onChanged 无关副作用。
            if (!current.equals(nameField.getText())) nameField.setText(current);
        }
        if (!editing) {
            nameField.setEditable(false);
            nameField.setFocused(false);
        }
    }

    private void beginEdit() {
        GroupStore.Group g = GroupStore.byId(groupId);
        if (g == null) return;
        pendingName = null;
        editing = true;
        nameField.setEditable(true);
        nameField.setFocused(true);
        searchField.setFocused(false);
        nameField.setCursorToEnd(false);
        ChatMessageStore.debugLog("[e33chat] group settings | begin edit name | " + groupId);
    }

    /** 放弃编辑：输入框回到"显示当前群名"的只读态。 */
    private void cancelEdit() {
        editing = false;
        nameField.setFocused(false);
        nameField.setEditable(false);
        syncNameField(true);
    }

    /** 提交改名：只有编辑态才发，发完立刻回只读态（等服务端 GroupSync 刷新）。 */
    private void commitEdit() {
        if (!editing) return;
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        GroupStore.rename(groupId, name);
        ChatMessageStore.debugLog(() -> "[e33chat] group settings | rename submit | "
            + groupId + " | name='" + name + "'");
        // 乐观显示：服务端回显前先按玩家输入的名字显示，避免"刚保存就闪回旧名"
        pendingName = name;
        pendingAt = Util.getMeasuringTimeMs();
        editing = false;
        nameField.setFocused(false);
        nameField.setEditable(false);
    }

    /** 全部群成员（服务端快照，保持入群顺序）。 */
    private List<GroupStore.Member> members() {
        GroupStore.Group g = GroupStore.byId(groupId);
        return g == null ? List.of() : g.members();
    }

    /**
     * 按 {@link #appliedQuery} 过滤后的成员列表（第 4 轮）。
     *
     * <p>匹配规则：**不区分大小写 + 包含**；空词 = 全部。绘制、行高、滚动夹紧、踢出命中
     * 全部只认这一份列表，不会出现"显示的和点到的不一致"。
     */
    private List<GroupStore.Member> filteredMembers() {
        List<GroupStore.Member> all = members();
        String q = appliedQuery == null ? "" : appliedQuery.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return all;
        List<GroupStore.Member> hit = new ArrayList<>();
        for (GroupStore.Member m : all) {
            String name = m.name() == null ? "" : m.name().toLowerCase(Locale.ROOT);
            if (name.contains(q)) hit.add(m);
        }
        return hit;
    }

    /** 提交搜索：把输入框内容变成生效的过滤条件（点「搜索」按钮 / 搜索框回车）。 */
    private void applySearch() {
        appliedQuery = searchField.getText() == null ? "" : searchField.getText().trim();
        scrollRow = 0;
        clampScroll();
        ChatMessageStore.debugLog(() -> "[e33chat] group settings | member search '" + appliedQuery
            + "' | hits=" + filteredMembers().size() + "/" + members().size());
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / (ROW_H + ROW_GAP));
    }

    private void clampScroll() {
        int max = Math.max(0, filteredMembers().size() - visibleRows());
        scrollRow = MathHelper.clamp(scrollRow, 0, max);
    }

    private boolean confirmingDisband() {
        return disbandConfirmAt > 0 && Util.getMeasuringTimeMs() - disbandConfirmAt <= CONFIRM_MS;
    }

    /** 我是不是这个群的创建者（创建者能邀请/解散，其它成员只能退出）。 */
    private boolean isOwner(GroupStore.Group group) {
        UUID self = GroupStore.currentSelf();
        return group != null && self != null && self.equals(group.creator());
    }

    /**
     * 按角色算出底部按钮矩形（第 2 / 4 轮）：**等间距 8px、整体在卡片内水平居中**。
     *
     * <p>创建者 = 添加玩家 / 解散群聊 / 完成；普通成员 = 退出群聊 / 完成。
     * 渲染与点击都从 {@link #footerBtns} 取同一份矩形，不会再出现"画的位置和点的位置不一致"。
     */
    private void computeFooterButtons(boolean owner) {
        footerBtns.clear();
        int[] widths = owner ? new int[]{BTN_W, BTN_W_WIDE, BTN_W} : new int[]{BTN_W, BTN_W};
        int[] actions = owner
            ? new int[]{ACT_ADD, ACT_DISBAND_OR_LEAVE, ACT_DONE}
            : new int[]{ACT_DISBAND_OR_LEAVE, ACT_DONE};
        int total = BOTTOM_GAP * (widths.length - 1);
        for (int w : widths) total += w;
        int x = cardX + (cardW - total) / 2;
        for (int i = 0; i < widths.length; i++) {
            footerBtns.add(new int[]{x, btnY, widths[i], BTN_H, actions[i]});
            x += widths[i] + BOTTOM_GAP;
        }
    }

    /** 关掉本模态（回聊天面板；群已解散/被踢时 ChatBubbleScreen 会把视图切回公屏）。 */
    private void close() {
        host.closeGroupModal();
    }

    // ===== 绘制 =====

    @Override
    public void render(DrawContext g, int mouseX, int mouseY, float delta) {
        ensureLayout();

        GroupStore.Group group = GroupStore.byId(groupId);
        if (group == null) {
            // 群已解散 / 已被踢出：直接退回聊天面板（不需要玩家再点一次）
            ChatMessageStore.debugLog(() -> "[e33chat] group settings | group gone, closing | " + groupId);
            close();
            return;
        }

        dimHost(g);
        drawCard(g);
        var c = GroupUi.colors();

        // 只读态每帧跟服务端快照对齐（别人改了群名 / GroupSync 晚到 → 界面上立刻能看到）
        if (!editing) syncNameField(false);

        GroupUi.title(g, tr, Text.literal(group.displayName()), cardX + cardW / 2,
            cardY + 8, c.textPrimary());
        GroupUi.title(g, tr,
            Text.translatable("e33chat.group.settings.member_count", group.size()),
            cardX + cardW / 2, cardY + 20, c.textMuted());
        GroupUi.divider(g, cardX + 8, cardY + TITLE_H, cardW - 16);

        // ---- 群名区域：label + 只读输入框 + 「修改/保存」按钮（间隔 4px）----
        GroupUi.label(g, tr, Text.literal(GroupUi.ellipsize(tr,
                Text.translatable("e33chat.group.settings.name_label").getString(), cardW - 20)),
            fieldX + 2, fieldY - 12);
        GroupUi.fieldBox(g, fieldX, fieldY, fieldW, FIELD_H);
        if (!editing && nameField.getText().isEmpty()) {
            g.drawText(tr, Text.translatable("e33chat.group.settings.name_empty").getString(),
                nameField.getX() + 1, nameField.getY(), c.textMuted(), false);
        }
        nameField.render(g, mouseX, mouseY, delta);
        hoverEdit = hit(mouseX, mouseY, saveX, fieldY, SAVE_W, FIELD_H);
        GroupUi.button(g, tr, saveX, fieldY, SAVE_W, FIELD_H,
            Text.translatable(editing
                ? "e33chat.group.settings.save" : "e33chat.group.settings.edit"),
            hoverEdit, editing, false, true);

        // ---- 搜索成员行（第 4 轮）：输入框 + 同高「搜索」按钮，间隔 4px ----
        GroupUi.fieldBox(g, cardX + 8, searchY, searchFieldW, FIELD_H);
        if (searchField.getText().isEmpty()) {
            g.drawText(tr, Text.translatable("e33chat.group.settings.search_hint").getString(),
                searchField.getX() + 1, searchField.getY(), c.textMuted(), false);
        }
        searchField.render(g, mouseX, mouseY, delta);
        hoverSearch = hit(mouseX, mouseY, searchBtnX, searchY, SEARCH_BTN_W, FIELD_H);
        GroupUi.button(g, tr, searchBtnX, searchY, SEARCH_BTN_W, FIELD_H,
            Text.translatable("e33chat.group.search.button"), hoverSearch, false, false, true);

        // ---- 成员列表（按搜索词过滤后）----
        clampScroll();
        kickRects.clear();
        List<GroupStore.Member> members = members();
        List<GroupStore.Member> shown = filteredMembers();
        UUID self = GroupStore.currentSelf();
        int y = listTop;
        int end = Math.min(shown.size(), scrollRow + visibleRows());
        for (int i = scrollRow; i < end; i++) {
            GroupStore.Member m = shown.get(i);
            boolean hov = mouseX >= cardX + 4 && mouseX <= cardX + cardW - 4
                && mouseY >= y && mouseY <= y + ROW_H;
            if (hov) {
                GroupUi.rowHighlight(g, cardX + 4, y, cardW - 8, ROW_H);
            }
            GroupUi.head(g, m.uuid(), m.name(), cardX + 10, y + 2, 16);
            boolean isSelf = self != null && self.equals(m.uuid());
            boolean isCreator = group.creator() != null && group.creator().equals(m.uuid());
            String label = m.name() == null || m.name().isBlank()
                ? (m.uuid() == null ? "?" : m.uuid().toString().substring(0, 8)) : m.name();
            if (isSelf) label = label + Text.translatable("e33chat.group.settings.self_tag").getString();
            if (isCreator) label = label + Text.translatable("e33chat.group.settings.creator_tag").getString();
            int nameX = cardX + 32;
            int kickW = 40;
            g.drawText(tr,
                GroupUi.ellipsize(tr, label, cardX + cardW - 12 - kickW - 8 - nameX),
                nameX, y + (ROW_H - tr.fontHeight) / 2 + 1, c.textPrimary(), false);

            // 踢出：自己 / 创建者不可踢（服务端也会拒绝，这里只是不画按钮）
            if (!isSelf && !isCreator) {
                int kx = cardX + cardW - 12 - kickW;
                int ky = y + (ROW_H - 14) / 2;
                boolean kh = hit(mouseX, mouseY, kx, ky, kickW, 14);
                GroupUi.button(g, tr, kx, ky, kickW, 14,
                    Text.translatable("e33chat.group.settings.kick"), kh, false, true, true);
                kickRects.add(new int[]{kx, ky, kickW, 14, i});
            }
            y += ROW_H + ROW_GAP;
        }
        if (members.isEmpty()) {
            drawEmptyHint(g, Text.translatable("e33chat.group.settings.no_members"), listTop + 8);
        } else if (shown.isEmpty()) {
            // 有成员但被搜索词滤空 → 明确说"没有匹配的成员"，而不是让人以为群空了
            drawEmptyHint(g, Text.translatable("e33chat.group.settings.search_no_match"), listTop + 8);
        }

        // ---- 底部：角色区分 + 等距居中（第 2 / 4 轮）----
        boolean owner = isOwner(group);
        computeFooterButtons(owner);
        drawFooterLine(g, btnY);
        for (int[] b : footerBtns) {
            boolean hov = hit(mouseX, mouseY, b[0], b[1], b[2], b[3]);
            switch (b[4]) {
                case ACT_ADD -> GroupUi.button(g, tr, b[0], b[1], b[2], b[3],
                    Text.translatable("e33chat.group.settings.add_player"), hov, false, false, true);
                case ACT_DISBAND_OR_LEAVE -> GroupUi.button(g, tr, b[0], b[1], b[2], b[3],
                    Text.translatable(owner
                        ? (confirmingDisband() ? "e33chat.group.settings.disband_confirm"
                                               : "e33chat.group.settings.disband")
                        : "e33chat.group.settings.leave"),
                    hov, false, true, true);
                case ACT_DONE -> GroupUi.button(g, tr, b[0], b[1], b[2], b[3],
                    Text.translatable("e33chat.group.done"), hov, true, false, true);
                default -> { }
            }
        }
    }

    // ===== 输入 =====

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        ensureLayout();
        double mx = click.x();
        double my = click.y();
        if (click.button() == 0) {
            if (hit(mx, my, saveX, fieldY, SAVE_W, FIELD_H)) {
                if (editing) commitEdit();
                else beginEdit();
                return true;
            }
            if (hit(mx, my, searchBtnX, searchY, SEARCH_BTN_W, FIELD_H)) {
                // 「搜索」按钮：应用过滤，但不抢搜索框的焦点（方便继续改词再搜）
                applySearch();
                return true;
            }
            // 两个输入框各自可聚焦（模态层拦截所有键盘事件后再转给聚焦的那个）
            if (editing && nameField.mouseClicked(click, doubleClick)) {
                nameField.setFocused(true);
                searchField.setFocused(false);
                return true;
            }
            if (searchField.mouseClicked(click, doubleClick)) {
                searchField.setFocused(true);
                if (editing) {
                    editing = false;
                    nameField.setEditable(false);
                    syncNameField(true);
                }
                nameField.setFocused(false);
                return true;
            }
            // 底部按钮：与绘制同一份矩形表（角色不同，按钮数量/位置都不同）
            GroupStore.Group snapshot = GroupStore.byId(groupId);
            boolean owner = isOwner(snapshot);
            computeFooterButtons(owner);
            for (int[] b : footerBtns) {
                if (!hit(mx, my, b[0], b[1], b[2], b[3])) continue;
                switch (b[4]) {
                    case ACT_ADD -> {
                        // 邀请页以"模态里的模态"打开，关掉它回到本设置页。
                        // 只有创建者能看到这个按钮（普通成员没有 footerBtns 的 ACT_ADD）。
                        host.openGroupModal(new GroupCreateScreen(host, groupId, this));
                        return true;
                    }
                    case ACT_DONE -> {
                        close();
                        return true;
                    }
                    default -> {
                        if (snapshot == null) { close(); return true; }
                        if (!owner) {
                            // 普通成员：退出群聊（服务端 group_leave，其余成员会收到新的 GroupSync）
                            GroupStore.leave(groupId);
                            ChatMessageStore.debugLog(() -> "[e33chat] group leave requested | " + groupId);
                            close();
                            return true;
                        }
                        if (confirmingDisband()) {
                            GroupStore.disband(groupId);
                            disbandConfirmAt = 0L;
                            ChatMessageStore.debugLog(() -> "[e33chat] group disband requested | " + groupId);
                            close();
                        } else {
                            disbandConfirmAt = Util.getMeasuringTimeMs();
                        }
                        return true;
                    }
                }
            }
            for (int[] r : kickRects) {
                if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                    List<GroupStore.Member> shown = filteredMembers();
                    if (r[4] < 0 || r[4] >= shown.size()) return true;
                    GroupStore.Member m = shown.get(r[4]);
                    GroupStore.kick(groupId, m.uuid());
                    ChatMessageStore.debugLog(() -> "[e33chat] group kick requested | "
                        + groupId + " | target=" + m.uuid());
                    return true;
                }
            }
            if (!insideCard(mx, my)) { close(); return true; }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        nameField.mouseReleased(click);
        searchField.mouseReleased(click);
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double dragX, double dragY) {
        if (editing && nameField.isFocused()) nameField.mouseDragged(click, dragX, dragY);
        if (searchField.isFocused()) searchField.mouseDragged(click, dragX, dragY);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount,
                                 double verticalAmount) {
        ensureLayout();
        if (mouseX >= cardX && mouseX <= cardX + cardW && mouseY >= listTop && mouseY <= listBottom) {
            scrollRow -= (int) Math.signum(verticalAmount);
            clampScroll();
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (editing && nameField.isFocused() && nameField.keyPressed(input)) return true;
        if (searchField.isFocused() && searchField.keyPressed(input)) return true;
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            // 编辑中按 ESC = 放弃这次改名（仍留在设置页）；只读态按 ESC = 关模态（不关聊天面板）
            if (editing) cancelEdit();
            else if (searchField.isFocused()) searchField.setFocused(false);
            else close();
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
            || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            if (editing) commitEdit();
            else if (searchField.isFocused()) applySearch();
            return true;
        }
        return true;
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (editing && nameField.isFocused()) nameField.charTyped(input);
        else if (searchField.isFocused()) searchField.charTyped(input);
        return true;
    }
}
