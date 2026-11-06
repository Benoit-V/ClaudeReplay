package fr.bebedlastreat.claudeReplay.playback;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBossBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnExperienceOrb;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import fr.bebedlastreat.claudeReplay.packet.PacketFilters;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import fr.bebedlastreat.claudeReplay.util.VarInts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Absorbs the packets of a timeline jump and keeps only the resulting state, so that moving to minute 15 sends
 * a few hundred packets instead of fifteen minutes of history.
 * <p>
 * Entities are rebuilt from their spawn packet with their final position, merged entity data and equipment;
 * tab list, teams, objectives, scores, boss bars, time, weather and world border keep their last value.
 * Short-lived effects (sounds, particles, animations...) are dropped.
 */
public final class StateCompactor implements PacketSink {

    private static final int KEPT_CHAT_LINES = 20;
    private static final int KEPT_ATTRIBUTE_PACKETS = 16;

    private final User user;

    private final Map<Integer, EntityState> entities = new LinkedHashMap<>();
    private final Map<UUID, InfoState> infos = new LinkedHashMap<>();
    /** 1.8 tab list entries. */
    private final Map<UUID, WrapperPlayServerPlayerInfo.PlayerData> legacyInfos = new LinkedHashMap<>();
    private final Map<String, TeamState> teams = new LinkedHashMap<>();
    private final Map<String, WrapperPlayServerScoreboardObjective> objectives = new LinkedHashMap<>();
    private final Map<Integer, byte[]> displays = new LinkedHashMap<>();
    private final Map<String, Map<String, byte[]>> scores = new LinkedHashMap<>();
    private final Map<UUID, WrapperPlayServerBossBar> bossBars = new LinkedHashMap<>();
    private final Map<String, Out> singles = new LinkedHashMap<>();
    private final Map<Long, byte[]> blockEntities = new LinkedHashMap<>();
    private final Map<Long, byte[]> blockChanges = new LinkedHashMap<>();
    private final List<byte[]> multiBlockChanges = new ArrayList<>();
    private final Map<Integer, byte[]> maps = new LinkedHashMap<>();
    private final Deque<Out> chat = new ArrayDeque<>();

    private record Out(Server type, byte[] payload) {
    }

    private static final class EntityState {
        final Server spawnType;
        /** Decoded spawn packet, null for the spawns kept as they are (paintings). */
        final PacketWrapper<?> spawn;
        final byte[] rawSpawn;
        double x, y, z;
        float yaw, pitch, headYaw;
        final Map<Integer, EntityData<?>> metadata = new LinkedHashMap<>();
        final Map<EquipmentSlot, Equipment> equipment = new EnumMap<>(EquipmentSlot.class);
        final Map<String, byte[]> effects = new LinkedHashMap<>();
        final Deque<byte[]> attributes = new ArrayDeque<>();
        byte[] passengers;
        byte[] leash;
        /** 1.8: riding is an ATTACH_ENTITY packet too. */
        byte[] vehicle;

        EntityState(Server spawnType, PacketWrapper<?> spawn, byte[] rawSpawn) {
            this.spawnType = spawnType;
            this.spawn = spawn;
            this.rawSpawn = rawSpawn;
            if (spawn instanceof WrapperPlayServerSpawnEntity entity) {
                position(entity.getPosition(), entity.getYaw(), entity.getPitch(), entity.getHeadYaw());
            } else if (spawn instanceof WrapperPlayServerSpawnPlayer player) {
                position(player.getPosition(), player.getYaw(), player.getPitch(), player.getYaw());
            } else if (spawn instanceof WrapperPlayServerSpawnLivingEntity living) {
                position(living.getPosition(), living.getYaw(), living.getPitch(), living.getHeadPitch());
            } else if (spawn instanceof WrapperPlayServerSpawnExperienceOrb orb) {
                position(new Vector3d(orb.getX(), orb.getY(), orb.getZ()), 0, 0, 0);
            }
        }

        private void position(Vector3d position, float yaw, float pitch, float headYaw) {
            x = position.getX();
            y = position.getY();
            z = position.getZ();
            this.yaw = yaw;
            this.pitch = pitch;
            this.headYaw = headYaw;
        }

