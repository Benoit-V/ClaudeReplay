package fr.bebedlastreat.claudeReplay.api.replay;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * A player recorded in a replay.
 *
 * @param uuid            the player's real UUID
 * @param name            the player's name when they were first recorded
 * @param protocolVersion protocol version of the player's client, 0 if unknown (replays made before it was stored)
 * @param clientVersion   human readable version of the player's client ("1.8.8", "26.3"...), null if unknown
 */
public record ReplayPlayer(@NotNull UUID uuid, @NotNull String name, int protocolVersion, @Nullable String clientVersion) {

    public ReplayPlayer {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
    }

    public ReplayPlayer(@NotNull UUID uuid, @NotNull String name) {
        this(uuid, name, 0, null);
    }
}
