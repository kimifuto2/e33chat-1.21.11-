package com.niuqu.chatbubble.chat.action;

/**
 * 玩家互动动作（拍了拍等）的词条表 —— **动作词条是消息协议的一部分**，因此这里用固定的
 * 中文词条（协议传 {@code verbId}，服务端按本表生成权威文本），不随客户端语言变化：
 * 同一条动作消息在所有客户端上必须显示成同一句话，否则「A 拍了拍 B 说：」会被不同语言拆开。
 *
 * <p>「自定义」不是本表里的词条：{@code verbId = }{@link #CUSTOM_ID}，词条文本由玩家
 * 一次性输入（不保存），服务端只做长度/字符清洗（见 {@link #resolveVerb(String, String)}）。
 *
 * <p>服务端与客户端共用本类：两边用**同一张表**把 id 翻成文本，客户端无法伪造词条
 * （已知 id 的自定义文本会被忽略），自定义词条也只保留可打印字符。
 */
public final class PlayerActionVerbs {
    private PlayerActionVerbs() {}

    /** 8 个内置动作的 id（协议字段）。新增动作只在**末尾**追加，别改已有值。 */
    public static final String[] IDS = {
        "pat", "poke", "pinch", "kick", "rub", "spray", "hug", "blow"
    };

    /** 与 {@link #IDS} 一一对应的中文词条（也是消息里显示的权威文本）。 */
    public static final String[] TEXTS = {
        "拍了拍", "戳了戳", "捏了捏", "踢了踢", "揉了揉", "喷了喷", "抱了抱", "吹了吹"
    };

    /** 自定义词条的 verbId。 */
    public static final String CUSTOM_ID = "custom";
    /** 自定义词条最大字符数（一次性输入、不保存；太长会把消息行撑爆）。 */
    public static final int MAX_CUSTOM_LEN = 12;
    /** 动作消息正文最大字符数（与原版聊天上限一致）。 */
    public static final int MAX_CONTENT = 256;
    /** 目标玩家名最大字符数（离线目标只能靠客户端带上来，服务端按此夹断）。 */
    public static final int MAX_TARGET_NAME = 32;

    public static int count() {
        return IDS.length;
    }

    public static String idAt(int index) {
        return index >= 0 && index < IDS.length ? IDS[index] : null;
    }

    public static String textAt(int index) {
        return index >= 0 && index < TEXTS.length ? TEXTS[index] : null;
    }

    /** 是否是内置动作 id（「自定义」不算）。 */
    public static boolean isKnown(String verbId) {
        return indexOf(verbId) >= 0;
    }

    /**
     * 是否是内置动作的**显示词条**（{@code 拍了拍}/{@code 戳了戳}/…）。
     *
     * <p>给「退化路径」用：服务端没有 {@code e33chat:player_action} 通道时动作消息只能走
     * 普通聊天，收端只有一句 {@code 拍了拍 B 说：正文} 的纯文本，必须靠词条白名单判断
     * 它到底是动作还是玩家随手打的一句话（只认这 8 个固定词条，自定义词条不参与 ——
     * 任意词条都能匹配的话，普通聊天会被大面积误判成动作）。
     */
    public static boolean isKnownText(String verbText) {
        if (verbText == null) return false;
        String v = verbText.trim();
        for (String text : TEXTS) {
            if (text.equals(v)) return true;
        }
        return false;
    }

    private static int indexOf(String verbId) {
        if (verbId == null) return -1;
        String v = verbId.trim();
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(v)) return i;
        }
        return -1;
    }

    /**
     * 权威词条：已知 id → 表里的固定文本（忽略客户端的 customText，防伪造）；
     * {@link #CUSTOM_ID} → 清洗后的自定义词；其余 → null（无效载荷）。
     */
    public static String resolveVerb(String verbId, String customText) {
        String id = verbId == null ? "" : verbId.trim();
        if (CUSTOM_ID.equals(id)) {
            String custom = sanitizeVerb(customText);
            return custom.isEmpty() ? null : custom;
        }
        int index = indexOf(id);
        return index < 0 ? null : TEXTS[index];
    }

    /** 自定义词条清洗：去掉 § 颜色码与控制字符，按字符数夹到 {@link #MAX_CUSTOM_LEN}。 */
    public static String sanitizeVerb(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < MAX_CUSTOM_LEN; i++) {
            char ch = raw.charAt(i);
            if (ch == '\u00a7') { // 「§」+ 一个字符 = 原版颜色码，整对丢掉
                i++;
                continue;
            }
            if (ch < 0x20 || ch == 0x7f) continue; // 控制字符 / 换行
            sb.append(ch);
        }
        return sb.toString().trim();
    }

    /** 正文清洗：只做长度夹断与控制字符过滤，保留 & 颜色码/图片括号码（原样广播）。 */
    public static String sanitizeContent(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < MAX_CONTENT; i++) {
            char ch = raw.charAt(i);
            if (ch < 0x20 && ch != '\n') continue;
            if (ch == 0x7f) continue;
            sb.append(ch);
        }
        return sb.toString().trim();
    }

    /** 目标玩家名清洗：去颜色码/控制字符，按字符数夹断。 */
    public static String sanitizeTargetName(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < MAX_TARGET_NAME; i++) {
            char ch = raw.charAt(i);
            if (ch == '\u00a7') {
                i++;
                continue;
            }
            if (ch < 0x20 || ch == 0x7f) continue;
            sb.append(ch);
        }
        return sb.toString().trim();
    }

    /** 动作消息的**正文段**：{@code 拍了拍 B 说：正文}（不含发送者名）。 */
    public static String composeBody(String verb, String target, String content) {
        return nz(verb) + " " + nz(target) + " 说：" + nz(content);
    }

    /** 动作消息的整行文本：{@code A 拍了拍 B 说：正文}（系统信息样式由消息的 isSystem 决定）。
     *  服务端不装本模组时的退化路径只发 {@link #composeBody} 那一段（气泡自己带发送者名）。 */
    public static String composeLine(String sender, String verb, String target, String content) {
        return nz(sender) + " " + composeBody(verb, target, content);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
