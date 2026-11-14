# TROUBLESHOOTING.md

Real fixes for the failures a HomeRelay user can actually hit. Ordered roughly by likelihood.
If something here is wrong or incomplete, open an issue with your device model, Android/One UI
build, and the exact error text from the app — not guesses.

## M01 hosts, M36 cannot see the network (v1.2.4 A/B experiment)

Evidence: M36-hosted HomeRelay works (M01 discovers, joins, plays). M01-hosted HomeRelay is
invisible in the M36's ordinary Wi‑Fi list, even though the group is created and the M36 can
see the M01 in Wi‑Fi Direct peer discovery. NetShare allowed the M01 to host.

**Settings → Wi‑Fi Direct group mode (experimental)** in v1.2.4 offers three modes. Stop
sharing, switch, start again — one mode per session, the previous group is removed first:

- **Mode A** (default): custom `DIRECT-xxxxxx` + passphrase, 2.4 GHz forced,
  `groupOwnerIntent=15`, persistent off — the v1.2.x baseline.
- **Mode B**: system-managed group; Android/Samsung generates the credentials and
  configuration. No custom SSID/band. If Samsung's own implementation hosts a normal
  network, this mode should behave closest to NetShare.
- **Mode C**: custom credentials but the framework default band (no forced 2.4 GHz) —
  isolates a bad forced-2.4 GHz interaction on this firmware.

Test procedure on the M01:

1. Install v1.2.4; connect M01 to home Wi‑Fi; start sharing in Mode A; note SSID/frequency
   on the dashboard; on the M36, check (a) Wi‑Fi Direct peer list, (b) ordinary Wi‑Fi list.
2. If invisible in the ordinary list, stop, switch to Mode B, repeat. Then Mode C.
3. Record per mode: Checks → group_mode, group_created, group_owner, ssid_advertisement,
   group_interface, group_ip, group_clients, upstream_survived, plus the dashboard
   frequency/security/SSID readout.
4. Pass/fail per mode: the M36 can see the SSID in its ordinary list, joins, and loads
   media through the proxy (verify proxy_listen, http_via_proxy (204), https_via_proxy).
5. If none of the modes works, the limitation is firmware-level on the M01 GO path
   (creation succeeds but beaconing/advertising to legacy clients is broken). Report the
   dumpsys output and the per-mode Checks; do not blame the proxy — the proxy binds to the
   real group interface regardless of mode.

## v1.2.2 fix — "Cleartext HTTP traffic to connectivitycheck.gstatic.com not permitted"

v1.2.1's plain-HTTP check used `HttpURLConnection` to an `http://` URL. API 36 blocks
app-wide cleartext HTTP (and HomeRelay does **not** opt out), so the check failed before a
single byte reached the proxy — while HTTPS (CONNECT) passed. v1.2.2 probes the **local
proxy** over a raw socket in absolute-form (`GET http://connectivitycheck.gstatic.com/
generate_204 HTTP/1.1`, `Host`, `Connection: close`) and parses the real status line.
No `usesCleartextTraffic`, no network-policy weakening. Upgrade if on v1.2.1.

## v1.2.0 defect fixed in v1.2.1 — proxy socket fails / Checks fail locally

v1.2.0 used `WifiP2pInfo.groupOwnerAddress` as the proxy bind address. Android anonymises
that field for Group Owner apps (0.0.0.0) unless they hold the privileged
`LOCAL_MAC_ADDRESS` permission — so the proxy never bound and the local socket/HTTP checks
failed. v1.2.1 instead discovers the address by enumerating the P2P interface reported by
`getInterface()`. If you are on v1.2.0, upgrade.

## If the proxy works locally but the M36 still cannot see or join the group

The v1.2.1 fix restores the *local* path (proxy bound to the real group IPv4). It does **not**
prove the M36 discovery path — that is a separate question to investigate, in this order:

1. **Band.** HomeRelay creates the group on 2.4 GHz (`GROUP_OWNER_BAND_2GHZ`). Check Checks →
   "P2P interface" → frequency — if it reports 5 GHz, some legacy clients on crowded bands
   won't offer the network. There is no user-facing band switch yet; report the reading.
