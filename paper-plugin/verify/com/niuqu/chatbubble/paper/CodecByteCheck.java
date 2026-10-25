package com.niuqu.chatbubble.paper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * <b>离线逐字节校验</b>：拿真正的 Minecraft {@code PacketByteBuf}（yarn 1.21.11 的 merged jar）当基准，
 * 双向验证 Paper 侧的复刻实现。
 *
 * <p>不是「自己编一遍再自己解一遍」那种自证：这里的基准字节由 **原版实现**
 * （{@code StringEncoding.encode} / {@code VarInts.write} / {@code writeCollection}）产生，
 * 待测字节由本插件产生（或反之），两边逐字节比对。
 *
 * <p>覆盖点：
 * <ol>
 *   <li>quote_sync 解码：原版编码 → 本插件解码（含中文/emoji/负数 hash/中文多字节长度前缀）</li>
 *   <li>quote_sync 旧版 3 字段兼容、尾部多余字段兼容、截断包拒绝</li>
 *   <li>chat_meta 编码：本插件编码 vs 原版编码，**整条 byte[] 必须完全相同**</li>
 *   <li>chat_meta 的非空 mentionTargets（验证 VarInt 元素数 + 逐元素字符串）</li>
 *   <li>chat_meta 解码回来完全消耗（没有多余/缺失字节）</li>
 *   <li>超长字符串边界（32767 字符 / 98301 字节）与超限拒绝</li>
 * </ol>
 *
 * 运行方式见同目录 README.md（需要 minecraft-merged + netty-buffer/netty-common 在 classpath 上）。
 */
public final class CodecByteCheck {

    private static int checks = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        checkQuoteSyncDecode();
        checkQuoteSyncLegacyAndForwardCompat();
        checkQuoteSyncTruncatedRejected();
        checkChatMetaEncodeByteIdentical();
        checkChatMetaMentionsByteIdentical();
        checkChatMetaDecodeRoundTrip();
        checkStringLimits();

        System.out.println();
        System.out.println("checks run : " + checks);
        System.out.println("failures   : " + failures.size());
        for (String failure : failures) {
            System.out.println("  FAIL " + failure);
        }
        System.out.println(failures.isEmpty() ? "RESULT: ALL BYTE CHECKS PASSED" : "RESULT: FAILED");
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 1

    /** 原版 {@code PacketByteBuf} 按 Fabric 字段顺序写 quote_sync，本插件解码后必须字段全等。 */
    private static void checkQuoteSyncDecode() {
        ByteBuf raw = Unpooled.buffer();
        PacketByteBuf mc = new PacketByteBuf(raw);
        mc.writeString("史蒂夫");              // quotedSenderName：3 个中文字 = 9 字节
        mc.writeString("引用：你好✅ 混排 content");  // quotedContent
        mc.writeString("-1234567890");        // messageHash（可为负数）
        mc.writeString("987654321");          // quotedHash
        byte[] wire = drain(raw);

        // 中文字符串的长度前缀必须是「字节数」而不是「字符数」：'史蒂夫' = 3 字符 / 9 字节
        check("quote_sync varint prefix = BYTE length (9 for 3 CJK chars)", wire[0] == 9);
        check("quote_sync first field UTF-8 bytes",
            new String(wire, 1, 9, StandardCharsets.UTF_8).equals("史蒂夫"));

        QuoteSyncPayload decoded = QuoteSyncPayload.decode(wire);
        check("quote_sync quotedSenderName", decoded.quotedSenderName().equals("史蒂夫"));
        check("quote_sync quotedContent", decoded.quotedContent().equals("引用：你好✅ 混排 content"));
        check("quote_sync messageHash", decoded.messageHash().equals("-1234567890"));
        check("quote_sync quotedHash", decoded.quotedHash().equals("987654321"));
    }

    // ------------------------------------------------------------------ 2

    /** 旧客户端只发 3 个字段 → 第 4 个读成 ""；新客户端多发字段 → 多余字节被忽略（都不得抛异常）。 */
    private static void checkQuoteSyncLegacyAndForwardCompat() {
        ByteBuf legacyRaw = Unpooled.buffer();
        PacketByteBuf legacy = new PacketByteBuf(legacyRaw);
        legacy.writeString("Alex");
        legacy.writeString("old client");
        legacy.writeString("42");
        QuoteSyncPayload threeFields = QuoteSyncPayload.decode(drain(legacyRaw));
        check("legacy 3-field quotedHash == \"\"", threeFields.quotedHash().isEmpty());
        check("legacy 3-field messageHash", threeFields.messageHash().equals("42"));

        ByteBuf futureRaw = Unpooled.buffer();
        PacketByteBuf future = new PacketByteBuf(futureRaw);
        future.writeString("Alex");
        future.writeString("newer client");
        future.writeString("43");
        future.writeString("44");
        future.writeString("some-future-field");   // 未来版本追加的字段
        QuoteSyncPayload fiveFields = QuoteSyncPayload.decode(drain(futureRaw));
        check("future extra field ignored", fiveFields.quotedHash().equals("44"));
        check("future extra field not misparsed", fiveFields.quotedContent().equals("newer client"));
    }

