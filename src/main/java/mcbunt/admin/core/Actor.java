package mcbunt.admin.core;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;

/** Origin identity, never a network address. See SYS-COMMANDS. */
public record Actor(String id, String source, CommandSender sender) {
    public static Actor from(CommandSender sender) {
        if (sender instanceof Player player) return new Actor(player.getUniqueId().toString(), "PLAYER", sender);
        if (sender instanceof RemoteConsoleCommandSender) return new Actor("RCON", "RCON", sender);
        if (sender instanceof ConsoleCommandSender) return new Actor("CONSOLE", "CONSOLE", sender);
        return new Actor(sender.getName(), "UNSUPPORTED", sender);
    }

    public static Actor system() { return new Actor("SYSTEM", "SYSTEM", null); }
    public boolean supported() {
        return sender == null || sender instanceof Player || sender instanceof ConsoleCommandSender
                || sender instanceof RemoteConsoleCommandSender;
    }
    public boolean hasPermission(String permission) {
        return supported() && (sender == null || sender.hasPermission(permission));
    }
}
