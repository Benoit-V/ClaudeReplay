package fr.bebedlastreat.claudeReplay.api.replay;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Description of a saved replay.
 *
 * @param id              unique id of the replay
 * @param name            display name
 * @param createdAt       epoch millis of the start of the recording
 * @param durationTicks   length of the replay in ticks
 * @param protocolVersion protocol version of the server that recorded it
 * @param serverVersion   human readable version of the server that recorded it
 * @param players         every player recorded at some point
 * @param worlds          the worlds of the replay, ordered by start tick (never empty)
 * @param markers         the markers, ordered by tick
 * @param metadata        free metadata given by the recording plugin
 */
public record ReplayInfo(@NotNull UUID id, @NotNull String name, long createdAt, int durationTicks,
                         int protocolVersion, @NotNull String serverVersion,
                         @NotNull List<ReplayPlayer> players, @NotNull List<ReplayWorld> worlds,
                         @NotNull List<ReplayMarker> markers, @NotNull Map<String, String> metadata) {

    public ReplayInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(serverVersion, "serverVersion");
        players = List.copyOf(players);
        worlds = List.copyOf(worlds);
        markers = List.copyOf(markers);
        metadata = Map.copyOf(metadata);
    }

    public @NotNull Optional<ReplayPlayer> getPlayer(@NotNull UUID uuid) {
        return players.stream().filter(p -> p.uuid().equals(uuid)).findFirst();
    }

    public @NotNull Optional<ReplayPlayer> getPlayer(@NotNull String name) {
        return players.stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
    }

    /** The world active at the given tick. */
    public @NotNull ReplayWorld getWorldAt(int tick) {
        ReplayWorld current = worlds.get(0);
        for (ReplayWorld world : worlds) {
            if (world.startTick() > tick) {
                break;
            }
            current = world;
        }
        return current;
    }

    public @Nullable String getMetadata(@NotNull String key) {
        return metadata.get(key);
    }
}
