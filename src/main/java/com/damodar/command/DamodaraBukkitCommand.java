package com.damodar.command;

import com.damodar.DamodarPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@Deprecated
public final class DamodaraBukkitCommand extends Command implements PluginIdentifiableCommand {

    private final DamodarPlugin plugin;
    private final DamodarCommand handler;

    public DamodaraBukkitCommand(DamodarPlugin plugin, DamodarCommand handler) {
        super(
                "damodara",
                "Primary management command for Damodara backups and cloud synchronization.",
                "/damodara [backup|backups|status|reload|auth|help]",
                List.of("dm")
        );
        this.plugin = plugin;
        this.handler = handler;
        setPermission("damodara.admin");
    }

    @Override
    public @NotNull Plugin getPlugin() {
        return plugin;
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        return handler.onCommand(sender, this, commandLabel, args);
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        return handler.onTabComplete(sender, this, alias, args);
    }
}
