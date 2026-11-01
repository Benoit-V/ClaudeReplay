package fr.bebedlastreat.claudeReplay.api.playback;

import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A replay being watched by one or more viewers. The viewers share the timeline (pause, speed, position)
 * but each one chooses their own point of view.
 * <p>
 * All methods must be called from the main thread.
 */
public interface PlaybackSession {

    @NotNull UUID getId();

    @NotNull ReplayInfo getReplay();

    @NotNull Set<Player> getViewers();

    /** Adds a viewer. They start in free camera mode at the current position of the replay. */
    void addViewer(@NotNull Player player);

    /** Removes a viewer and restores their state. The session stops when its last viewer leaves. */
    void removeViewer(@NotNull Player player);

    boolean isPaused();

    void setPaused(boolean paused);

    double getSpeed();

    /** Sets the playback speed (0.1 to 10). */
    void setSpeed(double speed);

    /** Current tick of the replay. */
    int getTick();

    /** Moves the timeline to the given tick (clamped to the replay bounds). */
    void seek(int tick);

    /**
     * The recorded player whose point of view the viewer is watching, or empty in free camera mode.
     */
    @NotNull Optional<UUID> getPointOfView(@NotNull Player viewer);

    /**
     * Makes the viewer watch the replay through the eyes of a recorded player.
     *
     * @param target the real UUID of the recorded player, or {@code null} for the free camera
     *               (the merged view of every recorded player)
     */
    void setPointOfView(@NotNull Player viewer, @Nullable UUID target);

    /** Stops the session: every viewer is sent back and the temporary worlds are deleted. */
    void stop();

    boolean isRunning();
}
