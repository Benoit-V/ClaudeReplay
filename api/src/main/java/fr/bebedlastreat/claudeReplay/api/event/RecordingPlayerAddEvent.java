package fr.bebedlastreat.claudeReplay.api.event;

import fr.bebedlastreat.claudeReplay.api.recording.Recording;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called when a player is about to be added to a recording (manually or automatically).
 */
public class RecordingPlayerAddEvent extends RecordingEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final boolean automatic;
    private boolean cancelled;

    public RecordingPlayerAddEvent(@NotNull Recording recording, @NotNull Player player, boolean automatic) {
        super(recording);
        this.player = player;
        this.automatic = automatic;
    }

    public @NotNull Player getPlayer() {
        return player;
    }

    /** True if the player is added because of {@link fr.bebedlastreat.claudeReplay.api.recording.PlayerTrackingMode#WORLD}. */
    public boolean isAutomatic() {
        return automatic;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
