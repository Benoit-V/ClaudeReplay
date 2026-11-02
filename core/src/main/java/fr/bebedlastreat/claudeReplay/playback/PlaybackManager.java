package fr.bebedlastreat.claudeReplay.playback;

import fr.bebedlastreat.claudeReplay.ClaudeReplay;
import fr.bebedlastreat.claudeReplay.api.playback.PlaybackSession;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayWorld;
import fr.bebedlastreat.claudeReplay.api.storage.MapResolver;
import fr.bebedlastreat.claudeReplay.api.storage.ReplayStorage;
import fr.bebedlastreat.claudeReplay.format.ReplayFileReader;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import fr.bebedlastreat.claudeReplay.platform.Platform;
import fr.bebedlastreat.claudeReplay.platform.Platforms;
import fr.bebedlastreat.claudeReplay.util.Threads;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import fr.bebedlastreat.claudeReplay.util.Files2;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Starts, ticks and stops playback sessions.
 */
public final class PlaybackManager {

    private final ClaudeReplay plugin;
    private final Map<UUID, PlaybackSessionImpl> sessions = new LinkedHashMap<>();
    private final Map<UUID, PlaybackSessionImpl> viewers = new ConcurrentHashMap<>();
    private final Set<UUID> worlds = ConcurrentHashMap.newKeySet();
    private final ExecutorService io = Executors.newFixedThreadPool(2, Threads.daemon("ClaudeReplay-playback-"));
    private final Executor mainThread;

    public PlaybackManager(ClaudeReplay plugin) {
        this.plugin = plugin;
        ViewerBackup.init(plugin);
        this.mainThread = task -> {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        };
    }

    private record Prepared(ReplayFileReader reader, Path file, Map<String, PlaybackWorld> worlds, AtomicBoolean owned) {
    }

    /**
     * Loads a replay and starts watching it. Completes on the main thread.
     */
    public CompletableFuture<PlaybackSession> start(UUID replayId, Collection<? extends Player> players) {
        List<Player> list = List.copyOf(players);
        if (list.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("No viewer"));
        }
        ReplayStorage storage = plugin.getStorage();
        MapResolver resolver = plugin.getMapResolver();
        List<Prepared> preparation = new ArrayList<>(1);

        CompletableFuture<PlaybackSession> result = storage.fetch(replayId)
                .thenApplyAsync(file -> {
                    try {
                        ReplayFileReader reader = ReplayFileReader.open(file);
                        if (!isPlayable(reader.getProtocolVersion())) {
                            reader.close();
                            throw new IllegalStateException("Ce replay vient d'un serveur en " + reader.getInfo().serverVersion()
                                    + ", plus récent que ce serveur (" + Platforms.get().minecraftVersion() + ")");
                        }
                        Prepared prepared = new Prepared(reader, file, new ConcurrentHashMap<>(), new AtomicBoolean());
                        preparation.add(prepared);
                        return prepared;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }, io)
                .thenComposeAsync(prepared -> prepareWorlds(prepared, resolver), mainThread)
                .thenApplyAsync(prepared -> {
                    PlaybackSessionImpl session = new PlaybackSessionImpl(plugin, this, prepared.reader(), prepared.file(), prepared.worlds());
                    prepared.owned().set(true);
                    sessions.put(session.getId(), session);
                    List<Player> online = list.stream().filter(Player::isOnline).toList();
                    if (online.isEmpty()) {
                        session.stop();
                        throw new IllegalStateException("Every viewer left");
                    }
                    try {
                        session.begin(online);
                    } catch (IOException | RuntimeException e) {
                        session.stop();
                        throw new CompletionException(e);
                    }
                    return (PlaybackSession) session;
                }, mainThread);

        result.whenComplete((session, error) -> {
            // Once a session exists, stopping it cleans everything; before that, the copies are removed here.
            if (error != null && !preparation.isEmpty() && !preparation.get(0).owned().get()) {
                Prepared prepared = preparation.get(0);
                io.execute(() -> cleanupFiles(prepared.worlds().values(), prepared.reader(), replayId, prepared.file()));
            }
        });
        return result;
    }

