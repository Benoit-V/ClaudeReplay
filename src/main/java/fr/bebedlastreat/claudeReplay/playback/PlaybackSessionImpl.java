package fr.bebedlastreat.claudeReplay.playback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import fr.bebedlastreat.claudeReplay.ClaudeReplay;
import fr.bebedlastreat.claudeReplay.api.event.PlaybackStartEvent;
import fr.bebedlastreat.claudeReplay.api.event.PlaybackStopEvent;
import fr.bebedlastreat.claudeReplay.api.playback.PlaybackSession;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayPlayer;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayWorld;
import fr.bebedlastreat.claudeReplay.format.ReplayFileReader;
import fr.bebedlastreat.claudeReplay.format.ReplayRecord;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import fr.bebedlastreat.claudeReplay.util.TimeFormat;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * A replay being watched.
 * <p>
 * The timeline is read with a cursor; each record is dispatched to every viewer's {@link ViewerPipeline}.
 * Jumps in time ({@link #seek}, a new point of view, a new viewer) rebuild the client state with a
 * {@link StateCompactor}. Block modifications are applied to real (temporary) worlds, so chunks sent by the
 * server already contain them.
 */
public final class PlaybackSessionImpl implements PlaybackSession {

    private static final double POV_FOLLOW_DISTANCE = 24;

    private final ClaudeReplay plugin;
    private final PlaybackManager manager;
    private final UUID id = UUID.randomUUID();
    private final ReplayFileReader reader;
    private final Path file;
    private final ReplayInfo info;
    private final Map<String, PlaybackWorld> worlds;
    private final ClientVersion recordedVersion;

    private final Map<UUID, Viewer> viewers = new LinkedHashMap<>();
    private final Map<UUID, Integer> subjectsByUuid = new HashMap<>();
    private final Map<Integer, Integer> subjectsByEntity = new HashMap<>();
    private final Map<Integer, double[]> subjectPositions = new HashMap<>();
    private final Map<Integer, BlockData> blockData = new HashMap<>();

    private ReplayFileReader.Cursor cursor;
    private int tick;
    private double accumulator;
    private boolean paused;
    private double speed = 1;
    private int entryIndex = -1;
    private PlaybackWorld currentWorld;
    private boolean running = true;

    PlaybackSessionImpl(ClaudeReplay plugin, PlaybackManager manager, ReplayFileReader reader, Path file, Map<String, PlaybackWorld> worlds) {
        this.plugin = plugin;
        this.manager = manager;
        this.reader = reader;
        this.file = file;
        this.info = reader.getInfo();
        this.worlds = worlds;
        this.recordedVersion = ClientVersion.getById(reader.getProtocolVersion());
        List<ReplayPlayer> players = info.players();
        for (int i = 0; i < players.size(); i++) {
            subjectsByUuid.put(players.get(i).uuid(), i);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    void begin(Collection<? extends Player> players) throws IOException {
        for (Player player : players) {
            createViewer(player);
        }
        rebuild(0, List.copyOf(viewers.values()), true);
        for (Viewer viewer : viewers.values()) {
            viewer.setFrozen(paused);
            viewer.live.flush();
            ControlPanel.send(viewer.player, this);
        }
        new PlaybackStartEvent(this).callEvent();
    }

    private Viewer createViewer(Player player) {
        manager.getSession(player.getUniqueId()).ifPresent(other -> other.removeViewer(player, false));
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user == null) {
            throw new IllegalStateException(player.getName() + " is not connected");
        }
        Viewer viewer = new Viewer(player, user);
        viewers.put(player.getUniqueId(), viewer);
        manager.registerViewer(player.getUniqueId(), this);
        viewer.setup(plugin);
        return viewer;
    }

    void tick() {
        if (!running) {
            return;
        }
        try {
            if (!paused) {
                accumulator += speed;
                int steps = (int) accumulator;
                accumulator -= steps;
                for (int i = 0; i < steps; i++) {
                    if (tick >= info.durationTicks()) {
                        setPaused(true);
                        break;
                    }
                    tick++;
                    cursor.forEachUntil(tick, record -> dispatch(record, false));
                }
            }
            for (Viewer viewer : viewers.values()) {
                updateCamera(viewer);
                followPointOfView(viewer);
                viewer.live.flush();
            }
            if (Bukkit.getCurrentTick() % 4 == 0) {
                updateHud();
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Error while playing the replay " + info.name() + ", stopping it", e);
            viewers.values().forEach(viewer -> viewer.player.sendMessage(Component.text("Erreur pendant la lecture du replay.", NamedTextColor.RED)));
            stop();
        }
    }

    // ------------------------------------------------------------------ timeline

    private void dispatch(ReplayRecord record, boolean fast) {
        switch (record) {
            case ReplayRecord.Event event -> {
                switch (event.event()) {
                    case ReplayRecord.WorldChange change -> changeWorldLive(change.worldIndex());
                    case ReplayRecord.CaptureStop stop -> viewers.values().forEach(viewer -> viewer.pipeline.onCaptureStop(stop.index(), viewer.live));
                    case ReplayRecord.Marker marker -> {
                        if (!fast) {
                            Component message = Component.text("⚑ " + marker.name(), NamedTextColor.GOLD);
                            viewers.values().forEach(viewer -> viewer.player.sendActionBar(message));
                        }
                    }
                    default -> {
                    }
                }
            }
            case ReplayRecord.Blocks blocks -> {
                for (int i = 0; i < blocks.positions().length; i++) {
                    BlockData data = blockData(blocks.states()[i]);
                    if (data != null) {
                        currentWorld.setBlock(blocks.positions()[i], data);
                    }
                }
            }
            case ReplayRecord.Self self -> {
                trackSubject(self, subjectPositions);
                viewers.values().forEach(viewer -> viewer.pipeline.accept(self, viewer.live, fast));
            }
            case ReplayRecord.Packet packet -> viewers.values().forEach(viewer -> viewer.pipeline.accept(packet, viewer.live, fast));
        }
    }

    private static final class Scan {
        int entry;
        final Map<Long, Integer> modifications = new HashMap<>();
        final Map<Integer, double[]> positions = new HashMap<>();
    }

    /**
     * Recomputes the state at {@code target} for the given viewers by reading the timeline from the start.
     *
     * @param includeWorld also move the session itself (cursor, world, blocks) to {@code target}
     */
    private void rebuild(int target, Collection<Viewer> targets, boolean includeWorld) throws IOException {
        Map<Viewer, StateCompactor> compactors = new LinkedHashMap<>();
        for (Viewer viewer : targets) {
            viewer.detachCamera();
            viewer.live.resetClient();
            viewer.pipeline.reset();
            compactors.put(viewer, new StateCompactor(viewer.user));
        }
        Scan scan = new Scan();
        ReplayFileReader.Cursor scanCursor = reader.cursor();
        scanCursor.forEachUntil(target, record -> {
            switch (record) {
                case ReplayRecord.Event event -> {
                    if (event.event() instanceof ReplayRecord.WorldChange change) {
                        scan.entry = change.worldIndex();
                        scan.modifications.clear();
                        scan.positions.clear();
                        compactors.forEach((viewer, compactor) -> {
                            viewer.pipeline.onWorldChange();
                            compactor.onWorldChange();
                        });
                    } else if (event.event() instanceof ReplayRecord.CaptureStop stop) {
                        compactors.forEach((viewer, compactor) -> viewer.pipeline.onCaptureStop(stop.index(), compactor));
                    }
                }
                case ReplayRecord.Blocks blocks -> {
                    if (includeWorld) {
                        for (int i = 0; i < blocks.positions().length; i++) {
                            scan.modifications.put(blocks.positions()[i], blocks.states()[i]);
                        }
                    }
                }
                case ReplayRecord.Self self -> {
                    trackSubject(self, scan.positions);
                    compactors.forEach((viewer, compactor) -> viewer.pipeline.accept(self, compactor, true));
                }
                case ReplayRecord.Packet packet -> compactors.forEach((viewer, compactor) -> viewer.pipeline.accept(packet, compactor, true));
            }
        });
        if (includeWorld) {
            cursor = scanCursor;
            tick = target;
            accumulator = 0;
            subjectPositions.clear();
            subjectPositions.putAll(scan.positions);
            enterWorld(scan.entry, scan.modifications);
        }
        for (Map.Entry<Viewer, StateCompactor> entry : compactors.entrySet()) {
            Viewer viewer = entry.getKey();
            ensureInWorld(viewer);
            entry.getValue().flushTo(viewer.live);
            viewer.live.flush();
            updateCamera(viewer);
        }
    }

    private void enterWorld(int index, Map<Long, Integer> modifications) throws IOException {
        ReplayWorld replayWorld = info.worlds().get(index);
        PlaybackWorld world = worlds.get(replayWorld.mapId());
        boolean loaded = world.world() != null;
        World bukkitWorld = world.load(replayWorld, plugin.getLogger());
        if (!loaded) {
            manager.registerWorld(bukkitWorld.getUID());
        }
        world.syncTo(modifications, this::blockData);
        entryIndex = index;
        currentWorld = world;
    }

    private void changeWorldLive(int index) {
        subjectPositions.clear();
        for (Viewer viewer : viewers.values()) {
            viewer.detachCamera();
            viewer.live.destroyEntities(-1);
            viewer.pipeline.onWorldChange();
        }
        try {
            enterWorld(index, Map.of());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load the map " + info.worlds().get(index).mapId(), e);
        }
        viewers.values().forEach(this::ensureInWorld);
    }

    private void ensureInWorld(Viewer viewer) {
        World world = currentWorld.world();
        if (viewer.player.getWorld().equals(world)) {
            return;
        }
        viewer.player.teleport(viewerTarget(viewer, world));
        viewer.live.onClientWorldChange();
        viewer.cameraEntity = -1;
    }

    private Location viewerTarget(Viewer viewer, World world) {
        Integer pov = viewer.pointOfView();
        double[] position = pov == null ? null : subjectPositions.get(pov);
        if (position != null) {
            return new Location(world, position[0], position[1], position[2], (float) position[3], (float) position[4]);
        }
        ReplayWorld replayWorld = info.worlds().get(Math.max(entryIndex, 0));
        return new Location(world, replayWorld.x(), replayWorld.y(), replayWorld.z(), replayWorld.yaw(), replayWorld.pitch());
    }

    private void trackSubject(ReplayRecord.Self self, Map<Integer, double[]> positions) {
        if (self.type() != Server.SPAWN_ENTITY && self.type() != Server.ENTITY_TELEPORT) {
            return;
        }
        User user = anyUser();
        if (user == null) {
            return;
        }
        if (self.type() == Server.SPAWN_ENTITY) {
            WrapperPlayServerSpawnEntity spawn = PacketCodec.decode(WrapperPlayServerSpawnEntity::new, self.type(), self.payload(), user);
            Vector3d position = spawn.getPosition();
            positions.put(self.subject(), new double[]{position.getX(), position.getY(), position.getZ(), spawn.getYaw(), spawn.getPitch()});
            subjectsByEntity.put(EntityRemapper.id(spawn.getEntityId()), self.subject());
        } else {
            WrapperPlayServerEntityTeleport teleport = PacketCodec.decode(WrapperPlayServerEntityTeleport::new, self.type(), self.payload(), user);
            Vector3d position = teleport.getPosition();
            positions.put(self.subject(), new double[]{position.getX(), position.getY(), position.getZ(), teleport.getYaw(), teleport.getPitch()});
        }
    }

    private User anyUser() {
        for (Viewer viewer : viewers.values()) {
            return viewer.user;
        }
        return null;
    }

    private BlockData blockData(int state) {
        return blockData.computeIfAbsent(state, id -> {
            try {
                return SpigotConversionUtil.toBukkitBlockData(WrappedBlockState.getByGlobalId(recordedVersion, id));
            } catch (RuntimeException e) {
                return null;
            }
        });
    }

    // ------------------------------------------------------------------ viewers

    private void updateCamera(Viewer viewer) {
        Integer pov = viewer.pointOfView();
        if (pov == null) {
            viewer.detachCamera();
            return;
        }
        Integer original = viewer.pipeline.subjectEntity(pov);
        int target = original == null ? -1 : EntityRemapper.id(original);
        if (target != -1 && viewer.live.hasEntity(target)) {
            if (viewer.cameraEntity != target || viewer.cameraSpawn != viewer.live.spawnCount(target)) {
                viewer.attachCamera(target);
            }
        } else if (viewer.cameraEntity != -1) {
            viewer.cameraEntity = -1; // the entity is gone, the client put the camera back on the viewer
        }
    }

    /** Keeps a point of view viewer near the watched player so that the server sends them the right chunks. */
    private void followPointOfView(Viewer viewer) {
        Integer pov = viewer.pointOfView();
        double[] position = pov == null ? null : subjectPositions.get(pov);
        if (position == null || !viewer.player.getWorld().equals(currentWorld.world())) {
            return;
        }
        Location location = viewer.player.getLocation();
        double dx = location.getX() - position[0];
        double dy = location.getY() - position[1];
        double dz = location.getZ() - position[2];
        if (dx * dx + dy * dy + dz * dz > POV_FOLLOW_DISTANCE * POV_FOLLOW_DISTANCE) {
            viewer.player.teleport(new Location(currentWorld.world(), position[0], position[1], position[2], location.getYaw(), location.getPitch()));
        }
    }

    private void updateHud() {
        float progress = info.durationTicks() <= 0 ? 0 : Math.min(1, (float) tick / info.durationTicks());
        for (Viewer viewer : viewers.values()) {
            Integer pov = viewer.pointOfView();
            String view = pov == null ? "Caméra libre" : "POV " + info.players().get(pov).name();
            Component title = Component.text((paused ? "⏸ " : "▶ ") + TimeFormat.format(tick) + " / " + TimeFormat.format(info.durationTicks())
                    + "  ·  x" + formatSpeed(speed) + "  ·  " + view);
            viewer.hud.name(title);
            viewer.hud.progress(progress);
            viewer.hud.color(paused ? BossBar.Color.YELLOW : BossBar.Color.GREEN);
        }
    }

    static String formatSpeed(double speed) {
        return speed == Math.floor(speed) ? String.valueOf((int) speed) : String.valueOf(Math.round(speed * 100) / 100.0);
    }

    void removeViewer(Player player, boolean teleport) {
        Viewer viewer = viewers.remove(player.getUniqueId());
        if (viewer == null) {
            return;
        }
        manager.unregisterViewer(player.getUniqueId());
        viewer.restore(plugin, teleport);
        if (viewers.isEmpty()) {
            stop();
        }
    }

    /** A server player joined: viewers must not see them, nor be seen. */
    void hideFromViewers(Player player) {
        for (Viewer viewer : viewers.values()) {
            viewer.hide(plugin, player);
        }
    }

    /** The viewer picked a player in the spectator menu (the UUID is the scrambled one). */
    void handleSpectate(Player player, UUID target) {
        Integer subject = subjectsByUuid.get(EntityRemapper.uuid(target));
        if (subject != null && viewers.containsKey(player.getUniqueId())) {
            setPointOfView(player, info.players().get(subject).uuid());
        }
    }

    /** The viewer clicked on a replay entity. */
    void handleInteract(Player player, int entityId) {
        Integer subject = subjectsByEntity.get(entityId);
        if (subject != null && viewers.containsKey(player.getUniqueId())) {
            setPointOfView(player, info.players().get(subject).uuid());
        }
    }

    boolean isWorldOfSession(World world) {
        for (PlaybackWorld playbackWorld : worlds.values()) {
            if (world.equals(playbackWorld.world())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ API

    @Override
    public @NotNull UUID getId() {
        return id;
    }

    @Override
    public @NotNull ReplayInfo getReplay() {
        return info;
    }

    @Override
    public @NotNull Set<Player> getViewers() {
        Set<Player> players = new LinkedHashSet<>();
        viewers.values().forEach(viewer -> players.add(viewer.player));
        return players;
    }

    @Override
    public void addViewer(@NotNull Player player) {
        checkRunning();
        if (viewers.containsKey(player.getUniqueId())) {
            return;
        }
        Viewer viewer = createViewer(player);
        try {
            rebuild(tick, List.of(viewer), false);
        } catch (IOException e) {
            removeViewer(player, true);
            throw new IllegalStateException("Cannot read the replay", e);
        }
        viewer.setFrozen(paused);
        viewer.live.flush();
        ControlPanel.send(player, this);
    }

    @Override
    public void removeViewer(@NotNull Player player) {
        removeViewer(player, true);
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) {
            accumulator = 0;
        } else if (tick >= info.durationTicks()) {
            seek(0);
        }
        viewers.values().forEach(viewer -> viewer.setFrozen(this.paused));
        updateHud();
    }

    @Override
    public double getSpeed() {
        return speed;
    }

    @Override
    public void setSpeed(double speed) {
        this.speed = Math.max(0.1, Math.min(10, speed));
        viewers.values().forEach(viewer -> viewer.setFrozen(paused));
        updateHud();
    }

    @Override
    public int getTick() {
        return tick;
    }

    @Override
    public void seek(int target) {
        checkRunning();
        target = Math.max(0, Math.min(info.durationTicks(), target));
        if (target == tick) {
            return;
        }
        try {
            if (target > tick && target - tick <= plugin.getReplayConfig().fastForwardLimitTicks()) {
                tick = target;
                accumulator = 0;
                cursor.forEachUntil(target, record -> dispatch(record, true));
                for (Viewer viewer : viewers.values()) {
                    updateCamera(viewer);
                    viewer.live.flush();
                }
            } else {
                rebuild(target, List.copyOf(viewers.values()), true);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the replay", e);
        }
        updateHud();
    }

    @Override
    public @NotNull Optional<UUID> getPointOfView(@NotNull Player viewer) {
        Viewer v = viewers.get(viewer.getUniqueId());
        if (v == null || v.pointOfView() == null) {
            return Optional.empty();
        }
        return Optional.of(info.players().get(v.pointOfView()).uuid());
    }

    @Override
    public void setPointOfView(@NotNull Player player, @Nullable UUID target) {
        checkRunning();
        Viewer viewer = viewers.get(player.getUniqueId());
        if (viewer == null) {
            throw new IllegalArgumentException(player.getName() + " is not watching this replay");
        }
        Integer subject = null;
        if (target != null) {
            subject = subjectsByUuid.get(target);
            if (subject == null) {
                throw new IllegalArgumentException(target + " is not recorded in this replay");
            }
        }
        if (Objects.equals(subject, viewer.pointOfView())) {
            return;
        }
        viewer.pipeline.setPointOfView(subject);
        try {
            rebuild(tick, List.of(viewer), false);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the replay", e);
        }
        if (subject != null) {
            double[] position = subjectPositions.get(subject);
            if (position != null) {
                viewer.player.teleport(new Location(currentWorld.world(), position[0], position[1], position[2], (float) position[3], (float) position[4]));
            }
            viewer.player.sendActionBar(Component.text("Point de vue de " + info.players().get(subject).name()
                    + " (sneak pour revenir en caméra libre)", NamedTextColor.AQUA));
        } else {
            viewer.player.sendActionBar(Component.text("Caméra libre", NamedTextColor.AQUA));
        }
        updateHud();
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        for (Viewer viewer : List.copyOf(viewers.values())) {
            manager.unregisterViewer(viewer.player.getUniqueId());
            viewer.restore(plugin, true);
        }
        viewers.clear();
        manager.remove(this);
        for (PlaybackWorld world : worlds.values()) {
            if (world.world() != null) {
                manager.unregisterWorld(world.world().getUID());
            }
            world.unload();
        }
        manager.cleanupAsync(this, worlds.values(), reader, info.id(), file);
        new PlaybackStopEvent(this).callEvent();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    int getEntryIndex() {
        return entryIndex;
    }

    private void checkRunning() {
        if (!running) {
            throw new IllegalStateException("The playback session is stopped");
        }
    }
}
