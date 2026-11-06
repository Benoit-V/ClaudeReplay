package fr.bebedlastreat.claudeReplay.api.recording;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Parameters of a new recording. Build it with {@link #builder(World)}.
 */
public final class RecordingOptions {

    private final String name;
    private final World world;
    private final String mapId;
    private final PlayerTrackingMode trackingMode;
    private final Set<UUID> players;
    private final Map<String, String> metadata;
    private final int maxDurationTicks;
    private final int keepLastTicks;

    private RecordingOptions(Builder builder) {
        this.name = builder.name;
        this.world = builder.world;
        this.mapId = builder.mapId;
        this.trackingMode = builder.trackingMode;
        this.players = Collections.unmodifiableSet(new LinkedHashSet<>(builder.players));
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
        this.maxDurationTicks = builder.maxDurationTicks;
        this.keepLastTicks = builder.keepLastTicks;
    }

    public static @NotNull Builder builder(@NotNull World world) {
        return new Builder(world);
    }

    public @NotNull String getName() {
        return name;
    }

    public @NotNull World getWorld() {
        return world;
    }

    /**
     * Identifier of the base map used at playback time to rebuild the world.
     * Defaults to the world name.
     */
    public @NotNull String getMapId() {
        return mapId;
    }

    public @NotNull PlayerTrackingMode getTrackingMode() {
        return trackingMode;
    }

    /** Players recorded from the start (in addition to the automatic ones in {@link PlayerTrackingMode#WORLD}). */
    public @NotNull Set<UUID> getPlayers() {
        return players;
    }

    public @NotNull Map<String, String> getMetadata() {
        return metadata;
    }

    /** Maximum duration in ticks, the recording stops by itself when reached. 0 = unlimited. */
    public int getMaxDurationTicks() {
        return maxDurationTicks;
    }

    /**
     * Maximum length of the saved replay in ticks. When the recording lasts longer, only its last ticks are kept:
     * the replay starts later, with the state of the game at that moment. 0 = keep everything.
     */
    public int getKeepLastTicks() {
        return keepLastTicks;
    }

    public static final class Builder {

        private final World world;
        private String name;
        private String mapId;
        private PlayerTrackingMode trackingMode = PlayerTrackingMode.WORLD;
        private final Set<UUID> players = new LinkedHashSet<>();
        private final Map<String, String> metadata = new LinkedHashMap<>();
        private int maxDurationTicks;
        private int keepLastTicks;

        private Builder(@NotNull World world) {
            this.world = Objects.requireNonNull(world, "world");
            this.name = world.getName();
            this.mapId = world.getName();
        }

        public @NotNull Builder name(@NotNull String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        public @NotNull Builder mapId(@NotNull String mapId) {
            this.mapId = Objects.requireNonNull(mapId, "mapId");
            return this;
        }

        public @NotNull Builder trackingMode(@NotNull PlayerTrackingMode mode) {
            this.trackingMode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        public @NotNull Builder player(@NotNull Player player) {
            this.players.add(player.getUniqueId());
            return this;
        }

        public @NotNull Builder player(@NotNull UUID player) {
            this.players.add(Objects.requireNonNull(player, "player"));
            return this;
        }

        public @NotNull Builder players(@NotNull Collection<? extends Player> players) {
            players.forEach(this::player);
            return this;
        }

        public @NotNull Builder metadata(@NotNull String key, @Nullable String value) {
            if (value == null) {
                this.metadata.remove(key);
            } else {
                this.metadata.put(key, value);
            }
            return this;
        }

        public @NotNull Builder maxDurationTicks(int ticks) {
            if (ticks < 0) {
                throw new IllegalArgumentException("ticks < 0");
            }
            this.maxDurationTicks = ticks;
            return this;
        }

        /**
         * Keeps only the last {@code ticks} ticks in the saved replay (e.g. {@code 20 * 60 * 5} for the last five
         * minutes). The recording can run as long as needed: older parts are dropped while it runs.
         * 0 = keep everything.
         */
        public @NotNull Builder keepLastTicks(int ticks) {
            if (ticks < 0) {
                throw new IllegalArgumentException("ticks < 0");
            }
            this.keepLastTicks = ticks;
            return this;
        }

        public @NotNull RecordingOptions build() {
            return new RecordingOptions(this);
        }
    }
}
