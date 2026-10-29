package com.damodar.backup;

import com.damodar.config.DamodarConfig;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class BackupService {

    private final Logger logger;
    private volatile DamodarConfig config;
    private final BackupSource liveBackupSource;
    private final SnapshotProvider snapshotProvider;
    private final ArchiveService archiveService;
    private final IntegrityVerifier integrityVerifier;
    private final RetentionService retentionService;
    private final CloudUploader cloudUploader;
    private final BackupIndex backupIndex;
    private final String serverInstanceId;
    private final BackupStatus status;
    private final BackupHistoryLogger historyLogger;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final ExecutorService backupWorkerExecutor;

    public BackupService(
            Logger logger,
            DamodarConfig config,
            SnapshotProvider snapshotProvider,
            CloudUploader cloudUploader,
            BackupIndex backupIndex,
            String serverInstanceId
    ) {
        this.logger = logger;
        this.config = config;
        this.liveBackupSource = new LiveCopyBackupSource(logger);
        this.snapshotProvider = snapshotProvider;
        this.archiveService = new ArchiveService(logger);
        this.integrityVerifier = new IntegrityVerifier(logger);
        this.retentionService = new RetentionService(logger);
        this.cloudUploader = cloudUploader;
        this.backupIndex = backupIndex;
        this.serverInstanceId = (serverInstanceId != null && !serverInstanceId.isBlank()) ? serverInstanceId : "server-default";
        this.status = new BackupStatus();
        this.historyLogger = new BackupHistoryLogger(logger);

        this.backupWorkerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "damodara-backup-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public BackupService(
            Logger logger,
            DamodarConfig config,
            SnapshotProvider snapshotProvider,
            CloudUploader cloudUploader
    ) {
        this(logger, config, snapshotProvider, cloudUploader, null, "server-default");
    }

    public BackupStatus getStatus() {
        return status;
    }

    public boolean isBackupRunning() {
        return isRunning.get();
    }

    public RetentionService getRetentionService() {
        return retentionService;
    }

    public BackupIndex getBackupIndex() {
        return backupIndex;
    }

    public String getServerInstanceId() {
        return serverInstanceId;
    }

    public BackupHistoryLogger getHistoryLogger() {
        return historyLogger;
    }

    public synchronized void updateConfig(DamodarConfig newConfig) {
        this.config = newConfig;
        if (this.cloudUploader instanceof GoogleDriveUploader driveUploader) {
            driveUploader.updateConfig(
                    newConfig.isGoogleDriveEnabled(),
                    newConfig.getGoogleDriveFolderId(),
                    newConfig.getGoogleDriveChunkSizeMb(),
                    newConfig.getGoogleDriveMaxRetries(),
                    newConfig.getGoogleDriveInitialRetryDelaySeconds()
            );
        }
        if (logger != null) {
            logger.info("[Damodara] Backup service configuration updated.");
        }
    }

    public CompletableFuture<Boolean> performBackupAsync(String initiator) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        if (!isRunning.compareAndSet(false, true)) {
            if (logger != null) {
                logger.warning("[" + initiator + "] Backup request rejected: Another backup is already in progress!");
            }
            future.complete(false);
            return future;
        }

        status.markStarted();
        if (logger != null) {
            logger.info("[" + initiator + "] Starting Damodara backup pipeline...");
        }

        CompletableFuture.runAsync(() -> {
            DamodarConfig currentConfig = this.config;
            Optional<Path> snapshotMount = Optional.empty();
            Instant backupStartTime = Instant.now();
            String finalFileName = null;
            Path finalizedArchive = null;
            long archiveSize = 0L;
            String checksum = null;
            String backupId = UUID.randomUUID().toString();

            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(currentConfig.getTimestampPattern());
                String timestamp = ZonedDateTime.now(currentConfig.getTimeZone()).format(formatter);

                finalFileName = currentConfig.getFilenamePattern()
                        .replace("{server}", currentConfig.getServerIdentifier())
                        .replace("{timestamp}", timestamp)
                        .replace("{extension}", currentConfig.getArchiveFormat().toLowerCase());

                Path sourceDir = currentConfig.getSourceDirectory();
                Path destinationDir = currentConfig.getDestinationDirectory();

                Path effectiveSourceDir = sourceDir;

                if (snapshotProvider.isAvailable()) {
                    if (logger != null) {
                        logger.info("[Damodara] Using snapshot provider: " + snapshotProvider.getProviderName());
                    }
                    try {
                        snapshotMount = snapshotProvider.createSnapshot(sourceDir);
                        if (snapshotMount.isPresent()) {
                            effectiveSourceDir = snapshotMount.get();
                        }
                    } catch (IOException e) {
                        if (logger != null) {
                            logger.warning("[Damodara] Snapshot creation failed (" + e.getMessage() + "). Falling back to live copy.");
                        }
                    }
                } else {
                    if (logger != null) {
                        logger.info("[Damodara] Snapshot provider not available. Using best-effort non-blocking live copy.");
                    }
                }

                try {
                    List<Path> filesToArchive = liveBackupSource.collectFilesToBackup(
                            effectiveSourceDir,
                            destinationDir,
                            currentConfig.getExcludes(),
                            currentConfig.getIncludes(),
                            currentConfig.isFollowSymlinks()
                    );

                    if (filesToArchive.isEmpty()) {
                        throw new IOException("No files were matched for backup in source directory: " + effectiveSourceDir);
                    }

                    finalizedArchive = archiveService.createZipArchive(
                            effectiveSourceDir,
                            filesToArchive,
                            destinationDir,
                            finalFileName,
                            currentConfig.getCompressionLevel()
                    );

                    if (currentConfig.isVerifyChecksum()) {
                        boolean validZip = integrityVerifier.verifyZipStructure(finalizedArchive);
                        if (!validZip) {
                            throw new IOException("Created archive failed structural ZIP integrity verification!");
                        }
                        checksum = integrityVerifier.computeSha256(finalizedArchive);
                        if (logger != null) {
                            logger.info("[Damodara] Archive SHA-256: " + checksum);
                        }
                    }

                    archiveSize = Files.size(finalizedArchive);
                    status.markSuccess(finalizedArchive, archiveSize, checksum);
                    if (logger != null) {
                        logger.info("[Damodara] Backup successfully completed: " + finalFileName + " (" + (archiveSize / (1024 * 1024)) + " MB)");
                    }

                    final String finalChecksum = checksum;
                    final Path archivePath = finalizedArchive;
                    final long finalSize = archiveSize;
                    final String archiveName = finalFileName;

                    if (currentConfig.isGoogleDriveEnabled()) {
                        if (!cloudUploader.isConfigured()) {
                            if (logger != null) {
                                logger.warning("[Google Drive] Cloud upload skipped: Google Drive is enabled, but no Google account is connected. "
                                        + "Use '/damodara auth connect' in the server console to link an account. "
                                        + "The local backup archive remains safely available at: " + finalizedArchive.getFileName());
                            }
                            status.markDriveUploadResult(false);

                            List<String> pruned = retentionService.applyLocalRetention(
                                    destinationDir,
                                    currentConfig.getServerIdentifier(),
                                    currentConfig.isLocalRetentionEnabled(),
                                    currentConfig.getLocalRetentionMaxCount(),
                                    currentConfig.getLocalRetentionMaxAgeDays()
                            );

                            recordHistoryLog(destinationDir, backupStartTime, currentConfig, archiveName, finalSize, finalChecksum,
                                    "SUCCESS", "NOT_CONNECTED", formatRetentionActions(pruned, List.of()));

                            future.complete(true);
                            return;
                        }

                        cloudUploader.uploadArchiveAsync(finalizedArchive).thenAccept(uploadResult -> {
                            status.markDriveUploadResult(uploadResult.success());
                            if (uploadResult.success()) {
                                if (logger != null) {
                                    logger.info("[Damodara] Google Drive off-site backup upload complete.");
                                }

                                if (backupIndex != null) {
                                    backupIndex.recordUpload(
                                            backupId,
                                            archivePath.toAbsolutePath().toString(),
                                            uploadResult.fileId(),
                                            archiveName,
                                            Instant.now(),
                                            finalSize,
                                            finalChecksum,
                                            serverInstanceId
                                    );
                                }

                                List<String> pruned = retentionService.applyLocalRetention(
                                        destinationDir,
                                        currentConfig.getServerIdentifier(),
                                        currentConfig.isLocalRetentionEnabled(),
                                        currentConfig.getLocalRetentionMaxCount(),
                                        currentConfig.getLocalRetentionMaxAgeDays()
                                );

                                List<String> trashed = List.of();
                                if (backupIndex != null) {
                                    trashed = retentionService.applyDriveRetention(
                                            backupIndex,
                                            serverInstanceId,
                                            cloudUploader,
                                            currentConfig.isDriveRetentionEnabled(),
                                            currentConfig.getDriveRetentionMaxCount(),
                                            currentConfig.getDriveRetentionMaxAgeDays()
                                    );
                                }

                                recordHistoryLog(destinationDir, backupStartTime, currentConfig, archiveName, finalSize, finalChecksum,
                                        "SUCCESS", "SUCCESS (File ID: " + uploadResult.fileId() + ")",
                                        formatRetentionActions(pruned, trashed));
                            } else {
                                if (logger != null) {
                                    logger.warning("[Damodara] Google Drive upload did not succeed (" + uploadResult.errorMessage()
                                            + "). Local backup archive and retention pruning held to preserve all local copies.");
                                }

                                recordHistoryLog(destinationDir, backupStartTime, currentConfig, archiveName, finalSize, finalChecksum,
                                        "SUCCESS", "FAILED: " + uploadResult.errorMessage(),
                                        "None (held due to cloud upload failure)");
                            }
                        });
                    } else {
                        List<String> pruned = retentionService.applyLocalRetention(
                                destinationDir,
                                currentConfig.getServerIdentifier(),
                                currentConfig.isLocalRetentionEnabled(),
                                currentConfig.getLocalRetentionMaxCount(),
                                currentConfig.getLocalRetentionMaxAgeDays()
                        );

                        recordHistoryLog(destinationDir, backupStartTime, currentConfig, archiveName, finalSize, finalChecksum,
                                "SUCCESS", "DISABLED", formatRetentionActions(pruned, List.of()));
                    }

                    future.complete(true);

                } finally {
                    if (snapshotMount.isPresent()) {
                        try {
                            snapshotProvider.releaseSnapshot(snapshotMount.get());
                        } catch (IOException e) {
                            if (logger != null) {
                                logger.warning("[Damodara] Failed to release snapshot mount: " + e.getMessage());
                            }
                        }
                    }
                }

            } catch (Exception e) {
                if (logger != null) {
                    logger.severe("[Damodara] Backup operation failed: " + e.getMessage());
                }
                status.markFailure(e.getMessage());
                archiveService.cleanupStagingDirectory(currentConfig.getDestinationDirectory());

                recordHistoryLog(currentConfig.getDestinationDirectory(), backupStartTime, currentConfig,
                        finalFileName, archiveSize, checksum,
                        "FAILED: " + e.getMessage(), "SKIPPED", "None");

                future.complete(false);
            } finally {
                isRunning.set(false);
            }
        }, backupWorkerExecutor);

        return future;
    }

    private void recordHistoryLog(
            Path destinationDir,
            Instant timestamp,
            DamodarConfig cfg,
            String filename,
            long sizeBytes,
            String checksum,
            String backupResult,
            String driveResult,
            String retentionActions
    ) {
        int count = 0;
        long totalSize = 0L;
        if (destinationDir != null && Files.isDirectory(destinationDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(destinationDir)) {
                for (Path p : stream) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".zip") && !name.endsWith(".tmp")) {
                        count++;
                        try {
                            totalSize += Files.size(p);
                        } catch (IOException ignored) {}
                    }
                }
            } catch (IOException ignored) {}
        }

        historyLogger.recordEntry(
                destinationDir,
                timestamp,
                cfg.getTimeZone(),
                filename,
                sizeBytes,
                checksum,
                backupResult,
                driveResult,
                retentionActions,
                count,
                totalSize
        );
    }

    private static String formatRetentionActions(List<String> pruned, List<String> trashed) {
        StringBuilder sb = new StringBuilder();
        if (pruned != null && !pruned.isEmpty()) {
            sb.append("Local: pruned ").append(pruned.size()).append(" (").append(String.join(", ", pruned)).append(")");
        } else {
            sb.append("Local: none");
        }
        sb.append("; ");
        if (trashed != null && !trashed.isEmpty()) {
            sb.append("Drive: trashed ").append(trashed.size()).append(" (").append(String.join(", ", trashed)).append(")");
        } else {
            sb.append("Drive: none");
        }
        return sb.toString();
    }

    public void shutdown() {
        if (isRunning.get()) {
            if (logger != null) {
                logger.warning("[Damodara] Plugin is shutting down while a backup is in progress. Temporary files may remain in staging.");
            }
        }

        backupWorkerExecutor.shutdown();
        try {
            if (!backupWorkerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                backupWorkerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            backupWorkerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (config != null) {
            archiveService.cleanupStagingDirectory(config.getDestinationDirectory());
        }
    }
}
