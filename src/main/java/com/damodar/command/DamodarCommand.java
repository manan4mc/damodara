package com.damodar.command;

import com.damodar.DamodarPlugin;
import com.damodar.auth.DeviceFlowAuthService;
import com.damodar.auth.TokenStore;
import com.damodar.config.DamodarConfig;
import com.damodar.scheduler.BackupScheduler;
import com.damodar.backup.BackupService;
import com.damodar.backup.BackupStatus;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class DamodarCommand implements CommandExecutor, TabCompleter {

    private static final int BACKUPS_PAGE_SIZE = 8;

    private final DamodarPlugin plugin;
    private final BackupService backupService;
    private final TokenStore tokenStore;
    private final DeviceFlowAuthService deviceFlowAuthService;

    private final DateTimeFormatter displayFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    public DamodarCommand(
            DamodarPlugin plugin,
            BackupService backupService,
            TokenStore tokenStore,
            DeviceFlowAuthService deviceFlowAuthService
    ) {
        this.plugin = plugin;
        this.backupService = backupService;
        this.tokenStore = tokenStore;
        this.deviceFlowAuthService = deviceFlowAuthService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!hasAnyPermission(sender)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use Damodara commands.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "backup" -> handleBackup(sender, label, args);
            case "backups" -> handleBackupsList(sender, args);
            case "status" -> handleStatus(sender);
            case "reload" -> handleReload(sender);
            case "auth" -> handleAuth(sender, label, args);
            case "restore" -> sender.sendMessage(ChatColor.GOLD + "[Damodara] "
                    + ChatColor.YELLOW + "In-game restoration is disabled to prevent live world corruption. "
                    + "Stop the server completely, then extract your chosen archive from the backups directory.");
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown sub-command: " + args[0]);
                sendHelp(sender, label);
            }
        }
        return true;
    }

    private void handleBackup(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("damodara.backup") && !sender.hasPermission("damodara.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage backups.");
            return;
        }

        if (args.length < 2 || !args[1].equalsIgnoreCase("now")) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " backup now");
            return;
        }

        triggerManualBackup(sender);
    }

    private void triggerManualBackup(CommandSender sender) {
        if (backupService.isBackupRunning()) {
            sender.sendMessage(ChatColor.RED + "[Damodara] A backup is already running. Please wait.");
            return;
        }
        sender.sendMessage(ChatColor.AQUA + "[Damodara] Initiating manual asynchronous backup...");
        backupService.performBackupAsync("COMMAND:" + sender.getName()).thenAccept(success -> {
            if (success) {
                sender.sendMessage(ChatColor.GREEN + "[Damodara] Manual backup finished successfully!");
            } else {
                sender.sendMessage(ChatColor.RED + "[Damodara] Manual backup failed. Check server console for details.");
            }
        });
    }

    private void handleBackupsList(CommandSender sender, String[] args) {
        if (!sender.hasPermission("damodara.status") && !sender.hasPermission("damodara.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to view backup lists.");
            return;
        }

        DamodarConfig cfg = plugin.getDamodarConfig();
        if (cfg == null) {
            sender.sendMessage(ChatColor.RED + "[Damodara] Configuration not available.");
            return;
        }

        Path destDir = cfg.getDestinationDirectory();
        if (!Files.isDirectory(destDir)) {
            sender.sendMessage(ChatColor.YELLOW + "[Damodara] Backup directory does not exist yet: " + destDir);
            return;
        }

        List<BackupEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(destDir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".zip") && !name.endsWith(".tmp");
            }).forEach(p -> {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                    entries.add(new BackupEntry(p, attrs.size(), attrs.creationTime().toInstant()));
                } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + "[Damodara] Could not list backup directory: " + e.getMessage());
            return;
        }

        if (entries.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "[Damodara] No backup archives found in: " + destDir);
            return;
        }

        entries.sort(Comparator.comparing(BackupEntry::createdAt).reversed());

        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {}
        }

        int totalPages = (int) Math.ceil((double) entries.size() / BACKUPS_PAGE_SIZE);
        page = Math.max(1, Math.min(page, totalPages));
        int from = (page - 1) * BACKUPS_PAGE_SIZE;
        int to = Math.min(from + BACKUPS_PAGE_SIZE, entries.size());

        long totalBytes = entries.stream().mapToLong(BackupEntry::sizeBytes).sum();

        sender.sendMessage(ChatColor.GOLD + "=== Damodara Backups (Page " + page + "/" + totalPages
                + ", " + entries.size() + " archives, "
                + formatSize(totalBytes) + " total) ===");

        BackupStatus status = backupService.getStatus();
        Path lastSuccessFile = status.getLastBackupFile();
        boolean lastWasUploaded = status.getLastDriveUploadTime() != null && status.isLastDriveUploadSuccess();

        for (int i = from; i < to; i++) {
            BackupEntry e = entries.get(i);
            String date = displayFormatter.format(e.createdAt());
            String size = formatSize(e.sizeBytes());
            boolean isLast = (lastSuccessFile != null
                    && lastSuccessFile.getFileName().equals(e.path().getFileName()));
            String uploadTag = isLast && lastWasUploaded ? ChatColor.GREEN + " [Drive ✓]" : "";
            String newest = (i == 0) ? ChatColor.AQUA + " [newest]" : "";
            sender.sendMessage("" + ChatColor.YELLOW + (i + 1) + ". " + ChatColor.WHITE
                    + e.path().getFileName() + ChatColor.GRAY + " | " + date
                    + " | " + size + uploadTag + newest);
        }

        if (totalPages > 1) {
            sender.sendMessage(ChatColor.GRAY + "Use /damodara backups " + (page + 1) + " for the next page.");
        }
        sender.sendMessage(ChatColor.GOLD + "==============================");
    }

    private void handleStatus(CommandSender sender) {
        if (!sender.hasPermission("damodara.status") && !sender.hasPermission("damodara.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to view Damodara status.");
            return;
        }

        BackupStatus status = backupService.getStatus();
        BackupScheduler scheduler = plugin.getBackupScheduler();
        DamodarConfig cfg = plugin.getDamodarConfig();

        sender.sendMessage(ChatColor.GOLD + "=== Damodara Status ===");
        sender.sendMessage(ChatColor.YELLOW + "Plugin: " + ChatColor.GREEN + "RUNNING");

        boolean schedRunning = scheduler != null && scheduler.isScheduleRunning();
        sender.sendMessage(ChatColor.YELLOW + "Scheduled Backups: "
                + (schedRunning ? ChatColor.GREEN + "ENABLED" : ChatColor.RED + "DISABLED"));

        if (cfg != null) {
            sender.sendMessage(ChatColor.YELLOW + "Schedule: "
                    + ChatColor.WHITE + "Every " + cfg.getDaysInterval() + " day(s) at " + cfg.getScheduleTimes() + " (" + cfg.getScheduleTimeZone().getId() + ")");
        }

        if (schedRunning && scheduler != null) {
            Instant next = scheduler.getNextScheduledRun();
            if (next != null) {
                sender.sendMessage(ChatColor.YELLOW + "Next Scheduled: "
                        + ChatColor.WHITE + displayFormatter.format(next));
            }
        }

        if (cfg != null) {
            sender.sendMessage(ChatColor.YELLOW + "Retention: "
                    + ChatColor.WHITE + "Local: " + (cfg.isLocalRetentionEnabled() ? "keep " + cfg.getLocalRetentionMaxCount() : "off")
                    + ChatColor.GRAY + " | "
                    + ChatColor.WHITE + "Drive: " + (cfg.isDriveRetentionEnabled() ? "keep " + cfg.getDriveRetentionMaxCount() : "off"));
        }

        if (status.isRunning()) {
            sender.sendMessage(ChatColor.YELLOW + "Backup State: " + ChatColor.GREEN + "RUNNING");
            if (status.getCurrentBackupStartTime() != null) {
                long elapsed = Duration.between(status.getCurrentBackupStartTime(), Instant.now()).toSeconds();
                sender.sendMessage(ChatColor.YELLOW + "Elapsed: " + ChatColor.WHITE + elapsed + "s");
            }
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Backup State: " + ChatColor.GRAY + "IDLE");
        }

        if (status.getLastBackupEndTime() != null) {
            sender.sendMessage(ChatColor.YELLOW + "Last Run: "
                    + ChatColor.WHITE + displayFormatter.format(status.getLastBackupEndTime()));
            sender.sendMessage(ChatColor.YELLOW + "Outcome: "
                    + (status.isLastBackupSuccess()
                    ? ChatColor.GREEN + "SUCCESS"
                    : ChatColor.RED + "FAILED"));
            sender.sendMessage(ChatColor.YELLOW + "Duration: "
                    + ChatColor.WHITE + status.getLastBackupDuration().toSeconds() + "s");

            if (status.isLastBackupSuccess()) {
                if (status.getLastBackupFile() != null) {
                    sender.sendMessage(ChatColor.YELLOW + "File: "
                            + ChatColor.WHITE + status.getLastBackupFile().getFileName());
                }
                sender.sendMessage(ChatColor.YELLOW + "Size: "
                        + ChatColor.WHITE + formatSize(status.getLastBackupSizeBytes()));
                if (status.getLastBackupChecksum() != null) {
                    sender.sendMessage(ChatColor.YELLOW + "SHA-256: "
                            + ChatColor.GRAY + status.getLastBackupChecksum().substring(0, Math.min(16, status.getLastBackupChecksum().length())) + "...");
                }
                if (status.getLastDriveUploadTime() != null) {
                    sender.sendMessage(ChatColor.YELLOW + "Drive Sync: "
                            + (status.isLastDriveUploadSuccess()
                            ? ChatColor.GREEN + "SYNCED"
                            : ChatColor.RED + "UPLOAD FAILED (local backup preserved)"));
                }
            } else if (status.getLastErrorMessage() != null) {
                sender.sendMessage(ChatColor.RED + "Last Error: " + status.getLastErrorMessage());
            }
        } else {
            sender.sendMessage(ChatColor.GRAY + "No backups have been executed since last boot.");
        }

        if (cfg != null && Files.isDirectory(cfg.getDestinationDirectory())) {
            try (Stream<Path> stream = Files.list(cfg.getDestinationDirectory())) {
                List<Path> archives = stream
                        .filter(p -> p.getFileName().toString().endsWith(".zip"))
                        .toList();
                long totalSize = 0;
                for (Path p : archives) {
                    try { totalSize += Files.size(p); } catch (IOException ignored) {}
                }
                sender.sendMessage(ChatColor.YELLOW + "Local Backups: "
                        + ChatColor.WHITE + archives.size() + " archives, " + formatSize(totalSize));
            } catch (IOException ignored) {}
        }

        sender.sendMessage(ChatColor.YELLOW + "Google Drive: "
                + (tokenStore.isAuthorized()
                ? ChatColor.GREEN + "CONNECTED"
                : ChatColor.RED + "NOT CONNECTED")
                + (cfg != null && cfg.isGoogleDriveEnabled() ? "" : ChatColor.GRAY + " (uploads disabled in config)"));

        if (deviceFlowAuthService != null && deviceFlowAuthService.isLinking()) {
            sender.sendMessage(ChatColor.AQUA + "[Linking in progress — check server console for URL and code]");
        }

        sender.sendMessage(ChatColor.GOLD + "=======================");
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("damodara.reload") && !sender.hasPermission("damodara.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to reload Damodara.");
            return;
        }

        sender.sendMessage(ChatColor.AQUA + "[Damodara] Reloading configuration...");
        try {
            plugin.reloadPluginConfig();
            sender.sendMessage(ChatColor.GREEN + "[Damodara] Configuration reloaded successfully.");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "[Damodara] Reload failed: " + e.getMessage());
            plugin.getLogger().warning("[Damodara] Config reload failed: " + e.getMessage());
        }
    }

    private void handleAuth(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(ChatColor.RED + "[Damodara] Unknown sub-command or access restricted. "
                    + "Authentication commands can only be run from the server console.");
            return;
        }

        if (!sender.hasPermission("damodara.auth") && !sender.hasPermission("damodara.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage Damodara authentication.");
            return;
        }

        String action = (args.length >= 2) ? args[1].toLowerCase() : "status";

        switch (action) {
            case "connect" -> handleAuthConnect(sender);
            case "status" -> handleAuthStatus(sender);
            case "disconnect" -> handleAuthDisconnect(sender);
            default -> sender.sendMessage(ChatColor.RED
                    + "Unknown auth action. Use: connect, status, or disconnect.");
        }
    }

    private void handleAuthConnect(CommandSender sender) {
        DamodarConfig cfg = plugin.getDamodarConfig();
        if (cfg == null) {
            sender.sendMessage(ChatColor.RED + "[Damodara Auth] Configuration not loaded. Cannot start device flow.");
            return;
        }

        String clientId = cfg.getGoogleDriveClientId();
        String clientSecret = cfg.getGoogleDriveClientSecret();

        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            sender.sendMessage(ChatColor.RED + "[Damodara Auth] OAuth client credentials are not configured in this build.");
            sender.sendMessage(ChatColor.YELLOW + "If you built Damodara from source, configure your own OAuth client:");
            sender.sendMessage(ChatColor.WHITE + "  1. Copy 'oauth.properties.example' to 'oauth.properties' in the project root.");
            sender.sendMessage(ChatColor.WHITE + "  2. Fill in your Google Cloud OAuth 2.0 client credentials (type: 'TVs and Limited Input devices').");
            sender.sendMessage(ChatColor.WHITE + "  3. Rebuild the plugin artifact.");
            sender.sendMessage(ChatColor.GRAY + "If you are using an official release, please verify your build was not modified.");
            return;
        }

        if (deviceFlowAuthService.isLinking()) {
            sender.sendMessage(ChatColor.YELLOW + "[Damodara Auth] A linking session is already in progress. "
                    + "Check the console above for the verification URL and code.");
            return;
        }

        sender.sendMessage(ChatColor.AQUA + "[Damodara Auth] Requesting Google Drive device authorization code...");

        deviceFlowAuthService.startDeviceFlow(clientId, clientSecret).thenAccept(success -> {
            if (success) {
                plugin.getLogger().info("[Damodara Auth] Device linking completed successfully. "
                        + "Set google-drive.enabled: true in config.yml (if not already enabled) and run /damodara reload.");
            } else {
                plugin.getLogger().warning("[Damodara Auth] Device linking did not complete. "
                        + "Run '/damodara auth connect' in the server console to try again.");
            }
        });
    }

    private void handleAuthStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Damodara Google Drive Auth ===");
        sender.sendMessage(ChatColor.YELLOW + "Status: "
                + (tokenStore.isAuthorized() ? ChatColor.GREEN + "CONNECTED" : ChatColor.RED + "NOT CONNECTED"));
        if (deviceFlowAuthService.isLinking()) {
            sender.sendMessage(ChatColor.AQUA + "Device linking session in progress. "
                    + "Check console for URL and code.");
        }
        sender.sendMessage(ChatColor.GRAY + "To link: /damodara auth connect (console only)");
        sender.sendMessage(ChatColor.GRAY + "To unlink: /damodara auth disconnect (console only)");
        sender.sendMessage(ChatColor.GOLD + "=================================");
    }

    private void handleAuthDisconnect(CommandSender sender) {
        if (deviceFlowAuthService.isLinking()) {
            deviceFlowAuthService.cancelLinking();
            sender.sendMessage(ChatColor.YELLOW + "[Damodara Auth] In-progress linking session cancelled.");
        }

        if (!tokenStore.isAuthorized()) {
            sender.sendMessage(ChatColor.YELLOW + "[Damodara Auth] No Google Drive account is currently connected.");
            return;
        }

        sender.sendMessage(ChatColor.AQUA + "[Damodara Auth] Revoking Google Drive authorization...");
        boolean revokedAtGoogle = tokenStore.revokeAndDisconnect();
        if (revokedAtGoogle) {
            sender.sendMessage(ChatColor.GREEN + "[Damodara Auth] Authorization revoked at Google and credentials removed locally.");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "[Damodara Auth] Local credentials removed. "
                    + "(Google revocation endpoint could not be confirmed — token may have already expired.)");
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        boolean isConsole = sender instanceof ConsoleCommandSender;
        sender.sendMessage(ChatColor.GOLD + "=== Damodara Help ===");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " backup now " + ChatColor.GRAY + "- Trigger an immediate asynchronous backup.");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " backups [page] " + ChatColor.GRAY + "- List local backup archives.");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " status " + ChatColor.GRAY + "- View plugin, schedule, last backup, and Drive status.");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " reload " + ChatColor.GRAY + "- Reload configuration safely.");
        if (isConsole) {
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " auth connect " + ChatColor.GRAY + "- Start headless Google Drive device-flow linking.");
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " auth status " + ChatColor.GRAY + "- Show Google Drive connection status.");
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " auth disconnect " + ChatColor.GRAY + "- Revoke Google Drive authorization.");
        }
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " restore " + ChatColor.GRAY + "- Show offline restore instructions.");
        sender.sendMessage(ChatColor.GOLD + "====================");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!hasAnyPermission(sender)) return List.of();

        List<String> completions = new ArrayList<>();
        boolean isConsole = sender instanceof ConsoleCommandSender;
        boolean canBackup = sender.hasPermission("damodara.backup") || sender.hasPermission("damodara.admin");
        boolean canStatus = sender.hasPermission("damodara.status") || sender.hasPermission("damodara.admin");
        boolean canReload = sender.hasPermission("damodara.reload") || sender.hasPermission("damodara.admin");
        boolean canAuth = isConsole && (sender.hasPermission("damodara.auth") || sender.hasPermission("damodara.admin"));

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            if (canBackup && "backup".startsWith(prefix)) completions.add("backup");
            if (canStatus && "backups".startsWith(prefix)) completions.add("backups");
            if (canStatus && "status".startsWith(prefix)) completions.add("status");
            if (canReload && "reload".startsWith(prefix)) completions.add("reload");
            if ("restore".startsWith(prefix)) completions.add("restore");
            if ("help".startsWith(prefix)) completions.add("help");
            if (canAuth && "auth".startsWith(prefix)) {
                completions.add("auth");
            }

        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            String prefix = args[1].toLowerCase();

            if (sub.equals("backup") && canBackup) {
                if ("now".startsWith(prefix)) completions.add("now");
            } else if (sub.equals("auth") && canAuth) {
                for (String c : List.of("connect", "status", "disconnect")) {
                    if (c.startsWith(prefix)) completions.add(c);
                }
            }
        }

        return completions;
    }

    private boolean hasAnyPermission(CommandSender sender) {
        return sender.hasPermission("damodara.admin")
                || sender.hasPermission("damodara.backup")
                || sender.hasPermission("damodara.status")
                || sender.hasPermission("damodara.reload")
                || sender.hasPermission("damodara.auth");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private record BackupEntry(Path path, long sizeBytes, Instant createdAt) {}
}
