package com.damodar.backup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BackupIndex {

    public static final String FILENAME = "backup-index.json";
    private static final String TMP_FILENAME = "backup-index.json.tmp";

    private final Logger logger;
    private final Path indexPath;
    private final Path tmpPath;

    private final List<BackupIndexEntry> entries = new ArrayList<>();
    private boolean healthy = true;

    public BackupIndex(Logger logger, Path pluginDataFolder) {
        this.logger = logger;
        this.indexPath = pluginDataFolder.resolve(FILENAME);
        this.tmpPath = pluginDataFolder.resolve(TMP_FILENAME);
        load();
    }

    public synchronized boolean isHealthy() {
        return healthy;
    }

    public synchronized void load() {
        entries.clear();
        healthy = true;

        if (!Files.exists(indexPath)) {
            return;
        }

        try {
            String json = Files.readString(indexPath, StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) {
                return;
            }
            parseIndexJson(json);
        } catch (Exception e) {
            healthy = false;
            if (logger != null) {
                logger.severe("[Damodara Index] Failed to parse backup index file at " + indexPath + ": " + e.getMessage()
                        + ". Drive retention pruning will be suspended to protect untracked files.");
            }
        }
    }

    public synchronized BackupIndexEntry recordUpload(
            String backupId,
            String localPath,
            String driveFileId,
            String archiveName,
            Instant timestamp,
            long sizeBytes,
            String checksum,
            String serverInstanceId
    ) {
        if (!healthy) {
            if (logger != null) {
                logger.warning("[Damodara Index] Cannot record upload: index is currently in an unhealthy state.");
            }
        }

        BackupIndexEntry entry = new BackupIndexEntry(
                backupId,
                localPath,
                driveFileId,
                archiveName,
                timestamp != null ? timestamp : Instant.now(),
                sizeBytes,
                checksum != null ? checksum : "",
                serverInstanceId,
                "UPLOADED"
        );

        entries.removeIf(e -> e.driveFileId().equals(driveFileId));
        entries.add(entry);

        save();
        return entry;
    }

    public synchronized BackupIndexEntry recordUpload(
            String driveFileId,
            String archiveName,
            Instant timestamp,
            String checksum,
            String serverInstanceId
    ) {
        return recordUpload(
                UUID.randomUUID().toString(),
                archiveName,
                driveFileId,
                archiveName,
                timestamp,
                0L,
                checksum,
                serverInstanceId
        );
    }

    public synchronized List<BackupIndexEntry> getActiveEntriesForServer(String serverInstanceId) {
        if (!healthy) {
            return List.of();
        }

        return entries.stream()
                .filter(e -> e.serverInstanceId().equals(serverInstanceId))
                .filter(e -> "UPLOADED".equals(e.status()) || "FAILED_TRASH".equals(e.status()))
                .sorted(Comparator.comparing(BackupIndexEntry::timestamp))
                .toList();
    }

    public synchronized void markTrashed(String driveFileId) {
        updateEntryStatus(driveFileId, "TRASHED");
    }

    public synchronized void markFailedTrash(String driveFileId) {
        updateEntryStatus(driveFileId, "FAILED_TRASH");
    }

    private synchronized void updateEntryStatus(String driveFileId, String status) {
        for (int i = 0; i < entries.size(); i++) {
            BackupIndexEntry entry = entries.get(i);
            if (entry.driveFileId().equals(driveFileId)) {
                entries.set(i, entry.withStatus(status));
                save();
                return;
            }
        }
        if (logger != null) {
            logger.warning("[Damodara Index] Attempted to update status of untracked Drive file ID: " + driveFileId);
        }
    }

    public synchronized boolean save() {
        try {
            Path parent = indexPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            String json = serializeIndexJson();
            Files.writeString(tmpPath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            try {
                Files.move(tmpPath, indexPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, indexPath, StandardCopyOption.REPLACE_EXISTING);
            }

            healthy = true;
            return true;
        } catch (IOException e) {
            if (logger != null) {
                logger.severe("[Damodara Index] Failed to save backup index to " + indexPath + ": " + e.getMessage());
            }
            try {
                Files.deleteIfExists(tmpPath);
            } catch (IOException ignored) {}
            return false;
        }
    }

    private String serializeIndexJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"entries\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            BackupIndexEntry e = entries.get(i);
            sb.append("    {\n");
            sb.append("      \"backup_id\": \"").append(escapeJson(e.backupId())).append("\",\n");
            sb.append("      \"local_path\": \"").append(escapeJson(e.localPath())).append("\",\n");
            sb.append("      \"drive_file_id\": \"").append(escapeJson(e.driveFileId())).append("\",\n");
            sb.append("      \"archive_name\": \"").append(escapeJson(e.archiveName())).append("\",\n");
            sb.append("      \"timestamp\": \"").append(e.timestamp().toString()).append("\",\n");
            sb.append("      \"size_bytes\": ").append(e.sizeBytes()).append(",\n");
            sb.append("      \"checksum\": \"").append(escapeJson(e.checksum())).append("\",\n");
            sb.append("      \"server_instance_id\": \"").append(escapeJson(e.serverInstanceId())).append("\",\n");
            sb.append("      \"status\": \"").append(escapeJson(e.status())).append("\"\n");
            sb.append("    }");
            if (i < entries.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private void parseIndexJson(String json) {
        int arrayStart = json.indexOf('[');
        int arrayEnd = json.lastIndexOf(']');
        if (arrayStart == -1 || arrayEnd == -1 || arrayEnd <= arrayStart) {
            throw new IllegalStateException("Index file content is present but does not contain a valid JSON entries array.");
        }

        String arrayContent = json.substring(arrayStart + 1, arrayEnd);
        Pattern objPattern = Pattern.compile("\\{([^}]+)\\}");
        Matcher objMatcher = objPattern.matcher(arrayContent);

        while (objMatcher.find()) {
            String block = objMatcher.group(1);
            String backupId = extractField(block, "backup_id");
            String localPath = extractField(block, "local_path");
            String fileId = extractField(block, "drive_file_id");
            String archive = extractField(block, "archive_name");
            String timeStr = extractField(block, "timestamp");
            String sizeStr = extractField(block, "size_bytes");
            String checksum = extractField(block, "checksum");
            String serverId = extractField(block, "server_instance_id");
            String status = extractField(block, "status");

            if (fileId != null && archive != null) {
                Instant time = Instant.now();
                if (timeStr != null) {
                    try {
                        time = Instant.parse(timeStr);
                    } catch (Exception ignored) {}
                }

                long sizeBytes = 0L;
                if (sizeStr != null) {
                    try {
                        sizeBytes = Long.parseLong(sizeStr);
                    } catch (Exception ignored) {}
                }

                entries.add(new BackupIndexEntry(
                        backupId != null ? backupId : UUID.randomUUID().toString(),
                        localPath != null ? localPath : archive,
                        fileId,
                        archive,
                        time,
                        sizeBytes,
                        checksum != null ? checksum : "",
                        serverId != null ? serverId : "unknown",
                        status != null ? status : "UPLOADED"
                ));
            }
        }
    }

    private static String extractField(String block, String fieldName) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"?([^\",\r\n}]+)\"?");
        Matcher m = p.matcher(block);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public synchronized List<BackupIndexEntry> getAllEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }
}
