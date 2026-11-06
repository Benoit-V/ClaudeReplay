package fr.bebedlastreat.claudeReplay.example.minigame;

import fr.bebedlastreat.claudeReplay.api.ClaudeReplayAPI;
import fr.bebedlastreat.claudeReplay.api.recording.PlayerTrackingMode;
import fr.bebedlastreat.claudeReplay.api.recording.Recording;
import fr.bebedlastreat.claudeReplay.api.recording.RecordingOptions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Use case: a mini-game records each of its games.
 * <ul>
 *     <li>the recording starts with the game and follows the players of the arena world;</li>
 *     <li>kills and broken beds become markers, so viewers can jump to the highlights;</li>
 *     <li>the game id, mode and winner are stored as metadata, to find the replay later;</li>
 *     <li>at the end, everyone gets a clickable message to watch it.</li>
 * </ul>
 * A real mini-game calls {@link #start} and {@link #end} from its own game loop; here {@code /game} does it.
 */
public final class GameRecorder implements CommandExecutor, Listener {

    private final ClaudeReplayAPI api;
    /** Running game per arena world. */
    private final Map<UUID, Recording> games = new HashMap<>();
    private int nextGameId = 1;

    public GameRecorder(ClaudeReplayAPI api) {
        this.api = api;
    }

    public void start(World arena, String mode) {
        int gameId = nextGameId++;
        Recording recording = api.startRecording(RecordingOptions.builder(arena)
                .name(mode + " #" + gameId)
                .mapId(arena.getName())                  // base map in plugins/ClaudeReplay/maps/<mapId>
                .trackingMode(PlayerTrackingMode.WORLD)  // everyone entering the arena is recorded
                .metadata("game", mode)
                .metadata("gameId", String.valueOf(gameId))
                .maxDurationTicks(20 * 60 * 60)          // safety net: never more than one hour
                .build());
        games.put(arena.getUID(), recording);
    }

    public void end(World arena, String winner) {
        Recording recording = games.remove(arena.getUID());
        if (recording == null) {
            return;
        }
        recording.setMetadata("winner", winner);
        recording.addMarker("End", winner == null ? null : winner + " wins");
        recording.stop().thenAccept(replay -> Bukkit.broadcast(Component.text("Replay of " + replay.name() + " saved. ", NamedTextColor.GREEN)
                .append(Component.text("[Watch]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/replay play " + replay.id())))));
    }

    /** The recording of the game played in this world, if any (and still running). */
    private Recording game(World world) {
        Recording recording = games.get(world.getUID());
        return recording != null && recording.isRecording() ? recording : null;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Recording recording = game(victim.getWorld());
        if (recording != null) {
            Player killer = victim.getKiller();
            recording.addMarker(killer == null ? victim.getName() + " died" : killer.getName() + " killed " + victim.getName(),
                    killer == null ? null : killer.getUniqueId() + ">" + victim.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Recording recording = game(event.getBlock().getWorld());
        if (recording != null && event.getBlock().getType().name().endsWith("_BED")) {
            recording.addMarker("Bed broken by " + event.getPlayer().getName());
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player) || args.length == 0) {
            return false;
        }
        World world = player.getWorld();
        switch (args[0]) {
            case "start" -> {
                if (game(world) != null) {
                    sender.sendMessage(Component.text("A game is already recorded here.", NamedTextColor.RED));
                    return true;
                }
                start(world, args.length > 1 ? args[1] : "Game");
                sender.sendMessage(Component.text("Game started and recorded.", NamedTextColor.GREEN));
            }
            case "end" -> end(world, args.length > 1 ? args[1] : null);
            default -> {
                return false;
            }
        }
        return true;
    }
}
