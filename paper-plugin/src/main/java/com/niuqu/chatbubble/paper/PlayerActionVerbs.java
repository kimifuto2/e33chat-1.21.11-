package com.niuqu.chatbubble.paper;

/**
 * 玩家互动动作（拍了拍等）的词条表 —— **动作词条是消息协议的一部分**，因此这里用固定的中文
 * 词条（协议只传 {@code verbId}，服务端按本表生成权威文本），不随客户端语言变化。
 *
 * <p>与 Fabric 端 {@code com.niuqu.chatbubble.chat.action.PlayerActionVerbs} 逐项同值：
 * <b>不要改 {@link #IDS} 的顺序或内容</b>，客户端按同一张表把 id 翻成文本；新增动作只能在末尾追加。
 *
 * <p>服务端的职责边界（与 Fabric {@code PlayerActionRelay} 一致）：
 * <ul>
 *   <li>已知 {@code verbId} 一律用 {@link #TEXTS} 里的固定文本，**忽略客户端送来的 customText**
 *       —— 否则任何人都能借动作消息广播任意词条。</li>
 *   <li>{@code verbId=custom} 才用自定义词，且只保留可打印字符、按字符数夹断。</li>
 *   <li>正文/目标名同样清洗 + 夹断（正文 256 字符、目标名 32 字符）。</li>
 * </ul>
 */
final class PlayerActionVerbs {

    private PlayerActionVerbs() {
    }

    /** 8 个内置动作的 id（协议字段）。新增动作只在**末尾**追加，别改已有值。 */
    static final String[] IDS = {
        "pat", "poke", "pinch", "kick", "rub", "spray", "hug", "blow"
    };

    /** 与 {@link #IDS} 一一对应的中文词条（也是消息里显示的权威文本）。 */
    static final String[] TEXTS = {
        "拍了拍", "戳了戳", "捏了捏", "踢了踢", "揉了揉", "喷了喷", "抱了抱", "吹了吹"
    };

    /** 自定义词条的 verbId。 */
    static final String CUSTOM_ID = "custom";
    /** 自定义词条最大字符数（一次性输入、不保存）。 */
    static final int MAX_CUSTOM_LEN = 12;
    /** 动作消息正文最大字符数（与原版聊天上限一致）。 */
    static final int MAX_CONTENT = 256;
    /** 目标玩家名最大字符数（离线目标只能靠客户端带上来，服务端按此夹断）。 */
    static final int MAX_TARGET_NAME = 32;

    /** 是否是内置动作 id（「自定义」不算）。 */
    static boolean isKnown(String verbId) {
        return indexOf(verbId) >= 0;
    }

    private static int indexOf(String verbId) {
        if (verbId == null) {
            return -1;
        }
        String v = verbId.trim();
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(v)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 权威词条：已知 id → 表里的固定文本（忽略客户端的 customText，防伪造）；
     * {@link #CUSTOM_ID} → 清洗后的自定义词；其余 → {@code null}（无效载荷）。
     */
    static String resolveVerb(String verbId, String customText) {
        String id = verbId == null ? "" : verbId.trim();
        if (CUSTOM_ID.equals(id)) {
            String custom = sanitizeVerb(customText);
            return custom.isEmpty() ? null : custom;
        }
        int index = indexOf(id);
        return index < 0 ? null : TEXTS[index];
    }

    /** 自定义词条清洗：去掉 § 颜色码与控制字符，按字符数夹到 {@link #MAX_CUSTOM_LEN}。 */
    static String sanitizeVerb(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < MAX_CUSTOM_LEN; i++) {
            char ch = raw.charAt(i);
            if (ch == '\u00a7') { // 「§」+ 一个字符 = 原版颜色码，整对丢掉
                i++;
                continue;
            }
            if (ch < 0x20 || ch == 0x7f) {
                continue;
            }
            sb.append(ch);
        }
        return sb.toString().trim();
    }

    /** 正文清洗：只做长度夹断与控制字符过滤，保留 & 颜色码/图片括号码（原样广播）。 */
    static String sanitizeContent(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < MAX_CONTENT; i++) {
            char ch = raw.charAt(i);
            if (ch < 0x20 && ch != '\n') {
                continue;
            }
            if (ch == 0x7f) {
                continue;
            }
            sb.append(ch);
        }
        return sb.toString().trim();
    }

    /** 目标玩家名清洗：去颜色码/控制字符，按字符数夹断。 */
    static String sanitizeTargetName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < MAX_TARGET_NAME; i++) {
            char ch = raw.charAt(i);
            if (ch == '\u00a7') {
                i++;
                continue;
            }
            if (ch < 0x20 || ch == 0x7f) {
                continue;
            }
            sb.append(ch);
        }
        return sb.toString().trim();
    }
}
