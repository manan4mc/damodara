package com.damodar.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DeviceFlowAuthService {

    private static final String DEVICE_AUTH_ENDPOINT = "https://oauth2.googleapis.com/device/code";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/drive.file";

    private static final String ERR_AUTHORIZATION_PENDING = "authorization_pending";
    private static final String ERR_SLOW_DOWN = "slow_down";
    private static final String ERR_ACCESS_DENIED = "access_denied";
    private static final String ERR_EXPIRED_TOKEN = "expired_token";

    private final Logger logger;
    private final TokenStore tokenStore;

    private final HttpClient httpClient;
    private final AtomicBoolean linking = new AtomicBoolean(false);
    private final ScheduledExecutorService pollScheduler;
    private volatile ScheduledFuture<?> activePollTask = null;

    public DeviceFlowAuthService(Logger logger, TokenStore tokenStore) {
        this.logger = logger;
        this.tokenStore = tokenStore;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "damodara-device-poll");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        this.pollScheduler = executor;
    }

    public boolean isLinking() {
        return linking.get();
    }

    public boolean cancelLinking() {
        ScheduledFuture<?> task = activePollTask;
        if (task != null && !task.isDone()) {
            task.cancel(false);
            activePollTask = null;
            linking.set(false);
            if (logger != null) {
                logger.info("[Damodara Auth] Device-flow linking session cancelled.");
            }
            return true;
        }
        return false;
    }

    public CompletableFuture<Boolean> startDeviceFlow(String clientId, String clientSecret) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        if (clientId == null || clientId.isBlank()) {
            if (logger != null) {
                logger.severe("[Damodara Auth] Cannot start device flow: OAuth client ID is missing.");
            }
            future.complete(false);
            return future;
        }

        if (clientSecret == null || clientSecret.isBlank()) {
            if (logger != null) {
                logger.severe("[Damodara Auth] Cannot start device flow: OAuth client secret is missing.");
            }
            future.complete(false);
            return future;
        }

        if (!linking.compareAndSet(false, true)) {
            if (logger != null) {
                logger.warning("[Damodara Auth] A device-flow linking session is already in progress. "
                        + "Check the server console for the active URL and code, or wait for it to complete.");
            }
            future.complete(false);
            return future;
        }

        CompletableFuture.runAsync(() -> {
            try {
                DeviceCodeResponse dcr = requestDeviceCode(clientId);
                if (dcr == null) {
                    linking.set(false);
                    future.complete(false);
                    return;
                }

                if (logger != null) {
                    logger.info("================================================================");
                    logger.info("[Damodara Auth] *** GOOGLE DRIVE DEVICE LINKING ***");
                    logger.info("[Damodara Auth] Open this URL on any phone or computer:");
                    logger.info("[Damodara Auth]   " + dcr.verificationUri);
                    logger.info("[Damodara Auth] Then enter this code when prompted:");
                    logger.info("[Damodara Auth]   " + dcr.userCode);
                    logger.info("[Damodara Auth] This code expires in " + (dcr.expiresIn / 60) + " minutes.");
                    logger.info("[Damodara Auth] Waiting for approval in browser...");
                    logger.info("================================================================");
                }

                schedulePollLoop(clientId, clientSecret, dcr, future);

            } catch (Exception e) {
                if (logger != null) {
                    logger.severe("[Damodara Auth] Unexpected error during device flow setup: " + e.getMessage());
                }
                linking.set(false);
                future.complete(false);
            }
        });

        return future;
    }

    private DeviceCodeResponse requestDeviceCode(String clientId) {
        try {
            String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DEVICE_AUTH_ENDPOINT))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(20))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String rb = response.body();
                String deviceCode = extractJsonField(rb, "device_code");
                String userCode = extractJsonField(rb, "user_code");
                String verUri = extractJsonField(rb, "verification_url");
                if (verUri == null || verUri.isBlank()) {
                    verUri = extractJsonField(rb, "verification_uri");
                }
                String expiresInStr = extractJsonField(rb, "expires_in");
                String intervalStr = extractJsonField(rb, "interval");

                if (deviceCode == null || userCode == null || verUri == null) {
                    if (logger != null) {
                        logger.severe("[Damodara Auth] Device authorization endpoint returned incomplete response.");
                    }
                    return null;
                }

                long expiresIn = expiresInStr != null ? Long.parseLong(expiresInStr) : 1800L;
                long interval = intervalStr != null ? Long.parseLong(intervalStr) : 5L;

                return new DeviceCodeResponse(deviceCode, userCode, verUri, expiresIn, interval);

            } else {
                if (logger != null) {
                    logger.severe("[Damodara Auth] Device authorization endpoint returned HTTP " + response.statusCode());
                    if (response.body().contains("unauthorized_client")) {
                        logger.severe("[Damodara Auth] Google rejected the client as 'unauthorized_client'. "
                                + "Ensure the OAuth client is registered for 'TVs and Limited Input devices'. "
                                + "Desktop or Web clients are NOT compatible with device flow.");
                    }
                }
                return null;
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.severe("[Damodara Auth] Network error contacting device authorization endpoint: " + e.getMessage());
            }
            return null;
        }
    }

    private void schedulePollLoop(String clientId, String clientSecret,
                                  DeviceCodeResponse dcr, CompletableFuture<Boolean> future) {
        long expiryMs = System.currentTimeMillis() + (dcr.expiresIn * 1000L);
        long[] pollIntervalSec = {dcr.pollIntervalSec};

        Runnable pollStep = new Runnable() {
            @Override
            public void run() {
                if (future.isDone() || !linking.get()) {
                    linking.set(false);
                    activePollTask = null;
                    return;
                }

                if (System.currentTimeMillis() >= expiryMs) {
                    if (logger != null) {
                        logger.warning("[Damodara Auth] The device code expired before authorization was completed. "
                                + "Run '/damodara auth connect' in the server console to start a new session.");
                    }
                    linking.set(false);
                    activePollTask = null;
                    future.complete(false);
                    return;
                }

                try {
                    PollResult result = pollTokenEndpoint(clientId, clientSecret, dcr.deviceCode);

                    switch (result.status) {
                        case SUCCESS -> {
                            boolean saved = tokenStore.saveDeviceFlowTokens(
                                    clientId, clientSecret,
                                    result.refreshToken, result.accessToken, result.expiresAtEpochMs);
                            if (saved) {
                                if (logger != null) {
                                    logger.info("[Damodara Auth] Google Drive account successfully linked! "
                                            + "Set google-drive.enabled: true in config.yml (if not already enabled) and run /damodara reload.");
                                }
                            } else {
                                if (logger != null) {
                                    logger.severe("[Damodara Auth] Tokens received from Google but could not be saved to disk. "
                                            + "Check server directory write permissions.");
                                }
                            }
                            linking.set(false);
                            activePollTask = null;
                            future.complete(saved);
                            return;
                        }
                        case PENDING -> {
                        }
                        case SLOW_DOWN -> {
                            pollIntervalSec[0] += 5;
                            if (logger != null) {
                                logger.fine("[Damodara Auth] Google returned slow_down; poll interval adjusted to "
                                        + pollIntervalSec[0] + "s.");
                            }
                        }
                        case DENIED -> {
                            if (logger != null) {
                                logger.warning("[Damodara Auth] Authorization was denied by the user. "
                                        + "Run '/damodara auth connect' in the server console if you wish to try again.");
                            }
                            linking.set(false);
                            activePollTask = null;
                            future.complete(false);
                            return;
                        }
                        case EXPIRED -> {
                            if (logger != null) {
                                logger.warning("[Damodara Auth] Device code expired (reported by Google). "
                                        + "Run '/damodara auth connect' in the server console to generate a new code.");
                            }
                            linking.set(false);
                            activePollTask = null;
                            future.complete(false);
                            return;
                        }
                        case ERROR -> {
                            if (logger != null) {
                                logger.severe("[Damodara Auth] Polling encountered an error. Check server console for details.");
                            }
                            linking.set(false);
                            activePollTask = null;
                            future.complete(false);
                            return;
                        }
                    }
                } catch (Exception e) {
                    if (logger != null) {
                        logger.severe("[Damodara Auth] Unexpected error during token polling: " + e.getMessage());
                    }
                    linking.set(false);
                    activePollTask = null;
                    future.complete(false);
                    return;
                }

                if (linking.get() && !future.isDone()) {
                    activePollTask = pollScheduler.schedule(this, pollIntervalSec[0], TimeUnit.SECONDS);
                }
            }
        };

        activePollTask = pollScheduler.schedule(pollStep, pollIntervalSec[0], TimeUnit.SECONDS);
    }

    private PollResult pollTokenEndpoint(String clientId, String clientSecret, String deviceCode) throws Exception {
        String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&device_code=" + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8)
                + "&grant_type=urn:ietf:params:oauth:grant-type:device_code";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_ENDPOINT))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(20))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String rb = response.body();

        if (response.statusCode() == 200) {
            String accessToken = extractJsonField(rb, "access_token");
            String refreshToken = extractJsonField(rb, "refresh_token");
            String expiresInStr = extractJsonField(rb, "expires_in");
            long expiresInSec = expiresInStr != null ? Long.parseLong(expiresInStr) : 3600L;
            long expiresAtEpochMs = System.currentTimeMillis() + (expiresInSec * 1000L);
            return PollResult.success(accessToken, refreshToken, expiresAtEpochMs);
        }

        String error = extractJsonField(rb, "error");
        if (error == null) error = "";

        return switch (error) {
            case ERR_AUTHORIZATION_PENDING -> PollResult.pending();
            case ERR_SLOW_DOWN -> PollResult.slowDown();
            case ERR_ACCESS_DENIED -> PollResult.denied();
            case ERR_EXPIRED_TOKEN -> PollResult.expired();
            default -> {
                if (logger != null) {
                    logger.warning("[Damodara Auth] Poll response HTTP " + response.statusCode() + ", error=" + error);
                }
                yield PollResult.error();
            }
        };
    }

    private record DeviceCodeResponse(
            String deviceCode,
            String userCode,
            String verificationUri,
            long expiresIn,
            long pollIntervalSec
    ) {}

    private enum PollStatus { SUCCESS, PENDING, SLOW_DOWN, DENIED, EXPIRED, ERROR }

    private static final class PollResult {
        final PollStatus status;
        final String accessToken;
        final String refreshToken;
        final long expiresAtEpochMs;

        private PollResult(PollStatus status, String accessToken, String refreshToken, long expiresAtEpochMs) {
            this.status = status;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }

        static PollResult success(String at, String rt, long exp) {
            return new PollResult(PollStatus.SUCCESS, at, rt, exp);
        }
        static PollResult pending() { return new PollResult(PollStatus.PENDING, null, null, 0); }
        static PollResult slowDown() { return new PollResult(PollStatus.SLOW_DOWN, null, null, 0); }
        static PollResult denied() { return new PollResult(PollStatus.DENIED, null, null, 0); }
        static PollResult expired() { return new PollResult(PollStatus.EXPIRED, null, null, 0); }
        static PollResult error() { return new PollResult(PollStatus.ERROR, null, null, 0); }
    }

    private static String extractJsonField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"?([^\",}\\]]+)\"?");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    public void shutdown() {
        cancelLinking();
        pollScheduler.shutdown();
    }
}
