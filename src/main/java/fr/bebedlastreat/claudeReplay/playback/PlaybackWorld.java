package fr.bebedlastreat.claudeReplay.playback;

import fr.bebedlastreat.claudeReplay.api.replay.ReplayWorld;
import fr.bebedlastreat.claudeReplay.format.BlockPositions;
import fr.bebedlastreat.claudeReplay.util.Files2;
import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * A temporary copy of a base map, in which a session applies the recorded block modifications.
 * <p>
 * The original state of every modified block is remembered, so the world can be brought back to the state of
 * any tick without reloading it.
 */
final class PlaybackWorld {

    /** Never copied: entities are replayed from the packets, POI and lock files are useless. */
    private static final Set<String> EXCLUDED = Set.of("entities", "poi", "session.lock", "uid.dat");

    private final String mapId;
    private final NamespacedKey key;
    private final Path dimensionFolder;
    private Path worldFolder;
    private World world;
    private final Map<Long, BlockData> originals = new HashMap<>();

    PlaybackWorld(String mapId, NamespacedKey key) {
        this.mapId = mapId;
        this.key = key;
        this.dimensionFolder = Bukkit.getServer().getLevelDirectory().resolve("dimensions").resolve(key.getNamespace()).resolve(key.getKey());
    }

    String mapId() {
        return mapId;
    }

    World world() {
        return world;
    }

    Path folder() {
        return worldFolder != null ? worldFolder : dimensionFolder;
    }

    /**
     * Finds the folder holding the region files: the map itself, or the overworld/nether/end of a full level.
     */
    static Path findDimensionRoot(Path map, World.Environment environment) throws IOException {
        if (Files.isDirectory(map.resolve("region"))) {
            return map;
        }
        String dimension = switch (environment) {
            case NETHER -> "the_nether";
            case THE_END -> "the_end";
            default -> "overworld";
        };
        String legacy = switch (environment) {
            case NETHER -> "DIM-1";
            case THE_END -> "DIM1";
            default -> ".";
        };
        for (Path candidate : List.of(map.resolve("dimensions").resolve("minecraft").resolve(dimension), map.resolve(legacy))) {
            if (Files.isDirectory(candidate.resolve("region"))) {
                return candidate.normalize();
            }
        }
        try (Stream<Path> stream = Files.find(map, 5, (path, attributes) -> attributes.isDirectory() && path.getFileName().toString().equals("region"))) {
            return stream.findFirst().map(Path::getParent)
                    .orElseThrow(() -> new IOException("No region folder found in " + map));
        }
    }

    /** Copies the map files. Any thread. */
    void prepare(Path map, World.Environment environment) throws IOException {
        Path source = findDimensionRoot(map, environment);
        Files2.deleteRecursively(dimensionFolder);
        Files2.copyDirectory(source, dimensionFolder, EXCLUDED);
    }

    /** Loads the copied map as a Bukkit world. Main thread. */
    World load(ReplayWorld info, Logger logger) throws IOException {
        if (world != null) {
            return world;
        }
        world = create(info);
        if (!world.getWorldPath().toAbsolutePath().normalize().equals(dimensionFolder.toAbsolutePath().normalize())) {
            // The server stores custom worlds elsewhere than expected: copy the map where it really is and reload.
            logger.warning("Replay world stored in " + world.getWorldPath() + " instead of " + dimensionFolder + ", reloading it");
            Path actual = world.getWorldPath();
            Bukkit.unloadWorld(world, false);
            Files2.deleteRecursively(actual);
            Files2.copyDirectory(dimensionFolder, actual, EXCLUDED);
            Files2.deleteRecursively(dimensionFolder);
            world = create(info);
        }
        worldFolder = world.getWorldPath();
        world.setAutoSave(false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.SPAWN_MONSTERS, false);
        world.setGameRule(GameRules.SPAWN_PATROLS, false);
        world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        world.setGameRule(GameRules.SPAWN_WARDENS, false);
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        world.setGameRule(GameRules.MOB_GRIEFING, false);
        world.setGameRule(GameRules.SPREAD_VINES, false);
        world.setGameRule(GameRules.LOCATOR_BAR, false);
        world.setStorm(false);
        world.setThundering(false);
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }
        return world;
    }

    private World create(ReplayWorld info) {
        World created = WorldCreator.ofKey(key)
                .environment(info.environment())
                .generator(new VoidGenerator())
                .generateStructures(false)
                .forcedSpawnPosition(Position.fine(info.x(), info.y(), info.z()), info.yaw(), info.pitch())
                .createWorld();
        if (created == null) {
            throw new IllegalStateException("Cannot create the replay world " + key);
        }
        return created;
    }

    /** Sets a block, remembering its original state. */
    void setBlock(long position, BlockData data) {
        int x = BlockPositions.x(position);
        int y = BlockPositions.y(position);
        int z = BlockPositions.z(position);
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return;
        }
        BlockData current = world.getBlockData(x, y, z);
        originals.putIfAbsent(position, current);
        if (!current.equals(data)) {
            world.getBlockAt(x, y, z).setBlockData(data, false);
        }
    }

    /**
     * Brings the world to "original map + {@code modifications}": modified positions get their recorded state,
     * every other position touched before goes back to its original state.
     */
    void syncTo(Map<Long, Integer> modifications, IntFunction<BlockData> states) {
        Set<Long> positions = new HashSet<>(originals.keySet());
        positions.addAll(modifications.keySet());
        for (long position : positions) {
            Integer state = modifications.get(position);
            BlockData data = state != null ? states.apply(state) : originals.get(position);
            if (data != null) {
                setBlock(position, data);
            }
        }
    }

    /** Unloads the world (viewers must have left it). Main thread. */
    void unload() {
        if (world != null) {
            for (Player player : world.getPlayers()) {
                player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
            }
            Bukkit.unloadWorld(world, false);
        }
    }

    /** Deletes the files (after {@link #unload()}). Any thread. */
    void delete() throws IOException {
        Files2.deleteRecursively(folder());
    }
}
