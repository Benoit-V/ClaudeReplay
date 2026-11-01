package fr.bebedlastreat.claudeReplay.api.event;

import fr.bebedlastreat.claudeReplay.api.recording.Recording;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called when a recording starts.
 */
public class RecordingStartEvent extends RecordingEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public RecordingStartEvent(@NotNull Recording recording) {
        super(recording);
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
