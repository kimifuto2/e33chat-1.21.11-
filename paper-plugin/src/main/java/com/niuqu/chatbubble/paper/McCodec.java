package com.niuqu.chatbubble.paper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 逐字节复刻 Minecraft 的线格式（{@code PacketByteBuf} 的两类原语：VarInt 与「VarInt 前缀的 UTF-8 字符串」）。
 *
 * <p>Fabric 端用的是 {@code PacketByteBuf.writeString(String)} / {@code readString()}，其 1.21.11 字节码
 * （javap 实测，{@code net.minecraft.network.encoding.StringEncoding}）为：
 * <pre>
 *   encode(buf, chars, maxLength = 32767):
 *       require chars.length() &lt;= maxLength
 *       byteLen = UTF-8 字节数;  require byteLen &lt;= ByteBufUtil.utf8MaxBytes(maxLength) = maxLength * 3
 *       VarInts.write(buf, byteLen);  buf.writeBytes(utf8Bytes)
 *
 *   decode(buf, maxLength = 32767):
 *       byteLen = VarInts.read(buf)
 *       require 0 &lt;= byteLen &lt;= maxLength * 3 且 byteLen &lt;= readableBytes
 *       s = utf8(byteLen 个字节);  require s.length() &lt;= maxLength
 * </pre>
 * 也就是「VarInt(字节长度) + UTF-8 字节」，与旧的 {@code writeUtf/readUtf} 完全等价；
 * VarInt 是 LEB128（每字节 7 位数据 + 最高位继续位，最多 5 字节，超过即 "VarInt too big"）。
 *
 * <p>所有边界检查都是为了 DoS 防护：畸形输入一律抛 {@link IllegalArgumentException}，由调用方丢包。
 */
final class McCodec {

    /** {@code writeString(String)} / {@code readString()} 的默认上限（UTF-16 code unit 数）。 */
    static final int MAX_STR_CHARS = 32767;

    /** 对应的字节上限：{@code ByteBufUtil.utf8MaxBytes(32767)} = 32767 * 3。 */
    static final int MAX_STR_BYTES = MAX_STR_CHARS * 3;

    private McCodec() {
    }

    // ------------------------------------------------------------------ 写

    /** 与 {@code VarInts.write} 完全一致的小端 7 位分组。 */
    static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    static void writeString(ByteArrayOutputStream out, String value) {
        String s = value == null ? "" : value;
        if (s.length() > MAX_STR_CHARS) {
            throw new IllegalArgumentException(
                "string too long: " + s.length() + " chars > " + MAX_STR_CHARS);
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STR_BYTES) {
            throw new IllegalArgumentException(
                "string too long: " + bytes.length + " bytes > " + MAX_STR_BYTES);
        }
        writeVarInt(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /** 与 netty {@code ByteBuf.writeLong} 一致：8 字节**大端**（高字节在前）。 */
    static void writeLong(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift) & 0xFF);
        }
    }

    /**
     * 与 {@code PacketByteBuf.writeUuid(UUID)} 一致：{@code writeLong(msb); writeLong(lsb)}，
     * 共 16 字节（javap 实测 1.21.11 的 {@code PacketByteBuf.writeUuid} 字节码就是这个两条 writeLong）。
     * {@code null} 写成全零 UUID（与 Fabric 侧 {@code GroupWire.writeUuidList} 的兜底同义）。
     */
    static void writeUuid(ByteArrayOutputStream out, UUID value) {
        UUID u = value == null ? new UUID(0, 0) : value;
        writeLong(out, u.getMostSignificantBits());
        writeLong(out, u.getLeastSignificantBits());
    }

    /** 与 netty {@code ByteBuf.writeBoolean} 一致：单字节 0 / 1。 */
    static void writeBoolean(ByteArrayOutputStream out, boolean value) {
        out.write(value ? 1 : 0);
    }

    // ------------------------------------------------------------------ 读

    /** 只读游标。任何越界 / 畸形输入都抛 {@link IllegalArgumentException}。 */
    static final class Reader {

        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        boolean isReadable() {
            return pos < data.length;
        }

        int readVarInt() {
            int result = 0;
            for (int i = 0; i < 5; i++) {
                if (pos >= data.length) {
                    throw new IllegalArgumentException("truncated varint at offset " + pos);
                }
                byte b = data[pos++];
                result |= (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            throw new IllegalArgumentException("varint too big (> 5 bytes)");
        }

        String readString() {
            int byteLen = readVarInt();
            if (byteLen < 0) {
                throw new IllegalArgumentException("negative string length " + byteLen);
            }
            if (byteLen > MAX_STR_BYTES) {
                throw new IllegalArgumentException(
                    "string too long: " + byteLen + " bytes > " + MAX_STR_BYTES);
            }
            if (byteLen > data.length - pos) {
                throw new IllegalArgumentException(
                    "truncated string: want " + byteLen + " bytes, have " + (data.length - pos));
            }
            String s = new String(data, pos, byteLen, StandardCharsets.UTF_8);
            pos += byteLen;
            if (s.length() > MAX_STR_CHARS) {
                throw new IllegalArgumentException(
                    "string too long: " + s.length() + " chars > " + MAX_STR_CHARS);
            }
            return s;
        }

        /** 8 字节大端 long（与 netty {@code readLong} 一致）。 */
        long readLong() {
            if (data.length - pos < 8) {
                throw new IllegalArgumentException(
                    "truncated long: want 8 bytes, have " + (data.length - pos));
            }
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (data[pos++] & 0xFFL);
            }
            return value;
        }

        /** 与 {@code PacketByteBuf.readUuid()} 一致：两个 long。 */
        UUID readUuid() {
            return new UUID(readLong(), readLong());
        }

        /** 与 netty {@code readBoolean} 一致：单字节，非 0 即 true。 */
        boolean readBoolean() {
            if (pos >= data.length) {
                throw new IllegalArgumentException("truncated boolean at offset " + pos);
            }
            return data[pos++] != 0;
        }
    }

    // --------------------------------------------------------------- 日志辅助

    /** 只保留前 40 个字符并把换行压成空格：既不把整段引用原文写进日志，也避免日志注入。 */
    static String preview(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= 40 ? one : one.substring(0, 40) + "...";
    }
}
