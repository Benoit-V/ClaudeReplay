package fr.bebedlastreat.claudeReplay.platform.modern;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayWorld;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import fr.bebedlastreat.claudeReplay.platform.Platform;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.papermc.paper.math.Position;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundGroup;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paper 26.x.
 */
public final class ModernPlatform implements Platform {

    private final Plugin plugin;
    private final String namespace;
    private final Map<BlockData, Integer> stateIds = new HashMap<>();
    private final Map<Integer, BlockData> blockData = new HashMap<>();

    public ModernPlatform(Plugin plugin) {
        this.plugin = plugin;
        this.namespace = new NamespacedKey(plugin, "x").getNamespace();
    }

    @Override
    public String minecraftVersion() {
        return Bukkit.getMinecraftVersion();
    }

    @Override
    public List<Listener> listeners(PlatformHooks hooks) {
        return List.of(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onAnimation(PlayerAnimationEvent event) {
                if (event.getAnimationType() == PlayerAnimationType.OFF_ARM_SWING) {
                    hooks.onOffHandSwing(event.getPlayer());
                }
            }

            @EventHandler(priority = EventPriority.LOWEST)
            public void onSwapHands(PlayerSwapHandItemsEvent event) {
                if (hooks.isViewer(event.getPlayer().getUniqueId())) {
                    event.setCancelled(true);
                }
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onEntitiesLoad(EntitiesLoadEvent event) {
                if (hooks.isPlaybackWorld(event.getWorld())) {
                    for (Entity entity : event.getEntities()) {
                        if (!(entity instanceof Player)) {
                            entity.remove();
                        }
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ text

    @Override
    public void sendMessage(CommandSender sender, Component message) {
        sender.sendMessage(message);
    }

    @Override
    public void sendActionBar(Player player, Component message) {
        player.sendActionBar(message);
    }

    // ------------------------------------------------------------------ items and menus

    private static Material material(Icon icon) {
        return switch (icon) {
            case PLAYER_HEAD -> Material.PLAYER_HEAD;
            case NAME_TAG -> Material.NAME_TAG;
            case PAPER -> Material.PAPER;
            case BACKWARD -> Material.RED_DYE;
            case PLAY -> Material.GRAY_DYE;
            case PAUSE -> Material.LIME_DYE;
            case FORWARD -> Material.LIGHT_BLUE_DYE;
            case CLOCK -> Material.CLOCK;
            case BOOK -> Material.BOOK;
            case BARRIER -> Material.BARRIER;
            case ENDER_EYE -> Material.ENDER_EYE;
            case ARROW -> Material.ARROW;
            case REPLAY -> Material.FILLED_MAP;
            case REPLAY_LOCKED -> Material.MAP;
            case COMPASS -> Material.COMPASS;
        };
    }

    @Override
    public ItemStack icon(Icon icon, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material(icon));
        item.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
        });
        return item;
    }

    @Override
    public ItemStack head(UUID owner, String ownerName, Component name, List<Component> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, meta -> {
            meta.setPlayerProfile(Bukkit.createProfile(owner, ownerName));
            meta.displayName(name);
            meta.lore(lore);
        });
        return head;
    }

    @Override
    public Inventory createInventory(InventoryHolder holder, int size, Component title) {
        return Bukkit.createInventory(holder, size, title);
    }

    @Override
    public boolean isEmpty(@Nullable ItemStack item) {
        return item == null || item.getType().isAir();
    }

    @Override
    public boolean isMainHand(PlayerInteractEvent event) {
        return event.getHand() == EquipmentSlot.HAND;
    }

    // ------------------------------------------------------------------ players

    @Override
    public void hidePlayer(Plugin plugin, Player viewer, Player other) {
        viewer.hidePlayer(plugin, other);
    }

    @Override
    public void showPlayer(Plugin plugin, Player viewer, Player other) {
        viewer.showPlayer(plugin, other);
    }

    @Override
    public UserProfile profile(Player player) {
        List<TextureProperty> properties = new ArrayList<>();
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            properties.add(new TextureProperty(property.getName(), property.getValue(), property.getSignature()));
        }
        return new UserProfile(player.getUniqueId(), player.getName(), properties);
    }

    @Override
    public ItemStack[] equipment(Player player) {
        EntityEquipment equipment = player.getEquipment();
        return new ItemStack[]{
                equipment.getItemInMainHand(), equipment.getItemInOffHand(), equipment.getHelmet(),
                equipment.getChestplate(), equipment.getLeggings(), equipment.getBoots()
        };
    }

    @Override
    public List<EntityData<?>> fallbackMetadata(Player player) {
        List<EntityData<?>> data = new ArrayList<>();
        byte flags = 0;
        if (player.getFireTicks() > 0) flags |= 0x01;
        if (player.isSneaking()) flags |= 0x02;
        if (player.isSprinting()) flags |= 0x08;
        if (player.isSwimming()) flags |= 0x10;
        if (player.isInvisible()) flags |= 0x20;
        if (player.isGlowing()) flags |= 0x40;
        if (player.isGliding()) flags |= (byte) 0x80;
        data.add(new EntityData<>(0, EntityDataTypes.BYTE, flags));
        data.add(new EntityData<>(6, EntityDataTypes.ENTITY_POSE, SpigotConversionUtil.fromBukkitPose(player.getPose())));
        int skinIndex = PacketCodec.serverVersion().isNewerThanOrEquals(com.github.retrooper.packetevents.manager.server.ServerVersion.V_1_21_9) ? 16 : 17;
        data.add(new EntityData<>(skinIndex, EntityDataTypes.BYTE, (byte) player.getClientOption(com.destroystokyo.paper.ClientOption.SKIN_PARTS).getRaw()));
        return data;
    }

    @Override
    public Hud createHud(Player player) {
        BossBar bar = BossBar.bossBar(Component.empty(), 0, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        player.showBossBar(bar);
        return new Hud() {
            @Override
            public void update(Component title, float progress, boolean paused) {
                bar.name(title);
                bar.progress(Math.max(0, Math.min(1, progress)));
                bar.color(paused ? BossBar.Color.YELLOW : BossBar.Color.GREEN);
            }

            @Override
            public void remove() {
                player.hideBossBar(bar);
            }
        };
    }

    // ------------------------------------------------------------------ recording

    private int stateId(BlockData data) {
        return stateIds.computeIfAbsent(data, d -> SpigotConversionUtil.fromBukkitBlockData(d).getGlobalId());
    }

    @Override
    public int blockState(Block block) {
        return stateId(block.getBlockData());
    }

    @Override
    public int breakEffectData(Block block) {
        return blockState(block);
    }

    private static @Nullable SoundInfo sound(Block block, boolean step) {
        if (block.getType().isAir()) {
            return null;
        }
        SoundGroup group = block.getBlockData().getSoundGroup();
        Sound sound = step ? group.getStepSound() : group.getPlaceSound();
        return new SoundInfo(Registry.SOUNDS.getKeyOrThrow(sound).asString(), group.getVolume(), group.getPitch());
    }

    @Override
    public @Nullable SoundInfo stepSound(Block block) {
        return sound(block, true);
    }

    @Override
    public @Nullable SoundInfo placeSound(Block block) {
        return sound(block, false);
    }

    @Override
    public List<TabEntry> tabList(Player viewer) {
        List<TabEntry> entries = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != viewer && !viewer.canSee(other)) {
                continue;
            }
            entries.add(new TabEntry(profile(other), viewer.isListed(other), other.getPing(), other.getGameMode(),
                    other.playerListName(), other.getPlayerListOrder()));
        }
        return entries;
    }

