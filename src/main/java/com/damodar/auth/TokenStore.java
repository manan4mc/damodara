package com.damodar.auth;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TokenStore {

    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke";

    private final Logger logger;
    private final Path credentialsDir;
    private final Path credentialsFile;
    private final Path tempCredentialsFile;

    private final HttpClient httpClient;

    private String clientId;
    private String clientSecret;
    private String refreshToken;
    private String accessToken;
    private long expiresAtEpochMs = 0;

    public TokenStore(Logger logger, Path pluginDataFolder, String clientId, String clientSecret) {
        this.logger = logger;
        this.credentialsDir = pluginDataFolder.resolve("credentials");
        this.credentialsFile = credentialsDir.resolve("credentials.json");
        this.tempCredentialsFile = credentialsDir.resolve("credentials.json.tmp");
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        loadStoredCredentials();
    }

    public synchronized void updateClientCredentials(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public synchronized boolean loadStoredCredentials() {
        if (Files.exists(credentialsFile)) {
            try {
                String json = Files.readString(credentialsFile, StandardCharsets.UTF_8);
                parseAndApplyCredentials(json);
                restrictFilePermissions(credentialsFile);
                if (logger != null) {
                    logger.info("[Damodara Auth] Google Drive credentials loaded from plugins/Damodara/credentials/credentials.json.");
                }
                return true;
            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("[Damodara Auth] Failed to read credentials from disk: " + e.getMessage());
                }
                return false;
            }
        }

        Path legacyCredDir = credentialsDir.getParent().getParent()
                .resolve("Damodar").resolve("credentials");
        Path legacyTokenFile = legacyCredDir.resolve("token.enc");
        if (Files.exists(legacyTokenFile) && !Files.exists(credentialsFile)) {
            if (logger != null) {
                logger.info("[Damodara Auth] Found legacy credentials at plugins/Damodar/credentials/token.enc. "
                        + "Because Damodara now uses the Google OAuth 2.0 device flow for headless servers, "
                        + "previous credentials cannot be automatically reused. "
                        + "Please run '/damodara auth connect' in the server console to link your Google Drive account.");
            }
            return false;
        }

        return false;
    }

    public synchronized boolean saveDeviceFlowTokens(
            String clientId, String clientSecret,
            String refreshToken, String accessToken, long expiresAtEpochMs) {

        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
        this.accessToken = accessToken;
        this.expiresAtEpochMs = expiresAtEpochMs;

        return persistCredentials();
    }

    private synchronized boolean persistCredentials() {
        try {
            if (!Files.exists(credentialsDir)) {
                Files.createDirectories(credentialsDir);
                restrictDirectoryPermissions(credentialsDir);
            }

            String payload = "{\n"
                    + "  \"client_id\": \"" + escapeJson(clientId != null ? clientId : "") + "\",\n"
                    + "  \"refresh_token\": \"" + escapeJson(refreshToken != null ? refreshToken : "") + "\",\n"
                    + "  \"access_token\": \"" + escapeJson(accessToken != null ? accessToken : "") + "\",\n"
                    + "  \"expires_at_epoch_ms\": " + expiresAtEpochMs + "\n"
                    + "}\n";

            Files.writeString(
                    tempCredentialsFile,
                    payload,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            restrictFilePermissions(tempCredentialsFile);

            try {
                Files.move(tempCredentialsFile, credentialsFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempCredentialsFile, credentialsFile, StandardCopyOption.REPLACE_EXISTING);
            }

            restrictFilePermissions(credentialsFile);
            if (logger != null) {
                logger.info("[Damodara Auth] Credentials saved to plugins/Damodara/credentials/credentials.json (owner-restricted).");
            }
            return true;

        } catch (Exception e) {
            if (logger != null) {
                logger.severe("[Damodara Auth] Failed to save credentials to disk: " + e.getMessage());
            }
            try {
                Files.deleteIfExists(tempCredentialsFile);
            } catch (IOException ignored) {}
            return false;
        }
    }

    private void parseAndApplyCredentials(String json) {
        String rt = extractJsonField(json, "refresh_token");
        String at = extractJsonField(json, "access_token");

        if (rt != null && !rt.isBlank()) this.refreshToken = rt;
        if (at != null && !at.isBlank()) this.accessToken = at;

        String cidInToken = extractJsonField(json, "client_id");
        if ((this.clientId == null || this.clientId.isBlank())
                && cidInToken != null && !cidInToken.isBlank()) {
            this.clientId = cidInToken;
        }

        String expiresStr = extractJsonField(json, "expires_at_epoch_ms");
        if (expiresStr != null) {
            try {
                this.expiresAtEpochMs = Long.parseLong(expiresStr);
            } catch (NumberFormatException ignored) {}
        }
    }

    public synchronized boolean isAuthorized() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    public synchronized String getValidAccessToken() {
        if (!isAuthorized()) {
            return null;
        }

        long now = System.currentTimeMillis();
        if (accessToken != null && !accessToken.isBlank() && now < (expiresAtEpochMs - 60000L)) {
            return accessToken;
        }

        if (clientSecret == null || clientSecret.isBlank()) {
            if (logger != null) {
                logger.severe("[Damodara Auth] Cannot refresh access token: OAuth Client Secret is missing.");
            }
            return null;
        }

        if (clientId == null || clientId.isBlank()) {
            if (logger != null) {
                logger.severe("[Damodara Auth] Cannot refresh access token: OAuth Client ID is missing.");
            }
            return null;
        }

        if (logger != null) {
            logger.info("[Damodara Auth] Access token expired. Requesting refresh from Google OAuth...");
        }

        try {
            String form = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                    + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                    + "&grant_type=refresh_token";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_ENDPOINT))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .timeout(Duration.ofSeconds(20))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String responseBody = response.body();
                this.accessToken = extractJsonField(responseBody, "access_token");
                String expiresInStr = extractJsonField(responseBody, "expires_in");
                long expiresInSec = expiresInStr != null ? Long.parseLong(expiresInStr) : 3600L;
                this.expiresAtEpochMs = System.currentTimeMillis() + (expiresInSec * 1000L);

                persistCredentials();

                if (logger != null) {
                    logger.info("[Damodara Auth] Access token successfully refreshed.");
                }
                return this.accessToken;
            } else {
                if (logger != null) {
                    logger.severe("[Damodara Auth] Token refresh failed with HTTP " + response.statusCode());
                    if (response.body().contains("invalid_grant")) {
                        logger.warning("[Damodara Auth] Google authorization has expired or was revoked. "
                                + "Run '/damodara auth connect' in the server console to re-authorize.");
                    }
                }
                return null;
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.severe("[Damodara Auth] Network error during token refresh: " + e.getMessage());
            }
            return null;
        }
    }

    public synchronized boolean revokeAndDisconnect() {
        boolean revokedAtGoogle = false;
        String tokenToRevoke = refreshToken != null ? refreshToken : accessToken;

        if (tokenToRevoke != null && !tokenToRevoke.isBlank()) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(REVOKE_ENDPOINT + "?token="
                                + URLEncoder.encode(tokenToRevoke, StandardCharsets.UTF_8)))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(15))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                revokedAtGoogle = (response.statusCode() == 200);
            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("[Damodara Auth] Failed to contact Google revocation endpoint: " + e.getMessage());
                }
            }
        }

        this.refreshToken = null;
        this.accessToken = null;
        this.expiresAtEpochMs = 0;

        try {
            Files.deleteIfExists(credentialsFile);
            Files.deleteIfExists(tempCredentialsFile);
            if (logger != null) {
                logger.info("[Damodara Auth] Credential file removed. Google Drive disconnected.");
            }
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("[Damodara Auth] Error deleting credential file: " + e.getMessage());
            }
        }

        return revokedAtGoogle;
    }

    private void restrictFilePermissions(Path file) {
        if (!Files.exists(file)) return;
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                );
                Files.setPosixFilePermissions(file, perms);
            } catch (Exception e) {
                if (logger != null) {
                    logger.fine("[Damodara Auth] Could not set POSIX permissions on " + file + ": " + e.getMessage());
                }
            }
        }
    }

    private void restrictDirectoryPermissions(Path dir) {
        if (!Files.exists(dir)) return;
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE
                );
                Files.setPosixFilePermissions(dir, perms);
            } catch (Exception e) {
                if (logger != null) {
                    logger.fine("[Damodara Auth] Could not set POSIX permissions on " + dir + ": " + e.getMessage());
                }
            }
        }
    }

    private static String extractJsonField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"?([^\",}\\]]+)\"?");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
