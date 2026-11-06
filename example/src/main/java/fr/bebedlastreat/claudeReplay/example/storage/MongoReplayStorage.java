package fr.bebedlastreat.claudeReplay.example.storage;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayPlayer;
import fr.bebedlastreat.claudeReplay.api.storage.ReplayStorage;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.mongodb.client.model.Filters.eq;

/**
 * Keeps the replays in MongoDB: one document per replay in {@code replays}, the replay file in GridFS
 * ({@code replay_files}), since a file can be larger than the 16 MB limit of a document.
 * <p>
 * Documents also hold the names and UUIDs of the recorded players, so that other tools (a web panel...) can find
 * the replays of a player with a simple query.
 */
public final class MongoReplayStorage implements ReplayStorage {

    private final MongoCollection<Document> replays;
    private final GridFSBucket files;
    private final Executor executor;
    private final Path downloads;

    /**
     * @param executor  runs the queries, never the server main thread
     * @param downloads folder receiving the replays fetched for playback
     */
    public MongoReplayStorage(MongoDatabase database, Executor executor, Path downloads) throws IOException {
        this.replays = database.getCollection("replays");
        this.files = GridFSBuckets.create(database, "replay_files");
        this.executor = executor;
        this.downloads = Files.createDirectories(downloads);
        replays.createIndex(Indexes.descending("createdAt"));
        replays.createIndex(Indexes.ascending("players.uuid"));
    }

    @Override
    public @NotNull CompletableFuture<Void> save(@NotNull ReplayInfo info, @NotNull Path replayFile) {
        return CompletableFuture.runAsync(() -> {
            ObjectId fileId;
            try (InputStream data = Files.newInputStream(replayFile)) {
                fileId = files.uploadFromStream(info.id() + ".crp", data);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            List<Document> players = new ArrayList<>();
            for (ReplayPlayer player : info.players()) {
                players.add(new Document("uuid", player.uuid().toString()).append("name", player.name()));
            }
            Document document = new Document("_id", info.id().toString())
                    .append("name", info.name())
                    .append("createdAt", info.createdAt())
                    .append("durationTicks", info.durationTicks())
                    .append("serverVersion", info.serverVersion())
                    .append("players", players)
                    .append("metadata", new Document(new java.util.LinkedHashMap<>(info.metadata())))
                    .append("info", ReplayInfoJson.write(info))
                    .append("file", fileId);
            replays.replaceOne(eq("_id", info.id().toString()), document, new ReplaceOptions().upsert(true));
        }, executor);
    }

    @Override
    public @NotNull CompletableFuture<Path> fetch(@NotNull UUID replayId) {
        return CompletableFuture.supplyAsync(() -> {
            Document document = replays.find(eq("_id", replayId.toString())).first();
            if (document == null) {
                throw new NoSuchElementException("No replay " + replayId);
            }
            try {
                Path file = Files.createTempFile(downloads, replayId.toString(), ".crp");
                try (OutputStream out = Files.newOutputStream(file)) {
                    files.downloadToStream(document.getObjectId("file"), out);
                }
                return file;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, executor);
    }

    @Override
    public void release(@NotNull UUID replayId, @NotNull Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    @Override
    public @NotNull CompletableFuture<List<ReplayInfo>> list() {
        return CompletableFuture.supplyAsync(() -> {
            List<ReplayInfo> result = new ArrayList<>();
            for (Document document : replays.find().projection(new Document("info", 1)).sort(Sorts.descending("createdAt"))) {
                result.add(ReplayInfoJson.read(document.getString("info")));
            }
            return result;
        }, executor);
    }

    @Override
    public @NotNull CompletableFuture<Optional<ReplayInfo>> info(@NotNull UUID replayId) {
        return CompletableFuture.supplyAsync(() -> {
            Document document = replays.find(eq("_id", replayId.toString())).projection(new Document("info", 1)).first();
            return Optional.ofNullable(document).map(found -> ReplayInfoJson.read(found.getString("info")));
        }, executor);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> delete(@NotNull UUID replayId) {
        return CompletableFuture.supplyAsync(() -> {
            Document document = replays.findOneAndDelete(eq("_id", replayId.toString()));
            if (document == null) {
                return false;
            }
            files.delete(document.getObjectId("file"));
            return true;
        }, executor);
    }
}
