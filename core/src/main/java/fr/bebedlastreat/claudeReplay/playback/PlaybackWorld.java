package fr.bebedlastreat.claudeReplay.playback;

import fr.bebedlastreat.claudeReplay.api.replay.ReplayWorld;
import fr.bebedlastreat.claudeReplay.format.BlockPositions;
import fr.bebedlastreat.claudeReplay.platform.Platform;
import fr.bebedlastreat.claudeReplay.platform.Platforms;
import fr.bebedlastreat.claudeReplay.util.Files2;
import org.bukkit.Bukkit;
import org.bukkit.World;
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
import java.util.function.IntUnaryOperator;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * A temporary copy of a base map, in which a session applies the recorded block modifications.
 * <p>
 * The original state of every modified block is remembered, so the world can be brought back to the state of
 * any tick without reloading it. Block states are global ids of the server's protocol.
 */
final class PlaybackWorld {

    /**
     * Never copied: entities are replayed from the packets, POI and lock files are useless, and the Paper world
     * identity ({@code data/paper/metadata.dat}, {@code paper-world.yml}) would make Paper refuse the copy as a
     * duplicate of the original world. Forced chunk tickets would keep chunks loaded for nothing.
     */
    private static final Set<String> EXCLUDED = Set.of("entities", "poi", "session.lock", "uid.dat",
            "paper", "paper-world.yml", "chunk_tickets.dat");

    private final Platform platform = Platforms.get();
    private final String mapId;
    private final String id;
    private final Path copyFolder;
    private Path worldFolder;
    private World world;
    private final Map<Long, Integer> originals = new HashMap<>();

    PlaybackWorld(String mapId, String id) {
        this.mapId = mapId;
        this.id = id;
        this.copyFolder = platform.playbackWorldFolder(id);
    }

    String mapId() {
        return mapId;
    }

    World world() {
        return world;
    }

    Path folder() {
        return worldFolder != null ? worldFolder : copyFolder;
    }

    /**
     * Finds the folder holding the region files: the map itself, or the overworld/nether/end of a full level.
     */
    static Path findDimensionRoot(Path map, World.Environment environment) throws IOException {
        if (Files.isDirectory(map.resolve("region"))) {
            return map;
        }
        String dimension;
        String legacy;
        switch (environment) {
            case NETHER -> {
                dimension = "the_nether";
                legacy = "DIM-1";
            }
            case THE_END -> {
                dimension = "the_end";
                legacy = "DIM1";
            }
            default -> {
                dimension = "overworld";
                legacy = ".";
            }
        }
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
        Files2.deleteRecursively(copyFolder);
        Files2.copyDirectory(source, copyFolder, EXCLUDED);
    }

    /** Loads the copied map as a Bukkit world. Main thread. */
    World load(ReplayWorld info, Logger logger) throws IOException {
        if (world != null) {
            return world;
        }
        world = platform.createPlaybackWorld(id, info);
        Path actual = platform.worldFolder(world);
        if (!actual.toAbsolutePath().normalize().equals(copyFolder.toAbsolutePath().normalize())) {
            // The server stores custom worlds elsewhere than expected: copy the map where it really is and reload.
            logger.warning("Replay world stored in " + actual + " instead of " + copyFolder + ", reloading it");
            Bukkit.unloadWorld(world, false);
            Files2.deleteRecursively(actual);
            Files2.copyDirectory(copyFolder, actual, EXCLUDED);
            Files2.deleteRecursively(copyFolder);
            world = platform.createPlaybackWorld(id, info);
        }
        worldFolder = platform.worldFolder(world);
        world.setAutoSave(false);
        platform.freezeWorld(world);
        world.setStorm(false);
        world.setThundering(false);
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }
        return world;
    }

    /** Sets a block (server protocol state), remembering its original state. */
    void setBlock(long position, int state) {
        int x = BlockPositions.x(position);
        int y = BlockPositions.y(position);
        int z = BlockPositions.z(position);
        if (y < platform.minHeight(world) || y >= world.getMaxHeight()) {
            return;
        }
        int current = platform.getBlock(world, x, y, z);
        originals.putIfAbsent(position, current);
        if (current != state) {
            platform.setBlock(world, x, y, z, state);
        }
    }

    /**
     * Brings the world to "original map + {@code modifications}": modified positions get their recorded state,
     * every other position touched before goes back to its original state.
     *
     * @param states converts a recorded state to the server's protocol (negative: unknown, skipped)
     */
    void syncTo(Map<Long, Integer> modifications, IntUnaryOperator states) {
        Set<Long> positions = new HashSet<>(originals.keySet());
        positions.addAll(modifications.keySet());
        for (long position : positions) {
            Integer recorded = modifications.get(position);
            int state;
            if (recorded != null) {
                state = states.applyAsInt(recorded);
            } else {
                Integer original = originals.get(position);
                state = original == null ? -1 : original;
            }
            if (state >= 0) {
                setBlock(position, state);
            }
        }
    }

    /** Unloads the world (viewers must have left it). Main thread. */
    void unload() {
        if (world != null) {
            for (Player player : world.getPlayers()) {
                player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            }
            Bukkit.unloadWorld(world, false);
        }
    }

    /** Deletes the files (after {@link #unload()}). Any thread. */
    void delete() throws IOException {
        Files2.deleteRecursively(folder());
    }
}
