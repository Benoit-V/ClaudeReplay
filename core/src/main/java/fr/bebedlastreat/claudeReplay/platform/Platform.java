package fr.bebedlastreat.claudeReplay.platform;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayWorld;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Everything that differs between the supported server versions. The common code is compiled against the oldest
 * supported API (1.8.8) and goes through this interface for the rest.
 */
public interface Platform {

    /** Human readable version of the server ("1.8.8", "26.3"...). */
    String minecraftVersion();

    /** Listeners for events that only exist on this version (registered with the common ones). */
    List<Listener> listeners(PlatformHooks hooks);

    // ------------------------------------------------------------------ text

    void sendMessage(CommandSender sender, Component message);

    void sendActionBar(Player player, Component message);

    // ------------------------------------------------------------------ items and menus

    ItemStack icon(Icon icon, Component name, List<Component> lore);

    ItemStack head(UUID owner, String ownerName, Component name, List<Component> lore);

    Inventory createInventory(InventoryHolder holder, int size, Component title);

    boolean isEmpty(@Nullable ItemStack item);

    /** False for the off hand interactions (always true on versions without off hand). */
    boolean isMainHand(PlayerInteractEvent event);

    // ------------------------------------------------------------------ players

    void hidePlayer(Plugin plugin, Player viewer, Player other);

    void showPlayer(Plugin plugin, Player viewer, Player other);

    UserProfile profile(Player player);

    /** Main hand, off hand, helmet, chestplate, leggings, boots (null when the slot does not exist). */
    ItemStack[] equipment(Player player);

    /** Entity data of a player built from the Bukkit API, when the server data cannot be read. */
    List<EntityData<?>> fallbackMetadata(Player player);

    /** A viewer's progress bar: a boss bar when the version has them. */
    Hud createHud(Player player);

    // ------------------------------------------------------------------ recording

    /** Global block state id (PacketEvents, server protocol) of a block. */
    int blockState(Block block);

    /** Data of the block break level event (2001) for a block about to be broken. */
    int breakEffectData(Block block);

    /** Sound played when stepping on this block, null for none. */
    @Nullable SoundInfo stepSound(Block block);

    /** Sound played when this block is placed, null for none. */
    @Nullable SoundInfo placeSound(Block block);

    /** What a player sees in their tab list, for the resynchronisation at the start of a capture. */
    List<TabEntry> tabList(Player viewer);

    /** Tab list header and footer of a player, null when unknown. */
    @Nullable Component[] tabHeaderFooter(Player player);

    /** World border seen by a player. */
    BorderInfo border(Player player);

    /** Makes the server send the boss bars of a player again. */
    void resendBossBars(Player player);

    /** Makes the server send the whole state of an entity to a player again. */
    void resendEntity(Plugin plugin, Player player, Entity entity);

    // ------------------------------------------------------------------ playback worlds

    /** Folder where the map of a playback world must be copied before {@link #createPlaybackWorld}. */
    Path playbackWorldFolder(String id);

    World createPlaybackWorld(String id, ReplayWorld info) throws IOException;

    Path worldFolder(World world);

    /** Stops time, weather, mob spawning... */
    void freezeWorld(World world);

    boolean isPlaybackWorld(World world);

    /** Folders of playback worlds left by a crash, deleted at startup (the worlds are not loaded). */
    List<Path> leftoverPlaybackWorlds();

    /** Global block state id (server protocol) at a position. */
    int getBlock(World world, int x, int y, int z);

    /** Sets a block from its global state id (server protocol), without physics. */
    void setBlock(World world, int x, int y, int z, int state);

    int minHeight(World world);

    // ------------------------------------------------------------------ types

    enum Icon {
        PLAYER_HEAD, NAME_TAG, PAPER, BACKWARD, PLAY, PAUSE, FORWARD, CLOCK, BOOK, BARRIER, ENDER_EYE, ARROW,
        REPLAY, REPLAY_LOCKED, COMPASS
    }

    /** @param name sound key ("block.stone.step" or "step.stone" on old versions) */
    record SoundInfo(String name, float volume, float pitch) {
    }

    record TabEntry(UserProfile profile, boolean listed, int ping, org.bukkit.GameMode gameMode,
                    @Nullable Component displayName, int listOrder) {
    }

    record BorderInfo(double centerX, double centerZ, double size, int warningBlocks, int warningSeconds) {
    }

    interface Hud {
        void update(Component title, float progress, boolean paused);

        void remove();
    }

    /** What the version specific listeners call back into. */
    interface PlatformHooks {
        boolean isViewer(UUID player);

        boolean isPlaybackWorld(World world);

        void onOffHandSwing(Player player);
    }
}
