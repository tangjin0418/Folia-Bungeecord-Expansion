package com.extendedclip.papi.bungeeexpansion;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.clip.placeholderapi.expansion.Configurable;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Taskable;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class BungeeExpansion extends PlaceholderExpansion implements PluginMessageListener, Taskable, Configurable {

    private static final String MESSAGE_CHANNEL = "BungeeCord";
    private static final String SERVERS_CHANNEL = "GetServers";
    private static final String PLAYERS_CHANNEL = "PlayerCount";
    private static final String CONFIG_INTERVAL = "check_interval";

    private static final Splitter SPLITTER = Splitter.on(",").trimResults();


    private final Map<String, Integer> counts = new HashMap<>();
    private final AtomicReference<ScheduledTask> cached = new AtomicReference<>();

    private static Field inputField;

    static {
        try {
            inputField = Class.forName("com.google.common.io.ByteStreams$ByteArrayDataInputStream")
                              .getDeclaredField("input");
            inputField.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getIdentifier() {
        return "bungee";
    }

    @Override
    public String getAuthor() {
        return "clip";
    }

    @Override
    public String getVersion() {
        return "2.3";
    }

    @Override
    public Map<String, Object> getDefaults() {
        return Collections.singletonMap(CONFIG_INTERVAL, 30);
    }


    @Override
    public String onRequest(final OfflinePlayer player, String identifier) {
        final int value;

        switch (identifier.toLowerCase()) {
            case "all":
            case "total":
                value = counts.values().stream().mapToInt(Integer::intValue).sum();
                break;
            default:
                value = counts.getOrDefault(identifier.toLowerCase(), 0);
                break;
        }

        return String.valueOf(value);
    }

    @Override
    public void start() {
        final ScheduledTask task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(getPlaceholderAPI(), (s) -> {

            if (counts.isEmpty()) {
                sendServersChannelMessage();
            } else {
                counts.keySet().forEach(this::sendPlayersChannelMessage);
            }

        }, 20 * 2, getLong(CONFIG_INTERVAL, 30) * 20);


        final ScheduledTask prev = cached.getAndSet(task);
        if (prev != null) {
            prev.cancel();
        } else {
            Bukkit.getMessenger().registerOutgoingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL);
            Bukkit.getMessenger().registerIncomingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL, this);
        }
    }

    @Override
    public void stop() {
        final ScheduledTask prev = cached.getAndSet(null);
        if (prev == null) {
            return;
        }

        prev.cancel();
        counts.clear();

        Bukkit.getMessenger().unregisterOutgoingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL, this);
    }


    @Override
    public void onPluginMessageReceived(final String channel, final Player player, final byte[] message) {
        if (!MESSAGE_CHANNEL.equals(channel)) {
            return;
        }

        //noinspection UnstableApiUsage
        final ByteArrayDataInput in = ByteStreams.newDataInput(message);
        try {
            DataInputStream stream = (DataInputStream) inputField.get(in);
            switch (in.readUTF()) {
                case PLAYERS_CHANNEL:
                    if (stream.available() == 0) return; // how ?
                    final String server = in.readUTF();
                    if (stream.available() == 0) { // how ? x2
                        getPlaceholderAPI().getLogger()
                                           .log(
                                                   Level.SEVERE,
                                                   String.format(
                                                           "[%s] Could not get the player count from server %s.",
                                                           getName(),
                                                           server
                                                   )
                                           );
                        counts.put(server.toLowerCase(), 0);
                    } else counts.put(server.toLowerCase(), in.readInt());
                    break;
                case SERVERS_CHANNEL:
                    SPLITTER.split(in.readUTF()).forEach(serverName -> counts.putIfAbsent(serverName.toLowerCase(), 0));
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void sendServersChannelMessage() {
        sendMessage(SERVERS_CHANNEL, out -> {
        });
    }

    private void sendPlayersChannelMessage(final String serverName) {
        sendMessage(PLAYERS_CHANNEL, out -> out.writeUTF(serverName));
    }

    private void sendMessage(final String channel, final Consumer<ByteArrayDataOutput> consumer) {
        final Player player = Iterables.getFirst(Bukkit.getOnlinePlayers(), null);
        if (player == null) {
            return;
        }

        //noinspection UnstableApiUsage
        final ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(channel);

        consumer.accept(out);

        player.sendPluginMessage(getPlaceholderAPI(), MESSAGE_CHANNEL, out.toByteArray());
    }

}