package com.damodar.backup;


import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

public final class RetentionService {

    private final Logger logger;

    public RetentionService(Logger logger) {
        this.logger = logger;
    }

    public record BackupRecord(Path path, FileTime timestamp) {}

    public List<String> applyRetention(Path destinationDirectory, boolean enabled, int maxCount, int maxAgeDays) {
        return applyLocalRetention(destinationDirectory, enabled, maxCount, maxAgeDays);
    }

    public List<String> applyLocalRetention(Path destinationDirectory, boolean enabled, int maxCount, int maxAgeDays) {
        return applyLocalRetention(destinationDirectory, "server", enabled, maxCount, maxAgeDays);
    }

    public List<String> applyLocalRetention(
            Path destinationDirectory,
            String serverIdentifier,
            boolean enabled,
            int maxCount,
            int maxAgeDays
    ) {
        List<String> prunedNames = new ArrayList<>();
        if (!enabled) {
            return prunedNames;
        }

        if (destinationDirectory == null || !Files.exists(destinationDirectory) || !Files.isDirectory(destinationDirectory)) {
            return prunedNames;
        }

        List<BackupRecord> backups = listCompletedBackups(destinationDirectory, serverIdentifier);

        if (backups.isEmpty()) {
            return prunedNames;
        }

        backups.sort(Comparator.comparing(BackupRecord::timestamp));

        if (backups.size() <= 1) {
            return prunedNames;
        }

        Instant cutoffInstant = maxAgeDays > 0
                ? Instant.now().minus(maxAgeDays, ChronoUnit.DAYS)
                : null;

        List<BackupRecord> toDelete = new ArrayList<>();
        int currentCount = backups.size();

        for (BackupRecord record : backups) {
            if ((currentCount - toDelete.size()) <= 1) {
                break;
            }

            boolean shouldPruneByCount = (maxCount > 0) && ((currentCount - toDelete.size()) > maxCount);
            boolean shouldPruneByAge = (cutoffInstant != null) && record.timestamp().toInstant().isBefore(cutoffInstant);

            if (shouldPruneByCount || shouldPruneByAge) {
                toDelete.add(record);
            }
        }

        for (BackupRecord doomed : toDelete) {
            try {
                Files.deleteIfExists(doomed.path());
                prunedNames.add(doomed.path().getFileName().toString());
                if (logger != null) {
                    logger.info("[Damodara Retention] Retention pruned older local backup: " + doomed.path().getFileName());
                }
            } catch (IOException e) {
                if (logger != null) {
                    logger.warning("[Damodara Retention] Failed to delete expired backup: " + doomed.path() + " (" + e.getMessage() + ")");
                }
            }
        }

        return prunedNames;
    }

    public List<String> applyDriveRetention(
            BackupIndex backupIndex,
            String serverInstanceId,
            CloudUploader cloudUploader,
            boolean enabled,
            int maxCount,
            int maxAgeDays
    ) {
        List<String> trashedList = new ArrayList<>();
        if (!enabled) {
            return trashedList;
        }

        if (backupIndex == null || !backupIndex.isHealthy()) {
            if (logger != null) {
                logger.warning("[Damodara Retention] Backup index is missing or damaged. "
                        + "Google Drive retention suspended to protect unknown and untracked files.");
            }
            return trashedList;
        }

        if (cloudUploader == null || !cloudUploader.isConfigured()) {
            if (logger != null) {
                logger.warning("[Damodara Retention] Cloud uploader is not configured. Skipping Drive retention.");
            }
            return trashedList;
        }

        List<BackupIndexEntry> active = backupIndex.getActiveEntriesForServer(serverInstanceId);

        if (active.isEmpty()) {
            return trashedList;
        }

        if (active.size() <= 1) {
            return trashedList;
        }

        Instant cutoffInstant = maxAgeDays > 0
                ? Instant.now().minus(maxAgeDays, ChronoUnit.DAYS)
                : null;

        List<BackupIndexEntry> toTrash = new ArrayList<>();
        int currentCount = active.size();

        for (BackupIndexEntry entry : active) {
            if ((currentCount - toTrash.size()) <= 1) {
                break;
            }

            boolean shouldPruneByCount = (maxCount > 0) && ((currentCount - toTrash.size()) > maxCount);
            boolean shouldPruneByAge = (cutoffInstant != null) && entry.timestamp().isBefore(cutoffInstant);

            if (shouldPruneByCount || shouldPruneByAge) {
                toTrash.add(entry);
            }
        }

        for (BackupIndexEntry doomed : toTrash) {
            boolean trashed = cloudUploader.trashFile(doomed.driveFileId());
            if (trashed) {
                backupIndex.markTrashed(doomed.driveFileId());
                trashedList.add(doomed.archiveName());
                if (logger != null) {
                    logger.info("[Damodara Retention] Moved expired Google Drive backup to Trash: "
                            + doomed.archiveName() + " (File ID: " + doomed.driveFileId() + ")");
                }
            } else {
                backupIndex.markFailedTrash(doomed.driveFileId());
                if (logger != null) {
                    logger.warning("[Damodara Retention] Failed to move Google Drive backup to Trash: "
                            + doomed.archiveName() + " (File ID: " + doomed.driveFileId() + "). Retained in index for retry.");
                }
            }
        }

        return trashedList;
    }

    public List<BackupRecord> listCompletedBackups(Path destinationDirectory) {
        return listCompletedBackups(destinationDirectory, "server");
    }

    public List<BackupRecord> listCompletedBackups(Path destinationDirectory, String serverIdentifier) {
        List<BackupRecord> records = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(destinationDirectory)) {
            for (Path entry : stream) {
                if (isDamodaraBackupArchive(entry, serverIdentifier)) {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                        FileTime fileTime = attrs.creationTime().toMillis() > 0
                                ? attrs.creationTime()
                                : attrs.lastModifiedTime();
                        records.add(new BackupRecord(entry, fileTime));
                    } catch (IOException e) {
                        if (logger != null) {
                            logger.warning("[Damodara Retention] Could not read attributes for backup file: "
                                    + entry + " (" + e.getMessage() + ")");
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("[Damodara Retention] Error reading backup destination directory: " + e.getMessage());
            }
        }

        return records;
    }

    public boolean isDamodaraBackupArchive(Path path) {
        return isDamodaraBackupArchive(path, "server");
    }

    public boolean isDamodaraBackupArchive(Path path, String serverIdentifier) {
        if (Files.isDirectory(path)) {
            return false;
        }

        String fileName = path.getFileName().toString();
        if (fileName.startsWith(".") || fileName.endsWith(".tmp") || fileName.endsWith(".csv") || fileName.equalsIgnoreCase("backup-history.csv")) {
            return false;
        }

        if (!fileName.toLowerCase().endsWith(".zip")) {
            return false;
        }

        if (fileName.startsWith("Damodara-") || fileName.startsWith("Damodar-")) {
            return true;
        }

        if (serverIdentifier != null && !serverIdentifier.isBlank() && fileName.startsWith(serverIdentifier + "-")) {
            return true;
        }

        return false;
    }
}
