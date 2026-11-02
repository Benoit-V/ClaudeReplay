package fr.bebedlastreat.claudeReplay.util;

/**
 * Tick counter of the plugin (old servers do not expose the server tick). Incremented by the main task.
 */
public final class ServerTicks {

    private static volatile int current;

    private ServerTicks() {
    }

    public static int current() {
        return current;
    }

    public static void increment() {
        current++;
    }
}
