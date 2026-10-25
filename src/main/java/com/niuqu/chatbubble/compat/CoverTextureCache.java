package com.niuqu.chatbubble.compat;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.niuqu.chatbubble.image.ImageLoader;
import com.niuqu.chatbubble.image.RasterImageDecoder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 音乐岛封面缓存：URL → 下载 → 解码 → 纹理。
 *
 * <p>线程纪律（硬约束）：
 * <ul>
 *   <li>下载/解码只在 {@link ImageLoader#executor()} 的工作线程上；</li>
 *   <li>纹理注册/释放只在渲染线程（{@link #get(String)} 由渲染代码调用，
 *       它在渲染线程发现 pending 完成后才 register）；</li>
 *   <li>HUD 渲染路径绝不做阻塞 IO —— {@code get()} 只是「读缓存 / 提交任务 / 取 pending」。</li>
 * </ul>
 *
 * <p>缓存策略：按封面 URL 做 LRU，最多 {@value #CAP} 张；逐出时 release 纹理 +
 * 关闭 NativeImage。失败（DNS/404/解码不出）记 2 分钟冷却，避免每帧重试打爆网络。
 * 任何异常只 debug，返回 {@code null} 让调用方画占位块。
 *
 * <p>MC 26.3 适配（与 1.21.11 分支的差异，全部按该分支已有写法）：
 * <ul>
 *   <li>{@code MinecraftClient} → {@code Minecraft}；</li>
 *   <li>{@code net.minecraft.client.texture.NativeImage} →
 *       {@code com.mojang.blaze3d.platform.NativeImage}（26.x 已挪到 blaze3d）；
 *       方法名不变：{@code getWidth/getHeight/resizeSubRectTo/close}，{@code Format.RGBA} 仍存在；</li>
 *   <li>{@code NativeImageBackedTexture(Supplier&lt;String&gt;, NativeImage)} →
 *       {@code DynamicTexture(Supplier&lt;String&gt;, NativeImage)}（前者在 26.x 已不存在）；</li>
 *   <li>{@code TextureManager.registerTexture/destroyTexture} →
 *       {@code TextureManager.register/release}（26.x 只有 register/release，
 *       release 内部走 safeClose，会 close 掉 DynamicTexture 并释放其 NativeImage）；</li>
 *   <li>{@code Identifier.of(ns, path)} → {@code Identifier.fromNamespaceAndPath(ns, path)}。</li>
 * </ul>
 */
public final class CoverTextureCache {

    private CoverTextureCache() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** LRU 上限（同时常驻的封面纹理数）。 */
    public static final int CAP = 24;
    /** 单张封面下载的字节上限（128×128 JPEG 远小于这个值）。 */
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    /** 上传纹理前的最大边长：网易云封面按 ?param=128y128 给 128px，再压到 64 省显存。 */
    private static final int MAX_TEX_SIDE = 64;
    private static final long REQUEST_TIMEOUT_SECONDS = 20L;
    /** 失败后的重试冷却。 */
    private static final long RETRY_MS = 120_000L;
    private static final String USER_AGENT = "Mozilla/5.0";

    /** 已注册到 TextureManager 的封面（**只在渲染线程读写**）。 */
    private static final LinkedHashMap<String, Cover> READY = new LinkedHashMap<>(CAP, 0.75f, true);
    /** 工作线程解码完、等渲染线程注册的图（工作线程 put / 渲染线程 remove）。 */
    private static final Map<String, NativeImage> PENDING = new ConcurrentHashMap<>();
    /** 正在下载的 key（防重复提交）。 */
    private static final Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();
    /** 失败 key → 失败时刻。 */
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();

    private record Cover(Identifier id, NativeImage image) {}

    public static void clearAll() {
        for (Cover c : READY.values()) destroy(c);
        READY.clear();
        for (NativeImage img : PENDING.values()) closeQuietly(img);
        PENDING.clear();
        INFLIGHT.clear();
        FAILED.clear();
    }

    /**
     * 取封面纹理：命中缓存直接返回；有解码完成的结果就当场注册并返回；
     * 都没有就提交一次下载并返回 {@code null}（调用方画占位块）。
     *
     * <p>**必须在渲染线程调用**（内部会 register）。
     */
    public static Identifier get(String coverUrl) {
        if (coverUrl == null || coverUrl.isBlank()) return null;
        String key = keyOf(coverUrl);
        synchronized (READY) {
            Cover hit = READY.get(key);
            if (hit != null) return hit.id();
        }

        NativeImage decoded = PENDING.remove(key);
        if (decoded != null) {
            try {
                return register(key, decoded);
            } catch (Throwable t) {
                closeQuietly(decoded);
                fail(key, "register " + t);
                return null;
            }
        }

        long now = System.currentTimeMillis();
        Long failedAt = FAILED.get(key);
        if (failedAt != null) {
            if (now - failedAt < RETRY_MS) return null;
            FAILED.remove(key, failedAt);
        }
        if (INFLIGHT.add(key)) {
            try {
                ImageLoader.executor().execute(() -> download(key, coverUrl));
            } catch (Throwable t) {
                INFLIGHT.remove(key);
                LOGGER.debug("[e33chat] cover queue failed {}: {}", coverUrl, String.valueOf(t));
            }
        }
        return null;
    }

    private static Identifier register(String key, NativeImage image) {
        try {
            TextureManager tm = Minecraft.getInstance().getTextureManager();
            Identifier id = Identifier.fromNamespaceAndPath("e33chat", "cover/" + key);
            // 同 URL 重新注册（被逐出后又下回来）时先把旧的关掉，避免泄漏 GpuTexture
            tm.release(id);
            synchronized (READY) {
                Cover previous = READY.remove(key);
                if (previous != null) closeQuietly(previous.image());
            }
            tm.register(id, new DynamicTexture(() -> "e33chat_cover", image));
            synchronized (READY) {
                READY.put(key, new Cover(id, image));
                evictLocked();
            }
            return id;
        } catch (Throwable t) {
            closeQuietly(image);
            // 注册失败也进冷却，否则「下载→注册失败→再下载」会每帧重复提交请求
            fail(key, "upload " + t);
            return null;
        }
    }

    /** 逐出最久未用的；调用方必须持有 READY 锁。 */
    private static void evictLocked() {
        Iterator<Map.Entry<String, Cover>> it = READY.entrySet().iterator();
        while (READY.size() > CAP && it.hasNext()) {
            Cover c = it.next().getValue();
            it.remove();
            destroy(c);
        }
    }

    private static void download(String key, String coverUrl) {
        long t0 = System.currentTimeMillis();
        try {
            String url = coverUrl + (coverUrl.indexOf('?') >= 0 ? "&param=128y128" : "?param=128y128");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("User-Agent", USER_AGENT)
                .build();
            HttpResponse<byte[]> response = ImageLoader.client().send(
                request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                fail(key, "http " + response.statusCode());
                return;
            }
            byte[] body = response.body();
            if (body == null || body.length == 0 || body.length > MAX_BYTES) {
                fail(key, "bad body " + (body == null ? 0 : body.length));
                return;
            }
            RasterImageDecoder.DecodedImage decoded = RasterImageDecoder.decode(body);
            if (decoded == null) {
                fail(key, "decode failed");
                return;
            }
            NativeImage image = downscale(decoded);
            if (image == null) {
                fail(key, "downscale failed");
                return;
            }
            FAILED.remove(key);
            LOGGER.debug("[e33chat] cover {} -> {}x{} ({} bytes, {}ms)",
                key, image.getWidth(), image.getHeight(), body.length, System.currentTimeMillis() - t0);
            if (PENDING.size() < CAP * 2) {
                NativeImage stale = PENDING.put(key, image);
                if (stale != null) closeQuietly(stale);
            } else {
                // 渲染线程长时间没取（例如玩家一直停在会遮住 HUD 的界面），宁可不留也不堆积显存
                closeQuietly(image);
            }
        } catch (Throwable t) {
            fail(key, String.valueOf(t));
        } finally {
            INFLIGHT.remove(key);
        }
    }

    /** 把大图压到 {@value #MAX_TEX_SIDE} 见方以内（封面只画 24px，没必要吃整张大图的显存）。 */
    private static NativeImage downscale(RasterImageDecoder.DecodedImage decoded) {
        int w = decoded.width();
        int h = decoded.height();
        if (w <= 0 || h <= 0) {
            closeQuietly(decoded.image());
            return null;
        }
        if (w <= MAX_TEX_SIDE && h <= MAX_TEX_SIDE) return decoded.image();
        double scale = Math.min((double) MAX_TEX_SIDE / w, (double) MAX_TEX_SIDE / h);
        int tw = Math.max(1, (int) (w * scale));
        int th = Math.max(1, (int) (h * scale));
        NativeImage scaled = null;
        try {
            scaled = new NativeImage(NativeImage.Format.RGBA, tw, th, false);
            decoded.image().resizeSubRectTo(0, 0, w, h, scaled);
            closeQuietly(decoded.image());
            NativeImage out = scaled;
            scaled = null;   // 所有权转移给调用方，finally 不再关它
            return out;
        } catch (Throwable t) {
            LOGGER.debug("[e33chat] cover resize failed: {}", String.valueOf(t));
            // 缩放失败就把原图交上去（仍然能画，只是占显存）
            return decoded.image();
        } finally {
            closeQuietly(scaled);   // 只有失败/异常路径会走到这里
        }
    }

    private static void fail(String key, String reason) {
        FAILED.put(key, System.currentTimeMillis());
        LOGGER.debug("[e33chat] cover failed {}: {}", key, reason);
    }

    private static void destroy(Cover cover) {
        try {
            Minecraft.getInstance().getTextureManager().release(cover.id());
        } catch (Throwable t) {
            LOGGER.debug("[e33chat] cover destroy failed {}: {}", cover.id(), String.valueOf(t));
        }
        // release 内部会 safeClose（DynamicTexture.close() 再 close 掉 image），
        // 这里再关一次是幂等的（NativeImage.close() 有 isClosed 保护），
        // 但若 release 因「纹理未找到」提前返回，这句才是真正的释放。
        closeQuietly(cover.image());
    }

    private static void closeQuietly(NativeImage image) {
        if (image == null) return;
        try {
            image.close();
        } catch (Throwable ignored) {
        }
    }

    private static String keyOf(String url) {
        return Integer.toHexString(url.hashCode());
    }
}