    // ------------------------------------------------------------------ 3

    /** 截断 / 长度撒谎的包必须抛异常（插件捕获后丢包，绝不冒泡到网络层）。 */
    private static void checkQuoteSyncTruncatedRejected() {
        ByteBuf raw = Unpooled.buffer();
        PacketByteBuf mc = new PacketByteBuf(raw);
        mc.writeString("史蒂夫");
        mc.writeString("content");
        mc.writeString("42");
        mc.writeString("43");
        byte[] whole = drain(raw);

        // 去掉最后一个字符串（"43" = 1 字节 VarInt 前缀 + 2 字节数据 = 3 字节）：
        // 第 4 个字段「整个读不到」→ 按兼容策略退化成 ""，不算错误
        byte[] withoutLast = Arrays.copyOf(whole, whole.length - 3);
        check("dropped last field -> empty (tolerated)",
            QuoteSyncPayload.decode(withoutLast).quotedHash().isEmpty());

        // 但第 1 个字符串被截断（声明 9 字节只剩 5 字节）→ 必须抛
        byte[] truncatedFirst = Arrays.copyOf(whole, 6);
        check("truncated string rejected", throwsOnDecode(truncatedFirst));

        // 长度撒谎（声明 0x7F 但没数据）
        check("lying length rejected", throwsOnDecode(new byte[] {0x7F}));
        // 0xFF 0xFF 0xFF 0xFF 0xFF = 6 字节 VarInt → "VarInt too big"
        check("5-byte varint rejected",
            throwsOnDecode(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
        // 空包 → 四个空串（原版 CustomPayload 也不会崩）
        QuoteSyncPayload empty = QuoteSyncPayload.decode(new byte[0]);
        check("empty payload -> all empty",
            empty.quotedSenderName().isEmpty() && empty.quotedHash().isEmpty());
    }

    private static boolean throwsOnDecode(byte[] wire) {
        try {
            QuoteSyncPayload.decode(wire);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    // ------------------------------------------------------------------ 4

    /** chat_meta：本插件编码 vs 原版按 Fabric 字段顺序编码 —— 整条字节必须一致。 */
    private static void checkChatMetaEncodeByteIdentical() {
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        ChatMetaPayload mine = new ChatMetaPayload(uuid, "史蒂夫", "-1234567890", "Alex",
            "被引用的中文内容 ✅", List.of(), "13579");

        ByteBuf raw = Unpooled.buffer();
        PacketByteBuf mc = new PacketByteBuf(raw);
        mc.writeString(uuid.toString());
        mc.writeString("史蒂夫");
        mc.writeString("-1234567890");
        mc.writeString("Alex");
        mc.writeString("被引用的中文内容 ✅");
        mc.writeCollection(List.<String>of(), PacketByteBuf::writeString);
        mc.writeString("13579");
        byte[] expected = drain(raw);

        byte[] actual = mine.encode();
        check("chat_meta byte-identical (" + expected.length + " bytes)",
            Arrays.equals(expected, actual));
        if (!Arrays.equals(expected, actual)) {
            System.out.println("  expected: " + hex(expected));
            System.out.println("  actual  : " + hex(actual));
        }
    }

    /** 非空 mentionTargets：验证 VarInt 元素数 + 每个元素的字符串编码（MVP 恒空，但契约必须对）。 */
    private static void checkChatMetaMentionsByteIdentical() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        List<String> mentions = List.of("史蒂夫", "Alex", "玩家_3");
        ChatMetaPayload mine = new ChatMetaPayload(uuid, "sender", "1", "q", "c", mentions, "2");

        ByteBuf raw = Unpooled.buffer();
        PacketByteBuf mc = new PacketByteBuf(raw);
        mc.writeString(uuid.toString());
        mc.writeString("sender");
        mc.writeString("1");
        mc.writeString("q");
        mc.writeString("c");
        mc.writeCollection(mentions, PacketByteBuf::writeString);
        mc.writeString("2");
        byte[] expected = drain(raw);

        check("chat_meta+mentions byte-identical", Arrays.equals(expected, mine.encode()));
    }

    // ------------------------------------------------------------------ 5

    /** 用原版解码器读回本插件编码的 chat_meta：字段全等且刚好消耗完（没有多写/少写字节）。 */
    private static void checkChatMetaDecodeRoundTrip() {
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        ChatMetaPayload mine = new ChatMetaPayload(uuid, "史蒂夫", "-1234567890", "Alex",
            "被引用的中文内容 ✅", List.of(), "13579");

        ByteBuf raw = Unpooled.wrappedBuffer(mine.encode());
        PacketByteBuf mc = new PacketByteBuf(raw);

        check("readback senderUUID", UUID.fromString(mc.readString()).equals(uuid));
        check("readback senderName", mc.readString().equals("史蒂夫"));
        check("readback messageHash", mc.readString().equals("-1234567890"));
        check("readback quoteSender", mc.readString().equals("Alex"));
        check("readback quoteContent", mc.readString().equals("被引用的中文内容 ✅"));
        int mentionCount = mc.readVarInt();
        check("readback mentionTargets size == 0", mentionCount == 0);
        check("readback quotedHash", mc.readString().equals("13579"));
        check("readback fully consumed (no stray bytes)", !mc.isReadable());
    }

    // ------------------------------------------------------------------ 6

    /** 边界：32767 字符可以，32768 拒绝；多字节字符串的 VarInt 长度前缀必须是字节数。 */
    private static void checkStringLimits() {
        String max = "a".repeat(McCodec.MAX_STR_CHARS);
        check("32767 ASCII chars encode ok", encodeString(max) != null);
        check("32768 chars rejected", encodeString(max + "a") == null);

        // 中文 30000 字符 = 90000 字节 < 98301，可以
        String cjk = "汉".repeat(30000);
        byte[] mine = encodeString(cjk);
        byte[] real = mcEncodeString(cjk);
        check("30000 CJK chars encode ok (90000 bytes)", mine != null);
        check("30000 CJK encode == real PacketByteBuf", Arrays.equals(mine, real));
        if (mine != null) {
            // 90000 = 0x10 | 0x80, 0x3F | 0x80, 0x05（LEB128，与真实现比对过，不是手算唯一依据）
            check("90000-byte VarInt prefix = 90 BF 05",
                mine[0] == (byte) 0x90 && mine[1] == (byte) 0xBF && mine[2] == 0x05);
            // 解码回来必须一模一样（字节数 vs 字符数不混淆）
            McCodec.Reader reader = new McCodec.Reader(mine);
            check("30000 CJK chars decode ok", reader.readString().equals(cjk));
        }

        // 32767 字符的中文 = 98301 字节，刚好等于上限
        String edge = "汉".repeat(McCodec.MAX_STR_CHARS);
        check("32767 CJK chars (98301 bytes) ok", encodeString(edge) != null);
        check("32767 CJK encode == real PacketByteBuf",
            Arrays.equals(encodeString(edge), mcEncodeString(edge)));

        // 混合样本（含 4 字节 UTF-8 的 emoji / 控制字符 / 空串）：逐个字节比对真实现
        String[] samples = {"", "a", "-1234567890", "史蒂夫", "你好✅ 混排", "🇨🇳 emoji 🚀",
            "line\nbreak\tand\rcr", max};
        boolean allEqual = true;
        for (String sample : samples) {
            if (!Arrays.equals(encodeString(sample), mcEncodeString(sample))) {
                allEqual = false;
                System.out.println("  mismatch for sample len=" + sample.length());
            }
        }
        check("writeString == real PacketByteBuf for " + samples.length + " samples", allEqual);
    }

    /** 用原版 {@code PacketByteBuf.writeString} 编码同一个字符串，作为字节基准。 */
    private static byte[] mcEncodeString(String s) {
        ByteBuf raw = Unpooled.buffer();
        new PacketByteBuf(raw).writeString(s);
        return drain(raw);
    }

    private static byte[] encodeString(String s) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            McCodec.writeString(out, s);
            return out.toByteArray();
        } catch (RuntimeException rejected) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 工具

    private static byte[] drain(ByteBuf raw) {
        byte[] out = new byte[raw.readableBytes()];
        raw.readBytes(out);
        return out;
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            sb.append(String.format("%02x ", b));
        }
        return sb.toString().trim();
    }

    private static void check(String name, boolean condition) {
        checks++;
        if (!condition) {
            failures.add(name);
        }
        System.out.println((condition ? "[ok]   " : "[FAIL] ") + name);
    }
}
