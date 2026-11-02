package fr.bebedlastreat.claudeReplay.storage;

import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.api.storage.ReplayStorage;
import fr.bebedlastreat.claudeReplay.format.ReplayFormat;
import fr.bebedlastreat.claudeReplay.format.ReplayInfoJson;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Default storage: {@code <folder>/<id>.crp} plus a {@code <id>.json} description used for listing.
 */
public final class FileReplayStorage implements ReplayStorage {

    private final Path folder;
    private final Executor executor;

    public FileReplayStorage(Path folder, Executor executor) {
        this.folder = folder;
        this.executor = executor;
    }

    private Path replayFile(UUID id) {
        return folder.resolve(id + ReplayFormat.EXTENSION);
    }

    private Path infoFile(UUID id) {
        return folder.resolve(id + ".json");
    }

    @Override
    public @NotNull CompletableFuture<Void> save(@NotNull ReplayInfo info, @NotNull Path replayFile) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(folder);
                Files.copy(replayFile, replayFile(info.id()), StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(infoFile(info.id()), ReplayInfoJson.write(info), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, executor);
    }

    @Override
    public @NotNull CompletableFuture<Path> fetch(@NotNull UUID replayId) {
        return CompletableFuture.supplyAsync(() -> {
            Path file = replayFile(replayId);
            if (!Files.isRegularFile(file)) {
                throw new UncheckedIOException(new NoSuchFileException(file.toString(), null, "Unknown replay " + replayId));
            }
            return file;
        }, executor);
    }

    @Override
    public @NotNull CompletableFuture<List<ReplayInfo>> list() {
        return CompletableFuture.supplyAsync(() -> {
            List<ReplayInfo> infos = new ArrayList<>();
            if (!Files.isDirectory(folder)) {
                return infos;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.json")) {
                for (Path path : stream) {
                    try {
                        infos.add(ReplayInfoJson.read(Files.readString(path, StandardCharsets.UTF_8)));
                    } catch (IOException | RuntimeException ignored) {
                        // corrupted description: skip it
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            infos.sort(Comparator.comparingLong(ReplayInfo::createdAt).reversed());
            return infos;
        }, executor);
    }

    @Override
    public @NotNull CompletableFuture<Optional<ReplayInfo>> info(@NotNull UUID replayId) {
        return CompletableFuture.supplyAsync(() -> {
            Path file = infoFile(replayId);
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            try {
                return Optional.of(ReplayInfoJson.read(Files.readString(file, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, executor);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> delete(@NotNull UUID replayId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                boolean deleted = Files.deleteIfExists(replayFile(replayId));
                return Files.deleteIfExists(infoFile(replayId)) || deleted;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, executor);
    }
}
