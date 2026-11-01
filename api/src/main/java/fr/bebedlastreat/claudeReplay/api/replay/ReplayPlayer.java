package fr.bebedlastreat.claudeReplay.api.replay;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * A player recorded in a replay.
 *
 * @param uuid the player's real UUID
 * @param name the player's name when they were first recorded
 */
public record ReplayPlayer(@NotNull UUID uuid, @NotNull String name) {

    public ReplayPlayer {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
    }
}
