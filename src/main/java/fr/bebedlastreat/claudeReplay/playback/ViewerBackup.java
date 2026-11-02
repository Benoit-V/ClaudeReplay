package fr.bebedlastreat.claudeReplay.playback;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Copy of a viewer's state stored on the player itself, so that a crash during a replay does not lose their
 * inventory, game mode or position: it is restored at their next join.
 */
final class ViewerBackup {

    private static NamespacedKey inventoryKey;
    private static NamespacedKey stateKey;

    private ViewerBackup() {
    }

    static void init(Plugin plugin) {
        inventoryKey = new NamespacedKey(plugin, "viewer_inventory");
        stateKey = new NamespacedKey(plugin, "viewer_state");
    }

    static void save(Player player, ItemStack[] contents, Location location, GameMode gameMode, boolean allowFlight) {
        ItemStack[] items = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            items[i] = contents[i] == null ? ItemStack.empty() : contents[i];
        }
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(inventoryKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(items));
        data.set(stateKey, PersistentDataType.STRING, String.join(";", gameMode.name(), String.valueOf(allowFlight),
                location.getWorld().getName(), String.valueOf(location.getX()), String.valueOf(location.getY()),
                String.valueOf(location.getZ()), String.valueOf(location.getYaw()), String.valueOf(location.getPitch())));
    }

    static void clear(Player player) {
        player.getPersistentDataContainer().remove(inventoryKey);
        player.getPersistentDataContainer().remove(stateKey);
    }

    /** Restores a backup left by a crash. Returns true if there was one. */
    static boolean restoreIfPresent(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        byte[] inventory = data.get(inventoryKey, PersistentDataType.BYTE_ARRAY);
        String state = data.get(stateKey, PersistentDataType.STRING);
        if (inventory == null || state == null) {
            return false;
        }
        player.getInventory().setContents(ItemStack.deserializeItemsFromBytes(inventory));
        String[] parts = state.split(";");
        try {
            player.setGameMode(GameMode.valueOf(parts[0]));
            player.setAllowFlight(Boolean.parseBoolean(parts[1]));
            World world = Bukkit.getWorld(parts[2]);
            if (world != null) {
                player.teleport(new Location(world, Double.parseDouble(parts[3]), Double.parseDouble(parts[4]), Double.parseDouble(parts[5]),
                        Float.parseFloat(parts[6]), Float.parseFloat(parts[7])));
            }
        } catch (RuntimeException ignored) {
            // malformed state: the inventory is what matters
        }
        clear(player);
        return true;
    }
}
