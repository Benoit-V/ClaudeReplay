package fr.bebedlastreat.claudeReplay.playback;

import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBossBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearTitles;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTitle;
import fr.bebedlastreat.claudeReplay.packet.PacketFilters;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerListHeaderAndFooter;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import fr.bebedlastreat.claudeReplay.util.VarInts;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sends packets to a viewer and remembers what the replay created on their client (entities, tab entries,
 * teams, objectives, boss bars) so that it can be removed when the timeline jumps or the viewer leaves.
 */
final class LiveSink implements PacketSink {

    private final User user;
    private final Set<Integer> entities = new LinkedHashSet<>();
    private final Set<UUID> profiles = new HashSet<>();
    private final Set<String> teams = new HashSet<>();
    private final Set<String> objectives = new HashSet<>();
    private final Set<UUID> bossBars = new HashSet<>();
    private final Map<Integer, Integer> spawnCounts = new HashMap<>();
    private boolean dirty;

    LiveSink(User user) {
        this.user = user;
    }

    User user() {
        return user;
    }

    boolean hasEntity(int id) {
        return entities.contains(id);
    }

    /** Incremented each time the entity is spawned: lets the camera notice an entity spawned again. */
    int spawnCount(int id) {
        return spawnCounts.getOrDefault(id, 0);
    }

    @Override
    public void send(Server type, byte[] payload) {
        try {
            track(type, payload);
        } catch (RuntimeException ignored) {
            // tracking is best effort, the packet is still sent
        }
        PacketCodec.write(user, type, payload);
        dirty = true;
    }

    void send(PacketWrapper<?> wrapper) {
        PacketCodec.write(user, wrapper);
        dirty = true;
    }

    void flush() {
        if (dirty) {
            dirty = false;
            user.flushPackets();
        }
    }

    private void track(Server type, byte[] payload) {
        if (PacketFilters.SPAWNS.contains(type)) {
            int id = VarInts.leadingValue(payload);
            entities.add(id);
            spawnCounts.merge(id, 1, Integer::sum);
            return;
        }
        switch (type) {
            case DESTROY_ENTITIES -> {
                for (int id : PacketCodec.decode(WrapperPlayServerDestroyEntities::new, type, payload, user).getEntityIds()) {
                    entities.remove(id);
                }
            }
            case PLAYER_INFO_UPDATE -> {
                WrapperPlayServerPlayerInfoUpdate update = PacketCodec.decode(WrapperPlayServerPlayerInfoUpdate::new, type, payload, user);
                if (update.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER)) {
                    for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo info : update.getEntries()) {
                        profiles.add(info.getProfileId());
                    }
                }
            }
            case PLAYER_INFO -> { // 1.8
                WrapperPlayServerPlayerInfo info = PacketCodec.decode(WrapperPlayServerPlayerInfo::new, type, payload, user);
                for (WrapperPlayServerPlayerInfo.PlayerData data : info.getPlayerDataList()) {
                    if (info.getAction() == WrapperPlayServerPlayerInfo.Action.ADD_PLAYER) {
                        profiles.add(data.getUserProfile().getUUID());
                    } else if (info.getAction() == WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER) {
                        profiles.remove(data.getUserProfile().getUUID());
                    }
                }
            }
            case PLAYER_INFO_REMOVE ->
                    PacketCodec.decode(WrapperPlayServerPlayerInfoRemove::new, type, payload, user).getProfileIds().forEach(profiles::remove);
            case TEAMS -> {
                WrapperPlayServerTeams teamsPacket = PacketCodec.decode(WrapperPlayServerTeams::new, type, payload, user);
                if (teamsPacket.getTeamMode() == WrapperPlayServerTeams.TeamMode.CREATE) {
                    teams.add(teamsPacket.getTeamName());
                } else if (teamsPacket.getTeamMode() == WrapperPlayServerTeams.TeamMode.REMOVE) {
                    teams.remove(teamsPacket.getTeamName());
                }
            }
            case SCOREBOARD_OBJECTIVE -> {
                WrapperPlayServerScoreboardObjective objective = PacketCodec.decode(WrapperPlayServerScoreboardObjective::new, type, payload, user);
                if (objective.getMode() == WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE) {
                    objectives.add(objective.getName());
                } else if (objective.getMode() == WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) {
                    objectives.remove(objective.getName());
                }
            }
            case BOSS_BAR -> {
                WrapperPlayServerBossBar bar = PacketCodec.decode(WrapperPlayServerBossBar::new, type, payload, user);
                if (bar.getAction() == WrapperPlayServerBossBar.Action.ADD) {
                    bossBars.add(bar.getUUID());
                } else if (bar.getAction() == WrapperPlayServerBossBar.Action.REMOVE) {
                    bossBars.remove(bar.getUUID());
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void destroyEntities(int keep) {
        List<Integer> ids = new ArrayList<>();
        for (int id : entities) {
            if (id != keep) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        entities.removeAll(ids);
        send(new WrapperPlayServerDestroyEntities(ids.stream().mapToInt(Integer::intValue).toArray()));
    }

    /** The client changed world (respawn): it already forgot every entity. */
    void onClientWorldChange() {
        entities.clear();
    }

    /** Removes everything the replay created on the client. */
    void resetClient() {
        destroyEntities(-1);
        if (!profiles.isEmpty()) {
            if (PacketCodec.isLegacy()) {
                List<WrapperPlayServerPlayerInfo.PlayerData> removed = new ArrayList<>();
                for (UUID profile : profiles) {
                    removed.add(new WrapperPlayServerPlayerInfo.PlayerData((Component) null, new UserProfile(profile, ""), GameMode.SURVIVAL, 0));
                }
                send(new WrapperPlayServerPlayerInfo(WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER, removed));
            } else {
                send(new WrapperPlayServerPlayerInfoRemove(new ArrayList<>(profiles)));
            }
            profiles.clear();
        }
        for (String team : teams) {
            send(new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.REMOVE, Optional.empty(), List.of()));
        }
        teams.clear();
        for (String objective : objectives) {
            send(new WrapperPlayServerScoreboardObjective(objective, WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
                    Component.empty(), WrapperPlayServerScoreboardObjective.RenderType.INTEGER));
        }
        objectives.clear();
        for (UUID bar : bossBars) {
            send(new WrapperPlayServerBossBar(bar, WrapperPlayServerBossBar.Action.REMOVE));
        }
        bossBars.clear();
        if (PacketCodec.isLegacy()) {
            send(new WrapperPlayServerTitle(WrapperPlayServerTitle.TitleAction.RESET, (Component) null, null, null, 0, 0, 0));
        } else {
            send(new WrapperPlayServerClearTitles(true));
        }
        send(new WrapperPlayServerPlayerListHeaderAndFooter(Component.empty(), Component.empty()));
        send(new WrapperPlayServerChangeGameState(1, 0)); // end raining
        send(new WrapperPlayServerChangeGameState(7, 0));
        send(new WrapperPlayServerChangeGameState(8, 0));
    }
}
