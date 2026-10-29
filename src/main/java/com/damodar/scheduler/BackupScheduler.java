package com.damodar.scheduler;

import com.damodar.config.DamodarConfig;
import com.damodar.backup.BackupService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

public final class BackupScheduler {

    private final Plugin plugin;
    private final Logger logger;
    private volatile DamodarConfig config;
    private final BackupService backupService;
    private final SchedulerStateStore schedulerStateStore;

    private BukkitTask scheduledBackupTask = null;
    private BukkitTask startupBackupTask = null;

    private volatile boolean scheduleRunning = false;
    private volatile ZonedDateTime currentTargetSlot = null;
    private volatile Instant nextScheduledRun = null;

    public BackupScheduler(
            Plugin plugin,
            Logger logger,
            DamodarConfig config,
            BackupService backupService,
            SchedulerStateStore schedulerStateStore
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.config = config;
        this.backupService = backupService;
        this.schedulerStateStore = schedulerStateStore;
    }

    public BackupScheduler(
            Plugin plugin,
            Logger logger,
            DamodarConfig config,
            BackupService backupService
    ) {
        this(plugin, logger, config, backupService, null);
    }

    public synchronized void start(boolean scheduleEnabled) {
        cancel();

        if (!scheduleEnabled || !config.isScheduleEnabled()) {
            logger.info("[Damodara] Automated periodic and catch-up backups are disabled in config.yml.");
            return;
        }

        startSchedule();
    }

    public synchronized void onConfigReload(DamodarConfig newConfig, boolean scheduleEnabled) {
        this.config = newConfig;
        cancel();
        if (newConfig.isScheduleEnabled()) {
            start(true);
        } else {
            logger.info("[Damodara] Automated periodic and catch-up backups are disabled in config.yml.");
        }
    }

    public synchronized void onConfigReload(DamodarConfig newConfig) {
        onConfigReload(newConfig, newConfig.isScheduleEnabled());
    }

    public boolean isScheduleRunning() {
        return scheduleRunning;
    }

    public Instant getNextScheduledRun() {
        return nextScheduledRun;
    }

    public ZonedDateTime getCurrentTargetSlot() {
        return currentTargetSlot;
    }

    public synchronized void cancel() {
        if (startupBackupTask != null && !startupBackupTask.isCancelled()) {
            startupBackupTask.cancel();
            startupBackupTask = null;
        }
        if (scheduledBackupTask != null && !scheduledBackupTask.isCancelled()) {
            scheduledBackupTask.cancel();
            scheduledBackupTask = null;
        }
        scheduleRunning = false;
        nextScheduledRun = null;
        currentTargetSlot = null;
    }

