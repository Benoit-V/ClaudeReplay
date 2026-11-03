package fr.bebedlastreat.claudeReplay.playback;

import fr.bebedlastreat.claudeReplay.api.playback.PlaybackSession;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayMarker;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayPlayer;
import fr.bebedlastreat.claudeReplay.util.Lang;
import fr.bebedlastreat.claudeReplay.util.Text;
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
        Text.send(player, Component.text(Lang.tr("controls.header", "name", session.getReplay().name()), NamedTextColor.GOLD));
        Text.send(player, Component.text()
                .append(button("⏯", "/replay toggle", Lang.tr("controls.toggle"), NamedTextColor.GREEN)).append(space)
                .append(button("-30s", "/replay skip -30", Lang.tr("controls.backward", "seconds", 30), NamedTextColor.AQUA)).append(space)
                .append(button("-5s", "/replay skip -5", Lang.tr("controls.backward", "seconds", 5), NamedTextColor.AQUA)).append(space)
                .append(button("+5s", "/replay skip 5", Lang.tr("controls.forward", "seconds", 5), NamedTextColor.AQUA)).append(space)
                .append(button("+30s", "/replay skip 30", Lang.tr("controls.forward", "seconds", 30), NamedTextColor.AQUA)).append(space)
                .append(button("x0.5", "/replay speed 0.5", Lang.tr("controls.slow"), NamedTextColor.YELLOW)).append(space)
                .append(button("x1", "/replay speed 1", Lang.tr("controls.normal"), NamedTextColor.YELLOW)).append(space)
                .append(button("x2", "/replay speed 2", Lang.tr("controls.fast"), NamedTextColor.YELLOW)).append(space)
                .append(button("x4", "/replay speed 4", Lang.tr("controls.faster"), NamedTextColor.YELLOW))
                .build());

        TextComponent.Builder pov = Component.text().append(Component.text(Lang.tr("controls.points-of-view"), NamedTextColor.GRAY))
                .append(button(Lang.tr("controls.free"), "/replay free", Lang.tr("controls.free-hover"), NamedTextColor.WHITE));
        for (ReplayPlayer recorded : session.getReplay().players()) {
            pov.append(space).append(button(recorded.name(), "/replay pov " + recorded.name(), Lang.tr("controls.pov-hover", "player", recorded.name()), NamedTextColor.LIGHT_PURPLE));
        }
        Text.send(player, pov.build());

        if (!session.getReplay().markers().isEmpty()) {
            TextComponent.Builder markers = Component.text().append(Component.text(Lang.tr("controls.markers"), NamedTextColor.GRAY));
            for (ReplayMarker marker : session.getReplay().markers()) {
                markers.append(button(marker.name() + " " + TimeFormat.format(marker.tick()), "/replay seek " + marker.tick() + "t",
                        Lang.tr("controls.marker-hover", "time", TimeFormat.format(marker.tick())), NamedTextColor.GOLD)).append(space);
            }
            Text.send(player, markers.build());
        }
        Text.send(player, Component.text()
                .append(button(Lang.tr("controls.leave"), "/replay leave", Lang.tr("controls.leave-hover"), NamedTextColor.RED))
                .append(Component.text(Lang.tr("controls.tip"), NamedTextColor.DARK_GRAY))
                .build());
    }
}
