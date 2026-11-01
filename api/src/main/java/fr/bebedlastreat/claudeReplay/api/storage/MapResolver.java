package fr.bebedlastreat.claudeReplay.api.storage;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Gives the base map of a replay at playback time.
 * <p>
 * Replays never contain the world itself, only its modifications. When a replay is watched, the folder returned
 * here is copied into a temporary world, then the recorded modifications are applied on top of it.
 */
@FunctionalInterface
public interface MapResolver {

    /**
     * @param mapId the map id given when the recording started or switched world
     * @return a future giving a world folder (containing {@code level.dat} and the region files). It is only read,
     * never modified. May be completed from any thread.
     */
    @NotNull CompletableFuture<Path> resolve(@NotNull String mapId);
}
