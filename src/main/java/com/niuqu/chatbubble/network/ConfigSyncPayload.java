package com.niuqu.chatbubble.network;

import com.niuqu.chatbubble.store.ChatMessageStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client sync of server-side settings (currently: use_tpa). */
public record ConfigSyncPayload(boolean useTpa) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ConfigSyncPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("e33chat", "config_sync"));

    public static final StreamCodec<FriendlyByteBuf, ConfigSyncPayload> CODEC = StreamCodec.ofMember(
        (value, buf) -> buf.writeBoolean(value.useTpa),
        buf -> new ConfigSyncPayload(buf.readBoolean())
    );

    @Override
    public Type<ConfigSyncPayload> type() { return ID; }

    public static void handle(ConfigSyncPayload payload) {
        ChatMessageStore.setServerUseTpa(payload.useTpa());
    }
}
