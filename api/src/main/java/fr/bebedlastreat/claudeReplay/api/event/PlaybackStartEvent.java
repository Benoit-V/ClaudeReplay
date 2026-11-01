package fr.bebedlastreat.claudeReplay.api.event;

import fr.bebedlastreat.claudeReplay.api.playback.PlaybackSession;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called when a playback session starts (its viewers are inside the replay).
 */
public class PlaybackStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PlaybackSession session;

    public PlaybackStartEvent(@NotNull PlaybackSession session) {
        this.session = session;
    }

    public @NotNull PlaybackSession getSession() {
        return session;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
