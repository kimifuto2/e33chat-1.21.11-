package com.niuqu.chatbubble;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;

import com.niuqu.chatbubble.config.ChatBubbleConfig;
import com.niuqu.chatbubble.compat.IMBlockerCompat;
import com.niuqu.chatbubble.compat.ModernUIEmojiCompat;
import com.niuqu.chatbubble.compat.NativeFileDialog;
import com.niuqu.chatbubble.config.ChatBubbleConfigScreen;
import com.niuqu.chatbubble.render.Animation;
import com.niuqu.chatbubble.render.AnimationStyle;
import com.niuqu.chatbubble.render.BlurRenderer;
import com.niuqu.chatbubble.render.RoundRectRenderer;
import com.niuqu.chatbubble.render.UiLayout;
import com.niuqu.chatbubble.render.MessageGrouping;
import com.niuqu.chatbubble.render.UiTokens;
import com.niuqu.chatbubble.render.ChatTextSelection;
import com.niuqu.chatbubble.render.TextSpan;
import com.niuqu.chatbubble.store.BlockList;
import com.niuqu.chatbubble.ui.EmoteStore;
import com.niuqu.chatbubble.image.BracketCodec;
import com.niuqu.chatbubble.image.ImageEntry;
import com.niuqu.chatbubble.image.ImageLoader;
import com.niuqu.chatbubble.image.ImageUploader;
import com.niuqu.chatbubble.image.LocalImageSource;
import com.niuqu.chatbubble.render.Appearance;
import com.niuqu.chatbubble.render.ChatBubbleTheme;
import com.niuqu.chatbubble.store.ChatMessageStore;
import com.niuqu.chatbubble.network.QuoteSyncPayload;
import com.niuqu.chatbubble.texture.ColoredTextureRenderer;
import com.niuqu.chatbubble.texture.UiElement;
import com.niuqu.chatbubble.texture.UiTextureManager;
import com.niuqu.chatbubble.ui.ChatEmojiPanel;
import com.niuqu.chatbubble.ui.ChatQuickChatPanel;
import com.niuqu.chatbubble.ui.ChatSearchPanel;
import com.niuqu.chatbubble.ui.ChatSettingsMenu;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatInputSuggestor;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

import java.io.InputStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class ChatBubbleScreen extends ChatScreen {

    // Layout
    private int panelX, panelW;
    private static final int TITLE_H = 24;
    private int titleY, msgTop, msgBottom, barTop;
    private static final int PAD = UiTokens.PAD;
    private static final int BUBBLE_PAD_X = UiTokens.BUBBLE_PAD_X;
    private static final int BUBBLE_PAD_Y = UiTokens.BUBBLE_PAD_Y;
    private static final int NAME_H = 10;
    private static final int TIME_SEP_H = 14;
    /** 私聊会话顶部「私聊 · 对象名」标题条的高度：标题条绘制与消息区顶部下移都取这一个值。
     *  （原来条右侧还有一颗「本私聊免打扰」月亮按钮，已并入标题栏那颗按视图切换的按钮，
     *  所以这里不再涉及按钮命中。） */
    private static final int WHISPER_BAR_H = 14;
    public static final int BAR_H = 26;
    private static final int SIDEBAR_W = 90;
    private static final int SIDEBAR_ITEM_H = 22;
    private static final int SIDEBAR_ICON_S = 20;
    /** 玩家行「私聊免打扰」纯月亮图标的边长（无圆角底，就是图标本身，故取 9px）。 */
    private static final int DND_ROW_ICON = 9;
    /** 月亮图标的 X：紧贴头像右侧（头像 16px 画在 x=4，外层 hat 扩到 x=21）。 */
    private static final int DND_ROW_ICON_X = 22;
    /** 图标与名字之间的间距：名字整体右移「图标宽 + 间距」，把宽度让给图标。 */
    private static final int DND_ROW_ICON_GAP = 2;
    /** 侧边栏底部状态行（在线人数 / 单人游戏）的文字颜色：绿色，暗浅主题下都够亮。 */
    private static final int SIDEBAR_STATUS_COLOR = 0xFF55FF55;

    private ChatBubbleTheme.Colors c() {
        return Appearance.snapshot();
    }

    private ChatBubbleTheme theme() {
        return Appearance.resolvedTheme();
    }

    private static final int INPUT_H = 14;
    /** 输入框底色/描边相对输入框本体向外扩的像素数（renderBottomBar 里的 -2 / +2）。
     *  输入框「看起来的那块矩形」= 本体上下各外扩这么多，状态行对齐用的就是它。 */
    private static final int INPUT_BOX_OUTSET = 2;
    private static final int ICON_S = 14;

    /** 底部输入框本体的顶部 Y（底栏内垂直居中）。
     *  绘制（renderBottomBar）、输入框 widget 定位（init/rebuildLayout）、
     *  侧边栏状态行对齐（sidebarStatusY）全部从这里取值——以前三处各算一遍
     *  (BAR_H - INPUT_H)/2，改一个常量就可能错位。 */
    private int inputBarY() {
        return barTop + (BAR_H - INPUT_H) / 2;
    }

    /** 底部输入框本体的高度（不含 {@link #INPUT_BOX_OUTSET} 的外扩描边）。 */
    private int inputBarH() {
        return INPUT_H;
    }

    /** 底部输入框「可见矩形」（含外扩描边）的垂直中心：
     *  上沿 = inputBarY - 2，下沿 = inputBarY + inputBarH + 2。 */
    private int inputBoxCenterY() {
        int top = inputBarY() - INPUT_BOX_OUTSET;
        int bottom = inputBarY() + inputBarH() + INPUT_BOX_OUTSET;
        return (top + bottom) / 2;
    }

    public static Identifier iconTex(String name) {
        String theme = Appearance.resolvedTheme().name().toLowerCase();
        return Identifier.of("e33chat", "textures/gui/" + theme + "/" + name + ".png");
    }


    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private static String timeKey(long t) {
        return ChatMessageStore.timeKey(t, ChatBubbleClientSetup.config().timeSeparatorMinutes());
    }

    private ChatInputSuggestor commandSuggestions;
    private static int inputX, inputY;

    public static int getInputX() { return inputX; }
    public static int getInputY() { return inputY; }
    private final String initialText;
    private String historyBuffer = "";
    private int historyPos = -1;
    private int scrollOffset;
    private int maxScroll;
    private boolean scrollToBottom = true;
    private boolean firstRender = true;
    // 切换私聊会话后的一次性请求：下一帧直接把 scrollOffset 钉到新会话底部，
    // 不从旧会话的偏移做 150ms 插值（否则会看到内容从旧位置滑/跳过去）。
    private boolean scrollSnapPending;
    private static String savedInput = "";
    private boolean emojiReplacing;


    final ChatEmojiPanel emojiPanel = new ChatEmojiPanel();
    final ChatSettingsMenu settingsMenu = new ChatSettingsMenu();
    final ChatSearchPanel searchPanel = new ChatSearchPanel();
    private TextFieldWidget searchInput;
    private final List<Integer> searchMatches = new ArrayList<>();
    private int searchMatchIdx;
    private int searchHighlightIndex = -1;
    final ChatQuickChatPanel quickChatPanel = new ChatQuickChatPanel();
    private TextFieldWidget quickChatInput;
    private static final int QUICK_CHAT_W = 140;
    private static boolean sidebarOpen;

    // Popup open animation timestamps (opening only; closing stays instant)
    private long settingsAnimStart, emojiAnimStart, quickAnimStart, searchAnimStart;
    // Popup close animation timestamps (0 = not closing; D07-6)
    private long settingsCloseStart, emojiCloseStart, quickCloseStart, searchCloseStart;
    private String whisperPartner;
    private int sidebarScrollOffset;
    private int sidebarMaxScroll;
    private TextFieldWidget sidebarSearchBox;

    private long sidebarAnimStart;
    private boolean sidebarTargetOpen;
    private boolean sidebarAnimating;

    private static final int SCROLLBAR_WIDTH = 6;
    private static final int MIN_THUMB_H = 8;
    private boolean scrollbarDragging;
    private int scrollbarDragStartY;
    private int scrollbarDragStartOffset;
    private int messageTotalH;
    private boolean scrollbarHovered;
    private float scrollbarAlpha;
    private static final int SCROLLBAR_HOVER_ZONE = 20;
    private boolean scrollAnimActive;
    private long scrollAnimStart;
    private float scrollAnimFrom;
    private float scrollAnimTo;
    private int scrollAnimDuration;
    private long lastScrollTime;

    private boolean showMentions;
    private boolean mentionNavigated;
    private final List<String> mentionCandidates = new ArrayList<>();
    private int mentionIdx;
    private String mentionFilter = "";

    private int contextMsgIndex = -1;
    private int contextX, contextY;
    private static final int CTX_W = 80;
    private static final int CTX_ITEM_H = 18;
    /** 右键菜单外框圆角：沿用设置菜 ?表情面板/快捷聊天/搜索面板 ?9f（RoundRectRenderer.panel） ?*/
    private static final float CTX_RADIUS = 9f;
    /** 菜单内悬 ?选中高亮圆角：沿用设置菜单的「外框内 ?1px + 5f」相对关系 ?*/
    private static final float CTX_ITEM_RADIUS = 5f;
    private int contextAvatarIndex = -1;
    private int contextAvatarX, contextAvatarY;

    // Per-frame wrap cache: every message is measured once (layout pass) and
    // rendered once (bubble pass), and both call getMsgHeight -> wrapContent.
    // Without the cache each message gets re-wrapped 3x per frame.
    private final Map<ChatMessageStore.ChatMessage, Integer> msgHeightCache =
        new IdentityHashMap<>();

    // Image cards: parsed once per message (bracket strip + refs), invalidated
    // when ImageLoader flips any entry's state (VERSION bumps).
    private final Map<ChatMessageStore.ChatMessage, BracketCodec.ParseResult> imageParseCache =
        new IdentityHashMap<>();
    private int lastImageVersion = -1;
    private int uploadToastTicks = 0;
    /** Upload-in-progress hint; set while a job is running, cleared on completion. */
    private int uploadBusyTicks = 0;

    private final com.niuqu.chatbubble.image.UploadQueue uploadQueue =
        new com.niuqu.chatbubble.image.UploadQueue(new com.niuqu.chatbubble.image.UploadQueue.Callbacks() {
            @Override public void onBusyStart() { uploadBusyTicks = 60; }
            @Override public void onIdle() { uploadBusyTicks = 0; }
            @Override public void onFailure() { uploadBusyTicks = 0; uploadToastTicks = 60; }
            @Override public void onEmoteSent(String url) { sendMessageText(url); }
            @Override public void onSendText(String text) { sendMessageText(text); }
            @Override public void onInputImage(String code) {
                String cur = chatField.getText();
                if (cur.contains("[[CICode,url=file://")) {
                    cur = cur.replaceFirst("\\[\\[CICode,url=file://[^]]*]]", code);
                } else {
                    cur = cur.isEmpty() ? code : cur + " " + code;
                }
                chatField.setText(cur);
                chatField.setCursorToEnd(false);
            }
            @Override public void onRestoreInput(String text) { chatField.setText(text); }
        });
    private static final int EMOTE_MAX_SIZE = 32;


    private final List<int[]> bubbleRects = new ArrayList<>();
    private final List<ClickableSpan> clickableSpans = new ArrayList<>();
    /** 引用块命中矩形（屏幕坐标，每帧随滚动/气泡缩放回填）：{x, y, w, h, 持有该引用的消息下标} ?*/
    private final List<int[]> quoteRects = new ArrayList<>();
    /** 引用精确跳转表（主键 = 对象身份）：自己消息 -> (发送时自己的下标 - 被引用消息的全量下标)。
     *  存「相对距离」而不是绝对下标：消息列表从头部淘汰旧消息时两者同步位移，距离仍指向正确那条。
     *  查表用 IdentityHashMap，内容完全重复时也能跳到用户当初引用的那一条。 */
    private final java.util.Map<ChatMessageStore.ChatMessage, Integer> quoteTargetByMessage =
        new java.util.IdentityHashMap<>();
    /** 同一批关联的「指纹别名」= messageHash@time：自己消息的回显到达时 EchoTracker 会用
     *  withSenderName 换掉实例（身份键随之失效），指纹在这种替换后不变，可继续精确命中。 */
    private final java.util.Map<String, Integer> quoteTargetByFingerprint = new java.util.HashMap<>();
    /** 关联的插入顺序（存对象），按 FIFO 淘汰，保证两张表合计最多 QUOTE_TARGET_MAX 条 */
    private final java.util.ArrayDeque<ChatMessageStore.ChatMessage> quoteTargetOrder = new java.util.ArrayDeque<>();
    private static final int QUOTE_TARGET_MAX = 64;
    /** 左键按在引用块上：按下只吞掉这次点击（保持选字/无操作语义），跳转只由 mouseClicked 的 doubleClick 分支触发 */
    private boolean quotePressPending;
    private double quotePressX, quotePressY;
    /** 引用跳转高亮：目标消息（用对象身份，列表变动时不会高亮错消息 ? 起始时间；null = 无 ?*/
    private ChatMessageStore.ChatMessage quoteFlashMsg;
    private long quoteFlashStart;
    private static final int QUOTE_FLASH_MS = 1700;
    /** 引用命中矩形 ?quoteRects 里的下标（仅调试日志用，-1 = 未命中） ?*/
    private int quoteOwnerRectIndex = -1;
    /** 跳转后还要打印几帧的 scrollOffset（诊断「是否被自动到底/动画覆盖」，有限次） ?*/
    private int quoteJumpFrameLogs;
    /** QUOTEJUMP 诊断行：debugLog 关闭时落盘到 <runDir>/e33chat/quotejump-debug.log   *  保证「点了引用却什么都没有」也能被取证（点一次只写几行） ?*/
    private static void quoteJumpLog(java.util.function.Supplier<String> msg) {
        String line = msg.get();
        ChatMessageStore.debugLog(line);
        try {
            java.nio.file.Path dir = net.minecraft.client.MinecraftClient.getInstance()
                .runDirectory.toPath().resolve("e33chat");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path f = dir.resolve("quotejump-debug.log");
            if (java.nio.file.Files.exists(f) && java.nio.file.Files.size(f) > 262_144L)
                java.nio.file.Files.deleteIfExists(f);
            java.nio.file.Files.writeString(f, line + System.lineSeparator(),
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // 诊断写盘失败绝不能影响交互
        }
    }
    /** Per-frame list of rendered text runs (screen space) used for drag selection. */
    private final List<TextSpan> textSpans = new ArrayList<>();
    private final ChatTextSelection textSelection = new ChatTextSelection();
    private double selectionStartX;
    private double selectionStartY;

    private int replyTargetIndex = -1;
    private int copyToastTicks;

    private long animStart;
    private boolean closing;
    private static final int ANIM_MS = 150;
    private static final int NOTIF_H = 14;
    private int newMessageCount;
    private boolean hasNewMentionOrQuote;
    private int latestMentionIndex = -1;
    private int lastSeenMessageCount;
    private int notifCountLeft, notifCountRight;
    private int notifMentionLeft = -1, notifMentionRight = -1;
    private int notifBarTextY;

    public ChatBubbleScreen(String initialText) {
        super("", false);
        this.initialText = initialText;
    }

    @Override
    protected void init() {
        historyPos = client.inGameHud.getChatHud().getMessageHistory().size();
        ChatMessageStore.setScreenOpen(true);
        historyPos = client.inGameHud.getChatHud().getMessageHistory().size();
        animStart = Util.getMeasuringTimeMs();
        closing = false;
        firstRender = true;

        int physicalW = ChatBubbleClientSetup.config().panelWidth();
        int guiScale = (int) Math.round(client.getWindow().getScaleFactor());
        panelW = Math.max(100, Math.min(physicalW / Math.max(1, guiScale), width));
        if (sidebarOpen) {
            panelX = SIDEBAR_W;
            sidebarAnimating = false; // sidebar is already in place; the panel's
            // own open animation (by style) handles its entrance  ?don't let the
            // sidebar state machine re-drive panelX (slides the whole panel)
        } else {
            panelX = 0;
            sidebarAnimating = false;
            sidebarTargetOpen = false;
        }
        if (panelX + panelW > width) panelW = width - panelX;
        titleY = 0;
        msgTop = titleY + TITLE_H + 1;
        barTop = height - BAR_H;
        msgBottom = barTop - 1;

        int ibY = inputBarY();
        inputY = ibY;
        inputX = panelX + 4 + ICON_S + 3;
        int sendX = panelX + panelW - PAD - ICON_S + 2;
        int inputW = sendX - ICON_S - 8 - inputX;

        chatField = new TextFieldWidget(textRenderer, inputX, ibY + 3, inputW, INPUT_H, Text.literal(""));
        chatField.setMaxLength(256);
        chatField.setDrawsBackground(false);
        int editColor = theme() == ChatBubbleTheme.LIGHT ? c().textSecondary() : c().textPrimary();
        chatField.setEditableColor(editColor);
        chatField.setUneditableColor(c().textMuted());
        chatField.setText(initialText.isEmpty() && ChatBubbleClientSetup.config().preserveInput() && !savedInput.isEmpty() ? savedInput : initialText);
        chatField.setChangedListener(this::onInputEdited);
        chatField.setFocusUnlocked(false);
        addDrawableChild(chatField);

        int cmdBgAlpha = theme() == ChatBubbleTheme.LIGHT ? 0x99 : 0xDD;
        commandSuggestions = new ChatInputSuggestor(client, this, chatField, textRenderer,
            false, false, 0, 8, true, ChatBubbleTheme.alphaBlend(c().panelBg(), cmdBgAlpha));
        commandSuggestions.setWindowActive(true);
        commandSuggestions.refresh();


        sidebarSearchBox = new TextFieldWidget(textRenderer, 2, 5, SIDEBAR_W - 5, SIDEBAR_SEARCH_H, Text.literal(""));
        sidebarSearchBox.setMaxLength(20);
        sidebarSearchBox.setDrawsBackground(false);
        sidebarSearchBox.setEditableColor(editColor);
        sidebarSearchBox.setUneditableColor(editColor);
        sidebarSearchBox.setVisible(sidebarOpen);
        sidebarSearchBox.setChangedListener(s -> sidebarScrollOffset = 0);
        sidebarSearchBox.setFocusUnlocked(true);
        if (sidebarOpen) sidebarSearchBox.setX(2);
        addDrawableChild(sidebarSearchBox);

        quickChatInput = new TextFieldWidget(textRenderer, 0, 0, QUICK_CHAT_W - 8, 12, Text.translatable("e33chat.menu.quick_chat"));
        quickChatInput.setMaxLength(256);
        quickChatInput.setDrawsBackground(false);
        quickChatInput.setEditableColor(editColor);
        quickChatInput.setUneditableColor(c().textMuted());
        quickChatInput.setVisible(false);
        quickChatInput.setFocusUnlocked(true);
        addDrawableChild(quickChatInput);

        searchInput = new TextFieldWidget(textRenderer, 0, 0, 160, 12, Text.translatable("e33chat.menu.search"));
        searchInput.setMaxLength(128);
        searchInput.setDrawsBackground(false);
        searchInput.setEditableColor(editColor);
        searchInput.setUneditableColor(c().textMuted());
        searchInput.setVisible(false);
        searchInput.setChangedListener(this::onSearchEdited);
        searchInput.setFocusUnlocked(true);
        addDrawableChild(searchInput);

        setFocused(chatField);
        // The chat field's initial text is set before setChangedListener binds,
        // so the open-time value (e.g. "/" from the chat key) never flows through
        // onInputEdited  ?sync it once so the IMBlocker IME state is correct.
        onInputEdited(chatField.getText());

        // D07-6: 弹层关闭动画钩子——visible 延迟 ?false，先 ?150ms 关闭动画
        settingsMenu.closeRequest = () -> beginPopupClose(s -> settingsCloseStart = s,
            () -> settingsMenu.visible = false);
        emojiPanel.closeRequest = () -> beginPopupClose(s -> emojiCloseStart = s,
            () -> emojiPanel.visible = false);
        quickChatPanel.closeRequest = () -> beginPopupClose(s -> quickCloseStart = s, () -> {
            quickChatPanel.visible = false;
            quickChatInput.setVisible(false);
        });
    }

    private void rebuildLayout() {
        int physicalW = ChatBubbleClientSetup.config().panelWidth();
        int guiScale = (int) Math.round(client.getWindow().getScaleFactor());
        panelW = Math.max(100, Math.min(physicalW / Math.max(1, guiScale), width));
        if (panelX + panelW > width) panelW = width - panelX;
        titleY = 0;
        msgTop = titleY + TITLE_H + 1;
        barTop = height - BAR_H;
        msgBottom = barTop - 1;

        int ibY = inputBarY();
        inputY = ibY;
        inputX = panelX + 4 + ICON_S + 3;
        int sendX = panelX + panelW - PAD - ICON_S + 2;
        int inputW = sendX - ICON_S - 8 - inputX;

        if (chatField != null) {
            chatField.setX(inputX);
            chatField.setWidth(inputW);
            chatField.setY(ibY + 3);
        }
    }

    private String getDisplayTitle() {
        if (whisperPartner != null) return whisperPartner;
        return Text.translatable("e33chat.sidebar.public").getString();
    }

    private float getSidebarAnimProgress() {
        if (!ChatBubbleClientSetup.config().animationEnabled()) return sidebarOpen ? 1f : 0f;
        AnimationStyle style = AnimationStyle.parse(ChatBubbleClientSetup.config().panelAnimStyle());
        // Hamburger toggle always slides, regardless of the panel animation style
        if (sidebarAnimating) {
            long elapsed = Util.getMeasuringTimeMs() - sidebarAnimStart;
            float t = MathHelper.clamp((float) elapsed / ANIM_MS, 0f, 1f);
            float progress = Animation.styleCurve(AnimationStyle.SLIDE, t);
            return sidebarTargetOpen ? progress : 1.0f - progress;
        }
        // FADE/NONE have no horizontal displacement: the sidebar fades in place.
        if (style == AnimationStyle.FADE || style == AnimationStyle.NONE) return sidebarOpen ? 1f : 0f;
        if (!sidebarOpen) return 0f;
        return getAnimProgress(); // follow the panel's open animation
    }

    private int getSidebarScreenX() {
        return (int) ((getSidebarAnimProgress() - 1.0f) * SIDEBAR_W);
    }

    private void tickSidebarAnimation() {
        if (!sidebarAnimating) return;
        long elapsed = Util.getMeasuringTimeMs() - sidebarAnimStart;
        float t = MathHelper.clamp((float) elapsed / ANIM_MS, 0f, 1f);
        if (t >= 1f) {
            sidebarAnimating = false;
            sidebarOpen = sidebarTargetOpen;
            panelX = sidebarOpen ? SIDEBAR_W : 0;
            sidebarSearchBox.setX(2);
            sidebarSearchBox.setVisible(sidebarOpen);
            if (!sidebarOpen && sidebarSearchBox.isFocused()) setFocused(chatField);
            rebuildLayout();
            return;
        }
        float progress = getSidebarAnimProgress();
        panelX = (int) (SIDEBAR_W * progress);
        sidebarSearchBox.setX(2 + getSidebarScreenX());
        sidebarSearchBox.setVisible(progress > 0.01f);
        rebuildLayout();
    }

    private static final int SIDEBAR_SEARCH_H = 14;
    /** 侧边栏玩家列表可见区顶部 Y：搜索框（2+14）+ 3 间隔 + 世界频道行（22）+ 2 间隔。
     *  绘制与点击命中共用这一个值，滚动夹紧也用它算可见高度。 */
    private static final int SIDEBAR_LIST_TOP = 2 + SIDEBAR_SEARCH_H + 3 + SIDEBAR_ITEM_H + 2;

    private void renderSidebar(DrawContext g, int mouseX, int mouseY, float alpha) {
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_BG), 0, 0, SIDEBAR_W, height, alpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), SIDEBAR_W - 1, 0, 1, height, alpha);

        int y = 2;
        int itemH = SIDEBAR_ITEM_H;

        int sbx = 2;
        int sby = 2;
        int sbw = SIDEBAR_W - 5;
        int sbh = SIDEBAR_SEARCH_H;
        RoundRectRenderer.fill(g, sbx - 1, sby, sbx + sbw, sby + sbh, 5f, ChatBubbleTheme.alphaBlend(c().inputBg(), (int)(255 * alpha)));
        boolean hoverSearch = mouseX >= sbx - 1 && mouseX <= sbx + sbw && mouseY >= sby && mouseY <= sby + sbh;
        if (hoverSearch || sidebarSearchBox.isFocused())
            RoundRectRenderer.fill(g, sbx - 1, sby, sbx + sbw, sby + sbh, 5f, ChatBubbleTheme.alphaBlend(c().textMuted(), (int)(255 * alpha)));
        if (sidebarSearchBox.getText().isEmpty() && !sidebarSearchBox.isFocused()) {
            g.drawText(textRenderer, Text.translatable("e33chat.sidebar.search").getString(), sbx, sby + 3, c().textMuted(), false);
        }
        y = sby + sbh + 3;

        boolean isPublic = whisperPartner == null;
        boolean hoverTab = mouseX >= 0 && mouseX <= SIDEBAR_W && mouseY >= y && mouseY <= y + itemH;
        if (isPublic)
            ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_SELECTED), 0, y, SIDEBAR_W, itemH, alpha);
        else if (hoverTab)
            ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_HOVER), 0, y, SIDEBAR_W, itemH, alpha);
        drawTextureIconAlpha(g, iconTex("public_icon"), 2, y + 1, SIDEBAR_ICON_S, alpha);
        int nameX = 2 + SIDEBAR_ICON_S + 3;
        String publicLabel = Text.translatable("e33chat.sidebar.public").getString();
        g.drawText(textRenderer, publicLabel, nameX, y + 1, c().textPrimary(), false);
        ChatMessageStore.ChatMessage latestPub = ChatMessageStore.getLatestPublicMessage();
        if (latestPub != null) {
            int previewMaxW = SIDEBAR_W - nameX - 4;
            String preview = ChatMessageStore.singleLine(latestPub.content().getString());
            g.drawText(textRenderer, ellipsize(preview, previewMaxW), nameX, y + 1 + textRenderer.fontHeight, c().textMuted(), false);
        }
        y += itemH + 2;

        if (client.player != null && client.player.networkHandler != null) {
            var players = new ArrayList<>(client.player.networkHandler.getPlayerList());
            String selfName = client.player.getName().getString();
            String filter = sidebarSearchBox.getText().toLowerCase().trim();

            int startY = SIDEBAR_LIST_TOP;
            // 底部留出固定状态行的位置：列表（含 scissor）到它上方结束
            int visibleBottom = sidebarListBottom();
            int totalH = 0;
            for (var info : players) {
                String name = info.getProfile().name();
                if (name.equals(selfName)) continue;
                if (!filter.isEmpty() && !name.toLowerCase().contains(filter)) continue;
                if (ChatBubbleClientSetup.config().isSidebarHidden(name)) continue;
                totalH += itemH + 2;
            }

            if (totalH == 0) {
                // 列表空（全员离线 / 过滤无命中）时没有可滚动内容：把偏移一起归零，
                // 否则旧偏移会一直留到下次有人上线，首行被裁掉半截。
                clampSidebarScroll();
                int iconS = 32;
                drawTextureIconAlpha(g, iconTex("no_online"), (SIDEBAR_W - iconS) / 2, startY + 8, iconS, alpha);
                String noPlayers = Text.translatable("e33chat.sidebar.no_players").getString();
                int textW = textRenderer.getWidth(noPlayers);
                g.drawText(textRenderer, noPlayers,
                    (SIDEBAR_W - textW) / 2, startY + 8 + iconS + 4, c().textMuted(), false);
            } else {
                // 行数每帧都可能变（上下线 / 搜索词 / 隐藏规则）：画之前先把偏移夹回
                // [0, 上限]。越界 offset 会把首行画到可见区上方、被 scissor 裁掉半截，
                // 看起来就像"列表自己滚到了中间"。
                clampSidebarScroll();

                g.enableScissor(0, startY, SIDEBAR_W, visibleBottom);
                int scrollY = startY - sidebarScrollOffset;
                for (var info : players) {
                    String name = info.getProfile().name();
                    if (name.equals(selfName)) continue;
                    if (!filter.isEmpty() && !name.toLowerCase().contains(filter)) continue;
                    if (ChatBubbleClientSetup.config().isSidebarHidden(name)) continue;

                    if (scrollY + itemH > startY && scrollY < visibleBottom) {
                        boolean sel = name.equals(whisperPartner);
                        boolean hoverRow = mouseX >= 0 && mouseX <= SIDEBAR_W && mouseY >= scrollY && mouseY <= scrollY + itemH;
                        if (sel)
                            ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_SELECTED), 0, scrollY, SIDEBAR_W, itemH, alpha);
                        else if (hoverRow)
                            ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_HOVER), 0, scrollY, SIDEBAR_W, itemH, alpha);

                        Identifier skin = com.niuqu.chatbubble.render.SkinResolver.getSkin(info.getProfile().id(), info.getProfile().name());
                        drawPlayerHead(g, skin, 4, scrollY + 3, 16, 18, alpha);

                        // 该玩家的私聊是否被单独设为免打扰：是则在头像右侧、名字左侧的图形区
                        // 画一个「纯月亮图标」（无圆角底，与标题栏的免打扰按钮共用同一张图）。
                        // 图标染成紫色（深/浅主题各一档，见 ChatBubbleTheme.dndAccent）：
                        // 白色/深棕的月亮与玩家名（白/深米色）太接近，紫色才能一眼区分
                        // "这个名字旁边有个静音标记"。
                        boolean dndRow = ChatBubbleClientSetup.config().isWhisperDnd(name);
                        if (dndRow) {
                            int dndIconY = scrollY + 1 + (textRenderer.fontHeight - DND_ROW_ICON) / 2;
                            drawDndRowIcon(g, DND_ROW_ICON_X, dndIconY, alpha);
                        }
                        // 有月亮图标时名字整体右移，可用宽度相应扣掉「图标宽 + 间距」——
                        // 不扣的话名字/省略号会压到图标上。
                        int rowNameX = dndRow ? DND_ROW_ICON_X + DND_ROW_ICON + DND_ROW_ICON_GAP : nameX;

                        int tipW = ChatMessageStore.hasUnreadWhisper(name) ? 16 : 0;
                        int maxNameW = SIDEBAR_W - rowNameX - 4 - tipW - 2;
                        g.drawText(textRenderer, ellipsize(name, maxNameW), rowNameX, scrollY + 1, c().textPrimary(), false);

                        ChatMessageStore.ChatMessage latest = ChatMessageStore.getLatestWhisperWith(name);
                        if (latest != null) {
                            String preview = ChatMessageStore.singleLine(latest.content().getString());
                            g.drawText(textRenderer, ellipsize(preview, maxNameW), rowNameX,
                                scrollY + 1 + textRenderer.fontHeight, c().textMuted(), false);
                        }

                        if (ChatMessageStore.hasUnreadWhisper(name)) {
                            int tipX = SIDEBAR_W - 16 - 2;
                            int tipY = scrollY + 3 + (int) (Math.abs(Math.sin(System.currentTimeMillis() / 300.0)) * 3);
                            drawTextureIconAlpha(g, iconTex("private_tip"), tipX, tipY, 16, alpha);
                        }
                    }
                    scrollY += itemH + 2;
                }
                g.disableScissor();
            }
        }

        // 底部状态行：在线人数 / 单人游戏。放在侧边栏最底部那一行 —— 与右侧聊天
        // 输入栏同一段 y（底部输入栏高 BAR_H，文字在其中垂直居中），列表紧贴其上方；
        // 列表的 scissor 到 sidebarListBottom()（= 状态行上方 SIDEBAR_STATUS_PAD px）就结束，
        // 状态行画在它下面，所以不随玩家列表滚动，玩家列表为空 / 搜索无结果时也照常显示。
        // 在线人数取 TAB 列表口径（getPlayerList()，含自己）。
        String statusText;
        if (client.isInSingleplayer()) {
            statusText = "【单人游戏中】";
        } else {
            int online = (client.player != null && client.player.networkHandler != null)
                ? client.player.networkHandler.getPlayerList().size() : 0;
            statusText = "【在线" + online + "人】";
        }
        // 侧边栏有淡入淡出：文字色必须走 alphaBlend 归一化 alpha，
        // 否则 1.21.11 的 drawText 在 alpha==0 时直接不画（裸色值也会丢掉淡入效果）。
        // 水平居中：renderSidebar 是在 g.getMatrices().translate(sidebarOffset, 0) 之后
        // 调用的，所以这里的 x 已经是「侧边栏本地坐标」——不要再加 sidebarOffset，
        // 加了开合/滑入时文字会整体偏出去（淡出态更是直接飞出可视区）。
        // 度量与绘制用同一个 Text 实例，居中基准取扣掉右侧 1px 分隔线后的内容宽度；
        // 再给左边缘留 2px 兜底，避免某些字体下量出来的宽度偏大时文字贴到左边。
        Text statusLabel = Text.literal(statusText);
        int statusW = textRenderer.getWidth(statusLabel);
        int statusX = Math.max(2, (SIDEBAR_W - 1 - statusW) / 2);
        g.drawText(textRenderer, statusLabel, statusX,
            sidebarStatusY(), ChatBubbleTheme.alphaBlend(SIDEBAR_STATUS_COLOR, (int) (255 * alpha)), false);
    }

    /** 侧边栏底部状态行文字与玩家列表底部之间留出的空白（列表 scissor / 点击命中 / 滚动夹紧
     *  都以 sidebarListBottom() 为界，这里就是那段间隙）。
     *  取 2：只留一条视觉缝。以前是 20——那 20px 空带完全落在状态行上方，它的上沿比右侧
     *  输入框（可见底边 = barTop + INPUT_BOX_OUTSET）还高，侧边栏底部看起来比输入区"多出一块"，
     *  左右不持平。列表底部因此下移到状态行正上方（barTop 附近），与右侧输入框同一段收口。 */
    private static final int SIDEBAR_STATUS_PAD = 2;

    /** 侧边栏底部状态行文字的顶部 Y：文字**垂直中心**对齐底部输入框（含 -2px 外扩
     *  描边的那块可见矩形）的垂直中心，即 statusY = inputBoxCenterY - fontHeight/2。
     *  几何全部取自 inputBarY()/inputBarH()/INPUT_BOX_OUTSET 这一份数值，
     *  不再单独用 BAR_H 估一遍——以前用 (BAR_H - fontHeight)/2 会比输入框高出 1px，
     *  看起来"文字偏上、下面多一小条空白"。 */
    private int sidebarStatusY() {
        return inputBoxCenterY() - textRenderer.fontHeight / 2;
    }

    /** 侧边栏玩家列表可用区的底部 Y：到状态行文字顶部再往上留 {@link #SIDEBAR_STATUS_PAD}px
     *  为止（状态行与列表之间那条 2px 的缝）。
     *  绘制 / 点击命中 / 滚动夹紧共用这一个值：不留的话列表会画到状态行上、
     *  scissor 也会把状态行一起裁掉，点状态行还可能命中最后一行玩家。 */
    private int sidebarListBottom() {
        return Math.max(SIDEBAR_LIST_TOP, sidebarStatusY() - SIDEBAR_STATUS_PAD);
    }

    /** 侧边栏玩家列表可见区高度（底部到状态行正上方、再扣掉顶部搜索/世界频道那一段，
     *  与 renderSidebar 里的 visibleBottom 同源）。 */
    private int sidebarListViewH() {
        return Math.max(0, sidebarListBottom() - SIDEBAR_LIST_TOP);
    }

    /** 按当前搜索词 + 隐藏规则重新数一遍玩家行数。刻意不复用上一帧渲染的 totalH：
     *  点行/清空搜索框时列表内容已经变了，旧值不可信。 */
    private int sidebarVisibleRows() {
        if (client == null || client.player == null || client.player.networkHandler == null) return 0;
        String selfName = client.player.getName().getString();
        String filter = sidebarSearchBox != null ? sidebarSearchBox.getText().toLowerCase().trim() : "";
        int rows = 0;
        for (var info : client.player.networkHandler.getPlayerList()) {
            String name = info.getProfile().name();
            if (name.equals(selfName)) continue;
            if (!filter.isEmpty() && !name.toLowerCase().contains(filter)) continue;
            if (ChatBubbleClientSetup.config().isSidebarHidden(name)) continue;
            rows++;
        }
        return rows;
    }

    /** 把侧边栏滚动偏移夹进 [0, 当前上限]。
     *  列表是每帧按玩家列表/搜索词/隐藏规则重建的，行数随时可能变少（有人下线、过滤词
     *  变化、切换会话时清空搜索框、隐藏规则变更）。offset 一旦停在越界值，首行就会被
     *  画到可见区上方并被 scissor 裁掉半截，看起来像"列表自己滚到了中间"。因此凡是
     *  可能改变行数的地方都夹一次，不只依赖 render 里的那一处。 */
    private void clampSidebarScroll() {
        int totalH = sidebarVisibleRows() * (SIDEBAR_ITEM_H + 2);
        sidebarMaxScroll = Math.max(0, totalH - sidebarListViewH());
        if (sidebarScrollOffset < 0) sidebarScrollOffset = 0;
        if (sidebarScrollOffset > sidebarMaxScroll) sidebarScrollOffset = sidebarMaxScroll;
    }

    /** 按像素宽度截断并补 "..."（项目里原有 trimToWidth + "..." 写法抽成一处）。
     *  放得下就原样返回（不无脑加省略号）；连 "..." 都放不下时只做硬截断。 */
    private String ellipsize(String text, int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (maxWidth <= 0) return "";
        int ellipsisW = textRenderer.getWidth("...");
        if (maxWidth <= ellipsisW) return textRenderer.trimToWidth(text, maxWidth);
        String trimmed = textRenderer.trimToWidth(text, maxWidth - ellipsisW);
        return trimmed.equals(text) ? text : trimmed + "...";
    }

    private void insertMention(String name) {
        String text = chatField.getText();
        int atIdx = text.lastIndexOf('@');
        chatField.setText(text.substring(0, atIdx) + "@" + name + " ");
        chatField.setCursorToEnd(false);
        showMentions = false;
        mentionNavigated = false;
    }

    private void onInputEdited(String text) {
        // ModernUI hooks vanilla ChatScreen.onEdited; E33Chat installs its own
        // responder, so mirror the shortcode transformation here when ModernUI
        // is installed and has the feature enabled.
        if (!emojiReplacing && ModernUIEmojiCompat.isEnabled() && !text.startsWith("/")) {
            emojiReplacing = true;
            try {
                if (ModernUIEmojiCompat.replaceIn(chatField)) {
                    return; // reentrant onInputEdited already did post-processing
                }
            } finally {
                emojiReplacing = false;
            }
        }
        showMentions = false;
        mentionNavigated = false;
        int atIdx = text.lastIndexOf('@');
        // Commands use vanilla selectors (@s/@p/...) instead of player names:
        // do not offer player-name completion inside a command.
        if (atIdx >= 0 && !text.startsWith("/") && client.player != null && client.player.networkHandler != null) {
            String after = text.substring(atIdx + 1);
            if (!after.contains(" ")) {
                mentionFilter = after.toLowerCase();
                mentionCandidates.clear();
                for (var info : client.player.networkHandler.getPlayerList()) {
                    String name = info.getProfile().name();
                    if (name.toLowerCase().contains(mentionFilter))
                        mentionCandidates.add(name);
                }
                mentionCandidates.sort(String::compareToIgnoreCase);
                mentionIdx = 0;
                showMentions = !mentionCandidates.isEmpty();
            }
        }
        if (commandSuggestions != null) {
            commandSuggestions.refresh();
        }
        // IMBlocker listens to vanilla ChatScreen.onChatFieldUpdate, which we
        // bypass; mirror its command-detection hook so the IME still switches
        // to English while typing a command. No-op when IMBlocker is absent.
        IMBlockerCompat.setCommandMode(chatField, text.startsWith("/"));
    }

    private void onSearchEdited(String text) {
        searchMatches.clear();
        searchMatchIdx = -1;
        searchHighlightIndex = -1;
        if (text.isEmpty()) return;
        String lower = text.toLowerCase();
        var msgs = ChatMessageStore.getMessages();
        for (int i = 0; i < msgs.size(); i++) {
            var msg = msgs.get(i);
            if (msg == null) continue;
            if (msg.content().getString().toLowerCase().contains(lower)
                || (msg.senderName() != null && msg.senderName().getString().toLowerCase().contains(lower)))
                searchMatches.add(i);
        }
        if (!searchMatches.isEmpty()) {
            searchMatchIdx = 0;
            searchHighlightIndex = searchMatches.get(0);
            jumpToMessage(searchHighlightIndex);
        }
    }

    @Override
    public void tick() {
        if (copyToastTicks > 0) copyToastTicks--;
        if (uploadToastTicks > 0) uploadToastTicks--;
        finishPopupClose(settingsCloseStart, () -> { settingsCloseStart = 0; settingsMenu.visible = false; });
        finishPopupClose(emojiCloseStart, () -> { emojiCloseStart = 0; emojiPanel.visible = false; });
        finishPopupClose(quickCloseStart, () -> {
            quickCloseStart = 0;
            quickChatPanel.visible = false;
            quickChatInput.setVisible(false);
        });
        finishPopupClose(searchCloseStart, () -> {
            searchCloseStart = 0;
            searchPanel.visible = false;
            searchInput.setVisible(false);
        });
        if (closing && Util.getMeasuringTimeMs() - animStart >= ANIM_MS)
            client.setScreen(null);
    }

    @Override
    public void renderBackground(DrawContext g, int mouseX, int mouseY, float delta) {
        // no-op: disable vanilla blur
    }

    private float getAnimProgress() {
        if (!ChatBubbleClientSetup.config().animationEnabled()) return 1.0f;
        AnimationStyle style = AnimationStyle.parse(ChatBubbleClientSetup.config().panelAnimStyle());
        if (style == AnimationStyle.NONE) return 1.0f;
        long elapsed = Util.getMeasuringTimeMs() - animStart;
        float t = MathHelper.clamp((float) elapsed / ANIM_MS, 0f, 1f);
        if (closing) return 1.0f - (t * t);
        return Animation.styleCurve(style, t);
    }

    // 上下栏背景透明度：跟面板开合动画同步（150ms），但用线性曲线—  // easeOutCubic  ?75ms 就到 87% 不透明，观 ?瞬间出现 ?.3.13 用户反馈）
