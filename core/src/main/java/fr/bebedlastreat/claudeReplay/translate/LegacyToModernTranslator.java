package fr.bebedlastreat.claudeReplay.translate;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.sound.Sounds;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearTitles;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerInitializeWorldBorder;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleSubtitle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleText;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleTimes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnExperienceOrb;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnWeatherEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTitle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWorldBorder;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWorldBorderCenter;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWorldBorderSize;
import fr.bebedlastreat.claudeReplay.format.ReplayRecord;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import fr.bebedlastreat.claudeReplay.packet.PacketFilters;
import fr.bebedlastreat.claudeReplay.packet.Swings;
import fr.bebedlastreat.claudeReplay.util.VarInts;
import net.kyori.adventure.text.Component;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Plays a 1.8 replay on a modern server: the 1.8 packets are decoded with the 1.8 layout and rebuilt as the
 * modern packets with the same meaning. Entity data, sounds, block states and items are converted; what has
 * no modern equivalent (paintings, signs, maps, attributes...) is dropped.
 */
final class LegacyToModernTranslator implements Translator {

    private record Out(Server type, byte[] payload) {
    }

    private final ServerVersion recorded;
    private final ServerVersion server = PacketCodec.serverVersion();
    private final Supplier<User> users;
    private final BlockStates blocks;
    private final int skinPartsIndex;

    /** Type of each spawned entity, to convert its entity data. */
    private final Map<Integer, EntityType> types = new HashMap<>();
    /** Rider -> vehicle: 1.8 attaches riders one by one, modern versions list the passengers of a vehicle. */
    private final Map<Integer, Integer> vehicles = new HashMap<>();

    private User user;

    LegacyToModernTranslator(ServerVersion recorded, Supplier<User> users) {
        this.recorded = recorded;
        this.users = users;
        this.blocks = new BlockStates(recorded.toClientVersion(), server.toClientVersion());
        this.skinPartsIndex = server.isNewerThanOrEquals(ServerVersion.V_1_21_9) ? 16 : 17;
    }

    @Override
    public void translate(ReplayRecord record, Consumer<ReplayRecord> out) {
        if (record instanceof ReplayRecord.Blocks recordBlocks) {
            int[] states = new int[recordBlocks.states().length];
            for (int i = 0; i < states.length; i++) {
                states[i] = blocks.convert(recordBlocks.states()[i]);
            }
            out.accept(new ReplayRecord.Blocks(recordBlocks.tick(), recordBlocks.positions(), states));
        } else if (record instanceof ReplayRecord.Packet packet) {
            for (Out converted : convert(packet.type(), packet.payload())) {
                out.accept(new ReplayRecord.Packet(packet.tick(), converted.type(), packet.recipients(), converted.payload()));
            }
        } else if (record instanceof ReplayRecord.Self self) {
            for (Out converted : convert(self.type(), self.payload())) {
                out.accept(new ReplayRecord.Self(self.tick(), self.subject(), converted.type(), converted.payload()));
            }
        } else {
            out.accept(record);
        }
    }