        /** The spawn packet at the current position. */
        byte[] spawnPayload() {
            Vector3d position = new Vector3d(x, y, z);
            if (spawn instanceof WrapperPlayServerSpawnEntity entity) {
                entity.setPosition(position);
                entity.setYaw(yaw);
                entity.setPitch(pitch);
                entity.setHeadYaw(headYaw);
                if (entity.getVelocity().isPresent()) {
                    entity.setVelocity(Optional.of(new Vector3d(0, 0, 0)));
                }
            } else if (spawn instanceof WrapperPlayServerSpawnPlayer player) {
                player.setPosition(position);
                player.setYaw(yaw);
                player.setPitch(pitch);
            } else if (spawn instanceof WrapperPlayServerSpawnLivingEntity living) {
                living.setPosition(position);
                living.setYaw(yaw);
                living.setPitch(pitch);
                living.setHeadPitch(headYaw);
                living.setVelocity(new Vector3d(0, 0, 0));
            } else if (spawn instanceof WrapperPlayServerSpawnExperienceOrb orb) {
                orb.setX(x);
                orb.setY(y);
                orb.setZ(z);
            } else {
                return rawSpawn;
            }
            return PacketCodec.encode(spawn);
        }
    }

    private static final class InfoState {
        final EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions = EnumSet.noneOf(WrapperPlayServerPlayerInfoUpdate.Action.class);
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info;
    }

    private static final class TeamState {
        WrapperPlayServerTeams.ScoreBoardTeamInfo info;
        final Set<String> members = new LinkedHashSet<>();
    }

    public StateCompactor(User user) {
        this.user = user;
    }

    private <W extends PacketWrapper<?>> W decode(java.util.function.Function<com.github.retrooper.packetevents.event.PacketSendEvent, W> constructor,
                                                  Server type, byte[] payload) {
        return PacketCodec.decode(constructor, type, payload, user);
    }

    private byte[] encode(PacketWrapper<?> wrapper) {
        wrapper.setRegistryHolder(user);
        return PacketCodec.encodeNew(wrapper);
    }

    /** The viewer changed world: entities and blocks of the previous world are gone. */
    public void onWorldChange() {
        entities.clear();
        blockEntities.clear();
        blockChanges.clear();
        multiBlockChanges.clear();
        singles.keySet().removeIf(key -> key.startsWith("border"));
    }

    @Override
    public void destroyEntities(int keep) {
        entities.keySet().removeIf(id -> id != keep);
    }

    @Override
    public void send(Server type, byte[] payload) {
        try {
            absorb(type, payload);
        } catch (RuntimeException ignored) {
            // an undecodable packet only loses its part of the state
        }
    }

    private EntityState entity(byte[] payload) {
        return entities.get(VarInts.leadingValue(payload));
    }