private float getBarAlpha() {
        if (!ChatBubbleClientSetup.config().animationEnabled()) return 1.0f;
        AnimationStyle style = AnimationStyle.parse(ChatBubbleClientSetup.config().panelAnimStyle());
        if (style == AnimationStyle.NONE) return 1.0f;
        long elapsed = Util.getMeasuringTimeMs() - animStart;
        float t = MathHelper.clamp((float) elapsed / ANIM_MS, 0f, 1f);
        if (closing) return 1.0f - t;
        return t;
    }

    // Popup open animation (opening only  ?closing stays instant). The panel
    // renders itself with the given alpha (per-element fade); ZOOM additionally
    // scales it in around the screen center with overshoot.
    // Popup open/close animation (D07-6: closing is no longer instant).
    // Open: 200ms style curve (ease-out); close: 150ms ease-in fade + style displacement.
    private void renderPopupWithAnim(DrawContext g, long openStartMs, long closeStartMs,
                                     java.util.function.Function<Float, Runnable> renderer) {
        AnimationStyle style = AnimationStyle.parse(ChatBubbleClientSetup.config().popupAnimStyle());
        float alpha = 1f;
        float t = 1f;
        if (closeStartMs > 0) {
            // Closing: ease-in fade-out (07 §2.6: exit 150ms ease-in)
            float tc = MathHelper.clamp((float) (Util.getMeasuringTimeMs() - closeStartMs) / UiTokens.POPUP_CLOSE_MS, 0f, 1f);
            float a = (1f - tc) * (1f - tc);
            Runnable render = renderer.apply(a);
            if (style == AnimationStyle.ZOOM) {
                g.getMatrices().pushMatrix();
                float s = 0.85f + 0.15f * a;
                g.getMatrices().translate(width / 2f, height / 2f);
                g.getMatrices().scale(s, s);
                g.getMatrices().translate(-width / 2f, -height / 2f);
                render.run();
                g.getMatrices().popMatrix();
            } else if (style == AnimationStyle.SLIDE) {
                // 与打开同向位移：打开从下往上滑入，关闭向下滑出
                g.getMatrices().pushMatrix();
                g.getMatrices().translate(0, (1f - a) * 10f);
                render.run();
                g.getMatrices().popMatrix();
            } else {
                render.run();
            }
            return;
        }
        if (ChatBubbleClientSetup.config().animationEnabled() && style != AnimationStyle.NONE) {
            t = MathHelper.clamp((float) (Util.getMeasuringTimeMs() - openStartMs) / UiTokens.POPUP_OPEN_MS, 0f, 1f);
            alpha = Animation.styleCurve(style, t);
        }
        Runnable render = renderer.apply(alpha);
        if (t >= 1f || style == AnimationStyle.NONE) { render.run(); return; }
        if (style == AnimationStyle.ZOOM) {
            g.getMatrices().pushMatrix();
            float s = 0.85f + 0.15f * Animation.easeOutBack(alpha);
            g.getMatrices().translate(width / 2f, height / 2f);
            g.getMatrices().scale(s, s);
            g.getMatrices().translate(-width / 2f, -height / 2f);
            render.run();
            g.getMatrices().popMatrix();
        } else if (style == AnimationStyle.SLIDE) {
            // SLIDE: rise up from below while fading in
            g.getMatrices().pushMatrix();
            g.getMatrices().translate(0, (1f - alpha) * 10f);
            render.run();
            g.getMatrices().popMatrix();
        } else {
            render.run();
        }
    }

    /** 开始弹层关闭动画（D07-6）：动画 ?风格 NONE 时立即隐藏，否则 150ms 后由 tick 隐藏 ?*/
    private void beginPopupClose(java.util.function.LongConsumer setCloseStart, Runnable hide) {
        if (!ChatBubbleClientSetup.config().animationEnabled()
                || AnimationStyle.parse(ChatBubbleClientSetup.config().popupAnimStyle()) == AnimationStyle.NONE) {
            setCloseStart.accept(0);
            hide.run();
            return;
        }
        setCloseStart.accept(Util.getMeasuringTimeMs());
    }

    /** tick 调用：关闭动画到期后真正隐藏（D07-6） ?*/
    private void finishPopupClose(long closeStart, Runnable hide) {
        if (closeStart > 0 && Util.getMeasuringTimeMs() - closeStart >= UiTokens.POPUP_CLOSE_MS) {
            hide.run();
        }
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int keyCode = input.key();
        int scanCode = input.scancode();
        int modifiers = input.modifiers();
        // Ctrl+C with a message-text selection copies the selected runs (one per
        // selected span, joined with '\n') and reuses the existing "copied" toast.
        // 输入框自己有选区时让给输入框（复制输入框内选中的文本），否则复制消息选区。
        // 原判断是「输入框没聚焦」，可本屏 init 里就 setFocused(chatField)，于是选中消息
        // 文本后 Ctrl+C 会被输入框那边吃掉 —— 系统消息没有气泡、只能靠选区复制，表现就是
        // "能选中（有高亮）但复制不出来"。改成只看输入框有没有可复制的选区，
        // 输入框里真的选中了文字时仍然优先复制输入框，原有行为不变。
        boolean fieldCopiesFirst = chatField.isFocused() && !chatField.getSelectedText().isEmpty();
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_C && (modifiers & 0x2) != 0
                && !fieldCopiesFirst && textSelection.hasSelection()) {
            String copied = textSelection.copyText(textSpans);
            if (copied.isEmpty()) {
                // 兜底：选区没有落进任何 span（例如某条消息的行没注册 span）时，
                // 复制选区起点那条消息的完整正文 —— 至少不会出现「明明选中了却什么都没复制」。
                ChatMessageStore.ChatMessage anchor = ChatMessageStore.getMessageAt(textSelection.anchorMessage());
                if (anchor != null && anchor.content() != null) copied = anchor.content().getString();
            }
            if (!copied.isEmpty()) {
                client.keyboard.setClipboard(copied);
                copyToastTicks = 30;
            }
            final String copyResult = copied;
            ChatMessageStore.debugLog(() -> "[e33chat] COPY | spans=" + textSpans.size()
                + " | chars=" + copyResult.length() + " | fieldFocused=" + chatField.isFocused());
            return true;
        }
        // Ctrl+V with an image in the clipboard uploads it and inserts the code;
        // on the custom-emote tab it adds the image to the emote pack instead.
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_V && (modifiers & 0x2) != 0) {
            if (emojiPanel.visible && emojiPanel.tab == 2) {
                addClipboardEmote();
            } else {
                startUploadFromClipboard();
            }
        }
        if (settingsMenu.visible && keyCode == 256) {
            beginPopupClose(s -> settingsCloseStart = s, () -> settingsMenu.visible = false);
            return true;
        }
        if (emojiPanel.visible && keyCode == 256) {
            beginPopupClose(s -> emojiCloseStart = s, () -> emojiPanel.visible = false);
            return true;
        }
        if (quickChatPanel.visible && keyCode == 256) {
            beginPopupClose(s -> quickCloseStart = s, () -> {
                quickChatPanel.visible = false;
                quickChatInput.setVisible(false);
            });
            setFocused(chatField);
            return true;
        }
        if (searchPanel.visible && keyCode == 256) { closeSearchPanel(); return true; }

        if (searchPanel.visible && !searchMatches.isEmpty()) {
            if (keyCode == 265) {
                searchMatchIdx = searchMatchIdx > 0 ? searchMatchIdx - 1 : searchMatches.size() - 1;
                searchHighlightIndex = searchMatches.get(searchMatchIdx);
                jumpToMessage(searchHighlightIndex); return true;
            }
            if (keyCode == 264) {
                searchMatchIdx = searchMatchIdx < searchMatches.size() - 1 ? searchMatchIdx + 1 : 0;
                searchHighlightIndex = searchMatches.get(searchMatchIdx);
                jumpToMessage(searchHighlightIndex); return true;
            }
            if (keyCode == 257 || keyCode == 335) { closeSearchPanel(); return true; }
        }

        if (sidebarSearchBox.isFocused()) {
            if (keyCode == 256 || keyCode == 257 || keyCode == 335) {
                sidebarSearchBox.setFocused(false); setFocused(chatField); return true;
            }
        }

        if (showMentions) {
            if (keyCode == 258) { insertMention(mentionCandidates.get(mentionIdx)); return true; }
            if (keyCode == 256) { showMentions = false; mentionNavigated = false; return true; }
            if (keyCode == 265) { mentionIdx = mentionIdx > 0 ? mentionIdx - 1 : mentionCandidates.size() - 1; mentionNavigated = true; return true; }
            if (keyCode == 264) { mentionIdx = mentionIdx < mentionCandidates.size() - 1 ? mentionIdx + 1 : 0; mentionNavigated = true; return true; }
            if (keyCode == 257 || keyCode == 335) {
                // Only apply the highlighted candidate when the player actually
                // navigated it (arrow keys); otherwise Enter just sends the text.
                if (mentionNavigated) { insertMention(mentionCandidates.get(mentionIdx)); return true; }
            }
        }

        if (commandSuggestions != null && commandSuggestions.keyPressed(input))
            return true;
        if (keyCode == 256) { onClose(); return true; }
        if (quickChatInput.isFocused() && (keyCode == 257 || keyCode == 335)) {
            String text = quickChatInput.getText().trim();
            if (!text.isEmpty()) {
                var phrases = new ArrayList<>(ChatBubbleClientSetup.config().quickChatPhrases());
                phrases.add(text);
                ChatBubbleClientSetup.saveConfig(ChatBubbleClientSetup.config().withQuickChatPhrases(phrases));
                quickChatInput.setText("");
            }
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            sendMessage(); return true;
        }
        if (keyCode == 265 && this.getFocused() == chatField) { setChatFromHistory(-1); return true; }
        if (keyCode == 264 && this.getFocused() == chatField) { setChatFromHistory(1); return true; }

        // 不调 super.keyPressed ? ChatScreen，内部访 ?package-private chatInputSuggestor = null  ?NPE）
// self 实现 Screen.keyPressed 等价分发：先 ?focused widget（chatField TextFieldWidget 处理
        // backspace/删除/左右/Home/End/Ctrl+A/C/V/X）， ?Tab/箭头焦点导航
