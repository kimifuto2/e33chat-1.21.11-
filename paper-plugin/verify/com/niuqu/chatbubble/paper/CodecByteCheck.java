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
        checkUuidAndBooleanPrimitives();
        checkGroupOutboundByteIdentical();
        checkGroupInboundDecodeMatchesFabric();
        checkPlayerActionByteIdentical();
        checkGroupWireHelpers();

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

    // ================================================================ 群聊 / 动作协议
    //
    // 下面几组是 3.1.0 新增的群聊（group_*）与动作（player_action*）协议校验。
    // 基准一律是「真正的 PacketByteBuf 按 Fabric CODEC 的字段顺序写一遍」：
    //   - 出站（S2C）：本插件编码 vs 原版编码 → 整条 byte[] 必须完全相同；
    //   - 入站（C2S）：原版编码 → 本插件解码 → 字段必须逐项相等。
    // 这样才不是「自己编一遍再自己解一遍」的自证。

    /** UUID / boolean 这两个原语单独验：UUID 是两个大端 long，boolean 是单字节 0/1。 */
    private static void checkUuidAndBooleanPrimitives() {
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        // UUID：真实现 writeUuid = writeLong(msb) + writeLong(lsb)
        ByteBuf raw = Unpooled.buffer();
        PacketByteBuf mc = new PacketByteBuf(raw);
        mc.writeUuid(uuid);
        byte[] expected = drain(raw);
        check("writeUuid is 16 bytes", expected.length == 16);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        McCodec.writeUuid(out, uuid);
        check("writeUuid byte-identical to PacketByteBuf.writeUuid",
            Arrays.equals(expected, out.toByteArray()));

        // 大端：msb 的最高字节在前（0x12 0x3e 0x45 0x67 ...）
        check("UUID long is BIG-endian (first byte 0x12, second 0x3e)",
            expected[0] == 0x12 && expected[1] == 0x3e);
        check("UUID second long starts at offset 8 with 0xa4",
            expected[8] == (byte) 0xa4);

        // nil UUID（全零）与 null 都写 16 个 0 字节
        java.io.ByteArrayOutputStream nil = new java.io.ByteArrayOutputStream();
        McCodec.writeUuid(nil, null);
        byte[] nilBytes = nil.toByteArray();
        boolean allZero = nilBytes.length == 16;
        for (byte b : nilBytes) {
            if (b != 0) {
                allZero = false;
            }
        }
        check("null UUID -> 16 zero bytes", allZero);

        // 解码回来必须等值
        McCodec.Reader reader = new McCodec.Reader(expected);
        check("readUuid round-trip", reader.readUuid().equals(uuid));

        // boolean：真实现是 netty writeBoolean（1 字节），不是 VarInt
        ByteBuf braw = Unpooled.buffer();
        PacketByteBuf bmc = new PacketByteBuf(braw);
        bmc.writeBoolean(true);
        bmc.writeBoolean(false);
        byte[] breal = drain(braw);
        check("boolean is 1 byte each (2 bytes total)", breal.length == 2);
        java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
        McCodec.writeBoolean(bout, true);
        McCodec.writeBoolean(bout, false);
        check("writeBoolean byte-identical (01 00)",
            Arrays.equals(breal, bout.toByteArray()) && breal[0] == 1 && breal[1] == 0);
        McCodec.Reader br = new McCodec.Reader(breal);
        check("readBoolean true then false", br.readBoolean() && !br.readBoolean());
    }

    /** 4 条 S2C 群载荷：本插件编码 vs 原版按 Fabric 字段顺序编码，整条字节必须一致。 */
    private static void checkGroupOutboundByteIdentical() {
        UUID groupId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID creator = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID alice = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        UUID bob = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        // 故意用 >32 字符的群名/成员名，验证两端夹断位置与上限一致（不然字段会错位）
        String longName = "一个非常非常长的群聊名字用来验证三十二字符夹断行为是否正确无误";
        String longMemberName = "同样很长的成员显示名用于校验夹断逻辑";
        List<GroupOutbound.Member> members = List.of(
            new GroupOutbound.Member(alice, "史蒂夫"),
            new GroupOutbound.Member(bob, longMemberName));

        // ---- group_sync
        ByteBuf raw = Unpooled.buffer();
        PacketByteBuf mc = new PacketByteBuf(raw);
        mc.writeString(groupId.toString());
        mc.writeString(clamp(longName, 32));
        mc.writeString(creator.toString());
        mc.writeVarInt(members.size());
        for (GroupOutbound.Member m : members) {
            mc.writeUuid(m.uuid());
            mc.writeString(clamp(m.name(), 32));
        }
        byte[] expected = drain(raw);
        byte[] actual = GroupOutbound.sync(groupId.toString(), longName, creator.toString(), members);
        check("group_sync byte-identical (" + expected.length + " bytes)", Arrays.equals(expected, actual));
        if (!Arrays.equals(expected, actual)) {
            System.out.println("  expected: " + hex(expected));
            System.out.println("  actual  : " + hex(actual));
        }

        // ---- group_message_relay
        String content = "群消息正文：中文 + emoji ✅ + 换行\n第二行";
        String hash = "-1234567890";
        raw = Unpooled.buffer();
        mc = new PacketByteBuf(raw);
        mc.writeString(groupId.toString());
        mc.writeUuid(alice);
        mc.writeString("史蒂夫");
        mc.writeString(content);
        mc.writeString(hash);
        expected = drain(raw);
        actual = GroupOutbound.messageRelay(groupId.toString(), alice, "史蒂夫", content, hash);
        check("group_message_relay byte-identical (" + expected.length + " bytes)",
            Arrays.equals(expected, actual));

        // ---- group_event（kind 夹到 16、text 夹到 32）
        String kind = "DISBANDED";
        raw = Unpooled.buffer();
        mc = new PacketByteBuf(raw);
        mc.writeString(groupId.toString());
        mc.writeString(clamp(kind, 16));
        mc.writeString(clamp(longName, 32));
        expected = drain(raw);
        actual = GroupOutbound.event(groupId.toString(), kind, longName);
        check("group_event byte-identical", Arrays.equals(expected, actual));

        // ---- group_invite_event（5 个字符串，kind 夹到 16，其余夹到 32）
        raw = Unpooled.buffer();
        mc = new PacketByteBuf(raw);
        mc.writeString(groupId.toString());
        mc.writeString(clamp(longName, 32));
        mc.writeString(clamp("邀请者名字很长很长很长很长很长很长", 32));
        mc.writeString(clamp("ACCEPTED", 16));
        mc.writeString(clamp(longMemberName, 32));
        expected = drain(raw);
        actual = GroupOutbound.inviteEvent(groupId.toString(), longName,
            "邀请者名字很长很长很长很长很长很长", "ACCEPTED", longMemberName);
        check("group_invite_event byte-identical", Arrays.equals(expected, actual));
    }

    /** 8 条 C2S 群载荷：原版按 Fabric 字段顺序编码 → 本插件解码 → 字段逐项相等。 */
    private static void checkGroupInboundDecodeMatchesFabric() {
        UUID groupId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID a = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        UUID nil = new UUID(0, 0);

        // ---- group_create：String name + VarInt n + n × UUID
        ByteBuf raw = Unpooled.buffer();
        PacketByteBuf mc = new PacketByteBuf(raw);
        mc.writeString("周末开黑群");
        mc.writeVarInt(3);
        mc.writeUuid(a);
        mc.writeUuid(b);
        mc.writeUuid(nil);   // nil 必须被服务端过滤掉（Fabric readUuidList 同义）
        GroupInbound.Create create = GroupInbound.decodeCreate(drain(raw));
        check("group_create name", create.name().equals("周末开黑群"));
        check("group_create members (nil filtered) == 2", create.members().size() == 2);
        check("group_create member order preserved",
            create.members().get(0).equals(a) && create.members().get(1).equals(b));

        // ---- group_message：3 个字符串（groupId / content / msgHash）
        raw = Unpooled.buffer();
        mc = new PacketByteBuf(raw);
        mc.writeString(groupId.toString());
        mc.writeString("你好 ✅");
        mc.writeString("987654321");
        GroupInbound.Message msg = GroupInbound.decodeMessage(drain(raw));
        check("group_message groupId", msg.groupId().equals(groupId.toString()));
        check("group_message content", msg.content().equals("你好 ✅"));
        check("group_message msgHash (3rd string, not missing!)", msg.msgHash().equals("987654321"));
        check("group_message isValid", msg.isValid());

        // ---- group_invite
        raw = Unpooled.buffer();
        mc = new PacketByteBuf(raw);
        mc.writeString(groupId.toString());
        mc.writeVarInt(2);
        mc.writeUuid(a);
        mc.writeUuid(b);
        GroupInbound.Invite invite = GroupInbound.decodeInvite(drain(raw));
        check("group_invite groupId", invite.groupId().equals(groupId.toString()));
        check("group_invite targets == 2", invite.members().size() == 2);
        check("group_invite isValid", invite.isValid());

        // ---- group_invite_respond：String + boolean
        raw = Unpooled.buffer();
        mc = new PacketByteBuf(raw);
        mc.writeString(groupId.toString());
        mc.writeBoolean(true);
        GroupInbound.InviteRespond respond = GroupInbound.decodeInviteRespond(drain(raw));
        check("group_invite_respond groupId", respond.groupId().equals(groupId.toString()));
        check("group_invite_respond accept=true (1 byte, not varint)",
            respond.accept() && respond.isValid());

        raw = Unpooled.buffer();
        mc = new PacketByteBuf(raw);
        mc.writeString(groupId.toString());
        mc.writeBoolean(false);
        check("group_invite_respond accept=false",
            !GroupInbound.decodeInviteRespond(drain(raw)).accept());

        // ---- group_kick：String + UUID（16 字节，不是字符串）
        raw = Unpooled.buffer();
        mc = new PacketByteBuf(raw);
        mc.writeString(groupId.toString());
        mc.writeUuid(b);
        GroupInbound.Kick kick = GroupInbound.decodeKick(drain(raw));
        check("group_kick groupId", kick.groupId().equals(groupId.toString()));
        check("group_kick target UUID", kick.target().equals(b) && kick.isValid());

        // ---- group_leave / group_disband：单个字符串
        raw = Unpooled.buffer();
        mc = new PacketByteBuf(raw);
        mc.writeString(groupId.toString());
        check("group_leave groupId", GroupInbound.decodeLeave(drain(raw)).groupId().equals(groupId.toString()));

        raw = Unpooled.buffer();
        mc = new PacketByteBuf(raw);
        mc.writeString(groupId.toString());
        check("group_disband groupId",
            GroupInbound.decodeDisband(drain(raw)).groupId().equals(groupId.toString()));

        // ---- group_rename：String + String
        raw = Unpooled.buffer();
        mc = new PacketByteBuf(raw);
        mc.writeString(groupId.toString());
        mc.writeString("新群名");
        GroupInbound.Rename rename = GroupInbound.decodeRename(drain(raw));
        check("group_rename groupId+name",
            rename.groupId().equals(groupId.toString()) && rename.name().equals("新群名"));

        // ---- 畸形/截断包一律降级成「无效载荷」，不抛异常（不能踢人）
        boolean noThrow = true;
        try {
            GroupInbound.decodeCreate(new byte[] {0x7F});
            GroupInbound.decodeMessage(new byte[] {(byte) 0xFF, (byte) 0xFF});
            GroupInbound.decodeKick(new byte[] {0x01, 'x'});
            GroupInbound.decodeInviteRespond(new byte[0]);
            GroupInbound.decodeRename(new byte[] {0x7F, 0x7F});
        } catch (Throwable t) {
            noThrow = false;
            System.out.println("  unexpected throw: " + t);
        }
        check("malformed group payloads degrade instead of throwing", noThrow);
        check("empty group_message is invalid", !GroupInbound.decodeMessage(new byte[0]).isValid());
        check("empty group_kick is invalid", !GroupInbound.decodeKick(new byte[0]).isValid());
    }

    /** player_action（C2S，7 个字符串）与 player_action_relay（S2C，UUID + 7 个字符串）。 */
    private static void checkPlayerActionByteIdentical() {
        UUID sender = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        UUID target = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID groupId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        // ---- 入站：原版编码 → 本插件解码
        ByteBuf raw = Unpooled.buffer();
        PacketByteBuf mc = new PacketByteBuf(raw);
        mc.writeString(target.toString());
        mc.writeString("史蒂夫");
        mc.writeString("pat");
        mc.writeString("");                       // 内置词条时 customText 为空
        mc.writeString("拍了拍的正文 ✅");
        mc.writeString("group");
        mc.writeString(groupId.toString());
        PlayerActionPayloads.Inbound in = PlayerActionPayloads.decode(drain(raw));
        check("player_action targetUUID", in.targetUUID().equals(target.toString()));
        check("player_action targetName", in.targetName().equals("史蒂夫"));
        check("player_action verbId", in.verbId().equals("pat"));
        check("player_action customText empty", in.customText().isEmpty());
        check("player_action content", in.content().equals("拍了拍的正文 ✅"));
        check("player_action scope", in.scope().equals("group"));
        check("player_action groupId", in.groupId().equals(groupId.toString()));
        check("player_action isValid", in.isValid());

        // 截断/空包 → 无效载荷而不是异常
        check("player_action truncated -> invalid",
            !PlayerActionPayloads.decode(new byte[] {0x7F}).isValid());
        check("player_action empty -> invalid",
            !PlayerActionPayloads.decode(new byte[0]).isValid());

        // ---- 出站：第 1 个字段是 UUID（16 字节），不是字符串
        raw = Unpooled.buffer();
        mc = new PacketByteBuf(raw);
        mc.writeUuid(sender);
        mc.writeString("史蒂夫");
        mc.writeString(target.toString());
        mc.writeString("Alex");
        mc.writeString("拍了拍");
        mc.writeString("正文内容");
        mc.writeString("private");
        mc.writeString("");
        byte[] expected = drain(raw);
        byte[] actual = PlayerActionPayloads.encodeRelay(sender, "史蒂夫", target.toString(),
            "Alex", "拍了拍", "正文内容", "private", "");
        check("player_action_relay byte-identical (" + expected.length + " bytes)",
            Arrays.equals(expected, actual));
        if (!Arrays.equals(expected, actual)) {
            System.out.println("  expected: " + hex(expected));
            System.out.println("  actual  : " + hex(actual));
        }
        // 首字节是 senderUUID 的 msb 最高字节（0x12），若误写成 UUID 字符串会是 0x24（'$' 36 字符长度前缀）
        check("player_action_relay starts with UUID (first byte 0x12, not string length 0x24)",
            expected[0] == 0x12 && actual[0] == 0x12);
    }

    /** 群/动作协议的纯函数语义（夹断、UUID 解析、hash 归一化）—— 两端必须同义。 */
    private static void checkGroupWireHelpers() {
        check("clamp keeps 32 chars",
            "abcdefghijklmnopqrstuvwxyz012345".length() == 32
                && GroupWire.clamp("abcdefghijklmnopqrstuvwxyz012345", 32)
                    .equals("abcdefghijklmnopqrstuvwxyz012345"));
        check("clamp cuts 33 -> 32",
            GroupWire.clamp("abcdefghijklmnopqrstuvwxyz012345X", 32).length() == 32);
        check("clamp(null) == \"\"", GroupWire.clamp(null, 32).isEmpty());
        check("clamp does not split surrogate-free CJK by bytes", GroupWire.clamp("中文群名", 2).equals("中文"));

        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        check("parseUuid ok", uuid.equals(GroupWire.parseUuid("123e4567-e89b-12d3-a456-426614174000")));
        check("parseUuid trims", uuid.equals(GroupWire.parseUuid("  123e4567-e89b-12d3-a456-426614174000  ")));
        check("parseUuid blank -> null", GroupWire.parseUuid(" ") == null);
        check("parseUuid nil -> null", GroupWire.parseUuid("00000000-0000-0000-0000-000000000000") == null);
        check("parseUuid garbage -> null", GroupWire.parseUuid("not-a-uuid") == null);

        check("normalizeHash keeps decimal",
            GroupWire.normalizeHash("-1234567890", "x").equals("-1234567890"));
        check("normalizeHash hashes plain text",
            GroupWire.normalizeHash("hello", "hello").equals(String.valueOf("hello".hashCode())));
        check("normalizeHash empty -> content hash",
            GroupWire.normalizeHash("", "abc").equals(String.valueOf("abc".hashCode())));

        // 词条表：已知 id 用权威文本（忽略客户端自定义），custom 才用自定义词
        check("resolveVerb pat -> 拍了拍 (customText ignored)",
            "拍了拍".equals(PlayerActionVerbs.resolveVerb("pat", "伪造词条")));
        check("resolveVerb custom -> sanitized custom",
            "摸了摸".equals(PlayerActionVerbs.resolveVerb("custom", "摸了摸")));
        check("resolveVerb unknown -> null", PlayerActionVerbs.resolveVerb("nope", "x") == null);
        check("resolveVerb custom empty -> null", PlayerActionVerbs.resolveVerb("custom", "  ") == null);
        check("sanitizeVerb strips color codes",
            "红色".equals(PlayerActionVerbs.sanitizeVerb("§c红色")));
        check("sanitizeContent clamps to 256",
            PlayerActionVerbs.sanitizeContent("a".repeat(400)).length() == 256);
        check("sanitizeTargetName clamps to 32",
            PlayerActionVerbs.sanitizeTargetName("a".repeat(100)).length() == 32);
        check("8 built-in verbs with matching texts",
            PlayerActionVerbs.IDS.length == 8 && PlayerActionVerbs.TEXTS.length == 8);
        check("scope fromWire public/group/private",
            PlayerActionScope.fromWire("public") == PlayerActionScope.PUBLIC
                && PlayerActionScope.fromWire("GROUP") == PlayerActionScope.GROUP
                && PlayerActionScope.fromWire(" private ") == PlayerActionScope.PRIVATE
                && PlayerActionScope.fromWire("nope") == null);
    }

    private static String clamp(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }

    // ---------------------------------------------------------------- 工具

    private static byte[] drain(ByteBuf raw) {        byte[] out = new byte[raw.readableBytes()];
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
