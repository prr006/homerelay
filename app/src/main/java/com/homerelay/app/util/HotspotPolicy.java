package com.homerelay.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure, framework-free permission policy for the local-only hotspot path.
 *
 * Why this exists: on Android 12 (and below) the system refuses
 * {@code WifiManager.startLocalOnlyHotspot()} unless the caller holds a location permission
 * (the system error names "coarse/fine location"). On Android 13+ the location requirement was
 * replaced by NEARBY_WIFI_DEVICES with {@code neverForLocation}. This class encodes exactly that
 * mapping so the runtime flow, the service pre-check and the diagnostics screen all agree —
 * and so the behaviour is regression-testable on the JVM.
 *
 * <p>Canonical sources: Android developer documentation for
 * {@code WifiManager.startLocalOnlyHotspot} and the runtime permissions table for Wi-Fi APIs.
 */
public final class HotspotPolicy {
    private HotspotPolicy() {}

    public static final String FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";
    public static final String COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION";
    public static final String NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES";
    public static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    /** SDK at which NEARBY_WIFI_DEVICES replaced the location requirement (Android 13). */
    public static final int SDK_NEARBY_WIFI = 33;

    /**
     * True iff the hotspot may be requested on this SDK level.
     * Android ≤ 12: coarse OR fine location — matching the exact text of the
     * system refusal message ("doesn't have coarse/fine location permission").
     * Android 13+: NEARBY_WIFI_DEVICES.
     */
    public static boolean hotspotPermissionGranted(int sdkInt,
                                                   boolean fineGranted,
                                                   boolean coarseGranted,
                                                   boolean nearbyWifiGranted) {
        if (sdkInt >= SDK_NEARBY_WIFI) return nearbyWifiGranted;
        return fineGranted || coarseGranted;
    }

    /**
     * Permissions that still need to be requested at runtime to unlock the hotspot.
     * Returns an empty list when the hotspot may be requested now.
     * Android ≤ 12: requests fine location (and coarse as fallback) — the system dialog only
     * ever needs to appear once. Android 13+: requests NEARBY_WIFI_DEVICES only.
     */
    public static List<String> missingHotspotPermissions(int sdkInt,
                                                         boolean fineGranted,
                                                         boolean coarseGranted,
                                                         boolean nearbyWifiGranted) {
        List<String> missing = new ArrayList<>();
        if (sdkInt >= SDK_NEARBY_WIFI) {
            if (!nearbyWifiGranted) missing.add(NEARBY_WIFI_DEVICES);
        } else if (!fineGranted && !coarseGranted) {
            missing.add(FINE_LOCATION);
            missing.add(COARSE_LOCATION);
        } else if (!fineGranted) {
            // Coarse alone satisfies the system check; still ask for fine because it is what
            // the platform docs and the system error message name first.
            missing.add(FINE_LOCATION);
        }
        return Collections.unmodifiableList(missing);
    }

    /** Notification permission only exists (and is only required to be requested) on Android 13+. */
    public static boolean notificationPermissionSatisfied(int sdkInt, boolean postNotificationsGranted) {
        return sdkInt < SDK_NEARBY_WIFI || postNotificationsGranted;
    }
}
