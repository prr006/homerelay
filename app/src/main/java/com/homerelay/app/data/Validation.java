package com.homerelay.app.data;

/**
 * Pure, dependency-free input validation. Kept separate from Android so it can
 * be unit-tested on a plain JVM.
 */
public final class Validation {
    public static final int MIN_PROXY_PORT = 1024;
    public static final int MAX_PROXY_PORT = 65535;

    private Validation() {}

    /** Proxy port must be unprivileged and fit in 16 bits. */
    public static boolean isValidProxyPort(int port) {
        return port >= MIN_PROXY_PORT && port <= MAX_PROXY_PORT;
    }

    /** Parse a user-entered port; returns -1 when invalid. */
    public static int parseProxyPort(String text) {
        if (text == null) return -1;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 5) return -1;
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) return -1;
        }
        try {
            int p = Integer.parseInt(t);
            return isValidProxyPort(p) ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Strict dotted-quad IPv4 validation. */
    public static boolean isValidIpv4(String ip) {
        if (ip == null) return false;
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return false;
            }
            // Reject leading zeros other than "0" itself (ambiguous).
            if (part.length() > 1 && part.charAt(0) == '0') return false;
            int v;
            try { v = Integer.parseInt(part); } catch (NumberFormatException e) { return false; }
            if (v > 255) return false;
        }
        return true;
    }

    /** Ports a proxy binds to must additionally avoid the well-known local ports. */
    public static boolean isSafeServicePort(int port) {
        return isValidProxyPort(port) && port != 5353 /* mDNS */;
    }
}
