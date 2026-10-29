package com.damodar;

import com.damodar.auth.DeviceFlowAuthService;
import com.damodar.auth.TokenStore;
import com.damodar.backup.GoogleDriveUploader;
import com.damodar.command.DamodarCommand;
import com.damodar.config.DamodarConfig;
import com.damodar.backup.BackupIndex;
import com.damodar.backup.ServerInstanceId;
import com.damodar.scheduler.BackupScheduler;
import com.damodar.scheduler.SchedulerStateStore;
import com.damodar.backup.BackupService;
import com.damodar.backup.SnapshotProvider;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

public final class DamodarPlugin extends JavaPlugin {

    private DamodarConfig damodarConfig;
    private TokenStore tokenStore;
    private DeviceFlowAuthService deviceFlowAuthService;
    private BackupService backupService;
    private BackupScheduler backupScheduler;
    private SchedulerStateStore schedulerStateStore;
    private BackupIndex backupIndex;
    private String serverInstanceId;

    @Override
    public void onEnable() {
        getLogger().info("Initializing Damodara server backup & cloud sync system...");

        migrateLegacyDataFolder();
        cleanOldScheduleToggleStore();

        saveDefaultConfig();

        try {
            this.damodarConfig = DamodarConfig.fromConfiguration(getConfig(), getLogger());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE,
                    "Failed to load or validate Damodara configuration: " + e.getMessage(), e);
            getLogger().severe("Damodara will remain inactive to prevent unsafe backup behavior. "
                    + "Please correct config.yml and restart.");
            return;
        }

        this.serverInstanceId = ServerInstanceId.getOrCreate(
                getDataFolder().toPath(),
                damodarConfig.getServerIdentifier(),
                getLogger()
        );
        this.backupIndex = new BackupIndex(getLogger(), getDataFolder().toPath());
        this.schedulerStateStore = new SchedulerStateStore(getLogger(), getDataFolder().toPath());

        this.tokenStore = new TokenStore(
                getLogger(),
                getDataFolder().toPath(),
                damodarConfig.getGoogleDriveClientId(),
                damodarConfig.getGoogleDriveClientSecret()
        );

        this.deviceFlowAuthService = new DeviceFlowAuthService(getLogger(), tokenStore);

        SnapshotProvider snapshotProvider = new SnapshotProvider.UnsupportedSnapshotProvider();

        GoogleDriveUploader cloudUploader = new GoogleDriveUploader(
                getLogger(),
                tokenStore,
                damodarConfig.isGoogleDriveEnabled(),
                damodarConfig.getGoogleDriveFolderId(),
                damodarConfig.getGoogleDriveChunkSizeMb(),
                damodarConfig.getGoogleDriveMaxRetries(),
                damodarConfig.getGoogleDriveInitialRetryDelaySeconds()
        );

        if (schedulerStateStore.getResolvedFolderId() != null) {
            cloudUploader.setCachedAppFolderId(schedulerStateStore.getResolvedFolderId());
        }

        this.backupService = new BackupService(
                getLogger(),
                damodarConfig,
                snapshotProvider,
                cloudUploader,
                backupIndex,
                serverInstanceId
        );

        DamodarCommand commandHandler = new DamodarCommand(
                this, backupService, tokenStore, deviceFlowAuthService);

        PluginCommand damodaraCmd = getCommand("damodara");
        if (damodaraCmd != null) {
            damodaraCmd.setExecutor(commandHandler);
            damodaraCmd.setTabCompleter(commandHandler);
        } else {
            getLogger().severe("Could not find '/damodara' command. Ensure 'damodara' is declared in plugin.yml.");
        }

        this.backupScheduler = new BackupScheduler(
                this,
                getLogger(),
                damodarConfig,
                backupService,
                schedulerStateStore
        );
        this.backupScheduler.start(damodarConfig.isScheduleEnabled());

