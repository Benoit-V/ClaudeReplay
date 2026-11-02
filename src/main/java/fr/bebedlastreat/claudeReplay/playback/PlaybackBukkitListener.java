package fr.bebedlastreat.claudeReplay.playback;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
        // The server stopped while this player was watching a replay.
        ViewerBackup.restoreIfPresent(event.getPlayer());
        for (PlaybackSessionImpl session : manager.getSessions()) {
            session.hideFromViewers(event.getPlayer());
        }
    }

    // ------------------------------------------------------------------ control items

    private final Map<UUID, Integer> lastUse = new HashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Optional<PlaybackSessionImpl> session = manager.getSession(player.getUniqueId());
        if (session.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() == Action.PHYSICAL) {
            return;
        }
        ControlItems.Action action = ControlItems.actionOf(event.getItem());
        if (action == null) {
            return;
        }
        // The client may send several interactions for one click.
        int tick = Bukkit.getCurrentTick();
        Integer last = lastUse.put(player.getUniqueId(), tick);
        if (last != null && tick - last < 3) {
            return;
        }
        boolean rightClick = event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (action == ControlItems.Action.POINT_OF_VIEW && !rightClick) {
            return;
        }
        try {
            ControlItems.use(player, session.get(), action, rightClick);
        } catch (IllegalArgumentException | IllegalStateException e) {
            player.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !manager.isViewer(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        if (event.getView().getTopInventory().getHolder() instanceof ControlItems.Menu menu && event.getClickedInventory() == event.getView().getTopInventory()) {
            ControlItems.clickMenu(player, menu, event.getCurrentItem());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (manager.isViewer(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (manager.isViewer(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (manager.isViewer(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (manager.isViewer(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (manager.isViewer(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && manager.isViewer(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHunger(FoodLevelChangeEvent event) {
        if (manager.isViewer(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
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
