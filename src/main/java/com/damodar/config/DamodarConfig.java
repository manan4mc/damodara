package com.damodar.config;

import com.damodar.scheduler.ScheduleSlotCalculator;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

public final class DamodarConfig {

    private final Logger logger;

    private final boolean scheduleEnabled;
    private final int daysInterval;
    private final List<LocalTime> scheduleTimes;
    private final ZoneId scheduleTimeZone;
    private final boolean catchUpEnabled;
    private final long startupDelaySeconds;

    private final Path sourceDirectory;
    private final Path destinationDirectory;
    private final boolean followSymlinks;

    private final List<String> excludes;
    private final List<String> includes;

    private final String archiveFormat;
    private final int compressionLevel;
    private final String serverIdentifier;
    private final String filenamePattern;
    private final String timestampPattern;
    private final ZoneId timeZone;
    private final boolean verifyChecksum;

    private final boolean localRetentionEnabled;
    private final int localRetentionMaxCount;
    private final int localRetentionMaxAgeDays;

    private final boolean driveRetentionEnabled;
    private final int driveRetentionMaxCount;
    private final int driveRetentionMaxAgeDays;

    private final int maxConcurrentBackups;

    private final boolean googleDriveEnabled;
    private final String googleDriveClientId;
    private final String googleDriveClientSecret;
    private final String googleDriveFolderId;
    private final int googleDriveChunkSizeMb;
    private final int googleDriveMaxRetries;
    private final int googleDriveInitialRetryDelaySeconds;

    private DamodarConfig(
            Logger logger,
            boolean scheduleEnabled,
            int daysInterval,
            List<LocalTime> scheduleTimes,
            ZoneId scheduleTimeZone,
            boolean catchUpEnabled,
            long startupDelaySeconds,
            Path sourceDirectory,
            Path destinationDirectory,
            boolean followSymlinks,
            List<String> excludes,
            List<String> includes,
            String archiveFormat,
            int compressionLevel,
            String serverIdentifier,
            String filenamePattern,
            String timestampPattern,
            ZoneId timeZone,
            boolean verifyChecksum,
            boolean localRetentionEnabled,
            int localRetentionMaxCount,
            int localRetentionMaxAgeDays,
            boolean driveRetentionEnabled,
            int driveRetentionMaxCount,
            int driveRetentionMaxAgeDays,
            int maxConcurrentBackups,
            boolean googleDriveEnabled,
            String googleDriveClientId,
            String googleDriveClientSecret,
            String googleDriveFolderId,
            int googleDriveChunkSizeMb,
            int googleDriveMaxRetries,
            int googleDriveInitialRetryDelaySeconds
    ) {
        this.logger = logger;
        this.scheduleEnabled = scheduleEnabled;
        this.daysInterval = daysInterval;
        this.scheduleTimes = Collections.unmodifiableList(new ArrayList<>(scheduleTimes));
        this.scheduleTimeZone = scheduleTimeZone;
        this.catchUpEnabled = catchUpEnabled;
        this.startupDelaySeconds = startupDelaySeconds;
        this.sourceDirectory = sourceDirectory;
        this.destinationDirectory = destinationDirectory;
        this.followSymlinks = followSymlinks;
        this.excludes = Collections.unmodifiableList(new ArrayList<>(excludes));
        this.includes = Collections.unmodifiableList(new ArrayList<>(includes));
        this.archiveFormat = archiveFormat;
        this.compressionLevel = compressionLevel;
        this.serverIdentifier = serverIdentifier;
        this.filenamePattern = filenamePattern;
        this.timestampPattern = timestampPattern;
        this.timeZone = timeZone;
        this.verifyChecksum = verifyChecksum;
        this.localRetentionEnabled = localRetentionEnabled;
        this.localRetentionMaxCount = localRetentionMaxCount;
        this.localRetentionMaxAgeDays = localRetentionMaxAgeDays;
        this.driveRetentionEnabled = driveRetentionEnabled;
        this.driveRetentionMaxCount = driveRetentionMaxCount;
        this.driveRetentionMaxAgeDays = driveRetentionMaxAgeDays;
        this.maxConcurrentBackups = maxConcurrentBackups;
        this.googleDriveEnabled = googleDriveEnabled;
        this.googleDriveClientId = googleDriveClientId;
        this.googleDriveClientSecret = googleDriveClientSecret;
        this.googleDriveFolderId = googleDriveFolderId;
        this.googleDriveChunkSizeMb = googleDriveChunkSizeMb;
        this.googleDriveMaxRetries = googleDriveMaxRetries;
        this.googleDriveInitialRetryDelaySeconds = googleDriveInitialRetryDelaySeconds;
    }

