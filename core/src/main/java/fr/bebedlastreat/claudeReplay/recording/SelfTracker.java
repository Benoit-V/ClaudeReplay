package fr.bebedlastreat.claudeReplay.recording;

import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import com.github.retrooper.packetevents.protocol.sound.Sounds;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import fr.bebedlastreat.claudeReplay.packet.Swings;
import fr.bebedlastreat.claudeReplay.platform.Platform;
import fr.bebedlastreat.claudeReplay.platform.Platforms;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
    private final Platform platform = Platforms.get();

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

    private void add(int tick, PlayerPackets.Built packet) {
        recording.addSelf(tick, subject, packet.type(), packet.payload());
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

        // The profile must be known by the client before the entity is spawned (skin).
        add(tick, PlayerPackets.profile(player));
        add(tick, PlayerPackets.spawn(entityId, player.getUniqueId(), new Vector3d(x, y, z), yaw, pitch, PlayerPackets.metadata(player)));

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
            add(tick, PlayerPackets.gameMode(player, gameMode));
        }
    }

    // ------------------------------------------------------------------ own sounds
    // The server never sends a player the sounds of their own actions: their client plays them locally.
    // They are stored as packets received by the player (so they are merged with the identical packets received
    // by the players around).

    private void addSound(int tick, Server type, PacketWrapper<?> wrapper) {
        recording.addPacket(tick, subject, type, PacketCodec.encodeNew(wrapper));
    }

    private void playSound(int tick, Platform.SoundInfo sound, SoundCategory category, Vector3d position) {
        addSound(tick, Server.SOUND_EFFECT, new WrapperPlayServerSoundEffect(Sounds.getByNameOrCreate(sound.name()), category,
                position, sound.volume(), sound.pitch()));
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
        Platform.SoundInfo sound = platform.stepSound(below);
        if (sound != null) {
            playSound(tick, new Platform.SoundInfo(sound.name(), sound.volume() * 0.15f, sound.pitch()), SoundCategory.PLAYER,
                    new Vector3d(location.getX(), location.getY(), location.getZ()));
        }
    }

    /** Called before the block is broken. */
    void blockBroken(int tick, Block block) {
        // Level event 2001: break particles and sound.
        addSound(tick, Server.EFFECT, new WrapperPlayServerEffect(2001, new Vector3i(block.getX(), block.getY(), block.getZ()),
                platform.breakEffectData(block), false));
    }

    void blockPlaced(int tick, Block block) {
        Platform.SoundInfo sound = platform.placeSound(block);
        if (sound != null) {
            playSound(tick, new Platform.SoundInfo(sound.name(), (sound.volume() + 1) / 2, sound.pitch() * 0.8f), SoundCategory.BLOCK,
                    new Vector3d(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5));
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
        org.bukkit.inventory.ItemStack[] current = platform.equipment(player);
        List<Equipment> changed = new ArrayList<>(SLOTS.length);
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i] == EquipmentSlot.OFF_HAND && PacketCodec.isLegacy()) {
                continue;
            }
            if (force || !Objects.equals(current[i], lastEquipment[i])) {
                lastEquipment[i] = current[i] == null ? null : current[i].clone();
                changed.add(new Equipment(SLOTS[i], PlayerPackets.item(current[i])));
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        // Modern clients take the whole equipment at once; send every slot so the packet is self-contained.
        if (!PacketCodec.isLegacy() && changed.size() != SLOTS.length) {
            changed.clear();
            for (int i = 0; i < SLOTS.length; i++) {
                changed.add(new Equipment(SLOTS[i], PlayerPackets.item(current[i])));
            }
        }
        for (PlayerPackets.Built packet : PlayerPackets.equipment(entityId, changed)) {
            add(tick, packet);
        }
    }

    void swing(int tick, boolean offHand) {
        if (spawned) {
            Swings.Swing swing = Swings.of(entityId, offHand);
            add(tick, swing.type(), swing.wrapper());
        }
    }

    void hurt(int tick) {
        if (spawned) {
            add(tick, PlayerPackets.hurt(entityId));
        }
    }
}
