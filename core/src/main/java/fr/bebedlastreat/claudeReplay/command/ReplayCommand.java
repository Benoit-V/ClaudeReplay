package fr.bebedlastreat.claudeReplay.command;

import fr.bebedlastreat.claudeReplay.ClaudeReplay;
import fr.bebedlastreat.claudeReplay.api.recording.PlayerTrackingMode;
import fr.bebedlastreat.claudeReplay.api.recording.Recording;
import fr.bebedlastreat.claudeReplay.api.recording.RecordingOptions;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayPlayer;
import fr.bebedlastreat.claudeReplay.menu.ReplayBrowser;
import fr.bebedlastreat.claudeReplay.platform.Platforms;
import fr.bebedlastreat.claudeReplay.playback.ControlPanel;
import fr.bebedlastreat.claudeReplay.playback.PlaybackSessionImpl;
import fr.bebedlastreat.claudeReplay.recording.RecordingImpl;
import fr.bebedlastreat.claudeReplay.util.Text;
import fr.bebedlastreat.claudeReplay.util.TimeFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * /replay command.
 */
public final class ReplayCommand implements TabExecutor {

    private static final List<String> VIEWER_COMMANDS = List.of("pause", "resume", "toggle", "speed", "seek", "skip", "pov", "free", "invite", "leave", "controls");
    private static final List<String> COMMANDS = Stream.concat(Stream.of("menu", "record", "list", "play", "info", "delete"), VIEWER_COMMANDS.stream()).toList();

    private final ClaudeReplay plugin;
    private final ReplayBrowser browser;

    public ReplayCommand(ClaudeReplay plugin) {
        this.plugin = plugin;
        this.browser = new ReplayBrowser(plugin, this::play, this::describe);
    }

    private static void error(CommandSender sender, String message) {
        Text.send(sender, Component.text(message, NamedTextColor.RED));
    }

    private static void info(CommandSender sender, String message) {
        Text.send(sender, Component.text(message, NamedTextColor.GRAY));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "record" -> record(sender, args);
                case "menu" -> menu(sender);
                case "list" -> list(sender);
                case "play" -> play(sender, args);
                case "info" -> withReplay(sender, args, replay -> describe(sender, replay));
                case "delete" -> delete(sender, args);
                default -> {
                    if (VIEWER_COMMANDS.contains(sub)) {
                        viewer(sender, sub, args);
                    } else {
                        help(sender);
                    }
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            error(sender, e.getMessage());
        }
        return true;
    }

    private void help(CommandSender sender) {
        Text.send(sender, Component.text("ClaudeReplay", NamedTextColor.GOLD));
        info(sender, "/replay record start [nom] [map] | stop [nom] | list | marker <nom>");
        info(sender, "/replay menu | list | play <replay> | info <replay> | delete <replay>");
        info(sender, "/replay toggle | speed <x> | seek <temps> | skip <±secondes> | pov <joueur> | free | invite <joueur> | leave");
    }

    // ------------------------------------------------------------------ recording

