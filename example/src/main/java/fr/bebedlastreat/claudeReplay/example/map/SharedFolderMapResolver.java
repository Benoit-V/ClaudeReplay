package fr.bebedlastreat.claudeReplay.example.map;

import fr.bebedlastreat.claudeReplay.api.storage.MapResolver;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Use case: a network whose maps are stored once, as {@code <mapId>.zip} files in a folder shared by every server
 * (network drive, synced folder...). A replay recorded on one server can then be watched on any other.
 * <p>
 * Each zip is extracted once into a local cache, again when the zip changes. Maps that are not in the shared
 * folder are given by the previous resolver (ClaudeReplay's {@code plugins/ClaudeReplay/maps}).
 */
public final class SharedFolderMapResolver implements MapResolver {

    private final Path shared;
    private final Path cache;
    private final MapResolver fallback;
    private final Executor executor;

    public SharedFolderMapResolver(Path shared, Path cache, MapResolver fallback, Executor executor) {
        this.shared = shared;
        this.cache = cache;
        this.fallback = fallback;
        this.executor = executor;
    }

    @Override
    public @NotNull CompletableFuture<Path> resolve(@NotNull String mapId) {
        Path zip = shared.resolve(mapId + ".zip");
        if (!Files.isRegularFile(zip)) {
            return fallback.resolve(mapId);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return extract(mapId, zip);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, executor);
    }

    /** Extracts the zip into the cache unless the cached copy is already up to date. */
    private synchronized Path extract(String mapId, Path zip) throws IOException {
        Path target = cache.resolve(mapId);
        Path stamp = cache.resolve(mapId + ".stamp");
        FileTime modified = Files.getLastModifiedTime(zip);
        if (Files.isDirectory(target) && Files.exists(stamp) && Files.getLastModifiedTime(stamp).equals(modified)) {
            return target;
        }
        deleteRecursively(target);
        Files.createDirectories(target);
        try (InputStream in = Files.newInputStream(zip); ZipInputStream entries = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = entries.getNextEntry()) != null) {
                Path file = target.resolve(entry.getName()).normalize();
                if (!file.startsWith(target)) {
                    throw new IOException("Unsafe entry in " + zip + ": " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(file);
                } else {
                    Files.createDirectories(file.getParent());
                    Files.copy(entries, file);
                }
            }
        }
        Files.deleteIfExists(stamp);
        Files.createFile(stamp);
        Files.setLastModifiedTime(stamp, modified);
        return target;
    }

    private static void deleteRecursively(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }
}
