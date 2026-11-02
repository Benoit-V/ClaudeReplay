package fr.bebedlastreat.claudeReplay.playback;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSpectate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;

/**
 * Keeps the real server out of the viewers' screens (time, weather, world border, entities of the playback
 * world...) and turns their spectator interactions into point of view changes.
 * <p>
 * Replayed packets are sent silently and never pass through this listener.
 */
public final class ViewerPacketListener extends PacketListenerAbstract {

    /** CHANGE_GAME_STATE reasons: start/stop raining, rain level, thunder level. */
    private static final Set<Integer> WEATHER_EVENTS = Set.of(1, 2, 7, 8);

    private final Plugin plugin;
    private final PlaybackManager manager;

    public ViewerPacketListener(Plugin plugin, PlaybackManager manager) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacketType() instanceof PacketType.Play.Server type)) {
            return;
        }
        UUID uuid = event.getUser().getUUID();
        if (uuid == null || !manager.isViewer(uuid)) {
            return;
        }
        switch (type) {
            case TIME_UPDATE, PLAYER_LIST_HEADER_AND_FOOTER, TICKING_STATE, TICKING_STEP, INITIALIZE_WORLD_BORDER, WORLD_BORDER,
                 WORLD_BORDER_CENTER, WORLD_BORDER_LERP_SIZE, WORLD_BORDER_SIZE, WORLD_BORDER_WARNING_DELAY, WORLD_BORDER_WARNING_REACH,
                 SPAWN_ENTITY, SPAWN_LIVING_ENTITY, SPAWN_PLAYER, SPAWN_PAINTING, SPAWN_EXPERIENCE_ORB, WAYPOINT ->
                    event.setCancelled(true);
            case CHANGE_GAME_STATE -> {
                // Only the weather of the playback world is hidden (the replay sends its own). The other events
                // must pass, especially START_LOADING_CHUNKS: without it the client never leaves the loading
                // state after a world change and the viewer cannot move.
                Object buffer = event.getByteBuf();
                if (WEATHER_EVENTS.contains((int) ByteBufHelper.getUnsignedByte(buffer, ByteBufHelper.readerIndex(buffer)))) {
                    event.setCancelled(true);
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.SPECTATE && event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }
        UUID uuid = event.getUser().getUUID();
        if (uuid == null || !manager.isViewer(uuid)) {
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.SPECTATE) {
            UUID target = new WrapperPlayClientSpectate(event).getTargetUUID();
            event.setCancelled(true);
            runFor(uuid, (session, player) -> session.handleSpectate(player, target));
        } else {
            int entityId = new WrapperPlayClientInteractEntity(event).getEntityId();
            if (EntityRemapper.isReplayId(entityId)) {
                event.setCancelled(true);
                runFor(uuid, (session, player) -> session.handleInteract(player, entityId));
            }
        }
    }

    private void runFor(UUID uuid, java.util.function.BiConsumer<PlaybackSessionImpl, Player> action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                manager.getSession(uuid).ifPresent(session -> {
                    try {
                        action.accept(session, player);
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Cannot change the point of view of " + player.getName() + ": " + e.getMessage());
                    }
                });
            }
        });
    }
}
