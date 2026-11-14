package com.homerelay.app.util;

import com.homerelay.app.Test;

public final class GroupConfigPlanTest {

    private static P2pCredentials.Credentials creds() {
        return new P2pCredentials.Credentials("DIRECT-K7q2R9", "testpass12345678");
    }

    @Test
    public void modeA_customConfigForcedBand() {
        GroupConfigPlan plan = GroupConfigPlan.forMode(GroupCreationMode.MODE_A_CUSTOM_24GHZ, creds());
        assertTrue(plan.useCustomConfig);
        assertTrue(plan.forceBand2ghz);
        assertEquals(15, plan.groupOwnerIntent);
        assertFalse(plan.persistentMode);
        assertEquals("DIRECT-K7q2R9", plan.networkName);
        assertEquals("testpass12345678", plan.passphrase);
    }

    @Test
    public void modeB_noCustomConfigNoCredentials() {
        GroupConfigPlan plan = GroupConfigPlan.forMode(GroupCreationMode.MODE_B_SYSTEM_MANAGED, creds());
        assertFalse("system-managed must not build a WifiP2pConfig", plan.useCustomConfig);
        assertFalse(plan.forceBand2ghz);
        assertEquals(0, plan.groupOwnerIntent);
        assertFalse(plan.persistentMode);
        assertEquals(null, plan.networkName);
        assertEquals(null, plan.passphrase);
    }

    @Test
    public void modeB_ignoresSuppliedCredentials() {
        // Even if credentials exist, the plan must not leak them into a system-managed group.
        GroupConfigPlan plan = GroupConfigPlan.forMode(GroupCreationMode.MODE_B_SYSTEM_MANAGED,
            new P2pCredentials.Credentials("DIRECT-HACKED", "shouldnotbeused1"));
        assertEquals(null, plan.networkName);
        assertEquals(null, plan.passphrase);
    }

    @Test
    public void modeC_customConfigDefaultBand() {
        GroupConfigPlan plan = GroupConfigPlan.forMode(GroupCreationMode.MODE_C_CUSTOM_DEFAULT_BAND, creds());
        assertTrue(plan.useCustomConfig);
        assertFalse("default band must not force 2.4 GHz", plan.forceBand2ghz);
        assertEquals(15, plan.groupOwnerIntent);
        assertFalse(plan.persistentMode);
        assertEquals("DIRECT-K7q2R9", plan.networkName);
    }

    @Test
    public void nullModeFallsBackToDefault() {
        GroupConfigPlan plan = GroupConfigPlan.forMode(null, creds());
        assertEquals(GroupCreationMode.MODE_A_CUSTOM_24GHZ, plan.mode);
        assertTrue(plan.useCustomConfig);
        assertTrue(plan.forceBand2ghz);
    }

    @Test
    public void customModeRequiresCredentials() {
        boolean threw = false;
        try {
            GroupConfigPlan.forMode(GroupCreationMode.MODE_A_CUSTOM_24GHZ, null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue("custom mode without credentials must throw", threw);
        try {
            GroupConfigPlan.forMode(GroupCreationMode.MODE_C_CUSTOM_DEFAULT_BAND, null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw);
    }

    @Test
    public void describeContainsNoCredentials() {
        GroupConfigPlan a = GroupConfigPlan.forMode(GroupCreationMode.MODE_A_CUSTOM_24GHZ, creds());
        GroupConfigPlan b = GroupConfigPlan.forMode(GroupCreationMode.MODE_B_SYSTEM_MANAGED, creds());
        GroupConfigPlan c = GroupConfigPlan.forMode(GroupCreationMode.MODE_C_CUSTOM_DEFAULT_BAND, creds());
        assertFalse(a.describe().contains("testpass"));
        assertFalse(b.describe().contains("testpass"));
        assertFalse(c.describe().contains("testpass"));
        assertFalse(a.describe().contains("K7q2R9"));
        assertFalse(c.describe().contains("K7q2R9"));
    }

    private static void assertTrue(String msg, boolean v) { if (!v) throw new AssertionError(msg); }
    private static void assertTrue(boolean v) { if (!v) throw new AssertionError("expected true"); }
    private static void assertFalse(String msg, boolean v) { if (v) throw new AssertionError(msg); }
    private static void assertFalse(boolean v) { if (v) throw new AssertionError("expected false"); }
    private static void assertEquals(Object e, Object a) {
        if (e == null ? a != null : !e.equals(a)) throw new AssertionError("expected " + e + " but was " + a);
    }
}
