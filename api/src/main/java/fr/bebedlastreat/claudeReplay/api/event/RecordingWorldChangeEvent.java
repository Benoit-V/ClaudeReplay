package fr.bebedlastreat.claudeReplay.api.event;

import fr.bebedlastreat.claudeReplay.api.recording.Recording;
import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called after a recording switched to another world.
 */
public class RecordingWorldChangeEvent extends RecordingEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final World from;
    private final World to;
    private final String mapId;

    public RecordingWorldChangeEvent(@NotNull Recording recording, @NotNull World from, @NotNull World to, @NotNull String mapId) {
        super(recording);
        this.from = from;
        this.to = to;
        this.mapId = mapId;
    }

    public @NotNull World getFrom() {
        return from;
    }

    public @NotNull World getTo() {
        return to;
    }

    public @NotNull String getMapId() {
        return mapId;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
