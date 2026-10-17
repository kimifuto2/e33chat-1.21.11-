package com.niuqu.chatbubble.render;

import com.mojang.authlib.GameProfile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Identifier;

/**
 * Player-head skin resolution with a merged UUID + name cache.
 *
 * <p><b>Never blocks the calling (render/main) thread.</b> A cache miss returns the
 * default head immediately and schedules an asynchronous lookup that fills the
 * cache for later frames.
 *
 * <p>This matters a lot on multiplayer: resolving a head through
 * {@code SkinProvider.supplySkinTextures(...)} performs an HTTP request to the
 * session servers, which can take seconds — or never finish — behind a slow or
 * filtered network. Calling {@code .get()} on it from the render thread stalled
 * the client hard enough that the server timed it out and reset the connection
 * ("Internal Exception: java.net.SocketException: Connection reset"), which
 * presented as "cannot join the server". Single player hid the bug because the
 * only head on screen is the local player's, which is already loaded.
 */
public final class SkinResolver {
    private SkinResolver() {}

    private static final int SKIN_CACHE_CAP = 256;
    private static final UUID NIL_UUID = new UUID(0, 0);

    /** How long a failed lookup is left alone before the next attempt (ms). */
    private static final long RETRY_FAILED_MS = 60_000L;

    // Access-order LRU. Wrapped so the asynchronous cache fill (which runs on the
    // SkinProvider's worker thread) cannot corrupt the map while the render
    // thread reads it — an unsynchronized access-order LinkedHashMap can spin
    // forever under concurrent get/put.
    private static final Map<UUID, Identifier> skinCache =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Identifier> eldest) {
                return size() > SKIN_CACHE_CAP;
            }
        });

    // Name-keyed skin cache: an offline player seen in chat history keeps the
    // real head when the UUID lookup fails (cracked servers, uuid dropped in
    // old history files). Key is the §-stripped lowercase name.
    private static final Map<String, Identifier> skinNameCache =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Identifier> eldest) {
                return size() > SKIN_CACHE_CAP;
            }
        });

    /** Lookups currently in flight, so a miss does not start one per frame. */
    private static final Set<String> pending = ConcurrentHashMap.newKeySet();
    /** Lookups that failed and their timestamp, to throttle retries. */
    private static final Map<String, Long> failedAt = new ConcurrentHashMap<>();

    /** Single worker for session-server lookups; keeps them off the render thread. */
    private static final java.util.concurrent.ExecutorService SKIN_EXEC =
        java.util.concurrent.Executors.newFixedThreadPool(1, r -> {
            Thread t = new Thread(r, "e33chat-skin");
            t.setDaemon(true);
            return t;
        });

    private static String skinNameKey(String name) {
        if (name == null) return null;
        String key = name.replaceAll("§.", "").trim().toLowerCase(java.util.Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    private static void rememberSkin(UUID uuid, String name, Identifier tex) {
        if (tex == null) return;
        if (uuid != null && !uuid.equals(NIL_UUID)) skinCache.put(uuid, tex);
        String key = skinNameKey(name);
        if (key != null) skinNameCache.put(key, tex);
    }

    /**
     * Head texture for a player. Always returns immediately; a miss yields the
     * default head while the real texture is fetched in the background.
     */
    public static Identifier getSkin(UUID uuid, String name) {
        MinecraftClient client = MinecraftClient.getInstance();
        // Online players: read PlayerListEntry fresh every frame — caching the first
        // result (default Steve/Alex while the async download is in progress) would
        // freeze the head forever even after the real skin loaded. CSL intercepts the
        // underlying lookup. This path never touches the network.
        if (client.getNetworkHandler() != null && uuid != null && !uuid.equals(NIL_UUID)) {
            PlayerListEntry info = client.getNetworkHandler().getPlayerListEntry(uuid);
            if (info != null) {
                Identifier tex = info.getSkinTextures().body().texturePath();
                rememberSkin(uuid, name, tex);
                return tex;
            }
        }
        if (uuid != null && !uuid.equals(NIL_UUID)) {
            Identifier cached = skinCache.get(uuid);
            if (cached != null) return cached;
        }
        String nameKey = skinNameKey(name);
        if (nameKey != null) {
            Identifier cachedByName = skinNameCache.get(nameKey);
            if (cachedByName != null) return cachedByName;
        }
        // Miss: hand back the default head now, resolve in the background.
        requestSkin(uuid, name);
        return DefaultSkinHelper.getTexture();
    }

    /** Fire-and-forget lookup; never blocks and never throws at the caller. */
    private static void requestSkin(UUID uuid, String name) {
        if (name == null || name.isEmpty()) return;
        UUID id = (uuid != null && !uuid.equals(NIL_UUID)) ? uuid : NIL_UUID;
        String key = id + "|" + name.toLowerCase(java.util.Locale.ROOT);
        if (!pending.add(key)) return;   // already in flight
        Long lastFail = failedAt.get(key);
        if (lastFail != null && System.currentTimeMillis() - lastFail < RETRY_FAILED_MS) {
            pending.remove(key);
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            final UUID profileId = id;
            final String profileName = name;
            // supplySkinTextures(...) hands back a LAZY Supplier: calling get() on it
            // performs the (blocking) session-server lookup. Run the whole thing on a
            // worker thread so rendering never waits on it.
            CompletableFuture.supplyAsync(() -> {
                var supplier = client.getSkinProvider().supplySkinTextures(
                    new GameProfile(profileId, profileName), false);
                return supplier == null ? null : supplier.get();
            }, SKIN_EXEC).whenComplete((skin, error) -> {
                pending.remove(key);
                Identifier tex = null;
                if (skin != null && skin.body() != null) tex = skin.body().texturePath();
                if (tex != null) {
                    failedAt.remove(key);
                    rememberSkin(uuid, name, tex);
                } else {
                    failedAt.put(key, System.currentTimeMillis());
                }
            });
        } catch (Throwable t) {
            pending.remove(key);
            failedAt.put(key, System.currentTimeMillis());
        }
    }
}
