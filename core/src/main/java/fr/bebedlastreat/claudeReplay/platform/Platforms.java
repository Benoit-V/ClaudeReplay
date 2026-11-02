package fr.bebedlastreat.claudeReplay.platform;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;

/**
 * Picks the {@link Platform} implementation of the running server. The implementations live in modules compiled
 * against their own server API, so they are loaded by name.
 */
public final class Platforms {

    private static Platform platform;

    private Platforms() {
    }

    public static Platform get() {
        Platform current = platform;
        if (current == null) {
            throw new IllegalStateException("No platform loaded");
        }
        return current;
    }

    public static Platform load(org.bukkit.plugin.Plugin plugin) {
        ServerVersion version = PacketCodec.serverVersion();
        String implementation;
        if (version.isOlderThan(ServerVersion.V_1_9)) {
            implementation = "fr.bebedlastreat.claudeReplay.platform.legacy.LegacyPlatform";
        } else if (version.isNewerThanOrEquals(ServerVersion.V_26_1)) {
            implementation = "fr.bebedlastreat.claudeReplay.platform.modern.ModernPlatform";
        } else {
            throw new IllegalStateException("Unsupported server version " + version.getReleaseName()
                    + " (supported: 1.8.8 and 26.x)");
        }
        try {
            platform = (Platform) Class.forName(implementation, true, Platforms.class.getClassLoader())
                    .getConstructor(org.bukkit.plugin.Plugin.class).newInstance(plugin);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Cannot load the platform " + implementation, e);
        }
        return platform;
    }

    public static void unload() {
        platform = null;
    }
}
