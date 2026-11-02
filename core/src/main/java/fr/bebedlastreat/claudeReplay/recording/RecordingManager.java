package fr.bebedlastreat.claudeReplay.recording;

import fr.bebedlastreat.claudeReplay.ClaudeReplay;
import fr.bebedlastreat.claudeReplay.api.event.RecordingStartEvent;
import fr.bebedlastreat.claudeReplay.api.recording.Recording;
import fr.bebedlastreat.claudeReplay.api.recording.RecordingOptions;
import fr.bebedlastreat.claudeReplay.format.ReplayFormat;
import fr.bebedlastreat.claudeReplay.util.Threads;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Owns the running recordings.
 */
public final class RecordingManager {

    private final ClaudeReplay plugin;
    private final Map<UUID, RecordingImpl> recordings = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(Threads.daemon("ClaudeReplay-recorder-"));

    public RecordingManager(ClaudeReplay plugin) {
        this.plugin = plugin;
    }

    ExecutorService getIoExecutor() {
        return io;
    }

    public Recording start(RecordingOptions options) {
        Path file = plugin.getDataFolder().toPath().resolve("recording").resolve(UUID.randomUUID() + ReplayFormat.EXTENSION);
        RecordingImpl recording;
        try {
            recording = new RecordingImpl(plugin, this, options, file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the recording file", e);
        }
        recordings.put(recording.getId(), recording);
        Bukkit.getPluginManager().callEvent(new RecordingStartEvent(recording));
        return recording;
    }

    void unregister(RecordingImpl recording) {
        recordings.remove(recording.getId());
    }

    public boolean isEmpty() {
        return recordings.isEmpty();
    }

    /** Thread safe. */
    public Collection<RecordingImpl> active() {
        return recordings.values();
    }

    public RecordingImpl get(UUID id) {
        return recordings.get(id);
    }

    public List<Recording> getRecordings(Player player) {
        List<Recording> result = new ArrayList<>();
        for (RecordingImpl recording : recordings.values()) {
            if (recording.isRecorded(player.getUniqueId())) {
                result.add(recording);
            }
        }
        return result;
    }

    public void tick() {
        for (RecordingImpl recording : List.copyOf(recordings.values())) {
            try {
                recording.tick();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Error while recording " + recording.getName() + ", the recording is cancelled", e);
                recording.cancel();
            }
        }
    }

    void onSwing(UUID player, boolean offHand) {
        for (RecordingImpl recording : recordings.values()) {
            recording.onSwing(player, offHand);
        }
    }

    public void onOffHandSwing(Player player) {
        onSwing(player.getUniqueId(), true);
    }

    void onBlockBroken(UUID player, Block block) {
        for (RecordingImpl recording : recordings.values()) {
            recording.onBlockBroken(player, block);
        }
    }

    void onBlockPlaced(UUID player, Block block) {
        for (RecordingImpl recording : recordings.values()) {
            recording.onBlockPlaced(player, block);
        }
    }

    void onHurt(UUID player) {
        for (RecordingImpl recording : recordings.values()) {
            recording.onHurt(player);
        }
    }

    /** Stops and saves every recording, waiting for the files to be written. */
    public void shutdown() {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (RecordingImpl recording : List.copyOf(recordings.values())) {
            futures.add(recording.stopAndSave());
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Some recordings could not be saved", e);
        }
        io.shutdown();
    }
}
