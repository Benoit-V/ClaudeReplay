package fr.bebedlastreat.claudeReplay.api.storage;

import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Where replays are stored. The default implementation keeps them as local files; register another one
 * (database, S3...) with {@code ClaudeReplayAPI#setStorage}.
 * <p>
 * Replays are always produced and read as local files: {@link #save} receives the finished file and
 * {@link #fetch} must give back a local, readable copy. Methods may be called and completed from any thread.
 */
public interface ReplayStorage {

    /**
     * Stores a finished replay. The given file is deleted by ClaudeReplay once the future completes,
     * so the implementation must copy or move it.
     */
    @NotNull CompletableFuture<Void> save(@NotNull ReplayInfo info, @NotNull Path replayFile);

    /**
     * Gives a local readable file containing the replay. The file must stay valid until
     * {@link #release(UUID, Path)} is called for it.
     */
    @NotNull CompletableFuture<Path> fetch(@NotNull UUID replayId);

    /** Called when a file obtained with {@link #fetch} is no longer used (e.g. to delete a downloaded copy). */
    default void release(@NotNull UUID replayId, @NotNull Path file) {
    }

    @NotNull CompletableFuture<List<ReplayInfo>> list();

    @NotNull CompletableFuture<Optional<ReplayInfo>> info(@NotNull UUID replayId);

    @NotNull CompletableFuture<Boolean> delete(@NotNull UUID replayId);
}
