package fr.bebedlastreat.claudeReplay;

import fr.bebedlastreat.claudeReplay.util.Lang;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;

/**
 * Values of config.yml.
 */
public record ReplayConfig(String language, Path replaysFolder, Path mapsFolder, boolean useLoadedWorldsAsMaps,
                           int segmentTicks, boolean verifyBlockChanges, int fastForwardLimitTicks) {

    public static ReplayConfig load(ClaudeReplay plugin) {
        FileConfiguration config = plugin.getConfig();
        Path dataFolder = plugin.getDataFolder().toPath();
        return new ReplayConfig(
                config.getString("language", Lang.DEFAULT_LANGUAGE),
                dataFolder.resolve(config.getString("storage.folder", "replays")),
                dataFolder.resolve(config.getString("maps.folder", "maps")),
                config.getBoolean("maps.use-loaded-worlds", true),
                Math.max(20, config.getInt("recording.segment-ticks", 200)),
                config.getBoolean("recording.verify-block-changes", true),
                Math.max(0, config.getInt("playback.fast-forward-limit-seconds", 15)) * 20
        );
    }
}
