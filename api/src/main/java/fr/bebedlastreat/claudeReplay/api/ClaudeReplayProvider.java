package fr.bebedlastreat.claudeReplay.api;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Static access to the {@link ClaudeReplayAPI} instance.
 */
public final class ClaudeReplayProvider {

    private static ClaudeReplayAPI instance;

    private ClaudeReplayProvider() {
    }

    /**
     * @throws IllegalStateException if ClaudeReplay is not enabled
     */
    public static @NotNull ClaudeReplayAPI get() {
        ClaudeReplayAPI api = instance;
        if (api == null) {
            throw new IllegalStateException("ClaudeReplay is not enabled");
        }
        return api;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    @ApiStatus.Internal
    public static void register(@Nullable ClaudeReplayAPI api) {
        instance = api;
    }
}
