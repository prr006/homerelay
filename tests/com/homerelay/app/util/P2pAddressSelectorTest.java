package com.homerelay.app.util;

import com.homerelay.app.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Regression tests for local P2P (Group Owner) IPv4 selection.
 *
 * Why this exists: WifiP2pInfo.groupOwnerAddress is anonymised for Group Owners
 * (0.0.0.0 without the privileged LOCAL_MAC_ADDRESS permission). The proxy must bind
 * to the address actually assigned to the P2P interface — or fail, never guess.
 */
public final class P2pAddressSelectorTest {

    private static InetAddress ip(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<InetAddress> list(String... addrs) {
        InetAddress[] out = new InetAddress[addrs.length];
        for (int i = 0; i < addrs.length; i++) out[i] = ip(addrs[i]);
        return Arrays.asList(out);
    }

    /* ---------- usability filter ---------- */

    @Test
    public void loopbackIsNotUsable() {
        assertFalse(P2pAddressSelector.isUsableIpv4(ip("127.0.0.1")));
    }

    @Test
    public void wildcardIsNotUsable() {
        // Exactly what Android reports for groupOwnerAddress to a Group Owner app.
        assertFalse(P2pAddressSelector.isUsableIpv4(ip("0.0.0.0")));
    }

    @Test
    public void multicastIsNotUsable() {
        assertFalse(P2pAddressSelector.isUsableIpv4(ip("224.0.0.251")));
        assertFalse(P2pAddressSelector.isUsableIpv4(ip("239.255.255.250")));
    }

    @Test
    public void linkLocalIsNotUsable() {
        assertFalse(P2pAddressSelector.isUsableIpv4(ip("169.254.1.5")));
        assertFalse(P2pAddressSelector.isUsableIpv4(ip("169.254.0.1")));
    }

    @Test
    public void siteLocalIsUsable() {
        assertTrue(P2pAddressSelector.isUsableIpv4(ip("192.168.49.1")));
        assertTrue(P2pAddressSelector.isUsableIpv4(ip("10.0.0.5")));
        assertTrue(P2pAddressSelector.isUsableIpv4(ip("172.16.3.4")));
    }

    @Test
    public void ipv6IsNotUsable() {
        assertFalse(P2pAddressSelector.isUsableIpv4(ip("fe80::1")));
        assertFalse(P2pAddressSelector.isUsableIpv4(ip("::1"))); // IPv6 loopback — rejected with all non-IPv4
    }

    /* ---------- selection ---------- */

    @Test
    public void typicalGoAssignmentIsSelected() {
        // The address Android assigns to the p2p interface on a GO.
        InetAddress chosen = P2pAddressSelector.selectLocalIpv4(list("192.168.49.1"));
        assertEquals("192.168.49.1", chosen.getHostAddress());
    }

    @Test
    public void siteLocalPreferredOverOtherUsable() {
        InetAddress chosen = P2pAddressSelector.selectLocalIpv4(list("8.8.8.8", "192.168.49.1"));
        assertEquals("192.168.49.1", chosen.getHostAddress());
    }

    @Test
    public void mixedGarbageAndOneUsable_picksTheUsableOne() {
        InetAddress chosen = P2pAddressSelector.selectLocalIpv4(
            list("0.0.0.0", "127.0.0.1", "169.254.3.3", "224.0.0.1", "192.168.49.1"));
        assertEquals("192.168.49.1", chosen.getHostAddress());
    }

    @Test
    public void noUsableAddress_returnsNull() {
        assertEquals(null, P2pAddressSelector.selectLocalIpv4(list("0.0.0.0", "127.0.0.1", "169.254.1.1")));
        assertEquals(null, P2pAddressSelector.selectLocalIpv4(list("::1", "fe80::2")));
        assertEquals(null, P2pAddressSelector.selectLocalIpv4(Collections.emptyList()));
    }

    @Test
    public void globalUsableAcceptedWhenNoSiteLocal() {
        InetAddress chosen = P2pAddressSelector.selectLocalIpv4(list("203.0.113.7"));
        assertEquals("203.0.113.7", chosen.getHostAddress());
    }

    /* ---------- describe ---------- */

    @Test
    public void describeContainsInterfaceAndAddressAndClass() {
        String d = P2pAddressSelector.describe("p2p0", list("192.168.49.1", "0.0.0.0"));
        assertTrue("contains interface name", d.contains("p2p0"));
        assertTrue("contains address", d.contains("192.168.49.1"));
        assertTrue("contains class", d.contains("site-local"));
        assertTrue("contains wildcard class", d.contains("wildcard"));
    }

    @Test
    public void describeHandlesEmptyAndNull() {
        assertTrue(P2pAddressSelector.describe("p2p0", Collections.emptyList()).contains("no addresses"));
        assertTrue(P2pAddressSelector.describe(null, null).contains("<unknown interface>"));
    }

    private static void assertTrue(String msg, boolean v) {
        if (!v) throw new AssertionError(msg);
    }

    private static void assertTrue(boolean v) {
        if (!v) throw new AssertionError("expected true");
    }

    private static void assertFalse(boolean v) {
        if (v) throw new AssertionError("expected false");
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