    /** Resolves the maps (main thread) and copies them (IO threads). */
    private CompletableFuture<Prepared> prepareWorlds(Prepared prepared, MapResolver resolver) {
        ReplayInfo info = prepared.reader().getInfo();
        String prefix = "s" + Long.toHexString(System.nanoTime() & 0xFFFFFFFL);
        List<CompletableFuture<Void>> copies = new ArrayList<>();
        int index = 0;
        for (ReplayWorld replayWorld : info.worlds()) {
            if (prepared.worlds().containsKey(replayWorld.mapId())) {
                continue;
            }
            PlaybackWorld world = new PlaybackWorld(replayWorld.mapId(), prefix + "_" + index++);
            prepared.worlds().put(replayWorld.mapId(), world);
            copies.add(resolver.resolve(replayWorld.mapId()).thenAcceptAsync(map -> {
                try {
                    world.prepare(map, replayWorld.environment());
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot copy the map " + replayWorld.mapId(), e);
                }
            }, io));
        }
        return CompletableFuture.allOf(copies.toArray(CompletableFuture[]::new)).thenApply(ignored -> prepared);
    }

    void cleanupAsync(PlaybackSessionImpl session, Collection<PlaybackWorld> playbackWorlds, ReplayFileReader reader, UUID replayId, Path file) {
        List<PlaybackWorld> copy = List.copyOf(playbackWorlds);
        io.execute(() -> cleanupFiles(copy, reader, replayId, file));
    }

    private void cleanupFiles(Collection<PlaybackWorld> playbackWorlds, ReplayFileReader reader, UUID replayId, Path file) {
        for (PlaybackWorld world : playbackWorlds) {
            try {
                world.delete();
            } catch (IOException e) {
                plugin.getLogger().warning("Cannot delete the replay world " + world.folder() + ": " + e.getMessage());
            }
        }
        try {
            reader.close();
        } catch (IOException ignored) {
        }
        plugin.getStorage().release(replayId, file);
    }

    /** Removes the replay worlds left by a crash and gives back the state of players who were watching. */
    public void cleanupLeftovers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ViewerBackup.restoreIfPresent(player);
        }
        Platform platform = Platforms.get();
        for (World world : List.copyOf(Bukkit.getWorlds())) {
            if (platform.isPlaybackWorld(world)) {
                Path folder = platform.worldFolder(world);
                Bukkit.unloadWorld(world, false);
                deleteQuietly(folder);
            }
        }
        platform.leftoverPlaybackWorlds().forEach(this::deleteQuietly);
    }

    private void deleteQuietly(Path folder) {
        try {
            Files2.deleteRecursively(folder);
        } catch (IOException e) {
            plugin.getLogger().warning("Cannot delete " + folder + ": " + e.getMessage());
        }
    }

    /** A replay can be watched if it was recorded on this version or an older one. */
    public boolean isPlayable(ReplayInfo replay) {
        return isPlayable(replay.protocolVersion());
    }

    private static boolean isPlayable(int protocol) {
        ServerVersion recorded = PacketCodec.versionOf(protocol);
        return recorded != null && !recorded.isNewerThan(PacketCodec.serverVersion());
    }

    public void tick() {
        for (PlaybackSessionImpl session : List.copyOf(sessions.values())) {
            session.tick();
        }
    }

    void remove(PlaybackSessionImpl session) {
        sessions.remove(session.getId());
    }

    void registerViewer(UUID player, PlaybackSessionImpl session) {
        viewers.put(player, session);
    }

    void unregisterViewer(UUID player) {
        viewers.remove(player);
    }

    void registerWorld(UUID world) {
        worlds.add(world);
    }

    void unregisterWorld(UUID world) {
        worlds.remove(world);
    }

    /** Thread safe. */
    public boolean isViewer(UUID player) {
        return viewers.containsKey(player);
    }

    /** Thread safe. */
    public boolean isPlaybackWorld(World world) {
        return worlds.contains(world.getUID());
    }

    public Optional<PlaybackSessionImpl> getSession(UUID player) {
        return Optional.ofNullable(viewers.get(player));
    }

    public Collection<PlaybackSessionImpl> getSessions() {
        return List.copyOf(sessions.values());
    }

    public void shutdown() {
        for (PlaybackSessionImpl session : List.copyOf(sessions.values())) {
            try {
                session.stop();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Cannot stop a playback session", e);
            }
        }
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
