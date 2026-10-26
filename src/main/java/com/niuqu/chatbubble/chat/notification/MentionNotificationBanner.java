package com.niuqu.chatbubble.chat.notification;

import com.mojang.authlib.GameProfile;
import com.niuqu.chatbubble.render.Animation;
import com.niuqu.chatbubble.render.AnimationStyle;
import com.niuqu.chatbubble.render.Appearance;
import com.niuqu.chatbubble.ChatBubbleClientSetup;
import com.niuqu.chatbubble.compat.DangoMusicCompat;
import com.niuqu.chatbubble.compat.JadeCompat;
import com.niuqu.chatbubble.store.ChatMessageStore;
import com.niuqu.chatbubble.render.RoundRectRenderer;
import com.niuqu.chatbubble.render.UiTokens;
import com.niuqu.chatbubble.texture.ColoredTextureRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;

public class MentionNotificationBanner {
    public static final MentionNotificationBanner INSTANCE = new MentionNotificationBanner();

    /** 横幅类型。GROUP = 群聊（新消息 / 被拉进群 / 被踢 / 群解散），
     *  前缀「[群聊]」，头像只在有真实发送者时画（系统类群通知走无头像的纯文本样式）。 */
    public enum NotificationType { MENTION, QUOTE, WHISPER, SYSTEM, GROUP }

    private static final long VISIBLE_MS_PERIOD = 1000;
    private static final long SLIDE_IN_MS = 250;
    private static final long PUSH_MS = 200;
    private static final long EXIT_MS = 150;
    private static final int AVATAR = 24;
    private static final int AVATAR_HAT = 26;
    private static final int AVATAR_X = 8;
    private static final int TEXT_X = AVATAR_X + AVATAR + 6;
    private static final int TEXT_X_PLAIN = 8;   // no-avatar banners (system) start text flush left
    private static final int MAX_TEXT_W = 170;   // fixed content-area width cap for every banner
    private static final int BANNER_H = 36;
    private static final int MAX_MSG_LINES = 2;
    private static final int SHADOW_OFF = UiTokens.SHADOW_OFFSET_PANEL;
    private static final float COMPACT_SCALE = 0.75f;
    private static final int STACK_GAP = 4;
    /** Jade 描述框与横幅之间的最小间距（px）。 */
    private static final int JADE_GAP = 6;
    /** 避让下压的平滑时间常数（ms），避免描述框闪出闪没时横幅抖动。 */
    private static final double JADE_SHIFT_TAU_MS = 90.0;
    private static final UUID NIL_UUID = new UUID(0, 0);

    /** Newest first. */
    private final List<ActiveBanner> banners = new ArrayList<>();
    private final List<ExitingBanner> exiting = new ArrayList<>();

    /** Jade 描述框避让的当前下压量（平滑过渡）与其上次采样时间。 */
    private float jadeShift;
    private long jadeShiftLastMs;
    /** Last logged stack sizes, so the debug line only fires on change. */
    private int lastLoggedVisible = -1;
    private int lastLoggedExiting = -1;
    /** 聊天面板打开时音乐岛改画在面板下方，此时屏内 @ 横幅不为它让位。 */
    private boolean musicIslandSuppressed;

    private MentionNotificationBanner() {}

