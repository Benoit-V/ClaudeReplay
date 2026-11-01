package fr.bebedlastreat.claudeReplay.storage;

import fr.bebedlastreat.claudeReplay.api.storage.MapResolver;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Looks for the map in {@code <mapsFolder>/<mapId>}, then (optionally) uses the loaded world of the same name.
 * <p>
 * The fallback reads the live world: it is only right if that world was not modified since the recording started
 * (a lobby, a static map...). Mini-games should keep clean copies of their maps in the maps folder.
 */
public final class DefaultMapResolver implements MapResolver {

    private final Path mapsFolder;
    private final boolean useLoadedWorlds;

    public DefaultMapResolver(Path mapsFolder, boolean useLoadedWorlds) {
        this.mapsFolder = mapsFolder;
        this.useLoadedWorlds = useLoadedWorlds;
    }

    @Override
    public @NotNull CompletableFuture<Path> resolve(@NotNull String mapId) {
        Path template = mapsFolder.resolve(mapId);
        if (Files.isDirectory(template)) {
            return CompletableFuture.completedFuture(template);
        }
        if (useLoadedWorlds && Bukkit.isPrimaryThread()) {
            World world = Bukkit.getWorld(mapId);
            if (world != null) {
                return CompletableFuture.completedFuture(world.getWorldPath());
            }
        }
        return CompletableFuture.failedFuture(new UncheckedIOException(new FileNotFoundException(
                "No map '" + mapId + "' in " + mapsFolder)));
    }
}
