package com.niuqu.chatbubble.paper;

/**
 * C2S {@code e33chat:quote_sync} 的解码器。
 *
 * <p>字段顺序与 Fabric 侧 {@code QuoteSyncPayload.CODEC} 的编码器逐字段一致：
 * <pre>
 *   #  字段             类型                            编码
 *   1  quotedSenderName string（被引用消息的发送者名）   VarInt(UTF-8 字节数) + UTF-8
 *   2  quotedContent    string（被引用消息的正文）       同上
 *   3  messageHash      string（本次消息的 hashCode 串） 同上
 *   4  quotedHash       string（被引用消息的 hashCode）  同上（旧客户端不发 → 读成 ""）
 * </pre>
 *
 * @param quotedSenderName 被引用消息的发送者名
 * @param quotedContent    被引用消息的正文
 * @param messageHash      本次要发出的那条消息的明文 {@link String#hashCode()} 十进制串
 * @param quotedHash       被引用消息的明文 hashCode（服务端只转发，不解释）
 */
record QuoteSyncPayload(String quotedSenderName, String quotedContent, String messageHash,
                        String quotedHash) {

    /**
     * 解码整条 payload。
     *
     * <p>版本兼容策略与 Fabric 版一致：**尾部读不到**的字段退回空串（旧版客户端只发 3 个字段）；
     * 但字段**读到一半被截断**时直接抛 {@link IllegalArgumentException}，由调用方丢弃整包 ——
     * 半截数据没有转发价值，转发出去只会让所有客户端解码失败。
     */
    static QuoteSyncPayload decode(byte[] message) {
        McCodec.Reader in = new McCodec.Reader(message);
        return new QuoteSyncPayload(
            readStringOrEmpty(in),
            readStringOrEmpty(in),
            readStringOrEmpty(in),
            readStringOrEmpty(in));
    }

    private static String readStringOrEmpty(McCodec.Reader in) {
        return in.isReadable() ? in.readString() : "";
    }
}
