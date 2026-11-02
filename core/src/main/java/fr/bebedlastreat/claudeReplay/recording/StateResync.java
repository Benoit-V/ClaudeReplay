package fr.bebedlastreat.claudeReplay.recording;

import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerInitializeWorldBorder;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerListHeaderAndFooter;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWorldBorder;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import fr.bebedlastreat.claudeReplay.platform.Platform;
import fr.bebedlastreat.claudeReplay.platform.Platforms;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;

import java.util.List;

/**
 * Brings a player's recorded view to a complete state when their capture starts in the middle of a game:
 * what the client already knows was received before the recording, so it is sent again.
 * <ul>
 *     <li>tab list, tab header/footer, weather and world border are generated and stored directly;</li>
 *     <li>scoreboard, boss bars and entities are re-sent for real by the server (hide then show in the same tick),
 *     which gives the exact packets without any visible effect for the player.</li>
 * </ul>
 */
final class StateResync {

    private static final double ENTITY_RANGE = 160;

    private StateResync() {
    }

    static void resync(Plugin plugin, RecordingImpl recording, Player player, int index, int tick) {
        Platform platform = Platforms.get();
        World world = player.getWorld();

        // Tab list
        List<Platform.TabEntry> entries = platform.tabList(player);
        if (!entries.isEmpty()) {
            PlayerPackets.Built tab = PlayerPackets.tabAdd(entries);
            recording.addPacket(tick, index, tab.type(), tab.payload());
        }

        Component[] headerFooter = platform.tabHeaderFooter(player);
        if (headerFooter != null) {
            recording.addPacket(tick, index, Server.PLAYER_LIST_HEADER_AND_FOOTER, PacketCodec.encodeNew(new WrapperPlayServerPlayerListHeaderAndFooter(
                    headerFooter[0] == null ? Component.empty() : headerFooter[0], headerFooter[1] == null ? Component.empty() : headerFooter[1])));
        }

        // Weather (2 = begin raining, 7 = rain level, 8 = thunder level)
        boolean raining = player.getPlayerWeather() != null ? player.getPlayerWeather() == WeatherType.DOWNFALL : world.hasStorm();
        if (raining) {
            recording.addPacket(tick, index, Server.CHANGE_GAME_STATE, PacketCodec.encodeNew(new WrapperPlayServerChangeGameState(2, 0)));
            recording.addPacket(tick, index, Server.CHANGE_GAME_STATE, PacketCodec.encodeNew(new WrapperPlayServerChangeGameState(7, 1)));
            recording.addPacket(tick, index, Server.CHANGE_GAME_STATE, PacketCodec.encodeNew(new WrapperPlayServerChangeGameState(8, world.isThundering() ? 1 : 0)));
        }

        Platform.BorderInfo border = platform.border(player);
        if (PacketCodec.isLegacy()) {
            recording.addPacket(tick, index, Server.WORLD_BORDER, PacketCodec.encodeNew(new WrapperPlayServerWorldBorder(
                    border.centerX(), border.centerZ(), border.size(), border.size(), 0, 29999984,
                    border.warningSeconds(), border.warningBlocks())));
        } else {
            recording.addPacket(tick, index, Server.INITIALIZE_WORLD_BORDER, PacketCodec.encodeNew(new WrapperPlayServerInitializeWorldBorder(
                    border.centerX(), border.centerZ(), border.size(), border.size(), 0,
                    29999984, border.warningBlocks(), border.warningSeconds())));
        }

        // Scoreboard: switching to an empty board and back makes the server send it again entirely.
        Scoreboard scoreboard = player.getScoreboard();
        player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
        player.setScoreboard(scoreboard);

        platform.resendBossBars(player);

        // Entities: hiding then showing them makes the entity tracker send their full spawn data again.
        double height = world.getMaxHeight() - platform.minHeight(world);
        for (Entity entity : world.getNearbyEntities(player.getLocation(), ENTITY_RANGE, height, ENTITY_RANGE)) {
            if (entity == player || (entity instanceof Player other && !player.canSee(other))) {
                continue;
            }
            platform.resendEntity(plugin, player, entity);
        }
    }
}
