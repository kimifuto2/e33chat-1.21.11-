package com.niuqu.chatbubble.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.niuqu.chatbubble.channeldiet.ChannelDietFilter;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private ConfigManager() {}

    public static ChatBubbleConfig load(Path path) {
        if (Files.exists(path)) {
            try (Reader r = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
                ChatBubbleConfig loaded = GSON.fromJson(r, ChatBubbleConfig.class);
                if (loaded != null) {
                    var merged = mergeWithDefaults(loaded);
                    LoggerFactory.getLogger("e33chat").info("[e33chat] Loaded config | soundPublic=" + merged.soundPublic() + " | soundSystem=" + merged.soundSystem());
                    return merged;
                }
            } catch (Exception e) {
                LoggerFactory.getLogger("e33chat").warn("[e33chat] Failed to load config, using defaults", e);
                // Keep the corrupt file for manual recovery instead of overwriting it.
                try {
                    Files.move(path, path.resolveSibling(path.getFileName() + ".bak"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {}
            }
        }
        ChatBubbleConfig def = ChatBubbleConfig.defaults();
        save(path, def);
        LoggerFactory.getLogger("e33chat").info("[e33chat] Created default config | soundPublic=" + def.soundPublic() + " | soundSystem=" + def.soundSystem());
        return def;
    }

    private static ChatBubbleConfig mergeWithDefaults(ChatBubbleConfig c) {
        ChatBubbleConfig d = ChatBubbleConfig.defaults();
        // 通道节食：唯一事实来源是 channelDietEnabled。旧配置文件没有这个键 → null →
        // 回落默认 false（= 只统计不裁剪）。遗留键 channelDietMode（3.0.2 的三态模式）
        // 不再参与任何逻辑：**不拿它反推开关**（避免把用户设好的开关意外改掉），
        // 只原样透传，让老配置里的值不会被抹掉。
        boolean dietOn = Boolean.TRUE.equals(c.channelDietEnabled());
        return new ChatBubbleConfig(
            c.enabled(), c.theme() != null ? c.theme() : d.theme(),
            c.themeAccent() != null ? c.themeAccent() : d.themeAccent(),
            c.themeCustom() != null ? c.themeCustom() : d.themeCustom(),
            c.redDotEnabled(), c.hideChatIcon(), c.animationEnabled(),
            c.systemChatAsBubble(),
            c.antiSpam(), c.chatHistoryEnabled(),
            c.historyRetentionDays(), c.timeSeparatorMinutes(),
            c.panelWidth(), c.bubbleCornerRadius(),
            c.ownBubbleColor() != null ? c.ownBubbleColor() : d.ownBubbleColor(),
            c.otherBubbleColor() != null ? c.otherBubbleColor() : d.otherBubbleColor(),
            c.ownTextColor() != null ? c.ownTextColor() : d.ownTextColor(),
            c.otherTextColor() != null ? c.otherTextColor() : d.otherTextColor(),
            c.soundPublic(), c.soundSystem(), c.soundWhisper(),
            c.debugLog(), c.preserveInput(), c.colorCodes(),
            c.sidebarHidePatterns() != null ? c.sidebarHidePatterns() : d.sidebarHidePatterns(),
            c.blockedPlayers() != null ? c.blockedPlayers() : d.blockedPlayers(),
            c.quickChatPhrases() != null ? c.quickChatPhrases() : d.quickChatPhrases(),
            c.mentionBannerEnabled(),
            c.systemBannerEnabled(),
            c.mentionBannerDuration() != null ? c.mentionBannerDuration() : d.mentionBannerDuration(),
            c.mentionSoundEnabled(),
            c.mentionRequireAt(),
            c.mentionWhisperBanner(),
            c.blurEnabled(),
            c.panelOpacity() != null ? c.panelOpacity() : d.panelOpacity(),
            c.soundVolume() != null ? c.soundVolume() : d.soundVolume(),
            c.ownMentionNotify(), c.ownQuoteNotify(), c.ownWhisperNotify(),
            c.bannerCornerRadius() != null ? c.bannerCornerRadius() : d.bannerCornerRadius(),
            c.bannerOffsetX(), c.bannerOffsetY(),
            c.bannerMaxStack() != null ? c.bannerMaxStack() : d.bannerMaxStack(),
            c.panelAnimStyle() != null ? c.panelAnimStyle() : d.panelAnimStyle(),
            c.bannerAnimStyle() != null ? c.bannerAnimStyle() : d.bannerAnimStyle(),
            c.popupAnimStyle() != null ? c.popupAnimStyle() : d.popupAnimStyle(),
            c.messageAnimStyle() != null ? c.messageAnimStyle() : d.messageAnimStyle(),
            c.imageRenderEnabled() != null ? c.imageRenderEnabled() : d.imageRenderEnabled(),
            // receiveImages is the user-facing switch; legacy imageRenderEnabled
            // (2.3.10 early builds) migrates into it when the new key is absent.
            c.receiveImages() != null ? c.receiveImages()
                : (c.imageRenderEnabled() != null ? c.imageRenderEnabled() : d.receiveImages()),
            c.uploadUrl() != null ? c.uploadUrl() : d.uploadUrl(),
            c.uploadField() != null ? c.uploadField() : d.uploadField(),
            c.uploadExtra() != null ? c.uploadExtra() : d.uploadExtra(),
            c.uploadResponse() != null ? c.uploadResponse() : d.uploadResponse(),
            c.messageGap() != null ? c.messageGap() : d.messageGap(),
            c.avatarSize() != null ? c.avatarSize() : d.avatarSize(),
            c.hideRepeatedAvatars() != null ? c.hideRepeatedAvatars() : d.hideRepeatedAvatars(),
            c.closeChatOnSend(),
            c.bannerOpacity() != null ? c.bannerOpacity() : d.bannerOpacity(),
            c.bubbleSize() != null ? c.bubbleSize() : d.bubbleSize(),
            // 免打扰：旧配置文件没有这个键时回落默认（关闭），显式 true 才保留
            c.dndEnabled(),
            // 公屏消息小窗：旧文件缺键读成 null → 回落默认（开启）
            c.publicOverlayEnabled() != null ? c.publicOverlayEnabled() : d.publicOverlayEnabled(),
            // 公屏小窗静默秒数：旧文件缺键 → 回落默认 5 秒；手改成越界值也夹回 1~60
            clampIdleSeconds(c.publicOverlayIdleSeconds(), d.publicOverlayIdleSeconds()),
            // 私聊免打扰名单：旧配置文件没有这个键 → 读成 null，这里回落成空表（= 没有私聊被免打扰）
            c.dndWhisperPlayers() != null ? c.dndWhisperPlayers() : d.dndWhisperPlayers(),
            // 通道节食：旧配置文件没有这个键 → null → 回落默认（关闭，只统计不裁剪）
            dietOn,
            // 遗留模式键（不参与逻辑，原样透传）、上限（越界夹回 32~127，缺键回落 120）、
            // 被禁用的命名空间（缺键回落空表）
            c.channelDietMode(),
            ChannelDietFilter.clampLimit(
                c.channelDietLimit() != null ? c.channelDietLimit() : d.channelDietLimit()),
            ChannelDietFilter.normalizeNamespaces(
                c.channelDietDisabledNamespaces() != null
                    ? c.channelDietDisabledNamespaces() : d.channelDietDisabledNamespaces()),
            // DangoMusic 灵动岛联动：旧配置文件没有这个键 → null → 回落默认（开启）；
            // 用户显式写 false 才关闭（没装 DangoMusic 时该项本来就不产生影响）
            c.musicIslandEnabled() != null ? c.musicIslandEnabled() : d.musicIslandEnabled());
    }

    /** 公屏小窗静默秒数夹到声明的 1~60 秒范围（null → 默认值）。 */
    private static Integer clampIdleSeconds(Integer value, Integer fallback) {
        int def = fallback != null ? fallback : ChatBubbleConfig.PUBLIC_OVERLAY_IDLE_DEFAULT_SECONDS;
        if (value == null) return def;
        return Math.max(ChatBubbleConfig.PUBLIC_OVERLAY_IDLE_MIN_SECONDS,
            Math.min(ChatBubbleConfig.PUBLIC_OVERLAY_IDLE_MAX_SECONDS, value));
    }

    public static void save(Path path, ChatBubbleConfig config) {
        try {
            Files.createDirectories(path.getParent());
            // Write-then-move: a crash mid-write leaves the old file intact
            // instead of a truncated JSON that resets the config on next load.
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
                GSON.toJson(config, w);
            }
            try {
                Files.move(tmp, path,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LoggerFactory.getLogger("e33chat").warn("[e33chat] Failed to save config", e);
        }
    }
}
