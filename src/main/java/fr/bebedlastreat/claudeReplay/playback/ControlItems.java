package fr.bebedlastreat.claudeReplay.playback;

import fr.bebedlastreat.claudeReplay.api.replay.ReplayPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * The hotbar used to control a replay, and the point of view menu.
 */
final class ControlItems {

    enum Action {
        POINT_OF_VIEW, BACKWARD, PLAY_PAUSE, FORWARD, SPEED, CONTROLS, LEAVE
    }

    static final double[] SPEEDS = {0.25, 0.5, 1, 2, 4, 8};
    private static final int SHORT_SKIP = 10 * 20;
    private static final int LONG_SKIP = 60 * 20;

    private static NamespacedKey actionKey;
    private static NamespacedKey targetKey;

    private ControlItems() {
    }

    static void init(Plugin plugin) {
        actionKey = new NamespacedKey(plugin, "control");
        targetKey = new NamespacedKey(plugin, "pov_target");
    }

    private static ItemStack item(Material material, Action action, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action.name());
        });
        return item;
    }

    private static Component hint(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    /** Fills the viewer's hotbar with the controls, reflecting the session state. */
    static void give(Player player, PlaybackSessionImpl session) {
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(0, item(Material.PLAYER_HEAD, Action.POINT_OF_VIEW, Component.text("Points de vue", NamedTextColor.LIGHT_PURPLE),
                List.of(hint("Clic droit : choisir un joueur"), hint("Sneak : revenir en caméra libre"))));
        inventory.setItem(2, item(Material.RED_DYE, Action.BACKWARD, Component.text("⏪ Reculer", NamedTextColor.RED),
                List.of(hint("Clic droit : -10 secondes"), hint("Clic gauche : -1 minute"))));
        inventory.setItem(3, session.isPaused()
                ? item(Material.GRAY_DYE, Action.PLAY_PAUSE, Component.text("▶ Lecture", NamedTextColor.GREEN), List.of(hint("Clic : reprendre")))
                : item(Material.LIME_DYE, Action.PLAY_PAUSE, Component.text("⏸ Pause", NamedTextColor.YELLOW), List.of(hint("Clic : mettre en pause"))));
        inventory.setItem(4, item(Material.LIGHT_BLUE_DYE, Action.FORWARD, Component.text("⏩ Avancer", NamedTextColor.AQUA),
                List.of(hint("Clic droit : +10 secondes"), hint("Clic gauche : +1 minute"))));
        inventory.setItem(6, item(Material.CLOCK, Action.SPEED, Component.text("Vitesse x" + PlaybackSessionImpl.formatSpeed(session.getSpeed()), NamedTextColor.GOLD),
                List.of(hint("Clic droit : plus vite"), hint("Clic gauche : moins vite"))));
        inventory.setItem(7, item(Material.BOOK, Action.CONTROLS, Component.text("Panneau de contrôle", NamedTextColor.WHITE),
                List.of(hint("Clic : afficher les commandes et marqueurs"))));
        inventory.setItem(8, item(Material.BARRIER, Action.LEAVE, Component.text("Quitter le replay", NamedTextColor.RED),
                List.of(hint("Clic : revenir là où tu étais"))));
    }

    static Action actionOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String value = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            return Action.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean isControlItem(ItemStack item) {
        return actionOf(item) != null;
    }

    /** Runs the action of a control item. */
    static void use(Player player, PlaybackSessionImpl session, Action action, boolean rightClick) {
        switch (action) {
            case POINT_OF_VIEW -> openMenu(player, session);
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
        }
    }

    // ------------------------------------------------------------------ point of view menu

    /** Holder of the point of view menu. */
    static final class Menu implements InventoryHolder {
        private final PlaybackSessionImpl session;
        private Inventory inventory;

        Menu(PlaybackSessionImpl session) {
            this.session = session;
        }

        PlaybackSessionImpl session() {
            return session;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private static void openMenu(Player player, PlaybackSessionImpl session) {
        List<ReplayPlayer> players = session.getReplay().players();
        int size = Math.min(54, Math.max(9, (int) Math.ceil((players.size() + 1) / 9.0) * 9));
        Menu menu = new Menu(session);
        Inventory inventory = Bukkit.createInventory(menu, size, Component.text("Point de vue"));
        menu.inventory = inventory;

        ItemStack free = new ItemStack(Material.ENDER_EYE);
        free.editMeta(meta -> {
            meta.displayName(Component.text("Caméra libre", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(hint("Vue fusionnée de tous les joueurs").decoration(TextDecoration.ITALIC, false)));
        });
        inventory.setItem(0, free);

        UUID current = session.getPointOfView(player).orElse(null);
        for (int i = 0; i < players.size() && i + 1 < size; i++) {
            ReplayPlayer recorded = players.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            head.editMeta(SkullMeta.class, meta -> {
                meta.setPlayerProfile(Bukkit.createProfile(recorded.uuid(), recorded.name()));
                meta.displayName(Component.text(recorded.name(), recorded.uuid().equals(current) ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(hint(recorded.uuid().equals(current) ? "Point de vue actuel" : "Clic : voir par ses yeux")
                        .decoration(TextDecoration.ITALIC, false)));
                meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, recorded.uuid().toString());
            });
            inventory.setItem(i + 1, head);
        }
        player.openInventory(inventory);
    }

    /** Handles a click in the point of view menu. */
    static void clickMenu(Player player, Menu menu, ItemStack clicked) {
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        player.closeInventory();
        if (clicked.getType() == Material.ENDER_EYE) {
            menu.session().setPointOfView(player, null);
            return;
        }
        String target = clicked.hasItemMeta() ? clicked.getItemMeta().getPersistentDataContainer().get(targetKey, PersistentDataType.STRING) : null;
        if (target != null) {
            menu.session().setPointOfView(player, UUID.fromString(target));
        }
    }
}
