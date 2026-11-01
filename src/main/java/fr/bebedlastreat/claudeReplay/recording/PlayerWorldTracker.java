package fr.bebedlastreat.claudeReplay.recording;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread safe view of the world each player is in, readable from the network threads.
 */
public final class PlayerWorldTracker implements Listener {

    private final Map<UUID, UUID> worlds = new ConcurrentHashMap<>();

    public void init() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            worlds.put(player.getUniqueId(), player.getWorld().getUID());
        }
    }

    public UUID getWorld(UUID player) {
        return worlds.get(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        worlds.put(event.getPlayer().getUniqueId(), event.getPlayer().getWorld().getUID());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        worlds.put(event.getPlayer().getUniqueId(), event.getPlayer().getWorld().getUID());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        worlds.remove(event.getPlayer().getUniqueId());
    }
}