2. **SSID shape.** The group MUST advertise a `DIRECT-`-prefixed SSID — that is what tells
   legacy (non-P2P) Wi‑Fi clients "this is a joinable P2P group". HomeRelay generates
   `DIRECT-xxxxxx`; verify on the dashboard. If the SSID were ever anything else, the M36
   would never list it — report it.
3. **Hidden/open ambiguity.** HomeRelay never hides the SSID and never creates an open group;
   WPA2-Personal only. If the M36 can't find it even by manual "add network", that points at
   beaconing, not proxying.
4. **Capture evidence:**
   - `adb shell dumpsys wifip2p` on the M01 (group formed? interface? frequency? clients?)
   - `adb shell ip link` — is the p2p interface UP with an IPv4?
   - `adb logcat -s p2p,wpa_supplicant` while the M36 tries to join
   - Checks → "P2P interface (discovered)" and "Group IPv4" screenshots
5. **Samsung firmware quirks** (One UI 4.1): conflicting P2P consumers (Nearby Share, Smart
   View, system hotspot) can starve group beaconing; toggle Wi‑Fi off/on and retry with all
   other connectivity features off.
6. If it STILL fails after the above, this is a firmware/driver-level limitation of the M01 —
   the honest answer may be "cannot host a joinable legacy-client group", and the diagnostics
   above are exactly the evidence needed to say so definitively.

## Group won't start / "The Wi‑Fi Direct group did not form within 20 seconds"

1. Toggle Wi‑Fi off and on once, then press **Start sharing** again.
2. Another Wi‑Fi Direct app may hold the P2P role (e.g. Nearby Share, Smart View, another
   hotspot-sharing app). Stop it, then retry. `P2P_UNSUPPORTED`, `BUSY` or `ERROR` from the
   system mean exactly this class of problem.
3. `NO_PERMISSION` — Location (Android ≤12) or Nearby devices (Android 13+) permission
   missing → grant it; the Wi‑Fi Direct API requires it even though HomeRelay never uses your
   location.
4. System "Mobile Hotspot" / "Wi‑Fi sharing" active at the same time can conflict. Turn it
   off in Settings → Connections → Mobile Hotspot, then retry.
5. Checks tab → **Wi‑Fi Direct hardware support** says FAIL → this device cannot host the
   group at all; no software change can fix that.
6. Checks tab → **Wi‑Fi P2P enabled state** says disabled → turn Wi‑Fi off and on (P2P rides
   on it), then retry.
7. One UI aggressively freezes background starts: open the app (foreground) and press Start,
   rather than starting after a reboot or from a widget.

## "Creating the Wi‑Fi Direct group disconnected the upstream home Wi‑Fi"

This is the one real firmware risk for v1.2.0: some chipsets cannot keep a STA association to
the home router **and** host a P2P group at the same time. When that happens, HomeRelay
detects it immediately (the pinned network vanishes right after group formation), removes the
group, and fails with this exact message instead of showing a sharing state that cannot work.
Mobile data is **not** used as a fallback.

What to try:

1. Toggle Wi‑Fi off and on, then start again. Some firmwares sequence STA+P2P correctly on a
   fresh retry; some never can.
2. Make sure no other P2P/tethering path is active (system hotspot, Nearby Share, VPN) — one
   extra P2P consumer can be the difference.
3. If it fails every time, collect the evidence and report it:
   - HomeRelay → Checks → screenshot, especially **Upstream survived group creation** (FAIL)
     and **P2P interface / IPv4**;
   - `adb shell dumpsys wifi` and `adb shell ip link` right after the failure;
   - your One UI version (Settings → About phone → Software information).
   This is a device/firmware limitation, not an app bug — but it decides whether v1.2.0 works
   on the M01 at all, so the report genuinely matters.

## "The Wi‑Fi Direct group disappeared mid-session" / sharing stopped suddenly

