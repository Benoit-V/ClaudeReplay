package fr.bebedlastreat.claudeReplay.api.event;

import fr.bebedlastreat.claudeReplay.api.recording.Recording;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;

/**
 * Base class of the events related to a {@link Recording}. Always called on the main thread.
 */
public abstract class RecordingEvent extends Event {

    private final Recording recording;

    protected RecordingEvent(@NotNull Recording recording) {
        this.recording = recording;
    }

    public @NotNull Recording getRecording() {
        return recording;
    }
}
