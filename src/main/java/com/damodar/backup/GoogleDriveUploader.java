package com.damodar.backup;

import com.damodar.auth.TokenStore;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GoogleDriveUploader implements CloudUploader {

    private static final String RESUMABLE_INIT_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable";
    private static final String FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files/";
    private static final String FOLDERS_QUERY_URL = "https://www.googleapis.com/drive/v3/files?q=mimeType%3D%27application%2Fvnd.google-apps.folder%27+and+name%3D%27backup%27+and+trashed%3Dfalse&fields=files(id,name)&spaces=drive";
    private static final String CREATE_FILE_URL = "https://www.googleapis.com/drive/v3/files";

    private final Logger logger;
    private final TokenStore tokenStore;
    private volatile boolean enabled;
    private volatile String folderId;
    private volatile String cachedAppFolderId = null;
    private volatile int chunkSizeMb;
    private volatile int maxRetries;
    private volatile int initialRetryDelaySeconds;

    private final HttpClient httpClient;

    public GoogleDriveUploader(
            Logger logger,
            TokenStore tokenStore,
            boolean enabled,
            String folderId,
            int chunkSizeMb,
            int maxRetries,
            int initialRetryDelaySeconds
    ) {
        this.logger = logger;
        this.tokenStore = tokenStore;
        this.enabled = enabled;
        this.folderId = (folderId != null) ? folderId.trim() : "";
        this.chunkSizeMb = Math.max(1, chunkSizeMb);
        this.maxRetries = Math.max(0, maxRetries);
        this.initialRetryDelaySeconds = Math.max(1, initialRetryDelaySeconds);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public synchronized void updateConfig(
            boolean enabled,
            String folderId,
            int chunkSizeMb,
            int maxRetries,
            int initialRetryDelaySeconds
    ) {
        this.enabled = enabled;
        this.folderId = (folderId != null) ? folderId.trim() : "";
        this.chunkSizeMb = Math.max(1, chunkSizeMb);
        this.maxRetries = Math.max(0, maxRetries);
        this.initialRetryDelaySeconds = Math.max(1, initialRetryDelaySeconds);
    }

    public synchronized String getCachedAppFolderId() {
        return cachedAppFolderId;
    }

    public synchronized void setCachedAppFolderId(String folderId) {
        this.cachedAppFolderId = folderId;
    }

    @Override
    public String getName() {
        return "Google Drive";
    }

    @Override
    public boolean isConfigured() {
        return enabled && tokenStore != null && tokenStore.isAuthorized();
    }

    @Override
    public CompletableFuture<Boolean> uploadAsync(Path backupFile) {
        return uploadArchiveAsync(backupFile).thenApply(UploadResult::success);
    }

    @Override
    public CompletableFuture<UploadResult> uploadArchiveAsync(Path backupFile) {
        CompletableFuture<UploadResult> future = new CompletableFuture<>();

        if (!enabled) {
            future.complete(UploadResult.failure("Google Drive uploads are disabled in configuration"));
            return future;
        }

        if (!isConfigured()) {
            if (logger != null) {
                logger.warning("[Google Drive] Upload skipped: Google Drive is enabled in config, but the account is not connected. "
                        + "Run '/damodara auth connect' from the server console to link a Google account.");
            }
            future.complete(UploadResult.failure("Google Drive account not connected"));
            return future;
        }

        CompletableFuture.runAsync(() -> {
            try {
                UploadResult result = executeResumableUpload(backupFile);
                if (result.success()) {
                    if (logger != null) {
                        logger.info("[Google Drive] Backup upload verified and confirmed by Google Drive: " + backupFile.getFileName());
                    }
                } else {
                    if (logger != null) {
                        logger.warning("[Google Drive] Upload could not be completed. Local backup archive is preserved: " + backupFile.getFileName());
                    }
                }
                future.complete(result);
            } catch (Exception e) {
                if (logger != null) {
                    logger.severe("[Google Drive] Unexpected error during archive upload: " + e.getMessage());
                }
                future.complete(UploadResult.failure(e.getMessage()));
            }
        });

        return future;
    }

    public synchronized String resolveTargetFolderId(String accessToken) {
        if (this.folderId != null && !this.folderId.isBlank()) {
            return this.folderId.trim();
        }

        if (this.cachedAppFolderId != null && !this.cachedAppFolderId.isBlank()) {
            return this.cachedAppFolderId;
        }

        String existingId = searchExistingBackupFolder(accessToken);
        if (existingId != null && !existingId.isBlank()) {
            this.cachedAppFolderId = existingId;
            if (logger != null) {
                logger.info("[Google Drive] Reusing existing Damodara 'backup' folder: " + existingId);
            }
            return existingId;
        }

        String createdId = createBackupFolder(accessToken);
        if (createdId != null && !createdId.isBlank()) {
            this.cachedAppFolderId = createdId;
            if (logger != null) {
                logger.info("[Google Drive] Created new Damodara 'backup' folder: " + createdId);
            }
            return createdId;
        }

        return null;
    }

    private String searchExistingBackupFolder(String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(FOLDERS_QUERY_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .timeout(Duration.ofSeconds(20))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return parseFirstFileId(response.body());
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.warning("[Google Drive] Error checking for existing backup folder: " + e.getMessage());
            }
        }
        return null;
    }

    private String createBackupFolder(String accessToken) {
        try {
            String body = "{\"name\":\"backup\",\"mimeType\":\"application/vnd.google-apps.folder\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CREATE_FILE_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(20))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return extractJsonField(response.body(), "id");
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.warning("[Google Drive] Error creating backup folder: " + e.getMessage());
            }
        }
        return null;
    }

    private static String parseFirstFileId(String json) {
        if (json == null) return null;
        int filesIdx = json.indexOf("\"files\"");
        if (filesIdx == -1) return null;
        int arrayStart = json.indexOf('[', filesIdx);
        int arrayEnd = json.indexOf(']', arrayStart);
        if (arrayStart == -1 || arrayEnd == -1 || arrayEnd <= arrayStart + 1) {
            return null;
        }
        String content = json.substring(arrayStart + 1, arrayEnd);
        return extractJsonField(content, "id");
    }

    @Override
    public boolean trashFile(String remoteFileId) {
        if (remoteFileId == null || remoteFileId.isBlank()) {
            if (logger != null) {
                logger.warning("[Google Drive] Cannot trash file: remoteFileId is null or blank.");
            }
            return false;
        }

        String accessToken = tokenStore.getValidAccessToken();
        if (accessToken == null) {
            if (logger != null) {
                logger.severe("[Google Drive] Cannot trash file: Failed to obtain valid access token from TokenStore.");
            }
            return false;
        }

        String url = FILES_ENDPOINT + URLEncoder.encode(remoteFileId, StandardCharsets.UTF_8);
        String body = "{\"trashed\":true}";

        int attempt = 0;
        long delaySeconds = initialRetryDelaySeconds;
        String currentToken = accessToken;

        while (attempt <= maxRetries) {
            attempt++;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + currentToken)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(30))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    if (logger != null) {
                        logger.info("[Google Drive] Moved backup to Trash: " + remoteFileId);
                    }
                    return true;
                } else if (response.statusCode() == 404) {
                    if (logger != null) {
                        logger.info("[Google Drive] File already absent or in Trash on Google Drive: " + remoteFileId);
                    }
                    return true;
                } else if (response.statusCode() == 401 && attempt <= maxRetries) {
                    if (logger != null) {
                        logger.warning("[Google Drive] Access token rejected (HTTP 401) during trash operation. Refreshing token...");
                    }
                    String refreshed = tokenStore.getValidAccessToken();
                    if (refreshed != null) {
                        currentToken = refreshed;
                    }
                } else {
                    if (logger != null) {
                        logger.warning("[Google Drive] Trash file request returned HTTP " + response.statusCode()
                                + " for file ID " + remoteFileId);
                    }
                }
            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("[Google Drive] Trash attempt " + attempt + " failed for file ID " + remoteFileId + ": " + e.getMessage());
                }
            }

            if (attempt <= maxRetries) {
                sleepQuietly(delaySeconds);
                delaySeconds = Math.min(60, delaySeconds * 2);
            }
        }

        return false;
    }

    private UploadResult executeResumableUpload(Path backupFile) throws Exception {
        long fileSize = Files.size(backupFile);
        String fileName = backupFile.getFileName().toString();

        if (logger != null) {
            logger.info("[Google Drive] Preparing resumable upload for " + fileName + " (" + (fileSize / (1024 * 1024)) + " MB)");
        }

        String accessToken = tokenStore.getValidAccessToken();
        if (accessToken == null) {
            if (logger != null) {
                logger.severe("[Google Drive] Cannot proceed: Failed to obtain valid access token from TokenStore.");
            }
            return UploadResult.failure("Access token unavailable");
        }

        String sessionUri = initiateUploadSession(fileName, fileSize, accessToken);
        if (sessionUri == null) {
            if (logger != null) {
                logger.severe("[Google Drive] Failed to initiate resumable upload session with Google Drive API.");
            }
            return UploadResult.failure("Failed to initiate resumable session");
        }

        int chunkSizeBytes = chunkSizeMb * 1024 * 1024;
        byte[] buffer = new byte[chunkSizeBytes];

        long bytesUploaded = 0;
        AtomicReference<String> createdFileId = new AtomicReference<>(null);

        try (InputStream in = Files.newInputStream(backupFile)) {
            while (bytesUploaded < fileSize) {
                long currentStart = bytesUploaded;
                int bytesToRead = (int) Math.min(chunkSizeBytes, fileSize - currentStart);

                int read = 0;
                while (read < bytesToRead) {
                    int count = in.read(buffer, read, bytesToRead - read);
                    if (count == -1) break;
                    read += count;
                }

                long currentEnd = currentStart + read - 1;
                byte[] chunkData = new byte[read];
                System.arraycopy(buffer, 0, chunkData, 0, read);

                boolean chunkSuccess = uploadChunkWithRetry(sessionUri, chunkData, currentStart, currentEnd, fileSize, createdFileId);
                if (!chunkSuccess) {
                    if (logger != null) {
                        logger.severe("[Google Drive] Chunk transfer failed at range " + currentStart + "-" + currentEnd + "/" + fileSize);
                    }
                    return UploadResult.failure("Chunk transfer failed at range " + currentStart + "-" + currentEnd);
                }

                bytesUploaded += read;
                int percent = (int) ((bytesUploaded * 100) / fileSize);
                if (logger != null) {
                    logger.fine("[Google Drive] Upload progress: " + percent + "% (" + (bytesUploaded / (1024 * 1024)) + "/" + (fileSize / (1024 * 1024)) + " MB)");
                }
            }
        }

        String fileId = createdFileId.get();
        return UploadResult.success(fileId);
    }

    private String initiateUploadSession(String fileName, long fileSize, String accessToken) {
        int attempt = 0;
        long delaySeconds = initialRetryDelaySeconds;
        String currentToken = accessToken;

        String targetFolder = resolveTargetFolderId(currentToken);
        String metadataJson;
        if (targetFolder != null && !targetFolder.isBlank()) {
            metadataJson = "{\"name\":\"" + escapeJson(fileName) + "\",\"parents\":[\"" + escapeJson(targetFolder) + "\"]}";
        } else {
            metadataJson = "{\"name\":\"" + escapeJson(fileName) + "\"}";
        }

        while (attempt <= maxRetries) {
            attempt++;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(RESUMABLE_INIT_URL))
                        .header("Authorization", "Bearer " + currentToken)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .header("X-Upload-Content-Type", "application/zip")
                        .header("X-Upload-Content-Length", String.valueOf(fileSize))
                        .POST(HttpRequest.BodyPublishers.ofString(metadataJson, StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(30))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return response.headers().firstValue("Location").orElse(null);
                } else if (response.statusCode() == 401 && attempt <= maxRetries) {
                    if (logger != null) {
                        logger.warning("[Google Drive] Access token rejected with HTTP 401 during session initiation. Refreshing token...");
                    }
                    String refreshed = tokenStore.getValidAccessToken();
                    if (refreshed != null) {
                        currentToken = refreshed;
                    }
                } else {
                    if (logger != null) {
                        logger.warning("[Google Drive] Initiate upload session returned HTTP " + response.statusCode());
                    }
                }
            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("[Google Drive] Session initiation attempt " + attempt + " failed: " + e.getMessage());
                }
            }

            if (attempt <= maxRetries) {
                sleepQuietly(delaySeconds);
                delaySeconds = Math.min(60, delaySeconds * 2);
            }
        }

        return null;
    }

    private boolean uploadChunkWithRetry(
            String sessionUri,
            byte[] chunkData,
            long start,
            long end,
            long total,
            AtomicReference<String> createdFileId
    ) {
        int attempt = 0;
        long delaySeconds = initialRetryDelaySeconds;

        String contentRange = "bytes " + start + "-" + end + "/" + total;

        while (attempt <= maxRetries) {
            attempt++;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(sessionUri))
                        .header("Content-Range", contentRange)
                        .header("Content-Type", "application/zip")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(chunkData))
                        .timeout(Duration.ofMinutes(3))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 308) {
                    return true;
                }

                if (response.statusCode() == 200 || response.statusCode() == 201) {
                    String id = extractJsonField(response.body(), "id");
                    if (id != null) {
                        createdFileId.set(id);
                    }
                    return true;
                }

                if (logger != null) {
                    logger.warning("[Google Drive] Upload chunk returned unexpected HTTP " + response.statusCode());
                }

            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("[Google Drive] Chunk upload attempt " + attempt + " failed (" + e.getMessage() + ")");
                }
            }

            long probeReceived = queryUploadStatus(sessionUri, total, createdFileId);
            if (probeReceived == total || probeReceived >= end) {
                if (logger != null) {
                    logger.fine("[Google Drive] Status probe confirmed chunk " + start + "-" + end + " was received.");
                }
                return true;
            } else if (probeReceived == -1) {
                if (logger != null) {
                    logger.severe("[Google Drive] Resumable upload session is no longer valid.");
                }
                return false;
            }

            if (attempt <= maxRetries) {
                if (logger != null) {
                    logger.info("[Google Drive] Retrying chunk in " + delaySeconds + " seconds (attempt " + (attempt + 1) + "/" + (maxRetries + 1) + ")...");
                }
                sleepQuietly(delaySeconds);
                delaySeconds = Math.min(60, delaySeconds * 2);
            }
        }

        return false;
    }

    private long queryUploadStatus(String sessionUri, long total, AtomicReference<String> createdFileId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sessionUri))
                    .header("Content-Range", "bytes */" + total)
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(20))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                String id = extractJsonField(response.body(), "id");
                if (id != null) {
                    createdFileId.set(id);
                }
                return total;
            }

            if (response.statusCode() == 308) {
                String rangeHeader = response.headers().firstValue("Range").orElse(null);
                if (rangeHeader != null && rangeHeader.startsWith("bytes=0-")) {
                    String lastByteStr = rangeHeader.substring("bytes=0-".length()).trim();
                    return Long.parseLong(lastByteStr);
                }
                return 0;
            }

            if (response.statusCode() == 404) {
                return -1;
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.fine("[Google Drive] Query upload status probe encountered error: " + e.getMessage());
            }
        }
        return -2;
    }

    private static void sleepQuietly(long seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractJsonField(String json, String field) {
        if (json == null) return null;
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
}
