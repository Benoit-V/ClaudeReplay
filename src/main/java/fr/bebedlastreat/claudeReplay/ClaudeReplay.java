package fr.bebedlastreat.claudeReplay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import fr.bebedlastreat.claudeReplay.api.ClaudeReplayAPI;
import fr.bebedlastreat.claudeReplay.api.ClaudeReplayProvider;
import fr.bebedlastreat.claudeReplay.api.storage.MapResolver;
import fr.bebedlastreat.claudeReplay.api.storage.ReplayStorage;
import fr.bebedlastreat.claudeReplay.command.ReplayCommand;
import fr.bebedlastreat.claudeReplay.playback.PlaybackBukkitListener;
import fr.bebedlastreat.claudeReplay.playback.PlaybackManager;
import fr.bebedlastreat.claudeReplay.playback.ViewerPacketListener;
import fr.bebedlastreat.claudeReplay.recording.PlayerWorldTracker;
import fr.bebedlastreat.claudeReplay.recording.RecordingBukkitListener;
import fr.bebedlastreat.claudeReplay.recording.RecordingManager;
import fr.bebedlastreat.claudeReplay.recording.RecordingPacketListener;
import fr.bebedlastreat.claudeReplay.storage.DefaultMapResolver;
import fr.bebedlastreat.claudeReplay.storage.FileReplayStorage;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
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

        storageExecutor = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("ClaudeReplay-storage-", 0).daemon().factory());
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
        packetListeners.add(PacketEvents.getAPI().getEventManager().registerListener(new RecordingPacketListener(recordingManager, worldTracker)));
        packetListeners.add(PacketEvents.getAPI().getEventManager().registerListener(new ViewerPacketListener(this, playbackManager)));

        getServer().getScheduler().runTaskTimer(this, () -> {
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
