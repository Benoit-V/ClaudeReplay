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
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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
