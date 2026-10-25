package com.niuqu.chatbubble.network;

import com.niuqu.chatbubble.store.ChatMessageStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Server -> client sync of server-side settings (v2: adds message-format templates). */
public record ConfigSyncV2Payload(boolean useTpa, List<String> chatTemplates,
                                  List<String> whisperTemplates, boolean templateDebug)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ConfigSyncV2Payload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "config_sync_v2"));

    public static final StreamCodec<FriendlyByteBuf, ConfigSyncV2Payload> CODEC = StreamCodec.ofMember(
        (value, buf) -> {
            buf.writeBoolean(value.useTpa);
            writeList(buf, value.chatTemplates);
            writeList(buf, value.whisperTemplates);
            buf.writeBoolean(value.templateDebug);
        },
        buf -> new ConfigSyncV2Payload(
            buf.readBoolean(),
            readList(buf),
            readList(buf),
            buf.readBoolean()
        )
    );

    static List<String> readList(FriendlyByteBuf buf) {
        int count = buf.readInt();
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(buf.readUtf());
        return out;
    }

    static void writeList(FriendlyByteBuf buf, List<String> list) {
        buf.writeInt(list.size());
        for (String s : list) buf.writeUtf(s);
    }

    @Override
    public Type<ConfigSyncV2Payload> type() { return ID; }

    public static void handle(ConfigSyncV2Payload payload) {
        ChatMessageStore.setServerConfig(
            payload.useTpa(), payload.chatTemplates(), payload.whisperTemplates(), payload.templateDebug());
    }
}
