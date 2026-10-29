package com.damodar.backup;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ArchiveService {

    private static final String STAGING_DIR_NAME = ".damodar_staging";
    private final Logger logger;

    public ArchiveService(Logger logger) {
        this.logger = logger;
    }

    public Path createZipArchive(
            Path sourceDirectory,
            List<Path> filesToArchive,
            Path destinationDirectory,
            String finalFileName,
            int compressionLevel
    ) throws IOException {
        Path stagingDirectory = destinationDirectory.resolve(STAGING_DIR_NAME);
        if (!Files.exists(stagingDirectory)) {
            Files.createDirectories(stagingDirectory);
        }

        Path tempFile = stagingDirectory.resolve(finalFileName + ".tmp");
        Path finalFile = destinationDirectory.resolve(finalFileName);

        if (logger != null) {
            logger.info("Starting archive creation: " + finalFileName + " (" + filesToArchive.size() + " files to compress)");
        }

        try {
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tempFile)))) {
                zos.setLevel(compressionLevel);

                byte[] buffer = new byte[65536];

                for (Path relativePath : filesToArchive) {
                    Path fullPath = sourceDirectory.resolve(relativePath);

                    if (!Files.exists(fullPath) || Files.isDirectory(fullPath)) {
                        continue;
                    }

                    String entryName = relativePath.toString().replace('\\', '/');
                    ZipEntry entry = new ZipEntry(entryName);

                    try {
                        entry.setTime(Files.getLastModifiedTime(fullPath).toMillis());
                    } catch (Exception ignored) {
                    }

                    try (BufferedInputStream bis = new BufferedInputStream(Files.newInputStream(fullPath))) {
                        zos.putNextEntry(entry);
                        int bytesRead;
                        while ((bytesRead = bis.read(buffer)) != -1) {
                            zos.write(buffer, 0, bytesRead);
                        }
                        zos.closeEntry();
                    } catch (IOException e) {
                        if (logger != null) {
                            logger.warning("Could not read file during zip archiving: " + relativePath + " (" + e.getMessage() + ")");
                        }
                    }
                }
                zos.finish();
                zos.flush();
            }

            try {
                Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
            }

            if (logger != null) {
                logger.info("Successfully finalized archive: " + finalFile.getFileName() + " (" + Files.size(finalFile) + " bytes)");
            }
            return finalFile;

        } catch (Exception e) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException cleanupEx) {
                if (logger != null) {
                    logger.warning("Failed to clean up temporary archive file: " + tempFile + " (" + cleanupEx.getMessage() + ")");
                }
            }
            throw new IOException("Failed during archive compression: " + e.getMessage(), e);
        }
    }

    public void cleanupStagingDirectory(Path destinationDirectory) {
        Path stagingDirectory = destinationDirectory.resolve(STAGING_DIR_NAME);
        if (Files.exists(stagingDirectory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDirectory)) {
                for (Path entry : stream) {
                    try {
                        Files.deleteIfExists(entry);
                    } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {}
        }
    }
}