if (this.getFocused() != null && this.getFocused().keyPressed(input))
            return true;
        net.minecraft.client.gui.navigation.GuiNavigation nav = switch (keyCode) {
            case 258 -> new net.minecraft.client.gui.navigation.GuiNavigation.Tab((input.modifiers() & 0x2) != 0);
            case 262 -> new net.minecraft.client.gui.navigation.GuiNavigation.Arrow(net.minecraft.client.gui.navigation.NavigationDirection.RIGHT);
            case 263 -> new net.minecraft.client.gui.navigation.GuiNavigation.Arrow(net.minecraft.client.gui.navigation.NavigationDirection.LEFT);
            case 264 -> new net.minecraft.client.gui.navigation.GuiNavigation.Arrow(net.minecraft.client.gui.navigation.NavigationDirection.DOWN);
            case 265 -> new net.minecraft.client.gui.navigation.GuiNavigation.Arrow(net.minecraft.client.gui.navigation.NavigationDirection.UP);
            default -> null;
        };
        if (nav != null) {
            net.minecraft.client.gui.navigation.GuiNavigationPath path = super.getNavigationPath(nav);
            if (path == null && nav instanceof net.minecraft.client.gui.navigation.GuiNavigation.Tab) {
                this.blur();
                path = super.getNavigationPath(nav);
            }
            if (path != null) this.switchFocus(path);
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (emojiPanel.visible) { emojiPanel.handleScroll(scrollY); return true; }
        if (quickChatPanel.visible) { quickChatPanel.handleScroll(scrollY); return true; }
        if (searchPanel.visible && !searchMatches.isEmpty()) {
            searchMatchIdx = MathHelper.clamp(searchMatchIdx - (int) scrollY, 0, searchMatches.size() - 1);
            searchHighlightIndex = searchMatches.get(searchMatchIdx);
            jumpToMessage(searchHighlightIndex); return true;
        }
        if (showMentions && !mentionCandidates.isEmpty()) {
            mentionIdx = MathHelper.clamp(mentionIdx - (int) scrollY, 0, mentionCandidates.size() - 1);
            mentionNavigated = true;
            return true;
        }
        int sidebarX = getSidebarScreenX();
        if ((sidebarOpen || sidebarAnimating) && mouseX >= sidebarX && mouseX <= sidebarX + SIDEBAR_W) {
            // 先用真实的当前行数刷新上限，避免拿上一帧的旧上限滚出越界偏移。
            clampSidebarScroll();
            sidebarScrollOffset = MathHelper.clamp(sidebarScrollOffset - (int) (scrollY * 20), 0, sidebarMaxScroll);
            return true;
        }
        if (commandSuggestions != null && commandSuggestions.mouseScrolled(scrollY)) return true;
        scrollToBottom = false;
        lastScrollTime = Util.getMeasuringTimeMs();
        float newTarget = MathHelper.clamp(scrollOffset - (int) (scrollY * 40), 0, maxScroll);
        scrollAnimFrom = scrollOffset;
        scrollAnimTo = newTarget;
        scrollAnimStart = Util.getMeasuringTimeMs();
        if (!scrollAnimActive) { scrollAnimDuration = 120; scrollAnimActive = true; }
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        // Panel contents are translated by panelOffset during the open/close slide;
        // undo the shift here so hit-testing matches what is drawn. The sidebar and
        // EditBox render outside that translate (they set their own x), so they keep
        // the original coordinate.
        double origX = mouseX;
        if (isPanelSliding()) mouseX -= currentPanelOffset();

        // @mention popup click
        if (showMentions && button == 0) {
            int popupX = chatField.getX();
            int popupH = Math.min(mentionCandidates.size(), 8) * textRenderer.fontHeight + 4;
            int popupY = chatField.getY() - popupH - 2;
            if (popupY < msgTop) popupY = chatField.getY() + chatField.getHeight() + 2;
            int maxW = 60;
            for (String name : mentionCandidates) maxW = Math.max(maxW, textRenderer.getWidth(name));
            int popupW = maxW + 12;
            if (mouseX >= popupX && mouseX <= popupX + popupW && mouseY >= popupY && mouseY <= popupY + popupH) {
                int relY = (int) mouseY - popupY - 2;
                int idx = relY / textRenderer.fontHeight;
                int startIdx = Math.max(0, mentionIdx - Math.min(mentionCandidates.size(), 8) + 1);
                idx += startIdx;
                if (idx >= 0 && idx < mentionCandidates.size()) {
                    insertMention(mentionCandidates.get(idx)); return true;
                }
            }
        }

        // Sidebar clicks
        int sidebarX = getSidebarScreenX();
        if ((sidebarOpen || sidebarAnimating) && button == 0 && origX >= sidebarX && origX <= sidebarX + SIDEBAR_W) {
            int searchY = 2;
            int searchH = SIDEBAR_SEARCH_H;
            if (mouseY >= searchY && mouseY <= searchY + searchH) {
                setFocused(sidebarSearchBox); chatField.setFocused(false); return true;
            }
            if (sidebarSearchBox.isFocused()) setFocused(chatField);

            int y2 = searchY + searchH + 3;
            if (mouseY >= y2 && mouseY <= y2 + SIDEBAR_ITEM_H) {
                whisperPartner = null; beginConversationSwitch(); setFocused(chatField); scrollToBottom = true; return true;
            }
            y2 += SIDEBAR_ITEM_H + 2;
            // 命中测试必须用与绘制同一套夹紧后的偏移：列表行数变了以后旧 offset 可能越界，
            // 不夹就会点到"看起来在别处"的那一行。
            clampSidebarScroll();
            if (client.player != null && client.player.networkHandler != null) {
                var players = new ArrayList<>(client.player.networkHandler.getPlayerList());
                String selfName = client.player.getName().getString();
                String filter = sidebarSearchBox.getText().toLowerCase().trim();
                int scrollY = y2 - sidebarScrollOffset;
                // 状态行所在的底部一条不参与行命中：列表被 scissor 在它上方截断后，
                // 下半截被裁掉的那一行不该还能点中。
                int listBottom = sidebarListBottom();
                for (var info : players) {
                    String name = info.getProfile().name();
                    if (name.equals(selfName)) continue;
                    if (!filter.isEmpty() && !name.toLowerCase().contains(filter)) continue;
                    // 与 renderSidebar 保持一致：被隐藏规则过滤掉的玩家不占行，
                    // 否则点击行号会和可见行错位（点到别人、会话"跳转"到另一个人）。
                    if (ChatBubbleClientSetup.config().isSidebarHidden(name)) continue;
                    if (mouseY >= scrollY && mouseY <= scrollY + SIDEBAR_ITEM_H && mouseY < listBottom) {
                        whisperPartner = name;
                        ChatMessageStore.clearUnreadWhisper(name);
                        beginConversationSwitch(); setFocused(chatField); scrollToBottom = true; return true;
                    }
                    scrollY += SIDEBAR_ITEM_H + 2;
                }
            }
        }

        if (button == 0 && contextAvatarIndex >= 0) { handleAvatarContextClick((int) mouseX, (int) mouseY); return true; }
        if (contextAvatarIndex >= 0) { contextAvatarIndex = -1; return true; }
        if (button == 0 && contextMsgIndex >= 0) { handleContextClick((int) mouseX, (int) mouseY); return true; }
        if (contextMsgIndex >= 0) { contextMsgIndex = -1; return true; }

        // 免打扰月亮按钮只剩标题栏左上角那一个（汉堡右侧），按当前视图切换作用对象：
        // 公屏视图 → 公屏 DND，私聊视图 → 该私聊对象的 DND（见 toggleDndForCurrentView）。
        // 它的命中在下面「button == 0」段里判（与汉堡同一优先级段），这里不再单独处理
        // 私聊标题条上的按钮——那个按钮已经删掉，滚动条命中优先级保持原样。

        // Notification bar clicks
        if (button == 0 && newMessageCount > 0) {
            if (mouseX >= notifCountLeft && mouseX <= notifCountRight
                && mouseY >= notifBarTextY && mouseY <= notifBarTextY + textRenderer.fontHeight) {
                scrollToBottom = true; newMessageCount = 0; hasNewMentionOrQuote = false;
                latestMentionIndex = -1; lastSeenMessageCount = ChatMessageStore.getMessages().size(); return true;
            }
            if (hasNewMentionOrQuote && notifMentionLeft >= 0
                && mouseX >= notifMentionLeft && mouseX <= notifMentionRight
                && mouseY >= notifBarTextY && mouseY <= notifBarTextY + textRenderer.fontHeight) {
                jumpToMessage(latestMentionIndex); return true;
            }
        }

        if (button == 0 && replyTargetIndex >= 0 && isMouseOverReplyCancel(mouseX, mouseY)) {
            replyTargetIndex = -1; return true;
        }

        // Scrollbar interaction
        if (button == 0 && maxScroll > 0) {
            int trackX = panelX + panelW - SCROLLBAR_WIDTH;
            int effBottom = newMessageCount > 0 ? barTop - NOTIF_H - 1 : msgBottom;
            if (mouseX >= trackX && mouseX < trackX + SCROLLBAR_WIDTH
                && mouseY >= msgTop && mouseY < effBottom) {
                int trackH = effBottom - msgTop;
                int thumbH = Math.max(MIN_THUMB_H, (int) ((long) trackH * trackH / messageTotalH));
                thumbH = Math.min(thumbH, trackH);
                int travelRange = trackH - thumbH;
                int thumbY = msgTop + (int) ((long) scrollOffset * travelRange / maxScroll);
                if (mouseY < thumbY) { scrollOffset = Math.max(0, scrollOffset - trackH); }
                else if (mouseY > thumbY + thumbH) { scrollOffset = Math.min(maxScroll, scrollOffset + trackH); }
                else { scrollbarDragging = true; scrollbarDragStartY = (int) mouseY; scrollbarDragStartOffset = scrollOffset; }
                scrollToBottom = false; return true;
            }
        }

        if (commandSuggestions != null && commandSuggestions.mouseClicked(click))
            return true;

        if (button == 0) {
            if (isMouseOverDnd(mouseX, mouseY)) { toggleDndForCurrentView(); return true; }
            if (isMouseOverHamburger(mouseX, mouseY)) {
                if (!ChatBubbleClientSetup.config().animationEnabled()) {
                    sidebarOpen = !sidebarOpen; sidebarAnimating = false;
                    panelX = sidebarOpen ? SIDEBAR_W : 0;
                    sidebarSearchBox.setX(2); sidebarSearchBox.setVisible(sidebarOpen);
                    if (!sidebarOpen && sidebarSearchBox.isFocused()) setFocused(chatField);
                    rebuildLayout();
                } else if (sidebarAnimating) {
                    sidebarTargetOpen = !sidebarTargetOpen;
                    long elapsed = Util.getMeasuringTimeMs() - sidebarAnimStart;
                    float currentT = MathHelper.clamp((float) elapsed / ANIM_MS, 0f, 1f);
                    sidebarAnimStart = Util.getMeasuringTimeMs() - (long) ((1.0f - currentT) * ANIM_MS);
                } else {
                    sidebarTargetOpen = !sidebarOpen; sidebarAnimating = true;
                    sidebarAnimStart = Util.getMeasuringTimeMs();
                }
                return true;
            }
            if (mouseX >= panelX + panelW - 18 && mouseX <= panelX + panelW - 6
                && mouseY >= titleY + 6 && mouseY <= titleY + 18) { onClose(); return true; }
            if (settingsMenu.visible) {
                int action = settingsMenu.handleClick((int) mouseX, (int) mouseY, panelX, panelW, barTop, ICON_S);
                if (action >= 0) executeMenuAction(action);
                return true;
            }
            if (emojiPanel.visible) {
                String emojiText = emojiPanel.handleClick((int) mouseX, (int) mouseY, textRenderer, c(), panelX, panelW, barTop, ICON_S, PAD);
                if (emojiText != null && !emojiText.isEmpty()) {
                    if (emojiText.startsWith("@EMOTE:")) {
                        java.io.File f = new java.io.File(emojiText.substring(7));
                        if (f.isFile()) {
                            beginPopupClose(s -> emojiCloseStart = s, () -> emojiPanel.visible = false);
                            uploadQueue.enqueue(new com.niuqu.chatbubble.image.UploadQueue.UploadJob(f, null, null, true, null));
                        }
                    } else if (emojiText.startsWith("@EMOTE_DEL:")) {
                        java.io.File f = new java.io.File(emojiText.substring(11));
                        if (f.isFile()) EmoteStore.remove(f);
                    } else if (emojiText.equals("@EMOTE_ADD")) {
                        NativeFileDialog.pickImage(f -> {
                            if (f == null || !f.isFile()) return;
                            if (EmoteStore.isFull()) return;
                            EmoteStore.add(f);
                        });
                    } else {
                        chatField.write(emojiText);
                    }
                }
                return true;
            }
            if (quickChatPanel.visible) {
                // 输入框聚焦不依赖 widget 点击命中链路 ?.21.1/yarn TextFieldWidget 点击不自动聚焦）
// 直接几何判定命中就聚焦，覆盖所有情
if (ChatQuickChatPanel.isInsideInput((int) mouseX, (int) mouseY, panelX, panelW, barTop,
                        ChatBubbleClientSetup.config().quickChatPhrases().size())) {
                    //  ?sidebar 搜索框聚焦同款（Fabric 实测需显式失焦主输入框，否则焦点链 ?chatField 占用
quickChatInput.setVisible(true);
                    setFocused(quickChatInput);
                    chatField.setFocused(false);
                    return true;
                }
                int result = quickChatPanel.handleClick((int) mouseX, (int) mouseY, textRenderer, c(), panelX, panelW, barTop, quickChatInput);
                if (result >= 0) {
                    chatField.setText(ChatBubbleClientSetup.config().quickChatPhrases().get(result));
                    setFocused(chatField);
                } else if (result == -2) {
                    setFocused(quickChatInput);
                }
                return true;
            }
            if (searchPanel.visible) {
                if (searchPanel.isClickOnPanel((int) mouseX, (int) mouseY, panelX, panelW, barTop)) {
                    setFocused(searchInput); return true;
                }
                closeSearchPanel(); return true;
            }
            if (mouseY >= barTop) {
                if (handleIconClick((int) mouseX, (int) mouseY)) return true;
            }
        }

        // Avatar click for @mention
        if (button == 0) {
            for (int[] r : bubbleRects) {
                ChatMessageStore.ChatMessage msg = ChatMessageStore.getMessageAt(r[4]);
                if (msg == null || msg.isSystem()) continue;
                int avatarX = msg.isOwn() ? r[0] + r[2] + 4 : r[0] - Appearance.avatarSize() - 4;
                int avatarY = msg.replyContent() != null ? r[1] - textRenderer.fontHeight - 2 : r[1] - NAME_H;
                if (mouseX >= avatarX && mouseX <= avatarX + Appearance.avatarSize()
                    && mouseY >= avatarY && mouseY <= avatarY + Appearance.avatarSize()) {
                    String mentionName = (msg.rawPlayerName() != null && !msg.rawPlayerName().isEmpty())
                        ? msg.rawPlayerName() : msg.senderName().getString();
                    chatField.setText(chatField.getText() + "@" + mentionName + " ");
                    chatField.setCursorToEnd(false);
                    return true;
                }
            }
        }

        // Avatar right-click context menu
        if (button == 1) {
            for (int[] r : bubbleRects) {
                ChatMessageStore.ChatMessage msg = ChatMessageStore.getMessageAt(r[4]);
                if (msg == null || msg.isSystem() || msg.isOwn()) continue;
                if (msg.rawPlayerName() == null || msg.rawPlayerName().isEmpty()) continue;
                int avatarX = r[0] - Appearance.avatarSize() - 4;
                int avatarY = msg.replyContent() != null ? r[1] - textRenderer.fontHeight - 2 : r[1] - NAME_H;
                if (mouseX >= avatarX && mouseX <= avatarX + Appearance.avatarSize()
                    && mouseY >= avatarY && mouseY <= avatarY + Appearance.avatarSize()) {
                    contextAvatarIndex = r[4]; contextAvatarX = (int) mouseX; contextAvatarY = (int) mouseY;
                    return true;
                }
            }
        }

        // Bubble right-click
        if (button == 1) {
            for (int[] r : bubbleRects) {
                if (mouseX >= r[0] && mouseX <= r[0] + r[2]
                    && mouseY >= r[1] && mouseY <= r[1] + r[3]) {
                    contextMsgIndex = r[4]; contextX = (int) mouseX; contextY = (int) mouseY;
                    return true;
                }
            }
        }

        // 引用块跳转只认双击：单击不跳（保持选字/无操作），拖选经过引用块也不跳。
        // MC 把同一按键的快速第二击标成 doubleClick=true；第一击仍走下面的选区按下逻辑
        // 被吞掉，第二击在这里只跳一次（释放路径不再派发引用跳转，所以不会双跳）。
        if (button == 0 && doubleClick) {
            int owner = quoteOwnerAt(mouseX, mouseY);
            final double dbgX = mouseX, dbgY = mouseY;
            quoteJumpLog(() -> "[e33chat] QUOTEJUMP | DBLCLICK | raw=(" + click.x() + "," + click.y()
                + ") local=(" + dbgX + "," + dbgY + ") owner=" + owner
                + " rects=" + quoteRects.size());
            if (owner >= 0) {
                // 清掉第一击可能留下的选区与待派发状态，避免跳转后残留选区 / 二次派发
                quotePressPending = false;
                textSelection.clear();
                handleQuoteClick(mouseX, mouseY);
                return true;
            }
        }

        // Text selection: a left press that lands on rendered message text starts a
        // selection and swallows the click. The link dispatch itself is deferred to
        // mouseReleased (see handleClickableTextClick) so that it only fires when the
        // press did not drag  ?otherwise drag-selecting across a link would also
        // activate the link on press.
        if (button == 0) {
            // 消息 textSelection 只在消息区按下时才激活：底栏（输入框等）里按下不能开始消息选区
// 否则 ?scissor 裁掉 ?rect 仍被记录的消 ?span 会抢走输入框的按 ?拖选
TextSpan hit = (mouseY >= msgTop && mouseY <= msgBottom) ? findTextSpanAt(mouseX, mouseY) : null;
            if (hit != null) {
                if (textSelection.hasSelection()) textSelection.clear();
                textSelection.begin(hit.messageIndex(), hit.lineIndex(), hit.kind(), charAt(hit, mouseX));
                selectionStartX = mouseX;
                selectionStartY = mouseY;
                return true;
            }
            if (textSelection.hasSelection() || textSelection.isDragActive()) textSelection.clear();
            // 引用块（文本 span 之外的内边距也算）：按下同样吞掉这次点击，避免顺手触发
            // 链接/命令；真正的跳转只由上面的 doubleClick 分支触发（单击不跳）
            quotePressPending = beginQuotePress(mouseX, mouseY);
            final double dbgLocalX = mouseX, dbgLocalY = mouseY;
            final boolean dbgHit = quotePressPending;
            quoteJumpLog(() -> "[e33chat] QUOTEJUMP | PRESS | raw=(" + click.x() + "," + click.y()
                + ") local=(" + dbgLocalX + "," + dbgLocalY + ") sliding=" + isPanelSliding()
                + " panelOffset=" + currentPanelOffset() + " rects=" + quoteRects.size()
                + " hit=" + dbgHit);
            if (quotePressPending) return true;
        }

        // Clickable text (also reached from mouseReleased for a non-dragged press on text)
        if (button == 0 && handleClickableTextClick(mouseX, mouseY)) return true;
        // 输入框：本屏自己分发点击，必须补上原 ?ParentElement.mouseClicked 的记
// （setFocused(命中的子控件) + setDragging(true)）。漏了这两步 ?screen.isDragging()
        // 恒为 false  ?ParentElement.mouseDragged 直接 return  ?TextFieldWidget 永远收不
// mouseDragged  ?鼠标拖选（以及依赖选区的复 ?粘贴/整段删除）全部失效
boolean inputHandled = this.chatField.mouseClicked(click, doubleClick);
        if (inputHandled && button == 0) {
            setFocused(chatField);
            setDragging(true);
        }
        return inputHandled;
    }

    /** Dispatches the ClickEvent of the hovered clickable span. Shared by mouseClicked
     * (clicks that miss text) and mouseReleased (press-on-text that did not drag). */
    private boolean handleClickableTextClick(double mouseX, double mouseY) {
        // 引用块不在单击/释放路径里派发：跳转只认 mouseClicked(doubleClick=true)。
        // 单击引用文字保持原有用途（选字/无操作），拖选经过引用块也绝不跳转。
        quoteJumpLog(() -> "[e33chat] QUOTEJUMP | TEXTCLICK | local=(" + mouseX + "," + mouseY
            + ") rects=" + quoteRects.size());
        Style style = getHoveredStyle(mouseX, mouseY);
        if (style == null || style.getClickEvent() == null) return false;
        ClickEvent cev = style.getClickEvent();
        if (cev instanceof ClickEvent.SuggestCommand sc) {
            chatField.setText(sc.command()); return true;
        }
        if (cev instanceof ClickEvent.OpenFile of) {
            Util.getOperatingSystem().open(of.file()); return true;
        }
        if (cev instanceof ClickEvent.OpenUrl ou) {
            // Local file:// links (e.g. legacy chatimage messages) are not
            // browser URLs; opening them throws URISyntaxException. Only
            // hand http(s) to the browser.
            String clickUrl = ou.uri() != null ? ou.uri().toString() : null;
            if (clickUrl != null && (clickUrl.startsWith("http://") || clickUrl.startsWith("https://"))) {
                // Vanilla semantics: clicking a link OPENS it. This used to push the URL
                // into chat as a message instead, so a link click sent it to everyone.
                Util.getOperatingSystem().open(clickUrl);
            }
            return true;
        }
        if (cev instanceof ClickEvent.RunCommand rc) {
            if (client.player != null && client.player.networkHandler != null) {
                String cmd = rc.command();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                client.player.networkHandler.sendChatCommand(cmd);
            }
            return true;
        }
        if (cev instanceof ClickEvent.CopyToClipboard cc) {
            client.keyboard.setClipboard(cc.value()); return true;
        }
        // 未识别的 ClickEvent 类型（1.21.11 的 ClickEvent.Custom / ShowDialog / ChangePage，
        // 以及以后新增的类型）交回原版分发，不要再“吞掉但不执行”。
        // 原版入口：Screen.handleClickEvent(ClickEvent, MinecraftClient, Screen)，1.21.11 起
        // 由 Screen 统一分派（protected static，本类是 Screen 子类，可直接调用）：
        //   RunCommand  → handleRunCommand(...)
        //   ShowDialog  → player.networkHandler.showDialog(dialog, this)
        //   Custom      → player.networkHandler.sendPacket(new CustomClickActionC2SPacket(id, payload))
        //                 即原版的自定义载荷通道，插件服自带的「点击打开签到菜单」走的就是它
        //   其余（ChangePage 等）→ handleBasicClickEvent(...)
        // 注：handleClickEvent 第一句就是 requireNonNull(mc.player)，所以先确认玩家存在。
        if (client.player != null) {
            Screen.handleClickEvent(cev, client, this);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(Click click, double dragX, double dragY) {
        // 引用块上按下之后又拖动了 = 不是一次点击：清掉待派发标记（拖选经过引用块绝不跳转）
if (quotePressPending
            && Math.abs(click.x() - quotePressX) + Math.abs(click.y() - quotePressY) > 3.0) {
            quotePressPending = false;
        }
        // Selection drag first (a press either started a selection or the scrollbar
        // drag, never both  ?the scrollbar hit-test in mouseClicked returns earlier).
        if (textSelection.isDragActive()) {
            double mouseY = click.y();
            double mx = click.x();
            if (isPanelSliding()) mx -= currentPanelOffset();
            TextSpan hit = findTextSpanAt(mx, mouseY);
            if (hit == null) {
                // Off the text: keep extending with the nearest span so a sloppy drag
                // still grows the selection instead of freezing it.
                textSelection.markMoved();
                hit = findNearestTextSpan(mx, mouseY);
            } else if (Math.abs(mx - selectionStartX) + Math.abs(mouseY - selectionStartY) > 3.0) {
                textSelection.markMoved();
            }
            if (hit != null) {
                textSelection.update(hit.messageIndex(), hit.lineIndex(), hit.kind(), charAt(hit, mx));
            }
            autoScrollSelection(mouseY);
            return true;
        }
        if (scrollbarDragging && maxScroll > 0) {
            if (textSelection.hasSelection()) textSelection.clear();
            lastScrollTime = Util.getMeasuringTimeMs();
            int effBottom = newMessageCount > 0 ? barTop - NOTIF_H - 1 : msgBottom;
            int trackH = effBottom - msgTop;
            int thumbH = Math.max(MIN_THUMB_H, (int) ((long) trackH * trackH / messageTotalH));
            thumbH = Math.min(thumbH, trackH);
            int travelRange = trackH - thumbH;
            if (travelRange > 0) {
                int dy = (int) click.y() - scrollbarDragStartY;
                float newTarget = MathHelper.clamp(scrollbarDragStartOffset + (int) ((long) dy * maxScroll / travelRange), 0, maxScroll);
                scrollAnimFrom = scrollOffset; scrollAnimTo = newTarget;
                scrollAnimStart = Util.getMeasuringTimeMs();
                if (!scrollAnimActive) { scrollAnimDuration = 80; scrollAnimActive = true; }
            }
            return true;
        }
        return super.mouseDragged(click, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        // 每一路都留一 ?QUOTEJUMP 痕迹，用来区分「释放路径没走到」还是「走到了但没命中
boolean selDrag = textSelection.isDragActive();
        boolean selMoved = textSelection.didMove();
        quoteJumpLog(() -> "[e33chat] QUOTEJUMP | RELEASE | raw=(" + click.x() + "," + click.y()
            + ") selDrag=" + selDrag + " selMoved=" + selMoved
            + " pending=" + quotePressPending
            + " sliding=" + isPanelSliding() + " panelOffset=" + currentPanelOffset()
            + " rects=" + quoteRects.size());
        if (textSelection.isDragActive()) {
            textSelection.endDrag();
            if (!textSelection.didMove()) {
                // Press without drag: this was a click on text, so deliver the
                // deferred link dispatch now (the press swallowed it).
                double mx = click.x();
                if (isPanelSliding()) mx -= currentPanelOffset();
                if (handleClickableTextClick(mx, click.y())) return true;
                textSelection.clear();
            }
            return true;
        }
        if (scrollbarDragging) { scrollbarDragging = false; return true; }
        // 引用块：按下时已被吞掉，这里不再派发跳转（单击不跳；双击由 mouseClicked 的
        // doubleClick 分支处理）。只复位标记并吞掉这次释放，保持与按下对称。
        if (quotePressPending) {
            quotePressPending = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    private boolean handleIconClick(int mx, int my) {
        int iconY = barTop + (BAR_H - ICON_S) / 2;
        int gearX = panelX + 4;
        if (mx >= gearX && mx <= gearX + ICON_S && my >= iconY && my <= iconY + ICON_S) {
            if (emojiPanel.visible) beginPopupClose(s -> emojiCloseStart = s, () -> emojiPanel.visible = false);
            if (searchPanel.visible) closeSearchPanel();
            boolean opening = !settingsMenu.visible;
            if (opening) {
                settingsMenu.visible = true;
                settingsAnimStart = Util.getMeasuringTimeMs();
            } else {
                beginPopupClose(s -> settingsCloseStart = s, () -> settingsMenu.visible = false);
            }
            return true;
        }
        int sendX = panelX + panelW - PAD - ICON_S + 2;
        int emojiX = sendX - ICON_S - 4;
        if (mx >= emojiX && mx <= emojiX + ICON_S && my >= iconY && my <= iconY + ICON_S) {
            if (settingsMenu.visible) beginPopupClose(s -> settingsCloseStart = s, () -> settingsMenu.visible = false);
            if (searchPanel.visible) closeSearchPanel();
            boolean opening = !emojiPanel.visible;
            if (opening) {
                emojiPanel.visible = true;
                EmoteStore.refresh();
                emojiAnimStart = Util.getMeasuringTimeMs();
                showMentions = false;
                emojiPanel.scroll = 0;
            } else {
                beginPopupClose(s -> emojiCloseStart = s, () -> emojiPanel.visible = false);
            }
            return true;
        }
        if (mx >= sendX && mx <= sendX + ICON_S && my >= iconY && my <= iconY + ICON_S) {
            sendMessage(); return true;
        }
        return false;
    }


    // ---- Local image upload (2.3.11) ----

    /** OS file drag onto the window (vanilla drop hook): upload the first image dropped. */
    @Override
    public void onFilesDropped(List<java.nio.file.Path> paths) {
        com.mojang.logging.LogUtils.getLogger().info("[e33chat] filesDrop {} paths | emojiTab={}",
            paths.size(), emojiPanel.visible && emojiPanel.tab == 2);
        // Emote tab open: dropping adds to the pack instead of uploading.
        if (emojiPanel.visible && emojiPanel.tab == 2) {
            for (java.nio.file.Path p : paths) {
                java.io.File f = p.toFile();
                if (f.isFile() && EmoteStore.isImageFile(f)) {
                    EmoteStore.add(f);
                    break;
                }
            }
            return;
        }
        for (java.nio.file.Path p : paths) {
            String l = p.getFileName().toString().toLowerCase();
            if (l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg")
                    || l.endsWith(".gif") || l.endsWith(".bmp")) {
                uploadQueue.enqueue(new com.niuqu.chatbubble.image.UploadQueue.UploadJob(p.toFile(), null, null, false, null));
                // The OS drop can steal window focus; give it back to the chat input
                // so typing keeps working right after a drag.
                client.execute(() -> setFocused(chatField));
                return;
            }
        }
    }

    private void addClipboardEmote() {
        ImageLoader.executor().execute(() -> {
            LocalImageSource.PreparedImage prep = readClipboard();
            if (prep == null) return; // no image in clipboard
            client.execute(() -> EmoteStore.addBytes(prep.bytes(), "paste_" + System.currentTimeMillis() + ".png"));
        });
    }

    private void startUploadFromClipboard() {
        ImageLoader.executor().execute(() -> {
            LocalImageSource.PreparedImage prep = readClipboard();
            if (prep == null) return; // no image in clipboard  ?let vanilla paste text
            client.execute(() -> uploadQueue.enqueue(new com.niuqu.chatbubble.image.UploadQueue.UploadJob(null, prep.bytes(), "clipboard", false, null)));
        });
    }

    private static LocalImageSource.PreparedImage readClipboard() {
        try {
            return LocalImageSource.fromClipboard();
        } catch (Throwable t) {
            return null;
        }
    }



    /** Runs queued uploads one at a time; the completion callback in
     * finishUpload calls this again for the next job. */




    /** 消息右键菜单的条目数：系统消息只给「复制」（引用对系统消息没有意义），
     *  玩家消息维持「复制 + 引用」两项。 */
    private int contextMenuItemCount() {
        ChatMessageStore.ChatMessage msg = ChatMessageStore.getMessageAt(contextMsgIndex);
        return msg != null && msg.isSystem() ? 1 : 2;
    }

    /** 消息右键菜单的高度：按实际条目数算（1 项 = 一行 + 2px 边距，2 项 = 两行 + 2px），
     *  绘制与点击命中共用，避免菜单"看起来少一行、点上去却按两行算"的错位。 */
    private int contextMenuHeight() {
        return CTX_ITEM_H * contextMenuItemCount() + 2;
    }

    private void handleContextClick(int mx, int my) {
        int items = contextMenuItemCount();
        int menuH = contextMenuHeight();
        int menuX = Math.min(contextX, panelX + panelW - CTX_W - 2);
        int menuY = contextY - menuH;
        if (menuY < msgTop) menuY = contextY + 4;
        if (mx >= menuX && mx <= menuX + CTX_W) {
            if (my >= menuY && my <= menuY + CTX_ITEM_H) {
                ChatMessageStore.ChatMessage msg = ChatMessageStore.getMessageAt(contextMsgIndex);
                if (msg != null) { client.keyboard.setClipboard(msg.content().getString()); copyToastTicks = 30; }
            } else if (items > 1 && my >= menuY + CTX_ITEM_H + 1 && my <= menuY + CTX_ITEM_H * 2 + 1) {
                replyTargetIndex = contextMsgIndex;
            }
        }
        contextMsgIndex = -1;
    }

    private void handleAvatarContextClick(int mx, int my) {
        int menuH = CTX_ITEM_H * 3 + 4;
        int menuX = Math.min(contextAvatarX, panelX + panelW - CTX_W - 2);
        int menuY = contextAvatarY - menuH;
        if (menuY < msgTop) menuY = contextAvatarY + 4;
        if (mx >= menuX && mx <= menuX + CTX_W) {
            ChatMessageStore.ChatMessage msg = ChatMessageStore.getMessageAt(contextAvatarIndex);
            String name = msg != null ? msg.rawPlayerName() : null;
            if (name == null || name.isEmpty()) { contextAvatarIndex = -1; return; }
            if (my >= menuY && my <= menuY + CTX_ITEM_H) {
                client.player.networkHandler.sendChatCommand((ChatMessageStore.useTpa() ? "tpa " : "tp ") + name);
            } else if (my >= menuY + CTX_ITEM_H + 2 && my <= menuY + CTX_ITEM_H * 2 + 2) {
                whisperPartner = name;
                ChatMessageStore.clearUnreadWhisper(name);
                beginConversationSwitch();
                setFocused(chatField); scrollToBottom = true;
            } else if (my >= menuY + CTX_ITEM_H * 2 + 4 && my <= menuY + menuH) {
                toggleBlockedPlayer();
            }
        }
        contextAvatarIndex = -1;
    }

    // 屏蔽/取消屏蔽右键菜单目标玩家：名单即时生 ?+ 从消息列表清掉历 ?+ 立即写盘
    private void toggleBlockedPlayer() {
        ChatMessageStore.ChatMessage msg = ChatMessageStore.getMessageAt(contextAvatarIndex);
        if (msg == null) return;
        String name = msg.rawPlayerName();
        if (name == null || name.isEmpty()) {
            name = msg.senderName() != null ? msg.senderName().getString() : null;
        }
        if (name == null || name.isEmpty()) return;
        final String target = name;

        List<String> blocked = new ArrayList<>(ChatBubbleClientSetup.config().blockedPlayers());
        boolean nowBlocked = BlockList.isPlayerBlocked(
            msg.rawPlayerName(), msg.senderName(), blocked);
        if (nowBlocked) {
            blocked.removeIf(b -> b != null && b.trim().equalsIgnoreCase(target));
        } else {
            blocked.add(target.trim());
        }
        ChatBubbleClientSetup.saveConfig(ChatBubbleClientSetup.config().withBlockedPlayers(blocked));
        ChatMessageStore.purgeBlocked(blocked);
        ChatMessageStore.debugLog(() -> "[e33chat] Block list updated | name='" + target + "' | blocked=" + nowBlocked);
    }

    @Override
    public void render(DrawContext g, int mouseX, int mouseY, float delta) {
        tickSidebarAnimation();

        float anim = getAnimProgress();
        AnimationStyle pstyle = AnimationStyle.parse(ChatBubbleClientSetup.config().panelAnimStyle());
        int panelOffset = (pstyle == AnimationStyle.SLIDE) ? currentPanelOffset() : 0;
        boolean zoom = (pstyle == AnimationStyle.ZOOM) && anim < 1f;
        float panelScale = 1f;
        if (zoom) panelScale = 0.8f + 0.2f * Animation.easeOutBack(anim);

        g.getMatrices().pushMatrix();
        g.getMatrices().translate(panelOffset, 0);
        if (zoom) {
            float cx = panelX + panelW / 2f;
            g.getMatrices().translate(cx, height / 2f);
            g.getMatrices().scale(panelScale, panelScale);
            g.getMatrices().translate(-cx, -height / 2f);
        }

        float panelOpacity = ChatBubbleClientSetup.config().panelOpacity() / 100f * anim;
        // When sidebar is synced to main animation, extend panel bg to
        // sidebar's right edge so there's no gap between them. Only SLIDE
        // moves horizontally (the panel slides in); FADE/ZOOM keep the bg
        // in place and fade/scale it in place instead.
        int fillLeft = (!sidebarAnimating && sidebarOpen && pstyle == AnimationStyle.SLIDE)
            ? (int)(anim * SIDEBAR_W) : panelX;
        if (ChatBubbleClientSetup.config().blurEnabled() && panelOpacity < 0.999f && !zoom) {
            BlurRenderer.blurPanel(g, panelOffset + fillLeft, 0, panelX + panelW - fillLeft, height);
        }
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.PANEL_BG),
            fillLeft, 0, panelX + panelW - fillLeft, height, panelOpacity);

        // 上下栏背景只跟开合动画（fade 终点 1.0 不透明），不乘 PANEL_OPACITY ?.3.7 起永久半透明回归
        renderTitleBar(g, mouseX, mouseY, getBarAlpha());
        renderMessages(g, mouseX, mouseY);
        Style hovered = getHoveredStyle(mouseX, mouseY);
        if (hovered != null && hovered.getHoverEvent() != null) {
            g.drawHoverEvent(textRenderer, hovered, mouseX, mouseY);
        }

        g.getMatrices().translate(0, 0);
        renderNotificationBar(g, mouseX, mouseY);
        renderReplyBar(g, mouseX, mouseY);
        renderContextMenu(g, mouseX, mouseY);
        renderAvatarContextMenu(g, mouseX, mouseY);
        renderToast(g);
        renderBottomBar(g, mouseX, mouseY, getBarAlpha());
        renderMentionPopup(g, mouseX, mouseY);
        // 弹层面板（设 ?表情/快捷/搜索）画在底栏之上，z 高一层——侧边栏 ?z 后画
        // 会盖住它们，提升弹层 z 到侧边栏之上避免遮挡
        g.getMatrices().pushMatrix();
        g.getMatrices().translate(0, 0);
        renderPopupWithAnim(g, settingsAnimStart, settingsCloseStart, a -> () -> settingsMenu.render(g, mouseX, mouseY, textRenderer, c(), panelX, panelW, barTop, ChatBubbleScreen::iconTex, a));
        renderPopupWithAnim(g, emojiAnimStart, emojiCloseStart, a -> () -> emojiPanel.render(g, mouseX, mouseY, textRenderer, c(), panelX, panelW, barTop, ICON_S, PAD, a));
        renderPopupWithAnim(g, quickAnimStart, quickCloseStart, a -> () -> quickChatPanel.render(g, mouseX, mouseY, textRenderer, c(), panelX, panelW, barTop, quickChatInput, a));
        renderPopupWithAnim(g, searchAnimStart, searchCloseStart, a -> () -> searchPanel.render(g, mouseX, mouseY, textRenderer, c(), panelX, panelW, barTop, searchInput, searchMatches, searchMatchIdx, a));
        // 输入 ?widget  ?z=50  ?children 循环渲染，会被这 ?z=100 的不透明面板背景盖住
        //  ?bb740e 弹层 z 提升引入）——面板打开时在 ?z 重画一次，文字/光标才可见
// widget 无背景（drawsBackground=false），只画文字/光标，不遮挡面板内容
        if (quickChatPanel.visible && quickChatInput != null) quickChatInput.render(g, mouseX, mouseY, delta);
        if (searchPanel.visible && searchInput != null) searchInput.render(g, mouseX, mouseY, delta);
        g.getMatrices().popMatrix();

        // 免打扰按钮 tooltip 画在弹层之后（最高一层），避免被消息区/弹层盖住

        g.getMatrices().popMatrix();

        if (sidebarOpen || sidebarAnimating) {
            g.getMatrices().pushMatrix();
            // ZOOM: the sidebar scales with the panel around the panel center
            if (zoom) {
                float cx = panelX + panelW / 2f;
                g.getMatrices().translate(cx, height / 2f);
                g.getMatrices().scale(panelScale, panelScale);
                g.getMatrices().translate(-cx, -height / 2f);
            }
            // Fade/zoom-in-place applies only to the panel's own open/close
            // animation; the hamburger toggle always slides.
            boolean fadeSidebar = !sidebarAnimating && (pstyle == AnimationStyle.FADE || zoom);
            int sidebarOffset = (closing && !fadeSidebar)
                ? (int) ((getAnimProgress() - 1.0f) * SIDEBAR_W)
                : (fadeSidebar ? 0 : getSidebarScreenX());
            g.getMatrices().translate(sidebarOffset, 0);
            // Per-element alpha (vanilla drawTexture ignores setShaderColor; the
            // sidebar fades its own textures through the alpha path)
            renderSidebar(g, mouseX - sidebarOffset, mouseY, fadeSidebar ? getAnimProgress() : 1f);
            g.getMatrices().popMatrix();
            if (closing) sidebarSearchBox.setX(2 + sidebarOffset);
        }

        g.getMatrices().pushMatrix();
        g.getMatrices().translate(0, 0);
        chatField.setX(inputX + panelOffset);
        // 不调 super.render（ChatScreen.render 访问 package-private chatInputSuggestor      // 跨包无法初始化）；复 ?Screen.render  ?widgets 遍历渲染
        for (net.minecraft.client.gui.Element w : this.children()) {
            if (w instanceof net.minecraft.client.gui.Drawable d) d.render(g, mouseX, mouseY, delta);
        }
        // 建议框定位基 ?chatField.getScreenX()（屏幕坐标），与 input 同坐标空间渲
        g.enableScissor(panelX, 0, panelX + panelW, height);
        if (commandSuggestions != null) commandSuggestions.render(g, mouseX, mouseY);
        g.disableScissor();
        g.getMatrices().popMatrix();

        // Notification banner is rendered by ChatBubbleHudOverlay at z=300
    }

    private void renderTitleBar(DrawContext g, int mouseX, int mouseY, float panelAlpha) {
        int ty = titleY;
        int a255 = (int) (255 * panelAlpha);
        // Content (icons/text) alpha follows only the open/close animation       // panelOpacity must not tint it (2.3.7 regression: permanent 80%
        // opacity made icons/text lighter on light themes).
        int c255 = (int) (255 * getAnimProgress());
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.TITLE_BAR), panelX, ty, panelW, TITLE_H, panelAlpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), panelX, ty + TITLE_H, panelW, 1, panelAlpha);

        int menuX = panelX + 3;
        int menuY = ty + (TITLE_H - ICON_S) / 2;
        boolean hoverMenu = mouseX >= menuX && mouseX <= menuX + ICON_S && mouseY >= menuY && mouseY <= menuY + ICON_S;
        if (hoverMenu) ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.HOVER_BG), menuX - 1, menuY - 1, ICON_S + 2, ICON_S + 2, panelAlpha);
        drawTextureIconAlpha(g, iconTex("menu"), menuX, menuY, ICON_S, getAnimProgress());

        // 免打扰（DND）按钮：汉堡右侧，与齿轮按钮同尺寸的圆角按钮，启用态为紫色高亮底。
        // 全屏只有这一个月亮按钮，作用对象跟随当前视图（公屏 / 当前私聊对象），
        // 高亮也反映「当前作用对象」的状态——见 dndActiveForCurrentView()。
        drawDndButton(g, iconTex("dnd"), dndButtonX(), dndButtonY(), ICON_S - 1,
            getAnimProgress(), isMouseOverDnd(mouseX, mouseY),
            dndActiveForCurrentView());

        String title = getDisplayTitle();
        int titleW = textRenderer.getWidth(title);
        int titleX = UiLayout.centerX(panelX, panelW, titleW);
        int titleTextY = ty + (TITLE_H - textRenderer.fontHeight) / 2;
        g.drawText(textRenderer, title, titleX, titleTextY, ChatBubbleTheme.alphaBlend(c().textPrimary(), c255), false);

        String time = LocalTime.now().format(TIME_FMT);
        int timeW = textRenderer.getWidth(time);
        g.drawText(textRenderer, time,
            panelX + panelW - PAD - 20 - timeW, ty + (TITLE_H - textRenderer.fontHeight) / 2, ChatBubbleTheme.alphaBlend(c().timeColor(), c255), false);

        int closeX = panelX + panelW - 18;
        int closeY = ty + 6;
        boolean hoverClose = mouseX >= closeX && mouseX <= closeX + 12 && mouseY >= closeY && mouseY <= closeY + 12;
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(hoverClose ? UiElement.CLOSE_HOVER : UiElement.CLOSE_BG),
            closeX, closeY, 12, 12, panelAlpha);
        g.drawText(textRenderer, "✕", closeX + 6 - textRenderer.getWidth("✕") / 2, closeY + 2, ChatBubbleTheme.alphaBlend(c().closeText(), c255), false);
    }

    private boolean isMouseOverHamburger(double mx, double my) {
        int menuX = panelX + 3;
        int menuY = titleY + (TITLE_H - ICON_S) / 2;
        return mx >= menuX && mx <= menuX + ICON_S && my >= menuY && my <= menuY + ICON_S;
    }

    // ==== 免打扰（DND）按钮 ====
    // 尺寸与底部栏设置（齿轮）按钮完全一致：齿轮用 drawIconButton(..., ICON_S - 1, ...)，
    // 图标 13x13 + 外圈 1px 描边 → 15x15 圆角框（半径 6/5，与本屏其它图标按钮同一套圆角）。
    //
    // 全屏只有这一颗月亮按钮（标题栏、汉堡右侧）。它没有固定语义，而是**按当前视图切换作用对象**：
    //   公屏视图（whisperPartner == null）  → 公屏/系统免打扰（dndEnabled）
    //   私聊视图（whisperPartner != null）  → 当前私聊对象的免打扰（isWhisperDnd(name)）
    // 高亮、点击、系统回执全部走这一对判定，避免"按钮亮的状态和它改的对象不是同一个"。
    // （私聊标题条右侧原来那颗只作用于本私聊的月亮按钮已删除——同一个开关没必要放两处。）

    /** 免打扰月亮图标的「白色剪影」贴图。
     *  dnd.png 在 dark/ 下是纯白 (255,255,255) + alpha 形状，在 light/ 下是深棕 (71,25,0)。
     *  GUI 纹理着色是**乘法**（纹理色 × tint）：白 × 紫 = 准确的紫；深棕 × 紫 ≈ 近黑，
     *  根本读不出"紫"。所以凡是需要染色的场合（玩家行的紫月亮、启用态按钮上的浅色图标）
     *  都取这张白色剪影，**贴图文件本身一个字节都不改**。 */
    private static Identifier dndMaskTex() {
        return Identifier.of("e33chat", "textures/gui/dark/dnd.png");
    }

    /** 玩家列表行里的「该玩家私聊免打扰」紫月亮：没有圆角底，就是纯图标；
     *  颜色随主题（dark 亮紫 / light 蓝紫），透明度跟随侧边栏淡入淡出。 */
    private void drawDndRowIcon(DrawContext g, int x, int y, float alpha) {
        int a = (int) (255 * alpha);
        if (a <= 1) return;
        drawTintedIcon(g, dndMaskTex(), x, y, DND_ROW_ICON,
            ChatBubbleTheme.alphaBlend(theme().dndAccent(), a));
    }

    /** 免打扰按钮左上角 X：紧跟汉堡按钮右侧（汉堡起于 panelX+3、宽 ICON_S，再留 4px 间隙）。 */
    private int dndButtonX() { return panelX + 3 + ICON_S + 4; }

    /** 免打扰按钮左上角 Y：与汉堡按钮同一条基线（TITLE_H 内居中 ICON_S）。 */
    private int dndButtonY() { return titleY + (TITLE_H - ICON_S) / 2; }

    private boolean isMouseOverDnd(double mx, double my) {
        int bx = dndButtonX() - 1, by = dndButtonY() - 1;
        int s = ICON_S - 1 + 2;   // 图标尺寸（= 齿轮）+ 两侧各 1px 描边
        return mx >= bx && mx <= bx + s && my >= by && my <= by + s;
    }

    /** 当前视图下免打扰是否已开启：公屏视图看公屏开关，私聊视图看「这个对象」的开关。
     *  按钮高亮与点击分流共用它，保证画的亮态就是点下去会改的那个对象。 */
    private boolean dndActiveForCurrentView() {
        String partner = whisperPartner;
        return partner == null
            ? ChatBubbleClientSetup.config().dndEnabled()
            : ChatBubbleClientSetup.config().isWhisperDnd(partner);
    }

    /** 点击分流：按当前视图把这次切换派给「公屏」或「当前私聊对象」。
     *  两边的系统回执分别在 togglePublicDnd / toggleWhisperDnd 里插入，文案与作用对象一致。 */
    private void toggleDndForCurrentView() {
        if (whisperPartner == null) togglePublicDnd();
        else toggleWhisperDnd();
    }

    /** 免打扰按钮：与 drawIconButton 同尺寸/同圆角/同描边。
     *  - **启用态**：整块换成紫色（描边 = 主题免打扰紫，内层 = 深一档的紫填充），图标改用
     *    白色剪影（浅色主题的深棕月亮压在紫底上会糊掉）。紫色与选中行的强调色刻意不同，
     *    "我静音了" 与 "我正看着这条会话" 不会混。
     *  - **未启用态**：与其它图标按钮完全一致（textMuted 描边 + inputBg 内层 + 悬停 popupHover）。 */
    private void drawDndButton(DrawContext g, Identifier tex, int x, int y, int size,
                               float alpha, boolean hover, boolean active) {
        int a255 = (int) (255 * alpha);
        int pad = 1;
        int bx = x - pad, by = y - pad, bw = size + pad * 2, bh = size + pad * 2;
        if (bw <= 0 || bh <= 0) return;
        ChatBubbleTheme th = theme();
        int purpleBorder = th.dndAccent();
        int purpleFill = th.dndFill();
        int border = ChatBubbleTheme.alphaBlend(active ? purpleBorder : c().textMuted(), a255);
        int bg = ChatBubbleTheme.alphaBlend(active ? purpleFill : c().inputBg(), a255);
        // 圆角描边环：描边色圆角底 + 内缩 1px 的背景色圆角（与设置/表情/发送按钮同构）
        RoundRectRenderer.fill(g, bx, by, bx + bw, by + bh, 6f, border);
        RoundRectRenderer.fill(g, bx + 1, by + 1, bx + bw - 1, by + bh - 1, 5f, bg);
        if (hover) {
            // 启用态的 hover 用一层 27% 的白提亮（紫底不会被浅色 popupHover 洗掉）；
            // 未启用态照旧用 popupHover，与其它按钮的悬停观感一致。
            int hoverColor = active
                ? ChatBubbleTheme.alphaBlend(0xFFFFFFFF, (int) (0x44 * alpha))
                : ChatBubbleTheme.alphaBlend(c().popupHover(), a255);
            RoundRectRenderer.fill(g, bx, by, bx + bw, by + bh, 6f, hoverColor);
        }
        if (active) {
            drawTintedIcon(g, dndMaskTex(), x, y, size, ChatBubbleTheme.alphaBlend(0xFFFFFFFF, a255));
        } else {
            drawTextureIconAlpha(g, tex, x, y, size, alpha);
        }
    }

    /** 切换**公屏/系统**免打扰：写回配置（持久化）+ 往消息列表插一条系统回执。
     *  只在公屏视图下由 toggleDndForCurrentView 派发到这里；私聊是否免打扰由每个对话对象
     *  自己的开关决定（同一个月亮按钮在私聊视图下会派到 toggleWhisperDnd）。
     *  @提及/引用不受任何免打扰影响。 */
    private void togglePublicDnd() {
        boolean next = !ChatBubbleClientSetup.config().dndEnabled();
        ChatBubbleClientSetup.saveConfig(ChatBubbleClientSetup.config().withDndEnabled(next));
        ChatMessageStore.addLocalNotice(
            Text.translatable(next ? "e33chat.dnd.on" : "e33chat.dnd.off"), whisperPartner);
        scrollToBottom = true;
        ChatMessageStore.debugLog(() -> "[e33chat] Public DND toggled | enabled=" + next
            + " | whisperView=" + whisperPartner);
    }

    // ==== 私聊免打扰（按会话独立）的切换逻辑 ====
    // 这里不再有独立的按钮/命中区域：标题栏那颗月亮按钮在私聊视图下就是「本私聊」的开关
    // （见 toggleDndForCurrentView），所以只保留切换动作本身。

    /** 切换「当前私聊对象」的免打扰：写回配置（持久化）+ 在这条私聊里插一条系统回执。
     *  只影响这一个私聊：它的横幅与提示音静默，公屏/别的私聊不受影响；
     *  @提及/引用照旧穿透。 */
    private void toggleWhisperDnd() {
        if (whisperPartner == null) return;
        final String partner = whisperPartner;
        boolean next = !ChatBubbleClientSetup.config().isWhisperDnd(partner);
        ChatBubbleClientSetup.saveConfig(ChatBubbleClientSetup.config().withWhisperDnd(partner, next));
        ChatMessageStore.addLocalNotice(
            Text.translatable(next ? "e33chat.dnd.whisper.on" : "e33chat.dnd.whisper.off", partner), partner);
        scrollToBottom = true;
        ChatMessageStore.debugLog(() -> "[e33chat] Whisper DND toggled | partner=" + partner
            + " | enabled=" + next);
    }



    /** 切换私聊/公共频道的统一收尾：搜索框只在真有内容时清空 + 下一帧直接落到新会话底部。
     *  注意 TextFieldWidget.setText 不比较新旧文本、永远回调 changedListener，
     *  而侧边栏搜索框的监听器会把 sidebarScrollOffset 归零 —— 于是"点一下玩家行、
     *  玩家列表瞬间跳回顶部"。这里先判空，避免那次无意义的归零。 */
    private void beginConversationSwitch() {
        boolean clearedFilter = false;
        if (sidebarSearchBox != null && !sidebarSearchBox.getText().isEmpty()) {
            sidebarSearchBox.setText("");
            clearedFilter = true;
        }
        // 真的清掉了过滤词 → 玩家行从"过滤后的 N 行"突增成"全部"，旧偏移对新列表没有
        // 意义：显式归零（不依赖 changedListener 的副作用），否则列表会停在中段、
        // 首行被 scissor 裁掉半截。
        if (clearedFilter) sidebarScrollOffset = 0;
        // 没清过滤词时列表内容不变，只做夹紧：人数/可见高度可能已经变了，别留着越界偏移。
        clampSidebarScroll();
        scrollSnapPending = true;
        scrollAnimActive = false;
    }

    private void renderMessages(DrawContext g, int mouseX, int mouseY) {
        msgHeightCache.clear();
        int imgVersion = ImageLoader.version();
        if (imgVersion != lastImageVersion) {
            lastImageVersion = imgVersion;
            imageParseCache.clear();
        }
        bubbleRects.clear();
        clickableSpans.clear();
        quoteRects.clear();
        textSpans.clear();
        List<ChatMessageStore.ChatMessage> messages;
        if (whisperPartner != null) {
            messages = ChatMessageStore.getWhisperMessages(whisperPartner);
        } else {
            messages = ChatMessageStore.getPublicMessages();
        }
        // 私聊标题条先画：不能放在下面「空会话直接 return」之后，否则刚点开一个还没有
        // 聊天记录的玩家时整条标题条消失。（原来这里还画一个「本私聊免打扰」月亮按钮，
        // 现在已统一到标题栏那颗按视图切换的月亮按钮上，标题条只剩居中文字。）
        int indicatorH = 0;
        if (whisperPartner != null) {
            indicatorH = WHISPER_BAR_H;
            int indY = msgTop;
            ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.WHISPER_BAR), panelX, indY, panelW, indicatorH, getAnimProgress());
            // 标题条右侧不再有按钮：居中文字只留左右各 8px 内边距，名字过长时按面板宽省略。
            int modeMaxW = Math.max(20, panelW - 2 * 8);
            String modeLabel = Text.translatable("e33chat.whisper.mode").getString() + ": " + whisperPartner;
            String modeText = ellipsize(modeLabel, modeMaxW);
            int modeTW = textRenderer.getWidth(modeText);
            g.drawText(textRenderer, modeText, panelX + (panelW - modeTW) / 2, indY + 2, c().textPrimary(), false);
        }

        if (messages.isEmpty()) {
            // 空会话没有可滚动内容：丢弃这一次吸附请求，并把上一会话的滚动状态归零，
            // 免得 maxScroll/scrollOffset 停留在旧会话的值上。
            scrollSnapPending = false;
            scrollOffset = 0; maxScroll = 0; scrollToBottom = false; scrollAnimActive = false;
            return;
        }

        int effectiveMsgTop = msgTop + indicatorH;
        int effectiveMsgBottom = newMessageCount > 0 ? barTop - NOTIF_H - 1 : msgBottom;
        int areaH = effectiveMsgBottom - effectiveMsgTop;

        String lastKey = null;
        ChatMessageStore.ChatMessage prevMsg = null;
        int totalH = 0;
        for (var msg : messages) {
            if (!msg.isSystem()) {
                String key = timeKey(msg.time());
                if (lastKey == null || !key.equals(lastKey)) {
                    lastKey = key;
                    totalH += TIME_SEP_H + Appearance.messageGap();
                    prevMsg = null;
                }
            }
            if (prevMsg != null) totalH += Appearance.messageGap();
            totalH += getMsgHeight(msg);
            prevMsg = msg;
        }
        // 底部内边距（最新消息与输入栏的间隙
        totalH += BOTTOM_MESSAGE_PAD;
        int prevMaxScroll = maxScroll;
        maxScroll = Math.max(0, totalH - areaH);
        this.messageTotalH = totalH;

        boolean wasAtBottom = scrollOffset >= prevMaxScroll - 2;

        String playerName = client.player != null ? client.player.getName().getString() : "";
        int currentMsgCount = messages.size();
        if (wasAtBottom) {
            newMessageCount = 0; hasNewMentionOrQuote = false;
            latestMentionIndex = -1; lastSeenMessageCount = currentMsgCount;
        } else if (currentMsgCount > lastSeenMessageCount) {
            for (int i = lastSeenMessageCount; i < currentMsgCount; i++) {
                var msg = messages.get(i);
                if (msg == null) continue;
                newMessageCount++;
                if (msg.content().getString().contains("@" + playerName)) {
                    hasNewMentionOrQuote = true; latestMentionIndex = i;
                }
                if (msg.replySender() != null && msg.replySender().equals(playerName)) {
                    hasNewMentionOrQuote = true;
                    if (i > latestMentionIndex) latestMentionIndex = i;
                }
            }
            lastSeenMessageCount = currentMsgCount;
        }

        if (firstRender || scrollSnapPending) {
            scrollOffset = maxScroll; scrollToBottom = false; firstRender = false; scrollAnimActive = false;
            scrollSnapPending = false;
        } else if (scrollAnimActive) {
            float t = Animation.progress(scrollAnimStart, scrollAnimDuration, false);
            scrollOffset = Math.round(scrollAnimFrom + (scrollAnimTo - scrollAnimFrom) * t);
            if (t >= 1.0f) { scrollOffset = Math.round(scrollAnimTo); scrollAnimActive = false; }
        } else if (scrollToBottom || wasAtBottom) {
            float newTarget = maxScroll;
            if (Math.abs(scrollOffset - newTarget) <= 3) {
                scrollOffset = Math.round(newTarget); scrollToBottom = false;
            } else {
                lastScrollTime = Util.getMeasuringTimeMs();
                scrollAnimFrom = scrollOffset; scrollAnimTo = newTarget;
                scrollAnimStart = Util.getMeasuringTimeMs(); scrollAnimDuration = 150; scrollAnimActive = true;
            }
        }
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll);

        // 引用跳转后连 ?3 帧的 scrollOffset：判断是否被「自动到 ?/ 滚动动画」覆盖
