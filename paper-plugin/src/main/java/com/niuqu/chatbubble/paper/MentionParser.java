package com.niuqu.chatbubble.paper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从聊天明文里解析 {@code @名字}，与在线玩家名做**大小写不敏感的最长匹配**。
 *
 * <p>「客户端到底吃什么格式」是照代码定的，不是猜的：
 * <ul>
 *   <li>客户端真正的高亮/提醒来自
 *       {@code com.niuqu.chatbubble.chat.MentionDetector#isMentioned(text, localPlayerName, ...)}
 *       —— 它拿**消息正文**（不是 meta）和本地玩家名扫：{@code '@'} 必须紧贴在名字左边，名字右边
 *       必须是非名字字符（{@code Names.isNameChar} = {@link Character#isLetterOrDigit(char)} 或
 *       {@code '_'}），两侧都走 {@code toLowerCase()}。所以 {@code mentionTargets} 里放的应该是
 *       **纯玩家名**（不带 {@code @}、不带 UUID），大小写不敏感。</li>
 *   <li>Fabric 服务端就是这么造的：{@code ChatBubbleMod.MENTION_PATTERN = "@([\\p{L}\\p{N}_]+)"}，
 *       {@code extractMentions} 取的是<b>捕获组</b>（纯名字文本），塞进
 *       {@code ChatMetaPayload.mentionTargets}。</li>
 *   <li>客户端目前只把 {@code mentionTargets} 记进 debug 日志、并随
 *       {@code EchoTracker.PendingMeta} 透传（{@code ChatMessageStore#applyChatMeta}），
 *       没有任何按 UUID 匹配的逻辑 —— 所以这里放能对回玩家的**规范名字**最有用，也不破坏现有客户端。</li>
 * </ul>
 *
 * <p>匹配规则（与客户端 {@code MentionDetector} 对齐）：
 * <ul>
 *   <li>{@code '@'} 的**左侧不做边界要求**（客户端也不要求：{@code foo@Bob} 同样算提及）；</li>
 *   <li>{@code '@'} 右侧必须紧接名字；名字结束处必须是行尾或非名字字符；</li>
 *   <li>同一个 {@code '@'} 位置**优先匹配最长的名字**：{@code @Bobby} 不会被解析成 {@code Bob}
 *       （即使将来出现「短名 + 非名字字符」的昵称，也只会挑最长那一个）；</li>
 *   <li>按名字去重（{@link LinkedHashSet}，保持第一次出现的顺序）；
 *       <b>不排除发送者自己</b> —— 客户端明确支持「自己 @ 自己」也弹提醒
 *       （{@code ChatBubbleScreen} 发送路径里注释为 self-@ notifications 的那段）；</li>
 *   <li>{@code @everyone} / {@code @here} 不需要特判：只有真的存在同名玩家才会命中，
 *       因此不会误报（代价是「真有玩家叫 everyone」时会被当成提及，这是预期的精确匹配语义）。</li>
 * </ul>
 *
 * <p>纯文本逻辑、不引用任何 Bukkit 类型，便于离线跑用例。
 */
final class MentionParser {

    private MentionParser() {
    }

    /**
     * @param text        聊天明文（{@code AsyncChatEvent#message()} 序列化后的纯文本）
     * @param onlineNames 在线玩家名（{@code Player#getName()}，服务端规范大小写）
     * @return 命中的规范玩家名（不带 {@code @}、已去重、按首次出现顺序）；没有命中返回空列表
     */
    static List<String> parse(String text, Collection<String> onlineNames) {
        if (text == null || text.isEmpty() || onlineNames == null || onlineNames.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(onlineNames.size());
        for (String name : onlineNames) {
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            return List.of();
        }
        // 长名优先：同一个 '@' 位置先试最长的名字，等价于「取最长匹配」
        names.sort((a, b) -> Integer.compare(b.length(), a.length()));

        Set<String> hits = new LinkedHashSet<>();
        int at = 0;
        while ((at = text.indexOf('@', at)) >= 0) {
            int nameStart = at + 1;
            String matched = null;
            for (String name : names) {
                int end = nameStart + name.length();
                if (end > text.length()) {
                    continue;
                }
                // regionMatches(ignoreCase=true) 与客户端 toLowerCase 比较等价（ASCII 名字完全相同）
                if (!text.regionMatches(true, nameStart, name, 0, name.length())) {
                    continue;
                }
                // 与客户端一样的右边界规则：名字后面必须是行尾或非名字字符，
                // 否则 "@Bobby" 里的 "Bob"、或某个短名出现在更长的词里会误报。
                if (end < text.length() && isNameChar(text.charAt(end))) {
                    continue;
                }
                matched = name;
                break;
            }
            if (matched == null) {
                at++;
                continue;
            }
            hits.add(matched);
            // 跳过已消费的 @名字，避免 "@@Bob" 之类重复命中同一段
            at = nameStart + matched.length();
        }
        return hits.isEmpty() ? List.of() : List.copyOf(hits);
    }

    /** 与客户端 {@code com.niuqu.chatbubble.chat.Names#isNameChar} 完全一致。 */
    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
