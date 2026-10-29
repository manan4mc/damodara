package com.damodar.backup;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.logging.Logger;

public final class LiveCopyBackupSource implements BackupSource {

    private final Logger logger;

    public LiveCopyBackupSource(Logger logger) {
        this.logger = logger;
    }

    @Override
    public String getSourceName() {
        return "Live-Copy (Best-Effort Non-Blocking)";
    }

    @Override
    public boolean isSnapshotConsistent() {
        return false;
    }

    @Override
    public List<Path> collectFilesToBackup(
            Path sourceDir,
            Path destinationDir,
            List<String> excludes,
            List<String> includes,
            boolean followSymlinks
    ) throws IOException {
        Path canonicalSource = sourceDir.toAbsolutePath().normalize();
        Path canonicalDest = destinationDir.toAbsolutePath().normalize();

        FileSystem fs = FileSystems.getDefault();
        List<PathMatcher> excludeMatchers = new ArrayList<>();
        for (String pattern : excludes) {
            try {
                excludeMatchers.add(fs.getPathMatcher("glob:" + pattern));
            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("Invalid exclude glob pattern '" + pattern + "': " + e.getMessage());
                }
            }
        }

        List<PathMatcher> includeMatchers = new ArrayList<>();
        for (String pattern : includes) {
            try {
                includeMatchers.add(fs.getPathMatcher("glob:" + pattern));
            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("Invalid include glob pattern '" + pattern + "': " + e.getMessage());
                }
            }
        }

        List<Path> collectedRelativePaths = new ArrayList<>();
        Set<FileVisitOption> visitOptions = followSymlinks
                ? EnumSet.of(FileVisitOption.FOLLOW_LINKS)
                : EnumSet.noneOf(FileVisitOption.class);

        Files.walkFileTree(canonicalSource, visitOptions, Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path normalizedDir = dir.toAbsolutePath().normalize();

                if (normalizedDir.startsWith(canonicalDest)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                if (!followSymlinks && Files.isSymbolicLink(dir)) {
                    if (logger != null) {
                        logger.fine("Skipping symbolic link directory: " + dir);
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }

                Path relativeDir = canonicalSource.relativize(normalizedDir);
                if (!relativeDir.toString().isEmpty()) {
                    String relativeDirStr = relativeDir.toString().replace('\\', '/').toLowerCase();

                    if (relativeDirStr.equals("plugins/damodara/credentials")
                            || relativeDirStr.startsWith("plugins/damodara/credentials/")
                            || relativeDirStr.equals("plugins/damodar/credentials")
                            || relativeDirStr.startsWith("plugins/damodar/credentials/")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    for (PathMatcher matcher : excludeMatchers) {
                        if (matcher.matches(relativeDir) || matcher.matches(Path.of(relativeDirStr + "/"))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!followSymlinks && attrs.isSymbolicLink()) {
                    return FileVisitResult.CONTINUE;
                }

                Path relativePath = canonicalSource.relativize(file.toAbsolutePath().normalize());
                String relativeStr = relativePath.toString().replace('\\', '/').toLowerCase();

                if (relativeStr.startsWith("plugins/damodara/credentials/")
                        || relativeStr.startsWith("plugins/damodar/credentials/")
                        || (relativeStr.endsWith(".tmp") && relativeStr.contains("credentials"))
                        || relativeStr.endsWith("credentials.json")
                        || relativeStr.endsWith("token.enc")
                        || relativeStr.endsWith("session.lock")
                        || relativeStr.endsWith("backup-history.csv")) {
                    return FileVisitResult.CONTINUE;
                }

                for (PathMatcher matcher : excludeMatchers) {
                    if (matcher.matches(relativePath) || matcher.matches(Path.of(relativeStr))) {
                        return FileVisitResult.CONTINUE;
                    }
                }

                if (!includeMatchers.isEmpty()) {
                    boolean matched = false;
                    for (PathMatcher matcher : includeMatchers) {
                        if (matcher.matches(relativePath) || matcher.matches(Path.of(relativeStr))) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        return FileVisitResult.CONTINUE;
                    }
                }

                collectedRelativePaths.add(relativePath);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                if (logger != null) {
                    logger.warning("Could not access file during live backup scan: " + file + " (" + exc.getMessage() + ")");
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return collectedRelativePaths;
    }
}
