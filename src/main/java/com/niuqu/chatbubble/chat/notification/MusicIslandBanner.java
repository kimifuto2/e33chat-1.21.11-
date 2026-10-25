package com.niuqu.chatbubble.chat.notification;

import com.niuqu.chatbubble.ChatBubbleClientSetup;
import com.niuqu.chatbubble.compat.CoverTextureCache;
import com.niuqu.chatbubble.compat.DangoMusicCompat;
import com.niuqu.chatbubble.compat.JadeCompat;
import com.niuqu.chatbubble.render.Appearance;
import com.niuqu.chatbubble.render.ChatBubbleTheme;
import com.niuqu.chatbubble.render.RoundRectRenderer;
import com.niuqu.chatbubble.render.UiTokens;
import com.niuqu.chatbubble.texture.ColoredTextureRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * 灵动岛 × DangoMusic 联动胶囊。
 *
 * <p>与 {@link MentionNotificationBanner}（@ 横幅）同高（{@value #H}）、同位置（顶部居中，
 * 含 bannerOffsetX/Y 与 Jade 避让），独立一层：先画本岛，@ 横幅再按
 * {@link #stackShift()} 下移，两者不会重叠。
 *
 * <p>状态机（时间常数照抄 @ 横幅的常量，观感一致）：
 * <ul>
 *   <li>{@code alpha} 0..1；每帧 {@code alpha += (want?+1:-1) * min(100ms, now-lastMs) / (want?200:150)}；</li>
 *   <li>PLAYING / LOADING → {@code holdUntilMs = now + 1500}（播放中常驻）；</li>
 *   <li>PAUSED → 不再续期，1.5s 后按 150ms（= 原 EXIT_MS）淡出；</li>
 *   <li>STOPPED / 无曲目 → {@code holdUntilMs = 0}，立即淡出。</li>
 * </ul>
 * 没装 DangoMusic 时 {@link DangoMusicCompat#poll()} 恒为 {@code null} → alpha 永远 0 →
 * {@link #visible()} 恒 false，本模组行为与改动前完全一致（不建线程、不下载封面）。
 */
public final class MusicIslandBanner {

    public static final MusicIslandBanner INSTANCE = new MusicIslandBanner();

    /** 与 @ 横幅同高。 */
    private static final int H = 36;
    /** 宽度自适应：与 @ 横幅一致（头像位 + 文本区 + 右内边距），短标题不会撑成大胶囊。 */
    private static final int MIN_W = 120;
    private static final int COVER = 24;
    private static final int PAD_L = 8;
    private static final int GAP = 6;
    private static final int PAD_R = 12;
    /** 文本区最大宽度（与 @ 横幅的 MAX_TEXT_W 一致）。 */
    private static final int MAX_TEXT_W = 170;
    /** 文本起始 X（相对胶囊左边）：封面左内边距 + 封面 + 间隙 = 38（与 @ 横幅的 TEXT_X 一致）。 */
    private static final int TEXT_X = PAD_L + COVER + GAP;
    private static final int MAX_W = TEXT_X + MAX_TEXT_W + PAD_R;
    private static final int LINE1_Y = 6;
    private static final int LINE2_DY = 11;
    /** 进度条高度（贴胶囊底部）。 */
    private static final int BAR_H = 3;
    /** 播放中：每次 poll 把常驻期续到这个时刻之后。 */
    private static final long HOLD_MS = 1500L;
    /** 淡入时长（与 @ 横幅 POPUP_OPEN_MS 一致）。 */
    private static final float FADE_IN_MS = 200f;
    /** 淡出时长（= @ 横幅 EXIT_MS）。 */
    private static final float FADE_OUT_MS = 150f;
    private static final float ALPHA_EPS = 0.01f;
    /** 本岛与下方 @ 横幅之间的间距。 */
    private static final int STACK_GAP = 4;
    private static final int SHADOW_OFF = UiTokens.SHADOW_OFFSET_PANEL;
    /** Jade 描述框与胶囊之间的最小间距（与 @ 横幅一致）。 */
    private static final int JADE_GAP = 6;
    private static final double JADE_SHIFT_TAU_MS = 90.0;

    private float alpha;
    private long lastMs;
    private long holdUntilMs;
    private DangoMusicCompat.NowPlaying now;

    /** Jade 描述框避让的平滑下压量（本岛自己的，与 @ 横幅同公式）。 */
    private float jadeShift;
    private long jadeShiftLastMs;

    private MusicIslandBanner() {}

    /**
     * 每帧喂一次当前曲目（没装 DangoMusic / 无曲目就传 {@code null}）。
     * 同一毫秒内被调用多次（HUD 层与屏幕层各 tick 一次）时 dt=0，不会加速动画。
     */
    public void tick(DangoMusicCompat.NowPlaying next) {
        long nowMs = System.currentTimeMillis();
        // 淡出（暂停超时 / 停止播放）期间保留最后一张快照，否则 alpha 还没走完就没数据可画了
        if (next != null) this.now = next;
        if (next == null) {
            holdUntilMs = 0L;
        } else if (next.playing()) {
            holdUntilMs = nowMs + HOLD_MS;
        }
        boolean want = enabled() && next != null && nowMs < holdUntilMs;
        float dt = lastMs == 0L ? 0f : Math.min(100f, Math.max(0f, (float) (nowMs - lastMs)));
        lastMs = nowMs;
        float step = dt / (want ? FADE_IN_MS : FADE_OUT_MS);
        alpha += want ? step : -step;
        if (alpha < 0f) alpha = 0f;
        if (alpha > 1f) alpha = 1f;
        if (alpha <= 0f) this.now = null;   // 完全淡出后释放快照
        updateJadeShift(nowMs);
    }

    /** 当前不透明度（0..1）。 */
    public float alpha() {
        return alpha;
    }

    /** 是否需要绘制/让位。 */
    public boolean visible() {
        return alpha > ALPHA_EPS && now != null;
    }

    /** 胶囊高度（px）。 */
    public int heightPx() {
        return H;
    }

    /**
     * 本岛被 Jade 描述框下压的当前量（px，已平滑）。
     * @ 横幅用它与 {@link #heightPx()} 算出本岛的真实底边，保证两者不会重叠。
     */
    public int jadeShiftPx() {
        return (int) jadeShift;
    }

    /**
     * 本岛占据的顶部空间：可见时 = 高度 + 间距。
     * @ 横幅的 {@code stackTopY()} 直接加上它，避免与本岛重叠。
     * <p>注意：**不**为 DangoMusic 自带的灵动岛让位——按用户要求本岛位置固定不动，
     * 如果觉得两个岛重叠，请在 DangoMusic 的设置里关掉它自带的岛。</p>
     */
    public int stackShift() {
        if (!visible()) return 0;
        return H + STACK_GAP;
    }

    private static boolean enabled() {
        return ChatBubbleClientSetup.config().musicIslandEnabledResolved();
    }

    public void render(DrawContext g, int screenW, int screenH) {
        DangoMusicCompat.NowPlaying np = this.now;
        if (alpha <= ALPHA_EPS || np == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        ChatBubbleTheme.Colors theme = Appearance.snapshot();

        String title = np.title() == null ? "" : np.title();
        if (np.state() != null && np.state().equals("LOADING")) {
            title = Text.translatable("e33chat.music.loading").getString();
        }
        String artist = joinArtists(np.artists());
        String time = formatTime(np.posMs(), np.durationMs());

        int contentMax = MAX_W - TEXT_X - PAD_R;
        int timeW = time.isEmpty() ? 0 : tr.getWidth(time);
        String titleShown = trim(tr, title, contentMax);
        int titleW = tr.getWidth(titleShown);
        int artistBudget = Math.max(24, contentMax - (timeW > 0 ? timeW + GAP : 0));
        String artistShown = trim(tr, artist, artistBudget);
        int artistW = tr.getWidth(artistShown) + (timeW > 0 ? timeW + GAP : 0);
        int textW = Math.max(titleW, artistW);

        int w = Math.max(MIN_W, Math.min(MAX_W, TEXT_X + textW + PAD_R));
        if (screenW > 16) w = Math.min(w, screenW - 8);
        if (w < TEXT_X + 24) w = TEXT_X + 24;

        float cfgAlphaMul = ChatBubbleClientSetup.config().bannerOpacity() / 100f;
        int cornerRadius = ChatBubbleClientSetup.config().bannerCornerRadius();
        int x = (screenW - w) / 2 + ChatBubbleClientSetup.config().bannerOffsetX();
        // 用户决定：不为 DangoMusic 自带的灵动岛让位（他会自己关掉那个岛），本岛位置固定不动
        int y = (int) (ChatBubbleClientSetup.config().bannerOffsetY() + jadeShift);
        int iy = y;

        float bgAlphaMul = alpha * cfgAlphaMul;
        int bg = theme.bannerBg();
        int shadowAlpha = (int) (UiTokens.SHADOW_ALPHA_PANEL * bgAlphaMul);
        RoundRectRenderer.fill(g, x + SHADOW_OFF, iy + SHADOW_OFF,
            x + w + SHADOW_OFF, iy + H + SHADOW_OFF, cornerRadius, shadowAlpha << 24);
        int bgAlpha = (int) ((bg >>> 24) * bgAlphaMul);
        RoundRectRenderer.fill(g, x, iy, x + w, iy + H, cornerRadius,
            (bgAlpha << 24) | (bg & 0x00FFFFFF));

        drawCover(g, np.coverUrl(), x + PAD_L, iy + (H - COVER) / 2, theme);

        int line2Y = iy + LINE1_Y + LINE2_DY;
        if (alpha > 0.003f) {
            int titleAlpha = (int) ((theme.textPrimary() >>> 24) * alpha);
            g.drawText(tr, titleShown, x + TEXT_X, iy + LINE1_Y,
                (titleAlpha << 24) | (theme.textPrimary() & 0x00FFFFFF), false);

            int subAlpha = (int) ((theme.textSecondary() >>> 24) * alpha);
            int subColor = (subAlpha << 24) | (theme.textSecondary() & 0x00FFFFFF);
            g.drawText(tr, artistShown, x + TEXT_X, line2Y, subColor, false);
            if (timeW > 0) {
                g.drawText(tr, time, x + w - PAD_R - timeW, line2Y, subColor, false);
            }
        }

        drawProgress(g, np.posMs(), np.durationMs(), x, iy, w, theme);
    }

    /** 封面：有纹理就圆角贴图，没有就占位块 + 手绘八分音符（不依赖字体是否含 ♪）。 */
    private void drawCover(DrawContext g, String coverUrl, int cx, int cy, ChatBubbleTheme.Colors theme) {
        Identifier cover = CoverTextureCache.get(coverUrl);
        if (cover != null) {
            ColoredTextureRenderer.drawWithAlpha(g, cover, cx, cy, COVER, COVER, alpha);
            return;
        }
        int placeholderAlpha = (int) (0x40 * alpha);
        RoundRectRenderer.fill(g, cx, cy, cx + COVER, cy + COVER, 6,
            (placeholderAlpha << 24) | 0x00FFFFFF);

        int noteAlpha = (int) ((theme.textSecondary() >>> 24) * alpha * 0.9f);
        int noteColor = (noteAlpha << 24) | (theme.textSecondary() & 0x00FFFFFF);
        // 符头（圆角块）
        RoundRectRenderer.fill(g, cx + 8, cy + 13, cx + 14, cy + 18, 2, noteColor);
        // 符干
        g.fill(cx + 13, cy + 6, cx + 15, cy + 14, noteColor);
        // 符尾
        g.fill(cx + 15, cy + 6, cx + 19, cy + 8, noteColor);
    }

    /** 底部 3px 进度条：底条 0x44FFFFFF，填充用主题强调色；duration<=0 时整条不画。 */
    private void drawProgress(DrawContext g, long posMs, long durationMs,
                              int x, int y, int w, ChatBubbleTheme.Colors theme) {
        if (durationMs <= 0L) return;
        int x1 = x + PAD_L;
        int x2 = x + w - PAD_L;
        int y1 = y + H - BAR_H;
        int y2 = y + H;
        if (x2 <= x1) return;

        int trackAlpha = (int) (0x44 * alpha);
        g.fill(x1, y1, x2, y2, (trackAlpha << 24) | 0x00FFFFFF);

        float ratio = Math.max(0f, Math.min(1f, (float) ((double) posMs / (double) durationMs)));
        int filled = Math.round((x2 - x1) * ratio);
        if (filled <= 0) return;
        int accent = theme.iconHover();
        int accentAlpha = (int) ((accent >>> 24) * alpha);
        g.fill(x1, y1, x1 + filled, y2, (accentAlpha << 24) | (accent & 0x00FFFFFF));
    }

    private static String joinArtists(List<String> artists) {
        if (artists == null || artists.isEmpty()) return "";
        if (artists.size() == 1) return artists.get(0) == null ? "" : artists.get(0);
        StringBuilder sb = new StringBuilder();
        for (String a : artists) {
            if (a == null || a.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(a);
        }
        return sb.toString();
    }

    /** {@code 1:23 / 3:45}；时长未知时返回空串（不画时间也不画进度）。 */
    private static String formatTime(long posMs, long durationMs) {
        if (durationMs <= 0L) return "";
        return clock(posMs) + " / " + clock(durationMs);
    }

    private static String clock(long ms) {
        if (ms < 0L) ms = 0L;
        long totalSeconds = ms / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (seconds < 10L) return minutes + ":0" + seconds;
        return minutes + ":" + seconds;
    }

    private static String trim(TextRenderer tr, String text, int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (maxWidth <= 0) return "";
        if (tr.getWidth(text) <= maxWidth) return text;
        String cut = tr.trimToWidth(text, Math.max(0, maxWidth - tr.getWidth("...")));
        return cut + "...";
    }

    /** 与 @ 横幅同一套 Jade 避让：描述框压到顶部中间时，把胶囊下压到描述框下方。 */
    private void updateJadeShift(long nowMs) {
        float target = 0f;
        JadeCompat.Rect jade = JadeCompat.tooltipRect();
        if (jade != null) {
            MinecraftClient mc = MinecraftClient.getInstance();
            int screenW = mc.getWindow().getScaledWidth();
            int screenH = mc.getWindow().getScaledHeight();
            float centerX = screenW / 2f + ChatBubbleClientSetup.config().bannerOffsetX();
            float halfW = MAX_W / 2f + 16f;
            boolean overlapsX = jade.x() < centerX + halfW && jade.right() > centerX - halfW;
            if (overlapsX) {
                target = jade.bottom() + JADE_GAP - ChatBubbleClientSetup.config().bannerOffsetY();
                target = Math.max(0f, Math.min(target, screenH - 48f));
            }
        }

        if (jadeShiftLastMs == 0L) {
            jadeShift = target;
            jadeShiftLastMs = nowMs;
            return;
        }
        float dt = Math.min(250f, Math.max(0f, (float) (nowMs - jadeShiftLastMs)));
        jadeShiftLastMs = nowMs;
        if (dt <= 0f) return;
        float k = 1f - (float) Math.exp(-dt / JADE_SHIFT_TAU_MS);
        jadeShift += (target - jadeShift) * k;
        if (Math.abs(target - jadeShift) < 0.5f) jadeShift = target;
    }
}
