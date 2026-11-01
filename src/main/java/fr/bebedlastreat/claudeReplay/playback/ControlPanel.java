package fr.bebedlastreat.claudeReplay.playback;

import fr.bebedlastreat.claudeReplay.api.playback.PlaybackSession;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayMarker;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayPlayer;
import fr.bebedlastreat.claudeReplay.util.TimeFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

/**
 * Clickable chat controls of a session.
 */
public final class ControlPanel {

    private ControlPanel() {
    }

    private static Component button(String label, String command, String hover, NamedTextColor color) {
        return Component.text("[" + label + "]", color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover)));
    }

    public static void send(Player player, PlaybackSession session) {
        Component space = Component.space();
        player.sendMessage(Component.text("━━━━━━ Replay : " + session.getReplay().name() + " ━━━━━━", NamedTextColor.GOLD));
        player.sendMessage(Component.text()
                .append(button("⏯", "/replay toggle", "Pause / lecture", NamedTextColor.GREEN)).append(space)
                .append(button("-30s", "/replay skip -30", "Reculer de 30 secondes", NamedTextColor.AQUA)).append(space)
                .append(button("-5s", "/replay skip -5", "Reculer de 5 secondes", NamedTextColor.AQUA)).append(space)
                .append(button("+5s", "/replay skip 5", "Avancer de 5 secondes", NamedTextColor.AQUA)).append(space)
                .append(button("+30s", "/replay skip 30", "Avancer de 30 secondes", NamedTextColor.AQUA)).append(space)
                .append(button("x0.5", "/replay speed 0.5", "Ralenti", NamedTextColor.YELLOW)).append(space)
                .append(button("x1", "/replay speed 1", "Vitesse normale", NamedTextColor.YELLOW)).append(space)
                .append(button("x2", "/replay speed 2", "Accéléré", NamedTextColor.YELLOW)).append(space)
                .append(button("x4", "/replay speed 4", "Très accéléré", NamedTextColor.YELLOW))
                .build());

        TextComponent.Builder pov = Component.text().append(Component.text("Points de vue : ", NamedTextColor.GRAY))
                .append(button("Libre", "/replay free", "Caméra libre (vue fusionnée)", NamedTextColor.WHITE));
        for (ReplayPlayer recorded : session.getReplay().players()) {
            pov.append(space).append(button(recorded.name(), "/replay pov " + recorded.name(), "Voir par les yeux de " + recorded.name(), NamedTextColor.LIGHT_PURPLE));
        }
        player.sendMessage(pov.build());

        if (!session.getReplay().markers().isEmpty()) {
            TextComponent.Builder markers = Component.text().append(Component.text("Marqueurs : ", NamedTextColor.GRAY));
            for (ReplayMarker marker : session.getReplay().markers()) {
                markers.append(button(marker.name() + " " + TimeFormat.format(marker.tick()), "/replay seek " + marker.tick() + "t",
                        "Aller à " + TimeFormat.format(marker.tick()), NamedTextColor.GOLD)).append(space);
            }
            player.sendMessage(markers.build());
        }
        player.sendMessage(Component.text()
                .append(button("Quitter", "/replay leave", "Quitter le replay", NamedTextColor.RED))
                .append(Component.text("  Astuce : clic sur un joueur ou menu spectateur (molette) pour prendre son point de vue, sneak pour revenir.", NamedTextColor.DARK_GRAY))
                .build());
    }
}
