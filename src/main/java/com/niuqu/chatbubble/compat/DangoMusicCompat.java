package com.niuqu.chatbubble.compat;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DangoMusic 软兼容桥：只读「当前在放什么歌」（曲名/歌手/进度/时长/封面/状态）。
 *
 * <p>DangoMusic 是 GPL-3.0 + Kotlin + mojmap 的客户端模组，没有 maven 构件，
 * 因此**编译期零依赖**：全部走 {@code Class.forName} + {@code Method.invoke}，
 * 绝不 import 它的任何类。它自己的类名/成员名不在 remap 范围内（只有它内部
 * 引用的 MC 类被 remap），所以开发环境与正式 jar 行为一致。
 *
 * <p>反射契约（对 DangoMusic 1.4.1 + 1.21.11 fabric 用 javap 逐条核对过）：
 * <ul>
 *   <li>{@code me.sorihiko.dango.music.MusicPlayer} —— Kotlin object，
 *       静态字段 {@code INSTANCE}；实例方法 {@code getState()}（返回
 *       {@code MusicPlayer$State} 枚举：STOPPED/LOADING/PLAYING/PAUSED/ERROR）、
 *       {@code getCurrent()}、{@code getPositionMs()}(long)、
 *       {@code getDurationMs()}(long)、{@code getMessage()}(String)</li>
 *   <li>{@code me.sorihiko.dango.music.NeteaseTrack} —— {@code getName()}(String)、
 *       {@code getArtists()}(List&lt;String&gt;)、{@code getDurationMs()}(long)、
 *       {@code getCoverUrl()}(String)</li>
 * </ul>
 *
 * <p>**不读** DangoMusic 自己的灵动岛开关（{@code DynamicIslandModule}）：按用户决策，
 * 本模组的胶囊位置固定不动、不为 Dango 自带的岛让位（用户会自己在 Dango 设置里关掉它），
 * 所以这里索性连那条反射也删掉了——少一次 {@code Class.forName}，Dango 改版时
 * 不会因为找不到这个模块类而把整条联动一起拖挂。
 *
 * <p>没装 DangoMusic、版本结构不符或任何一步抛异常时，{@link #poll()} 一律返回
 * {@code null}（= 本模组行为完全不变），且失败只记一次日志。
 *
 * <p>性能：{@link Method}/{@link Field} 只解析一次并缓存；每帧 {@link #poll()}
 * 只有 5~7 次 {@code invoke}，不做 {@code Class.forName}。
 */
public final class DangoMusicCompat {

    private DangoMusicCompat() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String MOD_ID = "dangomusic";
    private static final String PLAYER_CLASS = "me.sorihiko.dango.music.MusicPlayer";
    private static final String TRACK_CLASS = "me.sorihiko.dango.music.NeteaseTrack";

    private static final String STATE_PLAYING = "PLAYING";
    private static final String STATE_LOADING = "LOADING";
    private static final String STATE_PAUSED = "PAUSED";

    /**
     * 一帧的「正在播放」快照。{@code state} 是枚举的 {@code name()}；
     * {@code durationMs} 已保证 &gt;= 0 且 {@code posMs} 已 clamp 到 {@code [0, durationMs]}。
     */
    public record NowPlaying(String title, List<String> artists, long posMs, long durationMs,
                             String coverUrl, String state, String message) {
        /** 播放中/加载中：这两种状态让音乐岛常驻。 */
        public boolean playing() {
            return STATE_PLAYING.equals(state) || STATE_LOADING.equals(state);
        }

        /** 暂停：还画，但不再续常驻计时（1.5s 后淡出）。 */
        public boolean paused() {
            return STATE_PAUSED.equals(state);
        }
    }

    private static boolean initDone;
    private static boolean available;
    private static boolean failureLogged;

    private static Object musicPlayer;
    private static Method mGetState;
    private static Method mGetCurrent;
    private static Method mGetPositionMs;
    private static Method mGetDurationMs;
    private static Method mGetMessage;

    private static Method tGetName;
    private static Method tGetArtists;
    private static Method tGetTrackDurationMs;
    private static Method tGetCoverUrl;

    private static synchronized void init() {
        if (initDone) return;
        initDone = true;
        try {
            if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return;
            // 版本白名单/取证：装了哪个版本记一条日志，方便对照反射契约是否匹配
            FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(c ->
                LOGGER.info("[e33chat] DangoMusic compat | mod={} | version={}",
                    c.getMetadata().getId(), c.getMetadata().getVersion().getFriendlyString()));

            Class<?> player = Class.forName(PLAYER_CLASS);
            Field instanceField = player.getField("INSTANCE");
            musicPlayer = instanceField.get(null);
            mGetState = player.getMethod("getState");
            mGetCurrent = player.getMethod("getCurrent");
            mGetPositionMs = player.getMethod("getPositionMs");
            mGetDurationMs = player.getMethod("getDurationMs");
            mGetMessage = player.getMethod("getMessage");

            Class<?> track = Class.forName(TRACK_CLASS);
            tGetName = track.getMethod("getName");
            tGetArtists = track.getMethod("getArtists");
            tGetTrackDurationMs = track.getMethod("getDurationMs");
            tGetCoverUrl = track.getMethod("getCoverUrl");

            available = true;
        } catch (Throwable t) {
            // 版本结构不符：整体降级为「没装」，绝不影响本模组的其它功能
            available = false;
            musicPlayer = null;
            logFailureOnce("init", t);
        }
    }

    /** 是否装了 DangoMusic 且反射契约可用。没装时调用方必须完全跳过（不建线程、不下载封面）。 */
    public static boolean isAvailable() {
        init();
        return available;
    }

    /**
     * 读当前播放状态；每帧调用安全。没装 / 无曲目 / 状态不在
     * PLAYING・PAUSED・LOADING / 任何反射异常 → {@code null}。
     */
    public static NowPlaying poll() {
        if (!isAvailable() || musicPlayer == null) return null;
        try {
            String state = stateName(mGetState.invoke(musicPlayer));
            if (!STATE_PLAYING.equals(state) && !STATE_PAUSED.equals(state) && !STATE_LOADING.equals(state)) {
                return null;
            }
            Object track = mGetCurrent.invoke(musicPlayer);
            if (track == null) return null;

            String title = asString(tGetName.invoke(track));
            if (title == null) title = "";
            List<String> artists = asStringList(tGetArtists.invoke(track));
            String cover = asString(tGetCoverUrl.invoke(track));
            String message = asString(mGetMessage.invoke(musicPlayer));

            long durationMs = asLong(tGetTrackDurationMs.invoke(track), 0L);
            if (durationMs <= 0L) durationMs = asLong(mGetDurationMs.invoke(musicPlayer), 0L);
            if (durationMs < 0L) durationMs = 0L;

            long posMs = asLong(mGetPositionMs.invoke(musicPlayer), 0L);
            if (posMs < 0L) posMs = 0L;
            if (durationMs > 0L && posMs > durationMs) posMs = durationMs;

            return new NowPlaying(title, artists, posMs, durationMs, cover, state, message);
        } catch (Throwable t) {
            logFailureOnce("poll", t);
            return null;
        }
    }

    private static String stateName(Object state) {
        if (state == null) return "";
        if (state instanceof Enum<?> e) return e.name();
        return String.valueOf(state);
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : (value == null ? null : String.valueOf(value));
    }

    private static long asLong(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o == null) continue;
            String s = o instanceof String str ? str : String.valueOf(o);
            if (!s.isEmpty()) out.add(s);
        }
        return out.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(out);
    }

    /** 失败只记一次，避免每帧刷日志（渲染/校验路径每帧都会调）。 */
    private static void logFailureOnce(String where, Throwable t) {
        if (failureLogged) return;
        failureLogged = true;
        LOGGER.info("[e33chat] DangoMusic compat disabled at {}: {}", where, String.valueOf(t));
    }
}