The group vanished (system removed it, Wi‑Fi toggled, a conflicting P2P app ran). HomeRelay
stops everything and does **not** keep showing "Sharing" — press Start again. If it recurs,
see the item above.

## "Upstream unavailable" while sharing

The group and proxy are up, but the pinned home Wi‑Fi is lost or Android has not validated it.
Proxied requests fail until Wi‑Fi returns — HomeRelay does not reroute to mobile data.
Reconnect the phone to home Wi‑Fi (same SSID); the app flips back to sharing automatically
when Android validates the network again. If it says "not validated" but you have internet,
the router may need a re-check: toggle Wi‑Fi off/on on the **phone**, not the client.

## Client connects but has no internet

1. **Proxy is the only path out.** Wi‑Fi → the DIRECT-xxxxxx group → Advanced/Modify → Proxy — set to
   *Manual*, host = the group IP shown on the HomeRelay dashboard (e.g. DIRECT-xxxxxx's owner address —
   shown live), port = the one shown (default 8080). Save, rejoin if the client "forgets" it.
2. Or use *Proxy auto-config* with the PAC URL printed in the app — it must be reachable in a
   browser first: open the PAC URL on the client; you should see JavaScript text beginning
   `function FindProxyForURL(`. If not, you are not on the group, or sharing is off.
3. If the client works for `http://` but not `https://`, it is not really using the proxy
   (HTTPS needs the CONNECT tunnel). Recheck step 1. Firefox ignores OS proxy settings —
   set it in Firefox → Settings → Network Settings directly.
4. HomeRelay shows **Upstream: Not connected / No validated internet** → fix the phone's home
   Wi‑Fi first. The proxy pins to Wi‑Fi by design; no Wi‑Fi means no relay (never cellular).
5. VPN apps on the sharing phone or on the client will fight the proxy. Turn the VPN off on
   both while testing.

## Mobile-data warning is showing

HomeRelay noticed the phone has cellular active. Sharing still works **only as far as Wi‑Fi
reaches**: the proxy sends zero bytes over cellular. If home Wi‑Fi drops mid-session, proxied
clients simply lose internet — by design. Connect to Wi‑Fi; do not expect silent 4G/5G use.

## One UI keeps killing the service (sharing stops after minutes)

Samsung's battery management will do this to any app it deems background. The app cannot opt
itself out — this requires your touch, once:

1. In HomeRelay → Settings → *One UI battery & background limits* → tap
   **Open battery optimization exemption dialog** and allow it.
2. Device care → Battery → Background usage limits:
   - check HomeRelay is **not** in *Sleeping apps* or *Deep sleeping apps*;
   - add HomeRelay to **Never sleeping apps**.
3. Keep the persistent notification visible; don't swipe it away.
4. If it was killed anyway: reopen the app and start sharing again — sessions are not
   auto-resumed after a kill or reboot (see next item).

## "Can it start automatically after a reboot?"

**No, and it doesn't pretend to.** Stock Android restricts boot-start for Wi‑Fi Direct/proxy
work; `BOOT_COMPLETED` receivers are also unreliable on One UI and HomeRelay does not ship
one. After a reboot: open the app, tap Start. That's the supported path.

## "Can I set the group name/password to match my home Wi‑Fi (same SSID/password)?"

No. Wi‑Fi Direct group names must start with `DIRECT-`, and HomeRelay generates fresh
`DIRECT-xxxxxx` credentials for every session by design. They cannot be made to match your
home SSID, and that would not be mesh, roaming or repeating anyway — joining the group gives
clients the group, nothing else. Seamless hand-off between home router and phone is not
possible without root.

## Byte counters / client list look odd

- Upload = bytes relayed client→origin, Download = origin→client. "Clients seen" = unique
  source IPs that talked to the proxy in the last 10 minutes. Neither is DHCP state and
  neither proves which app on the client made a request. If nothing is shown, no client has
  used the proxy yet — check the client's proxy setting again.
- Everything resets at Start; counters are not persisted across sessions.

## "Install unknown apps" blocked / APK not installing