    private void record(CommandSender sender, String[] args) {
        if (!sender.hasPermission("claudereplay.record")) {
            error(sender, "Tu n'as pas la permission.");
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "start" -> {
                World world;
                if (sender instanceof Player player) {
                    world = player.getWorld();
                } else {
                    world = Bukkit.getWorlds().get(0);
                }
                RecordingOptions.Builder options = RecordingOptions.builder(world).trackingMode(PlayerTrackingMode.WORLD);
                if (args.length > 2) {
                    options.name(args[2]);
                }
                if (args.length > 3) {
                    options.mapId(args[3]);
                }
                Recording recording = plugin.getRecordingManager().start(options.build());
                Text.send(sender, Component.text("Enregistrement de " + world.getName() + " démarré : " + recording.getName()
                        + " (map " + recording.getMapId() + ")", NamedTextColor.GREEN));
            }
            case "stop" -> {
                RecordingImpl recording = findRecording(sender, args.length > 2 ? args[2] : null);
                if (recording == null) {
                    return;
                }
                info(sender, "Arrêt et sauvegarde de " + recording.getName() + "...");
                recording.stop().whenComplete((replay, error) -> {
                    if (error != null) {
                        error(sender, "Échec de la sauvegarde : " + error.getMessage());
                    } else {
                        Text.send(sender, Component.text("Replay sauvegardé : " + replay.name() + " (" + TimeFormat.format(replay.durationTicks()) + ") ", NamedTextColor.GREEN)
                                .append(Component.text("[Regarder]", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/replay play " + replay.id()))));
                    }
                });
            }
            case "marker" -> {
                RecordingImpl recording = findRecording(sender, null);
                if (recording == null) {
                    return;
                }
                String name = args.length > 2 ? String.join(" ", List.of(args).subList(2, args.length)) : "Marqueur";
                recording.addMarker(name);
                info(sender, "Marqueur ajouté à " + TimeFormat.format(recording.getCurrentTick()));
            }
            default -> {
                if (plugin.getRecordingManager().isEmpty()) {
                    info(sender, "Aucun enregistrement en cours.");
                    return;
                }
                for (RecordingImpl recording : plugin.getRecordingManager().active()) {
                    info(sender, "● " + recording.getName() + " - " + recording.getWorld().getName() + " - " + TimeFormat.format(recording.getCurrentTick())
                            + " - " + recording.getPlayers().size() + " joueur(s)");
                }
            }
        }
    }

    private RecordingImpl findRecording(CommandSender sender, String name) {
        List<RecordingImpl> recordings = List.copyOf(plugin.getRecordingManager().active());
        for (RecordingImpl recording : recordings) {
            if (name != null ? recording.getName().equalsIgnoreCase(name) || recording.getId().toString().startsWith(name)
                    : sender instanceof Player player && recording.getWorld().equals(player.getWorld())) {
                return recording;
            }
        }
        if (name == null && recordings.size() == 1) {
            return recordings.get(0);
        }
        error(sender, "Enregistrement introuvable.");
        return null;
    }

    // ------------------------------------------------------------------ stored replays

    private void list(CommandSender sender) {
        if (!sender.hasPermission("claudereplay.play")) {
            error(sender, "Tu n'as pas la permission.");
            return;
        }
        plugin.getStorage().list().whenComplete((replays, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                error(sender, "Impossible de lister les replays : " + error.getMessage());
                return;
            }
            if (replays.isEmpty()) {
                info(sender, "Aucun replay.");
                return;
            }
            Text.send(sender, Component.text("Replays (" + replays.size() + ")", NamedTextColor.GOLD));
            SimpleDateFormat format = new SimpleDateFormat("dd/MM HH:mm");
            for (ReplayInfo replay : replays.subList(0, Math.min(20, replays.size()))) {
                Text.send(sender, Component.text("▶ ", NamedTextColor.GREEN)
                        .append(Component.text(replay.name(), NamedTextColor.WHITE))
                        .append(Component.text(" " + format.format(new Date(replay.createdAt())) + " · " + TimeFormat.format(replay.durationTicks())
                                + " · " + replay.players().size() + " joueur(s)", NamedTextColor.GRAY))
                        .clickEvent(ClickEvent.runCommand("/replay play " + replay.id()))
                        .hoverEvent(HoverEvent.showText(Component.text("Regarder " + replay.id()))));
            }
        }));
    }

    /** Resolves a replay by id, id prefix or name (most recent first). */
    private void withReplay(CommandSender sender, String[] args, Consumer<ReplayInfo> action) {
        if (args.length < 2) {
            error(sender, "Précise le replay (id ou nom).");
            return;
        }
        String query = args[1];
        plugin.getStorage().list().whenComplete((replays, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                error(sender, "Impossible de lire les replays : " + error.getMessage());
                return;
            }
            Optional<ReplayInfo> match = replays.stream().filter(replay -> replay.id().toString().equalsIgnoreCase(query)).findFirst()
                    .or(() -> replays.stream().filter(replay -> replay.id().toString().startsWith(query.toLowerCase(Locale.ROOT))).findFirst())
                    .or(() -> replays.stream().filter(replay -> replay.name().equalsIgnoreCase(query)).findFirst());
            if (match.isEmpty()) {
                error(sender, "Replay introuvable : " + query);
                return;
            }
            try {
                action.accept(match.get());
            } catch (IllegalArgumentException | IllegalStateException e) {
                error(sender, e.getMessage());
            }
        }));
    }

    private void describe(CommandSender sender, ReplayInfo replay) {
        Text.send(sender, Component.text(replay.name(), NamedTextColor.GOLD));
        info(sender, "Id : " + replay.id());
        info(sender, "Durée : " + TimeFormat.format(replay.durationTicks()) + " · Version : " + replay.serverVersion()
                + (plugin.getPlaybackManager().isPlayable(replay) ? "" : " (plus récente que ce serveur : illisible ici)"));
        info(sender, "Joueurs : " + String.join(", ", replay.players().stream()
                .map(player -> player.name() + (player.clientVersion() == null ? "" : " (" + player.clientVersion() + ")")).toList()));
        replay.worlds().forEach(world -> info(sender, "Monde à " + TimeFormat.format(world.startTick()) + " : " + world.worldName() + " (map " + world.mapId() + ")"));
        replay.markers().forEach(marker -> info(sender, "Marqueur à " + TimeFormat.format(marker.tick()) + " : " + marker.name()));
    }

    private void play(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Commande réservée aux joueurs.");
            return;
        }
        if (!sender.hasPermission("claudereplay.play")) {
            error(sender, "Tu n'as pas la permission.");
            return;
        }
        withReplay(sender, args, replay -> play(player, replay));
    }

    private void play(Player player, ReplayInfo replay) {
        if (!plugin.getPlaybackManager().isPlayable(replay)) {
            error(player, "Ce replay vient d'un serveur en " + replay.serverVersion() + ", plus récent que celui-ci ("
                    + Platforms.get().minecraftVersion() + ").");
            return;
        }
        info(player, "Chargement de " + replay.name() + "...");
        CompletableFuture<?> future = plugin.getPlaybackManager().start(replay.id(), List.of(player));
        future.whenComplete((session, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                plugin.getLogger().warning("Cannot play " + replay.id() + ": " + cause);
                Bukkit.getScheduler().runTask(plugin, () -> error(player, "Impossible de lancer le replay : " + cause.getMessage()));
            }
        });
    }

    private void menu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Commande réservée aux joueurs.");
            return;
        }
        if (!sender.hasPermission("claudereplay.play")) {
            error(sender, "Tu n'as pas la permission.");
            return;
        }
        browser.open(player, 0);
    }

    private void delete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("claudereplay.admin")) {
            error(sender, "Tu n'as pas la permission.");
            return;
        }
        withReplay(sender, args, replay -> plugin.getStorage().delete(replay.id()).whenComplete((deleted, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || !deleted) {
                        error(sender, "Suppression impossible.");
                    } else {
                        info(sender, "Replay " + replay.name() + " supprimé.");
                    }
                })));
    }

    // ------------------------------------------------------------------ viewer controls

    private void viewer(CommandSender sender, String sub, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Commande réservée aux joueurs.");
            return;
        }
        Optional<PlaybackSessionImpl> found = plugin.getPlaybackManager().getSession(player.getUniqueId());
        if (found.isEmpty()) {
            error(sender, "Tu ne regardes aucun replay.");
            return;
        }
        PlaybackSessionImpl session = found.get();
        switch (sub) {
            case "pause" -> session.setPaused(true);
            case "resume" -> session.setPaused(false);
            case "toggle" -> session.setPaused(!session.isPaused());
            case "speed" -> {
                if (args.length < 2) {
                    error(sender, "/replay speed <0.1-10>");
                    return;
                }
                session.setSpeed(Double.parseDouble(args[1].replace("x", "")));
            }
            case "seek" -> {
                if (args.length < 2) {
                    error(sender, "/replay seek <mm:ss | secondes | ticks t>");
                    return;
                }
                session.seek(TimeFormat.parse(args[1]));
            }
            case "skip" -> {
                if (args.length < 2) {
                    error(sender, "/replay skip <±secondes>");
                    return;
                }
                String value = args[1];
                boolean negative = value.startsWith("-");
                int ticks = TimeFormat.parse(value.replace("+", "").replace("-", ""));
                session.seek(session.getTick() + (negative ? -ticks : ticks));
            }
            case "pov" -> {
                if (args.length < 2) {
                    error(sender, "/replay pov <joueur>");
                    return;
                }
                ReplayPlayer target = session.getReplay().getPlayer(args[1])
                        .orElseThrow(() -> new IllegalArgumentException(args[1] + " n'est pas dans ce replay."));
                session.setPointOfView(player, target.uuid());
            }
            case "free" -> session.setPointOfView(player, null);
            case "invite" -> {
                if (args.length < 2) {
                    error(sender, "/replay invite <joueur>");
                    return;
                }
                Player invited = Bukkit.getPlayerExact(args[1]);
                if (invited == null) {
                    error(sender, args[1] + " n'est pas connecté.");
                    return;
                }
                session.addViewer(invited);
                info(sender, invited.getName() + " regarde le replay avec toi.");
            }
            case "leave" -> session.removeViewer(player);
            case "controls" -> ControlPanel.send(player, session);
            default -> help(sender);
        }
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return filter(COMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "record" -> {
                    return filter(List.of("start", "stop", "list", "marker"), args[1]);
                }
                case "speed" -> {
                    return filter(List.of("0.25", "0.5", "1", "2", "4"), args[1]);
                }
                case "skip" -> {
                    return filter(List.of("-30", "-5", "5", "30"), args[1]);
                }
                case "pov" -> {
                    if (sender instanceof Player player) {
                        return plugin.getPlaybackManager().getSession(player.getUniqueId())
                                .map(session -> filter(session.getReplay().players().stream().map(ReplayPlayer::name).toList(), args[1]))
                                .orElse(List.of());
                    }
                }
                case "invite" -> {
                    return null; // online players
                }
                default -> {
                }
            }
        }
        if (args.length == 3 && sub.equals("record") && args[1].equalsIgnoreCase("stop")) {
            List<String> names = new ArrayList<>();
            plugin.getRecordingManager().active().forEach(recording -> names.add(recording.getName()));
            return filter(names, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
