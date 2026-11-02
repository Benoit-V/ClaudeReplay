package fr.bebedlastreat.claudeReplay.playback;

import fr.bebedlastreat.claudeReplay.api.replay.ReplayMarker;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayPlayer;
import fr.bebedlastreat.claudeReplay.menu.Menus;
import fr.bebedlastreat.claudeReplay.platform.Platform;
import fr.bebedlastreat.claudeReplay.platform.Platforms;
import fr.bebedlastreat.claudeReplay.util.Text;
import fr.bebedlastreat.claudeReplay.util.TimeFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The hotbar used to control a replay, and its menus (points of view, markers). The hotbar slot of an item
 * gives its action.
 */
final class ControlItems {

    enum Action {
        POINT_OF_VIEW, MARKERS, BACKWARD, PLAY_PAUSE, FORWARD, NONE, SPEED, CONTROLS, LEAVE
    }

    static final int PLAY_PAUSE_SLOT = Action.PLAY_PAUSE.ordinal();

    /** Markers shown per page of the markers menu (the last row holds the page buttons). */
    private static final int MARKERS_PER_PAGE = 45;

    static final double[] SPEEDS = {0.25, 0.5, 1, 2, 4, 8};
    private static final int SHORT_SKIP = 10 * 20;
    private static final int LONG_SKIP = 60 * 20;

    private ControlItems() {
    }