    public static DamodarConfig fromConfiguration(FileConfiguration config, Logger logger) {
        boolean scheduleEnabled = config.getBoolean("schedule.enabled", true);

        int daysInterval = config.getInt("schedule.days-interval", 1);
        if (config.contains("schedule.days-interval") && daysInterval < 1) {
            throw new IllegalArgumentException("schedule.days-interval must be >= 1, but found: " + daysInterval);
        }
        if (daysInterval < 1) {
            daysInterval = 1;
        }

        List<String> rawTimes = config.getStringList("schedule.times");
        if (rawTimes.isEmpty() && config.isString("schedule.times")) {
            String singleTime = config.getString("schedule.times");
            if (singleTime != null) {
                rawTimes = List.of(singleTime);
            }
        }
        boolean hasExplicitTimes = config.contains("schedule.times");
        List<LocalTime> scheduleTimes = ScheduleSlotCalculator.parseTimes(
                rawTimes.isEmpty() && !hasExplicitTimes ? List.of("00:00") : rawTimes,
                logger
        );

        if (daysInterval > 1 && scheduleTimes.size() > 1) {
            throw new IllegalArgumentException("When schedule.days-interval is greater than 1, exactly one time is permitted in schedule.times, but found "
                    + scheduleTimes.size() + " times: " + scheduleTimes);
        }

        String rawScheduleTimezone = config.getString("schedule.timezone", "Asia/Kolkata");
        ZoneId scheduleTimeZone = ScheduleSlotCalculator.parseTimeZone(rawScheduleTimezone, logger);

        boolean catchUp = config.getBoolean("schedule.catch-up", true);
        long startupDelaySeconds = Math.max(10, config.getLong("schedule.startup-delay-seconds", 60));

        String rawSource = config.getString("storage.source-directory", ".");
        String rawDestination = config.getString("storage.destination-directory", "backups");
        boolean followSymlinks = config.getBoolean("storage.follow-symlinks", false);

        PathSafetyValidator validator = new PathSafetyValidator(logger);
        PathSafetyValidator.ValidationResult pathResult = validator.validate(rawSource, rawDestination);
        if (!pathResult.valid()) {
            throw new IllegalArgumentException("Path safety check failed: " + pathResult.errorMessage());
        }

        List<String> rawExcludes = config.getStringList("filters.excludes");
        if (rawExcludes.isEmpty()) {
            rawExcludes = List.of(
                    "backups/**",
                    ".damodar_staging/**",
                    "plugins/Damodara/credentials/**",
                    "plugins/Damodara/credentials",
                    "plugins/Damodara/*.tmp",
                    "plugins/Damodar/credentials/**",
                    "plugins/Damodar/credentials",
                    "crash-reports/**",
                    "logs/*.log.gz",
                    "cache/**",
                    "**/session.lock",
                    "*.tmp",
                    "**/backup-history.csv"
            );
        }
        List<String> rawIncludes = config.getStringList("filters.includes");

        String format = config.getString("archive.format", "ZIP").toUpperCase();
        if (!format.equals("ZIP")) {
            if (logger != null) logger.warning("Unsupported archive format '" + format + "'. Falling back to 'ZIP'.");
            format = "ZIP";
        }

        int compression = config.getInt("archive.compression-level", 6);
        if (compression < 0 || compression > 9) {
            if (logger != null) logger.warning("Invalid compression-level " + compression + " (must be 0-9). Falling back to 6.");
            compression = 6;
        }

        String serverId = config.getString("archive.server-identifier", "server").replaceAll("[^a-zA-Z0-9_-]", "_");
        String filenamePattern = config.getString("archive.filename-pattern", "{server}-{timestamp}.{extension}");
        String timestampPattern = config.getString("archive.timestamp-pattern", "yyyy-MM-dd_HH-mm-ss");

        try {
            DateTimeFormatter.ofPattern(timestampPattern);
        } catch (Exception e) {
            if (logger != null) logger.warning("Invalid timestamp pattern '" + timestampPattern + "'. Falling back to 'yyyy-MM-dd_HH-mm-ss'.");
            timestampPattern = "yyyy-MM-dd_HH-mm-ss";
        }

        String rawTimezone = config.getString("archive.timezone", "UTC");
        ZoneId zoneId;
        try {
            if ("SYSTEM".equalsIgnoreCase(rawTimezone)) {
                zoneId = ZoneId.systemDefault();
            } else {
                zoneId = ZoneId.of(rawTimezone);
            }
        } catch (Exception e) {
            if (logger != null) logger.warning("Invalid archive timezone '" + rawTimezone + "'. Falling back to UTC.");
            zoneId = ZoneId.of("UTC");
        }

        boolean verifyChecksum = config.getBoolean("archive.verify-checksum", true);

        boolean localRetentionEnabled = config.contains("retention.local.enabled")
                ? config.getBoolean("retention.local.enabled")
                : config.getBoolean("retention.enabled", true);

        int localRetentionMaxCount = config.contains("retention.local.max-count")
                ? config.getInt("retention.local.max-count")
                : config.getInt("retention.max-count", 3);

        int localRetentionMaxAgeDays = config.getInt("retention.local.max-age-days", -1);

        boolean driveRetentionEnabled = config.contains("retention.google-drive.enabled")
                ? config.getBoolean("retention.google-drive.enabled")
                : config.getBoolean("google-drive.retention.enabled", false);

        int driveRetentionMaxCount = config.contains("retention.google-drive.max-count")
                ? config.getInt("retention.google-drive.max-count")
                : config.getInt("google-drive.retention.max-count", 3);

        int driveRetentionMaxAgeDays = config.getInt("retention.google-drive.max-age-days", -1);

        int maxConcurrent = 1;

        boolean driveEnabled = config.getBoolean("google-drive.enabled", true);
        OAuthCredentials creds = resolveOAuthCredentials(config, logger);
        String driveClientId = creds.clientId();
        String driveClientSecret = creds.clientSecret();

        if (driveEnabled && (!creds.isComplete())) {
            if (logger != null) {
                logger.fine("[Damodara] google-drive.enabled is true, but OAuth client credentials are not yet configured for this build.");
            }
        }

        String driveFolderId = config.getString("google-drive.folder-id", "").trim();
        int driveChunkMb = Math.max(1, config.getInt("google-drive.chunk-size-mb", 8));
        int driveMaxRetries = Math.max(0, config.getInt("google-drive.max-retries", 3));
        int driveInitialDelaySec = Math.max(1, config.getInt("google-drive.initial-retry-delay-seconds", 5));

        return new DamodarConfig(
                logger,
                scheduleEnabled,
                daysInterval,
                scheduleTimes,
                scheduleTimeZone,
                catchUp,
                startupDelaySeconds,
                pathResult.resolvedSource(),
                pathResult.resolvedDestination(),
                followSymlinks,
                rawExcludes,
                rawIncludes,
                format,
                compression,
                serverId,
                filenamePattern,
                timestampPattern,
                zoneId,
                verifyChecksum,
                localRetentionEnabled,
                localRetentionMaxCount,
                localRetentionMaxAgeDays,
                driveRetentionEnabled,
                driveRetentionMaxCount,
                driveRetentionMaxAgeDays,
                maxConcurrent,
                driveEnabled,
                driveClientId,
                driveClientSecret,
                driveFolderId,
                driveChunkMb,
                driveMaxRetries,
                driveInitialDelaySec
        );
    }

