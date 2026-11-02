package fr.bebedlastreat.claudeReplay.recording;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;

/**
 * Feeds the self tracks with what cannot be sampled every tick.
 */
public final class RecordingBukkitListener implements Listener {

    private final RecordingManager manager;

    public RecordingBukkitListener(RecordingManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent event) {
        if (manager.isEmpty()) {
            return;
        }
        if (event.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            manager.onSwing(event.getPlayer().getUniqueId(), false);
        } else if (event.getAnimationType() == PlayerAnimationType.OFF_ARM_SWING) {
            manager.onSwing(event.getPlayer().getUniqueId(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!manager.isEmpty()) {
            manager.onBlockBroken(event.getPlayer().getUniqueId(), event.getBlock(), event.getBlock().getBlockData());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!manager.isEmpty()) {
            manager.onBlockPlaced(event.getPlayer().getUniqueId(), event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!manager.isEmpty() && event.getEntity() instanceof Player player && event.getFinalDamage() > 0) {
            manager.onHurt(player.getUniqueId());
        }
    }
}
