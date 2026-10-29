package com.damodar.backup;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

public final class IntegrityVerifier {

    private final Logger logger;

    public IntegrityVerifier(Logger logger) {
        this.logger = logger;
    }

    public String computeSha256(Path archivePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];

            try (BufferedInputStream bis = new BufferedInputStream(Files.newInputStream(archivePath))) {
                int bytesRead;
                while ((bytesRead = bis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in current JVM runtime", e);
        }
    }

    public boolean verifyZipStructure(Path archivePath) {
        if (!Files.exists(archivePath) || !Files.isRegularFile(archivePath)) {
            if (logger != null) {
                logger.warning("Integrity check failed: File does not exist or is not a regular file: " + archivePath);
            }
            return false;
        }

        try (ZipFile zipFile = new ZipFile(archivePath.toFile())) {
            int entryCount = zipFile.size();
            if (logger != null) {
                logger.fine("Verified zip structure for " + archivePath.getFileName() + " (" + entryCount + " entries).");
            }
            return true;
        } catch (IOException e) {
            if (logger != null) {
                logger.severe("Integrity check FAILED for " + archivePath.getFileName() + ": Corrupt zip headers (" + e.getMessage() + ")");
            }
            return false;
        }
    }
}