    private static OAuthCredentials resolveOAuthCredentials(FileConfiguration config, Logger logger) {
        String envId = System.getenv("DAMODARA_GOOGLE_CLIENT_ID");
        String envSecret = System.getenv("DAMODARA_GOOGLE_CLIENT_SECRET");
        if (envId != null && !envId.isBlank() && envSecret != null && !envSecret.isBlank()) {
            return new OAuthCredentials(envId.trim(), envSecret.trim());
        }

        String configId = config.getString("google-drive.client-id", "").trim();
        String configSecret = config.getString("google-drive.client-secret", "").trim();
        if (!configId.isEmpty() && !configSecret.isEmpty()) {
            return new OAuthCredentials(configId, configSecret);
        }

        OAuthCredentials injected = loadInjectedCredentials(logger);
        if (injected != null && injected.isComplete()) {
            return injected;
        }

        OAuthCredentials local = loadLocalDeveloperCredentials(logger);
        if (local != null && local.isComplete()) {
            return local;
        }

        String fallbackId = (envId != null && !envId.isBlank()) ? envId.trim() : configId;
        String fallbackSecret = (envSecret != null && !envSecret.isBlank()) ? envSecret.trim() : configSecret;
        return new OAuthCredentials(fallbackId, fallbackSecret);
    }

