package fr.bebedlastreat.claudeReplay.api.event;

import fr.bebedlastreat.claudeReplay.api.recording.Recording;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called when a recording stops, before it is saved.
 */
public class RecordingStopEvent extends RecordingEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final boolean saved;

    public RecordingStopEvent(@NotNull Recording recording, boolean saved) {
        super(recording);
        this.saved = saved;
    }

    /** False if the recording was cancelled and will not be saved. */
    public boolean isSaved() {
        return saved;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
