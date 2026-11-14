# TROUBLESHOOTING.md

Real fixes for the failures a HomeRelay user can actually hit. Ordered roughly by likelihood.
If something here is wrong or incomplete, open an issue with your device model, Android/One UI
build, and the exact error text from the app — not guesses.

## Hotspot won't start / "Hotspot did not start within 15 seconds"

1. Toggle Wi‑Fi off and on once, then press **Start sharing** again.
2. Another hotspot may already be active (system "Mobile Hotspot" or "Wi‑Fi sharing").
   Turn it off in Settings → Connections → Mobile Hotspot, then retry.
   `ERROR_INCOMPATIBLE_MODE` from the system means exactly this.
3. `ERROR_TETHERING_DISALLOWED` — the system refused because tethering is restricted on this
   SIM/plan or by device policy (enterprise). HomeRelay cannot override this; it can only show
   you the error.
4. Location (Android ≤12) or Nearby devices (Android 13+) permission missing → grant it; the
   hotspot API requires it even though HomeRelay never uses your location.
5. Corporate/MDM-managed phones: `ERROR_NO_CHANNEL` — no usable channel. Nothing to do.
6. One UI aggressively freezes background starts: open the app (foreground) and press Start,
   rather than starting after a reboot or from a widget.

## Client connects but has no internet

1. **Proxy is the only path out.** Wi‑Fi → hotspot → Advanced/Modify → Proxy — set to
   *Manual*, host = the hotspot IP shown on the HomeRelay dashboard, port = the one shown
   (default 8080). Save, rejoin if the client "forgets" it.
2. Or use *Proxy auto-config* with the PAC URL printed in the app — it must be reachable in a
   browser first: open the PAC URL on the client; you should see JavaScript text beginning
   `function FindProxyForURL(`. If not, you are not on the hotspot, or sharing is off.
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

**No, and it doesn't pretend to.** Stock Android restricts boot-start for hotspot/proxy
work; `BOOT_COMPLETED` receivers are also unreliable on One UI and HomeRelay does not ship
one. After a reboot: open the app, tap Start. That's the supported path.

## "Can I set the hotspot name/password to match my home Wi‑Fi (same SSID/password)?"

No on Android 12+: the system generates a random SSID and passphrase for every
`LocalOnlyHotspot` activation, and third-party apps cannot change or choose them. Matching
name/password with an *existing* router is also not mesh, not roaming, not repeating — joining
this hotspot gives clients this hotspot, nothing else. Seamless hand-off between home router
and phone is not possible without root.

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
