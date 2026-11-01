package fr.bebedlastreat.claudeReplay.api.replay;

import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A period of a replay spent in one world.
 *
 * @param startTick   the tick at which the replay switched to this world
 * @param worldName   the name of the recorded world
 * @param mapId       the base map used to rebuild the world at playback time
 * @param environment the environment of the recorded world
 * @param x           a point of interest of the world (where viewers are teleported when entering it)
 */
public record ReplayWorld(int startTick, @NotNull String worldName, @NotNull String mapId,
                          @NotNull World.Environment environment,
                          double x, double y, double z, float yaw, float pitch) {

    public ReplayWorld {
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(mapId, "mapId");
        Objects.requireNonNull(environment, "environment");
    }

    public @NotNull ReplayWorld withPointOfInterest(double x, double y, double z, float yaw, float pitch) {
        return new ReplayWorld(startTick, worldName, mapId, environment, x, y, z, yaw, pitch);
    }
}
