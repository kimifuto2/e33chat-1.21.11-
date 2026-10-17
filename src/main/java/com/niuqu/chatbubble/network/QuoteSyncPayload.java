package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S：客户端右键「引用」时把被引用消息的信息同步给服务端；服务端在广播
 * {@link ChatMetaPayload} 时带回，让**其他玩家**的客户端也能精确定位原文
 * （之前只有本地 IdentityHashMap，只能精确跳自己发的引用）。
 *
 * <p>两个 hash 语义不同，都不是「整条消息摘要」：
 * <ul>
 *   <li>{@code messageHash}（老字段）：**本次要发送的这条消息**明文内容的
 *       {@link String#hashCode()}。服务端用它把随后广播的 ChatMetaPayload 对回这条
 *       聊天回显。算法＝{@code String.valueOf(content.getString().hashCode())}。</li>
 *   <li>{@code quotedHash}（新字段）：**被引用的那条消息**明文内容的 hashCode，
 *       与 {@code ChatMessageStore.quoteHash(String)} 同一算法（先去掉只影响本地渲染的
 *       § 格式码），因此引用方与被引用方各自算出的值一致。</li>
 * </ul>
 *
 * <p>线格式兼容：新字段追加在**末尾**。旧版解码器只读前三个字符串，尾部多余字节被
 * 原版 {@code CustomPayload} 解码忽略；新版解码器读不到第四个字段时得到空串，
 * 自动退化成「只有内容兜底」。
 */
public record QuoteSyncPayload(String quotedSenderName, String quotedContent,
                               String messageHash, String quotedHash)
        implements CustomPayload {

    public static final CustomPayload.Id<QuoteSyncPayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "quote_sync"));

    public static final PacketCodec<PacketByteBuf, QuoteSyncPayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            buf.writeString(str(value.quotedSenderName));
            buf.writeString(str(value.quotedContent));
            buf.writeString(str(value.messageHash));
            buf.writeString(str(value.quotedHash));
        },
        buf -> {
            try {
                return new QuoteSyncPayload(
                    readStringOrEmpty(buf),
                    readStringOrEmpty(buf),
                    readStringOrEmpty(buf),
                    readStringOrEmpty(buf));
            } catch (Throwable t) {
                // 版本不一致 / 截断：绝不让异常冒到网络层（服务端会直接踢人）
                return new QuoteSyncPayload("", "", "", "");
            }
        }
    );

    private static String str(String s) { return s == null ? "" : s; }

    /** 读不到（旧版只发了 3 个字段）或读损坏时返回空串，不抛异常。 */
    private static String readStringOrEmpty(PacketByteBuf buf) {
        try {
            return buf.isReadable() ? buf.readString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public Id<QuoteSyncPayload> getId() { return ID; }
}
