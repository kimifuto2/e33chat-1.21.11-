package com.niuqu.chatbubble.chat.group;

import com.niuqu.chatbubble.store.ChatMessageStore;
import com.niuqu.chatbubble.store.GroupStore;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 模态层：「添加新群聊」（以及复用的「添加玩家」勾选页）。
 *
 * <p><b>不再是独立 Screen</b>：由 {@link GroupModal} 提供"卡片在聊天面板内居中 + 输入优先"
 * 的骨架，聊天面板照常画在下面（侧边栏永不消失，也不被覆盖）。
 *
 * <p>布局（自上而下，全部在居中卡片内）：
 * <pre>
 *   ┌──────────────────────────────┐
 *   │          添加新群聊           │   ← 标题居中
 *   │  [ 群聊名称（可留空…）      ] │   ← EditBox（仅建群模式）
 *   │  [ 搜索玩家           ] 4px [搜索] │   ← 「搜索玩家」框（两种模式都有）+ 同高、间隔 4px 的搜索按钮
 *   │  ☑ 头像 玩家名               │   ← 可滚动的在线玩家勾选列表（按搜索词过滤后）
 *   │  ☐ 头像 玩家名               │
 *   │  [取消]              [完成]   │
 *   └──────────────────────────────┘
 * </pre>
 *
 * <p>第 3 轮微调：两个输入框的**语义必须一眼分得开** —— 上方是「群名」（placeholder 就写着
 * 群聊名称），下方是「搜索玩家」（placeholder + 右侧同高的「搜索」按钮）。搜索**不是**逐字
 * 实时过滤，而是点「搜索」或按回车才生效（{@link #appliedQuery}），这样边打字边跳变的问题
 * 不存在，玩家也能把搜索框当普通输入框编辑。
 *
 * <p>「完成」：建群模式 → {@link GroupStore#create}（服务端建群后广播 GroupSync；被勾选的人
 * **不再直接进群**，而是各收一条「进群邀请」，见 {@link GroupInvites}）→ 邀请模式 →
 * {@link GroupStore#invite}。两种模式都在发起者侧于「我 ↔ 被邀请者」的私聊里留一条
 * 「已邀请 X 加入群聊【Y】」的本地回执，对方接受/拒绝后的回执也落在同一个会话。
 */
public class GroupCreateScreen extends GroupModal {
    private static final int TITLE_H = 22;
    private static final int ROW_H = 18;
    private static final int ROW_GAP = 2;
    private static final int FIELD_H = 20;
    private static final int BTN_H = 18;
    private static final int BTN_W = 70;
    /** 搜索按钮宽度：与输入框同高（{@link #FIELD_H}），文字「搜索」/「Search」都放得下。 */
    private static final int SEARCH_BTN_W = 40;
    /**
     * 输入框与右侧按钮之间的间距（第 4 轮）：之前是 0（输入框右缘与按钮左缘重合，两条
     * 1px 描边叠成 2px 粗线，看起来像"边框重叠"）。统一留 4px，群名行 / 搜索行共用同一常量。
     */
    private static final int FIELD_BTN_GAP = 4;

    /** null = 建群模式；非 null = 给该群「添加玩家」模式。 */
    private final UUID inviteGroupId;
    /** 邀请模式的"返回处"：关掉本模态后重新打开群聊设置模态（null = 直接回聊天面板）。 */
    private final GroupModal returnTo;

    private int listTop, listBottom, btnY, fieldY, searchY, searchBtnX, searchFieldW;

    private TextFieldWidget nameField;
    /** 「搜索玩家」输入框（两种模式都有；点「搜索」或回车才把内容应用到 {@link #appliedQuery}）。 */
    private TextFieldWidget searchField;
    /** 已生效的搜索词（空串 = 不过滤）。输入框里正在编辑、但还没提交的内容不影响列表。 */
    private String appliedQuery = "";
    private final Set<UUID> selected = new LinkedHashSet<>();
    /** 全部候选玩家（已按"邀请模式排除群内成员"筛过），搜索只在它上面做包含匹配。 */
    private List<GroupStore.Member> allPlayers = List.of();
    /** 当前**显示**的玩家（= allPlayers 按 appliedQuery 过滤后的结果）。行高/点击/空态都认它。 */
    private List<GroupStore.Member> players = List.of();
    private int scrollRow;

    private boolean hoverCancel, hoverDone, hoverSearch;
    /** 每行的命中矩形（渲染时回填，点击时用）：[x, y, w, h, memberIndex]。 */
    private final List<int[]> rowRects = new ArrayList<>();

    public GroupCreateScreen(Host host) {
        this(host, null, null);
    }

    public GroupCreateScreen(Host host, UUID inviteGroupId, GroupModal returnTo) {
        super(host);
        this.inviteGroupId = inviteGroupId;
        this.returnTo = returnTo;
        if (!isInviteMode()) {
            // 位置在 layout() 里按卡片矩形排（这里只创建，坐标先给 0）
            nameField = new TextFieldWidget(tr, 0, 0, 120, 12, Text.empty());
            nameField.setMaxLength(32);
            nameField.setDrawsBackground(false);
            nameField.setEditableColor(GroupUi.colors().textPrimary());
            nameField.setUneditableColor(GroupUi.colors().textMuted());
            nameField.setFocusUnlocked(true);
        }
        // 搜索框：两种模式都有（「添加玩家」页复用的就是本类）
        searchField = new TextFieldWidget(tr, 0, 0, 120, 12, Text.empty());
        searchField.setMaxLength(32);
        searchField.setDrawsBackground(false);
        searchField.setEditableColor(GroupUi.colors().textPrimary());
        searchField.setUneditableColor(GroupUi.colors().textMuted());
        searchField.setFocusUnlocked(true);
    }

    private boolean isInviteMode() {
        return inviteGroupId != null;
    }

    @Override
    protected int preferredWidth() {
        return 250;
    }

    @Override
    protected int preferredHeight() {
        // 比上一轮高 26px：多出来的就是「搜索玩家」那一行（FIELD_H + 6），
        // 其余行距不动，所以列表可见高度在屏幕上基本与原来一致。
        return 276;
    }

    @Override
    protected void layout() {
        int y = cardY + TITLE_H;
        if (!isInviteMode() && nameField != null) {
            fieldY = y;
            // 输入框底高 20：文字顶在 boxY+4，视觉居中（TextFieldWidget 无背景时文字 y = 自身 y）
            nameField.setX(cardX + 12);
            nameField.setY(fieldY + 4);
            nameField.setWidth(Math.max(20, cardW - 24));
            y += FIELD_H + 4;
        }
        // 「搜索玩家」行：输入框占满左侧，搜索按钮**同高（20）+ 与输入框留 4px 间距**
        // （第 4 轮：0 间距时两个 1px 描边贴在一起像"边框重叠"，右移按钮并相应收窄输入框）
        searchY = y;
        int rowX = cardX + 8;
        int rowRight = cardX + cardW - 8;
        searchBtnX = rowRight - SEARCH_BTN_W;
        searchFieldW = Math.max(20, searchBtnX - FIELD_BTN_GAP - rowX);
        if (searchField != null) {
            searchField.setX(rowX + 4);
            searchField.setY(searchY + 4);
            searchField.setWidth(Math.max(20, searchFieldW - 8));
        }
        y += FIELD_H + 6;

        listTop = y;
        btnY = cardY + cardH - BTN_H - 8;
        listBottom = btnY - 6;
        clampScroll();
    }

    @Override
    public void onOpened() {
        ensureLayout();
        selected.clear();
        appliedQuery = "";
        if (searchField != null) {
            searchField.setText("");
            searchField.setFocused(false);
        }
        refreshPlayers();
        scrollRow = 0;
        // 建群模式先聚焦群名框（打开就能起名）；邀请模式没有群名框，直接聚焦搜索框
        if (nameField != null) nameField.setFocused(true);
        else if (searchField != null) searchField.setFocused(true);
    }

    @Override
    public void onClosed() {
        if (nameField != null) nameField.setFocused(false);
        if (searchField != null) searchField.setFocused(false);
    }

    /** 拉一次在线玩家（并去掉邀请模式下已经在群里的人，避免勾了没反应），随后按当前搜索词过滤。 */
    private void refreshPlayers() {
        List<GroupStore.Member> list = GroupStore.onlinePlayers();
        GroupStore.Group g = isInviteMode() ? GroupStore.byId(inviteGroupId) : null;
        if (g != null) {
            List<GroupStore.Member> filtered = new ArrayList<>();
            for (GroupStore.Member m : list) if (!g.isMember(m.uuid())) filtered.add(m);
            list = filtered;
        }
        allPlayers = list;
        applyQueryToPlayers();
    }

    /**
     * 把 {@link #appliedQuery} 应用到 {@link #allPlayers} 上算出 {@link #players}。
     *
     * <p>匹配规则（用户要求）：**不区分大小写 + 包含匹配**；空词 = 显示全部在线玩家。
     * 这里是唯一的过滤入口 —— 点「搜索」按钮、在搜索框按回车、打开/刷新模态都走它，
     * 不会出现"按钮搜的和列表显示的不是同一份条件"。
     */
    private void applyQueryToPlayers() {
        String q = appliedQuery == null ? "" : appliedQuery.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            players = allPlayers;
        } else {
            List<GroupStore.Member> hit = new ArrayList<>();
            for (GroupStore.Member m : allPlayers) {
                String name = m.name() == null ? "" : m.name().toLowerCase(Locale.ROOT);
                if (name.contains(q)) hit.add(m);
            }
            players = hit;
        }
        rowRects.clear();
        scrollRow = 0;
        clampScroll();
    }

    /** 提交搜索：把输入框里当前内容变成生效的过滤条件（点「搜索」按钮 / 搜索框回车）。 */
    private void applySearch() {
        appliedQuery = searchField == null ? "" : searchField.getText().trim();
        applyQueryToPlayers();
        ChatMessageStore.debugLog(() -> "[e33chat] group " + (isInviteMode() ? "invite" : "create")
            + " | player search '" + appliedQuery + "' | hits=" + players.size()
            + "/" + allPlayers.size());
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / (ROW_H + ROW_GAP));
    }

    private void clampScroll() {
        int max = Math.max(0, players.size() - visibleRows());
        scrollRow = MathHelper.clamp(scrollRow, 0, max);
    }

    private boolean canFinish() {
        if (!GroupStore.available()) return false;
        return isInviteMode() ? !selected.isEmpty() : true;
    }

    private void finish() {
        if (!canFinish()) return;
        List<UUID> members = new ArrayList<>(selected);
        // 第 4 轮：勾选的人**不再被直接拉进群**，只发「进群邀请」；
        // 因此建群 / 添加玩家都只是"发邀请"，成员变更要等对方在私聊里点接受。
        String shownName;
        if (isInviteMode()) {
            GroupStore.Group g = GroupStore.byId(inviteGroupId);
            shownName = g != null ? g.displayName()
                : Text.translatable("e33chat.group.default_name").getString();
            GroupStore.invite(inviteGroupId, members);
        } else {
            String name = nameField != null ? nameField.getText().trim() : "";
            shownName = name.isEmpty()
                ? Text.translatable("e33chat.group.default_name").getString() : name;
            GroupStore.create(name, members);
        }
        // 发起者侧的本地回执：在「我 ↔ 每个被邀请者」的私聊里落一条「已邀请 X 加入群聊【Y】」。
        // 之后对方接受/拒绝时，服务端的回执也会落到同一个私聊会话里，前后文连得上。
        if (GroupStore.available()) {
            for (UUID id : members) {
                String target = nameOfPlayer(id);
                if (target.isBlank()) continue;
                ChatMessageStore.addWhisperNotice(target,
                    Text.literal(Text.translatable("e33chat.group.invite.sent", target, shownName).getString()),
                    false);
            }
        }
        ChatMessageStore.debugLog(() -> "[e33chat] group " + (isInviteMode() ? "invite" : "create")
            + " submit | invite targets=" + members.size() + " | group='" + shownName + "'");
        close();
    }

    /** 从「全部候选玩家」里取名字（勾选列表的来源就是它）；找不到时回退 uuid 前 8 位。 */
    private String nameOfPlayer(UUID id) {
        if (id == null) return "";
        for (GroupStore.Member m : allPlayers) {
            if (id.equals(m.uuid())) return m.name() == null ? "" : m.name();
        }
        for (GroupStore.Group g : GroupStore.groups()) {
            for (GroupStore.Member m : g.members()) {
                if (id.equals(m.uuid())) return m.name() == null ? "" : m.name();
            }
        }
        String s = id.toString();
        return s.length() >= 8 ? s.substring(0, 8) : s;
    }

    /** 关闭本模态：邀请模式回到群聊设置，建群模式直接回聊天面板。 */
    private void close() {
        onClosed();
        if (returnTo != null) host.openGroupModal(returnTo);
        else host.closeGroupModal();
    }

    private int cancelX() {
        return cardX + 12;
    }

    private int doneX() {
        return cardX + cardW - 12 - BTN_W;
    }

    // ===== 绘制 =====

    @Override
    public void render(DrawContext g, int mouseX, int mouseY, float delta) {
        ensureLayout();
        dimHost(g);
        drawCard(g);

        var c = GroupUi.colors();
        Text title = Text.translatable(isInviteMode()
            ? "e33chat.group.invite.title" : "e33chat.group.create.title");
        drawTitle(g, title, TITLE_H);

        if (!GroupStore.available()) {
            String hint = Text.translatable("e33chat.group.unavailable").getString();
            g.drawText(tr, GroupUi.ellipsize(tr, hint, cardW - 20),
                cardX + 10, cardY + TITLE_H - 1, c.redDot(), false);
        }

        if (!isInviteMode() && nameField != null) {
            GroupUi.fieldBox(g, cardX + 8, fieldY, cardW - 16, FIELD_H);
            if (nameField.getText().isEmpty()) {
                g.drawText(tr, Text.translatable("e33chat.group.create.name_hint").getString(),
                    nameField.getX() + 1, nameField.getY(), c.textMuted(), false);
            }
            nameField.render(g, mouseX, mouseY, delta);
        }

        // ---- 「搜索玩家」行：输入框 + 同高/紧贴的搜索按钮 ----
        GroupUi.fieldBox(g, cardX + 8, searchY, searchFieldW, FIELD_H);
        if (searchField != null) {
            if (searchField.getText().isEmpty()) {
                g.drawText(tr, Text.translatable("e33chat.group.search_hint").getString(),
                    searchField.getX() + 1, searchField.getY(), c.textMuted(), false);
            }
            searchField.render(g, mouseX, mouseY, delta);
        }
        hoverSearch = hit(mouseX, mouseY, searchBtnX, searchY, SEARCH_BTN_W, FIELD_H);
        GroupUi.button(g, tr, searchBtnX, searchY, SEARCH_BTN_W, FIELD_H,
            Text.translatable("e33chat.group.search.button"), hoverSearch, false, false, true);

        // 玩家勾选列表
        clampScroll();
        rowRects.clear();
        int y = listTop;
        int end = Math.min(players.size(), scrollRow + visibleRows());
        for (int i = scrollRow; i < end; i++) {
            GroupStore.Member m = players.get(i);
            boolean hov = mouseX >= cardX + 4 && mouseX <= cardX + cardW - 4
                && mouseY >= y && mouseY <= y + ROW_H;
            if (hov) {
                GroupUi.rowHighlight(g, cardX + 4, y, cardW - 8, ROW_H);
            }
            GroupUi.checkbox(g, tr, cardX + 10, y + (ROW_H - 10) / 2, 10, selected.contains(m.uuid()));
            GroupUi.head(g, m.uuid(), m.name(), cardX + 26, y + 1, 16);
            int nameX = cardX + 48;
            g.drawText(tr,
                GroupUi.ellipsize(tr, m.name(), cardX + cardW - 12 - nameX),
                nameX, y + (ROW_H - tr.fontHeight) / 2 + 1, c.textPrimary(), false);
            rowRects.add(new int[]{cardX + 4, y, cardW - 8, ROW_H, i});
            y += ROW_H + ROW_GAP;
        }
        if (players.isEmpty()) {
            // 候选为空 → "没有可邀请的在线玩家"；候选有但被搜索词滤空 → "没有匹配的玩家"
            drawEmptyHint(g, Text.translatable(allPlayers.isEmpty()
                ? "e33chat.group.create.no_players" : "e33chat.group.search.no_match"), listTop + 8);
        }

        // 底部按钮
        int cancelX = cancelX();
        int doneX = doneX();
        drawFooterLine(g, btnY);
        hoverCancel = hit(mouseX, mouseY, cancelX, btnY, BTN_W, BTN_H);
        hoverDone = hit(mouseX, mouseY, doneX, btnY, BTN_W, BTN_H);
        GroupUi.button(g, tr, cancelX, btnY, BTN_W, BTN_H,
            Text.translatable("e33chat.group.cancel"), hoverCancel, false, false, true);
        GroupUi.button(g, tr, doneX, btnY, BTN_W, BTN_H,
            Text.translatable("e33chat.group.done"), hoverDone, true, false, canFinish());
    }

    // ===== 输入 =====

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        ensureLayout();
        double mx = click.x();
        double my = click.y();
        if (click.button() == 0) {
            if (hit(mx, my, cancelX(), btnY, BTN_W, BTN_H)) { close(); return true; }
            if (hit(mx, my, doneX(), btnY, BTN_W, BTN_H)) { finish(); return true; }
            if (hit(mx, my, searchBtnX, searchY, SEARCH_BTN_W, FIELD_H)) {
                // 「搜索」按钮：把输入框内容应用到列表（按钮不抢输入框的焦点，方便继续改词再搜）
                applySearch();
                return true;
            }
            for (int[] r : rowRects) {
                if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                    GroupStore.Member m = players.get(r[4]);
                    if (!selected.remove(m.uuid())) selected.add(m.uuid());
                    return true;
                }
            }
            // 两个输入框各自可聚焦，点哪个就聚焦哪个、同时清掉另一个的焦点
            if (nameField != null && nameField.mouseClicked(click, doubleClick)) {
                // 输入框自己的聚焦不占 Screen 的焦点链（模态层拦截所有键盘事件后再转给它）
                nameField.setFocused(true);
                if (searchField != null) searchField.setFocused(false);
                return true;
            }
            if (searchField != null && searchField.mouseClicked(click, doubleClick)) {
                searchField.setFocused(true);
                if (nameField != null) nameField.setFocused(false);
                return true;
            }
            if (!insideCard(mx, my)) { close(); return true; }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (nameField != null) nameField.mouseReleased(click);
        if (searchField != null) searchField.mouseReleased(click);
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double dragX, double dragY) {
        if (nameField != null && nameField.isFocused()) nameField.mouseDragged(click, dragX, dragY);
        if (searchField != null && searchField.isFocused()) searchField.mouseDragged(click, dragX, dragY);
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
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
            || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            // 回车：焦点在搜索框 → 执行搜索；焦点在群名框 / 无焦点 → 完成（与原行为一致）
            if (searchField != null && searchField.isFocused()) applySearch();
            else if (canFinish()) finish();
            return true;
        }
        if (nameField != null && nameField.isFocused() && nameField.keyPressed(input)) return true;
        if (searchField != null && searchField.isFocused() && searchField.keyPressed(input)) return true;
        return true;
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (nameField != null && nameField.isFocused()) nameField.charTyped(input);
        if (searchField != null && searchField.isFocused()) searchField.charTyped(input);
        return true;
    }
}
