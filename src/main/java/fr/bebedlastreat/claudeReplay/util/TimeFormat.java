package fr.bebedlastreat.claudeReplay.util;

/**
 * Converts ticks to "mm:ss" and parses user time inputs.
 */
public final class TimeFormat {

    private TimeFormat() {
    }

    public static String format(int ticks) {
        int seconds = Math.max(0, ticks) / 20;
        int hours = seconds / 3600;
        int minutes = (seconds / 60) % 60;
        int secs = seconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, secs);
        }
        return String.format("%02d:%02d", minutes, secs);
    }

    /**
     * Parses "90", "1:30", "1:02:03" (seconds) or "200t" (ticks). Returns ticks.
     *
     * @throws IllegalArgumentException if the input is invalid
     */
    public static int parse(String input) {
        String value = input.trim();
        if (value.endsWith("t")) {
            return Integer.parseInt(value.substring(0, value.length() - 1));
        }
        if (value.endsWith("s")) {
            value = value.substring(0, value.length() - 1);
        }
        String[] parts = value.split(":");
        if (parts.length > 3) {
            throw new IllegalArgumentException("Invalid time: " + input);
        }
        double seconds = 0;
        for (String part : parts) {
            seconds = seconds * 60 + Double.parseDouble(part);
        }
        return (int) Math.round(seconds * 20);
    }
}
