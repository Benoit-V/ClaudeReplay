package fr.bebedlastreat.claudeReplay.playback;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

/**
 * Copy of a viewer's state saved in {@code plugins/ClaudeReplay/viewers/<uuid>.yml}, so that a crash during a
 * replay does not lose their inventory, game mode or position: it is restored at their next join.
 */
final class ViewerBackup {

    private static Plugin plugin;
    private static File folder;

    private ViewerBackup() {
    }

    static void init(Plugin owner) {
        plugin = owner;
        folder = new File(owner.getDataFolder(), "viewers");
    }

    private static File file(Player player) {
        return new File(folder, player.getUniqueId() + ".yml");
    }

    static void save(Player player, ItemStack[] contents, Location location, GameMode gameMode, boolean allowFlight) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("inventory", java.util.Arrays.asList(contents));
        yaml.set("game-mode", gameMode.name());
        yaml.set("allow-flight", allowFlight);
        yaml.set("world", location.getWorld().getName());
        yaml.set("x", location.getX());
        yaml.set("y", location.getY());
        yaml.set("z", location.getZ());
        yaml.set("yaw", (double) location.getYaw());
        yaml.set("pitch", (double) location.getPitch());
        try {
            folder.mkdirs();
            yaml.save(file(player));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Cannot save the backup of " + player.getName(), e);
        }
    }

    static void clear(Player player) {
        File file = file(player);
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("Cannot delete " + file);
        }
    }

    /** Restores a backup left by a crash. Returns true if there was one. */
    static boolean restoreIfPresent(Player player) {
        File file = file(player);
        if (!file.isFile()) {
            return false;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        try {
            List<?> items = yaml.getList("inventory");
            if (items != null) {
                ItemStack[] contents = new ItemStack[player.getInventory().getSize()];
                for (int i = 0; i < items.size() && i < contents.length; i++) {
                    contents[i] = items.get(i) instanceof ItemStack item ? item : null;
                }
                player.getInventory().setContents(contents);
            }
            player.setGameMode(GameMode.valueOf(yaml.getString("game-mode", "SURVIVAL")));
            player.setAllowFlight(yaml.getBoolean("allow-flight"));
            World world = Bukkit.getWorld(yaml.getString("world", ""));
            if (world != null) {
                player.teleport(new Location(world, yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                        (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch")));
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Malformed backup of " + player.getName(), e);
        }
        clear(player);
        return true;
    }
}
