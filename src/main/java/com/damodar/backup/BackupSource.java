package com.damodar.backup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface BackupSource {

    String getSourceName();

    boolean isSnapshotConsistent();

    List<Path> collectFilesToBackup(
            Path sourceDir,
            Path destinationDir,
            List<String> excludes,
            List<String> includes,
            boolean followSymlinks
    ) throws IOException;
}
