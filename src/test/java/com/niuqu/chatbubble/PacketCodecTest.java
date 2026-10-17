package com.niuqu.chatbubble;

import com.niuqu.chatbubble.network.ChatMetaPayload;
import com.niuqu.chatbubble.network.ConfigSyncPayload;
import com.niuqu.chatbubble.network.ConfigSyncV2Payload;
import com.niuqu.chatbubble.network.HistoryPayload;
import com.niuqu.chatbubble.network.MediaCapPayload;
import com.niuqu.chatbubble.network.MediaRequestPayload;
import com.niuqu.chatbubble.network.MediaResponsePayload;
import com.niuqu.chatbubble.network.MediaUploadAckPayload;
import com.niuqu.chatbubble.network.MediaUploadPayload;
import com.niuqu.chatbubble.network.QuoteSyncPayload;
import com.niuqu.chatbubble.network.ServerConfigSavePayload;
import com.niuqu.chatbubble.network.ServerConfigScreenPayload;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for every payload codec - the wire format is a hard
 * contract, so the codec must stay byte-stable.
 * Assertion style: encode -> decode -> encode again and compare bytes, so the
 * tests do not depend on getters that some payloads lack.
 */
class PacketCodecTest {

    private static <T> void assertStable(T payload, PacketCodec<PacketByteBuf, T> codec) {
        PacketByteBuf b1 = new PacketByteBuf(Unpooled.buffer());
        codec.encode(b1, payload);
        byte[] original = Arrays.copyOf(b1.array(), b1.writerIndex());
        T decoded = codec.decode(b1);
        PacketByteBuf b2 = new PacketByteBuf(Unpooled.buffer());
        codec.encode(b2, decoded);
        byte[] after = Arrays.copyOf(b2.array(), b2.writerIndex());
        assertArrayEquals(original, after, "encode(decode(encode(p))) must equal encode(p)");
    }

    @Test void quoteSyncStable() {
        assertStable(new QuoteSyncPayload("Steve", "hello [quote]", "hash123", "quotedHash456"),
            QuoteSyncPayload.CODEC);
    }

    @Test void chatMetaStable() {
        assertStable(new ChatMetaPayload(
                UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
                "Alex", "mh", "Steve", "quoted content", List.of("target1", "target2"),
                "quotedHash456"),
            ChatMetaPayload.CODEC);
    }

    @Test void chatMetaEmptyFieldsStable() {
        assertStable(new ChatMetaPayload(new UUID(0, 0), "X", "h", "", "", List.of(), ""),
            ChatMetaPayload.CODEC);
    }

    /** 旧版服务端只发到 mentionTargets 为止：新解码器必须容忍缺尾部字段（quotedHash 读成空串），
     *  不能抛异常（抛出去在网络层就是直接断开连接）。 */
    @Test void chatMetaDecodesLegacyPayloadWithoutQuotedHash() {
        ChatMetaPayload original = new ChatMetaPayload(new UUID(0, 0), "X", "h", "A", "q",
            List.of("t"), "willBeDropped");
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        // 手工按「旧线格式」写：只到 mentionTargets，不写 quotedHash
        buf.writeString(original.senderUUID().toString());
        buf.writeString(original.senderName());
        buf.writeString(original.messageHash());
        buf.writeString(original.quoteSender());
        buf.writeString(original.quoteContent());
        buf.writeCollection(original.mentionTargets(), PacketByteBuf::writeString);
        ChatMetaPayload decoded = ChatMetaPayload.CODEC.decode(buf);
        assertTrue(decoded.isValid(), "legacy payload must decode instead of throwing");
        assertEquals("", decoded.quotedHash(), "missing trailing field degrades to empty hash");
        assertEquals("q", decoded.quoteContent(), "the fields before it must survive");
    }

    /** 截断/损坏的 meta 必须解成 invalid()（senderUUID=null），由接收端整体忽略。 */
    @Test void chatMetaDecodeTruncatedIsInvalid() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeString("00112233-4455-6677-8899-aabbccddeeff");
        buf.writeString("Alex");
        // 后面全缺：readString 会越界
        ChatMetaPayload decoded = ChatMetaPayload.CODEC.decode(buf);
        assertFalse(decoded.isValid(), "truncated payload must not throw, must be marked invalid");
    }

    @Test void historyStable() {
        assertStable(new HistoryPayload(List.of(
                new HistoryPayload.HistoryEntry(new UUID(1, 1), "Steve", "hi there", 1700000000000L, false, "reply", "sender"),
                new HistoryPayload.HistoryEntry(new UUID(2, 2), "Alex", "system msg", 1700000001000L, true, "", ""))),
            HistoryPayload.CODEC);
    }

    @Test void historyEmptyStable() {
        assertStable(new HistoryPayload(List.of()),
            HistoryPayload.CODEC);
    }

    @Test void configSyncStable() {
        assertStable(new ConfigSyncPayload(true),
            ConfigSyncPayload.CODEC);
    }

    @Test void configSyncV2Stable() {
        assertStable(new ConfigSyncV2Payload(true,
                List.of("{name}: {content}", "{prefix}{name} >> {content}"), List.of("{sender} -> {content}"), false),
            ConfigSyncV2Payload.CODEC);
    }

    @Test void mediaCapStable() {
        assertStable(new MediaCapPayload(true),
            MediaCapPayload.CODEC);
    }

    @Test void mediaRequestStable() {
        assertStable(new MediaRequestPayload("0123456789abcdef0123456789abcdef"),
            MediaRequestPayload.CODEC);
    }

    @Test void mediaResponseStable() {
        assertStable(new MediaResponsePayload("abc", 2, 5, new byte[]{1, 2, 3}),
            MediaResponsePayload.CODEC);
    }

    @Test void mediaUploadAckOkStable() {
        assertStable(new MediaUploadAckPayload(42L, "mediaid", null),
            MediaUploadAckPayload.CODEC);
    }

    @Test void mediaUploadAckErrorStable() {
        assertStable(new MediaUploadAckPayload(7L, null, "too large"),
            MediaUploadAckPayload.CODEC);
    }

    @Test void mediaUploadStable() {
        assertStable(new MediaUploadPayload(99L, 0, 3, 3000, "image/png", new byte[]{9, 8, 7}),
            MediaUploadPayload.CODEC);
    }

    @Test void serverConfigScreenStable() {
        assertStable(new ServerConfigScreenPayload(true, false, true, true, true,
                List.of("chat tpl"), List.of("whisper tpl")),
            ServerConfigScreenPayload.CODEC);
    }

    @Test void serverConfigSaveStable() {
        assertStable(new ServerConfigSavePayload(false, true, false, false, true,
                List.of(), List.of("w")),
            ServerConfigSavePayload.CODEC);
    }
}
