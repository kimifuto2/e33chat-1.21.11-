package com.niuqu.chatbubble.paper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 群状态的落盘层 —— <b>{@code <主世界目录>/serverconfig/e33chat-groups.json}</b>。
 *
 * <p>这个类只做「结构 ↔ JSON ↔ 文件」三件事，不含任何群规则（规则全在
 * {@link GroupRegistry}）。之所以要它：Paper 侧原先的群状态是纯内存态，服务端一重启所有群
 * 就消失了；Fabric 端早就把同一份状态落在 {@code <world>/serverconfig/e33chat-groups.json}，
 * 这里**逐字段复刻 Fabric 的 Gson 结构**，两边的文件可以互相读取（Paper 写的文件 Fabric 能读，
 * Fabric 写的文件 Paper 能读）。
 *
 * <h2>文件结构（与 Fabric {@code GroupRegistry.saveToDisk()} 逐字段一致）</h2>
 * <pre>
 * {
 *   "version": 1,                       // Fabric 写死的版本号；读取时不校验（只记录）
 *   "groups": [
 *     {
 *       "id":      "&lt;群 UUID&gt;",
 *       "name":    "群名（空串 = 未命名）",
 *       "creator": "&lt;创建者 UUID&gt;" 或 ""（未知）,
 *       "members": [                     // 顺序 = 入群顺序（LinkedHashMap）
 *         { "uuid": "&lt;成员 UUID&gt;", "name": "成员最后已知名字" }
 *       ]
 *     }
 *   ],
 *   "pendingInvites": [                 // Paper 的**额外**字段（Fabric 没有，见下）
 *     { "group": "&lt;群 UUID&gt;", "players": [ "&lt;被邀请者 UUID&gt;" ] }
 *   ]
 * }
 * </pre>
 *
 * <p><b>兼容性</b>：Fabric 的读取器只看根上的 {@code groups}，不认识的多余键会被忽略，
 * 所以 {@code pendingInvites} 不会破坏 Fabric 的解析；反过来 Fabric 保存时会把
 * {@code pendingInvites} 丢掉（= Paper 侧 pending 失效），这是两套实现之间唯一的差异，
 * 只在「同一个世界目录先后被 Fabric 服和 Paper 服打开」时才会遇到。
 *
 * <p><b>字段名不能改</b>：{@code id / name / creator / members / uuid} 是 Fabric Gson
 * 序列化的字面键名，改一个 Fabric 就读不到（它用 {@code Map<String,Object>} 按名字取值）。
 *
 * <h2>写盘策略</h2>
 * <ul>
 *   <li><b>先写临时文件再原子替换</b>：写到同目录的 {@code e33chat-groups.json.tmp}
 *       （{@code FileChannel.force} 刷盘）→ {@code Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)}。
 *       这样即使进程在写一半时被杀、或磁盘写失败，原文件也仍然是上一次的完整内容，
 *       不会留下半截 JSON（半截 JSON 会让下次启动「一个群都没有」）。</li>
 *   <li>文件系统不支持原子改名（部分网络盘 / FAT）时退化为普通替换，仍然保证
 *       「临时文件写完才动目标文件」。</li>
 *   <li>任何 IOException 都抛给调用方（{@link GroupRegistry} 只记日志，绝不影响游戏）。</li>
 * </ul>
 *
 * <p>JSON 是手写的（不引第三方依赖，插件只用 paper-api）：写出来的是 Gson 风格的 2 空格缩进，
 * 读回来的是同一个解析器；字符串按 Gson {@code disableHtmlEscaping} 的语义处理
 * （{@code < > & = '} 原样输出、只有引号/反斜杠/控制字符/\u2028/\u2029 转义），
 * 中文/emoji 直接以 UTF-8 落盘，因此和 Fabric 的输出可以互读。
 */
final class GroupStore {

    /** 与 Fabric 同名的子目录（Fabric：{@code <world>/serverconfig/e33chat-groups.json}）。 */
    static final String DIR_NAME = "serverconfig";
    /** 与 Fabric 同名的主文件名。 */
    static final String FILE_NAME = "e33chat-groups.json";
    /** 原子写用的临时文件后缀（必须与目标文件同目录，否则 rename 可能跨卷）。 */
    static final String TEMP_SUFFIX = ".tmp";
    /** 文件格式版本 —— Fabric 写 {@code "version": 1}；读取时不拒绝更高版本（向后兼容）。 */
    static final int VERSION = 1;

    /**
     * 读文件的大小上限。理论上限 = 4096 群 × 64 人 × 约 60 字节 ≈ 250 KB，
     * 4 MiB 已经非常宽松；超过就当作坏文件拒绝，避免被一个巨大文件打爆内存。
     */
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    /** 群数量上限（防手改文件把内存打爆）；正常服远达不到。 */
    private static final int MAX_GROUPS = 4096;
    /** JSON 嵌套深度上限（坏文件防栈溢出）。 */
    private static final int MAX_DEPTH = 32;

    private final Path file;

    GroupStore(Path file) {
        // 统一成绝对路径：Paper 的 getWorldFolder() 返回的是相对 File（如 ".\world"），
        // 日志里直接打出来会是 ".\world\serverconfig\..."，运维不好找；绝对化之后两边一致。
        this.file = file.toAbsolutePath().normalize();
    }

    /** 落盘文件路径（用于日志/诊断）。 */
    Path file() {
        return file;
    }

    /** 读出来的内容：群 + pending 邀请；文件不存在时两者都是空的（不是错误）。 */
    static final class Snapshot {
        final List<GroupRegistry.Group> groups = new ArrayList<>();
        final Map<UUID, LinkedHashSet<UUID>> pending = new LinkedHashMap<>();
    }

    // ================================================================ 读

    /**
     * 读文件 → 内存结构。文件不存在 = 空快照（首次开服，正常情况）；
     * 内容损坏抛 {@link IOException}（调用方只记日志、保持当前内存态）。
     *
     * <p>容错口径与 Fabric {@code loadFromDisk()} 逐条对齐：单个群/单个成员坏掉只丢那一条，
     * 不影响其它群；**成员表为空的群不加载**（Fabric：{@code if (!g.members.isEmpty())}）。
     */
    Snapshot read() throws IOException {
        Snapshot snap = new Snapshot();
        if (!Files.isRegularFile(file)) {
            return snap;
        }
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new IOException("group store too large: " + size + " bytes > " + MAX_FILE_BYTES);
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        // 手改过的文件可能带 UTF-8 BOM（Windows 记事本），Gson 也不吃 BOM，这里主动剥掉。
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            return snap;
        }
        Object root;
        try {
            root = new Json(text).parse();
        } catch (RuntimeException e) {
            throw new IOException("malformed JSON in " + file + ": " + e.getMessage(), e);
        }
        Map<String, Object> rootMap = asMap(root);
        if (rootMap == null) {
            throw new IOException("group store root is not a JSON object: " + file);
        }
        List<Object> groupList = asList(rootMap.get("groups"));
        if (groupList != null) {
            for (Object item : groupList) {
                if (snap.groups.size() >= MAX_GROUPS) {
                    break;
                }
                GroupRegistry.Group group = readGroup(asMap(item));
                if (group != null) {
                    snap.groups.add(group);
                }
            }
        }
        readPending(rootMap.get("pendingInvites"), snap);
        return snap;
    }

    /** 单个群：任何一处坏掉返回 null（= 丢掉这个群，与 Fabric 的 per-group try/catch 同义）。 */
    private static GroupRegistry.Group readGroup(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        UUID id = parseUuid(asString(map.get("id")));
        if (id == null) {
            return null;
        }
        String name = asString(map.get("name"));
        UUID creator = parseUuid(asString(map.get("creator")));   // "" / 缺失 / 非法 → null（未知）
        GroupRegistry.Group group = new GroupRegistry.Group(id, name, creator);
        List<Object> members = asList(map.get("members"));
        if (members != null) {
            for (Object memberItem : members) {
                if (group.size() >= GroupRegistry.MAX_MEMBERS) {
                    break;
                }
                Map<String, Object> member = asMap(memberItem);
                if (member == null) {
                    continue;
                }
                UUID memberId = parseUuid(asString(member.get("uuid")));
                if (memberId == null) {
                    continue;
                }
                group.putMember(memberId, asString(member.get("name")));
            }
        }
        return group.size() == 0 ? null : group;   // 空群不加载（与 Fabric 一致）
    }

    /** {@code pendingInvites}：Paper 的额外字段；缺失/坏掉只是没有 pending，不影响群本身。 */
    private static void readPending(Object value, Snapshot snap) {
        List<Object> list = asList(value);
        if (list == null) {
            return;
        }
        for (Object item : list) {
            Map<String, Object> map = asMap(item);
            if (map == null) {
                continue;
            }
            UUID groupId = parseUuid(asString(map.get("group")));
            if (groupId == null) {
                continue;
            }
            LinkedHashSet<UUID> players = new LinkedHashSet<>();
            List<Object> playerList = asList(map.get("players"));
            if (playerList != null) {
                for (Object playerItem : playerList) {
                    if (players.size() >= GroupRegistry.MAX_MEMBERS) {
                        break;
                    }
                    UUID playerId = parseUuid(asString(playerItem));
                    if (playerId != null) {
                        players.add(playerId);
                    }
                }
            }
            if (!players.isEmpty()) {
                snap.pending.put(groupId, players);
            }
        }
    }

    // ================================================================ 写

    /**
     * 内存结构 → JSON → 原子替换目标文件。
     *
     * @return 实际写出的字节数（日志用）
     * @throws IOException 写临时文件/替换失败（调用方只记日志）
     */
    int write(Collection<GroupRegistry.Group> groups, Map<UUID, LinkedHashSet<UUID>> pending)
        throws IOException {
        StringBuilder out = new StringBuilder(512);
        out.append("{\n");
        out.append("  \"version\": ").append(VERSION).append(",\n");
        out.append("  \"groups\": ");
        appendGroups(out, groups);
        out.append(",\n");
        out.append("  \"pendingInvites\": ");
        appendPending(out, pending);
        out.append("\n}\n");
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        writeAtomic(bytes);
        return bytes.length;
    }

    private static void appendGroups(StringBuilder out, Collection<GroupRegistry.Group> groups) {
        Collection<GroupRegistry.Group> list = groups == null ? List.of() : groups;
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        boolean first = true;
        for (GroupRegistry.Group group : list) {
            if (group == null) {
                continue;
            }
            if (!first) {
                out.append(",\n");
            }
            first = false;
            out.append("    {\n");
            out.append("      \"id\": ");
            appendString(out, group.id().toString());
            out.append(",\n");
            out.append("      \"name\": ");
            appendString(out, group.name());
            out.append(",\n");
            out.append("      \"creator\": ");
            appendString(out, group.creator() == null ? "" : group.creator().toString());
            out.append(",\n");
            out.append("      \"members\": ");
            appendMembers(out, group.memberList());
            out.append("\n    }");
        }
        out.append("\n  ]");
    }

    private static void appendMembers(StringBuilder out, List<GroupOutbound.Member> members) {
        if (members == null || members.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < members.size(); i++) {
            GroupOutbound.Member member = members.get(i);
            if (i > 0) {
                out.append(",\n");
            }
            out.append("        {\n");
            out.append("          \"uuid\": ");
            appendString(out, member == null || member.uuid() == null ? "" : member.uuid().toString());
            out.append(",\n");
            out.append("          \"name\": ");
            appendString(out, member == null ? "" : member.name());
            out.append("\n        }");
        }
        out.append("\n      ]");
    }

    private static void appendPending(StringBuilder out, Map<UUID, LinkedHashSet<UUID>> pending) {
        List<Map.Entry<UUID, LinkedHashSet<UUID>>> entries = new ArrayList<>();
        if (pending != null) {
            for (Map.Entry<UUID, LinkedHashSet<UUID>> entry : pending.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    entries.add(entry);
                }
            }
        }
        if (entries.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                out.append(",\n");
            }
            Map.Entry<UUID, LinkedHashSet<UUID>> entry = entries.get(i);
            out.append("    {\n");
            out.append("      \"group\": ");
            appendString(out, entry.getKey().toString());
            out.append(",\n");
            out.append("      \"players\": [\n");
            boolean firstPlayer = true;
            for (UUID player : entry.getValue()) {
                if (player == null) {
                    continue;
                }
                if (!firstPlayer) {
                    out.append(",\n");
                }
                firstPlayer = false;
                out.append("        ");
                appendString(out, player.toString());
            }
            out.append("\n      ]\n");
            out.append("    }");
        }
        out.append("\n  ]");
    }

    /**
     * 原子写：同目录临时文件 → {@code force(true)} 刷盘 → 原子替换目标文件。
     *
     * <p>为什么不用 {@code Files.newBufferedWriter(目标)}：那个写法在写一半时被 kill 会留下
     * 半截 JSON，下次启动会「一个群都没有」。这里是「要么旧文件、要么新文件」。
     */
    private void writeAtomic(byte[] bytes) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName().toString() + TEMP_SUFFIX);
        try {
            try (FileChannel channel = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 部分网络盘 / FAT 不支持原子改名：退化为普通替换（内容已完整写完）
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (Throwable ignored) {
                // 清理临时文件失败不影响主流程
            }
            throw e;
        }
    }

    // ================================================================ JSON 输出

    /**
     * 按 JSON 规范转义一个字符串（Gson {@code disableHtmlEscaping} 的语义：不转义
     * {@code < > & = '}，只转义引号/反斜杠/控制字符与 U+2028/U+2029）。
     */
    static void appendString(StringBuilder out, String value) {
        out.append('"');
        String s = value == null ? "" : value;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    continue;
                case '\\':
                    out.append("\\\\");
                    continue;
                case '\n':
                    out.append("\\n");
                    continue;
                case '\r':
                    out.append("\\r");
                    continue;
                case '\t':
                    out.append("\\t");
                    continue;
                case '\b':
                    out.append("\\b");
                    continue;
                case '\f':
                    out.append("\\f");
                    continue;
                case '\u2028':
                    out.append("\\u2028");
                    continue;
                case '\u2029':
                    out.append("\\u2029");
                    continue;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    // ================================================================ JSON 解析

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static List<Object> asList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return null;
        }
        return new ArrayList<>(raw);
    }

    /** JSON 标量 → 字符串；缺失 / null / 非标量返回 {@code ""}（与 Fabric {@code String.valueOf} 的宽容度对齐）。 */
    private static String asString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Double d) {
            // 手改的文件里 uuid 可能被写成数字（虽然不合法），这里不猜，交给 parseUuid 拒绝
            return d.toString();
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        return "";
    }

    private static UUID parseUuid(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 极简递归下降 JSON 解析器（够读本文件即可，不需要第三方库）。
     *
     * <p>只支持标准 JSON 值：对象 / 数组 / 字符串 / 数字 / true / false / null。
     * 任何语法错误抛 {@link IllegalArgumentException}，由 {@link #read()} 包成
     * {@link IOException}（调用方只记日志，不会崩）。
     */
    private static final class Json {
        private final String text;
        private int pos;

        Json(String text) {
            this.text = text;
        }

        Object parse() {
            Object value = parseValue(0);
            skipWhitespace();
            if (pos < text.length()) {
                throw error("trailing characters");
            }
            return value;
        }

        private Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting too deep");
            }
            skipWhitespace();
            if (pos >= text.length()) {
                throw error("unexpected end of input");
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject(depth);
                case '[':
                    return parseArray(depth);
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        private Map<String, Object> parseObject(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;   // '{'
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected object key");
                }
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw error("expected ':'");
                }
                pos++;
                map.put(key, parseValue(depth + 1));
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw error("expected ',' or '}'");
            }
        }

        private List<Object> parseArray(int depth) {
            List<Object> list = new ArrayList<>();
            pos++;   // '['
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue(depth + 1));
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw error("expected ',' or ']'");
            }
        }

        private String parseString() {
            pos++;   // '"'
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= text.length()) {
                    throw error("unterminated escape");
                }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case '/':
                        out.append('/');
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > text.length()) {
                            throw error("truncated \\u escape");
                        }
                        String hex = text.substring(pos, pos + 4);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error("bad \\u escape: " + hex);
                        }
                        pos += 4;
                        break;
                    default:
                        throw error("bad escape: \\" + escape);
                }
            }
        }

        private Double parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < text.length() && isNumberChar(text.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                throw error("unexpected character");
            }
            try {
                return Double.valueOf(text.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error("bad number: " + text.substring(start, pos));
            }
        }

        private static boolean isNumberChar(char c) {
            return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, pos)) {
                throw error("expected " + literal);
            }
            pos += literal.length();
        }

        private char peek() {
            if (pos >= text.length()) {
                throw error("unexpected end of input");
            }
            return text.charAt(pos);
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + pos);
        }
    }
}