// 只有刚跳转过才有值，点一次只 ?3 行，不会每帧刷屏
if (quoteJumpFrameLogs > 0) {
            int n = 4 - quoteJumpFrameLogs;
            quoteJumpFrameLogs--;
            quoteJumpLog(() -> "[e33chat] QUOTEJUMP | AFTER-FRAME" + n + " | scrollOffset=" + scrollOffset
                + " maxScroll=" + maxScroll + " wasAtBottom=" + wasAtBottom
                + " animActive=" + scrollAnimActive + " scrollToBottom=" + scrollToBottom);
        }

        g.enableScissor(panelX, effectiveMsgTop, panelX + panelW, effectiveMsgBottom);

        List<ChatMessageStore.ChatMessage> fullList = ChatMessageStore.getMessages();
        int fullIdx = 0;
        while (fullIdx < fullList.size() && fullList.get(fullIdx) != messages.get(0)) fullIdx++;

        int contentY = 0;
        lastKey = null;
        ChatMessageStore.ChatMessage prevRenderMsg = null;
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            while (fullIdx < fullList.size() && fullList.get(fullIdx) != msg) fullIdx++;

            if (!msg.isSystem()) {
                String key = timeKey(msg.time());
                if (lastKey == null || !key.equals(lastKey)) {
                    lastKey = key;
                    int ssy = effectiveMsgTop + contentY - scrollOffset;
                    if (ssy + TIME_SEP_H > effectiveMsgTop && ssy < effectiveMsgBottom)
                        renderTimeSeparator(g, msg.time(), ssy);
                    contentY += TIME_SEP_H + Appearance.messageGap();
                    prevRenderMsg = null;
                }
            }

            int h = getMsgHeight(msg);
            if (prevRenderMsg != null) contentY += Appearance.messageGap();
            int screenY = effectiveMsgTop + contentY - scrollOffset;
            // showAvatar 必须用“上一条消息”比较；先赋 ?prevRenderMsg 再比会恒自比 ?.3.16 回归
