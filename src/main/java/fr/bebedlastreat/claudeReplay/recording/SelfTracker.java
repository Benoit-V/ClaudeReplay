package fr.bebedlastreat.claudeReplay.recording;

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import com.github.retrooper.packetevents.protocol.sound.Sounds;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundGroup;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Generates the packets describing a recorded player's own entity, which the player never receives himself:
 * spawn, movements, look, entity data, equipment, swings, damage and death.
 * Main thread only.
 */
final class SelfTracker {

    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HELMET,
            EquipmentSlot.CHEST_PLATE, EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS
    };

    private final RecordingImpl recording;
    private final Player player;
    private final int subject;

    private int entityId;
    private boolean spawned;
    private boolean dead;
    private double x, y, z;
    private float yaw, pitch;
    private byte[] lastMetadata;
    private final org.bukkit.inventory.ItemStack[] lastEquipment = new org.bukkit.inventory.ItemStack[SLOTS.length];
    private GameMode lastGameMode;
    private double walked;
    private int nextStep = 1;

    SelfTracker(RecordingImpl recording, Player player, int subject) {
        this.recording = recording;
        this.player = player;
        this.subject = subject;
    }

    private void add(int tick, Server type, PacketWrapper<?> wrapper) {
        recording.addSelf(tick, subject, type, PacketCodec.encodeNew(wrapper));
    }

    void spawn(int tick) {
        entityId = player.getEntityId();
        Location location = player.getLocation();
        x = location.getX();
        y = location.getY();
        z = location.getZ();
        yaw = location.getYaw();
        pitch = location.getPitch();
        lastGameMode = player.getGameMode();

        // The profile must be known by the client before the entity is spawned (skin). ADD_PLAYER never
        // overrides an existing tab entry, so it does not conflict with the real tab list.
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                PlayerPackets.profile(player), false, player.getPing(),
                SpigotConversionUtil.fromBukkitGameMode(lastGameMode), null, null);
        add(tick, Server.PLAYER_INFO_UPDATE, new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE), info));
        add(tick, Server.SPAWN_ENTITY, new WrapperPlayServerSpawnEntity(entityId, Optional.of(player.getUniqueId()),
                EntityTypes.PLAYER, new Vector3d(x, y, z), pitch, yaw, yaw, 0, Optional.empty()));

        lastMetadata = null;
        Arrays.fill(lastEquipment, null);
        sampleMetadata(tick);
        sampleEquipment(tick, true);
        spawned = true;
        dead = player.isDead();
    }

    void despawn(int tick) {
        if (!spawned) {
            return;
        }
        spawned = false;
        add(tick, Server.DESTROY_ENTITIES, new WrapperPlayServerDestroyEntities(entityId));
    }

    void sample(int tick) {
        if (!spawned) {
            return;
        }
        if (player.isDead()) {
            if (!dead) {
                dead = true;
                sampleMetadata(tick);
                add(tick, Server.ENTITY_STATUS, new WrapperPlayServerEntityStatus(entityId, 3)); // death animation
            }
            return;
        }
        if (dead || player.getEntityId() != entityId) {
            // Respawned: the client of the other players sees a brand new entity.
            despawn(tick);
            spawn(tick);
            return;
        }

        Location location = player.getLocation();
        boolean moved = location.getX() != x || location.getY() != y || location.getZ() != z;
        boolean rotated = location.getYaw() != yaw || location.getPitch() != pitch;
        if ((moved || rotated) && !player.isInsideVehicle()) {
            add(tick, Server.ENTITY_TELEPORT, new WrapperPlayServerEntityTeleport(entityId,
                    new Vector3d(location.getX(), location.getY(), location.getZ()), location.getYaw(), location.getPitch(),
                    player.isOnGround()));
        }
        if (location.getYaw() != yaw) {
            add(tick, Server.ENTITY_HEAD_LOOK, new WrapperPlayServerEntityHeadLook(entityId, location.getYaw()));
        }
        footsteps(tick, location);
        x = location.getX();
        y = location.getY();
        z = location.getZ();
        yaw = location.getYaw();
        pitch = location.getPitch();

        sampleMetadata(tick);
        sampleEquipment(tick, false);

        GameMode gameMode = player.getGameMode();
        if (gameMode != lastGameMode) {
            lastGameMode = gameMode;
            WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                    PlayerPackets.profile(player), true, player.getPing(), SpigotConversionUtil.fromBukkitGameMode(gameMode), null, null);
            add(tick, Server.PLAYER_INFO_UPDATE, new WrapperPlayServerPlayerInfoUpdate(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE, info));
        }
    }

    // ------------------------------------------------------------------ own sounds
    // The server never sends a player the sounds of their own actions: their client plays them locally.
    // They are stored as packets received by the player (so they are merged with the identical packets received
    // by the players around).

    private void addSound(int tick, Server type, PacketWrapper<?> wrapper) {
        recording.addPacket(tick, subject, type, PacketCodec.encodeNew(wrapper));
    }

    private static com.github.retrooper.packetevents.protocol.sound.Sound sound(Sound sound) {
        return Sounds.getByNameOrCreate(Registry.SOUNDS.getKeyOrThrow(sound).asString());
    }

    /** Same rhythm as vanilla: a step every ~1.67 blocks walked on the ground, none when sneaking. */
    private void footsteps(int tick, Location location) {
        double dx = location.getX() - x;
        double dz = location.getZ() - z;
        if (!player.isOnGround() || player.isSneaking() || player.isFlying() || player.isInsideVehicle()) {
            return;
        }
        walked += Math.sqrt(dx * dx + dz * dz) * 0.6;
        if (walked <= nextStep) {
            return;
        }
        nextStep = (int) walked + 1;
        Block below = location.clone().subtract(0, 0.2, 0).getBlock();
        if (below.getType().isAir()) {
            return;
        }
        SoundGroup group = below.getBlockData().getSoundGroup();
        addSound(tick, Server.SOUND_EFFECT, new WrapperPlayServerSoundEffect(sound(group.getStepSound()), SoundCategory.PLAYER,
                new Vector3d(location.getX(), location.getY(), location.getZ()), group.getVolume() * 0.15f, group.getPitch()));
    }

    void blockBroken(int tick, Block block, BlockData data) {
        // Level event 2001: break particles and sound.
        addSound(tick, Server.EFFECT, new WrapperPlayServerEffect(2001, new Vector3i(block.getX(), block.getY(), block.getZ()),
                SpigotConversionUtil.fromBukkitBlockData(data).getGlobalId(), false));
    }

    void blockPlaced(int tick, Block block) {
        SoundGroup group = block.getBlockData().getSoundGroup();
        addSound(tick, Server.SOUND_EFFECT, new WrapperPlayServerSoundEffect(sound(group.getPlaceSound()), SoundCategory.BLOCK,
                new Vector3d(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5), (group.getVolume() + 1) / 2, group.getPitch() * 0.8f));
    }

    private void sampleMetadata(int tick) {
        byte[] metadata = PacketCodec.encodeNew(new WrapperPlayServerEntityMetadata(entityId, PlayerPackets.metadata(player)));
        if (!Arrays.equals(metadata, lastMetadata)) {
            lastMetadata = metadata;
            recording.addSelf(tick, subject, Server.ENTITY_METADATA, metadata);
        }
    }

    private void sampleEquipment(int tick, boolean force) {
        EntityEquipment equipment = player.getEquipment();
        org.bukkit.inventory.ItemStack[] current = {
                equipment.getItemInMainHand(), equipment.getItemInOffHand(), equipment.getHelmet(),
                equipment.getChestplate(), equipment.getLeggings(), equipment.getBoots()
        };
        boolean changed = force;
        for (int i = 0; i < SLOTS.length; i++) {
            if (!Objects.equals(current[i], lastEquipment[i])) {
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        List<Equipment> list = new ArrayList<>(SLOTS.length);
        for (int i = 0; i < SLOTS.length; i++) {
            org.bukkit.inventory.ItemStack item = current[i];
            lastEquipment[i] = item == null ? null : item.clone();
            ItemStack converted = item == null || item.getType() == Material.AIR ? ItemStack.EMPTY : SpigotConversionUtil.fromBukkitItemStack(item);
            list.add(new Equipment(SLOTS[i], converted));
        }
        add(tick, Server.ENTITY_EQUIPMENT, new WrapperPlayServerEntityEquipment(entityId, list));
    }

    void swing(int tick, boolean offHand) {
        if (spawned) {
            add(tick, Server.ENTITY_ANIMATION, new WrapperPlayServerEntityAnimation(entityId, offHand
                    ? WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_OFF_HAND
                    : WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM));
        }
    }

    void hurt(int tick) {
        if (spawned) {
            add(tick, Server.HURT_ANIMATION, new WrapperPlayServerHurtAnimation(entityId, 0));
        }
    }
}
