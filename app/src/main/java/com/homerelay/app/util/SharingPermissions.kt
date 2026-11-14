package com.homerelay.app.util;

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Runtime permission state for the Wi-Fi Direct sharing path, backed by [HotspotPolicy].
 * The policy (what is required) lives in Java next to the test suite;
 * this object only knows how to ask the OS.
 *
 * Wi-Fi Direct uses the same OS permission requirements as the local-only hotspot:
 * location on Android ≤ 12, NEARBY_WIFI_DEVICES (neverForLocation) on 13+.
 */
object SharingPermissions {

    fun hasHotspotPermission(context: Context): Boolean = HotspotPolicy.hotspotPermissionGranted(
        Build.VERSION.SDK_INT,
        has(context, Manifest.permission.ACCESS_FINE_LOCATION),
        has(context, Manifest.permission.ACCESS_COARSE_LOCATION),
        nearbyWifiGranted(context),
    )

    /** Permissions still to request at runtime to unlock the group on this device. */
    fun missingHotspotPermissions(context: Context): List<String> =
        HotspotPolicy.missingHotspotPermissions(
            Build.VERSION.SDK_INT,
            has(context, Manifest.permission.ACCESS_FINE_LOCATION),
            has(context, Manifest.permission.ACCESS_COARSE_LOCATION),
            nearbyWifiGranted(context),
        )

    /** True once the user may use the persistent notification (Android 13+). */
    fun hasNotificationPermission(context: Context): Boolean =
        HotspotPolicy.notificationPermissionSatisfied(
            Build.VERSION.SDK_INT,
            Build.VERSION.SDK_INT >= 33 && has(context, Manifest.permission.POST_NOTIFICATIONS),
        )

    fun has(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** NEARBY_WIFI_DEVICES only exists on Android 13+; never probe for it before then. */
    private fun nearbyWifiGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && has(context, Manifest.permission.NEARBY_WIFI_DEVICES)
}
