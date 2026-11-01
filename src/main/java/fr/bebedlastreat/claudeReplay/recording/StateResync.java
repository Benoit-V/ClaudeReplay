package fr.bebedlastreat.claudeReplay.recording;

import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerInitializeWorldBorder;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerListHeaderAndFooter;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.EnumSet;
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
        World world = player.getWorld();

        // Tab list
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> infos = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != player && !player.canSee(other)) {
                continue;
            }
            infos.add(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(PlayerPackets.profile(other), player.isListed(other),
                    other.getPing(), SpigotConversionUtil.fromBukkitGameMode(other.getGameMode()), other.playerListName(),
                    null, other.getPlayerListOrder()));
        }
        if (!infos.isEmpty()) {
            recording.addPacket(tick, index, Server.PLAYER_INFO_UPDATE, PacketCodec.encodeNew(new WrapperPlayServerPlayerInfoUpdate(EnumSet.of(
                    WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LIST_ORDER), infos)));
        }

        Component header = player.playerListHeader();
        Component footer = player.playerListFooter();
        if (header != null || footer != null) {
            recording.addPacket(tick, index, Server.PLAYER_LIST_HEADER_AND_FOOTER, PacketCodec.encodeNew(new WrapperPlayServerPlayerListHeaderAndFooter(
                    header == null ? Component.empty() : header, footer == null ? Component.empty() : footer)));
        }

        // Weather (1 = start raining, 7 = rain level, 8 = thunder level)
        boolean raining = player.getPlayerWeather() != null ? player.getPlayerWeather() == WeatherType.DOWNFALL : world.hasStorm();
        if (raining) {
            recording.addPacket(tick, index, Server.CHANGE_GAME_STATE, PacketCodec.encodeNew(new WrapperPlayServerChangeGameState(1, 0)));
            recording.addPacket(tick, index, Server.CHANGE_GAME_STATE, PacketCodec.encodeNew(new WrapperPlayServerChangeGameState(7, 1)));
            recording.addPacket(tick, index, Server.CHANGE_GAME_STATE, PacketCodec.encodeNew(new WrapperPlayServerChangeGameState(8, world.isThundering() ? 1 : 0)));
        }

        WorldBorder border = player.getWorldBorder() != null ? player.getWorldBorder() : world.getWorldBorder();
        recording.addPacket(tick, index, Server.INITIALIZE_WORLD_BORDER, PacketCodec.encodeNew(new WrapperPlayServerInitializeWorldBorder(
                border.getCenter().getX(), border.getCenter().getZ(), border.getSize(), border.getSize(), 0,
                29999984, border.getWarningDistance(), border.getWarningTimeTicks() / 20)));

        // Scoreboard: switching to an empty board and back makes the server send it again entirely.
        Scoreboard scoreboard = player.getScoreboard();
        player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
        player.setScoreboard(scoreboard);

        List<BossBar> bossBars = new ArrayList<>();
        player.activeBossBars().forEach(bossBars::add);
        for (BossBar bar : bossBars) {
            player.hideBossBar(bar);
            player.showBossBar(bar);
        }

        // Entities: hiding then showing them makes the entity tracker send their full spawn data again.
        for (Entity entity : world.getNearbyEntities(player.getLocation(), ENTITY_RANGE, world.getMaxHeight() - world.getMinHeight(), ENTITY_RANGE)) {
            if (entity == player || !player.canSee(entity)) {
                continue;
            }
            player.hideEntity(plugin, entity);
            player.showEntity(plugin, entity);
        }
    }
}
