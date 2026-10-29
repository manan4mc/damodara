package com.damodar.backup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public interface SnapshotProvider {

    String getProviderName();

    boolean isAvailable();

    Optional<Path> createSnapshot(Path sourceDirectory) throws IOException;

    void releaseSnapshot(Path snapshotPath) throws IOException;

    final class UnsupportedSnapshotProvider implements SnapshotProvider {
        @Override
        public String getProviderName() {
            return "Unsupported / None (Host snapshot capability not configured)";
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Optional<Path> createSnapshot(Path sourceDirectory) {
            return Optional.empty();
        }

        @Override
        public void releaseSnapshot(Path snapshotPath) {
        }
    }
}
