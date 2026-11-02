package fr.bebedlastreat.claudeReplay.api.recording;

import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A running recording.
 * <p>
 * A recording saves the packets received by each recorded player while they are in the recorded world,
 * plus the block modifications of that world. The world itself is never saved: at playback time it is rebuilt
 * from the base map identified by {@link #getMapId()}.
 * <p>
 * All methods must be called from the main thread.
 */
public interface Recording {

    @NotNull UUID getId();

    @NotNull String getName();

    /** The world currently recorded. */
    @NotNull World getWorld();

    /** The base map of the world currently recorded. */
    @NotNull String getMapId();

    /**
     * Switches the recorded world (e.g. a mini-game moving to its next map). The map id defaults to the world name.
     *
     * @see #setWorld(World, String)
     */
    void setWorld(@NotNull World world);

    /**
     * Switches the recorded world. At playback time the new world is rebuilt from a fresh copy of {@code mapId}.
     * Recorded players are only captured while they are inside the recorded world.
     */
    void setWorld(@NotNull World world, @NotNull String mapId);

    @NotNull PlayerTrackingMode getTrackingMode();

    void setTrackingMode(@NotNull PlayerTrackingMode mode);

    /** The current members of the recording (online or not, in the world or not). */
    @NotNull Set<UUID> getPlayers();

    boolean isRecorded(@NotNull UUID player);

    /** Adds a player to the recording. Returns false if already recorded or if the add event was cancelled. */
    boolean addPlayer(@NotNull Player player);

    /** Removes a player from the recording. Returns false if not recorded. */
    boolean removePlayer(@NotNull UUID player);

    default boolean removePlayer(@NotNull Player player) {
        return removePlayer(player.getUniqueId());
    }

    /** Whether the recording is still running. */
    boolean isRecording();

    /** Current tick of the recording (0 = start). */
    int getCurrentTick();

    /** Adds a named marker at the current tick (visible in the playback UI). */
    default void addMarker(@NotNull String name) {
        addMarker(name, null);
    }

    void addMarker(@NotNull String name, @Nullable String data);

    @NotNull Map<String, String> getMetadata();

    void setMetadata(@NotNull String key, @Nullable String value);

    /**
     * Records the current state of a block of the recorded world. Block changes seen by players are recorded
     * automatically; call this after changing a block where no player can see it.
     */
    void recordBlockChange(@NotNull Location location);

    /**
     * Stops the recording and saves it in the storage.
     *
     * @return a future completed (on the main thread) with the saved replay
     */
    @NotNull CompletableFuture<ReplayInfo> stop();

    /** Stops the recording without saving anything. */
    void cancel();
}
