package com.damodar.backup;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface CloudUploader {

    String getName();

    boolean isConfigured();

    CompletableFuture<Boolean> uploadAsync(Path backupFile);

    default CompletableFuture<UploadResult> uploadArchiveAsync(Path backupFile) {
        return uploadAsync(backupFile).thenApply(success ->
                success ? UploadResult.success(null) : UploadResult.failure("Upload failed")
        );
    }

    default boolean trashFile(String remoteFileId) {
        return false;
    }

    record UploadResult(boolean success, String fileId, String errorMessage) {
        public static UploadResult success(String fileId) {
            return new UploadResult(true, fileId, null);
        }

        public static UploadResult failure(String errorMessage) {
            return new UploadResult(false, null, errorMessage);
        }
    }
}
