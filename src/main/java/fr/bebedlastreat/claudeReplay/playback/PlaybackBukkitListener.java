package fr.bebedlastreat.claudeReplay.playback;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Freezes the playback worlds (only the replay changes them) and follows the viewers.
 */
public final class PlaybackBukkitListener implements Listener {

    private final PlaybackManager manager;

    public PlaybackBukkitListener(PlaybackManager manager) {
        this.manager = manager;
    }

    // ------------------------------------------------------------------ viewers

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        for (PlaybackSessionImpl session : manager.getSessions()) {
            session.hideFromViewers(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        manager.getSession(player.getUniqueId()).ifPresent(session -> session.removeViewer(player, true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        World to = event.getTo().getWorld();
        manager.getSession(player.getUniqueId()).ifPresent(session -> {
            if (to != null && !session.isWorldOfSession(to)) {
                // Sent elsewhere by another plugin or a command: they left the replay.
                session.removeViewer(player, false);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (!event.isSneaking()) {
            return;
        }
        manager.getSession(player.getUniqueId()).ifPresent(session -> {
            if (session.getPointOfView(player).isPresent()) {
                session.setPointOfView(player, null);
            }
        });
    }

    // ------------------------------------------------------------------ frozen worlds

    private void freeze(World world, Cancellable event) {
        if (manager.isPlaybackWorld(world)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhysics(BlockPhysicsEvent event) {
        freeze(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFlow(BlockFromToEvent event) {
        freeze(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onForm(BlockFormEvent event) {
        freeze(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpread(BlockSpreadEvent event) {
        freeze(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFade(BlockFadeEvent event) {
        freeze(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onGrow(BlockGrowEvent event) {
        freeze(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onStructureGrow(StructureGrowEvent event) {
        freeze(event.getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDecay(LeavesDecayEvent event) {
        freeze(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBurn(BlockBurnEvent event) {
        freeze(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onIgnite(BlockIgniteEvent event) {
        freeze(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWeather(WeatherChangeEvent event) {
        if (event.toWeatherState()) {
            freeze(event.getWorld(), event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onThunder(ThunderChangeEvent event) {
        if (event.toThunderState()) {
            freeze(event.getWorld(), event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            freeze(event.getLocation().getWorld(), event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (manager.isPlaybackWorld(event.getWorld())) {
            for (Entity entity : event.getEntities()) {
                if (!(entity instanceof Player)) {
                    entity.remove();
                }
            }
        }
    }
}
