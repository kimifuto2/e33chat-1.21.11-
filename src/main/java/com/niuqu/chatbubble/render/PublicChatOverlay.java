package com.niuqu.chatbubble.render;

import com.niuqu.chatbubble.ChatBubbleClientSetup;
import com.niuqu.chatbubble.config.ChatBubbleConfig;
import com.niuqu.chatbubble.image.BracketCodec;
import com.niuqu.chatbubble.store.ChatMessageStore;
import com.niuqu.chatbubble.store.ChatMessageStore.ChatMessage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「公屏消息小窗」（B 方案：一整块半透明圆角面板）。
 *
 * 聊天面板关闭时，在左下角聊天图标正上方常驻显示最近 3 条<b>公屏</b>消息
 * （不含私聊、不含系统提示）。新消息把最旧一条从面板顶部顶出去（线性位移 +
 * 淡出），没有消息时整块不画。
 *
 * 静默自动隐藏：最后一条消息到达后静默「配置项 publicOverlayIdleSeconds 秒」
 * （默认 5 秒，见 {@link ChatBubbleConfig#publicOverlayIdleSeconds()}）开始淡出，
 * {@link #FADE_OUT_MS} 毫秒内降到完全不可见（之后整块不绘制，省性能也不留空白
 * 占位）。期间有任何新公屏消息 → 立刻淡入（{@link #FADE_IN_MS} 毫秒）并重新计时。
 * 淡出完成后消息快照仍保留，所以重新出现时不需要重建换行结果。
 *
 * 公屏免打扰：配置项 {@code dndEnabled}（语义 = 「公屏免打扰」）开启时，目标 alpha 恒为
 * 0 —— 复用同一条淡出路径（{@link #FADE_OUT_MS} 内淡出，不是硬消失）；期间到达的新消息
 * 只更新快照与计时，绝不会把它重新淡入。关闭免打扰后恢复正常。
 *
 * 性能：每帧只做一次 O(1) 的签名比较（消息总数 + 末条消息的 time / hash），
 * 只有签名变化（新消息、世界切换）或面板宽度变化时才回扫消息列表重建快照；
 * 重建最多回扫 {@link #SCAN_LIMIT} 条，绝不遍历全量历史（上限 10000 条）。
 *
 * 绘制约定：文字一律走 {@link ChatBubbleTheme#alphaBlend} 归一化 alpha
 * （1.21.11 的 DrawContext.drawText 在 alpha==0 时直接不画）；正文用主题的
 * textPrimary、发送者名用主题的 nameColor（主题解析里已按强调色拉开），
 * 不新增硬编码颜色常量。
 */
public final class PublicChatOverlay {

    /** 常驻条数（最近 N 条公屏）。 */
    private static final int MAX_MESSAGES = 3;
    /** 面板内最多显示的文字行数（单条长消息换行后受此限制，超出截断）。 */
    private static final int MAX_ROWS = 6;
    /** 面板宽度（GUI 像素），随窗口宽度收窄。 */
    private static final int PANEL_W = 220;
    private static final int MIN_PANEL_W = 96;
    /** 内边距。 */
    private static final int PAD = 4;
    /** 圆角半径。 */
    private static final int RADIUS = 4;
    /** 面板底边与聊天图标顶边的间距。 */
    private static final int GAP_ABOVE_ICON = 4;
    /** 面板背景 alpha（0~255，约 66%）。 */
    private static final int PANEL_ALPHA = 168;
    /** 位移动画时长（毫秒，线性）。 */
    private static final long ANIM_MS = 180L;
    /** 静默隐藏阈值的默认值（毫秒）：仅当配置项缺失（旧配置文件）时兜底。
     *  运行时的实际阈值每帧从配置读（{@link #idleHideMs(ChatBubbleConfig)}），
     *  所以配置屏改完即时生效，不要把这个值当常量用。 */
    private static final long DEFAULT_IDLE_HIDE_MS =
        ChatBubbleConfig.PUBLIC_OVERLAY_IDLE_DEFAULT_SECONDS * 1000L;
    /** 无新消息时淡出到完全不可见的时长（毫秒，线性）。 */
    private static final long FADE_OUT_MS = 400L;
    /** 有新消息时重新淡入的时长（毫秒，线性）。 */
    private static final long FADE_IN_MS = 200L;
    /** 单帧最多推进的毫秒数：掉帧 / 切回窗口时不要把整段淡入淡出一次性跳完。 */
    private static final long MAX_FRAME_STEP_MS = 100L;
    /** 重建快照时最多回扫的消息条数（只取最新几条公屏，不必扫全量）。 */
    private static final int SCAN_LIMIT = 200;

    private PublicChatOverlay() {}

    /** 一条待画的消息（含换行结果）。 */
    private static final class View {
        final String key;
        String rawName = "";
        String body = "";
        /** 画在首行的「名字:」，后续行不再重复。 */
        String name = "";
        /** 正文缩进（名字宽度 + 空格），后续行对齐到这里。 */
        int indent;
        int wrapW;
        int layoutW = -1;
        final List<String> lines = new ArrayList<>();

        View(String key) {
            this.key = key;
        }

        int rows() {
            return Math.max(1, lines.size());
        }
    }

    /** 旧 → 新（最旧的在前，最新的在最后）。 */
    private static final List<View> shown = new ArrayList<>();
    /** 被顶出面板、正在淡出的消息（比 shown 更旧，画在上方）。 */
    private static final List<View> fading = new ArrayList<>();
    private static long lastSig;
    private static boolean hasSig;
    private static long animStart;
    /** 本次动画的位移行数（≥1；见 refresh 里的推导）。 */
    private static int slideRows = 1;

    /** 面板当前不透明度（0~1）：1 = 完全显示，0 = 完全隐藏（此后整块不绘制）。 */
    private static float panelAlpha;
    /** 最近一条新消息（含反刷屏合并计数变化）到达的时刻；0 = 还没显示过。 */
    private static long lastActivityMs;
    /** 上一帧的时间戳：panelAlpha 按真实经过的毫秒推进，与帧率无关。 */
    private static long lastFrameMs;
    /** 每来一条「新」消息 +1；与 seenActivityVer 不同即视为有新消息到达。 */
    private static long activityVer;
    private static long seenActivityVer = -1L;

    /**
     * 画小窗。调用点必须在 {@code hudHidden} / {@code currentScreen} 判断之后，
     * 这样 F1 与「聊天面板打开」时整块自动消失。
     *
     * @param x     面板左边（与聊天图标左对齐）
     * @param iconY 聊天图标顶边（面板底边放在它上方 {@link #GAP_ABOVE_ICON}px）
     */
    public static void render(UiCanvas g, int x, int iconY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return;
        ChatBubbleConfig cfg = ChatBubbleClientSetup.config();
        if (cfg == null || Boolean.FALSE.equals(cfg.publicOverlayEnabled())) {
            reset();
            return;
        }

        Font tr = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int panelW = Math.max(MIN_PANEL_W, Math.min(PANEL_W, screenW - 8));
        int textW = panelW - PAD * 2;
        long now = System.currentTimeMillis();

        // 静默阈值每帧从配置读一次（毫秒）：配置屏把 publicOverlayIdleSeconds 改完
        // 下一帧就生效，不做任何静态缓存，也不需要重启 / 重开世界。
        long idleMs = idleHideMs(cfg);
        refresh(tr, textW, now);
        // 静默计时的唯一驱动点：有新消息 → 计时归零并（必要时）淡入；
        // 静默超过 idleMs → 目标变 0，FADE_OUT_MS 内淡出到不可见。
        // 公屏免打扰（cfg.dndEnabled，语义 = 公屏免打扰）开启时目标 alpha 恒为 0，
        // 走的就是下面这套淡出，不另起一套动画。
        updateIdleFade(now, idleMs, cfg.dndEnabled());

        float anim = animStart == 0L ? 1f
            : (float) Math.max(0.0, Math.min(1.0, (now - animStart) / (double) ANIM_MS));
        if (anim >= 1f) fading.clear();

        int shownRows = 0;
        for (View v : shown) shownRows += v.rows();
        if (shownRows <= 0) {
            fading.clear();
            panelAlpha = 0f;
            return;
        }
        // 静默超时且淡出已完成：整块不画（省性能、也不留空白占位），
        // 但 shown 快照原样保留 —— 下一条消息到达时由 updateIdleFade 直接淡入。
        if (panelAlpha <= 0.004f) return;
        // 淡出中的行仍然占位（它们从面板顶部滑出去），面板高度只按常驻行算
        int fadeRows = 0;
        for (View v : fading) fadeRows += v.rows();

        int lineH = tr.lineHeight + 1;
        int panelH = shownRows * lineH + PAD * 2;
        int top = iconY - GAP_ABOVE_ICON - panelH;
        // 淡出行占位在常驻行之上；t=0 时整块再往下滑 slideRows 行，动画结束时归位
        int blockTop = top + PAD - fadeRows * lineH
            + (int) Math.round((1f - anim) * slideRows * lineH);

        ChatBubbleTheme.Colors c = Appearance.snapshot();
        RoundRectRenderer.fill(g, x, top, x + panelW, top + panelH, RADIUS,
            ChatBubbleTheme.alphaBlend(c.popupBg(), Math.round(PANEL_ALPHA * panelAlpha)));

        // 文字裁到面板内：新消息从面板底边滑入、被顶出的那条从顶边滑出
        g.enableScissor(x, top, x + panelW, top + panelH);
        int y = blockTop;
        for (View v : fading) y = drawView(g, tr, c, v, x + PAD, y, lineH, (1f - anim) * panelAlpha);
        for (int i = 0; i < shown.size(); i++) {
            boolean newest = i == shown.size() - 1;
            y = drawView(g, tr, c, shown.get(i), x + PAD, y, lineH, (newest ? anim : 1f) * panelAlpha);
        }
        g.disableScissor();
    }

    /**
     * 推进「静默 N 秒 → 淡出」/「有新消息 → 淡入」的状态机（每帧 O(1)）。
     *
     * panelAlpha 朝目标推进一步：有新消息时目标 = 1，静默超时后目标 = 0；步长按真实
     * 经过的毫秒算，所以帧率变化不影响观感（单帧最多走 {@link #MAX_FRAME_STEP_MS}，
     * 防止长时间不渲染后一次性跳完整段淡变）。淡出只会发生在最后一条消息到达
     * {@code idleHideMs}（配置项 publicOverlayIdleSeconds，默认 5 秒）之后，
     * 并在 {@link #FADE_OUT_MS} 内线性降到 0。
     *
     * <p>公屏免打扰（{@link ChatBubbleConfig#dndEnabled()}，语义 = 公屏免打扰）开启时目标
     * alpha 恒为 0：复用这里的淡出，所以是 {@link #FADE_OUT_MS} 内的淡出而不是硬消失；
     * 目标值那一行用 {@code !dnd} 直接挡掉「新消息 → 目标 = 1」的分支，因此免打扰期间
     * 新消息绝不会把面板重新淡入（{@code lastActivityMs} 照常更新，关闭后凭它恢复）。
     */
    private static void updateIdleFade(long now, long idleHideMs, boolean dnd) {
        if (activityVer != seenActivityVer) {
            seenActivityVer = activityVer;
            lastActivityMs = now;
        }
        long rawStep = lastFrameMs == 0L ? 0L : now - lastFrameMs;
        lastFrameMs = now;

        boolean active = !dnd && lastActivityMs > 0L && now - lastActivityMs < idleHideMs;
        float target = active ? 1f : 0f;

        // 中间有很长一段时间没被渲染（聊天面板打开 / F1 隐藏 HUD / 严重卡顿）时，
        // 已经超过静默期的面板直接收掉，不补播那 400ms 淡出 —— 否则「关掉聊天面板」
        // 会看到小窗闪一下再消失。淡入不受影响（新消息仍然按 FADE_IN_MS 淡入）。
        // 公屏免打扰例外：DND 只能在某个界面里切换，切完关界面时 rawStep 往往已经很久，
        // 若也走这条捷径就成了「硬消失」；此时仍按 FADE_OUT_MS 淡出（目标本来就是 0，
        // 只会把已有 alpha 收掉，绝不会把面板重新显示出来）。
        if (!dnd && rawStep > idleHideMs && target <= 0f) {
            panelAlpha = 0f;
            return;
        }

        long step = Math.max(0L, Math.min(MAX_FRAME_STEP_MS, rawStep));
        if (target > panelAlpha) {
            panelAlpha = Math.min(target, panelAlpha + step / (float) FADE_IN_MS);
        } else {
            panelAlpha = Math.max(target, panelAlpha - step / (float) FADE_OUT_MS);
        }
    }

    /** 静默隐藏阈值（毫秒）：从配置项 publicOverlayIdleSeconds 现算，缺键回落到
     *  {@link #DEFAULT_IDLE_HIDE_MS}（5 秒），并夹进
     *  [{@value ChatBubbleConfig#PUBLIC_OVERLAY_IDLE_MIN_SECONDS},
     *  {@value ChatBubbleConfig#PUBLIC_OVERLAY_IDLE_MAX_SECONDS}] 秒 —— 手改配置文件
     *  写出越界值也不会把小窗卡死。 */
    private static long idleHideMs(ChatBubbleConfig cfg) {
        Integer seconds = cfg != null ? cfg.publicOverlayIdleSeconds() : null;
        int s = seconds == null ? (int) (DEFAULT_IDLE_HIDE_MS / 1000L) : seconds;
        s = Math.max(ChatBubbleConfig.PUBLIC_OVERLAY_IDLE_MIN_SECONDS,
            Math.min(ChatBubbleConfig.PUBLIC_OVERLAY_IDLE_MAX_SECONDS, s));
        return s * 1000L;
    }

    /** 配置关闭 / 离开世界时清掉动画残留。 */
    public static void reset() {
        shown.clear();
        fading.clear();
        hasSig = false;
        animStart = 0L;
        panelAlpha = 0f;
        lastActivityMs = 0L;
        lastFrameMs = 0L;
        activityVer = 0L;
        seenActivityVer = -1L;
    }

    private static int drawView(UiCanvas g, Font tr, ChatBubbleTheme.Colors c,
                                View v, int x, int y, int lineH, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        if (a > 0) {
            int nameColor = ChatBubbleTheme.alphaBlend(c.nameColor(), a);
            int bodyColor = ChatBubbleTheme.alphaBlend(c.textPrimary(), a);
            // 首行：名字 + 正文第一行（正文缩进到名字之后）
            g.drawText(tr, v.name, x, y, nameColor, false);
            for (int i = 0; i < v.lines.size(); i++) {
                g.drawText(tr, v.lines.get(i), x + v.indent, y + i * lineH, bodyColor, false);
            }
        }
        return y + v.rows() * lineH;
    }

    // ---- 快照刷新（只在「消息变了」或「宽度变了」时跑） ----

    private static void refresh(Font tr, int textW, long now) {
        int size = ChatMessageStore.size();
        ChatMessage tail = size > 0 ? ChatMessageStore.getMessageAt(size - 1) : null;
        long sig = signature(size, tail, textW);
        if (hasSig && sig == lastSig) return;
        hasSig = true;
        lastSig = sig;

        // 只回扫最近 SCAN_LIMIT 条，取最新的 MAX_MESSAGES 条公屏（新 → 旧）
        List<ChatMessage> picked = new ArrayList<>(MAX_MESSAGES);
        int floor = Math.max(0, size - SCAN_LIMIT);
        for (int i = size - 1; i >= floor && picked.size() < MAX_MESSAGES; i--) {
            ChatMessage m = ChatMessageStore.getMessageAt(i);
            if (m == null || m.isSystem() || m.whisper()) continue;
            picked.add(m);
        }
        Collections.reverse(picked);

        Map<String, View> prev = new HashMap<>();
        for (View v : shown) prev.put(v.key, v);
        for (View v : fading) prev.putIfAbsent(v.key, v);

        List<View> next = new ArrayList<>(picked.size());
        boolean fresh = false;
        for (ChatMessage m : picked) {
            String key = keyOf(m);
            View v = prev.remove(key);
            if (v == null) { v = new View(key); fresh = true; }
            layout(tr, v, m, textW);
            next.add(v);
        }

        // 行数上限：先截断最新一条本身，再从最旧整条开始裁（它们会滑出面板）
        List<View> newFades = new ArrayList<>(2);
        int total = 0;
        for (View v : next) total += v.rows();
        if (!next.isEmpty() && next.get(next.size() - 1).rows() > MAX_ROWS) {
            View newest = next.get(next.size() - 1);
            int before = newest.rows();
            truncate(tr, newest, MAX_ROWS);
            total -= before - newest.rows();
        }
        while (next.size() > 1 && total > MAX_ROWS) {
            View drop = next.remove(0);
            total -= drop.rows();
            newFades.add(drop);
        }

        // 被新消息挤掉的老消息：留着滑出 + 淡出（不直接消失）
        for (View v : shown) {
            if (prev.containsKey(v.key)) newFades.add(v);
        }

        int rowsBefore = 0;
        for (View v : shown) rowsBefore += v.rows();
        int rowsAfter = 0;
        for (View v : next) rowsAfter += v.rows();

        shown.clear();
        shown.addAll(next);

        // 位移行数 = 滑出去的旧行 + 面板净增行：这样已有行在 t=0 恰好停在上一次
        // 的画面位置，「新消息把旧的顶上去」没有跳动。没有任何变化（例如反刷屏合并
        // 只改 duplicateCount）则不重启动画，避免无谓的闪烁。
        int addedRows = 0;
        for (View v : newFades) addedRows += v.rows();
        int slide = addedRows + (rowsAfter - rowsBefore);
        if (slide > 0) {
            fading.clear();
            fading.addAll(newFades);
            slideRows = slide;
            animStart = now;
        } else {
            Set<String> nextKeys = new HashSet<>();
            for (View v : next) nextKeys.add(v.key);
            fading.removeIf(v -> nextKeys.contains(v.key));
        }

        // 快照里出现了全新的消息（含反刷屏合并让 duplicateCount 变化）→ 记一次「活动」，
        // 由 updateIdleFade 重新开始静默计时（阈值取配置项 publicOverlayIdleSeconds，
        // 默认 5 秒）并（隐藏时）淡入。
        // 只改宽度 / 世界切换后的首次重建不算：前者 key 复用，后者本来就要显示。
        if (fresh) activityVer++;
    }

    /**
     * 缓存命中时只在「宽度或名字/正文变了」才重排，避免每次刷新都重新量宽度换行。
     * 换行宽度用 {@link TextRenderer#wrapLines} 的真实字体度量，不按字符数估算。
     */
    private static void layout(Font tr, View v, ChatMessage m, int textW) {
        String rawName = cleanName(m);
        String body = cleanBody(m);
        if (v.layoutW == textW && rawName.equals(v.rawName) && body.equals(v.body)) return;
        v.layoutW = textW;
        v.rawName = rawName;
        v.body = body;

        int ellW = tr.width("...");
        int maxNameW = Math.max(24, textW / 2);
        String name = rawName;
        if (tr.width(name) > maxNameW - ellW) {
            name = tr.substrByWidth(Component.literal(name), Math.max(1, maxNameW - ellW)).getString() + "...";
        }
        v.name = name + ":";
        // 缩进 = 名字 + 冒号 + 一个空格；至少要给正文留 24px
        v.indent = Math.max(0, Math.min(textW - 24, tr.width(v.name) + tr.width(" ")));
        v.wrapW = Math.max(24, textW - v.indent);

        v.lines.clear();
        for (FormattedCharSequence line : tr.split(Component.literal(v.body), v.wrapW)) {
            v.lines.add(plain(line));
        }
        truncate(tr, v, MAX_ROWS);
    }

    private static void truncate(Font tr, View v, int maxRows) {
        if (v.lines.size() <= maxRows) return;
        while (v.lines.size() > maxRows) {
            v.lines.remove(v.lines.size() - 1);
        }
        if (!v.lines.isEmpty()) {
            int i = v.lines.size() - 1;
            String ell = "...";
            int avail = Math.max(1, v.wrapW - tr.width(ell));
            v.lines.set(i, tr.substrByWidth(Component.literal(v.lines.get(i)), avail).getString() + ell);
        }
    }

    // ---- 数据 ----

    /** 只认公屏：私聊（whisper）与系统提示都不进小窗。 */
    private static String keyOf(ChatMessage m) {
        return m.time() + "|" + m.messageHash() + "|" + m.duplicateCount();
    }

    /**
     * 每帧的 O(1) 指纹（不建字符串、不展开 Text 内容）：条数 + 末条消息的关键字段
     * + 面板文字宽度。世界切换 / 清空 / 合并去重（duplicateCount 变化）/ 自己名字被
     * 服务端回显修正（Text 换实例 → identityHashCode 变化）都会让它变化。
     */
    private static long signature(int size, ChatMessage tail, int textW) {
        long h = 1125899906842597L;
        h = h * 31 + size;
        h = h * 31 + textW;
        if (tail == null) return h;
        h = h * 31 + tail.time();
        h = h * 31 + tail.duplicateCount();
        h = h * 31 + (tail.messageHash() == null ? 0 : tail.messageHash().hashCode());
        h = h * 31 + (tail.senderName() == null ? 0 : System.identityHashCode(tail.senderName()));
        h = h * 31 + (tail.whisper() ? 1 : 0);
        h = h * 31 + (tail.isSystem() ? 2 : 0);
        return h;
    }

    private static String cleanName(ChatMessage m) {
        String n = m.senderName() == null ? "" : m.senderName().getString();
        n = stripSection(n).replace('\n', ' ').trim();
        return n.isEmpty() ? "?" : n;
    }

    /** 正文去掉图片/表情括号码（[[CICode,...]] 之类），并把换行压成空格。 */
    private static String cleanBody(ChatMessage m) {
        Component body = m.content() == null ? Component.empty() : BracketCodec.parse(m.content()).textWithoutImages();
        String s = stripSection(body.getString()).replace('\n', ' ').trim();
        // 纯图片/表情消息剥完只剩空串：给个 [图片] 占位，避免小窗里只显示一个名字
        return s.isEmpty() ? Component.translatable("e33chat.image.placeholder").getString() : s;
    }

    private static String stripSection(String s) {
        return s.indexOf('\u00A7') < 0 ? s : s.replaceAll("\u00A7.", "");
    }

    private static String plain(FormattedCharSequence line) {
        StringBuilder sb = new StringBuilder();
        line.accept((i, style, cp) -> {
            sb.appendCodePoint(cp);
            return true;
        });
        return sb.toString();
    }
}