boolean showAvatar = !(ChatBubbleClientSetup.config().hideRepeatedAvatars() != null
                && ChatBubbleClientSetup.config().hideRepeatedAvatars()
                && MessageGrouping.isSameGroup(prevRenderMsg, msg));
            contentY += h;
            prevRenderMsg = msg;

            if (screenY + h <= effectiveMsgTop || screenY >= effectiveMsgBottom) { fullIdx++; continue; }

            // New-message enter animation, staggered 40ms per message from the
            // tail (250ms window, keyed on msg.time()).
            // SLIDE: slide in horizontally  ?own bubbles from right to left,
            // others from left to right  ?plus fade. FADE: pure fade, no
            // displacement. ZOOM: scale in around the bubble center with overshoot.
            float mAlpha = 1f;
            int mDx = 0;
            int mDy = 0;
            float mScale = 1f;
            if (ChatBubbleClientSetup.config().animationEnabled()) {
                AnimationStyle mstyle = AnimationStyle.parse(ChatBubbleClientSetup.config().messageAnimStyle());
                if (mstyle != AnimationStyle.NONE) {
                    int tailIdx = messages.size() - 1 - i;
                    // msg.time() is epoch millis (System.currentTimeMillis), so the
                    // "now" side must use the same clock  ?the MC render clock is
                    // nanoTime-based and subtracting it yields a huge negative raw.
                    float raw = (float) (System.currentTimeMillis() - msg.time() - tailIdx * 40L) / 250f;
                    if (raw < 1f) {
                        float curve = Animation.styleCurve(mstyle, raw);
                        mAlpha = curve;
                        switch (mstyle) {
                            case SLIDE -> mDx = Math.round((1f - curve) * 40f) * (msg.isOwn() ? 1 : -1);
                            case FADE -> { /* pure fade, no displacement */ }
                            case ZOOM -> mScale = 0.8f + 0.2f * Animation.easeOutBack(curve);
                            default -> { }
                        }
                    }
                }
            }
            g.getMatrices().pushMatrix();
            g.getMatrices().translate(mDx, mDy);
            if (mScale != 1f) {
                // Bubble top-left for the ZOOM pivot (mirrors renderBubble's layout incl. bubble_size)
                float bs = Appearance.bubbleScale(textRenderer.fontHeight);
                int zMaxW = panelW - Appearance.avatarSize() - PAD * 2 - BUBBLE_PAD_X * 2 - 16;
                int zW = 0;
                for (var zl : wrapContent(msg.content(), Appearance.bubbleWrapWidth(zMaxW, textRenderer.fontHeight)))
                    zW = Math.max(zW, textRenderer.getWidth(zl));
                int zBubbleW = (int)((zW + BUBBLE_PAD_X * 2) * bs);
                int zBubbleX = msg.isOwn()
                    ? panelX + panelW - PAD - Appearance.avatarSize() - 4 - zBubbleW
                    : panelX + PAD + Appearance.avatarSize() + 4;
                int zBubbleY = screenY + NAME_H;
                g.getMatrices().translate(zBubbleX + zBubbleW / 2f, zBubbleY);
                g.getMatrices().scale(mScale, mScale);
                g.getMatrices().translate(-(zBubbleX + zBubbleW / 2f), -zBubbleY);
            }
            renderBubble(g, msg, fullIdx, screenY, mouseX, mouseY, mAlpha, showAvatar);
            g.getMatrices().popMatrix();
            // 引用跳转高亮：整条消息盖一层临时圆角底色，1.7s 淡出（纯提示，不参与命中
            renderQuoteFlash(g, msg, screenY, h);
            fullIdx++;
        }
        renderScrollbar(g, mouseX, mouseY, effectiveMsgBottom);
        g.disableScissor();
    }

    private void renderScrollbar(DrawContext g, int mouseX, int mouseY, int effectiveMsgBottom) {
        if (maxScroll <= 0) return;
        boolean inZone = mouseX >= panelX + panelW - SCROLLBAR_HOVER_ZONE
            && mouseX <= panelX + panelW && mouseY >= msgTop && mouseY < effectiveMsgBottom;
        boolean recentlyScrolled = Util.getMeasuringTimeMs() - lastScrollTime < 1000;
        float target = (inZone || scrollbarDragging || recentlyScrolled) ? 1f : 0f;
        scrollbarAlpha = Animation.lerpTo(scrollbarAlpha, target, 0.15f, 0.005f);
        if (scrollbarAlpha <= 0.005f && !scrollbarDragging) return;

        int trackX = panelX + panelW - SCROLLBAR_WIDTH;
        int trackTop = msgTop;
        int trackBottom = effectiveMsgBottom;
        int trackH = trackBottom - trackTop;

        // 纯色填充替代 drawWithAlpha：绕开消息路径 blend/flush 污染
        //  ?844270a 同机制：上游 drawWithAlpha  ?scrollbar 渐显只显最后一帧）
        g.fill(trackX, trackTop, trackX + SCROLLBAR_WIDTH, trackBottom,
            ChatBubbleTheme.alphaBlend(c().scrollbar(), (int) (0x1A * scrollbarAlpha)));

        int thumbH = Math.max(MIN_THUMB_H, (int) ((long) trackH * trackH / messageTotalH));
        thumbH = Math.min(thumbH, trackH);
        int travelRange = trackH - thumbH;
        int thumbY = trackTop + (int) ((long) scrollOffset * travelRange / maxScroll);

        boolean hovering = !scrollbarDragging
            && mouseX >= trackX && mouseX < trackX + SCROLLBAR_WIDTH
            && mouseY >= thumbY && mouseY < thumbY + thumbH;
        scrollbarHovered = hovering || scrollbarDragging;

        float thumbBase = scrollbarDragging ? 0xAA : scrollbarHovered ? 0x88 : 0x66;
        g.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH,
            ChatBubbleTheme.alphaBlend(c().scrollbar(), (int) (thumbBase * scrollbarAlpha)));
    }

    private void renderTimeSeparator(DrawContext g, long timeMillis, int y) {
        String text = ChatMessageStore.formatTime(timeMillis);
        int tw = textRenderer.getWidth(text);
        int tx = UiLayout.centerX(panelX, panelW, tw);
        g.fill(tx - 6, y + 2, tx + tw + 6, y + TIME_SEP_H - 2, ChatBubbleTheme.alphaBlend(c().toastBg(), 0x44));
        g.drawText(textRenderer, text, tx, y + 3, c().timeColor(), false);
    }

    private List<OrderedText> wrapContent(Text c, int width) {
        List<Text> paras = new ArrayList<>();
        MutableText[] cur = { Text.empty() };
        c.visit((style, text) -> {
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    if (i > start) cur[0].append(Text.literal(text.substring(start, i)).fillStyle(style));
                    paras.add(cur[0]);
                    cur[0] = Text.empty();
                    start = i + 1;
                }
            }
            if (start < text.length()) cur[0].append(Text.literal(text.substring(start)).fillStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        paras.add(cur[0]);
        while (!paras.isEmpty() && paras.get(0).getString().isEmpty()) paras.remove(0);
        while (!paras.isEmpty() && paras.get(paras.size() - 1).getString().isEmpty()) paras.remove(paras.size() - 1);
        List<OrderedText> out = new ArrayList<>();
        for (Text p : paras) out.addAll(textRenderer.wrapLines(p, width));
        if (out.isEmpty()) out.addAll(textRenderer.wrapLines(c, width));
        return out;
    }

    private boolean isPanelSliding() {
        return ChatBubbleClientSetup.config().animationEnabled() && getAnimProgress() < 1.0f;
    }

    private int currentPanelOffset() {
        if (AnimationStyle.parse(ChatBubbleClientSetup.config().panelAnimStyle()) != AnimationStyle.SLIDE)
            return 0; // FADE/ZOOM/NONE have no horizontal displacement
        float anim = getAnimProgress();
        int moveDist;
        if (sidebarOpen) {
            moveDist = closing ? panelW : SIDEBAR_W;
        } else {
            moveDist = panelW;
        }
        return (int) ((anim - 1.0f) * moveDist);
    }

    private int getMsgHeight(ChatMessageStore.ChatMessage msg) {
        Integer cached = msgHeightCache.get(msg);
        if (cached != null) return cached;
        int h;
        if (msg.isSystem()) {
            List<OrderedText> lines = wrapContent(msg.content(), panelW - PAD * 2 - 20);
            h = lines.size() * textRenderer.fontHeight + 4;
        } else {
            int bubbleMaxW = panelW - Appearance.avatarSize() - PAD * 2 - BUBBLE_PAD_X * 2 - 16;
            BracketCodec.ParseResult parsed = parseImages(msg);
            if (!parsed.images().isEmpty()
                    && parsed.images().stream().allMatch(BracketCodec.ImageRef::emote)
                    && parsed.textWithoutImages().getString().isBlank()) {
                h = NAME_H + textRenderer.fontHeight + 2 + EMOTE_MAX_SIZE + 2;
                msgHeightCache.put(msg, h);
                return h;
            }
            if (!parsed.images().isEmpty()) {
                List<OrderedText> imgLines = wrapContent(parsed.textWithoutImages(), bubbleMaxW);
                int textH = imgLines.size() * textRenderer.fontHeight;
                int imgH = 0;
                for (var ref : parsed.images()) imgH += imageEdgeHeight(ref.url()) + 2;
                h = NAME_H + textH + imgH;
                if (msg.replyContent() != null) h += textRenderer.fontHeight + 7;
                msgHeightCache.put(msg, h);
                return h;
            }
            float s = Appearance.bubbleScale(textRenderer.fontHeight);
            List<OrderedText> lines = wrapContent(parsed.textWithoutImages(), Appearance.bubbleWrapWidth(bubbleMaxW, textRenderer.fontHeight));
            double contentH = lines.size() * textRenderer.fontHeight + BUBBLE_PAD_Y * 2;
            if (msg.replyContent() != null) contentH += textRenderer.fontHeight + 7;
            h = NAME_H + (int) (contentH * s);
        }
        msgHeightCache.put(msg, h);
        return h;
    }

    private BracketCodec.ParseResult parseImages(ChatMessageStore.ChatMessage msg) {
        if (!ChatBubbleClientSetup.config().receiveImages()) {
            // Receiving disabled: bracket codes render as a plain-text
            // placeholder, never downloaded (the flood limiter stays untouched).
            return new BracketCodec.ParseResult(
                BracketCodec.toPlaceholderText(msg.content()), java.util.List.of());
        }
        BracketCodec.ParseResult cached = imageParseCache.get(msg);
        if (cached != null) return cached;
        cached = BracketCodec.parseOrExtract(msg.content());
        imageParseCache.put(msg, cached);
        return cached;
    }

    /** Height in px for one bubble-less image (state-dependent, panel-clamped, never upscaled). */
    private int imageEdgeHeight(String url) {
        int maxW = Math.max(80, panelW - Appearance.avatarSize() - PAD * 2 - 16);
        ImageEntry entry = ImageLoader.getOrLoad(url);
        if (entry != null && entry.state() == ImageEntry.State.LOADED
                && entry.width() > 0 && entry.height() > 0) {
            float ratio = Math.min((float) maxW / entry.width(),
                (float) maxW / entry.height());
            ratio = Math.min(1f, ratio);
            return Math.max(1, (int) (entry.height() * ratio));
        }
        return maxW;
    }

    private void renderBubble(DrawContext g, ChatMessageStore.ChatMessage msg, int index, int baseY, int mouseX, int mouseY, float alpha, boolean showAvatar) {
        if (msg.isSystem()) {
            List<OrderedText> lines = wrapContent(msg.content(), panelW - PAD * 2 - 20);
            int yy = baseY + 2;
            Style fb = findRootClickStyle(msg.content());
            int sysColor = ChatBubbleTheme.alphaBlend(c().textMuted(), (int)(255 * alpha));
            // 系统消息没有气泡，但同样要能被右键（右键菜单对系统消息只给「复制」）：
            // 补一条与玩家气泡同一套的命中矩形（第 5 项是全量列表下标）。左键的两个
            // 命中循环（头像 @ / 头像菜单）都会先跳过 isSystem，所以不会被这条矩形影响。
            bubbleRects.add(new int[]{panelX + PAD, baseY, panelW - PAD * 2,
                lines.size() * textRenderer.fontHeight + 4, index});
            int sysLi = 0;
            for (var line : lines) {
                int lw = textRenderer.getWidth(line);
                // 每一行都注册成可选中的 span（KIND_SYSTEM）：Ctrl+C 走的是
                // ChatTextSelection.copyText(textSpans)，没有 span 就永远复制不出来。
                // 系统消息居中、坐标已是屏幕空间，不需要矩阵回填。
                renderLineWithClicks(g, line, panelX + (panelW - lw) / 2, yy, sysColor, fb,
                    index, sysLi, TextSpan.KIND_SYSTEM, 1f);
                yy += textRenderer.fontHeight;
                sysLi++;
            }
            return;
        }

        boolean own = msg.isOwn();
        int bubbleMaxW = panelW - Appearance.avatarSize() - PAD * 2 - BUBBLE_PAD_X * 2 - 16;
        BracketCodec.ParseResult parsed = parseImages(msg);

        // E33Emote-only messages render bubble-less: max 64px, aligned by direction (QQ style).
        if (!parsed.images().isEmpty()
                && parsed.images().stream().allMatch(BracketCodec.ImageRef::emote)
                && parsed.textWithoutImages().getString().isBlank()) {
            renderEmoteMessage(g, msg, index, baseY, own, alpha, showAvatar);
            return;
        }

        List<OrderedText> lines = wrapContent(parsed.textWithoutImages(), bubbleMaxW);

        // Any message carrying images renders bubble-less too (320px long-edge,
        // aspect preserved, stacked vertically, direction-aligned).
        if (!parsed.images().isEmpty()) {
            renderNoBubbleMessage(g, msg, index, baseY, own, alpha, parsed, lines, showAvatar);
            return;
        }

        // Bubble path only: re-wrap at the scaled width so bigger bubbles fit fewer
        // characters per line (bubble-less emote/image paths above keep the unscaled lines).
        float s = Appearance.bubbleScale(textRenderer.fontHeight);
        lines = wrapContent(parsed.textWithoutImages(), Appearance.bubbleWrapWidth(bubbleMaxW, textRenderer.fontHeight));
        int textW = 0;
        for (var line : lines) textW = Math.max(textW, textRenderer.getWidth(line));
        int bubbleW = (int) ((textW + BUBBLE_PAD_X * 2) * s);
        int bubbleH = (int) ((lines.size() * textRenderer.fontHeight + BUBBLE_PAD_Y * 2) * s);

        int avatarX, bubbleX;
        if (own) {
            avatarX = panelX + panelW - PAD - Appearance.avatarSize();
            bubbleX = avatarX - UiTokens.AVATAR_GAP - bubbleW;
        } else {
            avatarX = panelX + PAD;
            bubbleX = avatarX + Appearance.avatarSize() + UiTokens.AVATAR_GAP;
        }

        int nameY = baseY;

        if (!msg.senderName().getString().isEmpty()) {
            int maxNameW = panelW - Appearance.avatarSize() - PAD * 2 - 20;
            Text sn = msg.senderName();
            OrderedText nameSeq;
            if (textRenderer.getWidth(sn) > maxNameW) {
                var cut = textRenderer.trimToWidth(sn, maxNameW - textRenderer.getWidth("..."));
                nameSeq = Language.getInstance().reorder(
                    StringVisitable.concat(cut, StringVisitable.plain("...")));
            } else {
                nameSeq = sn.asOrderedText();
            }
            int nameW = textRenderer.getWidth(nameSeq);
            int startX = own ? (bubbleX + bubbleW - nameW) : bubbleX;
            drawSelectableText(g, nameSeq, startX, nameY, ChatBubbleTheme.alphaBlend(c().nameColor(), (int)(255 * alpha)),
                index, 0, TextSpan.KIND_NAME, 1f);
        }

        int bubbleY = baseY + NAME_H;
        int avatarY = baseY;

        int bg = own
            ? ChatBubbleConfig.parseHexColor(ChatBubbleClientSetup.config().ownBubbleColor(), 0xFF1E90FF)
            : ChatBubbleConfig.parseHexColor(ChatBubbleClientSetup.config().otherBubbleColor(), c().contextHover());
        int fg = own
            ? ChatBubbleConfig.parseHexColor(ChatBubbleClientSetup.config().ownTextColor(), 0xFFFFFFFF)
            : ChatBubbleConfig.parseHexColor(ChatBubbleClientSetup.config().otherTextColor(), c().textPrimary());

        // 搜索命中高亮：先画一层圆角黄边（比气泡大 1px），再由气泡底色覆盖内部  ?圆角描边
        if (index == searchHighlightIndex)
            RoundRectRenderer.fill(g, bubbleX - 1, bubbleY - 1, bubbleX + bubbleW + 1, bubbleY + bubbleH + 1,
                ChatBubbleClientSetup.config().bubbleCornerRadius() * s, ChatSearchPanel.HIGHLIGHT);
        RoundRectRenderer.fill(g, bubbleX, bubbleY, bubbleX + bubbleW, bubbleY + bubbleH,
            ChatBubbleClientSetup.config().bubbleCornerRadius() * s, ChatBubbleTheme.alphaBlend(bg, (int)(255 * alpha)));

        Style fbP = findRootClickStyle(msg.content());
        int fgA = ChatBubbleTheme.alphaBlend(fg, (int)(255 * alpha));
        for (int li = 0; li < lines.size(); li++) {
            // Bubble text is drawn at the matrix origin; the translate must be unconditional
            // (s == 1 still needs the offset, only the scale is skipped), and clickable spans
            // are recorded in origin space then transformed back to screen space so
            // hit-testing and the visual position stay in sync at every bubble size.
            int textSX = bubbleX + (int)(BUBBLE_PAD_X * s);
            int textSY = bubbleY + (int)(BUBBLE_PAD_Y * s) + (int)(li * textRenderer.fontHeight * s);
            int beforeLine = clickableSpans.size();
            int beforeText = textSpans.size();
            g.getMatrices().pushMatrix();
            g.getMatrices().translate(textSX, textSY);
            if (s != 1f) g.getMatrices().scale(s, s);
            renderLineWithClicks(g, lines.get(li), 0, 0, fgA, fbP, index, li, TextSpan.KIND_CONTENT, s);
            g.getMatrices().popMatrix();
            for (int i = beforeLine; i < clickableSpans.size(); i++) {
                ClickableSpan sp = clickableSpans.get(i);
                clickableSpans.set(i, new ClickableSpan(
                    textSX + (int)(sp.x * s),
                    textSY + (int)(sp.y * s),
                    Math.max(1, (int)(sp.w * s)),
                    Math.max(1, (int)(sp.h * s)),
                    sp.style));
            }
            // Spans were recorded in the scaled matrix' origin space; bring them back
            // to screen space (same treatment as clickableSpans) or hit-testing would
            // be offset by the translate/scale.
            for (int i = beforeText; i < textSpans.size(); i++) {
                TextSpan sp = textSpans.get(i);
                textSpans.set(i, sp.withPosition(
                    textSX + (int)(sp.x() * s),
                    textSY + (int)(sp.y() * s),
                    Math.max(1, (int)(sp.w() * s)),
                    Math.max(1, (int)(sp.h() * s))));
            }
        }

        String skinName = (msg.rawPlayerName() != null && !msg.rawPlayerName().isEmpty())
            ? msg.rawPlayerName() : msg.senderName().getString();
        Identifier skin = com.niuqu.chatbubble.render.SkinResolver.getSkin(msg.senderUUID(), skinName);
        // Draw avatar (per-element alpha); D07: hidden on repeated same-sender messages
        if (showAvatar) drawPlayerHead(g, skin, avatarX, avatarY, Appearance.avatarSize(), Appearance.avatarSize() + 2, alpha);

        if (msg.duplicateCount() > 1) {
            String label = "x" + msg.duplicateCount();
            int labelW = (int)(textRenderer.getWidth(label) * s);
            int labelX, labelY = bubbleY + (bubbleH - (int)(textRenderer.fontHeight * s)) / 2;
            if (own) { labelX = bubbleX - labelW - 3; } else { labelX = bubbleX + bubbleW + 3; }
            g.getMatrices().pushMatrix();
            g.getMatrices().translate(labelX, labelY);
            if (s != 1f) g.getMatrices().scale(s, s);
            g.drawText(textRenderer, label, 0, 0, ChatBubbleTheme.alphaBlend(c().duplicateLabel(), (int)(255 * alpha)), false);
            g.getMatrices().popMatrix();
        }

        if (msg.replyContent() != null) {
            int quoteMaxW = panelW - PAD * 2 - Appearance.avatarSize() - 24;
            String quoteText = "↳ " + msg.replySender() + ": " + msg.replyContent();
            String quoteDisplay = textRenderer.trimToWidth(quoteText, Math.max(8, (int)((quoteMaxW - 10) / s)));
            if (!quoteDisplay.equals(quoteText)) quoteDisplay += "...";
            int quoteTextW = (int)(textRenderer.getWidth(quoteDisplay) * s);
            int quoteW = Math.min(quoteTextW + (int)(8 * s), quoteMaxW);
            int quoteH = Math.max(1, (int)((textRenderer.fontHeight + 4) * s));
            int quoteY = bubbleY + bubbleH + 3;
            int quoteX;
            if (own) { quoteX = bubbleX + bubbleW - quoteW; } else { quoteX = bubbleX; }
            if (quoteX < panelX + PAD) quoteX = panelX + PAD;
            if (quoteX + quoteW > panelX + panelW - PAD) quoteW = panelX + panelW - PAD - quoteX;
            // 引用块：SDF 圆角（随 bubble_size 缩放
        RoundRectRenderer.fill(g, quoteX, quoteY, quoteX + quoteW, quoteY + quoteH, ChatBubbleClientSetup.config().bubbleCornerRadius() * s, ChatBubbleTheme.alphaBlend(c().contextHover(), (int)(255 * alpha)));
            // 点它跳到被引用的原消息（命中矩形与气 ?链接 span 同为屏幕坐标
            quoteRects.add(new int[]{quoteX, quoteY, quoteW, quoteH, index});
            int beforeQuote = textSpans.size();
            g.getMatrices().pushMatrix();
            g.getMatrices().translate(quoteX + (int)(4 * s), quoteY + (int)(2 * s));
            if (s != 1f) g.getMatrices().scale(s, s);
            drawSelectableText(g, Text.literal(quoteDisplay).asOrderedText(), 0, 0,
                ChatBubbleTheme.alphaBlend(c().textSecondary(), (int)(255 * alpha)),
                index, 99, TextSpan.KIND_QUOTE, s);
            g.getMatrices().popMatrix();
            for (int i = beforeQuote; i < textSpans.size(); i++) {
                TextSpan sp = textSpans.get(i);
                textSpans.set(i, sp.withPosition(
                    quoteX + (int)(4 * s) + (int)(sp.x() * s),
                    quoteY + (int)(2 * s) + (int)(sp.y() * s),
                    Math.max(1, (int)(sp.w() * s)),
                    Math.max(1, (int)(sp.h() * s))));
            }
        }

        bubbleRects.add(new int[]{bubbleX, bubbleY, bubbleW, bubbleH, index});
    }

    /** Bubble-less image message: name + avatar + optional text + images
     * (320px long-edge, aspect preserved, stacked vertically, direction-aligned). */
    private void renderNoBubbleMessage(DrawContext g, ChatMessageStore.ChatMessage msg, int index, int baseY,
            boolean own, float alpha, BracketCodec.ParseResult parsed, List<OrderedText> lines, boolean showAvatar) {
        int avatarX = own ? panelX + panelW - PAD - Appearance.avatarSize() : panelX + PAD;

        if (!msg.senderName().getString().isEmpty()) {
            int maxNameW = panelW - Appearance.avatarSize() - PAD * 2 - 20;
            Text sn = msg.senderName();
            OrderedText nameSeq;
            if (textRenderer.getWidth(sn) > maxNameW) {
                var cut = textRenderer.trimToWidth(sn, maxNameW - textRenderer.getWidth("..."));
                nameSeq = Language.getInstance().reorder(
                    StringVisitable.concat(cut, StringVisitable.plain("...")));
            } else {
                nameSeq = sn.asOrderedText();
            }
            int nameW = textRenderer.getWidth(nameSeq);
            int startX = own ? (avatarX - UiTokens.AVATAR_NAME_GAP - nameW) : (avatarX + Appearance.avatarSize() + UiTokens.AVATAR_GAP);
            drawSelectableText(g, nameSeq, startX, baseY,
                ChatBubbleTheme.alphaBlend(c().nameColor(), (int) (255 * alpha)), index, 0, TextSpan.KIND_NAME, 1f);
        }

        Identifier skin = com.niuqu.chatbubble.render.SkinResolver.getSkin(msg.senderUUID(), msg.rawPlayerName());
        // 头像顶与名字行顶对齐 ?.3.16 曾改内容顶对齐，实测回退老锚点）
        if (showAvatar) drawPlayerHead(g, skin, avatarX, baseY, Appearance.avatarSize(), Appearance.avatarSize() + 2, alpha);

        int maxTextW = 0;
        for (var line : lines) maxTextW = Math.max(maxTextW, textRenderer.getWidth(line));
        int textX = own ? (avatarX - UiTokens.AVATAR_NAME_GAP - maxTextW) : (avatarX + Appearance.avatarSize() + UiTokens.AVATAR_GAP);

        int y = baseY + NAME_H;
        if (!lines.isEmpty()) {
            int fg = own
                ? ChatBubbleConfig.parseHexColor(ChatBubbleClientSetup.config().ownTextColor(), 0xFFFFFFFF)
                : ChatBubbleConfig.parseHexColor(ChatBubbleClientSetup.config().otherTextColor(), c().textPrimary());
            Style fb = findRootClickStyle(msg.content());
            int fgA = ChatBubbleTheme.alphaBlend(fg, (int) (255 * alpha));
            for (int li = 0; li < lines.size(); li++)
                renderLineWithClicks(g, lines.get(li), textX, y + li * textRenderer.fontHeight, fgA, fb,
                    index, li, TextSpan.KIND_CONTENT, 1f);
            y += lines.size() * textRenderer.fontHeight;
        }

        // Long-edge clamped to the panel's usable width so a narrow window/guiScale
        // can never push the image off-screen; small images keep their real size.
        int maxImgW = Math.max(80, panelW - Appearance.avatarSize() - PAD * 2 - 16);

        for (var ref : parsed.images()) {
            int w = maxImgW, h = maxImgW;
            ImageEntry entry = ImageLoader.getOrLoad(ref.url());
            if (entry != null && entry.state() == ImageEntry.State.LOADED
                    && entry.width() > 0 && entry.height() > 0) {
                float ratio = Math.min((float) maxImgW / entry.width(),
                    (float) maxImgW / entry.height());
                ratio = Math.min(1f, ratio); // never upscale
                w = Math.max(1, (int) (entry.width() * ratio));
                h = Math.max(1, (int) (entry.height() * ratio));
            }
            int imgX = own ? (avatarX - UiTokens.AVATAR_NAME_GAP - w) : (avatarX + Appearance.avatarSize() + UiTokens.AVATAR_GAP);
            if (entry != null && entry.state() == ImageEntry.State.LOADED && entry.textureId() != null) {
                g.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, entry.textureId(), imgX, y, 0f, 0f, w, h,
                    entry.width(), entry.height(), entry.width(), entry.height());
            } else {
                boolean limited = entry != null && entry.state() == ImageEntry.State.FAILED
                    && entry.failure() != null && entry.failure().contains("rate limited");
                String txt = limited
                    ? Text.translatable("e33chat.image.ratelimited").getString()
                    : entry != null && entry.state() == ImageEntry.State.FAILED
                        ? Text.translatable("e33chat.image.failed").getString()
                        : Text.translatable("e33chat.image.loading").getString();
                g.drawText(textRenderer, txt, imgX, y,
                    ChatBubbleTheme.alphaBlend(limited ? 0xFFFF5555 : c().textSecondary(), (int) (255 * alpha)), false);
            }
            // Open the URL in the system browser on click; hover shows the URL
            Style st = Style.EMPTY
                .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(ref.url())))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(ref.url())));
            clickableSpans.add(new ClickableSpan(imgX, y, w, h, st));
            y += h + 2;
        }

        if (msg.duplicateCount() > 1) {
            String label = "x" + msg.duplicateCount();
            int lx = own ? (avatarX - UiTokens.AVATAR_NAME_GAP - textRenderer.getWidth(label) - 3) : (avatarX + Appearance.avatarSize() + UiTokens.AVATAR_GAP + 3);
            g.drawText(textRenderer, label, lx, baseY + NAME_H + 2,
                ChatBubbleTheme.alphaBlend(c().duplicateLabel(), (int) (255 * alpha)), false);
        }

        if (msg.replyContent() != null) {
            int quoteMaxW = panelW - PAD * 2 - Appearance.avatarSize() - 24;
            String quoteText = "↳ " + msg.replySender() + ": " + msg.replyContent();
            String quoteDisplay = textRenderer.trimToWidth(quoteText, quoteMaxW - 10);
            if (!quoteDisplay.equals(quoteText)) quoteDisplay += "...";
            int quoteW = Math.min(textRenderer.getWidth(quoteDisplay) + 8, quoteMaxW);
            int quoteX = own ? (avatarX - UiTokens.AVATAR_NAME_GAP - quoteW) : (avatarX + Appearance.avatarSize() + UiTokens.AVATAR_GAP);
            if (quoteX < panelX + PAD) quoteX = panelX + PAD;
            if (quoteX + quoteW > panelX + panelW - PAD) quoteW = panelX + panelW - PAD - quoteX;
            RoundRectRenderer.fill(g, quoteX, y, quoteX + quoteW, y + textRenderer.fontHeight + 4, ChatBubbleClientSetup.config().bubbleCornerRadius(),
                ChatBubbleTheme.alphaBlend(c().contextHover(), (int) (255 * alpha)));
            // 点它跳到被引用的原消息（无气泡消息同样支持）
            quoteRects.add(new int[]{quoteX, y, quoteW, textRenderer.fontHeight + 4, index});
            g.drawText(textRenderer, quoteDisplay, quoteX + 4, y + 2,
                ChatBubbleTheme.alphaBlend(c().textSecondary(), (int) (255 * alpha)), false);
        }

        // Hit-test region for avatar clicks / context menus: the message span.
        bubbleRects.add(new int[]{own ? avatarX - 8 - maxTextW : avatarX + Appearance.avatarSize() + UiTokens.AVATAR_GAP,
            baseY, Math.max(maxTextW, maxImgW), y - baseY, index});
    }

    /** QQ-style emote: bubble-less image, max 64px, aligned by direction. */
    private void renderEmoteMessage(DrawContext g, ChatMessageStore.ChatMessage msg, int index, int baseY, boolean own, float alpha, boolean showAvatar) {
        BracketCodec.ParseResult parsed = parseImages(msg);
        if (parsed.images().isEmpty()) return;
        BracketCodec.ImageRef ref = parsed.images().get(0);

        int avatarX = own ? panelX + panelW - PAD - Appearance.avatarSize() : panelX + PAD;
        int nameY = baseY;

        if (!msg.senderName().getString().isEmpty()) {
            int maxNameW = panelW - Appearance.avatarSize() - PAD * 2 - 20;
            Text sn = msg.senderName();
            OrderedText nameSeq;
            if (textRenderer.getWidth(sn) > maxNameW) {
                var cut = textRenderer.trimToWidth(sn, maxNameW - textRenderer.getWidth("..."));
                nameSeq = Language.getInstance().reorder(
                    StringVisitable.concat(cut, StringVisitable.plain("...")));
            } else {
                nameSeq = sn.asOrderedText();
            }
            int nameW = textRenderer.getWidth(nameSeq);
            int startX = own ? (avatarX - UiTokens.AVATAR_NAME_GAP - nameW) : (avatarX + Appearance.avatarSize() + UiTokens.AVATAR_GAP);
            drawSelectableText(g, nameSeq, startX, nameY,
                ChatBubbleTheme.alphaBlend(c().nameColor(), (int) (255 * alpha)), index, 0, TextSpan.KIND_NAME, 1f);
        }

        Identifier skin = com.niuqu.chatbubble.render.SkinResolver.getSkin(msg.senderUUID(), msg.rawPlayerName());
        if (showAvatar) drawPlayerHead(g, skin, avatarX, baseY, Appearance.avatarSize(), Appearance.avatarSize() + 2, alpha);

        int emoteY = baseY + NAME_H + 2;
        int maxE = Math.max(16, Math.min(EMOTE_MAX_SIZE, panelW - Appearance.avatarSize() - PAD * 2 - 16));
        int w = maxE, h = maxE;
        ImageEntry entry = ImageLoader.getOrLoad(ref.url());
        if (entry != null && entry.state() == ImageEntry.State.LOADED
                && entry.width() > 0 && entry.height() > 0) {
            float ratio = Math.min((float) maxE / entry.width(),
                (float) maxE / entry.height());
            ratio = Math.min(1f, ratio); // never upscale
            w = Math.max(1, (int) (entry.width() * ratio));
            h = Math.max(1, (int) (entry.height() * ratio));
        }
        int emoteX = own ? (avatarX - UiTokens.AVATAR_NAME_GAP - w) : (avatarX + Appearance.avatarSize() + UiTokens.AVATAR_GAP);
        if (entry != null && entry.state() == ImageEntry.State.LOADED && entry.textureId() != null) {
            g.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, entry.textureId(), emoteX, emoteY, 0f, 0f, w, h,
                entry.width(), entry.height(), entry.width(), entry.height());
        } else {
            boolean limited = entry != null && entry.state() == ImageEntry.State.FAILED
                && entry.failure() != null && entry.failure().contains("rate limited");
            String txt = limited
                ? Text.translatable("e33chat.image.ratelimited").getString()
                : entry != null && entry.state() == ImageEntry.State.FAILED
                    ? Text.translatable("e33chat.image.failed").getString()
                    : Text.translatable("e33chat.image.loading").getString();
            g.drawText(textRenderer, txt, emoteX, emoteY,
                ChatBubbleTheme.alphaBlend(limited ? 0xFFFF5555 : c().textSecondary(), (int) (255 * alpha)), false);
        }
    }

    private void renderLineWithClicks(DrawContext g, OrderedText line, int x, int y, int color) {
        renderLineWithClicks(g, line, x, y, color, null, -1, -1, 1, 1f);
    }

    private void renderLineWithClicks(DrawContext g, OrderedText line, int x, int y, int color, Style fallback) {
        renderLineWithClicks(g, line, x, y, color, fallback, -1, -1, 1, 1f);
    }

    /** Selectable variant: records a TextSpan (for hit-testing) and paints the
     * selection background before the glyphs, then draws with the selected run
     * recoloured. x/y are in the current matrix space; the caller back-fills the
     * span to screen space when the matrix is scaled (bubble path). */
    private void renderLineWithClicks(DrawContext g, OrderedText line, int x, int y, int color, Style fallback,
                                      int messageIndex, int lineIndex, int kind, float scale) {
        TextSpan span = null;
        if (messageIndex >= 0) {
            span = new TextSpan(messageIndex, lineIndex, kind, x, y, textRenderer.getWidth(line),
                textRenderer.fontHeight, orderedTextPlain(line), scale, line);
            textSpans.add(span);
        }

        int[] selRange = span != null && textSelection.hasSelection() ? textSelection.rangeFor(span) : null;
        if (selRange != null) {
            int sx0 = x + prefixWidth(line, selRange[0]);
            int sx1 = x + prefixWidth(line, selRange[1]);
            g.fill(sx0, y, Math.max(sx0 + 1, sx1), y + textRenderer.fontHeight, ChatTextSelection.selectionBg());
        }

        renderLineWithClicks(g, line, x, y, color, fallback, selRange);
    }

    private static String orderedTextPlain(OrderedText line) {
        StringBuilder sb = new StringBuilder();
        line.accept((i, st, cp) -> { sb.appendCodePoint(cp); return true; });
        return sb.toString();
    }

    private void renderLineWithClicks(DrawContext g, OrderedText line, int x, int y, int color, Style fallback, int[] selRange) {
        final List<Style> styles = new ArrayList<>();
        line.accept((i, st, cp) -> { styles.add(st); return true; });

        final int beforeCount = clickableSpans.size();
        int runStart = -1;
        Style runStyle = null;
        List<int[]> clickableCharRanges = new ArrayList<>();
        for (int idx = 0; idx <= styles.size(); idx++) {
            Style st = idx < styles.size() ? styles.get(idx) : null;
            boolean clickable = st != null && (st.getClickEvent() != null || st.getHoverEvent() != null);
            if (runStyle == null) {
                if (clickable) { runStart = idx; runStyle = st; }
            } else if (!clickable || !st.equals(runStyle)) {
                int x0 = prefixWidth(line, runStart);
                int x1 = prefixWidth(line, idx);
                clickableSpans.add(new ClickableSpan(x + x0, y, x1 - x0, textRenderer.fontHeight, runStyle));
                clickableCharRanges.add(new int[]{runStart, idx});
                runStart = clickable ? idx : -1;
                runStyle = clickable ? st : null;
            }
        }

        if (fallback != null && fallback.getClickEvent() != null) {
            if (clickableSpans.size() == beforeCount) {
                clickableSpans.add(new ClickableSpan(x, y, textRenderer.getWidth(line), textRenderer.fontHeight,
                    fallback.withUnderline(true)));
                clickableCharRanges.add(new int[]{0, styles.size()});
            } else {
                for (int i = beforeCount; i < clickableSpans.size(); i++) {
                    ClickableSpan s = clickableSpans.get(i);
                    if (s.style.getClickEvent() == null) {
                        clickableSpans.set(i, new ClickableSpan(s.x, s.y, s.w, s.h,
                            s.style.withClickEvent(fallback.getClickEvent())));
                    }
                }
            }
        }

        int styleLen = styles.size();
        boolean[] hasClickEvent = new boolean[styleLen];
        for (int ri = 0; ri < clickableCharRanges.size(); ri++) {
            int spanIdx = beforeCount + ri;
            if (spanIdx < clickableSpans.size()
                && clickableSpans.get(spanIdx).style.getClickEvent() != null) {
                int[] r = clickableCharRanges.get(ri);
                for (int i = r[0]; i < r[1]; i++) hasClickEvent[i] = true;
            }
        }

        int[] idx = {0};
        OrderedText decorated = sink -> line.accept((i, st, cp) -> {
            int pos = Math.min(idx[0]++, styleLen);
            Style st2 = st;
            // Selected glyphs are repainted white (selection bg is dark).
            if (selRange != null && pos >= selRange[0] && pos < selRange[1]) {
                st2 = st.withColor(TextColor.fromRgb(ChatTextSelection.selectionFg()));
            }
            boolean underline = pos < styleLen ? hasClickEvent[pos] : st2.getClickEvent() != null;
            return sink.accept(i, underline && !st2.isUnderlined() ? st2.withUnderline(true) : st2, cp);
        });
        g.drawText(textRenderer, decorated, x, y, color, false);

    }

    /** Draws one line as selectable text (name / quote rows that are not routed
     * through renderLineWithClicks) and paints its selection background. */
    private void drawSelectableText(DrawContext g, OrderedText line, int x, int y, int color,
                                    int messageIndex, int lineIndex, int kind, float scale) {
        TextSpan span = new TextSpan(messageIndex, lineIndex, kind, x, y, textRenderer.getWidth(line),
            textRenderer.fontHeight, orderedTextPlain(line), scale, line);
        if (messageIndex >= 0) textSpans.add(span);

        OrderedText draw = line;
        if (messageIndex >= 0 && textSelection.hasSelection()) {
            int[] r = textSelection.rangeFor(span);
            if (r != null) {
                int sx0 = x + prefixWidth(line, r[0]);
                int sx1 = x + prefixWidth(line, r[1]);
                g.fill(sx0, y, Math.max(sx0 + 1, sx1), y + textRenderer.fontHeight, ChatTextSelection.selectionBg());
                int fs = r[0];
                int fe = r[1];
                int[] idx = {0};
                draw = sink -> line.accept((i, st, cp) -> {
                    int pos = idx[0]++;
                    return sink.accept(i, pos >= fs && pos < fe
                        ? st.withColor(TextColor.fromRgb(ChatTextSelection.selectionFg())) : st, cp);
                });
            }
        }

        g.drawText(textRenderer, draw, x, y, color, false);
    }

    private TextSpan findTextSpanAt(double mouseX, double mouseY) {
        for (int i = textSpans.size() - 1; i >= 0; i--) {
            TextSpan s = textSpans.get(i);
            if (mouseX >= s.x() && mouseX <= s.x() + s.w() && mouseY >= s.y() && mouseY <= s.y() + s.h()) {
                return s;
            }
        }
        return null;
    }

    private TextSpan findNearestTextSpan(double mouseX, double mouseY) {
        TextSpan best = null;
        double bestDist = Double.MAX_VALUE;
        for (TextSpan s : textSpans) {
            double cx = Math.max((double) s.x(), Math.min(mouseX, (double) (s.x() + s.w())));
            double cy = Math.max((double) s.y(), Math.min(mouseY, (double) (s.y() + s.h())));
            double dx = mouseX - cx;
            double dy = mouseY - cy;
            double dist = dx * dx + dy * dy;
            if (dist < bestDist) {
                bestDist = dist;
                best = s;
            }
        }
        return best;
    }

    /** Code-point index inside the span that the given screen x falls on. */
    private int charAt(TextSpan span, double mouseX) {
        String text = span.text();
        if (text.isEmpty()) return 0;
        double localX = (mouseX - span.x()) / span.scale();
        if (span.visualLine() instanceof OrderedText ot) {
            int lo = 0;
            int hi = text.codePointCount(0, text.length());
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (prefixWidth(ot, mid) <= localX) lo = mid;
                else hi = mid - 1;
            }
            return lo;
        }
        return text.codePointCount(0, text.length());
    }

    /** Auto-scrolls while dragging past the top/bottom edge of the message area. */
    private void autoScrollSelection(double mouseY) {
        if (mouseY < msgTop + 16 && scrollOffset > 0) {
            scrollOffset = Math.max(0, scrollOffset - 4);
            textSelection.markMoved();
            scrollToBottom = false;
        } else if (mouseY > msgBottom - 16 && scrollOffset < maxScroll) {
            scrollOffset = Math.min(maxScroll, scrollOffset + 4);
            textSelection.markMoved();
            scrollToBottom = false;
        }
    }

    private int prefixWidth(OrderedText line, int count) {
        if (count <= 0) return 0;
        return textRenderer.getWidth((OrderedText) sink -> {
            int[] left = {count};
            line.accept((i, st, cp) -> left[0]-- > 0 && sink.accept(i, st, cp));
            return true;
        });
    }

    private Style findRootClickStyle(Text c) {
        // Only a click event on the root/wrapper style is a true "parent-level"
        // fallback. A click event buried in one sibling must NOT underline or
        // make clickable unrelated lines/segments; per-character styles already
        // carry inherited parent styles, so the recursive search is unnecessary
        // and caused whole-line underlines on system messages.
        Style s = c.getStyle();
        return s != null && s.getClickEvent() != null ? s : null;
    }

    private Style getHoveredStyle(double mouseX, double mouseY) {
        for (ClickableSpan s : clickableSpans) {
            if (mouseX >= s.x && mouseX <= s.x + s.w
                && mouseY >= s.y && mouseY <= s.y + s.h)
                return s.style;
        }
        return null;
    }

    private void renderNotificationBar(DrawContext g, int mouseX, int mouseY) {
        if (newMessageCount <= 0) return;
        int notifY = barTop - NOTIF_H;
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), panelX, notifY - 1, panelW, 1, getAnimProgress());
        int yellow = c().notificationText();
        int textY = notifY + (NOTIF_H - textRenderer.fontHeight) / 2;
        String ct = Text.translatable("e33chat.notif.new_messages", newMessageCount).getString() + " ▽";
        notifCountLeft = panelX + PAD;
        notifCountRight = notifCountLeft + textRenderer.getWidth(ct);
        notifBarTextY = textY;
        boolean h = mouseX >= notifCountLeft && mouseX <= notifCountRight
            && mouseY >= textY && mouseY <= textY + textRenderer.fontHeight;
        g.drawText(textRenderer, ct, notifCountLeft, textY, h ? c().notificationText() : yellow, false);
        if (hasNewMentionOrQuote) {
            String mt = Text.translatable("e33chat.notif.mention").getString() + " ▽";
            notifMentionLeft = panelX + panelW - PAD - textRenderer.getWidth(mt);
            notifMentionRight = notifMentionLeft + textRenderer.getWidth(mt);
            h = mouseX >= notifMentionLeft && mouseX <= notifMentionRight
                && mouseY >= textY && mouseY <= textY + textRenderer.fontHeight;
            g.drawText(textRenderer, mt, notifMentionLeft, textY, h ? c().notificationText() : yellow, false);
        } else {
            notifMentionLeft = -1; notifMentionRight = -1;
        }
    }

    private void renderContextMenu(DrawContext g, int mouseX, int mouseY) {
        if (contextMsgIndex < 0) return;
        int items = contextMenuItemCount();
        int menuH = contextMenuHeight();
        int menuX = Math.min(contextX, panelX + panelW - CTX_W - 2);
        int menuY = contextY - menuH;
        if (menuY < msgTop) menuY = contextY + 4;
        float alpha = getAnimProgress();
        int a255 = (int) (255 * alpha);

        // 圆角化（1.21.11 restyle）：与设置菜 ?表情/快捷聊天面板同一套调用方
// （RoundRectRenderer.panel 外框 9f + 内缩 1px  ?5f 行高亮），矩 ?位置/尺寸/条目均不变
// 配色取纹 ?CONTEXT_MENU_BG/CONTEXT_HOVER/SIDEBAR_SELECTED 烘焙所用的基础调色
// （theme().colors()），不取含强调色混合 ?c()，避免菜单底 ?高亮被染色
        ChatBubbleTheme.Colors base = theme().colors();
        RoundRectRenderer.panel(g, menuX, menuY, CTX_W, menuH, CTX_RADIUS,
            // 保留各色自身 ?alpha（contextBg 烘焙 ?0xEE = 93% 半透明），只按开合动画缩放，
            // 否则菜单底色会从不透明变成半透明以外的观感差异
        ChatBubbleTheme.alphaBlend(base.contextBg(), (int) (a255 * ((base.contextBg() >>> 24) & 0xFF) / 255f)),
            ChatBubbleTheme.alphaBlend(base.divider(), (int) (a255 * ((base.divider() >>> 24) & 0xFF) / 255f)));

        // 只有悬停的行才有圆角高亮（非悬停 ?alpha=0 即不画）：整个二级菜单是一个圆角面板，
        // 而不是每行各画一个圆角药 ?— ?与设置二级菜单（ChatSettingsMenu）的观感一致
boolean hoverCopy = mouseX >= menuX && mouseX <= menuX + CTX_W
            && mouseY >= menuY && mouseY <= menuY + CTX_ITEM_H;
        RoundRectRenderer.fill(g, menuX + 1, menuY + 1, menuX + CTX_W - 1, menuY + CTX_ITEM_H, CTX_ITEM_RADIUS,
            ChatBubbleTheme.alphaBlend(base.contextHover(), hoverCopy ? a255 : 0));
        drawTextureIconAlpha(g, iconTex("copy"), menuX + 5, menuY + 3, 12, alpha);
        g.drawText(textRenderer, Text.translatable("e33chat.context.copy").getString(), menuX + 22, menuY + 4, c().textPrimary(), false);

        // 系统消息只画「复制」这一项（items == 1）：引用对系统消息没有意义，
        // 菜单高度已按实际条目数算，所以这里直接不画第二行、也不留空位。
        if (items > 1) {
            boolean hoverQuote = mouseX >= menuX && mouseX <= menuX + CTX_W
                && mouseY >= menuY + CTX_ITEM_H + 1 && mouseY <= menuY + menuH;
            RoundRectRenderer.fill(g, menuX + 1, menuY + CTX_ITEM_H + 1, menuX + CTX_W - 1, menuY + CTX_ITEM_H * 2 + 1,
                CTX_ITEM_RADIUS,
                ChatBubbleTheme.alphaBlend(base.contextHover(), hoverQuote ? a255 : 0));
            drawTextureIconAlpha(g, iconTex("quote"), menuX + 5, menuY + CTX_ITEM_H + 3, 12, alpha);
            g.drawText(textRenderer, Text.translatable("e33chat.context.quote").getString(), menuX + 22, menuY + CTX_ITEM_H + 5, c().textPrimary(), false);
        }
    }

    private void renderAvatarContextMenu(DrawContext g, int mouseX, int mouseY) {
        if (contextAvatarIndex < 0) return;
        int menuH = CTX_ITEM_H * 3 + 4;
        int menuX = Math.min(contextAvatarX, panelX + panelW - CTX_W - 2);
        int menuY = contextAvatarY - menuH;
        if (menuY < msgTop) menuY = contextAvatarY + 4;
        float alpha = getAnimProgress();
        int a255 = (int) (255 * alpha);

        // 与消息右键菜单同一套圆角（RoundRectRenderer.panel 9f + 内缩 1px  ?5f 行高亮）
        ChatBubbleTheme.Colors base = theme().colors();
        RoundRectRenderer.panel(g, menuX, menuY, CTX_W, menuH, CTX_RADIUS,
            // 保留各色自身 ?alpha（contextBg 烘焙 ?0xEE = 93% 半透明），只按开合动画缩放，
            // 否则菜单底色会从不透明变成半透明以外的观感差异
        ChatBubbleTheme.alphaBlend(base.contextBg(), (int) (a255 * ((base.contextBg() >>> 24) & 0xFF) / 255f)),
            ChatBubbleTheme.alphaBlend(base.divider(), (int) (a255 * ((base.divider() >>> 24) & 0xFF) / 255f)));

        boolean hoverTp = mouseX >= menuX && mouseX <= menuX + CTX_W
            && mouseY >= menuY && mouseY <= menuY + CTX_ITEM_H;
        RoundRectRenderer.fill(g, menuX + 1, menuY + 1, menuX + CTX_W - 1, menuY + CTX_ITEM_H, CTX_ITEM_RADIUS,
            ChatBubbleTheme.alphaBlend(base.contextHover(), hoverTp ? a255 : 0));
        drawTextureIconAlpha(g, iconTex("tp"), menuX + 5, menuY + 3, 12, alpha);
        g.drawText(textRenderer, Text.translatable(ChatMessageStore.useTpa() ? "e33chat.context.tpa" : "e33chat.context.tp").getString(), menuX + 22, menuY + 4, c().textPrimary(), false);

        boolean hoverWhisper = mouseX >= menuX && mouseX <= menuX + CTX_W
            && mouseY >= menuY + CTX_ITEM_H + 2 && mouseY <= menuY + CTX_ITEM_H * 2 + 2;
        RoundRectRenderer.fill(g, menuX + 1, menuY + CTX_ITEM_H + 2, menuX + CTX_W - 1, menuY + CTX_ITEM_H * 2 + 2,
            CTX_ITEM_RADIUS,
            ChatBubbleTheme.alphaBlend(base.contextHover(), hoverWhisper ? a255 : 0));
        drawTextureIconAlpha(g, iconTex("whisper"), menuX + 5, menuY + CTX_ITEM_H + 4, 12, alpha);
        g.drawText(textRenderer, Text.translatable("e33chat.context.whisper").getString(), menuX + 22, menuY + CTX_ITEM_H + 6, c().textPrimary(), false);

        boolean hoverBlock = mouseX >= menuX && mouseX <= menuX + CTX_W
            && mouseY >= menuY + CTX_ITEM_H * 2 + 4 && mouseY <= menuY + menuH;
        RoundRectRenderer.fill(g, menuX + 1, menuY + CTX_ITEM_H * 2 + 4, menuX + CTX_W - 1,
            menuY + CTX_ITEM_H * 3 + 4, CTX_ITEM_RADIUS,
            ChatBubbleTheme.alphaBlend(base.contextHover(), hoverBlock ? a255 : 0));
        drawTextureIconAlpha(g, iconTex("block"), menuX + 5, menuY + CTX_ITEM_H * 2 + 6, 12, alpha);
        ChatMessageStore.ChatMessage avaMsg = ChatMessageStore.getMessageAt(contextAvatarIndex);
        boolean isBlocked = avaMsg != null
            && BlockList.isPlayerBlocked(avaMsg.rawPlayerName(), avaMsg.senderName(),
                ChatBubbleClientSetup.config().blockedPlayers());
        g.drawText(textRenderer, Text.translatable(isBlocked ? "e33chat.context.unblock" : "e33chat.context.block").getString(),
            menuX + 22, menuY + CTX_ITEM_H * 2 + 8, c().textPrimary(), false);
    }

    private static final int REPLY_BAR_H = 18;
    private static final int BOTTOM_MESSAGE_PAD = 6;

    private void renderReplyBar(DrawContext g, int mouseX, int mouseY) {
        if (replyTargetIndex < 0) return;
        ChatMessageStore.ChatMessage target = ChatMessageStore.getMessageAt(replyTargetIndex);
        if (target == null) { replyTargetIndex = -1; return; }

        int notifOffset = (newMessageCount > 0) ? NOTIF_H : 0;
        int gearX = panelX + 4;
        int sendX = panelX + panelW - PAD - ICON_S + 2;
        int barX = gearX + ICON_S + 4;
        int barW = sendX - 6 - barX;
        int barY = barTop - REPLY_BAR_H - notifOffset;

        float panelBgAlpha = (c().panelBg() >>> 24) / 255f;
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.PANEL_BG),
            barX, barY, barW, barTop - notifOffset - barY, panelBgAlpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), barX, barTop - notifOffset - 1, barW, 1, getAnimProgress());

        String sender = target.senderName().getString();
        if (sender.isEmpty()) sender = Text.translatable("e33chat.sender.system").getString();
        String preview = sender + ": " + target.content().getString();
        int maxW = barW - 24;
        String display = textRenderer.trimToWidth(preview, maxW - textRenderer.getWidth("..."));
        if (!display.equals(preview)) display += "...";
        g.drawText(textRenderer, display, barX + 6, barY + 4, c().textSecondary(), false);

        int cx = barX + barW - 16;
        int cy = barY + 3;
        boolean hoverX = mouseX >= cx && mouseX <= cx + 12 && mouseY >= cy && mouseY <= cy + 12;
        int xBg = hoverX ? c().closeHoverBg() : c().sidebarItemSelected();
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(hoverX ? UiElement.CLOSE_HOVER : UiElement.SIDEBAR_SELECTED),
            cx, cy, 12, 12, getAnimProgress());
        g.drawText(textRenderer, "✕", cx + 6 - textRenderer.getWidth("✕") / 2, cy + 2, c().closeText(), false);
    }

    private boolean isMouseOverReplyCancel(double mx, double my) {
        if (replyTargetIndex < 0) return false;
        int notifOffset = (newMessageCount > 0) ? NOTIF_H : 0;
        int gearX = panelX + 4;
        int sendX = panelX + panelW - PAD - ICON_S + 2;
        int barX = gearX + ICON_S + 4;
        int barW = sendX - 6 - barX;
        int barY = barTop - REPLY_BAR_H - notifOffset;
        int cx = barX + barW - 16;
        int cy = barY + 3;
        return mx >= cx && mx <= cx + 12 && my >= cy && my <= cy + 12;
    }

    private void renderMentionPopup(DrawContext g, int mouseX, int mouseY) {
        if (!showMentions || mentionCandidates.isEmpty()) return;
        int maxW = 60;
        for (String name : mentionCandidates) maxW = Math.max(maxW, textRenderer.getWidth(name));
        int popupW = maxW + 12;
        int visible = Math.min(mentionCandidates.size(), 8);
        int popupH = visible * textRenderer.fontHeight + 4;
        int popupX = chatField.getX();
        int popupY = chatField.getY() - popupH - 2;
        if (popupY < msgTop) popupY = chatField.getY() + chatField.getHeight() + 2;

        int pa = (int) (255 * getAnimProgress());
        RoundRectRenderer.panel(g, popupX, popupY, popupW, popupH, 6f,
            ChatBubbleTheme.alphaBlend(c().popupBg(), pa), ChatBubbleTheme.alphaBlend(c().divider(), pa));

        int startIdx = Math.max(0, mentionIdx - visible + 1);
        int endIdx = Math.min(mentionCandidates.size(), startIdx + visible);
        if (endIdx - startIdx < visible) startIdx = Math.max(0, endIdx - visible);
        for (int i = startIdx; i < endIdx; i++) {
            int ly = popupY + 2 + (i - startIdx) * textRenderer.fontHeight;
            if (i == mentionIdx)
                g.fill(popupX + 1, ly, popupX + popupW - 1, ly + textRenderer.fontHeight, c().popupHover());
            g.drawText(textRenderer, mentionCandidates.get(i), popupX + 4, ly, c().textPrimary(), false);
        }
    }

    private void renderToast(DrawContext g) {
        int alpha;
        String text;
        int color;
        if (uploadToastTicks > 0) {
            alpha = Animation.fadeInOut(uploadToastTicks, 5, 20, 5);
            color = (alpha << 24) | 0x00FF5555;
            text = Text.translatable("e33chat.upload.failed").getString();
        } else if (uploadBusyTicks > 0) {
            // Upload-in-progress hint; cleared by the worker when the job finishes.
            // Same look as the copy toast (TOAST_BG texture + toastText color).
            alpha = 200;
            color = (alpha << 24) | (c().toastText() & 0x00FFFFFF);
            text = Text.translatable("e33chat.upload.start").getString();
        } else {
            if (copyToastTicks <= 0) return;
            alpha = Animation.fadeInOut(copyToastTicks, 5, 20, 5);
            color = (alpha << 24) | (c().toastText() & 0x00FFFFFF);
            text = Text.translatable("e33chat.toast.copied").getString();
        }
        int tw = textRenderer.getWidth(text);
        int tx = UiLayout.centerX(panelX, panelW, tw);
        int ty = msgBottom - 24;
        // Background fades with the text, at half opacity like the strong-hint bar
        // TOAST_BG 烘焙不透明 toastBg；纹理 × 动画 alpha = 半透明淡入淡出（2.2.4 黑块根因：
        // 当时 blit 的 alpha 通道渲染不透明纯黑，改 drawWithAlpha 后纹理可覆盖 + 透明度可控
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.TOAST_BG),
            tx - 6, ty - 2, tw + 12, textRenderer.fontHeight + 4, (alpha / 2) / 255f);
        g.drawText(textRenderer, text, tx, ty, color, false);
    }

    private void executeMenuAction(int action) {
        switch (action) {
            case 0: // search
                if (quickChatPanel.visible) beginPopupClose(s -> quickCloseStart = s, () -> {
                    quickChatPanel.visible = false;
                    quickChatInput.setVisible(false);
                });
                if (emojiPanel.visible) beginPopupClose(s -> emojiCloseStart = s, () -> emojiPanel.visible = false);
                searchPanel.visible = true;
                searchAnimStart = Util.getMeasuringTimeMs();
                searchInput.setText("");
                searchMatches.clear(); searchMatchIdx = -1; searchHighlightIndex = -1;
                setFocused(searchInput);
                break;
            case 1: // quick_chat
                if (searchPanel.visible) closeSearchPanel();
                if (emojiPanel.visible) beginPopupClose(s -> emojiCloseStart = s, () -> emojiPanel.visible = false);
                quickChatPanel.visible = true;
                quickAnimStart = Util.getMeasuringTimeMs();
                quickChatPanel.scrollOffset = 0;
                quickChatInput.setText("");
                setFocused(chatField);
                break;
            case 2: { // theme
                ChatBubbleTheme next = theme() == ChatBubbleTheme.DARK ? ChatBubbleTheme.LIGHT : ChatBubbleTheme.DARK;
                ChatBubbleClientSetup.saveConfig(ChatBubbleClientSetup.config().withTheme(next.name().toLowerCase()));
                        int editColor = next == ChatBubbleTheme.LIGHT ? c().textSecondary() : c().textPrimary();
                chatField.setEditableColor(editColor);
                chatField.setUneditableColor(c().textMuted());
                sidebarSearchBox.setEditableColor(editColor);
                sidebarSearchBox.setUneditableColor(editColor);
                quickChatInput.setEditableColor(editColor);
                quickChatInput.setUneditableColor(c().textMuted());
                searchInput.setEditableColor(editColor);
                searchInput.setUneditableColor(c().textMuted());
                int cmdAlpha = next == ChatBubbleTheme.LIGHT ? 0x99 : 0xDD;
                commandSuggestions = new ChatInputSuggestor(client, this, chatField, textRenderer,
                    false, false, 0, 8, true, ChatBubbleTheme.alphaBlend(c().panelBg(), cmdAlpha));
                commandSuggestions.setWindowActive(true);
                break;
            }
            case 3: // settings
                client.setScreen(new ChatBubbleConfigScreen(this));
                break;
        }
    }

    private void closeSearchPanel() {
        beginPopupClose(s -> searchCloseStart = s, () -> {
            searchPanel.visible = false;
            searchInput.setVisible(false);
        });
        searchInput.setVisible(false);
        searchMatches.clear(); searchMatchIdx = -1; searchHighlightIndex = -1;
        setFocused(chatField);
    }

    private void renderBottomBar(DrawContext g, int mouseX, int mouseY, float panelAlpha) {
        int a255 = (int) (255 * panelAlpha);
        int barBg = ChatBubbleTheme.alphaBlend(c().barBg(), a255);
        // 1.21.11 restyle: rounded floating card instead of the flat textured strip ( ? style).
        RoundRectRenderer.fill(g, panelX, barTop, panelX + panelW, height, 9f, barBg);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), panelX, barTop, panelW, 1, panelAlpha);

        int iconY = barTop + (BAR_H - ICON_S) / 2;

        int ibX = inputX;
        int ibY = inputBarY();
        int ibW = chatField.getWidth();
        int ibH = inputBarH();
        // Rounded input box with a subtle border. Radius uses the same set as the
        // bottom-bar icon buttons (drawIconButton: outer ring 6f + 1px inset 5f) so the
        // input looks like the other buttons; the box is taller but the visual corner
        // radius must NOT scale with the height (was 8f/9f — visibly rounder than the buttons).
        int inputBg = ChatBubbleTheme.alphaBlend(c().inputBg(), a255);
        RoundRectRenderer.fill(g, ibX - INPUT_BOX_OUTSET, ibY - INPUT_BOX_OUTSET,
            ibX + ibW + INPUT_BOX_OUTSET, ibY + ibH + INPUT_BOX_OUTSET, 6f, inputBg);
        boolean hoverInput = mouseX >= ibX - 1 && mouseX <= ibX + ibW && mouseY >= ibY && mouseY <= ibY + ibH;
        if (hoverInput || chatField.isFocused()) {
            int bcol = ChatBubbleTheme.alphaBlend(c().textMuted(), a255);
            RoundRectRenderer.fill(g, ibX - INPUT_BOX_OUTSET, ibY - INPUT_BOX_OUTSET,
                ibX + ibW + INPUT_BOX_OUTSET, ibY + ibH + INPUT_BOX_OUTSET, 6f, bcol);
            RoundRectRenderer.fill(g, ibX - 1, ibY - 1, ibX + ibW + 1, ibY + ibH + 1, 5f, inputBg);
        }

        int gearX = panelX + 4;
        int sendX = panelX + panelW - PAD - ICON_S + 2;
        int emojiX = sendX - ICON_S - 4;

        boolean hoverGear = mouseX >= gearX && mouseX <= gearX + ICON_S
            && mouseY >= iconY && mouseY <= iconY + ICON_S;
        drawIconButton(g, iconTex("settings"), gearX, iconY, ICON_S - 1, getAnimProgress(), hoverGear);

        boolean hoverEmoji = mouseX >= emojiX && mouseX <= emojiX + ICON_S
            && mouseY >= iconY && mouseY <= iconY + ICON_S;
        drawIconButton(g, iconTex("emoji"), emojiX, iconY, ICON_S - 1, getAnimProgress(), hoverEmoji || emojiPanel.visible);

        boolean hoverSend = mouseX >= sendX && mouseX <= sendX + ICON_S
            && mouseY >= iconY && mouseY <= iconY + ICON_S;
        drawIconButton(g, iconTex("send"), sendX, iconY, ICON_S - 1, getAnimProgress(), hoverSend);
    }

    /** Each bottom-bar icon sits in its own rounded button with a thin border ring ( ? style). */
    private void drawIconButton(DrawContext g, Identifier tex, int x, int y, int size, float alpha, boolean hover) {
        int a255 = (int) (255 * alpha);
        int pad = 1;
        int bx = x - pad, by = y - pad, bw = size + pad * 2, bh = size + pad * 2;
        if (bw <= 0 || bh <= 0) return;
        int border = ChatBubbleTheme.alphaBlend(c().textMuted(), a255);
        int bg = ChatBubbleTheme.alphaBlend(c().inputBg(), a255);
        // Rounded border ring: border-coloured rounded fill, then inner bg rounded fill.
        RoundRectRenderer.fill(g, bx, by, bx + bw, by + bh, 6f, border);
        RoundRectRenderer.fill(g, bx + 1, by + 1, bx + bw - 1, by + bh - 1, 5f, bg);
        if (hover)
            RoundRectRenderer.fill(g, bx, by, bx + bw, by + bh, 6f,
                ChatBubbleTheme.alphaBlend(c().popupHover(), a255));
        drawTextureIconAlpha(g, tex, x, y, size, alpha);
    }



    static void drawTextureIcon(DrawContext g, Identifier tex, int x, int y, int size) {
        drawTextureIconAlpha(g, tex, x, y, size, 1f);
    }

    /** 带透明度图标的绘制：与 drawTextureIcon 同采样语义，但走 ?alpha 的渲染路径（弹层淡入用） ?*/
    public static void drawTextureIconAlpha(DrawContext g, Identifier tex, int x, int y, int size, float alpha) {
        if (alpha <= 0.003f) return;
        if (size < 16) {
            ColoredTextureRenderer.drawWithAlpha(g, tex, x, y, size, size, 1f, 1f, 14, 14, 16, 16, alpha);
        } else {
            ColoredTextureRenderer.drawWithAlpha(g, tex, x, y, size, size, 0f, 0f, size, size, size, size, alpha);
        }
    }

    /** 带 tint 的图标绘制：采样语义与 {@link #drawTextureIconAlpha} 完全一致（size<16 时
     *  裁掉 1px 边、从 14x14 区域缩放），颜色改成 **纹理色 × argb**（GUI_TEXTURED 顶点色调制）。
     *  只对白色剪影贴图有意义：白 × 紫 = 紫；深色贴图乘紫会发黑，所以染色前要先选对贴图。 */
    public static void drawTintedIcon(DrawContext g, Identifier tex, int x, int y, int size, int argb) {
        if (((argb >>> 24) & 0xFF) <= 1) return;
        if (size < 16) {
            ColoredTextureRenderer.drawTinted(g, tex, x, y, size, size, 1f, 1f, 14, 14, 16, 16, argb);
        } else {
            ColoredTextureRenderer.drawTinted(g, tex, x, y, size, size, 0f, 0f, size, size, size, size, argb);
        }
    }

    private void drawPlayerHead(DrawContext g, Identifier skin, int x, int y, int baseSize, int hatSize, float alpha) {
        if (alpha <= 0.003f) return;
        ColoredTextureRenderer.drawWithAlpha(g, skin, x, y, baseSize, baseSize, 8.0F, 8.0F, 8, 8, 64, 64, alpha);
        int hatOff = (hatSize - baseSize) / 2;
        ColoredTextureRenderer.drawWithAlpha(g, skin, x - hatOff, y - hatOff, hatSize, hatSize, 40.0F, 8.0F, 8, 8, 64, 64, alpha);
    }





    /** 左键是否按在引用块上（引用文字之外的内边距也算）；命中则记为待派发的引用点击 ?*/
    private boolean beginQuotePress(double mouseX, double mouseY) {
        int owner = quoteOwnerAt(mouseX, mouseY);
        quoteJumpLog(() -> "[e33chat] QUOTEJUMP | PRESSPROBE | local=(" + mouseX + "," + mouseY
            + ") owner=" + owner + " rects=" + quoteRects.size() + " msgArea=[" + msgTop + "," + msgBottom + "]"
            + (owner >= 0 ? " rect=" + java.util.Arrays.toString(quoteRects.get(quoteOwnerRectIndex)) : ""));
        if (owner < 0) return false;
        quotePressX = mouseX;
        quotePressY = mouseY;
        return true;
    }

    /** 命中引用块时返回「持有该引用的消息」下标（全量列表下标，与 bubbleRects 同一套），否 ?-1 ?*/
    private int quoteOwnerAt(double mouseX, double mouseY) {
        quoteOwnerRectIndex = -1;
        if (mouseY < msgTop || mouseY > msgBottom) return -1;
        for (int i = quoteRects.size() - 1; i >= 0; i--) {
            int[] r = quoteRects.get(i);
            if (mouseX >= r[0] && mouseX <= r[0] + r[2] && mouseY >= r[1] && mouseY <= r[1] + r[3]) {
                quoteOwnerRectIndex = i;
                return r[4];
            }
        }
        return -1;
    }

    /** 双击引用块：滚动到被引用的原消息并短暂高亮；原消息已不在列表时静默无操作（只吞掉这次双击）。
     *  单击不会走到这里（单击保持选字/无操作），所以这里每触发一次只跳一次。 */
    private boolean handleQuoteClick(double mouseX, double mouseY) {
        int owner = quoteOwnerAt(mouseX, mouseY);
        quoteJumpLog(() -> "[e33chat] QUOTEJUMP | CLICK | local=(" + mouseX + "," + mouseY
            + ") owner=" + owner + " rects=" + quoteRects.size()
            + (quoteOwnerRectIndex >= 0 ? " rect=" + java.util.Arrays.toString(quoteRects.get(quoteOwnerRectIndex)) : ""));
        if (owner < 0) return false;
        int target = resolveQuoteTarget(owner);
        if (target >= 0) {
            int before = scrollOffset;
            jumpToMessage(target);
            // 抑制「自动滚到底」和仍在跑的滚动动画：下一 ?renderMessages 会读这两个开关，
            // 不关掉的话刚跳到的位置会被立刻拉回底 ?/ 被动画值覆盖
            scrollToBottom = false;
            scrollAnimActive = false;
            lastScrollTime = Util.getMeasuringTimeMs();
            quoteFlashMsg = ChatMessageStore.getMessages().get(target);
            quoteFlashStart = Util.getMeasuringTimeMs();
            final int after = scrollOffset;
            final int tgt = target;
            quoteJumpLog(() -> "[e33chat] QUOTEJUMP | JUMP-EXEC | target=" + tgt
                + " scrollOffset " + before + " -> " + after + " maxScroll=" + maxScroll
                + " msgTotalH=" + messageTotalH + " scrollToBottom=" + scrollToBottom
                + " flash=" + (quoteFlashMsg != null));
            quoteJumpFrameLogs = 3;
        } else {
            quoteJumpLog(() -> "[e33chat] QUOTEJUMP | ABORT | owner=" + owner
                + " reason=no target (hash miss + map miss + content miss)");
        }
        return true;
    }

    /** 最后一级兜底（src=content）：指纹表也查不到时，在当前显示的列表里从该消息往前找
     *  「发送者 + 内容」都吻合的一条。内容重复时取「离引用消息最近」的那条（更可能是本人
     *  引用的目标）；找不到（已被历史清理 / 不在当前视图）返回 -1，由调用方静默处理。
     *  日志统一带 src=content 以便和 src=hash / src=map 区分。 */
    private int findQuotedMessage(int ownerIndex) {
        List<ChatMessageStore.ChatMessage> full = ChatMessageStore.getMessages();
        if (ownerIndex < 0 || ownerIndex >= full.size()) {
            quoteJumpLog(() -> "[e33chat] QUOTEJUMP | FIND | ownerIndex out of range | owner=" + ownerIndex
                + " full=" + full.size());
            return -1;
        }
        ChatMessageStore.ChatMessage owner = full.get(ownerIndex);
        String quoted = owner.replyContent();
        if (quoted == null || quoted.isEmpty()) {
            quoteJumpLog(() -> "[e33chat] QUOTEJUMP | FIND | replyContent EMPTY | owner=" + ownerIndex);
            return -1;
        }
        List<ChatMessageStore.ChatMessage> shown = whisperPartner != null
            ? ChatMessageStore.getWhisperMessages(whisperPartner)
            : ChatMessageStore.getPublicMessages();
        int scanned = 0;
        int cmpLogged = 0;
        StringBuilder nearMiss = new StringBuilder();
        // 先定位 owner 在「显示列表」里的位置，再只往前扫 —— owner 往往就是最新一条，
        // 若从末尾开始扫会在第一轮就撞到它自己并 break（这正是 scanned=0、永不命中的根因）。
        int ownerShown = -1;
        for (int i = shown.size() - 1; i >= 0; i--) {
            if (shown.get(i) == owner) { ownerShown = i; break; }
        }
        if (ownerShown < 0) {
            quoteJumpLog(() -> "[e33chat] QUOTEJUMP | FIND | owner not in shown list | owner=" + ownerIndex
                + " shown=" + shown.size());
            return -1;
        }
        // 从「离引用消息最近」的那条往前扫：内容完全相同的多条里，离得最近的最可能就是
        // 用户当初引用的那一条（精确表命中时根本不会走到这里，所以这仅是兜底猜测）。
        for (int i = ownerShown - 1; i >= 0; i--) {
            ChatMessageStore.ChatMessage m = shown.get(i);
            if (m.isSystem()) continue;
            scanned++;
            String cand = ChatMessageStore.singleLine(m.content().getString());
            if (!cand.equals(quoted)) {
                // 记录「发送者一致但文本不等」的最近几条：这是 B  ?bug 的直接证
if (cmpLogged < 3 && quoteSenderMatches(m, owner.replySender())) {
                    cmpLogged++;
                    nearMiss.append("\n  [SM] quoted='").append(quoted).append("' vs cand='").append(cand).append('\'');
                }
                continue;
            }
            if (!quoteSenderMatches(m, owner.replySender())) continue;
            // 命中必须落在全量下标空间里（jumpToMessage / 气泡绘制都用它）
            for (int f = ownerIndex - 1; f >= 0; f--) {
                if (full.get(f) == m) {
                    final int hit = f;
                    final int sc2 = scanned;
                    quoteJumpLog(() -> "[e33chat] QUOTEJUMP | FIND | src=content owner=" + ownerIndex
                        + " replySender='" + owner.replySender() + "' replyContent='" + quoted + "'"
                        + " shown=" + shown.size() + " scanned=" + sc2 + " result=" + hit);
                    return f;
                }
            }
        }
        final int sc = scanned;
        final String nm = nearMiss.toString();
        quoteJumpLog(() -> "[e33chat] QUOTEJUMP | FIND | src=content owner=" + ownerIndex
            + " replySender='" + owner.replySender() + "' replyContent='" + quoted + "'"
            + " shown=" + shown.size() + " scanned=" + sc + " result=-1" + nm);
        return -1;
    }

    /** 引用块里的发送者名与候选消息是否同一人（优先原始玩家名，其次显示名，可能带前缀/后缀） ?*/
    private static boolean quoteSenderMatches(ChatMessageStore.ChatMessage m, String replySender) {
        if (replySender == null || replySender.isEmpty()) return true;
        String raw = m.rawPlayerName();
        if (raw != null && raw.equals(replySender)) return true;
        String name = m.senderName().getString();
        return name.equals(replySender) || (!name.isEmpty() && name.contains(replySender));
    }

    /** 记住「刚发出的这条自己消息引用了谁」：offset = 自己的下标 - 目标下标。
     *  同时写身份键与指纹别名，表容量固定（FIFO 淘汰），不会随聊天无限增长。 */
    private void rememberQuoteTarget(ChatMessageStore.ChatMessage sent, int selfIndex, int targetIndex) {
        if (sent == null || selfIndex < 0 || targetIndex < 0 || selfIndex <= targetIndex) return;
        int offset = selfIndex - targetIndex;
        String fp = quoteFingerprint(sent);
        quoteTargetByMessage.put(sent, offset);
        quoteTargetByFingerprint.put(fp, offset);
        quoteTargetOrder.addLast(sent);
        while (quoteTargetOrder.size() > QUOTE_TARGET_MAX) {
            ChatMessageStore.ChatMessage old = quoteTargetOrder.pollFirst();
            if (old == null) continue;
            quoteTargetByMessage.remove(old);
            quoteTargetByFingerprint.remove(quoteFingerprint(old));
        }
    }

    /** 身份之外的稳定别名：内容哈希 + 发送毫秒（withSenderName / withReply 换实例时不变；
     *  withMerge 只发生在同内容连发，会改 time，那种情况退回内容匹配）。 */
    private static String quoteFingerprint(ChatMessageStore.ChatMessage m) {
        return m == null ? "" : m.messageHash() + "@" + m.time();
    }

    /** 按对象身份取全量下标（不能用 List.indexOf：ChatMessage 是 record，equals 是值相等）。 */
    private static int indexOfIdentity(List<ChatMessageStore.ChatMessage> list, ChatMessageStore.ChatMessage msg) {
        if (list == null || msg == null) return -1;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) == msg) return i;
        }
        return -1;
    }

    /** 引用块指向哪条：① 服务端同步回来的被引用消息指纹（src=hash，跨客户端精确）
     *  → ② 发送时记下的本地精确关系（src=map）→ ③ 内容匹配兜底（src=content）。
     *  精确表两条通道：对象身份（同实例）+ 指纹（实例被 withSenderName 换掉后仍能接上）。
     *  命中后不删键（同一引用块重复双击仍精确），靠 QUOTE_TARGET_MAX 的 FIFO 上限回收。
     *  全程不抛异常：对象被替换 / 下标越界 / 表里没有，都只是走兜底。 */
    private int resolveQuoteTarget(int ownerIndex) {
        List<ChatMessageStore.ChatMessage> full = ChatMessageStore.getMessages();
        if (ownerIndex >= 0 && ownerIndex < full.size()) {
            ChatMessageStore.ChatMessage owner = full.get(ownerIndex);
            int byHash = findQuotedByHash(owner, ownerIndex);
            if (byHash >= 0) return byHash;
            Integer offset = quoteTargetByMessage.get(owner);
            String key = "id";
            if (offset == null) {
                offset = quoteTargetByFingerprint.get(quoteFingerprint(owner));
                key = "fp";
            }
            if (offset != null) {
                final int off = offset;
                final String srcKey = key;
                int target = ownerIndex - off;
                if (off > 0 && target >= 0 && target < ownerIndex) {
                    final int dbgTarget = target;
                    quoteJumpLog(() -> "[e33chat] QUOTEJUMP | FIND | src=map key=" + srcKey
                        + " owner=" + ownerIndex + " offset=" + off + " target=" + dbgTarget
                        + " full=" + full.size());
                    return target;
                }
                final int staleTarget = target;
                quoteJumpLog(() -> "[e33chat] QUOTEJUMP | FIND | src=map key=" + srcKey + " STALE owner="
                    + ownerIndex + " offset=" + off + " target=" + staleTarget + " -> fallback content");
            }
        }
        return findQuotedMessage(ownerIndex);
    }

    /** 候选气泡的内容是否就是这个指纹：既比 record 里存下的 messageHash（老数据/未剥 § 的情况），
     *  也比「重算的明文指纹」（跨客户端时唯一可靠的那个）。 */
    private static boolean quoteHashMatches(ChatMessageStore.ChatMessage m, String quotedHash) {
        if (m == null || quotedHash == null || quotedHash.isEmpty()) return false;
        if (quotedHash.equals(m.messageHash())) return true;
        return quotedHash.equals(ChatMessageStore.quoteHash(m.content().getString()));
    }

    /**
     * ① 精确通道：按服务端同步回来的「被引用消息指纹」在**当前显示列表**里找原文，
     * 命中返回全量下标，找不到返回 -1（交给 map / content 兜底）。
     *
     * <p>为什么优先「离引用消息最近的那条」：同内容消息的 hashCode 相同，指纹本身无法区分
     * 重复文本；引用总是发生在原文之后，所以离引用块最近的那条最可能就是用户当时引用的
     * 那一条（和内容兜底同一套启发式，但指纹不受前缀/换行/选字差异影响）。
     */
    private int findQuotedByHash(ChatMessageStore.ChatMessage owner, int ownerIndex) {
        String quotedHash = owner == null ? null : owner.quotedHash();
        if (quotedHash == null || quotedHash.isEmpty()) return -1;
        List<ChatMessageStore.ChatMessage> full = ChatMessageStore.getMessages();
        List<ChatMessageStore.ChatMessage> shown = whisperPartner != null
            ? ChatMessageStore.getWhisperMessages(whisperPartner)
            : ChatMessageStore.getPublicMessages();
        int ownerShown = -1;
        for (int i = shown.size() - 1; i >= 0; i--) {
            if (shown.get(i) == owner) { ownerShown = i; break; }
        }
        if (ownerShown < 0) {
            quoteJumpLog(() -> "[e33chat] QUOTEJUMP | FIND | src=hash MISS owner not in shown list | owner="
                + ownerIndex + " quotedHash=" + quotedHash);
            return -1;
        }
        int scanned = 0;
        int hits = 0;
        for (int i = ownerShown - 1; i >= 0; i--) {
            ChatMessageStore.ChatMessage m = shown.get(i);
            if (m.isSystem()) continue;
            scanned++;
            if (!quoteHashMatches(m, quotedHash)) continue;
            hits++;
            int f = indexOfIdentity(full, m);
            if (f < 0 || f >= ownerIndex) continue;
            final int hit = f;
            final int sc = scanned;
            final int hs = hits;
            quoteJumpLog(() -> "[e33chat] QUOTEJUMP | FIND | src=hash owner=" + ownerIndex
                + " quotedHash=" + quotedHash + " shown=" + shown.size() + " scanned=" + sc
                + " hits=" + hs + " result=" + hit);
            return f;
        }
        // 引用一般总在原文之后；万一数据错位（历史回填顺序、清屏），再往后找最近的一条
        int forward = -1;
        for (int i = ownerShown + 1; i < shown.size(); i++) {
            ChatMessageStore.ChatMessage m = shown.get(i);
            if (m.isSystem() || !quoteHashMatches(m, quotedHash)) continue;
            int f = indexOfIdentity(full, m);
            if (f >= 0 && f != ownerIndex) { forward = f; break; }
        }
        final int fwd = forward;
        final int sc2 = scanned;
        final int hs2 = hits;
        quoteJumpLog(() -> "[e33chat] QUOTEJUMP | FIND | src=hash owner=" + ownerIndex
            + " quotedHash=" + quotedHash + " shown=" + shown.size() + " scanned=" + sc2
            + " hits=" + hs2 + " result=" + fwd);
        return forward;
    }

    /** 引用跳转后的一次性高亮：目标消息整条盖一层圆角底色，随时间线性淡出后自然消失 ?*/
    private void renderQuoteFlash(DrawContext g, ChatMessageStore.ChatMessage msg, int screenY, int msgH) {
        if (quoteFlashMsg == null || msg != quoteFlashMsg) return;
        float t = MathHelper.clamp((float) (Util.getMeasuringTimeMs() - quoteFlashStart) / QUOTE_FLASH_MS, 0f, 1f);
        int a = (int) (0x66 * (1f - t));
        if (t >= 1f || a <= 0) { quoteFlashMsg = null; return; }   // 淡出结束：自动消失，无需用户操作
        // 颜色取主题里现成的“选中条目底色”（sidebarItemSelected），圆角沿用气泡圆角
        RoundRectRenderer.fill(g, panelX + PAD - 2, screenY - 2, panelX + panelW - PAD + 2, screenY + msgH + 2,
            ChatBubbleClientSetup.config().bubbleCornerRadius(),
            ChatBubbleTheme.alphaBlend(c().sidebarItemSelected(), a));
    }

    private void jumpToMessage(int msgIndex) {
        var msgs = ChatMessageStore.getMessages();
        if (msgIndex < 0 || msgIndex >= msgs.size()) return;
        int cy = 0;
        String lk = null;
        ChatMessageStore.ChatMessage prevMsg = null;
        for (int i = 0; i < msgIndex && i < msgs.size(); i++) {
            var m = msgs.get(i);
            if (!m.isSystem()) {
                String k = timeKey(m.time());
                if (lk == null || !k.equals(lk)) { lk = k; cy += TIME_SEP_H + Appearance.messageGap(); prevMsg = null; }
            }
            if (prevMsg != null) cy += Appearance.messageGap();
            cy += getMsgHeight(m);
            prevMsg = m;
        }
        scrollOffset = Math.max(0, cy - 20);
        newMessageCount = 0; hasNewMentionOrQuote = false;
        latestMentionIndex = -1; lastSeenMessageCount = msgs.size();
    }

    private static Text parseColorCodes(String s) {
        if (s.indexOf('&') < 0) return Text.literal(s);
        MutableText out = Text.empty();
        Style style = Style.EMPTY;
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && isFormatCode(s.charAt(i + 1))) {
                if (run.length() > 0) {
                    out.append(Text.literal(run.toString()).fillStyle(style));
                    run.setLength(0);
                }
                style = applyCode(style, s.charAt(i + 1));
                i++;
            } else {
                run.append(c);
            }
        }
        if (run.length() > 0) out.append(Text.literal(run.toString()).fillStyle(style));
        return out;
    }

    private static Style applyCode(Style st, char c) {
        switch (Character.toLowerCase(c)) {
            case '0': return st.withColor(Formatting.BLACK.getColorValue() != null ? Formatting.BLACK.getColorValue() : null);
            case '1': return st.withColor(Formatting.DARK_BLUE.getColorValue() != null ? Formatting.DARK_BLUE.getColorValue() : null);
            case '2': return st.withColor(Formatting.DARK_GREEN.getColorValue() != null ? Formatting.DARK_GREEN.getColorValue() : null);
            case '3': return st.withColor(Formatting.DARK_AQUA.getColorValue() != null ? Formatting.DARK_AQUA.getColorValue() : null);
            case '4': return st.withColor(Formatting.DARK_RED.getColorValue() != null ? Formatting.DARK_RED.getColorValue() : null);
            case '5': return st.withColor(Formatting.DARK_PURPLE.getColorValue() != null ? Formatting.DARK_PURPLE.getColorValue() : null);
            case '6': return st.withColor(Formatting.GOLD.getColorValue() != null ? Formatting.GOLD.getColorValue() : null);
            case '7': return st.withColor(Formatting.GRAY.getColorValue() != null ? Formatting.GRAY.getColorValue() : null);
            case '8': return st.withColor(Formatting.DARK_GRAY.getColorValue() != null ? Formatting.DARK_GRAY.getColorValue() : null);
            case '9': return st.withColor(Formatting.BLUE.getColorValue() != null ? Formatting.BLUE.getColorValue() : null);
            case 'a': return st.withColor(Formatting.GREEN.getColorValue() != null ? Formatting.GREEN.getColorValue() : null);
            case 'b': return st.withColor(Formatting.AQUA.getColorValue() != null ? Formatting.AQUA.getColorValue() : null);
            case 'c': return st.withColor(Formatting.RED.getColorValue() != null ? Formatting.RED.getColorValue() : null);
            case 'd': return st.withColor(Formatting.LIGHT_PURPLE.getColorValue() != null ? Formatting.LIGHT_PURPLE.getColorValue() : null);
            case 'e': return st.withColor(Formatting.YELLOW.getColorValue() != null ? Formatting.YELLOW.getColorValue() : null);
            case 'f': return st.withColor(Formatting.WHITE.getColorValue() != null ? Formatting.WHITE.getColorValue() : null);
            case 'k': return st.withObfuscated(true);
            case 'l': return st.withBold(true);
            case 'm': return st.withStrikethrough(true);
            case 'n': return st.withUnderline(true);
            case 'o': return st.withItalic(true);
            case 'r': return Style.EMPTY;
            default: return st;
        }
    }

    private static boolean isFormatCode(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
            || (c >= 'k' && c <= 'o') || (c >= 'A' && c <= 'F')
            || (c >= 'K' && c <= 'O');
    }

    /** Extracts the local path from [[CICode,url=file:///...]] (chatimage appends Windows backslash paths). */
    private static String extractLocalPath(String cicode) {
        int start = cicode.indexOf("url=file:///");
        if (start < 0) return null;
        start += "url=file:///".length();
        int end = cicode.indexOf("]]", start);
        if (end < 0) end = cicode.length();
        String path = cicode.substring(start, end);
        // file:///C:\...  ?C:\... (drop the leading slash before the drive letter)
        if (path.startsWith("/") && path.length() > 1 && path.charAt(1) == ':') return path.substring(1);
        return path;
    }

    private void sendMessage() {
        String raw = chatField.getText().trim();
        if (raw.isEmpty()) return;
        if (raw.contains("[[CICode,url=file://")) {
            // A local file:// CICode (chatimage's drag/paste handler inserts
            // these) is a local-only broken link. Queue our own upload and
            // finish the send automatically once the real URL is up  ?one
            // enter, no second press. The input is cleared so the enter can't
            // double-fire; the text is restored if the upload fails.
            //
            // Strict validity check: only a real, existing local file is an
            // upload candidate. Edited remnants (deleted brackets, stale paths)
            // fall through and are sent as plain text  ?never block the user
            // on a string prefix alone.
            String localPath = extractLocalPath(raw);
            if (localPath == null || !new java.io.File(localPath).isFile()) {
                ChatMessageStore.debugLog("[e33chat] upload skip | not a live file | raw=" + raw);
                sendMessageText(raw);
                return;
            }
            if (uploadQueue.enqueue(new com.niuqu.chatbubble.image.UploadQueue.UploadJob(new java.io.File(localPath), null, null, false, raw))) {
                chatField.setText("");
                savedInput = "";
                client.player.sendMessage(Text.translatable("e33chat.upload.wait"), false);
                ChatMessageStore.debugLog("[e33chat] upload block | queued=" + uploadQueue.pending() + " | raw=" + raw);
            } else {
                client.player.sendMessage(Text.translatable("e33chat.upload.queue_full"), false);
            }
            return;
        }
        sendMessageText(raw);
    }

    private void sendMessageText(String text) {
        String raw = text;
        var cfg = ChatBubbleClientSetup.config();
        // Send the text UNCHANGED (raw '&', never '§'): vanilla servers reject '§' in
        // player chat and kick, so converting client-side is a dead end. Server color
        // plugins (Essentials etc.) translate '&' for everyone; on plain servers others
        // see the literal '&'. Local coloring of our own bubble is done at addMessage.

        if (whisperPartner != null && !text.startsWith("/")) {
            text = "/msg " + whisperPartner + " " + text;
        }

        String whisperTarget = null;
        String displayText = text;
        if (text.startsWith("/msg ") || text.startsWith("/tell ") || text.startsWith("/w ")) {
            String[] parts = text.split(" ", 3);
            if (parts.length >= 3) { whisperTarget = parts[1]; displayText = parts[2]; }
        }

        boolean localBubble = !text.startsWith("/") || whisperTarget != null;
        // 发送前先取走引用目标：下面会把 replyTargetIndex 置 -1，之后就不知道引用了谁
        final int quoteTarget = replyTargetIndex;
        boolean quoteTargetValid = false;

        if (replyTargetIndex >= 0) {
            if (localBubble) {
                ChatMessageStore.ChatMessage target = ChatMessageStore.getMessageAt(replyTargetIndex);
                if (target != null) {
                    quoteTargetValid = true;
                    String quoteSender = (target.rawPlayerName() != null && !target.rawPlayerName().isEmpty())
                        ? target.rawPlayerName() : target.senderName().getString();
                    String quoted = ChatMessageStore.singleLine(target.content().getString());
                    // 被引用消息的跨客户端指纹：服务端会原样广播，别人双击我这条的引用块时
                    // 靠它在全量消息里精确定位原文（src=hash）。必须用「明文去 § 码」算，
                    // 否则自己带 &颜色码的本地气泡与别人看到的内容算出的值不一致。
                    String quotedHash = ChatMessageStore.quoteHash(target.content().getString());
                    ChatMessageStore.setPendingReply(quoted, quoteSender);
                    // Optional payload: only send when the server registered e33chat:quote_sync.
                    // Without this guard, right-click quote on servers without the E33Chat
                    // server mod still works locally but can fail on the network layer.
                    if (ClientPlayNetworking.canSend(QuoteSyncPayload.ID)) {
                        try {
                            ClientPlayNetworking.send(new QuoteSyncPayload(quoteSender, quoted, displayText, quotedHash));
                        } catch (Exception e) {
                            ChatMessageStore.debugLog(() -> "[e33chat] quote_sync skipped | " + e.getMessage());
                        }
                    } else {
                        ChatMessageStore.debugLog(() -> "[e33chat] quote_sync skipped | server has no e33chat:quote_sync channel");
                    }
                }
            }
            replyTargetIndex = -1;
        }

        if (text.startsWith("/"))
            // yarn: sendChatCommand is the full signed path (vanilla ChatScreen
            // uses it); sendCommand silently drops commands with signable
            // arguments (/msg /tell /w)  ?regression from the 1.20 port
            client.player.networkHandler.sendChatCommand(text.substring(1));
        else
            client.player.networkHandler.sendChatMessage(text);
        client.inGameHud.getChatHud().addToMessageHistory(text);
        // Keep the history cursor at the newest end: this screen stays open after
        // send (vanilla closes), so init()'s one-time historyPos snapshot goes
        // stale and up-arrow would skip the freshly sent entries.
        historyPos = client.inGameHud.getChatHud().getMessageHistory().size();

        ChatMessageStore.debugLog("[e33chat] Send | cmd='" + text + "' | display='" + displayText + "' | whisperTarget=" + whisperTarget + " | localBubble=" + localBubble);
        if (localBubble) {
            Text contentForSend = cfg != null && cfg.colorCodes() ? parseColorCodes(displayText) : Text.literal(displayText);
            // 2.3.10+: keep image bracket codes raw so the local bubble renders
            // the picture natively (BracketCodec + ImageLoader); the vanilla chat
            // echo is converted by ChatImage's own mixins when installed.
            String playerName = client.player.getName().getString();
            String replySender = ChatMessageStore.getPendingReplySender();

            ChatMessageStore.ChatMessage sentMsg = ChatMessageStore.addMessage(contentForSend,
                client.player.getUuid(),
                ChatMessageStore.ownDisplayName(),
                false,
                playerName,
                whisperTarget != null, whisperTarget, true);
            // 把「这条自己消息」与发送时记下的引用目标关联起来，点击引用块时直接查表，
            // 不再靠内容和发送者去猜（内容重复时从最早/最近匹配都会跳错）。
            if (quoteTargetValid && quoteTarget >= 0 && sentMsg != null) {
                int selfIndex = indexOfIdentity(ChatMessageStore.getMessages(), sentMsg);
                if (selfIndex > quoteTarget) {
                    rememberQuoteTarget(sentMsg, selfIndex, quoteTarget);
                    ChatMessageStore.ChatMessage qTarget = ChatMessageStore.getMessageAt(quoteTarget);
                    final String qHash = qTarget == null ? ""
                        : ChatMessageStore.quoteHash(qTarget.content().getString());
                    quoteJumpLog(() -> "[e33chat] QUOTEJUMP | MAP-PUT | self=" + selfIndex
                        + " target=" + quoteTarget + " offset=" + (selfIndex - quoteTarget)
                        + " quotedHash=" + qHash);
                }
            }
            ChatMessageStore.incrementPendingEcho(text);

            // Trigger mention detection directly from send path.
            // The echo will be consumed (preventing duplicate bubbles),
            // so the controller must fire here for self-@ notifications.
            if (com.niuqu.chatbubble.chat.MentionDetector.isMentioned(
                    contentForSend.getString(), playerName,
                    cfg.mentionRequireAt(), replySender)) {
                com.niuqu.chatbubble.chat.notification.MentionNotificationController.INSTANCE.onMessageCaptured(
                    contentForSend,
                    new ChatMessageStore.SenderMeta(client.player.getUuid(),
                        Text.literal(playerName), contentForSend, false,
                        playerName, whisperTarget != null, whisperTarget),
                    ChatMessageStore.size(), replySender);
            }
        }
        if (whisperTarget != null) ChatMessageStore.markPendingWhisperEcho(whisperTarget);

        chatField.setText("");
        savedInput = "";
        scrollToBottom = true;
        // Optional vanilla-style behaviour: close the chat screen right after the
        // message goes out (off by default  ?this screen supports multi-send).
        if (cfg != null && cfg.closeChatOnSend()) onClose();
    }


    // 父类 setChatFromHistory 访问 package-private chatInputSuggestor（跨 ?null），
    // override 用自己的实现（history 字段也私有化到本类）
    @Override
    public void setChatFromHistory(int offset) {
        int size = client.inGameHud.getChatHud().getMessageHistory().size();
        int newPos = MathHelper.clamp(historyPos + offset, 0, size);
        if (newPos != historyPos) {
            if (newPos == size) {
                historyPos = size;
                chatField.setText(historyBuffer);
            } else {
                if (historyPos == size) historyBuffer = chatField.getText();
                chatField.setText(client.inGameHud.getChatHud().getMessageHistory().get(newPos));
                historyPos = newPos;
            }
        }
    }

    // 父类 resize 访问 package-private chatInputSuggestor（跨 ?null）→ 自实  @Override
    public void resize(int width, int height) {
        String cur = chatField.getText();
        this.width = width;
        this.height = height;
        this.init();
        chatField.setText(cur);
    }

    @Override
    public void removed() {
        if (ChatBubbleClientSetup.config().preserveInput()) savedInput = chatField.getText();
        ChatMessageStore.setScreenOpen(false);
        client.inGameHud.getChatHud().reset();
    }

    public void onClose() {
        if (ChatBubbleClientSetup.config().preserveInput()) savedInput = chatField.getText();
        if (!ChatBubbleClientSetup.config().animationEnabled()) {
            client.setScreen(null); return;
        }
        if (closing) return;
        closing = true;
        animStart = Util.getMeasuringTimeMs();
    }

    public boolean shouldPause() { return false; }

    private static class ClickableSpan {
        final int x, y, w, h;
        final Style style;
        ClickableSpan(int x, int y, int w, int h, Style style) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.style = style;
        }
    }
}
