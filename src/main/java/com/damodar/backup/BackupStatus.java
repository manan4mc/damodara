package com.damodar.backup;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

public final class BackupStatus {

    private volatile boolean running = false;
    private volatile Instant currentBackupStartTime = null;

    private volatile Instant lastBackupStartTime = null;
    private volatile Instant lastBackupEndTime = null;
    private volatile boolean lastBackupSuccess = false;
    private volatile Path lastBackupFile = null;
    private volatile long lastBackupSizeBytes = 0;
    private volatile String lastBackupChecksum = null;
    private volatile String lastErrorMessage = null;

    private volatile boolean lastDriveUploadSuccess = false;
    private volatile Instant lastDriveUploadTime = null;

    public void markStarted() {
        this.running = true;
        this.currentBackupStartTime = Instant.now();
    }

    public void markSuccess(Path finalFile, long sizeBytes, String checksum) {
        this.running = false;
        this.lastBackupEndTime = Instant.now();
        this.lastBackupStartTime = this.currentBackupStartTime;
        this.lastBackupSuccess = true;
        this.lastBackupFile = finalFile;
        this.lastBackupSizeBytes = sizeBytes;
        this.lastBackupChecksum = checksum;
        this.lastErrorMessage = null;
    }

    public void markFailure(String errorMessage) {
        this.running = false;
        this.lastBackupEndTime = Instant.now();
        this.lastBackupStartTime = this.currentBackupStartTime;
        this.lastBackupSuccess = false;
        this.lastErrorMessage = errorMessage;
    }

    public void markDriveUploadResult(boolean success) {
        this.lastDriveUploadSuccess = success;
        this.lastDriveUploadTime = Instant.now();
    }

    public boolean isRunning() { return running; }
    public Instant getCurrentBackupStartTime() { return currentBackupStartTime; }
    public Instant getLastBackupStartTime() { return lastBackupStartTime; }
    public Instant getLastBackupEndTime() { return lastBackupEndTime; }
    public Duration getLastBackupDuration() {
        if (lastBackupStartTime != null && lastBackupEndTime != null) {
            return Duration.between(lastBackupStartTime, lastBackupEndTime);
        }
        return Duration.ZERO;
    }
    public boolean isLastBackupSuccess() { return lastBackupSuccess; }
    public Path getLastBackupFile() { return lastBackupFile; }
    public long getLastBackupSizeBytes() { return lastBackupSizeBytes; }
    public String getLastBackupChecksum() { return lastBackupChecksum; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public boolean isLastDriveUploadSuccess() { return lastDriveUploadSuccess; }
    public Instant getLastDriveUploadTime() { return lastDriveUploadTime; }
}