Enable it only for the browser or file manager you actually use:
Settings → Biometrics and security → Install unknown apps → (that app) → toggle on.
The **release** APK is signed only if the release owner set `HOMERELAY_RELEASE_*` secrets;
otherwise it is unsigned and Android will refuse it — use the debug APK instead, or sign your
own build (BUILDING.md).

**"App not installed" / "package conflicts with an existing package" (signature mismatch):**
the v1.0.0 debug APK was signed on an ephemeral CI runner keystore that no longer exists, so
v1.1.0+ cannot update over it in place. Uninstall v1.0.0, then install v1.1.0. From v1.1.0 on,
every CI debug build uses the same committed debug keystore, so updates install normally.
If you sideloaded a locally built APK and a CI APK, the same rule applies — pick one signature
and stick with it (uninstall before switching).

## Data usage audit

Home network interface counters will show the phone pulling the same bytes the clients push
through the proxy — there is no duplication beyond the phone acting as the relay, and none of
it is billed as mobile data. To verify on the phone: Settings → Connections → Data usage;
or Device care → Battery → App power management → HomeRelay for background usage sanity.

## When reporting bugs, include

Model (SM-M015G/DS…), Android + One UI version, HomeRelay version (Settings → About),
whether home Wi‑Fi had validated internet (Checks tab), and the exact text on the failing
screen. Screenshots of the notification while sharing is running help a lot.

## Upgrading from v1.1.x or v1.0.0

- v1.1.x → v1.2.0: installs in place (same `com.homerelay.app` id, same committed debug
  keystore). No uninstall needed. v1.2.0 replaces the hotspot sharing path with Wi‑Fi Direct
  Group Owner — it is not a settings migration, it is a new sharing engine.
- v1.0.0 → anything ≥ v1.1.0: uninstall v1.0.0 first (its signing key is unrecoverable).

## Hardware test procedure (pending)

Nothing on this page has been verified on physical M01/M36 hardware yet. When you test, this
is the procedure that produces trustworthy evidence — in order, ~10 minutes:

1. **Baseline (v1.2.0, fresh install).** Install `homerelay-debug.apk`. Open the app, run
   Checks. Record: Android version, Wi‑Fi Direct hardware support, P2P enabled state,
   permissions, battery exemption.
2. **Upstream pin.** Connect the M01 to home Wi‑Fi. Start sharing. As soon as "Group active —
   waiting for client" appears, confirm on the M01 that Wi‑Fi is **still connected** (Settings →
   Connections → Wi‑Fi shows your SSID, not disconnected).
3. **Survival evidence** (the decisive check): HomeRelay → Checks → **Upstream survived group
   creation**. PASS = this firmware runs STA+P2P. FAIL = it cannot; stop here and file the
   report described above.
4. **Join from the M36.** Wi‑Fi → pick `DIRECT-xxxxxx` → enter the password from the dashboard.
5. **Proxy + browser.** Set M36's proxy to the dashboard's host:port. Load
   `http://connectivitycheck.gstatic.com/generate_204`, then `https://www.wikipedia.org`.
   Then try YouTube (HTTPS CONNECT) — report separately.
6. **Diagnostics back on the M01.** Checks → confirm: group created PASS, Group Owner PASS,
   interface name (e.g. `p2p0`), P2P IPv4, credentials PASS, upstream identity = your home
   SSID, validated PASS, HTTP through proxy PASS, HTTPS through proxy PASS.
7. **Repeatability.** Stop sharing, start again, re-join from the client. Five cycles. No
   stale group should linger in the client's Wi‑Fi list, and the dashboard must never show
   "Sharing"/"Group active" after a group disappeared.
8. **Negative control.** Turn home Wi‑Fi off on the M01 mid-session. The dashboard must flip
   to **Upstream unavailable** (not "Sharing"), and proxied requests on the M36 must fail —
   then turn Wi‑Fi back on and confirm it recovers to sharing.
9. Capture screenshots of the Checks screen in both the PASS and any FAIL scenario and attach
   them to the issue. That is the difference between "it didn't work" and a fixable bug.