    public void enqueue(UUID senderUUID, Text senderName, Text content,
                        int messageIndex, NotificationType type) {
        MinecraftClient mc = MinecraftClient.getInstance();

        String prefix = switch (type) {
            case MENTION -> Text.translatable("e33chat.banner.mention").getString();
            case QUOTE -> Text.translatable("e33chat.banner.quote").getString();
            case WHISPER -> Text.translatable("e33chat.banner.whisper").getString();
            case SYSTEM -> Text.translatable("e33chat.banner.system").getString();
            case GROUP -> Text.translatable("e33chat.banner.group").getString();
        };
        Text labeledName = Text.literal(prefix).append(senderName);

        // System banners carry no sender — plain text, no avatar, flush text start.
        // [系统] 标签与第一行内容同行，内容宽度预算扣掉标签宽，避免同行溢出横幅
        // 群聊通知（被拉进群 / 被踢 / 解散）没有发送者（nil UUID）→ 同样走无头像的纯文本样式，
        // 否则会画出一个默认皮肤的假头像。
        boolean hasAvatar = type != NotificationType.SYSTEM
            && senderUUID != null && !NIL_UUID.equals(senderUUID);
        int textOriginX = hasAvatar ? TEXT_X : TEXT_X_PLAIN;
        int maxTextW = Math.min(MAX_TEXT_W, mc.getWindow().getScaledWidth() - textOriginX - 12);
        int dotsW = mc.textRenderer.getWidth("...");
        int contentMaxW = hasAvatar ? maxTextW : Math.max(40, maxTextW - mc.textRenderer.getWidth(prefix));

        List<OrderedText> nameLines = mc.textRenderer.wrapLines(labeledName, maxTextW);
        OrderedText nameSeq;
        if (nameLines.isEmpty()) {
            nameSeq = OrderedText.EMPTY;
        } else if (nameLines.size() > 1) {
            String plainName = mc.textRenderer.trimToWidth(
                labeledName.getString(), maxTextW - dotsW) + "...";
            nameSeq = mc.textRenderer.wrapLines(Text.literal(plainName), maxTextW).get(0);
        } else {
            nameSeq = nameLines.get(0);
        }

        List<OrderedText> msgLines = mc.textRenderer.wrapLines(content, contentMaxW);
        if (msgLines.size() > MAX_MSG_LINES) {
            // Styled truncation: keeps per-run colors of multi-colored system lines
            msgLines = mc.textRenderer.wrapLines(truncateStyled(content, contentMaxW * 2 - dotsW, mc.textRenderer, "..."), contentMaxW);
            if (msgLines.size() > MAX_MSG_LINES)
                msgLines = msgLines.subList(0, MAX_MSG_LINES);
        }

        int textW = mc.textRenderer.getWidth(nameSeq);
        for (var line : msgLines) textW = Math.max(textW, mc.textRenderer.getWidth(line));
        if (!hasAvatar && !msgLines.isEmpty()) {
            // 纯文本：[系统] 与第一行内容并排，横幅宽度按合并行算
            textW = Math.max(textW, mc.textRenderer.getWidth(nameSeq) + mc.textRenderer.getWidth(msgLines.get(0)));
        }
        int bannerW = textOriginX + textW + 12;
        // 高度：头像横幅固定 36（装名字+内容+头像）；纯文本系统横幅按行数紧凑
        // （上下各 5px 边距），1 行 ~20 / 2 行 ~30，不再留大片空白
        int bannerH = hasAvatar ? BANNER_H
            : mc.textRenderer.fontHeight * msgLines.size() + 10;

        PendingBanner pb = new PendingBanner(senderUUID, senderName, content, messageIndex,
            type, hasAvatar, nameSeq, msgLines, textW, bannerW, bannerH);
        addBanner(pb);
    }

    public int pendingCount() { return banners.size() + exiting.size(); }

    public void tick() {
        long now = System.currentTimeMillis();
        // DangoMusic 联动：每帧喂一次音乐岛（没装 DangoMusic 时 poll() 恒 null，无副作用）
        MusicIslandBanner.INSTANCE.tick(DangoMusicCompat.poll());
        updateJadeShift(now);

        // Natural expiry: snapshot every current position, remove expired banners,
        // then let the remaining ones push up to fill the gaps.
        List<ActiveBanner> expired = new ArrayList<>();
        for (ActiveBanner b : banners) {
            if (now - b.bornMs >= b.totalVisibleMs) expired.add(b);
        }
        if (!expired.isEmpty()) {
            for (ActiveBanner b : banners) {
                b.fromY = currentY(b, now);
                b.fromScale = currentScale(b, now);
            }
            for (ActiveBanner b : expired) {
                float y = b.fromY;
                float scale = b.fromScale;
                banners.remove(b);
                exiting.add(new ExitingBanner(b.data, now, y, scale));
            }
            for (ActiveBanner b : banners) {
                b.enterStartMs = -1;
                b.pushStartMs = now;
            }
        }

        exiting.removeIf(e -> now - e.startMs >= EXIT_MS);

        if (!banners.isEmpty() || !exiting.isEmpty()) {
            // Only log on change: this used to fire every frame while a banner was
            // on screen, which flooded the log (and the log I/O sat on the render thread).
            if (banners.size() != lastLoggedVisible || exiting.size() != lastLoggedExiting) {
                lastLoggedVisible = banners.size();
                lastLoggedExiting = exiting.size();
                ChatMessageStore.debugLog(() -> "[e33chat] Banner stack | visible=" + banners.size()
                    + " | exiting=" + exiting.size());
            }
        } else if (lastLoggedVisible != 0 || lastLoggedExiting != 0) {
            lastLoggedVisible = 0;
            lastLoggedExiting = 0;
            ChatMessageStore.debugLog(() -> "[e33chat] Banner stack | visible=0 | exiting=0");
        }
    }