    @Override
    public Component @Nullable [] tabHeaderFooter(Player player) {
        Component header = player.playerListHeader();
        Component footer = player.playerListFooter();
        return header == null && footer == null ? null : new Component[]{header, footer};
    }

    @Override
    public BorderInfo border(Player player) {
        org.bukkit.WorldBorder border = player.getWorldBorder() != null ? player.getWorldBorder() : player.getWorld().getWorldBorder();
        return new BorderInfo(border.getCenter().getX(), border.getCenter().getZ(), border.getSize(),
                border.getWarningDistance(), border.getWarningTimeTicks() / 20);
    }

    @Override
    public void resendBossBars(Player player) {
        List<BossBar> bars = new ArrayList<>();
        player.activeBossBars().forEach(bars::add);
        for (BossBar bar : bars) {
            player.hideBossBar(bar);
            player.showBossBar(bar);
        }
    }

    @Override
    public void resendEntity(Plugin plugin, Player player, Entity entity) {
        if (player.canSee(entity)) {
            player.hideEntity(plugin, entity);
            player.showEntity(plugin, entity);
        }
    }

    // ------------------------------------------------------------------ playback worlds

    private Path dimensionsRoot() {
        return Bukkit.getServer().getLevelDirectory().resolve("dimensions").resolve(namespace);
    }

    @Override
    public Path playbackWorldFolder(String id) {
        return dimensionsRoot().resolve(id);
    }

    /** Generates nothing: chunks outside the copied map are empty. */
    private static final class VoidGenerator extends ChunkGenerator {
    }

    @Override
    public World createPlaybackWorld(String id, ReplayWorld info) {
        World created = WorldCreator.ofKey(new NamespacedKey(plugin, id))
                .environment(info.environment())
                .generator(new VoidGenerator())
                .generateStructures(false)
                .forcedSpawnPosition(Position.fine(info.x(), info.y(), info.z()), info.yaw(), info.pitch())
                .createWorld();
        if (created == null) {
            throw new IllegalStateException("Cannot create the replay world " + id);
        }
        return created;
    }

    @Override
    public Path worldFolder(World world) {
        return world.getWorldPath();
    }

    @Override
    public void freezeWorld(World world) {
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.SPAWN_MONSTERS, false);
        world.setGameRule(GameRules.SPAWN_PATROLS, false);
        world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        world.setGameRule(GameRules.SPAWN_WARDENS, false);
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        world.setGameRule(GameRules.MOB_GRIEFING, false);
        world.setGameRule(GameRules.SPREAD_VINES, false);
        world.setGameRule(GameRules.LOCATOR_BAR, false);
    }

    @Override
    public boolean isPlaybackWorld(World world) {
        return world.getKey().getNamespace().equals(namespace);
    }

    @Override
    public List<Path> leftoverPlaybackWorlds() {
        return List.of(dimensionsRoot());
    }

    @Override
    public int getBlock(World world, int x, int y, int z) {
        return stateId(world.getBlockData(x, y, z));
    }

    @Override
    public void setBlock(World world, int x, int y, int z, int state) {
        BlockData data = blockData.computeIfAbsent(state, id -> {
            try {
                return SpigotConversionUtil.toBukkitBlockData(WrappedBlockState.getByGlobalId(PacketCodec.nativeVersion(), id));
            } catch (RuntimeException e) {
                return null;
            }
        });
        if (data != null) {
            world.getBlockAt(x, y, z).setBlockData(data, false);
        }
    }

    @Override
    public int minHeight(World world) {
        return world.getMinHeight();
    }
}
