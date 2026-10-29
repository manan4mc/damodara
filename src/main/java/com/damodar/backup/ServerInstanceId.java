package com.damodar.backup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

public final class ServerInstanceId {

    public static final String FILENAME = "server-instance.id";

    private ServerInstanceId() {}

    public static String getOrCreate(Path pluginDataFolder, String serverIdentifier, Logger logger) {
        Path file = pluginDataFolder.resolve(FILENAME);
        if (Files.exists(file)) {
            try {
                String id = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) {
                    return id;
                }
            } catch (IOException e) {
                if (logger != null) {
                    logger.warning("[Damodara] Could not read " + FILENAME + ": " + e.getMessage());
                }
            }
        }

        String prefix = (serverIdentifier != null && !serverIdentifier.isBlank())
                ? serverIdentifier.replaceAll("[^a-zA-Z0-9_-]", "_") + "-"
                : "server-";
        String newId = prefix + UUID.randomUUID();

        try {
            if (!Files.exists(pluginDataFolder)) {
                Files.createDirectories(pluginDataFolder);
            }
            Files.writeString(file, newId, StandardCharsets.UTF_8);
            if (logger != null) {
                logger.info("[Damodara] Assigned durable server instance ID: " + newId);
            }
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("[Damodara] Could not persist " + FILENAME + ": " + e.getMessage());
            }
        }

        return newId;
    }
}
