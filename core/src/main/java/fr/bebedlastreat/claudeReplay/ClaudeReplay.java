package fr.bebedlastreat.claudeReplay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import fr.bebedlastreat.claudeReplay.api.ClaudeReplayAPI;
import fr.bebedlastreat.claudeReplay.api.ClaudeReplayProvider;
import fr.bebedlastreat.claudeReplay.api.storage.MapResolver;
import fr.bebedlastreat.claudeReplay.api.storage.ReplayStorage;
import fr.bebedlastreat.claudeReplay.command.ReplayCommand;
import fr.bebedlastreat.claudeReplay.menu.Menus;
import fr.bebedlastreat.claudeReplay.platform.Platform;
import fr.bebedlastreat.claudeReplay.platform.Platforms;
import fr.bebedlastreat.claudeReplay.playback.PlaybackBukkitListener;
import fr.bebedlastreat.claudeReplay.playback.PlaybackManager;
import fr.bebedlastreat.claudeReplay.playback.ViewerPacketListener;
import fr.bebedlastreat.claudeReplay.recording.PlayerWorldTracker;
import fr.bebedlastreat.claudeReplay.recording.RecordingBukkitListener;
import fr.bebedlastreat.claudeReplay.recording.RecordingManager;
import fr.bebedlastreat.claudeReplay.recording.RecordingPacketListener;
import fr.bebedlastreat.claudeReplay.storage.DefaultMapResolver;
import fr.bebedlastreat.claudeReplay.storage.FileReplayStorage;
import fr.bebedlastreat.claudeReplay.util.ServerTicks;
import fr.bebedlastreat.claudeReplay.util.Threads;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClaudeReplay extends JavaPlugin {

    private ReplayConfig replayConfig;
    private ExecutorService storageExecutor;
    private volatile ReplayStorage storage;
    private volatile MapResolver mapResolver;
    private RecordingManager recordingManager;
    private PlaybackManager playbackManager;
    private final List<PacketListenerCommon> packetListeners = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        replayConfig = ReplayConfig.load(this);
        Platform platform = Platforms.load(this);
        getLogger().info("Server " + platform.minecraftVersion() + " (protocol " + getServerProtocolVersion() + ")");

        storageExecutor = Executors.newFixedThreadPool(2, Threads.daemon("ClaudeReplay-storage-"));
        storage = new FileReplayStorage(replayConfig.replaysFolder(), storageExecutor);
        mapResolver = new DefaultMapResolver(replayConfig.mapsFolder(), replayConfig.useLoadedWorldsAsMaps());

        recordingManager = new RecordingManager(this);
        playbackManager = new PlaybackManager(this);
        playbackManager.cleanupLeftovers();

        PlayerWorldTracker worldTracker = new PlayerWorldTracker();
        worldTracker.init();
        getServer().getPluginManager().registerEvents(worldTracker, this);
        getServer().getPluginManager().registerEvents(new RecordingBukkitListener(recordingManager), this);
        getServer().getPluginManager().registerEvents(new PlaybackBukkitListener(playbackManager), this);
        getServer().getPluginManager().registerEvents(new Menus(this), this);
        Platform.PlatformHooks hooks = new Platform.PlatformHooks() {
            @Override
            public boolean isViewer(UUID player) {
                return playbackManager.isViewer(player);
            }

            @Override
            public boolean isPlaybackWorld(World world) {
                return playbackManager.isPlaybackWorld(world);
            }

            @Override
            public void onOffHandSwing(Player player) {
                recordingManager.onOffHandSwing(player);
            }
        };
        for (Listener listener : platform.listeners(hooks)) {
            getServer().getPluginManager().registerEvents(listener, this);
        }
        packetListeners.add(PacketEvents.getAPI().getEventManager().registerListener(new RecordingPacketListener(recordingManager, worldTracker)));
        packetListeners.add(PacketEvents.getAPI().getEventManager().registerListener(new ViewerPacketListener(this, playbackManager)));

        getServer().getScheduler().runTaskTimer(this, () -> {
            ServerTicks.increment();
            recordingManager.tick();
            playbackManager.tick();
        }, 1, 1);

        ReplayCommand command = new ReplayCommand(this);
        PluginCommand replay = getCommand("replay");
        if (replay != null) {
            replay.setExecutor(command);
            replay.setTabCompleter(command);
        }

        ClaudeReplayAPI api = new ClaudeReplayApiImpl(this);
        ClaudeReplayProvider.register(api);
        getServer().getServicesManager().register(ClaudeReplayAPI.class, api, this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        ClaudeReplayProvider.register(null);
        getServer().getServicesManager().unregisterAll(this);
        if (playbackManager != null) {
            playbackManager.shutdown();
        }
        if (recordingManager != null) {
            recordingManager.shutdown();
        }
        for (PacketListenerCommon listener : packetListeners) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        }
        packetListeners.clear();
        if (storageExecutor != null) {
            storageExecutor.shutdown();
        }
        Platforms.unload();
    }

    public int getServerProtocolVersion() {
        return com.github.retrooper.packetevents.PacketEvents.getAPI().getServerManager().getVersion().getProtocolVersion();
    }

    public ReplayConfig getReplayConfig() {
        return replayConfig;
    }

    public ReplayStorage getStorage() {
        return storage;
    }

    void setStorage(ReplayStorage storage) {
        this.storage = storage;
    }

    public MapResolver getMapResolver() {
        return mapResolver;
    }

    void setMapResolver(MapResolver mapResolver) {
        this.mapResolver = mapResolver;
    }

    public RecordingManager getRecordingManager() {
        return recordingManager;
    }

    public PlaybackManager getPlaybackManager() {
        return playbackManager;
    }
}