    private void absorb(Server type, byte[] payload) {
        if (PacketFilters.SPAWNS.contains(type)) {
            PacketWrapper<?> spawn = switch (type) {
                case SPAWN_ENTITY -> decode(WrapperPlayServerSpawnEntity::new, type, payload);
                case SPAWN_PLAYER -> decode(WrapperPlayServerSpawnPlayer::new, type, payload);
                case SPAWN_LIVING_ENTITY -> decode(WrapperPlayServerSpawnLivingEntity::new, type, payload);
                case SPAWN_EXPERIENCE_ORB -> decode(WrapperPlayServerSpawnExperienceOrb::new, type, payload);
                default -> null;
            };
            if (type != Server.SPAWN_WEATHER_ENTITY) { // lightning only lasts a moment
                entities.put(VarInts.leadingValue(payload), new EntityState(type, spawn, payload));
            }
            return;
        }
        switch (type) {
            case DESTROY_ENTITIES -> {
                for (int id : decode(WrapperPlayServerDestroyEntities::new, type, payload).getEntityIds()) {
                    entities.remove(id);
                }
            }
            case ENTITY_RELATIVE_MOVE -> {
                EntityState state = entity(payload);
                if (state != null) {
                    WrapperPlayServerEntityRelativeMove move = decode(WrapperPlayServerEntityRelativeMove::new, type, payload);
                    state.x += move.getDeltaX();
                    state.y += move.getDeltaY();
                    state.z += move.getDeltaZ();
                }
            }
            case ENTITY_RELATIVE_MOVE_AND_ROTATION -> {
                EntityState state = entity(payload);
                if (state != null) {
                    WrapperPlayServerEntityRelativeMoveAndRotation move = decode(WrapperPlayServerEntityRelativeMoveAndRotation::new, type, payload);
                    state.x += move.getDeltaX();
                    state.y += move.getDeltaY();
                    state.z += move.getDeltaZ();
                    state.yaw = move.getYaw();
                    state.pitch = move.getPitch();
                }
            }
            case ENTITY_ROTATION -> {
                EntityState state = entity(payload);
                if (state != null) {
                    WrapperPlayServerEntityRotation rotation = decode(WrapperPlayServerEntityRotation::new, type, payload);
                    state.yaw = rotation.getYaw();
                    state.pitch = rotation.getPitch();
                }
            }
            case ENTITY_HEAD_LOOK -> {
                EntityState state = entity(payload);
                if (state != null) {
                    state.headYaw = decode(WrapperPlayServerEntityHeadLook::new, type, payload).getHeadYaw();
                }
            }
            case ENTITY_TELEPORT -> {
                EntityState state = entity(payload);
                if (state != null) {
                    WrapperPlayServerEntityTeleport teleport = decode(WrapperPlayServerEntityTeleport::new, type, payload);
                    state.x = teleport.getPosition().getX();
                    state.y = teleport.getPosition().getY();
                    state.z = teleport.getPosition().getZ();
                    state.yaw = teleport.getYaw();
                    state.pitch = teleport.getPitch();
                }
            }
            case ENTITY_POSITION_SYNC -> {
                EntityState state = entity(payload);
                if (state != null) {
                    WrapperPlayServerEntityPositionSync sync = decode(WrapperPlayServerEntityPositionSync::new, type, payload);
                    Vector3d position = sync.getValues().getPosition();
                    state.x = position.getX();
                    state.y = position.getY();
                    state.z = position.getZ();
                    state.yaw = sync.getValues().getYaw();
                    state.pitch = sync.getValues().getPitch();
                }
            }
            case ENTITY_METADATA -> {
                EntityState state = entity(payload);
                if (state != null) {
                    for (EntityData<?> data : decode(WrapperPlayServerEntityMetadata::new, type, payload).getEntityMetadata()) {
                        state.metadata.put(data.getIndex(), data);
                    }
                }
            }
            case ENTITY_EQUIPMENT -> {
                EntityState state = entity(payload);
                if (state != null) {
                    for (Equipment equipment : decode(WrapperPlayServerEntityEquipment::new, type, payload).getEquipment()) {
                        state.equipment.put(equipment.getSlot(), equipment);
                    }
                }
            }
            case ENTITY_EFFECT -> {
                EntityState state = entity(payload);
                if (state != null) {
                    WrapperPlayServerEntityEffect effect = decode(WrapperPlayServerEntityEffect::new, type, payload);
                    state.effects.put(effect.getPotionType().getName().toString(), payload);
                }
            }
            case REMOVE_ENTITY_EFFECT -> {
                EntityState state = entity(payload);
                if (state != null) {
                    WrapperPlayServerRemoveEntityEffect effect = decode(WrapperPlayServerRemoveEntityEffect::new, type, payload);
                    state.effects.remove(effect.getPotionType().getName().toString());
                }
            }
            case UPDATE_ATTRIBUTES -> {
                EntityState state = entity(payload);
                if (state != null) {
                    state.attributes.addLast(payload);
                    if (state.attributes.size() > KEPT_ATTRIBUTE_PACKETS) {
                        state.attributes.removeFirst();
                    }
                }
            }
            case SET_PASSENGERS -> {
                EntityState state = entity(payload);
                if (state != null) {
                    state.passengers = payload;
                }
            }
            case ATTACH_ENTITY -> {
                // Starts with the attached entity id as a fixed int. On 1.8 it ends with the leash flag, riding
                // being the same packet without it.
                if (payload.length >= 4) {
                    int id = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
                    EntityState state = entities.get(id);
                    if (state != null) {
                        if (PacketCodec.isLegacy() && payload[payload.length - 1] == 0) {
                            state.vehicle = payload;
                        } else {
                            state.leash = payload;
                        }
                    }
                }
            }
            case PLAYER_INFO -> absorbLegacyInfo(decode(WrapperPlayServerPlayerInfo::new, type, payload));
            case UPDATE_SIGN -> blockEntities.put(leadingLong(payload), payload);
            case CHAT_MESSAGE -> { // 1.8 (modern chat is converted to system chat): JSON + position, 2 = action bar
                if (payload[payload.length - 1] != 2) {
                    chat.addLast(new Out(type, payload));
                    if (chat.size() > KEPT_CHAT_LINES) {
                        chat.removeFirst();
                    }
                }
            }
            case PLAYER_INFO_UPDATE -> {
                WrapperPlayServerPlayerInfoUpdate update = decode(WrapperPlayServerPlayerInfoUpdate::new, type, payload);
                for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry : update.getEntries()) {
                    InfoState state = infos.computeIfAbsent(entry.getProfileId(), id -> new InfoState());
                    mergeInfo(state, entry, update.getActions());
                }
            }
            case PLAYER_INFO_REMOVE -> decode(WrapperPlayServerPlayerInfoRemove::new, type, payload).getProfileIds().forEach(infos::remove);
            case TEAMS -> absorbTeam(decode(WrapperPlayServerTeams::new, type, payload));
            case SCOREBOARD_OBJECTIVE -> {
                WrapperPlayServerScoreboardObjective objective = decode(WrapperPlayServerScoreboardObjective::new, type, payload);
                if (objective.getMode() == WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) {
                    objectives.remove(objective.getName());
                    scores.remove(objective.getName());
                } else {
                    objectives.put(objective.getName(), objective);
                }
            }
            case DISPLAY_SCOREBOARD -> displays.put(decode(WrapperPlayServerDisplayScoreboard::new, type, payload).getPosition(), payload);
            case UPDATE_SCORE -> {
                WrapperPlayServerUpdateScore score = decode(WrapperPlayServerUpdateScore::new, type, payload);
                if (score.getAction() == WrapperPlayServerUpdateScore.Action.REMOVE_ITEM) {
                    Map<String, byte[]> entries = scores.get(score.getObjectiveName());
                    if (entries != null) {
                        entries.remove(score.getEntityName());
                    }
                } else {
                    scores.computeIfAbsent(score.getObjectiveName(), name -> new LinkedHashMap<>()).put(score.getEntityName(), payload);
                }
            }
            case RESET_SCORE -> {
                WrapperPlayServerResetScore reset = decode(WrapperPlayServerResetScore::new, type, payload);
                if (reset.getObjective() == null) {
                    scores.values().forEach(entries -> entries.remove(reset.getTargetName()));
                } else {
                    Map<String, byte[]> entries = scores.get(reset.getObjective());
                    if (entries != null) {
                        entries.remove(reset.getTargetName());
                    }
                }
            }
            case BOSS_BAR -> absorbBossBar(decode(WrapperPlayServerBossBar::new, type, payload));
            case TIME_UPDATE -> singles.put("time", new Out(type, payload));
            case PLAYER_LIST_HEADER_AND_FOOTER -> singles.put("tab", new Out(type, payload));
            case INITIALIZE_WORLD_BORDER -> {
                singles.keySet().removeIf(key -> key.startsWith("border"));
                singles.put("border", new Out(type, payload));
            }
            case WORLD_BORDER -> {
                // 1.8: one packet with an action, 3 = initialize
                int action = VarInts.leadingValue(payload);
                if (action == 3) {
                    singles.keySet().removeIf(key -> key.startsWith("border"));
                    singles.put("border", new Out(type, payload));
                } else {
                    singles.put("border:" + action, new Out(type, payload));
                }
            }
            case WORLD_BORDER_CENTER, WORLD_BORDER_LERP_SIZE, WORLD_BORDER_SIZE, WORLD_BORDER_WARNING_DELAY,
                 WORLD_BORDER_WARNING_REACH -> singles.put("border:" + type.name(), new Out(type, payload));
            case CHANGE_GAME_STATE -> {
                int reason = payload[0] & 0xFF;
                if (reason == 1 || reason == 2) {
                    singles.put("weather", new Out(type, payload));
                } else if (reason == 7 || reason == 8) {
                    singles.put("weather:" + reason, new Out(type, payload));
                }
            }
            case BLOCK_ENTITY_DATA -> blockEntities.put(leadingLong(payload), payload);
            case BLOCK_CHANGE -> blockChanges.put(leadingLong(payload), payload);
            case MULTI_BLOCK_CHANGE -> multiBlockChanges.add(payload);
            case MAP_DATA -> maps.put(VarInts.leadingValue(payload), payload);
            case SYSTEM_CHAT_MESSAGE, DISGUISED_CHAT -> {
                if (type == Server.DISGUISED_CHAT || payload[payload.length - 1] == 0) { // not an action bar
                    chat.addLast(new Out(type, payload));
                    if (chat.size() > KEPT_CHAT_LINES) {
                        chat.removeFirst();
                    }
                }
            }
            default -> {
            }
        }
    }

    private static long leadingLong(byte[] payload) {
        long value = 0;
        for (int i = 0; i < 8 && i < payload.length; i++) {
            value = (value << 8) | (payload[i] & 0xFF);
        }
        return value;
    }

    private static void mergeInfo(InfoState state, WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry,
                                  EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions) {
        if (state.info == null || actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER)) {
            if (state.info == null) {
                state.info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(entry);
            } else {
                state.info.setGameProfile(entry.getGameProfile());
            }
        }
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = state.info;
        for (WrapperPlayServerPlayerInfoUpdate.Action action : actions) {
            switch (action) {
                case UPDATE_GAME_MODE -> info.setGameMode(entry.getGameMode());
                case UPDATE_LISTED -> info.setListed(entry.isListed());
                case UPDATE_LATENCY -> info.setLatency(entry.getLatency());
                case UPDATE_DISPLAY_NAME -> info.setDisplayName(entry.getDisplayName());
                case UPDATE_LIST_ORDER -> info.setListOrder(entry.getListOrder());
                case UPDATE_HAT -> info.setShowHat(entry.isShowHat());
                default -> {
                }
            }
        }
        state.actions.addAll(actions);
        state.actions.remove(WrapperPlayServerPlayerInfoUpdate.Action.INITIALIZE_CHAT);
    }

    private void absorbLegacyInfo(WrapperPlayServerPlayerInfo packet) {
        for (WrapperPlayServerPlayerInfo.PlayerData data : packet.getPlayerDataList()) {
            UUID uuid = data.getUserProfile().getUUID();
            WrapperPlayServerPlayerInfo.PlayerData known = legacyInfos.get(uuid);
            switch (packet.getAction()) {
                case ADD_PLAYER -> legacyInfos.put(uuid, data);
                case REMOVE_PLAYER -> legacyInfos.remove(uuid);
                case UPDATE_GAME_MODE -> {
                    if (known != null) {
                        known.setGameMode(data.getGameMode());
                    }
                }
                case UPDATE_LATENCY -> {
                    if (known != null) {
                        known.setPing(data.getPing());
                    }
                }
                case UPDATE_DISPLAY_NAME -> {
                    if (known != null) {
                        known.setDisplayName(data.getDisplayName());
                    }
                }
                default -> {
                }
            }
        }
    }

    private void absorbTeam(WrapperPlayServerTeams packet) {
        String name = packet.getTeamName();
        switch (packet.getTeamMode()) {
            case CREATE -> {
                TeamState state = new TeamState();
                state.info = packet.getTeamInfo().orElse(null);
                state.members.addAll(packet.getPlayers());
                teams.put(name, state);
            }
            case REMOVE -> teams.remove(name);
            case UPDATE -> {
                TeamState state = teams.get(name);
                if (state != null) {
                    packet.getTeamInfo().ifPresent(info -> state.info = info);
                }
            }
            case ADD_ENTITIES -> {
                TeamState state = teams.get(name);
                if (state != null) {
                    state.members.addAll(packet.getPlayers());
                }
            }
            case REMOVE_ENTITIES -> {
                TeamState state = teams.get(name);
                if (state != null) {
                    packet.getPlayers().forEach(state.members::remove);
                }
            }
        }
    }

    private void absorbBossBar(WrapperPlayServerBossBar packet) {
        switch (packet.getAction()) {
            case ADD -> bossBars.put(packet.getUUID(), packet);
            case REMOVE -> bossBars.remove(packet.getUUID());
            default -> {
                WrapperPlayServerBossBar bar = bossBars.get(packet.getUUID());
                if (bar == null) {
                    return;
                }
                switch (packet.getAction()) {
                    case UPDATE_HEALTH -> bar.setHealth(packet.getHealth());
                    case UPDATE_TITLE -> bar.setTitle(packet.getTitle());
                    case UPDATE_STYLE -> {
                        bar.setColor(packet.getColor());
                        bar.setOverlay(packet.getOverlay());
                    }
                    case UPDATE_FLAGS -> bar.setFlags(packet.getFlags());
                    default -> {
                    }
                }
            }
        }
    }

    /** Sends the resulting state. */
    public void flushTo(PacketSink out) {
        // Tab list, grouped by the set of fields known for each entry.
        Map<EnumSet<WrapperPlayServerPlayerInfoUpdate.Action>, List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo>> groups = new LinkedHashMap<>();
        for (InfoState state : infos.values()) {
            if (state.info != null && state.actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER)) {
                groups.computeIfAbsent(state.actions, actions -> new ArrayList<>()).add(state.info);
            }
        }
        groups.forEach((actions, entries) -> out.send(Server.PLAYER_INFO_UPDATE, encode(new WrapperPlayServerPlayerInfoUpdate(actions, entries))));
        if (!legacyInfos.isEmpty()) {
            out.send(Server.PLAYER_INFO, encode(new WrapperPlayServerPlayerInfo(WrapperPlayServerPlayerInfo.Action.ADD_PLAYER,
                    new ArrayList<>(legacyInfos.values()))));
        }

        teams.forEach((name, state) -> {
            if (state.info != null) {
                out.send(Server.TEAMS, encode(new WrapperPlayServerTeams(name, WrapperPlayServerTeams.TeamMode.CREATE, state.info, state.members)));
            }
        });
        objectives.values().forEach(objective -> {
            objective.setMode(WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE);
            out.send(Server.SCOREBOARD_OBJECTIVE, encode(objective));
        });
        displays.values().forEach(payload -> out.send(Server.DISPLAY_SCOREBOARD, payload));
        scores.values().forEach(entries -> entries.values().forEach(payload -> out.send(Server.UPDATE_SCORE, payload)));
        bossBars.values().forEach(bar -> {
            bar.setAction(WrapperPlayServerBossBar.Action.ADD);
            out.send(Server.BOSS_BAR, encode(bar));
        });
        Out border = singles.get("border");
        if (border != null) {
            out.send(border.type(), border.payload());
        }
        singles.forEach((key, packet) -> {
            if (!key.equals("border")) {
                out.send(packet.type(), packet.payload());
            }
        });

        for (Map.Entry<Integer, EntityState> entry : entities.entrySet()) {
            int id = entry.getKey();
            EntityState state = entry.getValue();
            out.send(state.spawnType, state.spawnPayload());
            if (!state.metadata.isEmpty()) {
                out.send(Server.ENTITY_METADATA, encode(new WrapperPlayServerEntityMetadata(id, new ArrayList<>(state.metadata.values()))));
            }
            if (!state.equipment.isEmpty()) {
                if (PacketCodec.isLegacy()) { // one slot per packet
                    for (Equipment equipment : state.equipment.values()) {
                        out.send(Server.ENTITY_EQUIPMENT, encode(new WrapperPlayServerEntityEquipment(id, List.of(equipment))));
                    }
                } else {
                    out.send(Server.ENTITY_EQUIPMENT, encode(new WrapperPlayServerEntityEquipment(id, new ArrayList<>(state.equipment.values()))));
                }
            }
            if (state.headYaw != state.yaw) {
                out.send(Server.ENTITY_HEAD_LOOK, encode(new WrapperPlayServerEntityHeadLook(id, state.headYaw)));
            }
            state.effects.values().forEach(payload -> out.send(Server.ENTITY_EFFECT, payload));
            state.attributes.forEach(payload -> out.send(Server.UPDATE_ATTRIBUTES, payload));
        }
        for (EntityState state : entities.values()) {
            if (state.passengers != null) {
                out.send(Server.SET_PASSENGERS, state.passengers);
            }
            if (state.leash != null) {
                out.send(Server.ATTACH_ENTITY, state.leash);
            }
            if (state.vehicle != null) {
                out.send(Server.ATTACH_ENTITY, state.vehicle);
            }
        }

        blockEntities.values().forEach(payload -> out.send(Server.BLOCK_ENTITY_DATA, payload));
        multiBlockChanges.forEach(payload -> out.send(Server.MULTI_BLOCK_CHANGE, payload));
        blockChanges.values().forEach(payload -> out.send(Server.BLOCK_CHANGE, payload));
        maps.values().forEach(payload -> out.send(Server.MAP_DATA, payload));
        chat.forEach(packet -> out.send(packet.type(), packet.payload()));
    }
}
