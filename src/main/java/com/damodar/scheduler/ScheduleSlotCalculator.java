package com.damodar.scheduler;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

public final class ScheduleSlotCalculator {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private ScheduleSlotCalculator() {}

    public static List<LocalTime> parseTimes(List<String> rawTimes, Logger logger) {
        if (rawTimes == null || rawTimes.isEmpty()) {
            return List.of(LocalTime.of(0, 0));
        }

        List<LocalTime> parsed = new ArrayList<>();
        for (String raw : rawTimes) {
            if (raw == null || raw.isBlank()) continue;
            String trimmed = raw.trim();
            try {
                LocalTime time = LocalTime.parse(trimmed, TIME_FORMATTER);
                if (!parsed.contains(time)) {
                    parsed.add(time);
                }
            } catch (DateTimeParseException e) {
                if (logger != null) {
                    logger.warning("[Damodara] Invalid schedule time '" + raw
                            + "' in config.yml (expected 24-hour HH:mm, e.g. '00:00'). Ignoring invalid time.");
                }
            }
        }

        if (parsed.isEmpty()) {
            if (logger != null) {
                logger.warning("[Damodara] No valid times found under schedule.times. Falling back to default ['00:00'].");
            }
            return List.of(LocalTime.of(0, 0));
        }

        Collections.sort(parsed);
        return Collections.unmodifiableList(parsed);
    }

    public static ZoneId parseTimeZone(String rawTimezone, Logger logger) {
        if (rawTimezone == null || rawTimezone.isBlank() || "SYSTEM".equalsIgnoreCase(rawTimezone.trim())) {
            return ZoneId.systemDefault();
        }

        String trimmed = rawTimezone.trim();
        try {
            return ZoneId.of(trimmed);
        } catch (DateTimeException e) {
            ZoneId fallback = ZoneId.systemDefault();
            if (logger != null) {
                logger.warning("[Damodara] Invalid timezone '" + rawTimezone
                        + "' in config.yml. Falling back to server system timezone (" + fallback.getId() + ").");
            }
            return fallback;
        }
    }

    public static boolean isScheduledDay(LocalDate date, LocalDate anchorDate, int daysInterval) {
        if (daysInterval <= 1 || anchorDate == null) {
            return true;
        }
        long diff = ChronoUnit.DAYS.between(anchorDate, date);
        return ((diff % daysInterval) + daysInterval) % daysInterval == 0;
    }

    public static ZonedDateTime calculateNextSlot(
            ZonedDateTime fromZdt,
            List<LocalTime> times,
            ZoneId zoneId,
            int daysInterval,
            LocalDate anchorDate
    ) {
        if (times == null || times.isEmpty()) {
            times = List.of(LocalTime.of(0, 0));
        }

        ZonedDateTime localFrom = fromZdt.withZoneSameInstant(zoneId);
        LocalDate today = localFrom.toLocalDate();

        if (daysInterval <= 1) {
            List<ZonedDateTime> candidates = new ArrayList<>();
            for (LocalTime t : times) {
                ZonedDateTime candidate = today.atTime(t).atZone(zoneId);
                if (candidate.isAfter(localFrom)) {
                    candidates.add(candidate);
                }
            }

            LocalDate tomorrow = today.plusDays(1);
            for (LocalTime t : times) {
                ZonedDateTime candidate = tomorrow.atTime(t).atZone(zoneId);
                candidates.add(candidate);
            }

            return candidates.stream()
                    .min(Comparator.comparing(ZonedDateTime::toInstant))
                    .orElse(tomorrow.atTime(times.get(0)).atZone(zoneId));
        }

        LocalTime fixedTime = times.get(0);
        LocalDate effectiveAnchor = anchorDate != null ? anchorDate : today;

        if (isScheduledDay(today, effectiveAnchor, daysInterval)) {
            ZonedDateTime candidate = today.atTime(fixedTime).atZone(zoneId);
            if (candidate.isAfter(localFrom)) {
                return candidate;
            }
        }

        long diff = ChronoUnit.DAYS.between(effectiveAnchor, today);
        long rem = ((diff % daysInterval) + daysInterval) % daysInterval;
        long daysToAdd = (rem == 0) ? daysInterval : (daysInterval - rem);
        LocalDate nextDate = today.plusDays(daysToAdd);
        return nextDate.atTime(fixedTime).atZone(zoneId);
    }

    public static ZonedDateTime calculateNextSlot(ZonedDateTime fromZdt, List<LocalTime> times, ZoneId zoneId) {
        return calculateNextSlot(fromZdt, times, zoneId, 1, null);
    }

    public static List<ZonedDateTime> findMissedSlots(
            ZonedDateTime lastHandled,
            ZonedDateTime now,
            List<LocalTime> times,
            ZoneId zoneId,
            int daysInterval,
            LocalDate anchorDate
    ) {
        if (lastHandled == null || !now.isAfter(lastHandled)) {
            return List.of();
        }

        if (times == null || times.isEmpty()) {
            times = List.of(LocalTime.of(0, 0));
        }

        ZonedDateTime startLocal = lastHandled.withZoneSameInstant(zoneId);
        ZonedDateTime nowLocal = now.withZoneSameInstant(zoneId);

        List<ZonedDateTime> missed = new ArrayList<>();
        LocalDate currentDate = startLocal.toLocalDate();
        LocalDate endDate = nowLocal.toLocalDate();

        while (!currentDate.isAfter(endDate)) {
            if (daysInterval <= 1 || anchorDate == null || isScheduledDay(currentDate, anchorDate, daysInterval)) {
                for (LocalTime t : times) {
                    ZonedDateTime candidate = currentDate.atTime(t).atZone(zoneId);
                    if (candidate.isAfter(startLocal) && !candidate.isAfter(nowLocal)) {
                        missed.add(candidate);
                    }
                }
            }
            currentDate = currentDate.plusDays(1);
        }

        missed.sort(Comparator.comparing(ZonedDateTime::toInstant));
        return Collections.unmodifiableList(missed);
    }

    public static List<ZonedDateTime> findMissedSlots(
            ZonedDateTime lastHandled,
            ZonedDateTime now,
            List<LocalTime> times,
            ZoneId zoneId
    ) {
        return findMissedSlots(lastHandled, now, times, zoneId, 1, null);
    }
}
