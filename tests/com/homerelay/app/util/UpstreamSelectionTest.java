package com.homerelay.app.util;

import com.homerelay.app.Test;

import java.util.Arrays;
import java.util.List;

import static com.homerelay.app.util.UpstreamSelection.Candidate;
import static com.homerelay.app.util.UpstreamSelection.NONE;

/**
 * Regression tests for upstream network selection.
 *
 * The scenario that matters: the phone has home Wi-Fi (STA) AND the Wi-Fi Direct group
 * network (TRANSPORT_WIFI on interface p2p0) AND maybe cellular. The session must pin
 * the pre-creation home Wi-Fi and never pick the P2P network or cellular.
 */
public final class UpstreamSelectionTest {

    private static Candidate wifi(long id, boolean validated, boolean internet) {
        return new Candidate(id, true, false, false, validated, internet);
    }

    private static Candidate p2p(long id, boolean validated) {
        return new Candidate(id, true, false, true, validated, true);
    }

    private static Candidate cellular(long id) {
        return new Candidate(id, false, true, false, true, true);
    }

    private static final long STA = 100L;
    private static final long P2P = 200L;
    private static final long CEL = 300L;

    /* ---------- eligibility ---------- */

    @Test
    public void p2pNetworkIsNeverEligibleUpstream() {
        assertFalse(UpstreamSelection.isEligibleUpstream(p2p(P2P, true)));
    }

    @Test
    public void cellularIsNeverEligibleUpstream() {
        assertFalse(UpstreamSelection.isEligibleUpstream(cellular(CEL)));
    }

    @Test
    public void homeWifiIsEligible() {
        assertTrue(UpstreamSelection.isEligibleUpstream(wifi(STA, true, true)));
        assertTrue(UpstreamSelection.isEligibleUpstream(wifi(STA, false, true)));
    }

    /* ---------- pinning semantics (the v1.2.0 contract) ---------- */

    @Test
    public void retainedPinnedNetworkWinsEvenIfP2pAndCellularExist() {
        List<Candidate> visible = Arrays.asList(wifi(STA, true, true), p2p(P2P, true), cellular(CEL));
        assertEquals(STA, UpstreamSelection.chooseUpstreamNetwork(visible, STA));
    }

    @Test
    public void retainedPinnedNetworkWinsEvenWhenNotValidatedRightNow() {
        // Validation can lag; we keep the pinned network and report validation separately
        // instead of switching networks mid-session.
        List<Candidate> visible = Arrays.asList(wifi(STA, false, false), p2p(P2P, true));
        assertEquals(STA, UpstreamSelection.chooseUpstreamNetwork(visible, STA));
    }

    @Test
    public void retainedPinnedNetworkWinsEvenWhenAnotherWifiLooksBetter() {
        List<Candidate> visible = Arrays.asList(wifi(STA, false, false), wifi(999L, true, true));
        assertEquals(STA, UpstreamSelection.chooseUpstreamNetwork(visible, STA));
    }

    @Test
    public void pinnedNetworkLost_fallsBackToBestWifiNotP2pOrCellular() {
        List<Candidate> visible = Arrays.asList(p2p(P2P, true), cellular(CEL), wifi(999L, true, true));
        assertEquals(999L, UpstreamSelection.chooseUpstreamNetwork(visible, STA));
    }

    @Test
    public void pinnedNetworkLost_onlyP2pAndCellularVisible_returnsNone() {
        // Hard requirement: no silent fallback to mobile data, never to the P2P group.
        List<Candidate> visible = Arrays.asList(p2p(P2P, true), cellular(CEL));
        assertEquals(NONE, UpstreamSelection.chooseUpstreamNetwork(visible, STA));
    }

    @Test
    public void pinnedNetworkLost_nothingVisible_returnsNone() {
        assertEquals(NONE, UpstreamSelection.chooseUpstreamNetwork(java.util.Collections.emptyList(), STA));
    }

    @Test
    public void noPinYet_homeWifiBeatsNothing_validates() {
        List<Candidate> visible = Arrays.asList(p2p(P2P, true), wifi(STA, true, true));
        assertEquals(STA, UpstreamSelection.chooseUpstreamNetwork(visible, NONE));
    }

    @Test
    public void noPinYet_validatedPreferredOverUnvalidatedWifi() {
        List<Candidate> visible = Arrays.asList(wifi(1L, false, true), wifi(2L, true, true));
        assertEquals(2L, UpstreamSelection.chooseUpstreamNetwork(visible, NONE));
    }

    /* ---------- interface-name heuristic ---------- */

    @Test
    public void p2pAndSoftApInterfaceNamesAreRejected() {
        assertTrue(UpstreamSelection.isNonUpstreamInterface("p2p0"));
        assertTrue(UpstreamSelection.isNonUpstreamInterface("p2p-p2p0"));
        assertTrue(UpstreamSelection.isNonUpstreamInterface("P2P0"));
        assertTrue(UpstreamSelection.isNonUpstreamInterface("softap0"));
        assertTrue(UpstreamSelection.isNonUpstreamInterface("swlan0"));
        assertTrue(UpstreamSelection.isNonUpstreamInterface("ap0"));
        assertFalse(UpstreamSelection.isNonUpstreamInterface("wlan0"));
        assertFalse(UpstreamSelection.isNonUpstreamInterface("wlan1"));
        assertFalse(UpstreamSelection.isNonUpstreamInterface(null));
    }

    /* ---------- describe ---------- */

    @Test
    public void describeReportsValidatedForPinnedNetwork() {
        List<Candidate> visible = Arrays.asList(wifi(STA, true, true));
        assertEquals("Wi-Fi — internet validated", UpstreamSelection.describe(visible, STA));
    }

    @Test
    public void describeReportsMissingWhenPinnedGone() {
        List<Candidate> visible = Arrays.asList(p2p(P2P, true));
        assertEquals("No eligible upstream network", UpstreamSelection.describe(visible, STA));
    }

    /* ---------- tiny assertion helpers (same style as HotspotPolicyTest) ---------- */

    private static void assertTrue(boolean v) {
        if (!v) throw new AssertionError("expected true");
    }

    private static void assertFalse(boolean v) {
        if (v) throw new AssertionError("expected false");
    }

    private static void assertEquals(long expected, long actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + " but was " + actual);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
