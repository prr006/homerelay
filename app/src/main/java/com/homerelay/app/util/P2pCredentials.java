package com.homerelay.app.util;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Wi-Fi Direct group credentials, generated per session. Framework-free and testable.
 *
 * <p>Rules honoured (from {@code WifiP2pConfig.Builder}):
 * <ul>
 *   <li>networkName must begin with "DIRECT-xy" where x and y are [A-Za-z0-9]; trailing
 *       bytes must be valid SSID bytes. We emit "DIRECT-" + 2 chars from the same set +
 *       4 more chars of the same alphabet, e.g. "DIRECT-K7q2R9" (14 chars total).
 *   <li>passphrase must be 8–63 ASCII chars (WPA2-Personal). We emit 16 chars from
 *       [A-Za-z0-9].
 *   <li>Credentials are fresh per session (never reused, never persisted).
 * </ul>
 */
public final class P2pCredentials {
    private P2pCredentials() {}

    private static final char[] SSID_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final char[] PASSPHRASE_ALPHABET = SSID_ALPHABET;
    private static final SecureRandom RNG = new SecureRandom();

    public static final String PREFIX = "DIRECT-";
    public static final int PASSPHRASE_LENGTH = 16;
    public static final int NETWORK_NAME_LENGTH = 13; // "DIRECT-" + 6

    public static final class Credentials {
        public final String networkName;
        public final String passphrase;
        public Credentials(String networkName, String passphrase) {
            this.networkName = networkName;
            this.passphrase = passphrase;
        }
    }

    public static Credentials generate() {
        return new Credentials(randomFrom(SSID_ALPHABET, NETWORK_NAME_LENGTH - PREFIX.length(), PREFIX),
                               randomFrom(PASSPHRASE_ALPHABET, PASSPHRASE_LENGTH, ""));
    }

    private static String randomFrom(char[] alphabet, int length, String prefix) {
        StringBuilder sb = new StringBuilder(prefix).ensureCapacity(prefix.length() + length);
        for (int i = 0; i < length; i++) sb.append(alphabet[RNG.nextInt(alphabet.length)]);
        return sb.toString();
    }

    public static boolean isValidNetworkName(String name) {
        if (name == null || !name.startsWith(PREFIX)) return false;
        String rest = name.substring(PREFIX.length());
        if (rest.length() < 2 || name.length() > 32) return false;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c >= 128 || SSID_ALPHABET_SET_INDEX[c] < 0) return false;
        }
        return true;
    }

    // On-by-char index into SSID_ALPHABET, -1 if not in the set. Computed once.
    private static final int[] SSID_ALPHABET_SET_INDEX = buildIndex();

    private static int[] buildIndex() {
        int[] idx = new int[128];
        Arrays.fill(idx, -1);
        for (int i = 0; i < SSID_ALPHABET.length && i < 128; i++) idx[SSID_ALPHABET[i]] = i;
        return idx;
    }

    public static boolean isValidPassphrase(String pass) {
        if (pass == null) return false;
        int len = pass.length();
        if (len < 8 || len > 63) return false;
        for (int i = 0; i < len; i++) {
            char c = pass.charAt(i);
            if (c < 0x20 || c > 0x7E) return false; // printable ASCII only
        }
        return true;
    }

}
