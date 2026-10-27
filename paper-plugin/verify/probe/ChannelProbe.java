import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.StandardMessenger;

/**
 * Dev probe: run Paper's own channel-name validator against every channel this plugin registers,
 * plus negative controls (no colon / empty suffix / very long), so the report can state the rule
 * from the real implementation instead of guessing.
 *
 * <p>This matters because {@code Messenger.registerIncomingPluginChannel} throws
 * {@code IllegalStateException} for an invalid name -> the plugin would disable itself in onEnable.
 *
 * Run: see ../run-verify.ps1 (its second stage compiles and runs this with paper-api on the classpath).
 */
public final class ChannelProbe {

    public static void main(String[] args) {
        System.out.println("Messenger.MAX_CHANNEL_SIZE = " + Messenger.MAX_CHANNEL_SIZE);
        String[] candidates = {
            // ---- 3.0.x
            "e33chat:quote_sync",
            "e33chat:chat_meta",
            // ---- 3.1.0 群聊 C2S
            "e33chat:group_create",
            "e33chat:group_message",
            "e33chat:group_invite",
            "e33chat:group_invite_respond",
            "e33chat:group_kick",
            "e33chat:group_leave",
            "e33chat:group_rename",
            "e33chat:group_disband",
            // ---- 3.1.0 群聊 S2C
            "e33chat:group_sync",
            "e33chat:group_message_relay",
            "e33chat:group_event",
            "e33chat:group_invite_event",
            // ---- 3.1.0 动作
            "e33chat:player_action",
            "e33chat:player_action_relay",
            // ---- 负面对照
            "e33chat-quote-sync",
            "e33chat:",
            "quote_sync",
            "minecraft:register",
            "e33chat:averyveryverylongchannelname_0123456789"
        };
        int accepted = 0;
        int rejected = 0;
        for (String channel : candidates) {
            try {
                String corrected = StandardMessenger.validateAndCorrectChannel(channel);
                accepted++;
                System.out.println("ACCEPT len=" + channel.length() + "  '" + channel + "'  -> '" + corrected + "'");
            } catch (Throwable t) {
                rejected++;
                System.out.println("REJECT len=" + channel.length() + "  '" + channel + "'  -> "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        System.out.println("accepted=" + accepted + " rejected=" + rejected);
    }
}
