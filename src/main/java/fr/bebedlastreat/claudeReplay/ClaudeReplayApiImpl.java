package fr.bebedlastreat.claudeReplay;

import fr.bebedlastreat.claudeReplay.api.ClaudeReplayAPI;
import fr.bebedlastreat.claudeReplay.api.playback.PlaybackSession;
import fr.bebedlastreat.claudeReplay.api.recording.Recording;
import fr.bebedlastreat.claudeReplay.api.recording.RecordingOptions;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.api.storage.MapResolver;
import fr.bebedlastreat.claudeReplay.api.storage.ReplayStorage;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class ClaudeReplayApiImpl implements ClaudeReplayAPI {

    private final ClaudeReplay plugin;

    ClaudeReplayApiImpl(ClaudeReplay plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull Recording startRecording(@NotNull RecordingOptions options) {
        return plugin.getRecordingManager().start(options);
    }

    @Override
    public @NotNull Optional<Recording> getRecording(@NotNull UUID recordingId) {
        return Optional.ofNullable(plugin.getRecordingManager().get(recordingId));
    }

    @Override
    public @NotNull Collection<Recording> getRecordings() {
        return List.copyOf(plugin.getRecordingManager().active());
    }

    @Override
    public @NotNull Collection<Recording> getRecordings(@NotNull Player player) {
        return plugin.getRecordingManager().getRecordings(player);
    }

    @Override
    public @NotNull CompletableFuture<PlaybackSession> startPlayback(@NotNull UUID replayId, @NotNull Collection<? extends Player> viewers) {
        return plugin.getPlaybackManager().start(replayId, viewers);
    }

    @Override
    public @NotNull Optional<PlaybackSession> getPlayback(@NotNull Player viewer) {
        return plugin.getPlaybackManager().getSession(viewer.getUniqueId()).map(session -> session);
    }

    @Override
    public @NotNull Collection<PlaybackSession> getPlaybacks() {
        return new ArrayList<>(plugin.getPlaybackManager().getSessions());
    }

    @Override
    public @NotNull CompletableFuture<List<ReplayInfo>> listReplays() {
        return plugin.getStorage().list();
    }

    @Override
    public @NotNull CompletableFuture<Optional<ReplayInfo>> getReplayInfo(@NotNull UUID replayId) {
        return plugin.getStorage().info(replayId);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> deleteReplay(@NotNull UUID replayId) {
        return plugin.getStorage().delete(replayId);
    }

    @Override
    public @NotNull ReplayStorage getStorage() {
        return plugin.getStorage();
    }

    @Override
    public void setStorage(@NotNull ReplayStorage storage) {
        plugin.setStorage(Objects.requireNonNull(storage, "storage"));
    }

    @Override
    public @NotNull MapResolver getMapResolver() {
        return plugin.getMapResolver();
    }

    @Override
    public void setMapResolver(@NotNull MapResolver resolver) {
        plugin.setMapResolver(Objects.requireNonNull(resolver, "resolver"));
    }
}