    private static OAuthCredentials loadInjectedCredentials(Logger logger) {
        try (InputStream in = DamodarConfig.class.getResourceAsStream("/damodara-oauth.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String id = props.getProperty("clientId", "").trim();
                String secret = props.getProperty("clientSecret", "").trim();
                if (!id.isEmpty() && !secret.isEmpty() && !id.startsWith("${") && !secret.startsWith("${")) {
                    return new OAuthCredentials(id, secret);
                }
            }
        } catch (Exception e) {
            if (logger != null) logger.fine("[Damodara] Could not load /damodara-oauth.properties: " + e.getMessage());
        }
        return null;
    }

    private static OAuthCredentials loadLocalDeveloperCredentials(Logger logger) {
        try {
            Path devProps = Path.of("oauth.properties");
            if (Files.exists(devProps)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(devProps)) {
                    props.load(in);
                }
                String id = props.getProperty("damodara.oauth.clientId", "").trim();
                String secret = props.getProperty("damodara.oauth.clientSecret", "").trim();
                if (!id.isEmpty() && !secret.isEmpty()) {
                    return new OAuthCredentials(id, secret);
                }
            }
        } catch (Exception e) {
            if (logger != null) logger.fine("[Damodara] Could not load local oauth.properties: " + e.getMessage());
        }
        return null;
    }

    public boolean isScheduleEnabled() { return scheduleEnabled; }
    public int getDaysInterval() { return daysInterval; }
    public String getScheduleMode() { return daysInterval == 1 ? "DAILY" : "INTERVAL"; }
    public List<LocalTime> getScheduleTimes() { return scheduleTimes; }
    public ZoneId getScheduleTimeZone() { return scheduleTimeZone; }
    public boolean isCatchUpEnabled() { return catchUpEnabled; }
    public long getIntervalSeconds() { return (long) daysInterval * 86400L; }
    public boolean isRunOnStartup() { return false; }
    public long getStartupDelaySeconds() { return startupDelaySeconds; }

    public Path getSourceDirectory() { return sourceDirectory; }
    public Path getDestinationDirectory() { return destinationDirectory; }
    public boolean isFollowSymlinks() { return followSymlinks; }
    public List<String> getExcludes() { return excludes; }
    public List<String> getIncludes() { return includes; }

    public String getArchiveFormat() { return archiveFormat; }
    public int getCompressionLevel() { return compressionLevel; }
    public String getServerIdentifier() { return serverIdentifier; }
    public String getFilenamePattern() { return filenamePattern; }
    public String getTimestampPattern() { return timestampPattern; }
    public ZoneId getTimeZone() { return timeZone; }
    public boolean isVerifyChecksum() { return verifyChecksum; }

    public boolean isLocalRetentionEnabled() { return localRetentionEnabled; }
    public int getLocalRetentionMaxCount() { return localRetentionMaxCount; }
    public int getLocalRetentionMaxAgeDays() { return localRetentionMaxAgeDays; }

    public boolean isDriveRetentionEnabled() { return driveRetentionEnabled; }
    public int getDriveRetentionMaxCount() { return driveRetentionMaxCount; }
    public int getDriveRetentionMaxAgeDays() { return driveRetentionMaxAgeDays; }

    public boolean isRetentionEnabled() { return localRetentionEnabled; }
    public int getRetentionMaxCount() { return localRetentionMaxCount; }
    public int getRetentionMaxAgeDays() { return localRetentionMaxAgeDays; }

    public int getMaxConcurrentBackups() { return maxConcurrentBackups; }

    public boolean isGoogleDriveEnabled() { return googleDriveEnabled; }
    public String getGoogleDriveClientId() { return googleDriveClientId; }
    public String getGoogleDriveClientSecret() { return googleDriveClientSecret; }
    public String getGoogleDriveFolderId() { return googleDriveFolderId; }
    public int getGoogleDriveChunkSizeMb() { return googleDriveChunkSizeMb; }
    public int getGoogleDriveMaxRetries() { return googleDriveMaxRetries; }
    public int getGoogleDriveInitialRetryDelaySeconds() { return googleDriveInitialRetryDelaySeconds; }

    public boolean hasOAuthCredentials() {
        return googleDriveClientId != null && !googleDriveClientId.isBlank()
                && googleDriveClientSecret != null && !googleDriveClientSecret.isBlank();
    }

    public record OAuthCredentials(String clientId, String clientSecret) {
        public boolean isComplete() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
