package com.homerelay.app.util;

import com.homerelay.app.Test;

/** Regression tests for Wi-Fi Direct group credential generation and validation. */
public final class P2pCredentialsTest {

    @Test
    public void generatedNetworkNameHasDirectPrefixAndValidAlphabet() {
        for (int i = 0; i < 200; i++) {
            P2pCredentials.Credentials c = P2pCredentials.generate();
            assertTrue("network name must start with DIRECT-", c.networkName.startsWith("DIRECT-"));
            assertTrue("network name too long: " + c.networkName, c.networkName.length() <= 32);
            assertTrue("network name invalid: " + c.networkName, P2pCredentials.isValidNetworkName(c.networkName));
        }
    }

    @Test
    public void generatedPassphraseIs8To63Ascii() {
        for (int i = 0; i < 200; i++) {
            P2pCredentials.Credentials c = P2pCredentials.generate();
            assertTrue("passphrase length", c.passphrase.length() >= 8 && c.passphrase.length() <= 63);
            for (int j = 0; j < c.passphrase.length(); j++) {
                char ch = c.passphrase.charAt(j);
                assertTrue("passphrase ASCII", ch >= 0x21 && ch <= 0x7E);
            }
            assertTrue(P2pCredentials.isValidPassphrase(c.passphrase));
        }
    }

    @Test
    public void generatedCredentialsAreUniquePerCall() {
        P2pCredentials.Credentials a = P2pCredentials.generate();
        P2pCredentials.Credentials b = P2pCredentials.generate();
        assertTrue("names should differ", !a.networkName.equals(b.networkName) || !a.passphrase.equals(b.passphrase));
    }

    @Test
    public void networkNameValidationRejectsBadInputs() {
        assertFalse(P2pCredentials.isValidNetworkName(null));
        assertFalse(P2pCredentials.isValidNetworkName(""));
        assertFalse(P2pCredentials.isValidNetworkName("DIRECT-"));          // no xy suffix
        assertFalse(P2pCredentials.isValidNetworkName("DIRECT-x"));          // too short
        assertFalse(P2pCredentials.isValidNetworkName("direct-A1B2C3"));     // wrong case prefix
        assertFalse(P2pCredentials.isValidNetworkName("DIRECT-A1 B2"));      // space
        assertFalse(P2pCredentials.isValidNetworkName("DIRECT-A1!B2"));      // punctuation
        assertFalse(P2pCredentials.isValidNetworkName(
            "DIRECT-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcde"));           // > 32 chars
        assertTrue(P2pCredentials.isValidNetworkName("DIRECT-K7q2R9"));
        assertTrue(P2pCredentials.isValidNetworkName("DIRECT-ab"));
    }

    @Test
    public void passphraseValidationMatchesWpa2Bounds() {
        assertFalse(P2pCredentials.isValidPassphrase(null));
        assertFalse(P2pCredentials.isValidPassphrase("short"));
        assertFalse(P2pCredentials.isValidPassphrase("1234567"));
        assertTrue(P2pCredentials.isValidPassphrase("12345678"));
        StringBuffer sixtyThree = new StringBuffer();
        for (int i = 0; i < 63; i++) sixtyThree.append('a');
        assertTrue(P2pCredentials.isValidPassphrase(sixtyThree.toString()));
        assertFalse(P2pCredentials.isValidPassphrase(sixtyThree.toString() + "a"));
        assertFalse(P2pCredentials.isValidPassphrase("pässwörd!!"));         // non-ASCII
        assertFalse(P2pCredentials.isValidPassphrase("has\ttab inside"));    // control char
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
}
