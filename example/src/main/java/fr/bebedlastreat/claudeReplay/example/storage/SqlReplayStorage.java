package fr.bebedlastreat.claudeReplay.example.storage;

import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.api.storage.ReplayStorage;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Keeps the replays in a MySQL / MariaDB table: the description as JSON, the replay file as a BLOB.
 * <p>
 * Every server of a network using the same database sees the same replays. Replay files are usually a few
 * hundred kilobytes to a few megabytes: the server's {@code max_allowed_packet} must be large enough.
 */
public final class SqlReplayStorage implements ReplayStorage {

    private static final String TABLE = "claudereplay_replays";

    private final DataSource dataSource;
    private final Executor executor;
    private final Path downloads;

    /**
     * @param dataSource a connection pool (HikariCP...)
     * @param executor   runs the queries, never the server main thread
     * @param downloads  folder receiving the replays fetched for playback
     */
    public SqlReplayStorage(DataSource dataSource, Executor executor, Path downloads) throws SQLException, IOException {
        this.dataSource = dataSource;
        this.executor = executor;
        this.downloads = Files.createDirectories(downloads);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "id CHAR(36) NOT NULL PRIMARY KEY,"
                    + "name VARCHAR(255) NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "info MEDIUMTEXT NOT NULL,"
                    + "data LONGBLOB NOT NULL,"
                    + "INDEX idx_created_at (created_at))");
        }
    }

    private interface Query<T> {
        T run(Connection connection) throws SQLException, IOException;
    }

    /** Runs a query on the executor with a pooled connection. */
    private <T> CompletableFuture<T> async(Query<T> query) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return query.run(connection);
            } catch (SQLException | IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public @NotNull CompletableFuture<Void> save(@NotNull ReplayInfo info, @NotNull Path replayFile) {
        return async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + TABLE
                    + " (id, name, created_at, info, data) VALUES (?, ?, ?, ?, ?)");
                 InputStream data = Files.newInputStream(replayFile)) {
                statement.setString(1, info.id().toString());
                statement.setString(2, info.name());
                statement.setLong(3, info.createdAt());
                statement.setString(4, ReplayInfoJson.write(info));
                statement.setBinaryStream(5, data, Files.size(replayFile));
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public @NotNull CompletableFuture<Path> fetch(@NotNull UUID replayId) {
        return async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT data FROM " + TABLE + " WHERE id = ?")) {
                statement.setString(1, replayId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new NoSuchElementException("No replay " + replayId);
                    }
                    Path file = Files.createTempFile(downloads, replayId.toString(), ".crp");
                    try (InputStream data = result.getBinaryStream(1)) {
                        Files.copy(data, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return file;
                }
            }
        });
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
        return async(connection -> {
            List<ReplayInfo> replays = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT info FROM " + TABLE + " ORDER BY created_at DESC")) {
                while (result.next()) {
                    replays.add(ReplayInfoJson.read(result.getString(1)));
                }
            }
            return replays;
        });
    }

    @Override
    public @NotNull CompletableFuture<Optional<ReplayInfo>> info(@NotNull UUID replayId) {
        return async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT info FROM " + TABLE + " WHERE id = ?")) {
                statement.setString(1, replayId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(ReplayInfoJson.read(result.getString(1))) : Optional.empty();
                }
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<Boolean> delete(@NotNull UUID replayId) {
        return async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + TABLE + " WHERE id = ?")) {
                statement.setString(1, replayId.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }
}
