package fr.bebedlastreat.claudeReplay.api.event;

import fr.bebedlastreat.claudeReplay.api.recording.Recording;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Called after a player has been removed from a recording.
 */
public class RecordingPlayerRemoveEvent extends RecordingEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final boolean automatic;

    public RecordingPlayerRemoveEvent(@NotNull Recording recording, @NotNull UUID player, boolean automatic) {
        super(recording);
        this.player = player;
        this.automatic = automatic;
    }

    public @NotNull UUID getPlayer() {
        return player;
    }

    public boolean isAutomatic() {
        return automatic;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
