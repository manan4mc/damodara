package com.damodar.scheduler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SchedulerStateStore {

    public static final String FILENAME = "scheduler-state.json";
    private static final String TMP_FILENAME = "scheduler-state.json.tmp";

    private final Logger logger;
    private final Path stateFile;
    private final Path tmpFile;

    private ZonedDateTime lastHandledSlot = null;
    private LocalDate scheduleAnchorDate = null;
    private String resolvedFolderId = null;

    public SchedulerStateStore(Logger logger, Path pluginDataFolder) {
        this.logger = logger;
        this.stateFile = pluginDataFolder.resolve(FILENAME);
        this.tmpFile = pluginDataFolder.resolve(TMP_FILENAME);
        load();
    }

    public synchronized ZonedDateTime getLastHandledSlot(ZoneId targetZone) {
        if (lastHandledSlot == null) {
            return null;
        }
        return lastHandledSlot.withZoneSameInstant(targetZone);
    }

    public synchronized void recordSlotHandled(ZonedDateTime slot) {
        if (slot == null) return;
        this.lastHandledSlot = slot;
        save();
    }

    public synchronized LocalDate getScheduleAnchorDate() {
        return scheduleAnchorDate;
    }

    public synchronized void setScheduleAnchorDate(LocalDate anchorDate) {
        if (anchorDate == null) return;
        this.scheduleAnchorDate = anchorDate;
        save();
    }

    public synchronized String getResolvedFolderId() {
        return resolvedFolderId;
    }

    public synchronized void setResolvedFolderId(String folderId) {
        this.resolvedFolderId = folderId;
        save();
    }

    public synchronized void load() {
        if (!Files.exists(stateFile)) {
            return;
        }

        try {
            String json = Files.readString(stateFile, StandardCharsets.UTF_8).trim();
            String slotStr = extractField(json, "last_handled_slot");
            if (slotStr != null && !slotStr.isBlank()) {
                try {
                    this.lastHandledSlot = ZonedDateTime.parse(slotStr);
                } catch (Exception e) {
                    try {
                        this.lastHandledSlot = Instant.parse(slotStr).atZone(ZoneId.systemDefault());
                    } catch (Exception ignored) {}
                }
            }

            String anchorStr = extractField(json, "schedule_anchor_date");
            if (anchorStr != null && !anchorStr.isBlank()) {
                try {
                    this.scheduleAnchorDate = LocalDate.parse(anchorStr);
                } catch (Exception ignored) {}
            }

            String folderStr = extractField(json, "resolved_folder_id");
            if (folderStr != null && !folderStr.isBlank()) {
                this.resolvedFolderId = folderStr;
            }
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("[Damodara] Could not read " + FILENAME + ": " + e.getMessage());
            }
        }
    }

    public synchronized boolean save() {
        try {
            Path parent = stateFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            if (lastHandledSlot != null) {
                sb.append("  \"last_handled_slot\": \"").append(lastHandledSlot.toString()).append("\",\n");
            }
            if (scheduleAnchorDate != null) {
                sb.append("  \"schedule_anchor_date\": \"").append(scheduleAnchorDate.toString()).append("\",\n");
            }
            if (resolvedFolderId != null) {
                sb.append("  \"resolved_folder_id\": \"").append(resolvedFolderId).append("\",\n");
            }
            sb.append("  \"updated_at\": \"").append(Instant.now().toString()).append("\"\n");
            sb.append("}\n");

            Files.writeString(tmpFile, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            try {
                Files.move(tmpFile, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }

            return true;
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("[Damodara] Could not save " + FILENAME + ": " + e.getMessage());
            }
            try {
                Files.deleteIfExists(tmpFile);
            } catch (IOException ignored) {}
            return false;
        }
    }

    private static String extractField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
}
