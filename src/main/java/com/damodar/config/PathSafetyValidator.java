package com.damodar.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

public final class PathSafetyValidator {

    private final Logger logger;

    public PathSafetyValidator(Logger logger) {
        this.logger = logger;
    }

    public record ValidationResult(boolean valid, Path resolvedSource, Path resolvedDestination, String errorMessage) {
        public static ValidationResult success(Path source, Path destination) {
            return new ValidationResult(true, source, destination, null);
        }

        public static ValidationResult failure(String message) {
            return new ValidationResult(false, null, null, message);
        }
    }

    public ValidationResult validate(String rawSourcePath, String rawDestinationPath) {
        if (rawSourcePath == null || rawSourcePath.isBlank()) {
            return ValidationResult.failure("Source directory configuration cannot be blank.");
        }
        if (rawDestinationPath == null || rawDestinationPath.isBlank()) {
            return ValidationResult.failure("Destination directory configuration cannot be blank.");
        }

        Path source;
        Path destination;

        try {
            source = Path.of(rawSourcePath).toAbsolutePath().normalize();
        } catch (Exception e) {
            return ValidationResult.failure("Source path is invalid or unparseable: " + e.getMessage());
        }

        try {
            destination = Path.of(rawDestinationPath).toAbsolutePath().normalize();
        } catch (Exception e) {
            return ValidationResult.failure("Destination path is invalid or unparseable: " + e.getMessage());
        }

        if (!Files.exists(source)) {
            return ValidationResult.failure("Configured source directory does not exist: " + source);
        }
        if (!Files.isDirectory(source)) {
            return ValidationResult.failure("Configured source path is a file, not a directory: " + source);
        }
        if (!Files.isReadable(source)) {
            return ValidationResult.failure("Configured source directory is not readable: " + source);
        }

        if (source.equals(destination)) {
            return ValidationResult.failure(
                    "Backup destination directory cannot be identical to the source directory! (" + source + ")"
            );
        }

        if (source.startsWith(destination)) {
            return ValidationResult.failure(
                    "Backup destination directory (" + destination + ") is a parent of the source directory (" + source + "). "
                            + "This would lead to disastrous recursion or deletion loops during retention cleanup."
            );
        }

        try {
            if (!Files.exists(destination)) {
                Files.createDirectories(destination);
                if (logger != null) {
                    logger.info("Created backup destination directory: " + destination);
                }
            }
        } catch (IOException e) {
            return ValidationResult.failure("Failed to create destination directory: " + destination + " (" + e.getMessage() + ")");
        }

        if (!Files.isDirectory(destination)) {
            return ValidationResult.failure("Backup destination path exists but is not a directory: " + destination);
        }
        if (!Files.isWritable(destination)) {
            return ValidationResult.failure("Backup destination directory is not writable: " + destination);
        }

        if (destination.startsWith(source)) {
            if (logger != null) {
                logger.info("Destination directory (" + destination + ") resides inside source directory (" + source + "). "
                        + "Damodara will automatically exclude this destination directory from file traversal.");
            }
        }

        return ValidationResult.success(source, destination);
    }
}
