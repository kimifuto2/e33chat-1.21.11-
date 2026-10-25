package com.niuqu.chatbubble.channeldiet;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「通道命名空间 → 模组显示名」解析（中文优先），带缓存。
 *
 * <p>解析顺序（每一级都可能失败，失败就落到下一级）：
 * <ol>
 *   <li>{@code minecraft} / {@code fabric} / {@code c} 这类系统命名空间给固定标签；</li>
 *   <li>在 {@code FabricLoader.getAllMods()} 里找 {@code metadata.id}（忽略大小写、连字符、
 *       下划线、点）等于该命名空间的模组：</li>
 *   <li>模组名含中文 → 直接用（绝大多数中文整合包会把 {@code name} 汉化）；</li>
 *   <li>模组名是英文 → 从 {@code getOrigin().getPaths()} 里取 jar 路径，文件名去掉
 *       {@code .jar} 再去掉版本号/平台后缀；**只有这个结果含中文时才用它**（整合包常见
 *       {@code 【聊天栏美化】e33chat-Fabric-1.21.11-3.0.1.jar} 这种改名），否则仍用
 *       {@code metadata.name}（{@code Fabric API} 比 {@code fabric-api} 好看）；</li>
 *   <li>都失败 → 命名空间本身。</li>
 * </ol>
 *
 * <p>缓存：{@code namespace → 显示名} 与 mod id 索引各缓存一份。GUI 每帧都会问
 * 「这一行长什么样」，绝不能每帧遍历 mod 列表 + 打磁盘判 {@code isRegularFile}。
 */
public final class ChannelDietNames {
    private ChannelDietNames() {}

    /** namespace（小写） → 显示名。 */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /** 归一 mod id → ModContainer。 */
    private static volatile Map<String, ModContainer> modIndex;

    /** 资源重载 / 语言切换后清空（语言文件变了，系统标签要跟着变）。 */
    public static void invalidate() {
        CACHE.clear();
    }

    /** 命名空间的显示名（永不返回 null / 空串）。 */
    public static String display(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return Text.translatable("e33chat.channeldiet.mod.unknown").getString();
        }
        String ns = namespace.toLowerCase(Locale.ROOT);
        String hit = CACHE.get(ns);
        if (hit != null) return hit;
        String name;
        try {
            name = resolve(ns);
        } catch (Throwable t) {
            name = namespace;   // 解析永远不许把 GUI 搞崩
        }
        if (name == null || name.isBlank()) name = namespace;
        CACHE.put(ns, name);
        return name;
    }

    /** 该命名空间能否映射到一个真实模组（mixin/内置命名空间映射不到 → 列表里靠后）。 */
    public static boolean isKnownMod(String namespace) {
        if (namespace == null || namespace.isEmpty()) return false;
        return findMod(namespace.toLowerCase(Locale.ROOT)) != null;
    }

    private static String resolve(String ns) {
        switch (ns) {
            case "minecraft": return Text.translatable("e33chat.channeldiet.mod.minecraft").getString();
            case "fabric": return Text.translatable("e33chat.channeldiet.mod.fabric").getString();
            case "c": return Text.translatable("e33chat.channeldiet.mod.c").getString();
            default: break;
        }
        ModContainer mod = findMod(ns);
        if (mod == null) return ns;
        String meta = safeModName(mod);
        String jarStem = jarStem(mod);
        if (ChannelDietNaming.hasCjk(meta)) return meta;
        if (ChannelDietNaming.hasCjk(jarStem)) return jarStem;
        if (meta != null && !meta.isBlank()) return meta;
        if (jarStem != null && !jarStem.isEmpty()) return jarStem;
        return ns;
    }

    private static String safeModName(ModContainer mod) {
        try {
            ModMetadata md = mod.getMetadata();
            return md != null ? md.getName() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从模组的来源路径里取 jar 文件名并剥掉版本号。
     * 开发环境（目录来源）没有 jar 文件名，`isRegularFile` 会挡掉。
     */
    private static String jarStem(ModContainer mod) {
        try {
            for (Path p : mod.getOrigin().getPaths()) {
                if (p == null || !Files.isRegularFile(p)) continue;
                Path fileName = p.getFileName();
                if (fileName == null) continue;
                String stem = ChannelDietNaming.stripVersionSuffix(
                    ChannelDietNaming.stripExtension(fileName.toString()));
                if (!stem.isEmpty()) return stem;
            }
        } catch (Throwable ignored) {
            // 来源不可读（权限/嵌套 jar 已被关掉）→ 交给上层回退
        }
        return null;
    }

    private static ModContainer findMod(String namespace) {
        Map<String, ModContainer> idx = modIndex;
        if (idx == null) {
            idx = buildIndex();
            modIndex = idx;
        }
        return idx.get(ChannelDietNaming.normalizeId(namespace));
    }

    private static Map<String, ModContainer> buildIndex() {
        Map<String, ModContainer> map = new HashMap<>();
        try {
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                ModMetadata md = mod.getMetadata();
                if (md == null) continue;
                String id = md.getId();
                if (id == null || id.isEmpty()) continue;
                String key = ChannelDietNaming.normalizeId(id);
                if (!key.isEmpty()) map.putIfAbsent(key, mod);
            }
        } catch (Throwable ignored) {
            // loader 不可用时返回空索引：GUI 仍能显示命名空间本身
        }
        return map;
    }
}
