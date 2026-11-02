package fr.bebedlastreat.claudeReplay.platform.legacy;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.chat.ChatTypes;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessageLegacy;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayWorld;
import fr.bebedlastreat.claudeReplay.platform.Platform;
import fr.bebedlastreat.claudeReplay.util.ServerTicks;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Spigot 1.8.8 and its forks. What the 1.8 API does not offer is read through the server internals by reflection,
 * with a degraded fallback.
 */
@SuppressWarnings("deprecation") // block ids and data values are the 1.8 way
public final class LegacyPlatform implements Platform {

    private static final String WORLD_PREFIX = "claudereplay_";
    /** Ticks during which a message shown in the action bar is not covered by the HUD. */
    private static final int MESSAGE_TICKS = 60;

    private final Plugin plugin;
    private final Map<UUID, Integer> actionBarMessages = new HashMap<>();
    private boolean entityResendBroken;

    public LegacyPlatform(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String minecraftVersion() {
        String version = Bukkit.getBukkitVersion();
        int dash = version.indexOf('-');
        return dash > 0 ? version.substring(0, dash) : version;
    }

    @Override
    public List<Listener> listeners(PlatformHooks hooks) {
        return List.of();
    }

    // ------------------------------------------------------------------ text

    private static String legacy(Component component) {
        return AdventureSerializer.toLegacyFormat(component);
    }

    @Override
    public void sendMessage(CommandSender sender, Component message) {
        if (sender instanceof Player player) {
            // JSON chat keeps the click and hover events.
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerChatMessage(new ChatMessageLegacy(message, ChatTypes.CHAT)));
        } else {
            sender.sendMessage(legacy(message));
        }
    }

