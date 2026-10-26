package com.niuqu.chatbubble.config;

import com.google.gson.annotations.SerializedName;
import com.niuqu.chatbubble.channeldiet.ChannelDietFilter;
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
    // 通道节食（实验性）的**唯一总开关**（3.0.3 起没有三态模式了）：
    //   开 → 连接**非 Fabric 服务器**（Paper/原版等）时，按「通道节食设置」里关掉的模组
    //        + 上限裁剪 PLAY 阶段随 minecraft:register 发出的自定义通道声明，规避 Paper
    //        的「通道数量上限」踢人；
    //   关 → 只统计不裁剪（GUI 仍能看到数量和改动效果，发送时原样放行）。
    // 可空 Boolean 用来区分「旧配置文件没有这个键」（null → 默认 false，关闭）与
    // 「用户显式关闭」。收敛在 ConfigManager.mergeWithDefaults，
    // 运行期由 ChannelRegistrationDietMixin 唯一读取。
    Boolean channelDietEnabled,
    // 【遗留键，不参与任何逻辑】3.0.2 的三态模式（"off" / "countOnly" / "active"）。
    // 3.0.3 起删掉了三态交互，但 JSON 键保留：老配置读进来不会报错、也不会被抹掉，
    // 值原样透传（谁都不读它）。是否裁剪只看上面的 channelDietEnabled —— **不从旧值
    // 推导开关**，避免把用户已经设好的开关意外改掉。
    String channelDietMode,
    // 允许声明的通道上限（默认 120，合法 32~127）。Paper 的判定是严格 < 128，即最多 127 条，
    // 所以这里不提供 128 及以上的值；null → 默认 120，越界值由 mergeWithDefaults 夹回。
    Integer channelDietLimit,
    // GUI 里被勾掉（禁用）的命名空间：这些命名空间下的通道一条都不声明。
    // 存小写命名空间；e33chat 不允许出现在这里（自己永远保留）。null → 空表。
    List<String> channelDietDisabledNamespaces,
    // 灵动岛 × DangoMusic 联动：装了 DangoMusic 时，顶部胶囊显示曲名/艺术家/封面/进度，
    // 播放中常驻、暂停后按原版 150ms 淡出。可空 Boolean —— 旧配置文件缺键读成 null，
    // 由 ConfigManager.mergeWithDefaults 收敛成 true（缺键=true）；运行时读
    // {@link #musicIslandEnabledResolved()}。没装 DangoMusic 时该项没有任何效果。
    @SerializedName("music_island_enabled")
    Boolean musicIslandEnabled,
    // 群聊总开关（方案 A：群消息全部走服务端中继）。默认开启。
    // 关掉后：侧边栏搜索框右侧的「+」按钮变灰且点击只回一条提示，群聊会话行也不再显示；
    // 已经在群里的消息仍会照常落到历史（服务端该发还是会发），只是界面上不展示入口。
    // 可空 Boolean —— 旧配置文件缺键读成 null，由 ConfigManager.mergeWithDefaults 收敛成 true。
    @SerializedName("group_chat_enabled")
    Boolean groupChatEnabled
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
            // 通道节食默认关闭（关闭 = 只统计不裁剪，行为与旧版一致）
            false,
            // 遗留模式键（不参与逻辑，按历史默认值 off 写出）/ 上限 120 / 禁用名单空表
            ChannelDietFilter.MODE_OFF,
            ChannelDietFilter.LIMIT_DEFAULT,
            List.of(),
            // DangoMusic 联动默认开启（没装 DangoMusic 时该项无任何效果）
            true,
            // 群聊默认开启
            true
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
            channelDietEnabled,
            channelDietMode, channelDietLimit, channelDietDisabledNamespaces,
            this.musicIslandEnabled(), this.groupChatEnabled());
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
            channelDietEnabled,
            channelDietMode, channelDietLimit, channelDietDisabledNamespaces,
            this.musicIslandEnabled(), this.groupChatEnabled());
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
            channelDietEnabled,
            channelDietMode, channelDietLimit, channelDietDisabledNamespaces,
            this.musicIslandEnabled(), this.groupChatEnabled());
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
            channelDietEnabled,
            channelDietMode, channelDietLimit, channelDietDisabledNamespaces,
            this.musicIslandEnabled(), this.groupChatEnabled());
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
            channelDietEnabled,
            channelDietMode, channelDietLimit, channelDietDisabledNamespaces,
            this.musicIslandEnabled(), this.groupChatEnabled());
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
            this.channelDietEnabled(),
            this.channelDietMode(), this.channelDietLimit(), this.channelDietDisabledNamespaces(),
            this.musicIslandEnabled(), this.groupChatEnabled());
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
            this.channelDietEnabled(),
            this.channelDietMode(), this.channelDietLimit(), this.channelDietDisabledNamespaces(),
            this.musicIslandEnabled(), this.groupChatEnabled());
    }

    /** 通道节食总开关（实验性，默认关闭）。关闭 = 只统计不裁剪（发送时原样放行）；
     *  打开 = 连接非 Fabric 服务器时按「通道节食设置」的模组勾选 + 上限裁剪通道声明。
     *  即使置 true，也只在「服务端 brand 已知且不含 fabric」时才真正节食。
     *  遗留的 {@link #channelDietMode} 不参与判定，这里原样透传、绝不被这个开关改写。 */
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
            enabled,
            this.channelDietMode(), this.channelDietLimit(), this.channelDietDisabledNamespaces(),
            this.musicIslandEnabled(), this.groupChatEnabled());
    }

    /** 整体替换「被禁用的命名空间」名单（清洗规则见
     *  {@link ChannelDietFilter#normalizeNamespaces}：小写、去重、e33chat 永不出现在名单里）。 */
    public ChatBubbleConfig withChannelDietDisabledNamespaces(List<String> namespaces) {
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
            this.channelDietEnabled(),
            this.channelDietMode(), this.channelDietLimit(),
            ChannelDietFilter.normalizeNamespaces(namespaces),
            this.musicIslandEnabled(), this.groupChatEnabled());
    }

    /** 设置通道上限（夹到 32~127，Paper 的硬上限是 127 条）。 */
    public ChatBubbleConfig withChannelDietLimit(Integer limit) {
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
            this.channelDietEnabled(),
            this.channelDietMode(), ChannelDietFilter.clampLimit(limit), this.channelDietDisabledNamespaces(),
            this.musicIslandEnabled(), this.groupChatEnabled());
    }

    /** 生效的「灵动岛 × DangoMusic」开关（旧配置缺键 → null → 默认开启）。
     *  实际是否显示还取决于有没有装 DangoMusic、以及当前有没有曲目。 */
    public boolean musicIslandEnabledResolved() {
        return musicIslandEnabled == null || musicIslandEnabled;
    }

    /** 生效的群聊开关（旧配置缺键 → null → 默认开启）。
     *  服务端是否真的支持群聊还要另看 {@code ClientPlayNetworking.canSend(group_create)}。 */
    public boolean groupChatEnabledResolved() {
        return groupChatEnabled == null || groupChatEnabled;
    }

    /** 设置群聊开关（配置屏写入）。 */
    public ChatBubbleConfig withGroupChatEnabled(boolean enabled) {
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
            this.channelDietEnabled(),
            this.channelDietMode(), this.channelDietLimit(), this.channelDietDisabledNamespaces(),
            this.musicIslandEnabled(), enabled);
    }

    /** 生效的通道上限（32~127，默认 120）。 */
    public int channelDietLimitResolved() {
        return ChannelDietFilter.clampLimit(channelDietLimit);
    }

    /** 生效的禁用命名空间名单（永不为 null，已归一）。 */
    public List<String> channelDietDisabledResolved() {
        return channelDietDisabledNamespaces != null
            ? ChannelDietFilter.normalizeNamespaces(channelDietDisabledNamespaces) : List.of();
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
