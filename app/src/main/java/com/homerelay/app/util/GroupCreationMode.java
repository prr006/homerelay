package com.homerelay.app.util;

/**
 * Selectable Wi-Fi Direct group creation modes (v1.2.4 A/B experiment).
 *
 * <p>Context: on Samsung M01 (Android 12, One UI 4.1) a HomeRelay-created group is visible
 * in Wi-Fi Direct peer discovery but never appears in the legacy Wi-Fi network list of a
 * Samsung M36, while the same M36 hosting works. NetShare, an alternative implementation,
 * allowed the M01 to host a usable network. These modes isolate which part of the
 * configuration the firmware mishandles.
 */
public enum GroupCreationMode {
    /** Mode A — current behaviour: custom DIRECT-xxxxxx SSID + passphrase, 2.4 GHz forced. */
    MODE_A_CUSTOM_24GHZ("a_custom_24ghz",
        "A · Custom SSID, 2.4 GHz (default)",
        "Custom DIRECT-xxxxxx SSID and passphrase, 2.4 GHz band forced, groupOwnerIntent 15, persistent off. " +
            "This is the baseline behaviour shipped in v1.2.x."),

    /** Mode B — Android system-managed group: no custom credentials or config at all. */
    MODE_B_SYSTEM_MANAGED("b_system_managed",
        "B · System-managed group",
        "Android/Samsung firmware generates the credentials and configuration via " +
            "WifiP2pManager.createGroup(channel, listener) without a WifiP2pConfig. The actual " +
            "SSID, passphrase, frequency and security are read back from requestGroupInfo(); " +
            "anything the OS hides stays hidden — never invented."),

    /** Mode C — custom credentials, framework default band (2.4 GHz NOT forced). */
    MODE_C_CUSTOM_DEFAULT_BAND("c_custom_default_band",
        "C · Custom SSID, default band",
        "Custom DIRECT-xxxxxx SSID and passphrase, persistent off, groupOwnerIntent 15 — but the " +
            "operating band is left at the framework default instead of forcing 2.4 GHz. Isolates a " +
            "bad interaction between the forced band flag and this firmware.");

    private final String key;
    private final String label;
    private final String description;

    GroupCreationMode(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }

    /** Stable storage key — never rename, existing installs persist it. */
    public String storageKey() { return key; }
    public String label() { return label; }
    public String description() { return description; }

    /** True when the mode supplies its own credentials (A and C). */
    public boolean usesCustomCredentials() { return this != MODE_B_SYSTEM_MANAGED; }

    /** True when 2.4 GHz is forced (A only). */
    public boolean forcesBand2ghz() { return this == MODE_A_CUSTOM_24GHZ; }

    public static final GroupCreationMode DEFAULT = MODE_A_CUSTOM_24GHZ;

    /** Parse a persisted key; unknown/garbage values fall back to the default, never crash. */
    public static GroupCreationMode fromStorage(String value) {
        if (value == null) return DEFAULT;
        for (GroupCreationMode m : values()) {
            if (m.key.equals(value)) return m;
        }
        return DEFAULT;
    }

    /** Short description for logs/diagnostics (never includes credentials). */
    public String describe() { return label + " [" + key + "]"; }
}
