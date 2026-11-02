package fr.bebedlastreat.claudeReplay.menu;

import fr.bebedlastreat.claudeReplay.platform.Platforms;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Chest menus whose buttons are bound to slots (no item tags: they do not exist on every version).
 */
public final class Menus implements Listener {

    private static Plugin plugin;

    public Menus(Plugin owner) {
        plugin = owner;
    }

    /** A click on a button. */
    public record Click(Player player, boolean right, boolean shift) {
    }

    public static final class Menu implements InventoryHolder {
        private final Inventory inventory;
        private final Map<Integer, Consumer<Click>> actions = new HashMap<>();

        private Menu(int size, Component title) {
            this.inventory = Platforms.get().createInventory(this, size, title);
        }

        public Menu set(int slot, ItemStack item, Consumer<Click> action) {
            inventory.setItem(slot, item);
            if (action == null) {
                actions.remove(slot);
            } else {
                actions.put(slot, action);
            }
            return this;
        }

        public Menu set(int slot, ItemStack item) {
            return set(slot, item, null);
        }

        public void open(Player player) {
            player.openInventory(inventory);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** @param rows 1 to 6 */
    public static Menu create(int rows, Component title) {
        return new Menu(Math.max(1, Math.min(6, rows)) * 9, title);
    }

    /** Rows needed to show {@code count} items. */
    public static int rowsFor(int count) {
        return Math.max(1, Math.min(6, (count + 8) / 9));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= menu.inventory.getSize()) {
            return;
        }
        Consumer<Click> action = menu.actions.get(event.getRawSlot());
        if (action == null) {
            return;
        }
        ClickType type = event.getClick();
        Click click = new Click(player, type.isRightClick(), type.isShiftClick());
        // Opening or closing inventories from the click event itself is not safe: act on the next tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                action.accept(click);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
