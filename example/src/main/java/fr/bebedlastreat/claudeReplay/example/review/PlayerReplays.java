package fr.bebedlastreat.claudeReplay.example.review;

import fr.bebedlastreat.claudeReplay.api.ClaudeReplayAPI;
import fr.bebedlastreat.claudeReplay.api.playback.PlaybackSession;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayMarker;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case: staff review the games of a player.
 * <ul>
 *     <li>{@code /replaysof <player>} lists the replays where the player was recorded, newest first;</li>
 *     <li>{@code /replaysof <player> watch <replay>} opens one through the player's eyes, a few seconds before
 *     the first report marker if there is one.</li>
 * </ul>
 */
public final class PlayerReplays implements CommandExecutor {

    private static final int SHOWN = 10;
    private static final int BEFORE_REPORT_TICKS = 10 * 20;

    private final Plugin plugin;
    private final ClaudeReplayAPI api;

    public PlayerReplays(Plugin plugin, ClaudeReplayAPI api) {
        this.plugin = plugin;
        this.api = api;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            return false;
        }
        String name = args[0];
        if (args.length >= 3 && args[1].equals("watch") && sender instanceof Player staff) {
            watch(staff, name, UUID.fromString(args[2]));
            return true;
        }
        // The storage may answer on another thread: go back to the main thread before using Bukkit.
        api.listReplays().thenAccept(replays -> Bukkit.getScheduler().runTask(plugin, () -> {
            List<ReplayInfo> found = replays.stream().filter(replay -> replay.getPlayer(name).isPresent()).limit(SHOWN).toList();
            if (found.isEmpty()) {
                sender.sendMessage(Component.text("No replay of " + name + ".", NamedTextColor.GRAY));
                return;
            }
            SimpleDateFormat date = new SimpleDateFormat("dd/MM HH:mm");
            sender.sendMessage(Component.text("Replays of " + name + ":", NamedTextColor.GOLD));
            for (ReplayInfo replay : found) {
                String reason = replay.getMetadata("reason");
                sender.sendMessage(Component.text(" ▶ " + replay.name(), NamedTextColor.WHITE)
                        .append(Component.text(" " + date.format(new Date(replay.createdAt())) + (reason == null ? "" : " · report: " + reason),
                                NamedTextColor.GRAY))
                        .clickEvent(ClickEvent.runCommand("/replaysof " + name + " watch " + replay.id())));
            }
        }));
        return true;
    }

    private void watch(Player staff, String name, UUID replayId) {
        api.getReplayInfo(replayId).thenAccept(info -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (info.isEmpty()) {
                staff.sendMessage(Component.text("This replay no longer exists.", NamedTextColor.RED));
                return;
            }
            ReplayInfo replay = info.get();
            if (!api.isPlayable(replay)) {
                staff.sendMessage(Component.text("This replay comes from a newer server (" + replay.serverVersion() + ").", NamedTextColor.RED));
                return;
            }
            Optional<ReplayPlayer> target = replay.getPlayer(name);
            api.startPlayback(replayId, List.of(staff)).whenComplete((session, error) -> {
                if (error != null) {
                    staff.sendMessage(Component.text("Cannot open the replay: " + error.getMessage(), NamedTextColor.RED));
                    return;
                }
                target.ifPresent(player -> session.setPointOfView(staff, player.uuid()));
                firstReport(replay).ifPresent(marker -> session.seek(Math.max(0, marker.tick() - BEFORE_REPORT_TICKS)));
                describe(staff, session);
            });
        }));
    }

    private static Optional<ReplayMarker> firstReport(ReplayInfo replay) {
        return replay.markers().stream().filter(marker -> marker.name().equals("Report")).findFirst();
    }

    private static void describe(Player staff, PlaybackSession session) {
        ReplayInfo replay = session.getReplay();
        String reporter = replay.getMetadata("reporter");
        if (reporter != null) {
            staff.sendMessage(Component.text("Reported by " + reporter + ": " + replay.getMetadata("reason"), NamedTextColor.GOLD));
        }
    }
}