        getLogger().info("Damodara successfully enabled. Destination: " + damodarConfig.getDestinationDirectory());
    }

    @Override
    public void onDisable() {
        getLogger().info("Shutting down Damodara...");

        if (deviceFlowAuthService != null) {
            deviceFlowAuthService.shutdown();
            deviceFlowAuthService = null;
        }

        if (backupScheduler != null) {
            backupScheduler.cancel();
            backupScheduler = null;
        }

        if (backupService != null) {
            backupService.shutdown();
            backupService = null;
        }

        getLogger().info("Damodara disabled cleanly.");
    }

    public BackupService getBackupService() { return backupService; }
    public DamodarConfig getDamodarConfig() { return damodarConfig; }
    public TokenStore getTokenStore() { return tokenStore; }
    public DeviceFlowAuthService getDeviceFlowAuthService() { return deviceFlowAuthService; }
    public BackupScheduler getBackupScheduler() { return backupScheduler; }
    public SchedulerStateStore getSchedulerStateStore() { return schedulerStateStore; }
    public BackupIndex getBackupIndex() { return backupIndex; }
    public String getServerInstanceId() { return serverInstanceId; }

    public synchronized void reloadPluginConfig() {
        reloadConfig();
        DamodarConfig newConfig = DamodarConfig.fromConfiguration(getConfig(), getLogger());
        this.damodarConfig = newConfig;

        if (this.tokenStore != null) {
            this.tokenStore.updateClientCredentials(
                    newConfig.getGoogleDriveClientId(),
                    newConfig.getGoogleDriveClientSecret()
            );
        }

        if (this.backupService != null) {
            this.backupService.updateConfig(newConfig);
        }

        if (this.backupScheduler != null) {
            this.backupScheduler.onConfigReload(newConfig, newConfig.isScheduleEnabled());
        }

        getLogger().info("[Damodara] Configuration reloaded successfully.");
    }

    private void cleanOldScheduleToggleStore() {
        try {
            Path oldToggleFile = getDataFolder().toPath().resolve("schedule-enabled.dat");
            Files.deleteIfExists(oldToggleFile);
        } catch (IOException ignored) {}
    }

    private void migrateLegacyDataFolder() {
        Path newFolder = getDataFolder().toPath();
        Path legacyFolder = newFolder.getParent().resolve("Damodar");

        if (!Files.exists(legacyFolder)) {
            return;
        }

        if (Files.exists(newFolder)) {
            getLogger().info("[Damodara Migration] plugins/Damodara/ already exists; skipping migration from plugins/Damodar/.");
            return;
        }

        getLogger().info("[Damodara Migration] Found legacy plugins/Damodar/ folder. Migrating to plugins/Damodara/...");

        try {
            Files.createDirectories(newFolder);
            try (var stream = Files.walk(legacyFolder)) {
                stream.forEach(source -> {
                    Path relative = legacyFolder.relativize(source);
                    Path target = newFolder.resolve(relative);
                    try {
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(target);
                        } else {
                            if (!Files.exists(target)) {
                                Files.copy(source, target);
                                if (source.getFileName().toString().endsWith(".enc")
                                        || source.getFileName().toString().endsWith(".json")) {
                                    getLogger().info("[Damodara Migration] Preserved legacy credential file: " + relative
                                            + " (re-authentication via '/damodara auth connect' required for device flow).");
                                } else {
                                    getLogger().info("[Damodara Migration] Migrated: " + relative);
                                }
                            }
                        }
                    } catch (Exception e) {
                        getLogger().warning("[Damodara Migration] Could not migrate " + relative + ": " + e.getMessage());
                    }
                });
            }

            getLogger().info("[Damodara Migration] Migration complete. "
                    + "You may safely delete plugins/Damodar/ after verifying Damodara is working correctly.");

        } catch (Exception e) {
            getLogger().warning("[Damodara Migration] Migration failed: " + e.getMessage()
                    + ". The plugin will continue with a clean data folder.");
        }
    }
}
