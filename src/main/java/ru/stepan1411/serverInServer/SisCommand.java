package ru.stepan1411.serverInServer;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

public class SisCommand implements SimpleCommand {

    private final ServerInServer plugin;

    public SisCommand(ServerInServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            source.sendMessage(usage());
            return;
        }

        String action = args[0].toLowerCase();

        switch (action) {
            case "start" -> {
                if (args.length < 2) {
                    source.sendMessage(Component.text("Usage: /sis start <server>", NamedTextColor.RED));
                    return;
                }
                String name = args[1];
                if (plugin.isServerRunning(name)) {
                    source.sendMessage(Component.text("Server '" + name + "' is already running", NamedTextColor.YELLOW));
                    return;
                }
                boolean ok = plugin.startServer(name);
                if (ok) {
                    source.sendMessage(Component.text("Server '" + name + "' started", NamedTextColor.GREEN));
                } else {
                    source.sendMessage(Component.text("Failed to start server '" + name + "'", NamedTextColor.RED));
                }
            }
            case "stop" -> {
                if (args.length < 2) {
                    source.sendMessage(Component.text("Usage: /sis stop <server>", NamedTextColor.RED));
                    return;
                }
                String name = args[1];
                if (!plugin.isServerRunning(name)) {
                    source.sendMessage(Component.text("Server '" + name + "' is not running", NamedTextColor.YELLOW));
                    return;
                }
                plugin.stopServer(name);
                source.sendMessage(Component.text("Server '" + name + "' stopped", NamedTextColor.GREEN));
            }
            case "restart" -> {
                if (args.length < 2) {
                    source.sendMessage(Component.text("Usage: /sis restart <server>", NamedTextColor.RED));
                    return;
                }
                String name = args[1];
                source.sendMessage(Component.text("Restarting server '" + name + "'...", NamedTextColor.AQUA));
                plugin.restartServer(name);
                source.sendMessage(Component.text("Server '" + name + "' restarted", NamedTextColor.GREEN));
            }
            case "reload" -> {
                source.sendMessage(Component.text("Reloading config...", NamedTextColor.AQUA));
                plugin.reloadConfig();
                source.sendMessage(Component.text("Config reloaded", NamedTextColor.GREEN));
            }
            case "list" -> {
                List<ServerEntry> entries = plugin.getServerEntries();
                if (entries.isEmpty()) {
                    source.sendMessage(Component.text("No servers configured", NamedTextColor.YELLOW));
                    return;
                }
                source.sendMessage(Component.text("--- Configured servers ---", NamedTextColor.AQUA));
                for (ServerEntry entry : entries) {
                    boolean running = plugin.isServerRunning(entry.getName());
                    Component status = running
                        ? Component.text("RUNNING", NamedTextColor.GREEN)
                        : Component.text("STOPPED", NamedTextColor.RED);
                    source.sendMessage(Component.text()
                        .append(Component.text("  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(entry.getName(), NamedTextColor.YELLOW))
                        .append(Component.text(" (port: " + entry.getPort() + ") ", NamedTextColor.GRAY))
                        .append(Component.text("[", NamedTextColor.DARK_GRAY))
                        .append(status)
                        .append(Component.text("]", NamedTextColor.DARK_GRAY))
                        .build());
                }
            }
            case "forcestop" -> {
                source.sendMessage(Component.text("Force stopping all servers and shutting down Velocity...", NamedTextColor.RED));
                plugin.forceStopAndShutdown();
            }
            case "com", "command" -> {
                if (args.length < 3) {
                    source.sendMessage(Component.text("Usage: /sis com <server> <command>", NamedTextColor.RED));
                    return;
                }
                String name = args[1];
                StringBuilder cmd = new StringBuilder();
                for (int i = 2; i < args.length; i++) {
                    if (cmd.length() > 0) cmd.append(' ');
                    cmd.append(args[i]);
                }
                if (!plugin.isServerRunning(name)) {
                    source.sendMessage(Component.text("Server '" + name + "' is not running", NamedTextColor.YELLOW));
                    return;
                }
                plugin.sendCommand(name, cmd.toString());
                source.sendMessage(Component.text("Command sent to '" + name + "'", NamedTextColor.GREEN));
            }
            default -> source.sendMessage(usage());
        }
    }

    private Component usage() {
        return Component.text("Usage: /sis <start|stop|restart|forcestop|reload|list|com> [server] [command]", NamedTextColor.RED);
    }
}