    private List<Out> convert(Server type, byte[] payload) {
        if (PacketFilters.NOT_REPLAYED.contains(type)) {
            // Never sent to a viewer; the pipeline only looks at the type (RESPAWN).
            return List.of(new Out(type, payload));
        }
        user = users.get();
        if (user == null) {
            return List.of();
        }
        try {
            List<Out> out = new ArrayList<>(2);
            convert(type, payload, out);
            return out;
        } catch (RuntimeException | IOException e) {
            if (Boolean.getBoolean("claudereplay.debug")) { // -Dclaudereplay.debug=true
                org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.WARNING, "[ClaudeReplay] Cannot translate " + type, e);
            }
            return List.of();
        }
    }

    // ------------------------------------------------------------------ helpers

    private <W extends PacketWrapper<?>> W decode(Function<PacketSendEvent, W> constructor, Server type, byte[] payload) {
        return PacketCodec.decode(constructor, type, payload, user, recorded);
    }

    private void add(List<Out> out, Server type, PacketWrapper<?> wrapper) {
        wrapper.setRegistryHolder(user);
        out.add(new Out(type, PacketCodec.encodeFor(wrapper, server)));
    }

    /** Decodes with the 1.8 layout and writes the same wrapper with the modern one. */
    private <W extends PacketWrapper<?>> void reencode(List<Out> out, Server type, byte[] payload, Function<PacketSendEvent, W> constructor) {
        add(out, type, decode(constructor, type, payload));
    }

    private static UUID objectUuid(int entityId) {
        return new UUID(0x436C617564655270L, entityId);
    }

    // ------------------------------------------------------------------ packets

    private void convert(Server type, byte[] payload, List<Out> out) throws IOException {
        switch (type) {
            case SPAWN_PLAYER -> {
                WrapperPlayServerSpawnPlayer spawn = decode(WrapperPlayServerSpawnPlayer::new, type, payload);
                types.put(spawn.getEntityId(), EntityTypes.PLAYER);
                add(out, Server.SPAWN_ENTITY, new WrapperPlayServerSpawnEntity(spawn.getEntityId(), Optional.of(spawn.getUUID()),
                        EntityTypes.PLAYER, spawn.getPosition(), spawn.getPitch(), spawn.getYaw(), spawn.getYaw(), 0, Optional.empty()));
                metadata(out, spawn.getEntityId(), spawn.getEntityMetadata());
            }
            case SPAWN_LIVING_ENTITY -> {
                WrapperPlayServerSpawnLivingEntity spawn = decode(WrapperPlayServerSpawnLivingEntity::new, type, payload);
                if (spawn.getEntityType() == null) {
                    return;
                }
                types.put(spawn.getEntityId(), spawn.getEntityType());
                // 1.8 mobs have no UUID: every modern entity needs its own.
                UUID uuid = spawn.getEntityUUID() == null || (spawn.getEntityUUID().getMostSignificantBits() == 0
                        && spawn.getEntityUUID().getLeastSignificantBits() == 0) ? objectUuid(spawn.getEntityId()) : spawn.getEntityUUID();
                add(out, Server.SPAWN_ENTITY, new WrapperPlayServerSpawnEntity(spawn.getEntityId(), Optional.of(uuid),
                        spawn.getEntityType(), spawn.getPosition(), spawn.getPitch(), spawn.getYaw(), spawn.getHeadPitch(), 0,
                        Optional.ofNullable(spawn.getVelocity())));
                metadata(out, spawn.getEntityId(), spawn.getEntityMetadata());
            }
            case SPAWN_ENTITY -> {
                WrapperPlayServerSpawnEntity spawn = decode(WrapperPlayServerSpawnEntity::new, type, payload);
                EntityType entityType = spawn.getEntityType();
                if (entityType == null) {
                    return;
                }
                types.put(spawn.getEntityId(), entityType);
                int data = spawn.getData();
                if (entityType == EntityTypes.FALLING_BLOCK) {
                    data = Math.max(0, blocks.convertLegacyEffect(data));
                } else if (entityType == EntityTypes.ITEM_FRAME) {
                    data = switch (data & 3) { // 1.8: south, west, north, east -> 3D direction ids
                        case 0 -> 3;
                        case 1 -> 4;
                        case 2 -> 2;
                        default -> 5;
                    };
                }
                add(out, Server.SPAWN_ENTITY, new WrapperPlayServerSpawnEntity(spawn.getEntityId(),
                        Optional.of(spawn.getUUID().orElse(objectUuid(spawn.getEntityId()))), entityType, spawn.getPosition(),
                        spawn.getPitch(), spawn.getYaw(), spawn.getYaw(), data, spawn.getVelocity()));
            }
            case SPAWN_EXPERIENCE_ORB -> {
                WrapperPlayServerSpawnExperienceOrb orb = decode(WrapperPlayServerSpawnExperienceOrb::new, type, payload);
                types.put(orb.getEntityId(), EntityTypes.EXPERIENCE_ORB);
                add(out, Server.SPAWN_ENTITY, new WrapperPlayServerSpawnEntity(orb.getEntityId(), Optional.of(objectUuid(orb.getEntityId())),
                        EntityTypes.EXPERIENCE_ORB, new Vector3d(orb.getX(), orb.getY(), orb.getZ()), 0, 0, 0, 0, Optional.empty()));
            }
            case SPAWN_WEATHER_ENTITY -> {
                WrapperPlayServerSpawnWeatherEntity lightning = decode(WrapperPlayServerSpawnWeatherEntity::new, type, payload);
                add(out, Server.SPAWN_ENTITY, new WrapperPlayServerSpawnEntity(lightning.getEntityId(), Optional.of(objectUuid(lightning.getEntityId())),
                        EntityTypes.LIGHTNING_BOLT, new Vector3d(lightning.getX(), lightning.getY(), lightning.getZ()), 0, 0, 0, 0, Optional.empty()));
            }
            case DESTROY_ENTITIES -> {
                WrapperPlayServerDestroyEntities destroy = decode(WrapperPlayServerDestroyEntities::new, type, payload);
                for (int id : destroy.getEntityIds()) {
                    types.remove(id);
                    vehicles.remove(id);
                }
                add(out, type, new WrapperPlayServerDestroyEntities(destroy.getEntityIds()));
            }
            case ENTITY_RELATIVE_MOVE -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove::new);
            case ENTITY_RELATIVE_MOVE_AND_ROTATION -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation::new);
            case ENTITY_ROTATION -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation::new);
            case ENTITY_HEAD_LOOK -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook::new);
            case ENTITY_TELEPORT -> {
                WrapperPlayServerEntityTeleport teleport = decode(WrapperPlayServerEntityTeleport::new, type, payload);
                add(out, type, new WrapperPlayServerEntityTeleport(teleport.getEntityId(), teleport.getPosition(), teleport.getYaw(),
                        teleport.getPitch(), teleport.isOnGround()));
            }
            case ENTITY_VELOCITY -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity::new);
            case ENTITY_EFFECT -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect::new);
            case REMOVE_ENTITY_EFFECT -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect::new);
            case COLLECT_ITEM -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCollectItem::new);
            case BLOCK_BREAK_ANIMATION -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation::new);
            case BLOCK_ACTION -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockAction::new);
            case PARTICLE -> {
                com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle particle =
                        decode(com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle::new, type, payload);
                if (particle.getParticle() != null && particle.getParticle().getType() != null) {
                    // The 1.8 data (block or item ids) has no modern form: only particles without data are kept.
                    add(out, type, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle(new Particle<>(particle.getParticle().getType()),
                            particle.isLongDistance(), particle.getPosition(), particle.getOffset(), particle.getMaxSpeed(), particle.getParticleCount()));
                }
            }
            case TIME_UPDATE -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTimeUpdate::new);
            case PLAYER_LIST_HEADER_AND_FOOTER -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerListHeaderAndFooter::new);
            case TEAMS -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams::new);
            case SCOREBOARD_OBJECTIVE -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective::new);
            case DISPLAY_SCOREBOARD -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard::new);
            case UPDATE_SCORE -> reencode(out, type, payload, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore::new);
            case CHANGE_GAME_STATE -> out.add(new Out(type, payload)); // same layout: reason byte + float
            case ENTITY_METADATA -> {
                WrapperPlayServerEntityMetadata metadata = decode(WrapperPlayServerEntityMetadata::new, type, payload);
                metadata(out, metadata.getEntityId(), metadata.getEntityMetadata());
            }
            case ENTITY_EQUIPMENT -> {
                WrapperPlayServerEntityEquipment equipment = decode(WrapperPlayServerEntityEquipment::new, type, payload);
                add(out, type, new WrapperPlayServerEntityEquipment(equipment.getEntityId(), equipment.getEquipment()));
            }
            case ENTITY_STATUS -> {
                WrapperPlayServerEntityStatus status = decode(WrapperPlayServerEntityStatus::new, type, payload);
                if (status.getStatus() == 2) { // hurt: a packet of its own on modern versions
                    add(out, Server.HURT_ANIMATION, new WrapperPlayServerHurtAnimation(status.getEntityId(), 0));
                } else {
                    out.add(new Out(type, payload)); // same layout: int id + byte
                }
            }
            case ENTITY_ANIMATION -> {
                int entityId = VarInts.leadingValue(payload);
                int animation = payload[payload.length - 1] & 0xFF;
                switch (animation) {
                    case 0 -> {
                        Swings.Swing swing = Swings.of(entityId, false);
                        add(out, swing.type(), swing.wrapper());
                    }
                    case 1 -> add(out, Server.HURT_ANIMATION, new WrapperPlayServerHurtAnimation(entityId, 0));
                    case 2 -> add(out, type, new WrapperPlayServerEntityAnimation(entityId, WrapperPlayServerEntityAnimation.EntityAnimationType.WAKE_UP));
                    case 4 -> add(out, type, new WrapperPlayServerEntityAnimation(entityId, WrapperPlayServerEntityAnimation.EntityAnimationType.CRITICAL_HIT));
                    case 5 -> add(out, type, new WrapperPlayServerEntityAnimation(entityId, WrapperPlayServerEntityAnimation.EntityAnimationType.MAGIC_CRITICAL_HIT));
                    default -> {
                    }
                }
            }
            case ATTACH_ENTITY -> attach(out, decode(WrapperPlayServerAttachEntity::new, type, payload));
            case BLOCK_CHANGE -> {
                WrapperPlayServerBlockChange change = decode(WrapperPlayServerBlockChange::new, type, payload);
                int state = blocks.convert(change.getBlockId());
                if (state >= 0) {
                    add(out, type, new WrapperPlayServerBlockChange(change.getBlockPosition(), state));
                }
            }
            case MULTI_BLOCK_CHANGE -> multiBlockChange(out, payload);
            case EFFECT -> {
                WrapperPlayServerEffect effect = decode(WrapperPlayServerEffect::new, type, payload);
                if (effect.getType() == 2001) { // block break: particles and sound
                    int state = blocks.convertLegacyEffect(effect.getData());
                    if (state > 0) {
                        add(out, type, new WrapperPlayServerEffect(2001, effect.getPosition(), state, false));
                    }
                }
            }
            case SOUND_EFFECT, NAMED_SOUND_EFFECT -> sound(out, payload);
            case EXPLOSION -> {
                WrapperPlayServerExplosion explosion = decode(WrapperPlayServerExplosion::new, type, payload);
                add(out, type, new WrapperPlayServerExplosion(explosion.getPosition(), null,
                        new Particle<>(ParticleTypes.EXPLOSION_EMITTER), Sounds.ENTITY_GENERIC_EXPLODE));
            }
            case CHAT_MESSAGE -> {
                // 1.8: JSON text + position (0 chat, 1 system, 2 action bar)
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
                Component text = AdventureSerializer.parseComponent(readString(in));
                boolean overlay = in.available() > 0 && in.readByte() == 2;
                add(out, Server.SYSTEM_CHAT_MESSAGE, new WrapperPlayServerSystemChatMessage(overlay, text));
            }
            case TITLE -> title(out, decode(WrapperPlayServerTitle::new, type, payload));
            case PLAYER_INFO -> playerInfo(out, decode(WrapperPlayServerPlayerInfo::new, type, payload));
            case WORLD_BORDER -> border(out, decode(WrapperPlayServerWorldBorder::new, type, payload));
            default -> {
                // No modern equivalent worth replaying (paintings, signs, maps, attributes, beds...).
            }
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        byte[] bytes = new byte[VarInts.read(in)];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 1.8 named sound: name, position * 8 (ints), volume, pitch * 63 (unsigned byte). */
    private void sound(List<Out> out, byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        String name = readString(in);
        double x = in.readInt() / 8.0;
        double y = in.readInt() / 8.0;
        double z = in.readInt() / 8.0;
        float volume = in.readFloat();
        float pitch = in.readUnsignedByte() / 63f;
        String modern = LegacySounds.modernName(name);
        if (modern != null) {
            add(out, Server.SOUND_EFFECT, new WrapperPlayServerSoundEffect(Sounds.getByNameOrCreate(modern), LegacySounds.category(name),
                    new Vector3d(x, y, z), volume, pitch));
        }
    }

    /** 1.8: chunk x, chunk z (ints), count, then (xz byte, y byte, state) per block. Sent as single block changes. */
    private void multiBlockChange(List<Out> out, byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int chunkX = in.readInt();
        int chunkZ = in.readInt();
        int count = VarInts.read(in);
        for (int i = 0; i < count; i++) {
            int horizontal = in.readUnsignedByte();
            int y = in.readUnsignedByte();
            int state = blocks.convert(VarInts.read(in));
            if (state >= 0) {
                Vector3i position = new Vector3i((chunkX << 4) + (horizontal >> 4), y, (chunkZ << 4) + (horizontal & 15));
                add(out, Server.BLOCK_CHANGE, new WrapperPlayServerBlockChange(position, state));
            }
        }
    }

    private void attach(List<Out> out, WrapperPlayServerAttachEntity attach) {
        if (attach.isLeash()) {
            add(out, Server.ATTACH_ENTITY, new WrapperPlayServerAttachEntity(attach.getAttachedId(), attach.getHoldingId(), true));
            return;
        }
        int rider = attach.getAttachedId();
        int vehicle = attach.getHoldingId();
        Integer previous = vehicle >= 0 ? vehicles.put(rider, vehicle) : vehicles.remove(rider);
        if (vehicle >= 0) {
            add(out, Server.SET_PASSENGERS, new WrapperPlayServerSetPassengers(vehicle, passengers(vehicle)));
        }
        if (previous != null && previous != vehicle) {
            add(out, Server.SET_PASSENGERS, new WrapperPlayServerSetPassengers(previous, passengers(previous)));
        }
    }

    private int[] passengers(int vehicle) {
        return vehicles.entrySet().stream().filter(entry -> entry.getValue() == vehicle).mapToInt(Map.Entry::getKey).toArray();
    }

    private void title(List<Out> out, WrapperPlayServerTitle title) {
        switch (title.getAction()) {
            case SET_TITLE -> add(out, Server.SET_TITLE_TEXT, new WrapperPlayServerSetTitleText(title.getTitle()));
            case SET_SUBTITLE -> add(out, Server.SET_TITLE_SUBTITLE, new WrapperPlayServerSetTitleSubtitle(title.getSubtitle()));
            case SET_TIMES_AND_DISPLAY -> add(out, Server.SET_TITLE_TIMES,
                    new WrapperPlayServerSetTitleTimes(title.getFadeInTicks(), title.getStayTicks(), title.getFadeOutTicks()));
            case HIDE -> add(out, Server.CLEAR_TITLES, new WrapperPlayServerClearTitles(false));
            case RESET -> add(out, Server.CLEAR_TITLES, new WrapperPlayServerClearTitles(true));
            default -> {
            }
        }
    }

    private void playerInfo(List<Out> out, WrapperPlayServerPlayerInfo info) {
        List<WrapperPlayServerPlayerInfo.PlayerData> entries = info.getPlayerDataList();
        if (info.getAction() == WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER) {
            List<UUID> ids = new ArrayList<>();
            for (WrapperPlayServerPlayerInfo.PlayerData entry : entries) {
                ids.add(entry.getUserProfile().getUUID());
            }
            add(out, Server.PLAYER_INFO_REMOVE, new WrapperPlayServerPlayerInfoRemove(ids));
            return;
        }
        EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions = switch (info.getAction()) {
            case ADD_PLAYER -> EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME);
            case UPDATE_GAME_MODE -> EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE);
            case UPDATE_LATENCY -> EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY);
            case UPDATE_DISPLAY_NAME -> EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME);
            default -> EnumSet.noneOf(WrapperPlayServerPlayerInfoUpdate.Action.class);
        };
        if (actions.isEmpty()) {
            return;
        }
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> infos = new ArrayList<>();
        for (WrapperPlayServerPlayerInfo.PlayerData entry : entries) {
            infos.add(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(entry.getUserProfile(), true, entry.getPing(),
                    entry.getGameMode(), entry.getDisplayName(), null, 0));
        }
        add(out, Server.PLAYER_INFO_UPDATE, new WrapperPlayServerPlayerInfoUpdate(actions, infos));
    }

    private void border(List<Out> out, WrapperPlayServerWorldBorder border) {
        switch (border.getAction()) {
            case INITIALIZE -> add(out, Server.INITIALIZE_WORLD_BORDER, new WrapperPlayServerInitializeWorldBorder(
                    border.getCenterX(), border.getCenterZ(), border.getOldRadius(), border.getNewRadius(), border.getSpeed(),
                    border.getPortalTeleportBoundary(), border.getWarningBlocks(), border.getWarningTime()));
            case SET_SIZE -> add(out, Server.WORLD_BORDER_SIZE, new WrapperPlayServerWorldBorderSize(border.getRadius()));
            case LERP_SIZE -> add(out, Server.WORLD_BORDER_SIZE, new WrapperPlayServerWorldBorderSize(border.getNewRadius()));
            case SET_CENTER -> add(out, Server.WORLD_BORDER_CENTER, new WrapperPlayServerWorldBorderCenter(border.getCenterX(), border.getCenterZ()));
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ entity data

    private void metadata(List<Out> out, int entityId, List<EntityData<?>> legacy) {
        if (legacy == null || legacy.isEmpty()) {
            return;
        }
        List<EntityData<?>> converted = convertMetadata(types.get(entityId), legacy);
        if (!converted.isEmpty()) {
            add(out, Server.ENTITY_METADATA, new WrapperPlayServerEntityMetadata(entityId, converted));
        }
    }

    /**
     * 1.8 entity data indexes and types to the modern ones, for the fields that matter visually: flags, pose,
     * names, health, arrows, player skin layers, armor stands and dropped items.
     */
    private List<EntityData<?>> convertMetadata(EntityType type, List<EntityData<?>> legacy) {
        boolean living = type != null && EntityTypes.isTypeInstanceOf(type, EntityTypes.LIVINGENTITY);
        List<EntityData<?>> out = new ArrayList<>();
        for (EntityData<?> data : legacy) {
            Object value = data.getValue();
            int index = data.getIndex();
            if (index == 0 && value instanceof Byte flags) {
                // fire, sneaking, sprinting, invisible keep their bit; "using an item" moved to the living hand state
                out.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) (flags & (0x01 | 0x02 | 0x08 | 0x20))));
                out.add(new EntityData<>(6, EntityDataTypes.ENTITY_POSE, (flags & 0x02) != 0 ? EntityPose.CROUCHING : EntityPose.STANDING));
                if (living) {
                    out.add(new EntityData<>(8, EntityDataTypes.BYTE, (byte) ((flags & 0x10) != 0 ? 1 : 0)));
                }
            } else if (index == 2 && value instanceof String name) {
                Optional<Component> customName = name.isEmpty() ? Optional.empty() : Optional.of(AdventureSerializer.fromLegacyFormat(name));
                out.add(new EntityData<>(2, EntityDataTypes.OPTIONAL_ADV_COMPONENT, customName));
            } else if ((index == 3 || index == 4) && value instanceof Byte flag) {
                out.add(new EntityData<>(index, EntityDataTypes.BOOLEAN, flag != 0)); // name visible, silent
            } else if (living && index == 6 && value instanceof Float health) {
                out.add(new EntityData<>(9, EntityDataTypes.FLOAT, health));
            } else if (living && index == 9 && value instanceof Byte arrows) {
                out.add(new EntityData<>(12, EntityDataTypes.INT, (int) arrows));
            } else if (type == EntityTypes.PLAYER && index == 10 && value instanceof Byte skin) {
                out.add(new EntityData<>(skinPartsIndex, EntityDataTypes.BYTE, skin));
            } else if (type == EntityTypes.ARMOR_STAND && index == 10 && value instanceof Byte flags) {
                out.add(new EntityData<>(15, EntityDataTypes.BYTE, (byte) (flags & ~0x02))); // 0x02 was "gravity"
            } else if (type == EntityTypes.ARMOR_STAND && index >= 11 && index <= 16 && value instanceof Vector3f rotation) {
                out.add(new EntityData<>(index + 5, EntityDataTypes.ROTATION, rotation));
            } else if (type == EntityTypes.ITEM && index == 10 && value instanceof ItemStack item) {
                out.add(new EntityData<>(8, EntityDataTypes.ITEMSTACK, item));
            }
        }
        return out;
    }
}
