package com.niuqu.chatbubble.channeldiet;

import java.util.Locale;

/**
 * 「通道节食」的**纯文本**规则：命名空间归一、jar 文件名去版本号、中文判定。
 *
 * <p>刻意不依赖 Minecraft / Fabric 的任何一个类，因此可以脱离游戏单独编译验证
 * （{@code javac ChannelDietNaming.java} + 一段 main 即可跑）。
 *
 * <p>为什么需要「jar 文件名去版本号」：整合包常把 mod jar 改名成带中文前缀的名字
 * （例如 {@code 【聊天栏美化】e33chat-Fabric-1.21.11-3.0.1.jar}），而
 * {@code fabric.mod.json} 里的 {@code name} 往往是英文（{@code E33Chat}）。用户要的是
 * 「中文优先」的显示名，所以中文只存在于 jar 文件名时就得从文件名里剥出干净的模组名。
 */
public final class ChannelDietNaming {
    private ChannelDietNaming() {}

    /**
     * 平台 / 加载器 / 侧别后缀：从末尾剥掉它们。
     * 例：{@code e33chat-Fabric} → {@code e33chat}、{@code sodium-fabric} → {@code sodium}。
     */
    private static final String[] PLATFORM_TOKENS = {
        "fabric", "quilt", "forge", "neoforge", "neoforged", "mojmap",
        "minecraft", "mc", "common", "client", "server"
    };

    /**
     * 命名空间 / mod id 的归一形式：忽略大小写、连字符、下划线、空格与点。
     *
     * <p>mod id 在生态里两种写法混用（{@code xaero-minimap} / {@code xaero_minimap}），
     * 而 {@code Identifier} 的命名空间只允许小写字母/数字/下划线/连字符/点，直接字符串
     * 比较会漏掉一大批 mod。归一后再比，能显著提高「命名空间 → 模组」的命中率。
     */
    public static String normalizeId(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '-' || ch == '_' || ch == ' ' || ch == '.') continue;
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    /** 是否含中日韩表意文字 / 假名（用于判断「模组名是不是中文」）。 */
    public static boolean hasCjk(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '\u4E00' && ch <= '\u9FFF') return true;   // CJK 统一表意
            if (ch >= '\u3400' && ch <= '\u4DBF') return true;   // 扩展 A
            if (ch >= '\u3040' && ch <= '\u30FF') return true;   // 平假名 / 片假名
        }
        return false;
    }

    /** 去掉可执行包扩展名（.jar/.JAR/.zip/.disabled 之后的部分不动）。 */
    public static String stripExtension(String fileName) {
        if (fileName == null) return "";
        String s = fileName.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar")) return s.substring(0, s.length() - 4);
        if (lower.endsWith(".zip")) return s.substring(0, s.length() - 4);
        return s;
    }

    /**
     * 从文件名（已去扩展名）末尾反复剥掉版本号与平台后缀。
     *
     * <p>规则：每次看最后一个 {@code -} / {@code _} 之后的片段；
     * 若它「像版本」（{@code 3.0.1}、{@code 1.21.11}、{@code v1.2}、{@code 0.6.13+mc1.21.11}）
     * 或是平台词（{@code Fabric} / {@code mc} / {@code client} …），就砍掉它继续；
     * 直到遇到不像版本的部分为止。
     *
     * <pre>
     * 【聊天栏美化】e33chat-Fabric-1.21.11-3.0.1 → 【聊天栏美化】e33chat
     * sodium-fabric-0.6.13+mc1.21.11             → sodium
     * fabric-api-0.141.5+1.21.11                 → fabric-api
     * modmenu-17.0.0-beta.2                      → modmenu-17.0.0   （beta.2 不像版本）
     * </pre>
     */
    public static String stripVersionSuffix(String stem) {
        if (stem == null) return "";
        String s = stem.trim();
        while (true) {
            int cut = lastSeparator(s);
            if (cut <= 0) break;                        // 没有分隔符 / 分隔符在开头：不再剥
            String tail = s.substring(cut + 1);
            if (!looksLikeVersion(tail) && !isPlatformToken(tail)) break;
            s = s.substring(0, cut);
        }
        return trimTrailingSeparators(s);
    }

    /** 最后一个 '-' 或 '_' 的下标（找不到 → -1）。 */
    private static int lastSeparator(String s) {
        for (int i = s.length() - 1; i > 0; i--) {
            char ch = s.charAt(i);
            if (ch == '-' || ch == '_') return i;
        }
        return -1;
    }

    /** 像版本号：可选 v/V 前缀 + 数字开头（覆盖 3.0.1 / 1.21.11 / v1.2 / 0.6.13+mc1.21.11）。 */
    private static boolean looksLikeVersion(String token) {
        if (token == null || token.isEmpty()) return false;
        int i = 0;
        char first = token.charAt(0);
        if (first == 'v' || first == 'V') i = 1;
        if (i >= token.length()) return false;
        return Character.isDigit(token.charAt(i));
    }

    private static boolean isPlatformToken(String token) {
        for (String t : PLATFORM_TOKENS) {
            if (t.equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    private static String trimTrailingSeparators(String s) {
        int end = s.length();
        while (end > 0) {
            char ch = s.charAt(end - 1);
            if (ch == '-' || ch == '_' || ch == ' ' || ch == '.') { end--; continue; }
            break;
        }
        return s.substring(0, end);
    }
}
