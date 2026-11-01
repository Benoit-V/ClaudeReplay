package fr.bebedlastreat.claudeReplay.api.replay;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A named point in time of a replay.
 */
public record ReplayMarker(int tick, @NotNull String name, @Nullable String data) {

    public ReplayMarker {
        Objects.requireNonNull(name, "name");
    }
}
