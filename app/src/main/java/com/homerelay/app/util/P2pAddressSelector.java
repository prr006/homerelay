package com.homerelay.app.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;

/**
 * Pure, framework-free selection of the local IPv4 address of this device's P2P (Group
 * Owner) interface. Exists because Android anonymizes {@code WifiP2pInfo.groupOwnerAddress}
 * for the calling app when it is the Group Owner (it reports 0.0.0.0 unless the caller holds
 * the privileged LOCAL_MAC_ADDRESS permission, which HomeRelay does not and must not use).
 *
 * <p>Rules:
 * <ul>
 *   <li>Only IPv4 addresses qualify (Inet6Address is rejected).</li>
 *   <li>Loopback, wildcard (0.0.0.0), multicast and link-local (169.254/16) are ignored.</li>
 *   <li>Site-local addresses (10/8, 172.16/12, 192.168/16 — i.e. the expected 192.168.49.x)
 *       are preferred; otherwise any remaining usable IPv4 is accepted.</li>
 *   <li>No usable address → {@code null}. Callers must fail, never guess.</li>
 * </ul>
 */
public final class P2pAddressSelector {
    private P2pAddressSelector() {}

    public static boolean isUsableIpv4(InetAddress a) {
        if (!(a instanceof Inet4Address)) return false;
        if (a.isLoopbackAddress()) return false;
        if (a.isAnyLocalAddress()) return false;
        if (a.isMulticastAddress()) return false;
        if (isLinkLocal((Inet4Address) a)) return false;
        return true;
    }

    public static boolean isLinkLocal(Inet4Address a) {
        byte[] b = a.getAddress();
        return (b[0] & 0xff) == 169 && (b[1] & 0xff) == 254;
    }

    /** Pick the local IPv4 for the group interface, or {@code null}. Site-local wins. */
    public static Inet4Address selectLocalIpv4(List<InetAddress> addresses) {
        Inet4Address siteLocal = null, other = null;
        for (InetAddress a : addresses) {
            if (!isUsableIpv4(a)) continue;
            Inet4Address a4 = (Inet4Address) a;
            if (a4.isSiteLocalAddress()) {
                if (siteLocal == null) siteLocal = a4;
            } else if (other == null) {
                other = a4;
            }
        }
        return siteLocal != null ? siteLocal : other;
    }

    /** Human-readable classification for diagnostics ("site-local", "link-local", …). */
    public static String classify(InetAddress a) {
        if (a == null) return "null";
        if (!(a instanceof Inet4Address)) return "non-IPv4";
        if (a.isLoopbackAddress()) return "loopback";
        if (a.isAnyLocalAddress()) return "wildcard";
        if (a.isMulticastAddress()) return "multicast";
        if (isLinkLocal((Inet4Address) a)) return "link-local";
        if (a.isSiteLocalAddress()) return "site-local";
        return "global";
    }

    /** "p2p0 [192.168.49.1 (site-local)]"-style description for failure messages. */
    public static String describe(String interfaceName, List<InetAddress> addresses) {
        StringBuilder sb = new StringBuilder();
        sb.append(interfaceName == null ? "<unknown interface>" : interfaceName).append(" [");
        if (addresses == null || addresses.isEmpty()) {
            sb.append("no addresses");
        } else {
            for (int i = 0; i < addresses.size(); i++) {
                if (i > 0) sb.append(", ");
                InetAddress a = addresses.get(i);
                if (a == null) { sb.append("<null>"); continue; }
                sb.append(a.getHostAddress()).append(" (").append(classify(a)).append(")");
            }
        }
        return sb.append("]").toString();
    }
}
