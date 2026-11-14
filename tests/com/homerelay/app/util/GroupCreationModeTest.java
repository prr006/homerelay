package com.homerelay.app.util;

import com.homerelay.app.Test;

public final class GroupCreationModeTest {

    @Test
    public void defaultModeIsModeA() {
        assertEquals(GroupCreationMode.MODE_A_CUSTOM_24GHZ, GroupCreationMode.DEFAULT);
        assertEquals(GroupCreationMode.MODE_A_CUSTOM_24GHZ, GroupCreationMode.fromStorage(null));
    }

    @Test
    public void storageRoundTrip() {
        for (GroupCreationMode m : GroupCreationMode.values()) {
            assertEquals(m, GroupCreationMode.fromStorage(m.storageKey()));
        }
    }

    @Test
    public void garbageStorageFallsBackToDefault() {
        assertEquals(GroupCreationMode.DEFAULT, GroupCreationMode.fromStorage(""));
        assertEquals(GroupCreationMode.DEFAULT, GroupCreationMode.fromStorage("not-a-mode"));
        assertEquals(GroupCreationMode.DEFAULT, GroupCreationMode.fromStorage("MODE_A_CUSTOM_24GHZ")); // wrong case/name
    }

    @Test
    public void modeSemantics() {
        assertTrue("A uses custom creds", GroupCreationMode.MODE_A_CUSTOM_24GHZ.usesCustomCredentials());
        assertTrue("A forces 2.4 GHz", GroupCreationMode.MODE_A_CUSTOM_24GHZ.forcesBand2ghz());
        assertTrue("C uses custom creds", GroupCreationMode.MODE_C_CUSTOM_DEFAULT_BAND.usesCustomCredentials());
        assertFalse("C default band", GroupCreationMode.MODE_C_CUSTOM_DEFAULT_BAND.forcesBand2ghz());
        assertFalse("B is system-managed", GroupCreationMode.MODE_B_SYSTEM_MANAGED.usesCustomCredentials());
        assertFalse("B does not force band", GroupCreationMode.MODE_B_SYSTEM_MANAGED.forcesBand2ghz());
    }

    @Test
    public void labelsNeverContainCredentials() {
        for (GroupCreationMode m : GroupCreationMode.values()) {
            assertFalse(m.label().contains("password"));
            assertFalse(m.description().toLowerCase().contains("passphrase ="));
            assertTrue(m.describe().contains(m.storageKey()));
        }
    }

    private static void assertTrue(String msg, boolean v) { if (!v) throw new AssertionError(msg); }
    private static void assertTrue(boolean v) { if (!v) throw new AssertionError("expected true"); }
    private static void assertFalse(String msg, boolean v) { if (v) throw new AssertionError(msg); }
    private static void assertFalse(boolean v) { if (v) throw new AssertionError("expected false"); }
    private static void assertEquals(Object e, Object a) {
        if (e == null ? a != null : !e.equals(a)) throw new AssertionError("expected " + e + " but was " + a);
    }
}
