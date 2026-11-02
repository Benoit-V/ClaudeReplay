package fr.bebedlastreat.claudeReplay.api;

import fr.bebedlastreat.claudeReplay.api.playback.PlaybackSession;
import fr.bebedlastreat.claudeReplay.api.recording.Recording;
import fr.bebedlastreat.claudeReplay.api.recording.RecordingOptions;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.api.storage.MapResolver;
import fr.bebedlastreat.claudeReplay.api.storage.ReplayStorage;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point of the ClaudeReplay API.
 * <p>
 * Obtain it with {@link ClaudeReplayProvider#get()} or through Bukkit's {@code ServicesManager}.
 * Every method must be called from the server main thread unless stated otherwise.
 */
public interface ClaudeReplayAPI {

    // ------------------------------------------------------------------ recording

    /**
     * Starts a new recording. The recording begins immediately (tick 0 is the current server tick).
     */
    @NotNull Recording startRecording(@NotNull RecordingOptions options);

    @NotNull Optional<Recording> getRecording(@NotNull UUID recordingId);

    /** All recordings currently running. */
    @NotNull Collection<Recording> getRecordings();

    /** Recordings in which the given player is currently a member. */
    @NotNull Collection<Recording> getRecordings(@NotNull Player player);

    // ------------------------------------------------------------------ playback

    /**
     * Loads a replay from the storage, prepares its maps and starts watching it.
     *
     * @param replayId the replay to watch
     * @param viewers  the players who will watch it (at least one)
     * @return a future completed on the main thread once the viewers are inside the replay; it fails if the
     * replay comes from a newer server version (see {@link #isPlayable(ReplayInfo)})
     */
    @NotNull CompletableFuture<PlaybackSession> startPlayback(@NotNull UUID replayId, @NotNull Collection<? extends Player> viewers);

    /** The playback session the given player is watching, if any. */
    /**
     * A replay can be watched on a server of the same version as the one that recorded it, or of a newer version.
     */
    boolean isPlayable(@NotNull ReplayInfo replay);

    /** Protocol version of this server, compared with {@link ReplayInfo#protocolVersion()}. */
    int getServerProtocolVersion();

    @NotNull Optional<PlaybackSession> getPlayback(@NotNull Player viewer);

    @NotNull Collection<PlaybackSession> getPlaybacks();

    // ------------------------------------------------------------------ stored replays

    /** Lists the replays known by the current {@link ReplayStorage}. May complete off the main thread. */
    @NotNull CompletableFuture<List<ReplayInfo>> listReplays();

    /** May complete off the main thread. */
    @NotNull CompletableFuture<Optional<ReplayInfo>> getReplayInfo(@NotNull UUID replayId);

    /** May complete off the main thread. */
    @NotNull CompletableFuture<Boolean> deleteReplay(@NotNull UUID replayId);

    // ------------------------------------------------------------------ extension points

    @NotNull ReplayStorage getStorage();

    /** Replaces the storage used to save and load replays (default: local files). */
    void setStorage(@NotNull ReplayStorage storage);

    @NotNull MapResolver getMapResolver();

    /** Replaces the resolver giving the base map of a replay at playback time. */
    void setMapResolver(@NotNull MapResolver resolver);
}
