package fr.bebedlastreat.claudeReplay.playback;

import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTickingState;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A player watching a session, with what is needed to give them back their previous state.
 */
final class Viewer {

    final Player player;
    final User user;
    final LiveSink live;
    final ViewerPipeline pipeline;
    final BossBar hud = BossBar.bossBar(Component.empty(), 0, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);

    /** Remapped id of the entity the camera is attached to, -1 when the camera is the viewer itself. */
    int cameraEntity = -1;
    /** {@link LiveSink#spawnCount} of the camera entity when the camera was attached. */
    int cameraSpawn;

    private final Location returnLocation;
    private final GameMode gameMode;
    private final Scoreboard scoreboard;
    private final boolean allowFlight;
    private final boolean flying;
    private final ItemStack[] inventory;
    private final int heldSlot;
    private final Set<UUID> hiddenPlayers = new HashSet<>();

    Viewer(Player player, User user) {
        this.player = player;
        this.user = user;
        this.live = new LiveSink(user);
        this.pipeline = new ViewerPipeline(user);
        this.returnLocation = player.getLocation();
        this.gameMode = player.getGameMode();
        this.scoreboard = player.getScoreboard();
        this.allowFlight = player.getAllowFlight();
        this.flying = player.isFlying();
        this.inventory = player.getInventory().getContents().clone();
        for (int i = 0; i < inventory.length; i++) {
            inventory[i] = inventory[i] == null ? null : inventory[i].clone();
        }
        this.heldSlot = player.getInventory().getHeldItemSlot();
    }

    Integer pointOfView() {
        return pipeline.getPointOfView();
    }

    /** Puts the player in viewer mode: creative, flying, with the control items. */
    void setup(Plugin plugin, PlaybackSessionImpl session) {
        ViewerBackup.save(player, inventory, returnLocation, gameMode, allowFlight);
        player.closeInventory();
        player.getInventory().clear();
        player.setGameMode(GameMode.CREATIVE);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
        for (Player other : Bukkit.getOnlinePlayers()) {
            hide(plugin, other);
        }
        ControlItems.give(player, session);
        player.getInventory().setHeldItemSlot(3);
        player.showBossBar(hud);
    }

    /** Called after a teleport: flying is reset when changing world. */
    void keepFlying() {
        if (player.getGameMode() == GameMode.CREATIVE && !player.isFlying()) {
            player.setAllowFlight(true);
            player.setFlying(true);
        }
    }

    /** Hides a player of the server from the viewer (and the viewer from them). */
    void hide(Plugin plugin, Player other) {
        if (other == player) {
            return;
        }
        player.hidePlayer(plugin, other);
        other.hidePlayer(plugin, player);
        hiddenPlayers.add(other.getUniqueId());
    }

    /**
     * Freezes the replay entities on the client while paused. The tick rate itself stays at 20: changing it would
     * also slow down or speed up the viewer's own movements.
     */
    void setFrozen(boolean frozen) {
        live.send(new WrapperPlayServerTickingState(20, frozen));
    }

    void attachCamera(int entity) {
        cameraEntity = entity;
        cameraSpawn = live.spawnCount(entity);
        live.send(new WrapperPlayServerCamera(entity));
    }

    void detachCamera() {
        if (cameraEntity != -1) {
            cameraEntity = -1;
            live.send(new WrapperPlayServerCamera(player.getEntityId()));
        }
    }

    /** Gives the player back their state. */
    void restore(Plugin plugin, boolean teleport) {
        detachCamera();
        live.resetClient();
        setFrozen(false);
        live.flush();
        player.hideBossBar(hud);
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof ControlItems.Menu) {
            player.closeInventory();
        }
        if (teleport) {
            player.teleport(returnLocation);
        }
        player.getInventory().setContents(inventory);
        player.getInventory().setHeldItemSlot(heldSlot);
        ViewerBackup.clear(player);
        player.setGameMode(gameMode);
        player.setAllowFlight(allowFlight);
        player.setFlying(flying && allowFlight);
        player.setScoreboard(scoreboard);
        for (UUID uuid : hiddenPlayers) {
            Player other = Bukkit.getPlayer(uuid);
            if (other != null) {
                player.showPlayer(plugin, other);
                other.showPlayer(plugin, player);
            }
        }
        hiddenPlayers.clear();
    }
}
