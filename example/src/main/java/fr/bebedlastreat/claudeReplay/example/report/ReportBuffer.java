package fr.bebedlastreat.claudeReplay.example.report;

import fr.bebedlastreat.claudeReplay.api.ClaudeReplayAPI;
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
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Use case: reports with evidence. Some worlds are always recorded with {@link RecordingOptions.Builder#keepLastTicks},
 * so a recording only ever holds their last minutes. When a player is reported, that recording is saved (the
 * replay shows the minutes before the report) and a new one starts right away.
 * <p>
 * Staff get a clickable message that opens the replay through the eyes of the reported player
 * (see {@link fr.bebedlastreat.claudeReplay.example.review.PlayerReplays}).
 */
public final class ReportBuffer implements CommandExecutor {

    public static final String STAFF_PERMISSION = "claudereplay.example.staff";

    private final ClaudeReplayAPI api;
    private final int keepTicks;
    private final Map<UUID, Recording> buffers = new HashMap<>();

    public ReportBuffer(ClaudeReplayAPI api, int minutes) {
        this.api = api;
        this.keepTicks = minutes * 60 * 20;
    }

    /** Starts recording a world in the background. */
    public void watch(World world) {
        buffers.put(world.getUID(), api.startRecording(RecordingOptions.builder(world)
                .name("Buffer " + world.getName())
                .keepLastTicks(keepTicks)
                .metadata("type", "report-buffer")
                .build()));
    }

    /** Stops the background recordings without saving them. */
    public void shutdown() {
        buffers.values().forEach(recording -> {
            if (recording.isRecording()) {
                recording.cancel();
            }
        });
        buffers.clear();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length < 2) {
            return false;
        }
        Player reported = Bukkit.getPlayerExact(args[0]);
        if (reported == null) {
            sender.sendMessage(Component.text(args[0] + " is not online.", NamedTextColor.RED));
            return true;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        World world = reported.getWorld();
        Recording buffer = buffers.get(world.getUID());
        if (buffer == null || !buffer.isRecording() || !buffer.isRecorded(reported.getUniqueId())) {
            sender.sendMessage(Component.text("Report sent, but no recording is available for this world.", NamedTextColor.YELLOW));
            return true;
        }

        // Saves the last minutes, then keeps watching the world.
        buffer.setMetadata("type", "report");
        buffer.setMetadata("reported", reported.getUniqueId().toString());
        buffer.setMetadata("reporter", sender.getName());
        buffer.setMetadata("reason", reason);
        buffer.addMarker("Report", reason);
        watch(world);
        sender.sendMessage(Component.text("Thanks, your report was sent to the staff.", NamedTextColor.GREEN));

        buffer.stop().thenAccept(replay -> {
            Component message = Component.text(sender.getName() + " reported " + reported.getName() + ": " + reason + " ", NamedTextColor.GOLD)
                    .append(Component.text("[Watch as " + reported.getName() + "]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/replaysof " + reported.getName() + " watch " + replay.id())));
            List.copyOf(Bukkit.getOnlinePlayers()).stream()
                    .filter(player -> player.hasPermission(STAFF_PERMISSION))
                    .forEach(player -> player.sendMessage(message));
        });
        return true;
    }
}
