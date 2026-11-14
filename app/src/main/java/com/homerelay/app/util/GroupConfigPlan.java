package com.homerelay.app.util;

/**
 * Framework-free plan for creating a Wi-Fi Direct group in a given {@link GroupCreationMode}.
 * The Kotlin controller maps this plan onto WifiP2pManager/WifiP2pConfig calls; keeping the
 * decision here makes the A/B/C behaviour unit-testable on the JVM.
 *
 * <p>Plan fields:
 * <ul>
 *   <li>{@code useCustomConfig} — true: build a WifiP2pConfig from the plan; false: call
 *       createGroup(channel, listener) with no config (system-managed).</li>
 *   <li>{@code forceBand2ghz} — true: setGroupOperatingBand(GROUP_OWNER_BAND_2GHZ).</li>
 *   <li>{@code groupOwnerIntent} — 0 means "leave the framework default".</li>
 *   <li>{@code persistentMode} — always false for HomeRelay groups.</li>
 * </ul>
 */
public final class GroupConfigPlan {
    public final GroupCreationMode mode;
    public final boolean useCustomConfig;
    public final boolean forceBand2ghz;
    public final int groupOwnerIntent;
    public final boolean persistentMode;
    public final String networkName;   // null when system-managed
    public final String passphrase;    // null when system-managed

    private GroupConfigPlan(GroupCreationMode mode, boolean useCustomConfig, boolean forceBand2ghz,
                            int groupOwnerIntent, boolean persistentMode,
                            String networkName, String passphrase) {
        this.mode = mode;
        this.useCustomConfig = useCustomConfig;
        this.forceBand2ghz = forceBand2ghz;
        this.groupOwnerIntent = groupOwnerIntent;
        this.persistentMode = persistentMode;
        this.networkName = networkName;
        this.passphrase = passphrase;
    }

    /**
     * @param creds freshly generated credentials (may be ignored for system-managed mode;
     *              pass fresh ones anyway — callers should not special-case generation)
     */
    public static GroupConfigPlan forMode(GroupCreationMode mode, P2pCredentials.Credentials creds) {
        if (mode == null) mode = GroupCreationMode.DEFAULT;
        switch (mode) {
            case MODE_B_SYSTEM_MANAGED:
                return new GroupConfigPlan(mode, false, false, 0, false, null, null);
            case MODE_C_CUSTOM_DEFAULT_BAND:
                if (creds == null) throw new IllegalArgumentException("custom mode needs credentials");
                return new GroupConfigPlan(mode, true, false, 15, false, creds.networkName, creds.passphrase);
            case MODE_A_CUSTOM_24GHZ:
            default:
                if (creds == null) throw new IllegalArgumentException("custom mode needs credentials");
                return new GroupConfigPlan(mode, true, true, 15, false, creds.networkName, creds.passphrase);
        }
    }

    /** Human-readable plan summary for diagnostics. Contains no credentials. */
    public String describe() {
        switch (mode) {
            case MODE_B_SYSTEM_MANAGED:
                return "system-managed group (no WifiP2pConfig; Android generates credentials and configuration)";
            case MODE_C_CUSTOM_DEFAULT_BAND:
                return "custom credentials, framework default band, persistent=false, groupOwnerIntent=15";
            case MODE_A_CUSTOM_24GHZ:
            default:
                return "custom credentials (DIRECT-xxxxxx), 2.4 GHz forced, persistent=false, groupOwnerIntent=15";
        }
    }
}
