# HomeRelay

**Turn a Samsung Galaxy M01 (SM-M015G/DS, Android 12, One UI 4.1) into a home Wi‑Fi sharing box for a Galaxy M36 5G — using only the phone's home Wi‑Fi connection, never mobile data.**

HomeRelay is a small, honest Android app. Version 1.2.0 creates a **Wi‑Fi Direct group** with the phone as **Group Owner** (the NetShare approach), runs an on-device HTTP proxy bound to that group only, and shows you everything a NetShare-style tool shows — the address, the port, live counters, and exactly which clients are using it. It does not, and cannot, do magic that Android does not allow. What it can't do, it tells you, and gives you a one-minute manual client setup instead.

> **Status:** source-complete, CI-built. Debug and release APKs are produced by GitHub Actions on every push to `arena/ce47bd5e-homerelay` and attached to tagged releases. **No physical-device testing on the M01 or M36 has been performed by the author** — see [Honest limitations](#honest-limitations) and the [hardware test procedure](TROUBLESHOOTING.md#hardware-test-procedure-pending) before trusting it on your hardware.

---

## What it does

- Connects the phone to your home Wi‑Fi (you do that as usual) and **shares that connection**, not mobile data.
- **Pins the home Wi‑Fi network before anything else happens** and keeps that exact `Network` object for all upstream proxy traffic and DNS (`Network.socketFactory` / `Network.getAllByName`). If home Wi‑Fi disappears, proxied requests fail instead of silently using mobile data or looping back into the group.
- Creates a **Wi‑Fi Direct group** via `WifiP2pManager.createGroup` with this device as Group Owner. Legacy clients (your M36) join it as a normal WPA2 Wi‑Fi network — no Wi‑Fi Direct support needed on the client. Group name and password are generated fresh per session by HomeRelay (`DIRECT-xxxxxx`, WPA2-Personal, 16-char password).
- Discovers the **real** P2P interface and IPv4 via `requestGroupInfo()` / `requestConnectionInfo()` — never a hardcoded `192.168.49.1`.
- Runs an on-device **HTTP proxy with HTTPS CONNECT tunneling** bound **only to the group's interface address** (never to all interfaces, never to the upstream Wi‑Fi).
- Shows a dashboard: upstream Wi‑Fi (SSID, validated internet, metered), the group (SSID/passphrase, interface, frequency, security, connected clients), proxy (bind address/port, PAC URL), live byte/request counters, uptime, clients seen (last 10 minutes), active connections.
- Serves a tiny status page and an optional **PAC** (`/proxy.pac`) file on the group address; QR code on the client-guide screen encodes the PAC URL.
- Foreground service with a persistent notification (**Stop** action), partial wake lock, live counters and per-state status line in the notification.
- Diagnostics screen: real checks (Android version, P2P hardware, P2P enabled, group creation, Group Owner status, discovered interface name, P2P IPv4, credentials, pinned upstream identity, upstream validated/reachable, **whether the upstream Wi‑Fi survived group creation**, DNS via the pinned network, proxy listening, HTTP+HTTPS through the proxy) with PASS/WARN/FAIL/INFO.
- Settings: proxy port, PAC on/off, theme (System/Light/Dark), keep-screen-on, passphrase visibility, One UI battery guidance, clear-data.
- **No analytics, no telemetry, no ads, no external VPN provider, no accounts, no servers.** Everything happens on the phone and your LAN.

## What it deliberately does NOT do

- It is **not** a transparent repeater, not mesh Wi‑Fi, not roaming, not a bridge. Proxy sharing requires each client to set its Wi‑Fi proxy manually (or via PAC). Apps that ignore the Wi‑Fi proxy get **no internet** through the group.
- It never uses mobile data — not as a fallback, not "temporarily". If the pinned home Wi‑Fi is gone, the proxy refuses to connect out.
- It does not decrypt HTTPS. CONNECT tunnels carry opaque bytes; certificates stay end-to-end.
- It cannot (and does not try to) route itself around Samsung/Android battery management, Wi‑Fi power save, or P2P restrictions. It guides you through the official settings instead.
- It does not auto-start after reboot. Android restricts that; a manual tap is the only supported path (see TROUBLESHOOTING.md).
- It does not claim bit rates or per-client byte counts. Android does not expose them reliably to a non-privileged app, so those are shown as "None yet" / count-based facts, or omitted — never invented.
- It does not use hidden APIs, root, shell, reflection into system services, or privileged permissions.

## Hardware & software targets

| Role | Device | Notes |
|---|---|---|
| Sharing host | Samsung Galaxy M01 SM-M015G/DS | Android 12, One UI 4.1, ARM64, **no root, no bootloader unlock, no ROM, no system mods** |
| Client | Samsung Galaxy M36 5G | Android (One UI 8-class); joins the group as a legacy WPA2 client |
| Build toolchain | JDK 17, Gradle 9.7.1 (wrapper), AGP 9.3.2, Kotlin 2.3.0, compileSdk 36/targetSdk 36 | All pinned in `app/build.gradle.kts` / `gradle/wrapper/gradle-wrapper.properties` |
| Min SDK | 26 (Android 8.0) | `WifiP2pManager` is API 14+; the config-based `createGroup` path is used on API 29+, with the legacy overload below it |

## Install

1. Download `homerelay-debug.apk` from the [latest release](https://github.com/prr006/homerelay/releases) (or the CI artifact `homerelay-apks` from any green `Build APKs` run).
2. On the M01: Settings → Biometrics and security → Install unknown apps → allow your browser/files app → open the APK and install.
3. Grant **Nearby devices** (Android 13+) or **Location** (Android 12 and below — required by the Wi‑Fi Direct API) when asked, and allow notifications. HomeRelay never reads your location; the OS simply requires the permission for Wi‑Fi Direct.
4. Tap **Start sharing** on the dashboard.

`homerelay-release.apk` is **signed only if the repository owner configured `HOMERELAY_RELEASE_*` secrets**; otherwise it is unsigned and will not install. See BUILDING.md for signing your own release build. The debug APK is signed with the committed project debug keystore so every CI build updates cleanly over the last.

**Upgrading from v1.0.0:** uninstall it first (its CI runner key is unrecoverable; Android will refuse an in-place update). One uninstall, then install the new APK.
**Upgrading from v1.1.x:** installs in place — same applicationId, same committed debug keystore.

## Client quick start (5 steps)

1. On HomeRelay tap **Start sharing** — wait for **Group active — waiting for client** and note the group name (`DIRECT-xxxxxx`) + password on the dashboard.
2. On the client, connect its Wi‑Fi to that group.
3. Client Wi‑Fi → advanced options → **Proxy: Manual** → host = the group IP shown in the app (typically `192.168.49.x`, but it is whatever the system reports — never assume), port = `8080` (or your configured port). Android/iOS/Windows/macOS/ChromeOS/Firefox instructions are in the in-app Guide and in TROUBLESHOOTING.md.
4. Open `http://connectivitycheck.gstatic.com/generate_204` and `https://www.wikipedia.org` on the client — both should work.
5. Done. Stop sharing from the notification or the app when finished.

The PAC file at `http://<group-ip>:<port>/proxy.pac` can be used with "Proxy auto-config" instead of manual host/port. The QR code on the Guide screen only encodes that URL — see TROUBLESHOOTING.md for why no QR can auto-configure a proxy.

## Build from source

See **BUILDING.md**. Short version: install JDK 17 + Android SDK 36, then

```sh
./gradlew :app:assembleDebug
```

or open the project in Android Studio and press Run. The framework-free test suite runs anywhere:

```sh
./tools/run-tests.sh
```

## Repository layout

```
app/                 Android application module (Kotlin + Java)
  src/main/java/com/homerelay/app/
    MainActivity.kt            Navigation host; owns theme, settings, permission gate
    proxy/ProxyServer.java     Dependency-free HTTP proxy + CONNECT, status page, /proxy.pac, /status.json
    proxy/ProxyStats.java      Real counters for bytes, requests, tunnels, clients seen (10-min window)
    p2p/P2pGroupController.kt  Wi-Fi Direct Group Owner lifecycle, credentials, client tracking
    net/                       Upstream selection/pinning (P2P and cellular never selected), Wi-Fi-pinned connector
    service/SharingService.kt  Foreground service, notification with Stop action, wake lock, upstream pin
    diagnostics/               Real network/P2P/permission checks
    state/AppState.kt          MutableStateFlow-based shared UI state (writes: service; reads: Compose)
    util/                      Pure, JVM-testable policy: permissions, upstream selection, credentials, session guard
    ui/                        Compose screens: Dashboard, Guide, Checks (diagnostics), Settings
    qr/, data/                 QR encoder (bit-identical verified), settings store
tests/, tools/                 Framework-free Java test suite + portable runner
.github/workflows/build.yml    CI: core JVM tests + APK build (+ release on v* tags)
docs: README.md, BUILDING.md, NETWORK_ARCHITECTURE.md, TROUBLESHOOTING.md, PRIVACY.md, LICENSE
```

## Feature status lists

### Works (by construction; built in CI)
- Home Wi‑Fi upstream detection with validation status and cellular-active warning.
- Upstream **pinning** to the exact pre-creation Wi‑Fi `Network`; no code path opens an upstream connection over cellular or over the P2P network.
- Wi‑Fi Direct group creation via official API; per-session `DIRECT-xxxxxx` credentials shown live; legacy clients join as plain WPA2 Wi‑Fi.
- Real P2P interface / IPv4 discovery; proxy bound to that interface only (fails closed otherwise).
- Upstream-loss detection during a session (dedicated `NetworkCallback`) with an explicit "Upstream unavailable" state; automatic resume when Wi‑Fi returns; never a cellular fallback.
- Group-loss detection (framework broadcast + per-second read-back): the UI never keeps showing "Sharing" after the group disappears — it fails closed.
- Live session counters: bytes up/down, request counts, CONNECT tunnels, active connections, clients in group, clients seen (10-min window), uptime.
- Persistent foreground notification with live status and a Stop action; partial wake lock with 8 h cap.
- Client Guide screen: per-OS proxy setup, PAC URL, QR of the PAC URL, copy buttons.
- Diagnostics (Checks tab): see the list above — including group creation status, GO status, interface name, P2P IPv4, credentials, upstream identity/validation, and **upstream-survived-group-creation**.
- Settings: proxy port, PAC toggle, System/Light/Dark theme, keep screen on, passphrase visibility, clear data.
- M3 UI, dark/light themes, no nonfunctional buttons (every control wired).

### Restricted (platform-imposed; app guides you instead)
- The upstream home Wi‑Fi and the P2P group must coexist in the phone's Wi‑Fi chip. Whether the M01's firmware does that reliably is **unverified** — see the next section. When it fails, HomeRelay says so and stops; it does not show a fake sharing state.
- Group name/password rotate every session (fresh `DIRECT-xxxxxx`); a client must be re-pointed if it "forgets" the network. They cannot be set to match your home Wi‑Fi.
- Foreground-service survival depends on the user exempting HomeRelay from One UI battery limits; the app cannot self-exempt.
- Client proxy must be set manually (or via PAC) — no Android/iOS version lets an app set it, so the QR only encodes the PAC URL.
- Per-client bandwidth limits, per-client byte accounting, and DHCP lease listing are not exposed to non-privileged apps — shown as "None yet"/counts or omitted, never invented.
- No auto-start after reboot (not supported by stock Android for this use; manual start required).
- Release signing requires the owner's HOMERELAY_RELEASE_* secrets; otherwise the release APK is unsigned.

### Not possible / not done (unsupported)
- Transparent Wi‑Fi bridging, repeater mode, mesh/roaming, or a client that needn't change any network settings.
- Anything over mobile data — the proxy hard-fails without Wi‑Fi rather than fall back.
- UDP relaying (TCP-only proxy); QUIC endpoints fall back to TCP where supported.
- HTTPS inspection (CONNECT is opaque by design; certificates stay end-to-end).
- Bypassing Samsung/MDM/enterprise P2P restrictions (P2P_UNSUPPORTED / ERROR are reported, not circumvented).
- Auto-start after reboot, silent battery-optimization bypass, or any use of private/privileged APIs, hidden APIs, root, or reflection into system services.

## Compatibility summary

| Setup | Status |
|---|---|
| M01 (Android 12) hosts a Wi‑Fi Direct group; M36 joins it as legacy WPA2 client and sets the proxy manually | **Supported target** (not physically tested by author) |
| Client via PAC auto-config | Supported (PAC served by the app) |
| Client apps that ignore Wi‑Fi proxy settings | **No internet** — inherent to proxy sharing |
| M01 firmware that cannot hold STA + P2P simultaneously | **Detected, reported, refused** — no relay is possible then; see TROUBLESHOOTING.md |
| Transparent bridging / repeater / mesh | **Not possible** for a stock app; not what this app does |
| Mobile data sharing | **Never** — hard-wired off |
| Auto-start on boot | **Not supported** — manual start required |
| Any Android < 8.0 | Unsupported |

## Honest limitations

- **No hardware verification by the author.** CI builds the APKs; nobody has run them on an SM-M015G/DS or an M36. Whether One UI 4.1 on the M01 allows a Wi‑Fi STA connection and a P2P Group Owner group at the same time is **the** open question for v1.2.0 — the app is built to answer it (the `upstream_survived` diagnostic) and to fail honestly if the answer is no.
- Group credentials are fresh per activation by design (as recent Android releases require for `createGroup`).
- "Clients seen" counts unique client IPs seen talking to the proxy in the last 10 minutes — it is not a DHCP lease list and not proof a specific app is connected. "Clients connected" on the dashboard is the real P2P group client count from `requestGroupInfo()`.
- Foreground-service survival depends on the user excluding HomeRelay from One UI battery optimization; the app cannot enforce this.
- Release APK is unsigned unless signing secrets are configured by the repo owner.
- v1.1.1 (the last LocalOnlyHotspot build) remains available on the releases page and is unaffected by this change; it has the same no-hardware caveat.

## License

MIT — see [LICENSE](LICENSE).
