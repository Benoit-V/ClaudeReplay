package fr.bebedlastreat.claudeReplay.util;

import fr.bebedlastreat.claudeReplay.platform.Platforms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Sends Adventure components on every version (old servers have no Adventure support of their own).
 */
public final class Text {

    private Text() {
    }

    public static void send(CommandSender sender, Component message) {
        Platforms.get().sendMessage(sender, message);
    }

    public static void actionBar(Player player, Component message) {
        Platforms.get().sendActionBar(player, message);
    }

    public static void error(CommandSender sender, String message) {
        send(sender, Component.text(message, NamedTextColor.RED));
    }

    public static void info(CommandSender sender, String message) {
        send(sender, Component.text(message, NamedTextColor.GRAY));
    }
}