    static Component hint(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    private static ItemStack item(Platform.Icon icon, Component name, List<Component> lore) {
        return Platforms.get().icon(icon, name.decoration(TextDecoration.ITALIC, false),
                lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
    }

    /** Fills the viewer's hotbar with the controls, reflecting the session state. */
    static void give(Player player, PlaybackSessionImpl session) {
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(Action.POINT_OF_VIEW.ordinal(), item(Platform.Icon.PLAYER_HEAD, Component.text("Points de vue", NamedTextColor.LIGHT_PURPLE),
                List.of(hint("Clic droit : choisir un joueur"), hint("Sneak : revenir en caméra libre"))));
        int markers = session.getReplay().markers().size();
        inventory.setItem(Action.MARKERS.ordinal(), item(Platform.Icon.NAME_TAG, Component.text("Marqueurs (" + markers + ")", NamedTextColor.GOLD),
                List.of(hint(markers == 0 ? "Aucun temps fort dans ce replay" : "Clic : aller à un temps fort"))));
        inventory.setItem(Action.BACKWARD.ordinal(), item(Platform.Icon.BACKWARD, Component.text("⏪ Reculer", NamedTextColor.RED),
                List.of(hint("Clic droit : -10 secondes"), hint("Clic gauche : -1 minute"))));
        inventory.setItem(Action.PLAY_PAUSE.ordinal(), session.isPaused()
                ? item(Platform.Icon.PLAY, Component.text("▶ Lecture", NamedTextColor.GREEN), List.of(hint("Clic : reprendre")))
                : item(Platform.Icon.PAUSE, Component.text("⏸ Pause", NamedTextColor.YELLOW), List.of(hint("Clic : mettre en pause"))));
        inventory.setItem(Action.FORWARD.ordinal(), item(Platform.Icon.FORWARD, Component.text("⏩ Avancer", NamedTextColor.AQUA),
                List.of(hint("Clic droit : +10 secondes"), hint("Clic gauche : +1 minute"))));
        inventory.setItem(Action.SPEED.ordinal(), item(Platform.Icon.CLOCK, Component.text("Vitesse x" + PlaybackSessionImpl.formatSpeed(session.getSpeed()), NamedTextColor.GOLD),
                List.of(hint("Clic droit : plus vite"), hint("Clic gauche : moins vite"))));
        inventory.setItem(Action.CONTROLS.ordinal(), item(Platform.Icon.BOOK, Component.text("Panneau de contrôle", NamedTextColor.WHITE),
                List.of(hint("Clic : afficher les commandes et marqueurs"))));
        inventory.setItem(Action.LEAVE.ordinal(), item(Platform.Icon.BARRIER, Component.text("Quitter le replay", NamedTextColor.RED),
                List.of(hint("Clic : revenir là où tu étais"))));
    }

    /** Action of the item in the given hotbar slot, null for none. */
    static Action actionOf(Player player, ItemStack item) {
        if (Platforms.get().isEmpty(item)) {
            return null;
        }
        int slot = player.getInventory().getHeldItemSlot();
        Action action = slot < Action.values().length ? Action.values()[slot] : null;
        return action == Action.NONE ? null : action;
    }

    /** Runs the action of a control item. */
    static void use(Player player, PlaybackSessionImpl session, Action action, boolean rightClick) {
        switch (action) {
            case POINT_OF_VIEW -> openPointOfViewMenu(player, session);
            case MARKERS -> openMarkersMenu(player, session, 0);
            case BACKWARD -> session.seek(session.getTick() - (rightClick ? SHORT_SKIP : LONG_SKIP));
            case FORWARD -> session.seek(session.getTick() + (rightClick ? SHORT_SKIP : LONG_SKIP));
            case PLAY_PAUSE -> session.setPaused(!session.isPaused());
            case SPEED -> {
                int index = 0;
                for (int i = 0; i < SPEEDS.length; i++) {
                    if (Math.abs(SPEEDS[i] - session.getSpeed()) < Math.abs(SPEEDS[index] - session.getSpeed())) {
                        index = i;
                    }
                }
                index = Math.max(0, Math.min(SPEEDS.length - 1, index + (rightClick ? 1 : -1)));
                session.setSpeed(SPEEDS[index]);
            }
            case CONTROLS -> ControlPanel.send(player, session);
            case LEAVE -> session.removeViewer(player);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ menus

    /** Runs a menu action only if the player still watches the session. */
    private static boolean stillWatching(Player player, PlaybackSessionImpl session) {
        return session.isRunning() && session.getViewers().contains(player);
    }

    /** Version of a player's client, as shown in the menus. */
    static String clientVersion(ReplayPlayer player) {
        return player.clientVersion() != null ? player.clientVersion() : "inconnue";
    }

    private static void openPointOfViewMenu(Player player, PlaybackSessionImpl session) {
        List<ReplayPlayer> players = session.getReplay().players();
        Menus.Menu menu = Menus.create(Menus.rowsFor(players.size() + 1), Component.text("Point de vue"));
        menu.set(0, item(Platform.Icon.ENDER_EYE, Component.text("Caméra libre", NamedTextColor.AQUA),
                List.of(hint("Vue fusionnée de tous les joueurs"))), click -> {
            if (stillWatching(player, session)) {
                player.closeInventory();
                session.setPointOfView(player, null);
            }
        });

        UUID current = session.getPointOfView(player).orElse(null);
        for (int i = 0; i < players.size() && i + 1 < 54; i++) {
            ReplayPlayer recorded = players.get(i);
            boolean watched = recorded.uuid().equals(current);
            List<Component> lore = List.of(
                    Component.text("Version : " + clientVersion(recorded), NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false),
                    hint(watched ? "Point de vue actuel" : "Clic : voir par ses yeux").decoration(TextDecoration.ITALIC, false));
            ItemStack head = Platforms.get().head(recorded.uuid(), recorded.name(),
                    Component.text(recorded.name(), watched ? NamedTextColor.GREEN : NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false), lore);
            menu.set(i + 1, head, click -> {
                if (stillWatching(player, session)) {
                    player.closeInventory();
                    session.setPointOfView(player, recorded.uuid());
                }
            });
        }
        menu.open(player);
    }

    /** Markers of the replay, past ones greyed out, with page buttons when there are many. */
    private static void openMarkersMenu(Player player, PlaybackSessionImpl session, int page) {
        List<ReplayMarker> markers = session.getReplay().markers();
        if (markers.isEmpty()) {
            Text.actionBar(player, Component.text("Aucun marqueur dans ce replay", NamedTextColor.GRAY));
            return;
        }
        int pages = (markers.size() + MARKERS_PER_PAGE - 1) / MARKERS_PER_PAGE;
        int shown = Math.max(0, Math.min(pages - 1, page));
        int from = shown * MARKERS_PER_PAGE;
        int to = Math.min(markers.size(), from + MARKERS_PER_PAGE);

        Menus.Menu menu = Menus.create(pages > 1 ? 6 : Menus.rowsFor(to - from),
                Component.text(pages > 1 ? "Marqueurs " + (shown + 1) + "/" + pages : "Marqueurs"));
        int now = session.getTick();
        for (int i = from; i < to; i++) {
            ReplayMarker marker = markers.get(i);
            boolean past = marker.tick() <= now;
            List<Component> lore = new ArrayList<>();
            lore.add(hint("À " + TimeFormat.format(marker.tick()) + (past ? " (déjà passé)" : "")));
            if (marker.data() != null && !marker.data().isBlank()) {
                lore.add(Component.text(marker.data(), NamedTextColor.DARK_GRAY));
            }
            lore.add(hint("Clic : y aller"));
            menu.set(i - from, item(past ? Platform.Icon.PAPER : Platform.Icon.NAME_TAG,
                    Component.text("⚑ " + marker.name(), past ? NamedTextColor.GRAY : NamedTextColor.GOLD), lore), click -> {
                if (stillWatching(player, session)) {
                    player.closeInventory();
                    session.seek(marker.tick());
                    Text.actionBar(player, Component.text("⚑ " + TimeFormat.format(marker.tick()), NamedTextColor.GOLD));
                }
            });
        }
        if (shown > 0) {
            menu.set(45, item(Platform.Icon.ARROW, Component.text("◀ Page précédente", NamedTextColor.YELLOW), List.of()), click -> {
                if (stillWatching(player, session)) {
                    openMarkersMenu(player, session, shown - 1);
                }
            });
        }
        if (shown < pages - 1) {
            menu.set(53, item(Platform.Icon.ARROW, Component.text("Page suivante ▶", NamedTextColor.YELLOW), List.of()), click -> {
                if (stillWatching(player, session)) {
                    openMarkersMenu(player, session, shown + 1);
                }
            });
        }
        menu.open(player);
    }
}
