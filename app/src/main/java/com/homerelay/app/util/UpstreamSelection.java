package com.homerelay.app.util;

import java.util.List;

/**
 * Pure upstream-network selection policy for a sharing session. Framework-free so the rules
 * are regression-testable on the JVM.
 *
 * <p>Context: with Wi-Fi Direct, the phone can end up with several Wi-Fi networks at once —
 * home-router Wi-Fi (STA), the P2P group's network (as Group Owner, usually interface
 * "p2p0"), and possibly cellular. Both Wi-Fi networks advertise TRANSPORT_WIFI, so transport
 * alone cannot tell them apart. Callers must therefore tag the P2P network with
 * {@link Candidate#p2p} (see knownP2pInterfaceNames) before choosing.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>The network retained when the session started always wins while it is still present
 *       and still eligible (it may be temporarily unvalidated — that is an error state to
 *       report, not a reason to switch networks).
 *   <li>If the retained network is gone, pick the best remaining eligible candidate
 *       (validated preferred, then reachable internet). Cellular is never eligible.
 *   <li>If nothing eligible remains, return {@link #NONE} — the session reports "no upstream"
 *       and requests fail, never silently use mobile data or the P2P network.
 * </ol>
 */
public final class UpstreamSelection {
    private UpstreamSelection() {}

    public static final long NONE = -1L;

    /** Interface-name prefixes that identify P2P/SoftAP interfaces, never upstream candidates. */
    public static final String[] NON_UPSTREAM_PREFIXES = {"p2p", "softap", "swlan", "ap"};

    /** True iff the interface name looks like a P2P or SoftAP interface (never upstream). */
    public static boolean isNonUpstreamInterface(String interfaceName) {
        if (interfaceName == null) return false;
        String n = interfaceName.toLowerCase(java.util.Locale.US);
        for (String p : NON_UPSTREAM_PREFIXES) {
            if (n.startsWith(p)) return true;
        }
        return false;
    }

    /** Framework-free descriptor of a network candidate. */
    public static final class Candidate {
        public final long id;
        public final boolean wifi;
        public final boolean cellular;
        /** True for the P2P group's network (or any SoftAP it shares with). */
        public final boolean p2p;
        public final boolean validated;
        public final boolean internet;

        public Candidate(long id, boolean wifi, boolean cellular, boolean p2p,
                         boolean validated, boolean internet) {
            this.id = id;
            this.wifi = wifi;
            this.cellular = cellular;
            this.p2p = p2p;
            this.validated = validated;
            this.internet = internet;
        }
    }

    /** True iff this candidate may carry upstream proxy traffic at all. */
    public static boolean isEligibleUpstream(Candidate c) {
        return c.wifi && !c.cellular && !c.p2p;
    }

    /**
     * Choose the network to pin for this session, or {@link #NONE}.
     *
     * @param candidates networks visible right now (P2P and cellular entries are allowed in
     *                   the list — they are rejected here too)
     * @param retainedId the network pinned when the session started, or {@link #NONE}
     */
    public static long chooseUpstreamNetwork(List<Candidate> candidates, long retainedId) {
        if (retainedId != NONE) {
            for (Candidate c : candidates) {
                if (c.id == retainedId && isEligibleUpstream(c)) {
                    return c.id; // keep the session's network; report validation loss separately
                }
            }
        }
        Candidate best = null;
        for (Candidate c : candidates) {
            if (!isEligibleUpstream(c)) continue;
            if (best == null) {
                best = c;
                continue;
            }
            if (c.validated && !best.validated) best = c;
            else if (c.validated == best.validated && c.internet && !best.internet) best = c;
        }
        return best != null ? best.id : NONE;
    }

    /** Human-readable summary for UI/diagnostics ("Wi-Fi — validated", "None"). */
    public static String describe(List<Candidate> candidates, long pinnedId) {
        for (Candidate c : candidates) {
            if (c.id == pinnedId && isEligibleUpstream(c)) {
                return c.validated ? "Wi-Fi — internet validated"
                    : c.internet ? "Wi-Fi — reachable, not validated"
                    : "Wi-Fi — no internet reported";
            }
        }
        return "No eligible upstream network";
    }
}
