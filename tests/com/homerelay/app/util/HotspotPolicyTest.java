package com.homerelay.app.util;

import com.homerelay.app.Test;

import java.util.Arrays;
import java.util.List;

/** Regression tests for the hotspot permission policy (see HotspotPolicy). */
public final class HotspotPolicyTest {

    /* ---------- Android 12 (M01 target): coarse/fine location ---------- */

    @Test
    public void android12_nothingGranted_hotspotBlocked() {
        assertFalse(HotspotPolicy.hotspotPermissionGranted(31, false, false, false));
        assertEquals(
            Arrays.asList(HotspotPolicy.FINE_LOCATION, HotspotPolicy.COARSE_LOCATION),
            HotspotPolicy.missingHotspotPermissions(31, false, false, false)
        );
    }

    @Test
    public void android12_fineGranted_hotspotAllowed() {
        assertTrue(HotspotPolicy.hotspotPermissionGranted(31, true, false, false));
        assertTrue(HotspotPolicy.missingHotspotPermissions(31, true, false, false).isEmpty());
    }

    @Test
    public void android12_coarseOnly_hotspotAllowed() {
        // System refusal text on the M01 names "coarse/fine" — coarse satisfies the check.
        assertTrue(HotspotPolicy.hotspotPermissionGranted(31, false, true, false));
        // But we still ask for fine so the dialog presents the precise permission named in the docs.
        assertEquals(
            List.of(HotspotPolicy.FINE_LOCATION),
            HotspotPolicy.missingHotspotPermissions(31, false, true, false)
        );
    }

    @Test
    public void android11_treatedLikeAndroid12() {
        assertFalse(HotspotPolicy.hotspotPermissionGranted(30, false, false, false));
        assertTrue(HotspotPolicy.hotspotPermissionGranted(30, true, false, false));
        assertEquals(
            Arrays.asList(HotspotPolicy.FINE_LOCATION, HotspotPolicy.COARSE_LOCATION),
            HotspotPolicy.missingHotspotPermissions(30, false, false, false)
        );
    }

    @Test
    public void android12_nearbyPermissionIgnored() {
        // NEARBY_WIFI_DEVICES does not exist before 33; granting it changes nothing.
        assertFalse(HotspotPolicy.hotspotPermissionGranted(31, false, false, true));
        assertFalse(HotspotPolicy.missingHotspotPermissions(31, false, false, false).isEmpty());
    }

    /* ---------- Android 13+ : NEARBY_WIFI_DEVICES ---------- */

    @Test
    public void android13_nearbyGranted_hotspotAllowed() {
        assertTrue(HotspotPolicy.hotspotPermissionGranted(33, false, false, true));
        assertTrue(HotspotPolicy.missingHotspotPermissions(33, false, false, true).isEmpty());
    }

    @Test
    public void android13_nearbyMissing_hotspotBlocked_evenWithLocation() {
        assertFalse(HotspotPolicy.hotspotPermissionGranted(33, true, true, false));
        assertEquals(
            List.of(HotspotPolicy.NEARBY_WIFI_DEVICES),
            HotspotPolicy.missingHotspotPermissions(33, true, true, false)
        );
    }

    @Test
    public void android17_nearbyMissing_hotspotBlocked() {
        assertFalse(HotspotPolicy.hotspotPermissionGranted(37, true, true, false));
        assertEquals(
            List.of(HotspotPolicy.NEARBY_WIFI_DEVICES),
            HotspotPolicy.missingHotspotPermissions(37, true, true, false)
        );
    }

    /* ---------- Notification permission ---------- */

    @Test
    public void notifications_requiredOnlyOnAndroid13Plus() {
        assertTrue(HotspotPolicy.notificationPermissionSatisfied(31, false));
        assertTrue(HotspotPolicy.notificationPermissionSatisfied(32, false));
        assertFalse(HotspotPolicy.notificationPermissionSatisfied(33, false));
        assertTrue(HotspotPolicy.notificationPermissionSatisfied(33, true));
        assertFalse(HotspotPolicy.notificationPermissionSatisfied(37, false));
        assertTrue(HotspotPolicy.notificationPermissionSatisfied(37, true));
    }

    /* ---------- helpers ---------- */

    private static void assertTrue(boolean v) {
        if (!v) throw new AssertionError("expected true");
    }

    private static void assertFalse(boolean v) {
        if (v) throw new AssertionError("expected false");
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