    private synchronized void startSchedule() {
        ZoneId zone = config.getScheduleTimeZone();
        List<LocalTime> times = config.getScheduleTimes();
        int daysInterval = config.getDaysInterval();
        ZonedDateTime now = ZonedDateTime.now(zone);
        long startupDelayTicks = config.getStartupDelaySeconds() * 20L;

        LocalDate anchor = null;
        if (schedulerStateStore != null) {
            anchor = schedulerStateStore.getScheduleAnchorDate();
            if (anchor == null) {
                ZonedDateTime lastHandled = schedulerStateStore.getLastHandledSlot(zone);
                anchor = (lastHandled != null) ? lastHandled.toLocalDate() : now.toLocalDate();
                schedulerStateStore.setScheduleAnchorDate(anchor);
            }
        }

        if (schedulerStateStore != null) {
            ZonedDateTime lastHandled = schedulerStateStore.getLastHandledSlot(zone);

            if (lastHandled != null && config.isCatchUpEnabled()) {
                List<ZonedDateTime> missed = ScheduleSlotCalculator.findMissedSlots(
                        lastHandled, now, times, zone, daysInterval, anchor
                );
                if (!missed.isEmpty()) {
                    logger.info("[Damodara Catch-Up] Detected " + missed.size()
                            + " missed scheduled backup slot(s) while server was offline: "
                            + missed.stream().map(s -> s.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).toList()
                            + ". Scheduling 1 fresh catch-up backup in " + config.getStartupDelaySeconds() + " seconds...");

                    schedulerStateStore.recordSlotHandled(missed.get(missed.size() - 1));

                    startupBackupTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                        logger.info("[Damodara Catch-Up] Executing catch-up backup for missed offline slot(s)...");
                        backupService.performBackupAsync("CATCH_UP_SCHEDULER");
                    }, startupDelayTicks);
                }
            } else if (lastHandled == null) {
                ZonedDateTime mostRecentPast = findMostRecentPastSlot(now, times, zone, daysInterval, anchor);
                schedulerStateStore.recordSlotHandled(mostRecentPast);
            }
        }

        ZonedDateTime nextSlot = ScheduleSlotCalculator.calculateNextSlot(now, times, zone, daysInterval, anchor);
        currentTargetSlot = nextSlot;
        nextScheduledRun = nextSlot.toInstant();

        logger.info("[Damodara Schedule] Automated backup scheduled for "
                + nextSlot.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))
                + " (Cadence: every " + daysInterval + " day(s), Times: " + times + ", Timezone: " + zone.getId() + ")");

        scheduledBackupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!scheduleRunning) return;

            Instant currentInstant = Instant.now();
            if (nextScheduledRun != null && !currentInstant.isBefore(nextScheduledRun)) {
                ZonedDateTime slotToExecute = currentTargetSlot;
                logger.info("[Damodara Schedule] Triggering scheduled backup for slot "
                        + (slotToExecute != null ? slotToExecute.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")) : "now") + "...");

                if (slotToExecute != null && schedulerStateStore != null) {
                    schedulerStateStore.recordSlotHandled(slotToExecute);
                }

                LocalDate currentAnchor = (schedulerStateStore != null)
                        ? schedulerStateStore.getScheduleAnchorDate()
                        : null;

                ZonedDateTime subsequentSlot = ScheduleSlotCalculator.calculateNextSlot(
                        ZonedDateTime.now(config.getScheduleTimeZone()),
                        config.getScheduleTimes(),
                        config.getScheduleTimeZone(),
                        config.getDaysInterval(),
                        currentAnchor
                );
                currentTargetSlot = subsequentSlot;
                nextScheduledRun = subsequentSlot.toInstant();

                logger.info("[Damodara Schedule] Next scheduled backup: "
                        + subsequentSlot.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));

                backupService.performBackupAsync("DAILY_SCHEDULER");
            }
        }, 100L, 100L);

        scheduleRunning = true;
    }

    private ZonedDateTime findMostRecentPastSlot(
            ZonedDateTime now,
            List<LocalTime> times,
            ZoneId zone,
            int daysInterval,
            LocalDate anchorDate
    ) {
        LocalDate today = now.toLocalDate();
        LocalDate yesterday = today.minusDays(1);

        if (daysInterval > 1 && anchorDate != null) {
            LocalDate check = today;
            for (int i = 0; i <= daysInterval + 1; i++) {
                if (ScheduleSlotCalculator.isScheduledDay(check, anchorDate, daysInterval)) {
                    ZonedDateTime cand = check.atTime(times.get(0)).atZone(zone);
                    if (!cand.isAfter(now)) {
                        return cand;
                    }
                }
                check = check.minusDays(1);
            }
        }

        List<ZonedDateTime> candidates = new java.util.ArrayList<>();
        for (LocalTime t : times) {
            ZonedDateTime candToday = today.atTime(t).atZone(zone);
            if (!candToday.isAfter(now)) {
                candidates.add(candToday);
            }
            candidates.add(yesterday.atTime(t).atZone(zone));
        }

        return candidates.stream()
                .max(Comparator.comparing(ZonedDateTime::toInstant))
                .orElse(yesterday.atTime(times.get(0)).atZone(zone));
    }
}
