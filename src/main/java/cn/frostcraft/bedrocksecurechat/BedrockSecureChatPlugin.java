package cn.frostcraft.bedrocksecurechat;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.geysermc.geyser.api.GeyserApi;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relays normal chat packets from Geyser players as unsigned Paper chat events.
 * Incoming packets are deliberately NOT cancelled: vanilla must still receive
 * the chat acknowledgement data carried by the original packet.
 */
public final class BedrockSecureChatPlugin extends JavaPlugin {

    private final Map<UUID, Long> lastRelayedAt = new ConcurrentHashMap<>();

    private PacketListenerAbstract packetListener;
    private boolean enabled;
    private int maxMessageLength;
    private long cooldownMillis;
    private boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocalSettings();

        // Geyser prints an informational warning to Bedrock players when secure
        // profile enforcement is enabled. This plugin provides the missing chat
        // relay, so suppress that now-misleading Geyser notice when configured.
        if (getConfig().getBoolean("suppress-geyser-secure-chat-warning", true)) {
            System.setProperty("Geyser.PrintSecureChatInformation", "false");
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            getLogger().severe("PacketEvents is missing or not enabled. Disabling BedrockSecureChat.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot")) {
            getLogger().severe("Geyser-Spigot is missing or not enabled. Disabling BedrockSecureChat.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (!enabled) {
            getLogger().info("Disabled by config.yml.");
            return;
        }

        if (!isSecureProfileEnforced()) {
            getLogger().info("enforce-secure-profile=false; BedrockSecureChat will stay idle to avoid duplicate chat.");
            return;
        }

        packetListener = new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() != PacketType.Play.Client.CHAT_MESSAGE) {
                    return;
                }

                UUID playerId = event.getUser().getUUID();
                if (playerId == null) {
                    return;
                }

                final String message;
                try {
                    message = new WrapperPlayClientChatMessage(event).getMessage();
                } catch (RuntimeException exception) {
                    if (debug) {
                        getLogger().warning("Could not read an incoming chat packet: " + exception.getMessage());
                    }
                    return;
                }

                if (message == null || message.isBlank()) {
                    return;
                }

                // Geyser Bedrock chat is relayed below as an unsigned system-style
                // Paper chat event. Cancel the original player-chat packet so vanilla
                // does not reject its missing Mojang profile signature and send the
                // misleading "missing profile public key" warning to the Bedrock client.
                // Chat acknowledgements use separate packets and are not cancelled.
                if (!isGeyserPlayer(playerId)) {
                    return;
                }

                event.setCancelled(true);
                Bukkit.getScheduler().runTask(BedrockSecureChatPlugin.this,
                        () -> relayIfBedrock(playerId, message));
            }
        };

        PacketEvents.getAPI().getEventManager().registerListener(packetListener);
        getLogger().info("Enabled. Geyser Bedrock chat will be relayed while enforce-secure-profile stays enabled.");
    }

    @Override
    public void onDisable() {
        if (packetListener != null && PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            packetListener = null;
        }
        lastRelayedAt.clear();
    }

    private void reloadLocalSettings() {
        reloadConfig();
        enabled = getConfig().getBoolean("enabled", true);
        maxMessageLength = Math.max(1, getConfig().getInt("max-message-length", 256));
        cooldownMillis = Math.max(0L, getConfig().getLong("cooldown-ms", 0L));
        debug = getConfig().getBoolean("debug", false);
    }

    private boolean isSecureProfileEnforced() {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream("server.properties")) {
            properties.load(input);
            return Boolean.parseBoolean(properties.getProperty("enforce-secure-profile", "true"));
        } catch (IOException exception) {
            // If the file cannot be read, assume the secure-profile default is enabled.
            // This avoids silently disabling the feature on installations where the
            // server properties file is managed outside the plugin's visibility.
            getLogger().warning("Could not read server.properties; assuming enforce-secure-profile=true: "
                    + exception.getMessage());
            return true;
        }
    }

    private void relayIfBedrock(UUID playerId, String message) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        if (message.length() > maxMessageLength) {
            if (debug) {
                getLogger().warning("Ignored an overlength Bedrock chat message from " + player.getName());
            }
            return;
        }

        if (cooldownMillis > 0L) {
            long now = System.currentTimeMillis();
            Long previous = lastRelayedAt.get(playerId);
            if (previous != null && now - previous < cooldownMillis) {
                return;
            }
            lastRelayedAt.put(playerId, now);
        }

        dispatchUnsignedChatEvent(player, message);
    }

    private boolean isGeyserPlayer(UUID playerId) {
        try {
            GeyserApi geyserApi = GeyserApi.api();
            return geyserApi != null && geyserApi.isBedrockPlayer(playerId);
        } catch (Throwable throwable) {
            if (debug) {
                getLogger().warning("Unable to query Geyser API for " + playerId + ": " + throwable.getMessage());
            }
            return false;
        }
    }

    /**
     * Fires Paper's modern chat event and then applies its renderer/viewer list.
     * The SignedMessage is explicitly marked as a system/unsigned message; no
     * fake Mojang signature is created or attached.
     */
    @SuppressWarnings("UnstableApiUsage")
    private void dispatchUnsignedChatEvent(Player sender, String rawMessage) {
        Component original = Component.text(rawMessage);
        Set<Audience> viewers = new HashSet<>(Bukkit.getOnlinePlayers());

        AsyncChatEvent chatEvent = new AsyncChatEvent(
                false,
                sender,
                viewers,
                ChatRenderer.defaultRenderer(),
                original,
                original,
                SignedMessage.system(rawMessage, original)
        );

        Bukkit.getPluginManager().callEvent(chatEvent);

        if (chatEvent.isCancelled()) {
            if (debug) {
                getLogger().info("A chat listener cancelled a relayed message from " + sender.getName());
            }
            return;
        }

        Component message = chatEvent.message();
        var renderer = chatEvent.renderer();
        Component displayName = sender.displayName();

        for (Audience viewer : Set.copyOf(chatEvent.viewers())) {
            try {
                Component rendered = renderer.render(sender, displayName, message, viewer);
                viewer.sendMessage(rendered);
            } catch (RuntimeException exception) {
                getLogger().warning("A chat renderer failed while relaying a Bedrock message: "
                        + exception.getMessage());
            }
        }

        // Match normal server chat visibility in console logs without writing
        // player input to a command dispatcher.
        getLogger().info("<" + sender.getName() + "> " + rawMessage);

        if (debug) {
            getLogger().info("Relayed a Bedrock chat message from " + sender.getName());
        }
    }
}
