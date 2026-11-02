package fr.bebedlastreat.claudeReplay.recording;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import fr.bebedlastreat.claudeReplay.ClaudeReplay;
import fr.bebedlastreat.claudeReplay.api.event.RecordingPlayerAddEvent;
import fr.bebedlastreat.claudeReplay.api.event.RecordingPlayerRemoveEvent;
import fr.bebedlastreat.claudeReplay.api.event.RecordingStopEvent;
import fr.bebedlastreat.claudeReplay.api.event.RecordingWorldChangeEvent;
import fr.bebedlastreat.claudeReplay.api.recording.PlayerTrackingMode;
import fr.bebedlastreat.claudeReplay.api.recording.Recording;
import fr.bebedlastreat.claudeReplay.api.recording.RecordingOptions;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayMarker;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayPlayer;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayWorld;
import fr.bebedlastreat.claudeReplay.format.BlockPositions;
import fr.bebedlastreat.claudeReplay.format.ReplayFileWriter;
import fr.bebedlastreat.claudeReplay.format.ReplayRecord;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import fr.bebedlastreat.claudeReplay.packet.PacketFilters;
import fr.bebedlastreat.claudeReplay.platform.Platform;
import fr.bebedlastreat.claudeReplay.platform.Platforms;
import fr.bebedlastreat.claudeReplay.util.ServerTicks;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * A running recording.
 * <p>
 * The network threads push the packets received by captured players into {@link #capture}; everything else
 * happens on the main thread in {@link #tick()}.
 */
public final class RecordingImpl implements Recording {

    /** Ticks kept in memory before being written, to wait for late packets and merge duplicates. */
    private static final int SEAL_DELAY = 3;

    private final ClaudeReplay plugin;
    private final RecordingManager manager;
    private final UUID id = UUID.randomUUID();
    private final String name;
    private final long createdAt = System.currentTimeMillis();
    private final int startServerTick;
    private final int maxDurationTicks;
    private final boolean verifyBlockChanges;

    private final Map<String, String> metadata;
    private final List<ReplayMarker> markers = new ArrayList<>();
    private final List<ReplayWorld> worlds = new ArrayList<>();
    private boolean worldPointPending;

    private World world;
    private volatile UUID worldId;
    private String mapId;
    private PlayerTrackingMode trackingMode;

    private final Map<UUID, Member> members = new LinkedHashMap<>();
    private final Set<UUID> refusedAutomatic = new HashSet<>();
    private final List<ReplayPlayer> players = new ArrayList<>();
    private final Map<UUID, Integer> indexes = new HashMap<>();
    private final Map<UUID, Member> capturing = new ConcurrentHashMap<>();

    private final Queue<Captured> queue = new ConcurrentLinkedQueue<>();
    private final TickBuffer buffer = new TickBuffer();
    private final ReplayFileWriter writer;
    private final Platform platform = Platforms.get();

    private volatile boolean running = true;
    private CompletableFuture<ReplayInfo> stopFuture;

    private static final class Member {
        final UUID uuid;
        final int index;
        SelfTracker self;

        Member(UUID uuid, int index) {
            this.uuid = uuid;
            this.index = index;
        }
    }

    private record Captured(int tick, int recipient, Server type, byte[] payload, User user) {
    }

    RecordingImpl(ClaudeReplay plugin, RecordingManager manager, RecordingOptions options, Path file) throws IOException {
        this.plugin = plugin;
        this.manager = manager;
        this.name = options.getName();
        this.maxDurationTicks = options.getMaxDurationTicks();
        this.metadata = new LinkedHashMap<>(options.getMetadata());
        this.trackingMode = options.getTrackingMode();
        this.verifyBlockChanges = plugin.getReplayConfig().verifyBlockChanges();
        this.startServerTick = ServerTicks.current();
        this.writer = new ReplayFileWriter(file, manager.getIoExecutor(), plugin.getReplayConfig().segmentTicks(),
                PacketCodec.serverVersion().getProtocolVersion());
        enterWorld(options.getWorld(), options.getMapId(), 0);
        for (UUID uuid : options.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                addMember(player, false);
            }
        }
    }

    // ------------------------------------------------------------------ network threads

    UUID worldId() {
        return worldId;
    }

    /** Index of the player if they are currently captured, -1 otherwise. */
    int capturingIndex(UUID player) {
        Member member = capturing.get(player);
        return member == null ? -1 : member.index;
    }

    void capture(int recipient, Server type, byte[] payload, User user) {
        if (running) {
            queue.add(new Captured(getCurrentTick(), recipient, type, payload, user));
        }
    }

    // ------------------------------------------------------------------ main thread

    void addPacket(int tick, int recipient, Server type, byte[] payload) {
        buffer.addPacket(tick, recipient, type, payload);
    }

    void addSelf(int tick, int subject, Server type, byte[] payload) {
        buffer.addSelf(tick, subject, type, payload);
    }

    void tick() {
        if (!running) {
            return;
        }
        int tick = getCurrentTick();
        if (maxDurationTicks > 0 && tick >= maxDurationTicks) {
            stop();
            return;
        }
        drain();
        if (trackingMode == PlayerTrackingMode.WORLD) {
            reconcileWorldMembers();
        }
        updateCaptures(tick);
        for (Member member : capturing.values()) {
            if (member.self != null) {
                member.self.sample(tick);
            }
        }
        buffer.seal(tick - SEAL_DELAY, writer::write);
    }

    void onSwing(UUID player, boolean offHand) {
        Member member = capturing.get(player);
        if (member != null && member.self != null) {
            member.self.swing(getCurrentTick(), offHand);
        }
    }

    void onBlockBroken(UUID player, Block block) {
        Member member = capturing.get(player);
        if (member != null && member.self != null) {
            member.self.blockBroken(getCurrentTick(), block);
        }
    }

    void onBlockPlaced(UUID player, Block block) {
        Member member = capturing.get(player);
        if (member != null && member.self != null) {
            member.self.blockPlaced(getCurrentTick(), block);
        }
    }

    void onHurt(UUID player) {
        Member member = capturing.get(player);
        if (member != null && member.self != null) {
            member.self.hurt(getCurrentTick());
        }
    }

    private void drain() {
        Captured captured;
        while ((captured = queue.poll()) != null) {
            if (PacketFilters.isBlockUpdate(captured.type)) {
                handleBlockUpdate(captured);
            } else {
                buffer.addPacket(captured.tick, captured.recipient, captured.type, captured.payload);
            }
        }
    }

    /**
     * Block updates matching the real world are world modifications (applied to the map at playback time);
     * the others are fake blocks sent to a single player and stay in their view only.
     */
    private void handleBlockUpdate(Captured captured) {
        boolean personal = false;
        try {
            if (captured.type == Server.BLOCK_CHANGE) {
                WrapperPlayServerBlockChange change = PacketCodec.decode(WrapperPlayServerBlockChange::new, captured.type, captured.payload, captured.user);
                var position = change.getBlockPosition();
                personal = !recordWorldBlock(captured.tick, position.getX(), position.getY(), position.getZ(), change.getBlockId());
            } else {
                WrapperPlayServerMultiBlockChange change = PacketCodec.decode(WrapperPlayServerMultiBlockChange::new, captured.type, captured.payload, captured.user);
                for (WrapperPlayServerMultiBlockChange.EncodedBlock block : change.getBlocks()) {
                    personal |= !recordWorldBlock(captured.tick, block.getX(), block.getY(), block.getZ(), block.getBlockId());
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "Cannot decode block update", e);
            personal = true;
        }
        if (personal && captured.recipient >= 0) {
            buffer.addPacket(captured.tick, captured.recipient, captured.type, captured.payload);
        }
    }

    private boolean recordWorldBlock(int tick, int x, int y, int z, int state) {
        if (verifyBlockChanges && world.isChunkLoaded(x >> 4, z >> 4) && platform.getBlock(world, x, y, z) != state) {
            return false;
        }
        buffer.addBlock(tick, BlockPositions.pack(x, y, z), state);
        return true;
    }

    private boolean isRecordable(Player player) {
        return player.isOnline() && !player.hasMetadata("NPC")
                && PacketEvents.getAPI().getPlayerManager().getUser(player) != null
                && !plugin.getPlaybackManager().isViewer(player.getUniqueId());
    }

    private void reconcileWorldMembers() {
        for (Player player : world.getPlayers()) {
            UUID uuid = player.getUniqueId();
            if (!members.containsKey(uuid) && !refusedAutomatic.contains(uuid) && isRecordable(player)) {
                if (!addMember(player, true)) {
                    refusedAutomatic.add(uuid);
                }
            }
        }
        for (Member member : List.copyOf(members.values())) {
            Player player = Bukkit.getPlayer(member.uuid);
            if (player == null || !player.getWorld().equals(world)) {
                removeMember(member.uuid, true);
            }
        }
        refusedAutomatic.removeIf(uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            return player == null || !player.getWorld().equals(world);
        });
    }

    private void updateCaptures(int tick) {
        for (Member member : members.values()) {
            Player player = Bukkit.getPlayer(member.uuid);
            boolean should = player != null && player.getWorld().equals(world) && isRecordable(player);
            boolean is = capturing.containsKey(member.uuid);
            if (should && !is) {
                startCapture(member, player, tick);
            } else if (!should && is) {
                stopCapture(member, tick);
            }
        }
    }

    private void startCapture(Member member, Player player, int tick) {
        capturing.put(member.uuid, member);
        buffer.addEvent(tick, new ReplayRecord.CaptureStart(member.index));
        if (worldPointPending) {
            worldPointPending = false;
            Location location = player.getLocation();
            worlds.set(worlds.size() - 1, worlds.get(worlds.size() - 1).withPointOfInterest(location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch()));
        }
        member.self = new SelfTracker(this, player, member.index);
        member.self.spawn(tick);
        StateResync.resync(plugin, this, player, member.index, tick);
    }

    private void stopCapture(Member member, int tick) {
        capturing.remove(member.uuid);
        if (member.self != null) {
            member.self.despawn(tick);
            member.self = null;
        }
        buffer.addEvent(tick, new ReplayRecord.CaptureStop(member.index));
    }

    private boolean addMember(Player player, boolean automatic) {
        UUID uuid = player.getUniqueId();
        if (members.containsKey(uuid)) {
            return false;
        }
        RecordingPlayerAddEvent event = new RecordingPlayerAddEvent(this, player, automatic);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        Integer index = indexes.get(uuid);
        if (index == null) {
            index = players.size();
            indexes.put(uuid, index);
            ClientVersion client = PacketCodec.clientVersion(player);
            players.add(new ReplayPlayer(uuid, player.getName(), client == null ? 0 : client.getProtocolVersion(),
                    client == null ? null : client.getReleaseName()));
            buffer.addEvent(getCurrentTick(), new ReplayRecord.PlayerRegistered(index, uuid, player.getName()));
        }
        members.put(uuid, new Member(uuid, index));
        return true;
    }

    private boolean removeMember(UUID uuid, boolean automatic) {
        Member member = members.remove(uuid);
        if (member == null) {
            return false;
        }
        if (capturing.containsKey(uuid)) {
            stopCapture(member, getCurrentTick());
        }
        Bukkit.getPluginManager().callEvent(new RecordingPlayerRemoveEvent(this, uuid, automatic));
        return true;
    }

    private void enterWorld(World newWorld, String newMapId, int tick) {
        this.world = newWorld;
        this.worldId = newWorld.getUID();
        this.mapId = newMapId;
        Location spawn = newWorld.getSpawnLocation();
        worlds.add(new ReplayWorld(tick, newWorld.getName(), newMapId, newWorld.getEnvironment(),
                spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getYaw(), spawn.getPitch()));
        worldPointPending = true;
        refusedAutomatic.clear();
        buffer.addEvent(tick, new ReplayRecord.WorldChange(worlds.size() - 1));
    }

    // ------------------------------------------------------------------ API

    @Override
    public @NotNull UUID getId() {
        return id;
    }

    @Override
    public @NotNull String getName() {
        return name;
    }

    @Override
    public @NotNull World getWorld() {
        return world;
    }

    @Override
    public @NotNull String getMapId() {
        return mapId;
    }

    @Override
    public void setWorld(@NotNull World world) {
        setWorld(world, world.getName());
    }

    @Override
    public void setWorld(@NotNull World newWorld, @NotNull String newMapId) {
        checkRunning();
        if (newWorld.equals(world) && newMapId.equals(mapId)) {
            return;
        }
        World from = world;
        int tick = getCurrentTick();
        drain();
        // Players still in the previous world stop being captured right now.
        for (Member member : List.copyOf(capturing.values())) {
            stopCapture(member, tick);
        }
        enterWorld(newWorld, newMapId, tick);
        Bukkit.getPluginManager().callEvent(new RecordingWorldChangeEvent(this, from, newWorld, newMapId));
    }

    @Override
    public @NotNull PlayerTrackingMode getTrackingMode() {
        return trackingMode;
    }

    @Override
    public void setTrackingMode(@NotNull PlayerTrackingMode mode) {
        this.trackingMode = mode;
        refusedAutomatic.clear();
    }

    @Override
    public @NotNull Set<UUID> getPlayers() {
        return Collections.unmodifiableSet(new HashSet<>(members.keySet()));
    }

    @Override
    public boolean isRecorded(@NotNull UUID player) {
        return members.containsKey(player);
    }

    @Override
    public boolean addPlayer(@NotNull Player player) {
        checkRunning();
        refusedAutomatic.remove(player.getUniqueId());
        return addMember(player, false);
    }

    @Override
    public boolean removePlayer(@NotNull UUID player) {
        checkRunning();
        return removeMember(player, false);
    }

    @Override
    public boolean isRecording() {
        return running;
    }

    @Override
    public int getCurrentTick() {
        return ServerTicks.current() - startServerTick;
    }

    @Override
    public void addMarker(@NotNull String name, @Nullable String data) {
        checkRunning();
        int tick = getCurrentTick();
        markers.add(new ReplayMarker(tick, name, data));
        buffer.addEvent(tick, new ReplayRecord.Marker(name, data));
    }

    @Override
    public @NotNull Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    @Override
    public void setMetadata(@NotNull String key, @Nullable String value) {
        if (value == null) {
            metadata.remove(key);
        } else {
            metadata.put(key, value);
        }
    }

    @Override
    public void recordBlockChange(@NotNull Location location) {
        checkRunning();
        if (world.equals(location.getWorld())) {
            buffer.addBlock(getCurrentTick(), BlockPositions.pack(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
                    platform.blockState(location.getBlock()));
        }
    }

    private void checkRunning() {
        if (!running) {
            throw new IllegalStateException("The recording " + name + " is stopped");
        }
    }

    @Override
    public @NotNull CompletableFuture<ReplayInfo> stop() {
        CompletableFuture<ReplayInfo> result = new CompletableFuture<>();
        stopAndSave().whenComplete((info, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(info);
            }
        }));
        return result;
    }

    /** Stops the recording; the returned future completes off the main thread once the replay is stored. */
    CompletableFuture<ReplayInfo> stopAndSave() {
        if (stopFuture != null) {
            return stopFuture;
        }
        running = false;
        manager.unregister(this);
        Bukkit.getPluginManager().callEvent(new RecordingStopEvent(this, true));

        int tick = getCurrentTick();
        drain();
        for (Member member : List.copyOf(capturing.values())) {
            stopCapture(member, tick);
        }
        buffer.seal(Integer.MAX_VALUE, writer::write);

        ReplayInfo info = new ReplayInfo(id, name, createdAt, Math.max(tick, 1),
                PacketCodec.serverVersion().getProtocolVersion(), platform.minecraftVersion(),
                players, worlds, markers, metadata);
        stopFuture = writer.finish(info)
                .thenCompose(file -> plugin.getStorage().save(info, file).whenComplete((ignored, error) -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        plugin.getLogger().warning("Cannot delete " + file + ": " + e.getMessage());
                    }
                }))
                .thenApply(ignored -> info);
        stopFuture.whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Cannot save the replay " + name + " (" + id + ")", error);
            } else {
                plugin.getLogger().info("Replay " + name + " saved (" + id + ")");
            }
        });
        return stopFuture;
    }

    @Override
    public void cancel() {
        if (!running) {
            return;
        }
        running = false;
        manager.unregister(this);
        Bukkit.getPluginManager().callEvent(new RecordingStopEvent(this, false));
        capturing.clear();
        queue.clear();
        writer.abort();
    }
}
