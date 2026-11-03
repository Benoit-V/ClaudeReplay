package fr.bebedlastreat.claudeReplay.menu;

import fr.bebedlastreat.claudeReplay.ClaudeReplay;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayPlayer;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayWorld;
import fr.bebedlastreat.claudeReplay.platform.Platform;
import fr.bebedlastreat.claudeReplay.platform.Platforms;
import fr.bebedlastreat.claudeReplay.util.Lang;
import fr.bebedlastreat.claudeReplay.util.Text;
import fr.bebedlastreat.claudeReplay.util.TimeFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Menu listing the stored replays, most recent first, with their main information.
 */
public final class ReplayBrowser {

    private static final int PER_PAGE = 45;
    private static final int SHOWN_PLAYERS = 8;

    private final ClaudeReplay plugin;
    private final BiConsumer<Player, ReplayInfo> onPlay;
    private final BiConsumer<Player, ReplayInfo> onDetails;

    /**
     * @param onPlay    starts watching a replay (only called for playable replays)
     * @param onDetails shows the details of a replay
     */
    public ReplayBrowser(ClaudeReplay plugin, BiConsumer<Player, ReplayInfo> onPlay, BiConsumer<Player, ReplayInfo> onDetails) {
        this.plugin = plugin;
        this.onPlay = onPlay;
        this.onDetails = onDetails;
    }

    /** Loads the list of replays and opens the menu. */
    public void open(Player player, int page) {
        plugin.getStorage().list().whenComplete((replays, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                Text.error(player, Lang.tr("replays.list-failed", "error", error.getMessage()));
                return;
            }
            List<ReplayInfo> sorted = new ArrayList<>(replays);
            sorted.sort(Comparator.comparingLong(ReplayInfo::createdAt).reversed());
            show(player, sorted, page);
        }));
    }

    private void show(Player player, List<ReplayInfo> replays, int page) {
        Platform platform = Platforms.get();
        int pages = Math.max(1, (replays.size() + PER_PAGE - 1) / PER_PAGE);
        int shown = Math.max(0, Math.min(pages - 1, page));
        int from = shown * PER_PAGE;
        int to = Math.min(replays.size(), from + PER_PAGE);

        Menus.Menu menu = Menus.create(6, Component.text(pages > 1 ? Lang.tr("menus.replays.title-page", "page", shown + 1, "pages", pages) : Lang.tr("menus.replays.title")));
        if (replays.isEmpty()) {
            menu.set(22, item(Platform.Icon.PAPER, Component.text(Lang.tr("menus.replays.none"), NamedTextColor.GRAY),
                    hints("menus.replays.none-lore")));
        }
        for (int i = from; i < to; i++) {
            ReplayInfo replay = replays.get(i);
            boolean playable = plugin.getPlaybackManager().isPlayable(replay);
            menu.set(i - from, item(playable ? Platform.Icon.REPLAY : Platform.Icon.REPLAY_LOCKED,
                    Component.text(replay.name(), playable ? NamedTextColor.GOLD : NamedTextColor.RED), describe(replay, playable)), click -> {
                if (click.right()) {
                    player.closeInventory();
                    onDetails.accept(player, replay);
                } else if (playable) {
                    player.closeInventory();
                    onPlay.accept(player, replay);
                } else {
                    Text.error(player, Lang.tr("replays.too-recent", "version", replay.serverVersion(), "server", platform.minecraftVersion()));
                }
            });
        }
        if (shown > 0) {
            menu.set(45, item(Platform.Icon.ARROW, Component.text(Lang.tr("general.previous-page"), NamedTextColor.YELLOW), List.of()),
                    click -> show(player, replays, shown - 1));
        }
        menu.set(49, item(Platform.Icon.COMPASS, Component.text(Lang.tr("menus.replays.count", "count", replays.size()), NamedTextColor.AQUA),
                hints("menus.replays.count-lore", "version", platform.minecraftVersion())));
        if (shown < pages - 1) {
            menu.set(53, item(Platform.Icon.ARROW, Component.text(Lang.tr("general.next-page"), NamedTextColor.YELLOW), List.of()),
                    click -> show(player, replays, shown + 1));
        }
        menu.open(player);
    }

    private static List<Component> describe(ReplayInfo replay, boolean playable) {
        SimpleDateFormat date = new SimpleDateFormat(Lang.tr("menus.replays.date-format"));
        List<Component> lore = new ArrayList<>();
        lore.add(line(Lang.tr("menus.replays.date"), date.format(new Date(replay.createdAt()))));
        lore.add(line(Lang.tr("menus.replays.duration"), TimeFormat.format(replay.durationTicks())));
        lore.add(line(Lang.tr("menus.replays.version"), replay.serverVersion()));

        Set<String> maps = new LinkedHashSet<>();
        for (ReplayWorld world : replay.worlds()) {
            maps.add(world.mapId());
        }
        lore.add(line(Lang.tr(maps.size() > 1 ? "menus.replays.maps" : "menus.replays.map"), String.join(", ", maps)));
        if (!replay.markers().isEmpty()) {
            lore.add(line(Lang.tr("menus.replays.markers"), String.valueOf(replay.markers().size())));
        }

        lore.add(line(Lang.tr("menus.replays.players", "count", replay.players().size()), ""));
        List<ReplayPlayer> players = replay.players();
        for (int i = 0; i < players.size() && i < SHOWN_PLAYERS; i++) {
            ReplayPlayer player = players.get(i);
            lore.add(Component.text(" • " + player.name(), NamedTextColor.WHITE)
                    .append(Component.text(player.clientVersion() == null ? "" : " (" + player.clientVersion() + ")", NamedTextColor.DARK_GRAY)));
        }
        if (players.size() > SHOWN_PLAYERS) {
            lore.add(hint(Lang.tr("menus.replays.more-players", "count", players.size() - SHOWN_PLAYERS)));
        }

        lore.add(Component.empty());
        if (playable) {
            lore.add(Component.text(Lang.tr("menus.replays.watch"), NamedTextColor.GREEN));
        } else {
            lore.add(Component.text(Lang.tr("menus.replays.too-recent"), NamedTextColor.RED));
        }
        lore.add(hint(Lang.tr("menus.replays.details")));
        return lore;
    }

    private static Component line(String label, String value) {
        return Component.text(label, NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }

    private static Component hint(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    private static List<Component> hints(String key, Object... placeholders) {
        return Lang.lines(key, placeholders).stream().map(ReplayBrowser::hint).toList();
    }

    private static org.bukkit.inventory.ItemStack item(Platform.Icon icon, Component name, List<Component> lore) {
        return Platforms.get().icon(icon, name.decoration(TextDecoration.ITALIC, false),
                lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
    }
}