    public void render(DrawContext g, int screenW, int screenH) {
        render(g, screenW, screenH, true);
    }

    /**
     * @param withMusicIsland 是否绘制常驻音乐岛。聊天面板打开时音乐岛改由 HUD 层画在
     *                        面板**下方**（它不常驻以外的横幅仍然叠在最上层），屏内叠层
     *                        只画 @ 横幅时传 {@code false}：不画岛，@ 横幅也不为它让位。
     */
    public void render(DrawContext g, int screenW, int screenH, boolean withMusicIsland) {
        this.musicIslandSuppressed = !withMusicIsland;
        // 关掉 @ 横幅不应连带关掉音乐岛：两者任一需要画就继续往下走
        if (!ChatBubbleClientSetup.config().mentionBannerEnabled()
            && !(withMusicIsland && MusicIslandBanner.INSTANCE.visible())) return;
        // 到期/退出清除：HUD 层每帧 tick，但屏内路径(renderBannerForScreen)不走 HUD tick，
        // 所以在渲染开头先 tick 一次，保证横幅在任意渲染路径都会按时自动隐藏。
        this.tick();
        // 绘制顺序：先画上方的音乐岛，再画 @ 横幅（横幅的顶部 Y 由 stackShift() 让位）
        if (withMusicIsland && MusicIslandBanner.INSTANCE.visible()) {
            MusicIslandBanner.INSTANCE.render(g, screenW, screenH);
        }
        if (!ChatBubbleClientSetup.config().mentionBannerEnabled()) return;
        if (banners.isEmpty() && exiting.isEmpty()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        long now = System.currentTimeMillis();

        // Exiting banners render behind the active stack while they shrink/fade.
        for (ExitingBanner e : exiting) {
            float t = Math.min(1f, (float) (now - e.startMs) / EXIT_MS);
            float alpha = exitFade(1f - t);
            float scale = e.scale * (1f - 0.5f * t);
            renderBanner(g, e.data, screenW, e.y, scale, alpha);
        }

        for (int i = 0; i < banners.size(); i++) {
            ActiveBanner b = banners.get(i);
            float y = currentY(b, now);
            float scale = currentScale(b, now);
            float alpha = currentAlpha(b, now);
            renderBanner(g, b.data, screenW, y, scale, alpha);
        }
    }

    public int currentMessageIndex() {
        return banners.isEmpty() ? -1 : banners.get(0).data.messageIndex;
    }

    private void addBanner(PendingBanner pb) {
        long now = System.currentTimeMillis();
        int maxStack = maxStack();

        // New messages always win: drop any banners that are already exiting.
        exiting.clear();

        // Snapshot current render state before mutating the list.
        for (ActiveBanner b : banners) {
            b.fromY = currentY(b, now);
            b.fromScale = currentScale(b, now);
        }

        // Full stack: evict the oldest (bottom) banner.
        if (banners.size() >= maxStack && !banners.isEmpty()) {
            ActiveBanner oldest = banners.get(banners.size() - 1);
            exiting.add(new ExitingBanner(oldest.data, now, oldest.fromY, oldest.fromScale));
            banners.remove(banners.size() - 1);
        }

        ActiveBanner nb = new ActiveBanner(pb, now, visibleDurationMs(), now);
        banners.add(0, nb);

        // Every previously visible banner is now pushed down / compacted.
        for (ActiveBanner b : banners) {
            if (b != nb) {
                b.enterStartMs = -1;
                b.pushStartMs = now;
            }
        }
    }

    private long visibleDurationMs() {
        return (long) ChatBubbleClientSetup.config().mentionBannerDuration() * VISIBLE_MS_PERIOD;
    }

    private int maxStack() {
        Integer v = ChatBubbleClientSetup.config().bannerMaxStack();
        int value = v != null ? v : 3;
        return Math.max(1, Math.min(5, value));
    }

    /**
     * Jade(玉) 兼容：描述框显示且与本叠横幅在水平方向有重叠时，把横幅整体下压到描述框下方，
     * 否则回到用户配置的顶部偏移。下压量做指数平滑，避免一边走一边看方块时横幅上下抖动。
     */
    private void updateJadeShift(long now) {
        float target = 0f;
        JadeCompat.Rect jade = JadeCompat.tooltipRect();
        if (jade != null) {
            MinecraftClient mc = MinecraftClient.getInstance();
            int screenW = mc.getWindow().getScaledWidth();
            int screenH = mc.getWindow().getScaledHeight();
            float centerX = screenW / 2f + ChatBubbleClientSetup.config().bannerOffsetX();
            float halfW = 0f;
            for (ActiveBanner b : banners) halfW = Math.max(halfW, b.data.bannerW / 2f);
            for (ExitingBanner e : exiting) halfW = Math.max(halfW, e.data.bannerW / 2f);
            if (halfW <= 0f) halfW = 100f;
            boolean overlapsX = jade.x() < centerX + halfW && jade.right() > centerX - halfW;
            if (overlapsX) {
                target = jade.bottom() + JADE_GAP - ChatBubbleClientSetup.config().bannerOffsetY();
                // 描述框在屏幕下方时，下压也要保证横幅留在屏幕内
                target = Math.max(0f, Math.min(target, screenH - 48f));
            }
        }

        if (jadeShiftLastMs == 0L) {
            jadeShift = target;
            jadeShiftLastMs = now;
            return;
        }
        float dt = Math.min(250f, Math.max(0f, now - jadeShiftLastMs));
        jadeShiftLastMs = now;
        if (dt <= 0f) return;
        float k = 1f - (float) Math.exp(-dt / JADE_SHIFT_TAU_MS);
        jadeShift += (target - jadeShift) * k;
        if (Math.abs(target - jadeShift) < 0.5f) jadeShift = target;
    }

    /** 横幅叠顶部 Y：用户配置的顶部偏移 + Jade 避让下压量 + 音乐岛让位量。 */
    private float stackTopY() {
        float own = ChatBubbleClientSetup.config().bannerOffsetY() + jadeShift;
        // 聊天面板打开时音乐岛画在面板下方（屏内只画 @ 横幅），此时不让位
        if (musicIslandSuppressed) return own;
        int shift = MusicIslandBanner.INSTANCE.stackShift();
        if (shift <= 0) return own;
        // 两个胶囊的 Jade 下压各自独立平滑（水平重叠判定用的半宽不同），所以不能只加
        // 固定高度：取「本横幅自己的下压 + 让位量」与本岛真实底边的较大者，任何情况下都不重叠。
        float islandBottom = ChatBubbleClientSetup.config().bannerOffsetY()
            + MusicIslandBanner.INSTANCE.jadeShiftPx()
            + MusicIslandBanner.INSTANCE.heightPx() + STACK_GAP;
        return Math.max(own + shift, islandBottom);
    }

    private float targetY(int index, ActiveBanner b) {
        float y = stackTopY();
        for (int i = 0; i < index; i++) {
            ActiveBanner prev = banners.get(i);
            float prevScale = i == 0 ? 1f : COMPACT_SCALE;
            y += prev.data.bannerH * prevScale + STACK_GAP;
        }
        return y;
    }

    private float targetScale(int index) {
        return index == 0 ? 1f : COMPACT_SCALE;
    }

    private float currentY(ActiveBanner b, long now) {
        int i = banners.indexOf(b);
        if (i < 0) return stackTopY();
        float target = targetY(i, b);
        if (b.pushStartMs >= 0) {
            float t = Math.min(1f, (float) (now - b.pushStartMs) / PUSH_MS);
            float e = Animation.easeOutCubic(t);
            return b.fromY + (target - b.fromY) * e;
        }
        if (i == 0 && b.enterStartMs >= 0) {
            long elapsed = now - b.enterStartMs;
            if (elapsed < SLIDE_IN_MS) {
                float raw = Math.min(1f, (float) elapsed / SLIDE_IN_MS);
                AnimationStyle bstyle = AnimationStyle.parse(ChatBubbleClientSetup.config().bannerAnimStyle());
                if (bstyle == AnimationStyle.SLIDE) {
                    float c = 1.70158f;
                    float slide = 1f + c * (float) Math.pow(raw - 1, 3) + c * (float) Math.pow(raw - 1, 2);
                    return (-b.data.bannerH) + slide * b.data.bannerH + stackTopY();
                }
            }
        }
        return target;
    }

    private float currentScale(ActiveBanner b, long now) {
        int i = banners.indexOf(b);
        if (i < 0) return 1f;
        float target = targetScale(i);
        if (b.pushStartMs >= 0) {
            float t = Math.min(1f, (float) (now - b.pushStartMs) / PUSH_MS);
            float e = Animation.easeOutCubic(t);
            return b.fromScale + (target - b.fromScale) * e;
        }
        if (i == 0 && b.enterStartMs >= 0) {
            long elapsed = now - b.enterStartMs;
            if (elapsed < SLIDE_IN_MS) {
                float raw = Math.min(1f, (float) elapsed / SLIDE_IN_MS);
                AnimationStyle bstyle = AnimationStyle.parse(ChatBubbleClientSetup.config().bannerAnimStyle());
                if (bstyle == AnimationStyle.ZOOM) {
                    return 0.8f + 0.2f * Animation.easeOutBack(raw);
                }
            }
        }
        return target;
    }

    private float currentAlpha(ActiveBanner b, long now) {
        if (b.pushStartMs >= 0) return 1f;
        int i = banners.indexOf(b);
        if (i == 0 && b.enterStartMs >= 0) {
            long elapsed = now - b.enterStartMs;
            if (elapsed < SLIDE_IN_MS) {
                float raw = Math.min(1f, (float) elapsed / SLIDE_IN_MS);
                AnimationStyle bstyle = AnimationStyle.parse(ChatBubbleClientSetup.config().bannerAnimStyle());
                if (bstyle == AnimationStyle.NONE) return 1f;
                if (bstyle == AnimationStyle.SLIDE) return Math.min(1f, raw / 0.6f);
                return Animation.easeOutQuad(raw);
            }
        }
        return 1f;
    }

    private void renderBanner(DrawContext g, PendingBanner b, int screenW,
                              float y, float scale, float alpha) {
        if (alpha <= 0.003f) return;
        MinecraftClient mc = MinecraftClient.getInstance();

        float bgAlphaMul = alpha * (ChatBubbleClientSetup.config().bannerOpacity() / 100f);

        var theme = Appearance.snapshot();
        int bg = theme.bannerBg();
        int cornerRadius = ChatBubbleClientSetup.config().bannerCornerRadius();

        int bannerW = b.bannerW;
        int bannerH = b.bannerH;
        int x = (screenW - bannerW) / 2 + ChatBubbleClientSetup.config().bannerOffsetX();
        int iy = (int) y;

        if (scale != 1f) {
            g.getMatrices().pushMatrix();
            g.getMatrices().translate(x + bannerW / 2f, y + bannerH / 2f);
            g.getMatrices().scale(scale, scale);
            g.getMatrices().translate(-(x + bannerW / 2f), -(y + bannerH / 2f));
        }

        int shadowAlpha = (int) (UiTokens.SHADOW_ALPHA_PANEL * bgAlphaMul);
        int shadowColor = (shadowAlpha << 24);
        RoundRectRenderer.fill(g, x + SHADOW_OFF, iy + SHADOW_OFF,
            x + bannerW + SHADOW_OFF, iy + bannerH + SHADOW_OFF, cornerRadius, shadowColor);

        // Background：SDF 圆角（与阴影同 shader，半径配置实时生效；不可被资源包覆盖）
        int bgAlpha = (int) ((bg >>> 24) * bgAlphaMul);
        RoundRectRenderer.fill(g, x, iy, x + bannerW, iy + bannerH, cornerRadius,
            (bgAlpha << 24) | (bg & 0x00FFFFFF));

        int textX = x + (b.hasAvatar ? TEXT_X : TEXT_X_PLAIN);
        // Compact banners show only the first content line, matching the mobile
        // notification style: avatar + title + one-line preview.
        List<OrderedText> drawLines = scale < 1f && b.msgLines.size() > 1
            ? b.msgLines.subList(0, 1) : b.msgLines;
        int nameColor, msgColor;
        if (b.hasAvatar) {
            int avatarY = iy + (bannerH - AVATAR_HAT) / 2;
            Identifier skin = getSkin(b.senderUUID, b.senderName.getString());
            drawPlayerHead(g, skin, x + AVATAR_X, avatarY, AVATAR, AVATAR_HAT, alpha);

            // Name (prefix already baked into nameSeq in enqueue)
            int nameY = iy + 6;
            int nameAlpha = (int) ((theme.textPrimary() >>> 24) * alpha);
            nameColor = (nameAlpha << 24) | (theme.textPrimary() & 0x00FFFFFF);
            g.drawText(mc.textRenderer, b.nameSeq, textX, nameY, nameColor, false);

            // Message lines
            int msgAlpha = (int) ((theme.textSecondary() >>> 24) * alpha);
            msgColor = (msgAlpha << 24) | (theme.textSecondary() & 0x00FFFFFF);
            int msgY = nameY + mc.textRenderer.fontHeight + 2;
            for (int i = 0; i < drawLines.size(); i++)
                g.drawText(mc.textRenderer, drawLines.get(i), textX,
                    msgY + i * mc.textRenderer.fontHeight, msgColor, false);
        } else {
            // Plain-text banner: [系统] label + content vertically centered, single row.
            // System notices read as yellow like the reference toast (bannerText).
            int nameAlpha = (int) ((theme.bannerText() >>> 24) * alpha);
            nameColor = (nameAlpha << 24) | (theme.bannerText() & 0x00FFFFFF);
            int msgAlpha = (int) ((theme.bannerText() >>> 24) * alpha);
            msgColor = (msgAlpha << 24) | (theme.bannerText() & 0x00FFFFFF);
            int lineH = mc.textRenderer.fontHeight;
            int totalH = lineH * drawLines.size();
            int textY = iy + (bannerH - totalH) / 2;
            g.drawText(mc.textRenderer, b.nameSeq, textX, textY, nameColor, false);
            int contentX = textX + mc.textRenderer.getWidth(b.nameSeq);
            g.drawText(mc.textRenderer, drawLines.get(0), contentX, textY, msgColor, false);
            for (int i = 1; i < drawLines.size(); i++)
                g.drawText(mc.textRenderer, drawLines.get(i), textX,
                    textY + i * lineH, msgColor, false);
        }

        if (scale != 1f) g.getMatrices().popMatrix();
    }

    // Delegates to SkinResolver: one shared, fully non-blocking implementation.
    // Rendering must never wait on a skin HTTP request — see SkinResolver's docs.
    private Identifier getSkin(UUID uuid, String name) {
        return com.niuqu.chatbubble.render.SkinResolver.getSkin(uuid, name);
    }

    private void drawPlayerHead(DrawContext g, Identifier skin, int x, int y,
                                 int baseSize, int hatSize, float alpha) {
        if (alpha <= 0.003f) return;
        ColoredTextureRenderer.drawWithAlpha(g, skin, x, y, baseSize, baseSize, 8.0F, 8.0F, 8, 8, 64, 64, alpha);
        int hatOff = (hatSize - baseSize) / 2;
        ColoredTextureRenderer.drawWithAlpha(g, skin, x - hatOff, y - hatOff, hatSize, hatSize, 40.0F, 8.0F, 8, 8, 64, 64, alpha);
    }

    /** 退出淡出曲线：ease-in（慢起快走），07 §1.4 退出规范（raw 从 1→0）。 */
    private static float exitFade(float raw) {
        return raw * (2f - raw);
    }

    // Width-limit a text run by run, keeping each run's style (colors of
    // multi-colored system lines survive truncation), then append the ellipsis.
    private static Text truncateStyled(Text src, int maxWidth,
                                       net.minecraft.client.font.TextRenderer font, String suffix) {
        int budget = maxWidth - font.getWidth(suffix);
        MutableText out = Text.empty();
        int[] used = {0};
        src.visit((style, text) -> {
            if (used[0] >= budget) return java.util.Optional.<Object>empty();
            int w = font.getWidth(text);
            if (used[0] + w <= budget) {
                out.append(Text.literal(text).fillStyle(style));
                used[0] += w;
            } else {
                String sub = font.trimToWidth(text, budget - used[0]);
                out.append(Text.literal(sub).fillStyle(style));
                used[0] = budget;
            }
            return java.util.Optional.<Object>empty();
        }, net.minecraft.text.Style.EMPTY);
        return out.append(Text.literal(suffix));
    }

    private static final class ActiveBanner {
        final PendingBanner data;
        final long bornMs;
        final long totalVisibleMs;
        long enterStartMs;
        long pushStartMs = -1;
        float fromY;
        float fromScale;

        ActiveBanner(PendingBanner data, long bornMs, long totalVisibleMs, long enterStartMs) {
            this.data = data;
            this.bornMs = bornMs;
            this.totalVisibleMs = totalVisibleMs;
            this.enterStartMs = enterStartMs;
        }
    }

    private static final class ExitingBanner {
        final PendingBanner data;
        final long startMs;
        final float y;
        final float scale;

        ExitingBanner(PendingBanner data, long startMs, float y, float scale) {
            this.data = data;
            this.startMs = startMs;
            this.y = y;
            this.scale = scale;
        }
    }

    private record PendingBanner(UUID senderUUID, Text senderName, Text content,
                                  int messageIndex, NotificationType type, boolean hasAvatar,
                                  OrderedText nameSeq, List<OrderedText> msgLines,
                                  int textW, int bannerW, int bannerH) {}
}
