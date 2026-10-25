package com.niuqu.chatbubble.config;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

public record ChatBubbleConfig(
    boolean enabled,
    String theme,
    // Theme mode: "dark" | "light". `theme` carries this mode.
    String themeAccent,
    Map<String, String> themeCustom,
    boolean redDotEnabled,
    boolean hideChatIcon,
    boolean animationEnabled,
    boolean systemChatAsBubble,
    boolean antiSpam,
    boolean chatHistoryEnabled,
    int historyRetentionDays,
    int timeSeparatorMinutes,
    int panelWidth,
    int bubbleCornerRadius,
    String ownBubbleColor,
    String otherBubbleColor,
    String ownTextColor,
    String otherTextColor,
    boolean soundPublic,
    boolean soundSystem,
    boolean soundWhisper,
    boolean debugLog,
    boolean preserveInput,
    boolean colorCodes,
    List<String> sidebarHidePatterns,
    List<String> blockedPlayers,
    List<String> quickChatPhrases,
    boolean mentionBannerEnabled,
    boolean systemBannerEnabled,
    Integer mentionBannerDuration,
    boolean mentionSoundEnabled,
    boolean mentionRequireAt,
    boolean mentionWhisperBanner,
    boolean blurEnabled,
    Integer panelOpacity,
    Integer soundVolume,
    boolean ownMentionNotify,
    boolean ownQuoteNotify,
    boolean ownWhisperNotify,
    Integer bannerCornerRadius,
    @SerializedName("banner_offset_x") int bannerOffsetX,
    @SerializedName("banner_offset_y") int bannerOffsetY,
    @SerializedName("banner_max_stack") Integer bannerMaxStack,
    String panelAnimStyle,
    String bannerAnimStyle,
    String popupAnimStyle,
    String messageAnimStyle,
    Boolean imageRenderEnabled,
    Boolean receiveImages,
    // Image upload host (2.3.11). null/blank = uguu.se default (~3h expiry);
    // response: "text" (body is the URL) or "json:<field>" (default json:files[0].url).
    String uploadUrl,
    String uploadField,
    String uploadExtra,
    String uploadResponse,
    Integer messageGap,
    Integer avatarSize,
    Boolean hideRepeatedAvatars,
    boolean closeChatOnSend,
    Integer bannerOpacity,
    Integer bubbleSize,
    // 免打扰（DND）—— **公屏/系统** 部分：只静音公屏与系统消息的「灵动岛」横幅与提示音，
    // @提及/引用优先级更高仍会弹出；私聊按每个对象独立开关（见 dndWhisperPlayers）。
    // 左下角聊天图标的红点（redDotEnabled）语义不变。默认关闭。
    boolean dndEnabled,
    // 公屏消息小窗：聊天面板关闭时，在左下角聊天图标正上方常驻显示最近 3 条公屏消息。
    // 这里用可空 Boolean 而不是 boolean —— record 走 Gson 反序列化时，旧配置文件里
    // 缺这个键会读成 null；只有 null 才能区分「旧文件没有这个键」（回落默认 true）
    // 与「用户显式关闭」。null → 默认值的收敛在 ConfigManager.mergeWithDefaults。
    Boolean publicOverlayEnabled,
    // 公屏小窗「静默多久后淡出」的秒数（1~60，默认 5）。同样是可空 Integer：
    // 旧配置文件缺键读成 null → 回落默认 5，由 ConfigManager.mergeWithDefaults 收敛。
    // 运行时由 PublicChatOverlay 每帧读取，配置屏保存后即时生效（无需重启）。
    Integer publicOverlayIdleSeconds,
    // 私聊免打扰名单（存玩家名）：每个私聊对象各自一个开关，A 的私聊免打扰不影响 B。
    // 公屏/系统免打扰仍然用上面的 dndEnabled（语义 = 「公屏免打扰」）。
    // 旧配置文件没有这个键时 Gson 读成 null → 由 ConfigManager.mergeWithDefaults 收敛成空表；
    // 空表 = 没有任何私聊被免打扰（与旧版全局 DND 的默认行为一致）。
    List<String> dndWhisperPlayers,
    // 通道节食（实验性）：在**非 Fabric 服务器**（Paper/原版等）上把 PLAY 阶段随
    // minecraft:register 发出的自定义通道声明压到最少，规避 Paper 的「通道数量上限」踢人。
    // 同样用可空 Boolean 区分「旧配置文件没有这个键」（null → 默认 false，关闭）与
    // 「用户显式关闭」。关闭时行为与旧版完全一致；收敛在 ConfigManager.mergeWithDefaults，
    // 运行期由 ChannelRegistrationDietMixin 读取。
    Boolean channelDietEnabled
) {
    /** 公屏小窗静默秒数（后续在 {@link #publicOverlayIdleSeconds()}）的默认值与合法范围：
     *  配置屏的输入框范围、旧配置缺键的回落值、运行时的夹取都用这三个常量，避免三处漂移。 */
    public static final int PUBLIC_OVERLAY_IDLE_DEFAULT_SECONDS = 5;
    public static final int PUBLIC_OVERLAY_IDLE_MIN_SECONDS = 1;
    public static final int PUBLIC_OVERLAY_IDLE_MAX_SECONDS = 60;

    public static ChatBubbleConfig defaults() {
        return new ChatBubbleConfig(
            true, "dark", "blue", Map.of(), true, false, true,
            false, true,
            false, 0, 5, 1000, 4,
            "#1E90FF", "#4A4A4A", "#FFFFFF", "#FFFFFF",
            false, false, true, false, true, false,
            List.of(), List.of(), List.of(),
            true, true, 4, true, true, true,
            false, 80, 80, false, false, false, 4, 0, 0, 3,
            "slide", "slide", "fade", "fade",
            true, true,
            null, null, null, null,
            6, 20, true, false, 100, 9,
            false,
            // 公屏消息小窗默认开启（用户要的功能）
            true,
            // 公屏小窗静默 5 秒后淡出
            PUBLIC_OVERLAY_IDLE_DEFAULT_SECONDS,
            // 私聊免打扰名单默认空（每个私聊各自开关，默认都不免打扰）
            List.of(),
            // 通道节食默认关闭（关闭 = 行为与旧版完全一致）
            false
        );
    }

    public static int parseHexColor(String hex, int defaultColor) {
        if (hex == null) return defaultColor;
        try {
            String h = hex.replace("#", "").trim();
            if (h.length() != 6) return defaultColor;
            return 0xFF000000 | Integer.parseInt(h, 16);
        } catch (NumberFormatException e) {
            return defaultColor;
        }
    }

    public ChatBubbleConfig withTheme(String theme) {
        return new ChatBubbleConfig(enabled, theme, themeAccent, themeCustom, redDotEnabled, hideChatIcon, animationEnabled,
            systemChatAsBubble, antiSpam,
            chatHistoryEnabled, historyRetentionDays, timeSeparatorMinutes,
            panelWidth, bubbleCornerRadius, ownBubbleColor, otherBubbleColor, ownTextColor, otherTextColor,
            soundPublic, soundSystem, soundWhisper, debugLog, preserveInput, colorCodes, sidebarHidePatterns, blockedPlayers, quickChatPhrases,
            mentionBannerEnabled, systemBannerEnabled, mentionBannerDuration, mentionSoundEnabled, mentionRequireAt, mentionWhisperBanner,
            blurEnabled, panelOpacity, soundVolume, ownMentionNotify, ownQuoteNotify, ownWhisperNotify, bannerCornerRadius, bannerOffsetX, bannerOffsetY, bannerMaxStack,
            panelAnimStyle, bannerAnimStyle, popupAnimStyle, messageAnimStyle, imageRenderEnabled, receiveImages,
            uploadUrl, uploadField, uploadExtra, uploadResponse, messageGap, avatarSize, hideRepeatedAvatars, closeChatOnSend, bannerOpacity, bubbleSize, dndEnabled, publicOverlayEnabled,
            publicOverlayIdleSeconds,
            dndWhisperPlayers,
            channelDietEnabled);
    }

    public ChatBubbleConfig withQuickChatPhrases(List<String> phrases) {
        return new ChatBubbleConfig(enabled, theme, themeAccent, themeCustom, redDotEnabled, hideChatIcon, animationEnabled,
            systemChatAsBubble, antiSpam,
            chatHistoryEnabled, historyRetentionDays, timeSeparatorMinutes,
            panelWidth, bubbleCornerRadius, ownBubbleColor, otherBubbleColor, ownTextColor, otherTextColor,
            soundPublic, soundSystem, soundWhisper, debugLog, preserveInput, colorCodes, sidebarHidePatterns, blockedPlayers, phrases,
            mentionBannerEnabled, systemBannerEnabled, mentionBannerDuration, mentionSoundEnabled, mentionRequireAt, mentionWhisperBanner,
            blurEnabled, panelOpacity, soundVolume, ownMentionNotify, ownQuoteNotify, ownWhisperNotify, bannerCornerRadius, bannerOffsetX, bannerOffsetY, bannerMaxStack,
            panelAnimStyle, bannerAnimStyle, popupAnimStyle, messageAnimStyle, imageRenderEnabled, receiveImages,
            uploadUrl, uploadField, uploadExtra, uploadResponse, messageGap, avatarSize, hideRepeatedAvatars, closeChatOnSend, bannerOpacity, bubbleSize, dndEnabled, publicOverlayEnabled,
            publicOverlayIdleSeconds,
            dndWhisperPlayers,
            channelDietEnabled);
    }

    public ChatBubbleConfig withSidebarHidePatterns(List<String> patterns) {
        return new ChatBubbleConfig(enabled, theme, themeAccent, themeCustom, redDotEnabled, hideChatIcon, animationEnabled,
            systemChatAsBubble, antiSpam,
            chatHistoryEnabled, historyRetentionDays, timeSeparatorMinutes,
            panelWidth, bubbleCornerRadius, ownBubbleColor, otherBubbleColor, ownTextColor, otherTextColor,
            soundPublic, soundSystem, soundWhisper, debugLog, preserveInput, colorCodes, patterns, blockedPlayers, quickChatPhrases,
            mentionBannerEnabled, systemBannerEnabled, mentionBannerDuration, mentionSoundEnabled, mentionRequireAt, mentionWhisperBanner,
            blurEnabled, panelOpacity, soundVolume, ownMentionNotify, ownQuoteNotify, ownWhisperNotify, bannerCornerRadius, bannerOffsetX, bannerOffsetY, bannerMaxStack,
            panelAnimStyle, bannerAnimStyle, popupAnimStyle, messageAnimStyle, imageRenderEnabled, receiveImages,
            uploadUrl, uploadField, uploadExtra, uploadResponse, messageGap, avatarSize, hideRepeatedAvatars, closeChatOnSend, bannerOpacity, bubbleSize, dndEnabled, publicOverlayEnabled,
            publicOverlayIdleSeconds,
            dndWhisperPlayers,
            channelDietEnabled);
    }

    public ChatBubbleConfig withBlockedPlayers(List<String> blocked) {
        return new ChatBubbleConfig(enabled, theme, themeAccent, themeCustom, redDotEnabled, hideChatIcon, animationEnabled,
            systemChatAsBubble, antiSpam,
            chatHistoryEnabled, historyRetentionDays, timeSeparatorMinutes,
            panelWidth, bubbleCornerRadius, ownBubbleColor, otherBubbleColor, ownTextColor, otherTextColor,
            soundPublic, soundSystem, soundWhisper, debugLog, preserveInput, colorCodes, sidebarHidePatterns, blocked, quickChatPhrases,
            mentionBannerEnabled, systemBannerEnabled, mentionBannerDuration, mentionSoundEnabled, mentionRequireAt, mentionWhisperBanner,
            blurEnabled, panelOpacity, soundVolume, ownMentionNotify, ownQuoteNotify, ownWhisperNotify, bannerCornerRadius, bannerOffsetX, bannerOffsetY, bannerMaxStack,
            panelAnimStyle, bannerAnimStyle, popupAnimStyle, messageAnimStyle, imageRenderEnabled, receiveImages,
            uploadUrl, uploadField, uploadExtra, uploadResponse, messageGap, avatarSize, hideRepeatedAvatars, closeChatOnSend, bannerOpacity, bubbleSize, dndEnabled, publicOverlayEnabled,
            publicOverlayIdleSeconds,
            dndWhisperPlayers,
            channelDietEnabled);
    }

    public ChatBubbleConfig withThemeValues(String theme, String themeAccent, Map<String, String> themeCustom) {
        return new ChatBubbleConfig(enabled, theme, themeAccent, themeCustom, redDotEnabled, hideChatIcon, animationEnabled,
            systemChatAsBubble, antiSpam,
            chatHistoryEnabled, historyRetentionDays, timeSeparatorMinutes,
            panelWidth, bubbleCornerRadius, ownBubbleColor, otherBubbleColor, ownTextColor, otherTextColor,
            soundPublic, soundSystem, soundWhisper, debugLog, preserveInput, colorCodes, sidebarHidePatterns, blockedPlayers, quickChatPhrases,
            mentionBannerEnabled, systemBannerEnabled, mentionBannerDuration, mentionSoundEnabled, mentionRequireAt, mentionWhisperBanner,
            blurEnabled, panelOpacity, soundVolume, ownMentionNotify, ownQuoteNotify, ownWhisperNotify, bannerCornerRadius, bannerOffsetX, bannerOffsetY, bannerMaxStack,
            panelAnimStyle, bannerAnimStyle, popupAnimStyle, messageAnimStyle, imageRenderEnabled, receiveImages,
            uploadUrl, uploadField, uploadExtra, uploadResponse, messageGap, avatarSize, hideRepeatedAvatars, closeChatOnSend, bannerOpacity, bubbleSize, dndEnabled, publicOverlayEnabled,
            publicOverlayIdleSeconds,
            dndWhisperPlayers,
            channelDietEnabled);
    }

    /** 免打扰开关（标题栏月亮按钮写入，重启后保留）。
     *  语义 = **公屏/系统免打扰**；私聊改为每个对象独立，见
     *  {@link #withWhisperDnd(String, boolean)} / {@link #isWhisperDnd(String)}。 */
    public ChatBubbleConfig withDndEnabled(boolean enabled) {
        return new ChatBubbleConfig(this.enabled(), this.theme(), this.themeAccent(), this.themeCustom(), this.redDotEnabled(), this.hideChatIcon(), this.animationEnabled(),
            this.systemChatAsBubble(), this.antiSpam,
            this.chatHistoryEnabled(), this.historyRetentionDays(), this.timeSeparatorMinutes(),
            this.panelWidth(), this.bubbleCornerRadius(), this.ownBubbleColor(), this.otherBubbleColor(), this.ownTextColor(), this.otherTextColor(),
            this.soundPublic(), this.soundSystem(), this.soundWhisper(), this.debugLog(), this.preserveInput(), this.colorCodes(), this.sidebarHidePatterns(), this.blockedPlayers(), this.quickChatPhrases(),
            this.mentionBannerEnabled(), this.systemBannerEnabled(), this.mentionBannerDuration(), this.mentionSoundEnabled(), this.mentionRequireAt(), this.mentionWhisperBanner(),
            this.blurEnabled(), this.panelOpacity(), this.soundVolume(), this.ownMentionNotify(), this.ownQuoteNotify(), this.ownWhisperNotify(), this.bannerCornerRadius(), this.bannerOffsetX(), this.bannerOffsetY(), this.bannerMaxStack(),
            this.panelAnimStyle(), this.bannerAnimStyle(), this.popupAnimStyle(), this.messageAnimStyle(), this.imageRenderEnabled(), this.receiveImages(),
            this.uploadUrl(), this.uploadField(), this.uploadExtra(), this.uploadResponse(), this.messageGap(), this.avatarSize(), this.hideRepeatedAvatars(), this.closeChatOnSend(), this.bannerOpacity(), this.bubbleSize(), enabled, this.publicOverlayEnabled(),
            this.publicOverlayIdleSeconds(),
            this.dndWhisperPlayers(),
            this.channelDietEnabled());
    }

    /** 私聊免打扰名单整体替换（null → 空表）。名单里存玩家名，逐个私聊独立生效。 */
    public ChatBubbleConfig withDndWhisperPlayers(List<String> players) {
        return new ChatBubbleConfig(this.enabled(), this.theme(), this.themeAccent(), this.themeCustom(), this.redDotEnabled(), this.hideChatIcon(), this.animationEnabled(),
            this.systemChatAsBubble(), this.antiSpam,
            this.chatHistoryEnabled(), this.historyRetentionDays(), this.timeSeparatorMinutes(),
            this.panelWidth(), this.bubbleCornerRadius(), this.ownBubbleColor(), this.otherBubbleColor(), this.ownTextColor(), this.otherTextColor(),
            this.soundPublic(), this.soundSystem(), this.soundWhisper(), this.debugLog(), this.preserveInput(), this.colorCodes(), this.sidebarHidePatterns(), this.blockedPlayers(), this.quickChatPhrases(),
            this.mentionBannerEnabled(), this.systemBannerEnabled(), this.mentionBannerDuration(), this.mentionSoundEnabled(), this.mentionRequireAt(), this.mentionWhisperBanner(),
            this.blurEnabled(), this.panelOpacity(), this.soundVolume(), this.ownMentionNotify(), this.ownQuoteNotify(), this.ownWhisperNotify(), this.bannerCornerRadius(), this.bannerOffsetX(), this.bannerOffsetY(), this.bannerMaxStack(),
            this.panelAnimStyle(), this.bannerAnimStyle(), this.popupAnimStyle(), this.messageAnimStyle(), this.imageRenderEnabled(), this.receiveImages(),
            this.uploadUrl(), this.uploadField(), this.uploadExtra(), this.uploadResponse(), this.messageGap(), this.avatarSize(), this.hideRepeatedAvatars(), this.closeChatOnSend(), this.bannerOpacity(), this.bubbleSize(), this.dndEnabled(), this.publicOverlayEnabled(),
            this.publicOverlayIdleSeconds(),
            normalizeWhisperDnd(players),
            this.channelDietEnabled());
    }

    /** 通道节食开关（实验性，默认关闭）。关闭时 PLAY 阶段的通道声明与旧版完全一致。
     *  即使置 true，也只在「服务端 brand 已知且不含 fabric」时才真正节食。 */
    public ChatBubbleConfig withChannelDietEnabled(boolean enabled) {
        return new ChatBubbleConfig(this.enabled(), this.theme(), this.themeAccent(), this.themeCustom(), this.redDotEnabled(), this.hideChatIcon(), this.animationEnabled(),
            this.systemChatAsBubble(), this.antiSpam,
            this.chatHistoryEnabled(), this.historyRetentionDays(), this.timeSeparatorMinutes(),
            this.panelWidth(), this.bubbleCornerRadius(), this.ownBubbleColor(), this.otherBubbleColor(), this.ownTextColor(), this.otherTextColor(),
            this.soundPublic(), this.soundSystem(), this.soundWhisper(), this.debugLog(), this.preserveInput(), this.colorCodes(), this.sidebarHidePatterns(), this.blockedPlayers(), this.quickChatPhrases(),
            this.mentionBannerEnabled(), this.systemBannerEnabled(), this.mentionBannerDuration(), this.mentionSoundEnabled(), this.mentionRequireAt(), this.mentionWhisperBanner(),
            this.blurEnabled(), this.panelOpacity(), this.soundVolume(), this.ownMentionNotify(), this.ownQuoteNotify(), this.ownWhisperNotify(), this.bannerCornerRadius(), this.bannerOffsetX(), this.bannerOffsetY(), this.bannerMaxStack(),
            this.panelAnimStyle(), this.bannerAnimStyle(), this.popupAnimStyle(), this.messageAnimStyle(), this.imageRenderEnabled(), this.receiveImages(),
            this.uploadUrl(), this.uploadField(), this.uploadExtra(), this.uploadResponse(), this.messageGap(), this.avatarSize(), this.hideRepeatedAvatars(), this.closeChatOnSend(), this.bannerOpacity(), this.bubbleSize(), this.dndEnabled(), this.publicOverlayEnabled(),
            this.publicOverlayIdleSeconds(),
            this.dndWhisperPlayers(),
            enabled);
    }

    /** 开关「某个私聊对象」的免打扰（同名去重、大小写不敏感）。
     *  enable=true 时把名字加进名单，false 时移除；名字为空/空白时原样返回。 */
    public ChatBubbleConfig withWhisperDnd(String playerName, boolean enable) {
        String name = normalizePlayerName(playerName);
        if (name.isEmpty()) return this;
        List<String> next = new java.util.ArrayList<>();
        if (dndWhisperPlayers != null) {
            for (String n : dndWhisperPlayers) {
                String norm = normalizePlayerName(n);
                if (norm.isEmpty() || norm.equalsIgnoreCase(name)) continue;
                next.add(norm);
            }
        }
        if (enable) next.add(name);
        return withDndWhisperPlayers(next);
    }

    /** 该私聊对象是否被单独设为免打扰（名单为空 / 名字为空 → false）。
     *  比对时剥掉 § 色码并忽略大小写：名字来源既有 TAB 列表的原始名，
     *  也有聊天行里带前后缀的装饰名，大小写/色码不同不该让开关"失灵"。 */
    public boolean isWhisperDnd(String playerName) {
        if (dndWhisperPlayers == null || dndWhisperPlayers.isEmpty()) return false;
        String name = normalizePlayerName(playerName);
        if (name.isEmpty()) return false;
        for (String n : dndWhisperPlayers) {
            if (normalizePlayerName(n).equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static String normalizePlayerName(String name) {
        if (name == null) return "";
        // 玩家列表每帧每行都要查一次，绝大多数名字里没有 § 色码：
        // 先判一下再走正则，避免每帧成千上万次 Pattern 编译。
        String s = name.indexOf('§') >= 0 ? name.replaceAll("§.", "") : name;
        return s.trim();
    }

    /** 名单清洗：剥色码、去空白、去空项、忽略大小写去重（保留首次出现的写法）。 */
    private static List<String> normalizeWhisperDnd(List<String> players) {
        if (players == null || players.isEmpty()) return List.of();
        List<String> out = new java.util.ArrayList<>(players.size());
        for (String n : players) {
            String name = normalizePlayerName(n);
            if (name.isEmpty()) continue;
            boolean dup = false;
            for (String existing : out) {
                if (existing.equalsIgnoreCase(name)) { dup = true; break; }
            }
            if (!dup) out.add(name);
        }
        return List.copyOf(out);
    }

    public boolean isSidebarHidden(String playerName) {
        if (sidebarHidePatterns == null || sidebarHidePatterns.isEmpty()) return false;
        String lowerName = playerName.toLowerCase();
        for (String pattern : sidebarHidePatterns) {
            if (pattern == null || pattern.isEmpty()) continue;
            String regex = "^" + pattern.toLowerCase()
                .replace("*", ".*")
                .replace("?", ".") + "$";
            if (lowerName.matches(regex)) return true;
        }
        return false;
    }
}
