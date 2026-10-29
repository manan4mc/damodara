package com.damodar.backup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

public final class BackupHistoryLogger {

    public static final String HISTORY_FILENAME = "backup-history.csv";
    private static final String CSV_HEADER = "Timestamp,Filename,SizeBytes,SizeFormatted,Checksum,BackupResult,DriveUploadResult,RetentionActions,LocalBackupCount,LocalBackupTotalSizeBytes\n";

    private final Logger logger;

    public BackupHistoryLogger(Logger logger) {
        this.logger = logger;
    }

    public synchronized void recordEntry(
            Path destinationDir,
            Instant timestamp,
            ZoneId zoneId,
            String filename,
            long sizeBytes,
            String checksum,
            String backupResult,
            String driveResult,
            String retentionActions,
            int localCount,
            long localTotalSize
    ) {
        if (destinationDir == null) return;

        try {
            if (!Files.exists(destinationDir)) {
                Files.createDirectories(destinationDir);
            }

            Path csvFile = destinationDir.resolve(HISTORY_FILENAME);
            boolean newFile = !Files.exists(csvFile);

            String formattedTime = DateTimeFormatter.ISO_INSTANT.format(timestamp != null ? timestamp : Instant.now());
            if (zoneId != null) {
                try {
                    formattedTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                            .withZone(zoneId)
                            .format(timestamp != null ? timestamp : Instant.now());
                } catch (Exception ignored) {}
            }

            String sizeFormatted = formatSize(sizeBytes);
            String row = String.format("%s,%s,%d,%s,%s,%s,%s,%s,%d,%d\n",
                    escapeCsv(formattedTime),
                    escapeCsv(filename != null ? filename : "N/A"),
                    sizeBytes,
                    escapeCsv(sizeFormatted),
                    escapeCsv(checksum != null ? checksum : "N/A"),
                    escapeCsv(sanitize(backupResult)),
                    escapeCsv(sanitize(driveResult)),
                    escapeCsv(sanitize(retentionActions)),
                    localCount,
                    localTotalSize
            );

            if (newFile) {
                Files.writeString(csvFile, CSV_HEADER + row, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            } else {
                Files.writeString(csvFile, row, StandardCharsets.UTF_8,
                        StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            }
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("[Damodara History] Failed to write to " + HISTORY_FILENAME + ": " + e.getMessage());
            }
        }
    }

    public static String sanitize(String text) {
        if (text == null) return "N/A";
        return text.replaceAll("(?i)(ya29\\.[a-zA-Z0-9_.-]+)", "[REDACTED_TOKEN]")
                .replaceAll("(?i)(client_secret=[^&\\s,]+)", "client_secret=[REDACTED]")
                .replaceAll("(?i)(refresh_token=[^&\\s,]+)", "refresh_token=[REDACTED]")
                .replaceAll("(?i)(code=[^&\\s,]+)", "code=[REDACTED]");
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}

