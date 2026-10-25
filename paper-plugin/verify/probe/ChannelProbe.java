import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.StandardMessenger;

/**
 * Dev probe: run Paper's own channel-name validator against the two channel names this plugin uses,
 * plus negative controls (no colon / empty suffix / very long), so the report can state the rule
 * from the real implementation instead of guessing.
 *
 * Run: see ../run-verify.ps1 (or compile with paper-api on the classpath and run).
 */
public final class ChannelProbe {

    public static void main(String[] args) {
        System.out.println("Messenger.MAX_CHANNEL_SIZE = " + Messenger.MAX_CHANNEL_SIZE);
        String[] candidates = {
            "e33chat:quote_sync",
            "e33chat:chat_meta",
            "e33chat-quote-sync",
            "e33chat:",
            "quote_sync",
            "minecraft:register",
            "e33chat:averyveryverylongchannelname_0123456789"
        };
        for (String channel : candidates) {
            try {
                String corrected = StandardMessenger.validateAndCorrectChannel(channel);
                System.out.println("ACCEPT len=" + channel.length() + "  '" + channel + "'  -> '" + corrected + "'");
            } catch (Throwable t) {
                System.out.println("REJECT len=" + channel.length() + "  '" + channel + "'  -> "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }
}