    @Override
    public void sendActionBar(Player player, Component message) {
        actionBarMessages.put(player.getUniqueId(), ServerTicks.current());
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerChatMessage(new ChatMessageLegacy(message, ChatTypes.GAME_INFO)));
    }

    // ------------------------------------------------------------------ items and menus

    private static ItemStack stack(Icon icon) {
        return switch (icon) {
            case PLAYER_HEAD -> new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
            case NAME_TAG -> new ItemStack(Material.NAME_TAG);
            case PAPER -> new ItemStack(Material.PAPER);
            case BACKWARD -> new ItemStack(Material.INK_SACK, 1, (short) 1); // red
            case PLAY -> new ItemStack(Material.INK_SACK, 1, (short) 8); // gray
            case PAUSE -> new ItemStack(Material.INK_SACK, 1, (short) 10); // lime
            case FORWARD -> new ItemStack(Material.INK_SACK, 1, (short) 12); // light blue
            case CLOCK -> new ItemStack(Material.WATCH);
            case BOOK -> new ItemStack(Material.BOOK);
            case BARRIER -> new ItemStack(Material.BARRIER);
            case ENDER_EYE -> new ItemStack(Material.EYE_OF_ENDER);
            case ARROW -> new ItemStack(Material.ARROW);
            case REPLAY -> new ItemStack(Material.MAP);
            case REPLAY_LOCKED -> new ItemStack(Material.EMPTY_MAP);
            case COMPASS -> new ItemStack(Material.COMPASS);
        };
    }

    private static ItemStack named(ItemStack item, Component name, List<Component> lore) {
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(legacy(name));
        List<String> lines = new ArrayList<>(lore.size());
        for (Component line : lore) {
            lines.add(legacy(line));
        }
        meta.setLore(lines);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public ItemStack icon(Icon icon, Component name, List<Component> lore) {
        return named(stack(icon), name, lore);
    }

    @Override
    public ItemStack head(UUID owner, String ownerName, Component name, List<Component> lore) {
        ItemStack head = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwner(ownerName);
        head.setItemMeta(meta);
        return named(head, name, lore);
    }

    @Override
    public Inventory createInventory(InventoryHolder holder, int size, Component title) {
        String text = legacy(title);
        return Bukkit.createInventory(holder, size, text.length() > 32 ? text.substring(0, 32) : text);
    }

    @Override
    public boolean isEmpty(@Nullable ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }

    @Override
    public boolean isMainHand(PlayerInteractEvent event) {
        return true;
    }

    // ------------------------------------------------------------------ players

    @Override
    public void hidePlayer(Plugin plugin, Player viewer, Player other) {
        viewer.hidePlayer(other);
    }

    @Override
    public void showPlayer(Plugin plugin, Player viewer, Player other) {
        viewer.showPlayer(other);
    }

    /** Skin of the player, read from the server's GameProfile (CraftPlayer#getProfile). */
    @Override
    public UserProfile profile(Player player) {
        List<TextureProperty> properties = new ArrayList<>();
        try {
            Object profile = player.getClass().getMethod("getProfile").invoke(player);
            Object propertyMap = profile.getClass().getMethod("getProperties").invoke(profile);
            Collection<?> values = (Collection<?>) propertyMap.getClass().getMethod("values").invoke(propertyMap);
            for (Object property : values) {
                Class<?> type = property.getClass();
                properties.add(new TextureProperty((String) type.getMethod("getName").invoke(property),
                        (String) type.getMethod("getValue").invoke(property), (String) type.getMethod("getSignature").invoke(property)));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // no skin: the player is shown with the default one
        }
        return new UserProfile(player.getUniqueId(), player.getName(), properties);
    }

    @Override
    public ItemStack[] equipment(Player player) {
        PlayerInventory inventory = player.getInventory();
        return new ItemStack[]{
                inventory.getItemInHand(), null, inventory.getHelmet(),
                inventory.getChestplate(), inventory.getLeggings(), inventory.getBoots()
        };
    }

    @Override
    public List<EntityData<?>> fallbackMetadata(Player player) {
        List<EntityData<?>> data = new ArrayList<>();
        byte flags = 0;
        if (player.getFireTicks() > 0) flags |= 0x01;
        if (player.isSneaking()) flags |= 0x02;
        if (player.isSprinting()) flags |= 0x08;
        data.add(new EntityData<>(0, EntityDataTypes.BYTE, flags));
        data.add(new EntityData<>(10, EntityDataTypes.BYTE, (byte) 0x7F)); // every skin layer
        return data;
    }

    /**
     * 1.8 has no boss bar API: the progress goes to the action bar, refreshed every second and left alone for a
     * few seconds after a message.
     */
    @Override
    public Hud createHud(Player player) {
        return new Hud() {
            @Override
            public void update(Component title, float progress, boolean paused) {
                int now = ServerTicks.current();
                Integer message = actionBarMessages.get(player.getUniqueId());
                if ((message != null && now - message < MESSAGE_TICKS) || now % 20 >= 4) {
                    return;
                }
                PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                        new WrapperPlayServerChatMessage(new ChatMessageLegacy(title, ChatTypes.GAME_INFO)));
            }

            @Override
            public void remove() {
                actionBarMessages.remove(player.getUniqueId());
            }
        };
    }

    // ------------------------------------------------------------------ recording

    @Override
    public int blockState(Block block) {
        return (block.getTypeId() << 4) | (block.getData() & 0xF);
    }

    @Override
    public int breakEffectData(Block block) {
        return block.getTypeId() | ((block.getData() & 0xF) << 12);
    }

    /** Sound family of a block (the 1.8 API does not expose them). */
    private static @Nullable String soundFamily(Material material) {
        if (material == Material.AIR) {
            return null;
        }
        String name = material.name();
        if (name.contains("WOOL") || name.contains("CARPET")) {
            return "cloth";
        }
        if (name.contains("LADDER")) {
            return "ladder";
        }
        if (name.contains("SNOW")) {
            return "snow";
        }
        if (name.contains("SAND") && !name.contains("STONE")) {
            return "sand";
        }
        if (name.equals("GRAVEL") || name.equals("SOUL_SAND") || name.equals("CLAY")) {
            return "gravel";
        }
        if (name.contains("GRASS") || name.contains("LEAVES") || name.contains("DIRT") || name.equals("MYCEL")
                || name.contains("SAPLING") || name.equals("HAY_BLOCK") || name.equals("SPONGE") || name.equals("TNT")) {
            return "grass";
        }
        if (name.contains("WOOD") || name.contains("LOG") || name.contains("FENCE") || name.contains("CHEST")
                || name.contains("DOOR") || name.equals("BOOKSHELF") || name.equals("WORKBENCH") || name.contains("SIGN")
                || name.equals("JUKEBOX") || name.equals("NOTE_BLOCK") || name.contains("PUMPKIN")) {
            return "wood";
        }
        return "stone";
    }

    @Override
    public @Nullable SoundInfo stepSound(Block block) {
        String family = soundFamily(block.getType());
        return family == null ? null : new SoundInfo("step." + family, 1, 1);
    }

    @Override
    public @Nullable SoundInfo placeSound(Block block) {
        String family = soundFamily(block.getType());
        return family == null ? null : new SoundInfo("dig." + family, 1, 1);
    }

    @Override
    public List<TabEntry> tabList(Player viewer) {
        List<TabEntry> entries = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != viewer && !viewer.canSee(other)) {
                continue;
            }
            String listName = other.getPlayerListName();
            Component displayName = listName == null || listName.equals(other.getName()) ? null : AdventureSerializer.fromLegacyFormat(listName);
            entries.add(new TabEntry(profile(other), true, PacketEvents.getAPI().getPlayerManager().getPing(other),
                    other.getGameMode(), displayName, 0));
        }
        return entries;
    }

    @Override
    public Component @Nullable [] tabHeaderFooter(Player player) {
        return null; // not readable through the 1.8 API
    }

    @Override
    public BorderInfo border(Player player) {
        WorldBorder border = player.getWorld().getWorldBorder();
        return new BorderInfo(border.getCenter().getX(), border.getCenter().getZ(), border.getSize(),
                border.getWarningDistance(), border.getWarningTime());
    }

    @Override
    public void resendBossBars(Player player) {
        // no boss bars on 1.8 (the wither and dragon bars come with their entity)
    }

    /**
     * Players are hidden and shown again; other entities go through the entity tracker
     * (EntityTrackerEntry#clear then #updatePlayer).
     */
    @Override
    public void resendEntity(Plugin plugin, Player player, Entity entity) {
        if (entity instanceof Player other) {
            player.hidePlayer(other);
            player.showPlayer(other);
            return;
        }
        if (entityResendBroken) {
            return;
        }
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object world = entity.getWorld().getClass().getMethod("getHandle").invoke(entity.getWorld());
            Field trackerField = world.getClass().getField("tracker");
            Object tracker = trackerField.get(world);
            Field entriesField = tracker.getClass().getField("trackedEntities");
            Object entries = entriesField.get(tracker);
            Object entry = entries.getClass().getMethod("get", int.class).invoke(entries, entity.getEntityId());
            if (entry == null) {
                return;
            }
            Method clear = entry.getClass().getMethod("clear", handle.getClass());
            Method update = entry.getClass().getMethod("updatePlayer", handle.getClass());
            clear.invoke(entry, handle);
            update.invoke(entry, handle);
        } catch (ReflectiveOperationException | RuntimeException e) {
            entityResendBroken = true;
            plugin.getLogger().log(Level.WARNING, "Cannot resend the entities to the recorded players: entities spawned "
                    + "before a capture starts will be missing from its replay", e);
        }
    }

    // ------------------------------------------------------------------ playback worlds

    @Override
    public Path playbackWorldFolder(String id) {
        return new File(Bukkit.getWorldContainer(), WORLD_PREFIX + id).toPath();
    }

    /** Generates nothing: chunks outside the copied map are empty. */
    private static final class VoidGenerator extends ChunkGenerator {
        @Override
        public byte[][] generateBlockSections(World world, Random random, int x, int z, BiomeGrid biomes) {
            return new byte[world.getMaxHeight() / 16][];
        }
    }

    @Override
    public World createPlaybackWorld(String id, ReplayWorld info) {
        World created = new WorldCreator(WORLD_PREFIX + id)
                .environment(info.environment())
                .generator(new VoidGenerator())
                .generateStructures(false)
                .createWorld();
        if (created == null) {
            throw new IllegalStateException("Cannot create the replay world " + id);
        }
        created.setSpawnLocation((int) Math.floor(info.x()), (int) Math.floor(info.y()), (int) Math.floor(info.z()));
        created.setKeepSpawnInMemory(false);
        return created;
    }

    @Override
    public Path worldFolder(World world) {
        return world.getWorldFolder().toPath();
    }

    @Override
    public void freezeWorld(World world) {
        world.setGameRuleValue("doDaylightCycle", "false");
        world.setGameRuleValue("doMobSpawning", "false");
        world.setGameRuleValue("randomTickSpeed", "0");
        world.setGameRuleValue("doFireTick", "false");
        world.setGameRuleValue("mobGriefing", "false");
    }

    @Override
    public boolean isPlaybackWorld(World world) {
        return world.getName().startsWith(WORLD_PREFIX);
    }

    @Override
    public List<Path> leftoverPlaybackWorlds() {
        List<Path> folders = new ArrayList<>();
        File[] files = Bukkit.getWorldContainer().listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory() && file.getName().toLowerCase(Locale.ROOT).startsWith(WORLD_PREFIX)) {
                    folders.add(file.toPath());
                }
            }
        }
        return folders;
    }

    @Override
    public int getBlock(World world, int x, int y, int z) {
        return blockState(world.getBlockAt(x, y, z));
    }

    @Override
    public void setBlock(World world, int x, int y, int z, int state) {
        world.getBlockAt(x, y, z).setTypeIdAndData(state >> 4, (byte) (state & 0xF), false);
    }

    @Override
    public int minHeight(World world) {
        return 0;
    }
}
