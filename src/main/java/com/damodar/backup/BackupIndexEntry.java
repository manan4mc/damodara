package com.damodar.backup;

import java.time.Instant;
import java.util.UUID;

public record BackupIndexEntry(
        String backupId,
        String localPath,
        String driveFileId,
        String archiveName,
        Instant timestamp,
        long sizeBytes,
        String checksum,
        String serverInstanceId,
        String status
) {
    public BackupIndexEntry {
        if (backupId == null || backupId.isBlank()) {
            backupId = UUID.randomUUID().toString();
        }
        if (localPath == null || localPath.isBlank()) {
            localPath = archiveName != null ? archiveName : "";
        }
        if (driveFileId == null || driveFileId.isBlank()) {
            throw new IllegalArgumentException("driveFileId must not be null or blank");
        }
        if (archiveName == null || archiveName.isBlank()) {
            throw new IllegalArgumentException("archiveName must not be null or blank");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (sizeBytes < 0) {
            sizeBytes = 0;
        }
        if (checksum == null) {
            checksum = "";
        }
        if (serverInstanceId == null || serverInstanceId.isBlank()) {
            serverInstanceId = "unknown";
        }
        if (status == null || status.isBlank()) {
            status = "UPLOADED";
        }
    }

    public BackupIndexEntry(
            String driveFileId,
            String archiveName,
            Instant timestamp,
            String checksum,
            String serverInstanceId,
            String status
    ) {
        this(
                UUID.randomUUID().toString(),
                archiveName,
                driveFileId,
                archiveName,
                timestamp,
                0L,
                checksum,
                serverInstanceId,
                status
        );
    }

    public BackupIndexEntry withStatus(String newStatus) {
        return new BackupIndexEntry(backupId, localPath, driveFileId, archiveName, timestamp, sizeBytes, checksum, serverInstanceId, newStatus);
    }
}
