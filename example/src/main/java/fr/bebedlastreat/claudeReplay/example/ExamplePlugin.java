package fr.bebedlastreat.claudeReplay.example;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fr.bebedlastreat.claudeReplay.api.ClaudeReplayAPI;
import fr.bebedlastreat.claudeReplay.api.ClaudeReplayProvider;
import fr.bebedlastreat.claudeReplay.api.storage.ReplayStorage;
import fr.bebedlastreat.claudeReplay.example.map.SharedFolderMapResolver;
import fr.bebedlastreat.claudeReplay.example.minigame.GameRecorder;
import fr.bebedlastreat.claudeReplay.example.report.ReportBuffer;
import fr.bebedlastreat.claudeReplay.example.review.PlayerReplays;
import fr.bebedlastreat.claudeReplay.example.rules.RecordingRules;
import fr.bebedlastreat.claudeReplay.example.storage.MongoReplayStorage;
import fr.bebedlastreat.claudeReplay.example.storage.SqlReplayStorage;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Wires every example together. Each one is independent: copy the class you need into your own plugin.
 */
public final class ExamplePlugin extends JavaPlugin {

    private ClaudeReplayAPI api;
    private final ExecutorService io = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ClaudeReplayExamples-io");
        thread.setDaemon(true);
        return thread;
    });
    private ReplayStorage previousStorage;
    private AutoCloseable database;
    private ReportBuffer reports;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        api = ClaudeReplayProvider.get();

        setUpStorage(getConfig().getConfigurationSection("storage"));
        setUpMaps();

        GameRecorder games = new GameRecorder(api);
        getCommand("game").setExecutor(games);
        getServer().getPluginManager().registerEvents(games, this);

        reports = new ReportBuffer(api, getConfig().getInt("report.minutes", 5));
        getCommand("report").setExecutor(reports);
        for (String name : getConfig().getStringList("report.worlds")) {
            World world = getServer().getWorld(name);
            if (world != null) {
                reports.watch(world);
            }
        }

        getCommand("replaysof").setExecutor(new PlayerReplays(this, api));
        getServer().getPluginManager().registerEvents(new RecordingRules(getLogger()), this);
    }

    /** Replaces ClaudeReplay's local files with a database, depending on the config. */
    private void setUpStorage(ConfigurationSection config) {
        String type = config.getString("type", "file").toLowerCase(Locale.ROOT);
        Path downloads = getDataFolder().toPath().resolve("downloads");
        try {
            ReplayStorage storage;
            switch (type) {
                case "mysql" -> {
                    HikariConfig hikari = new HikariConfig();
                    hikari.setJdbcUrl(config.getString("mysql.url"));
                    // Named explicitly: drivers loaded as plugin libraries are not visible to DriverManager.
                    hikari.setDriverClassName(config.getString("mysql.driver", "org.mariadb.jdbc.Driver"));
                    hikari.setUsername(config.getString("mysql.user"));
                    hikari.setPassword(config.getString("mysql.password"));
                    hikari.setMaximumPoolSize(4);
                    HikariDataSource dataSource = new HikariDataSource(hikari);
                    database = dataSource;
                    storage = new SqlReplayStorage(dataSource, io, downloads);
                }
                case "mongodb" -> {
                    MongoClient client = MongoClients.create(config.getString("mongodb.uri"));
                    database = client;
                    storage = new MongoReplayStorage(client.getDatabase(config.getString("mongodb.database")), io, downloads);
                }
                default -> {
                    return; // keep ClaudeReplay's local files
                }
            }
            previousStorage = api.getStorage();
            api.setStorage(storage);
            getLogger().info("Replays are stored in " + type);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Cannot connect to the " + type + " database, replays stay in local files", e);
            closeDatabase();
        }
    }

    private void setUpMaps() {
        String shared = getConfig().getString("maps.shared-folder", "");
        if (!shared.isBlank()) {
            api.setMapResolver(new SharedFolderMapResolver(Path.of(shared), getDataFolder().toPath().resolve("map-cache"),
                    api.getMapResolver(), io));
        }
    }

    @Override
    public void onDisable() {
        if (reports != null) {
            reports.shutdown();
        }
        // Recordings still running are saved by ClaudeReplay when it stops, after this plugin: give it back a
        // storage that still works, and let the saves already sent to the database finish.
        if (previousStorage != null) {
            api.setStorage(previousStorage);
        }
        io.shutdown();
        try {
            io.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeDatabase();
    }

    private void closeDatabase() {
        if (database != null) {
            try {
                database.close();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Cannot close the database", e);
            }
            database = null;
        }
    }
}
