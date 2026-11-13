package com.homerelay.app.util;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Locale-stable formatting helpers for stats and diagnostics. Pure Java. */
public final class FormatUtils {
    private FormatUtils() {}

    /** Human readable byte count, e.g. 12.4 MB. Uses US locale digits consistently. */
    public static String bytes(long value) {
        if (value < 0) value = 0;
        double v = value;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (v >= 1024.0 && unit < units.length - 1) {
            v /= 1024.0;
            unit++;
        }
        if (unit == 0) return ((long) v) + " " + units[0];
        return String.format(Locale.US, "%.1f %s", v, units[unit]);
    }

    /** Uptime like "3d 4h", "2h 14m", "45m 12s", "38s". */
    public static String uptime(long millis) {
        if (millis < 0) millis = 0;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (days > 0) return String.format(Locale.US, "%dd %dh", days, hours);
        if (hours > 0) return String.format(Locale.US, "%dh %dm", hours, minutes);
        if (minutes > 0) return String.format(Locale.US, "%dm %ds", minutes, secs);
        return secs + "s";
    }

    /** Shown when a value cannot be obtained from the OS — never invented. */
    public static final String UNAVAILABLE = "Unavailable";
}
