package fr.bebedlastreat.claudeReplay.example.rules;

import fr.bebedlastreat.claudeReplay.api.event.PlaybackStartEvent;
import fr.bebedlastreat.claudeReplay.api.event.PlaybackStopEvent;
import fr.bebedlastreat.claudeReplay.api.event.RecordingPlayerAddEvent;
import fr.bebedlastreat.claudeReplay.api.event.RecordingStopEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.metadata.MetadataValue;

import java.util.logging.Logger;

/**
 * Use case: rules applied to every recording, whoever started it.
 * <ul>
 *     <li>staff in vanish (or with a permission) are never recorded;</li>
 *     <li>recordings and playbacks are logged, e.g. to audit who watched what.</li>
 * </ul>
 */
public final class RecordingRules implements Listener {

    public static final String HIDDEN_PERMISSION = "claudereplay.example.hidden";

    private final Logger logger;

    public RecordingRules(Logger logger) {
        this.logger = logger;
    }

    @EventHandler
    public void onAdd(RecordingPlayerAddEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(HIDDEN_PERMISSION) || isVanished(player)) {
            event.setCancelled(true);
        }
    }

    /** Most vanish plugins (SuperVanish, PremiumVanish, EssentialsX...) set this metadata. */
    private static boolean isVanished(Player player) {
        for (MetadataValue value : player.getMetadata("vanished")) {
            if (value.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onStop(RecordingStopEvent event) {
        logger.info("Recording " + event.getRecording().getName() + (event.isSaved() ? " stopped" : " cancelled")
                + " after " + event.getRecording().getCurrentTick() / 20 + "s");
    }

    @EventHandler
    public void onPlaybackStart(PlaybackStartEvent event) {
        logger.info(event.getSession().getViewers().stream().map(Player::getName).toList() + " started watching "
                + event.getSession().getReplay().name());
    }

    @EventHandler
    public void onPlaybackStop(PlaybackStopEvent event) {
        logger.info("Playback of " + event.getSession().getReplay().name() + " stopped");
    }
}
