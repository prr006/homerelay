# HomeRelay

**Turn a Samsung Galaxy M01 (SM-M015G/DS, Android 12, One UI 4.1) into a home Wi‑Fi sharing box for a Galaxy M36 5G — using only the phone's home Wi‑Fi connection, never mobile data.**

HomeRelay is a small, honest Android app: it asks stock Android for a *local-only hotspot*, runs an on-device HTTP proxy bound to that hotspot only, and shows you everything a NetShare-style tool shows — the address, the port, live counters, and exactly which clients are using it. It does not, and cannot, do magic that Android does not allow. What it can't do transparently, it tells you, and gives you a one-minute manual client setup instead.

> **Status:** source-complete, CI-built. Debug and release APKs are produced by GitHub Actions on every push to `arena/ce47bd5e-homerelay` and attached to tagged releases. **No physical-device testing on the M01 or M36 has been performed by the author** — see [Honest limitations](#honest-limitations) before trusting it on your hardware.

---

## What it does

- Connects the phone to your home Wi‑Fi (you do that as usual) and **shares that connection**, not mobile data.
- Creates an Android **LocalOnlyHotspot** (`startLocalOnlyHotspot`) — Android itself picks the SSID and passphrase on Android 12+; HomeRelay cannot choose them.
- Runs an on-device **HTTP proxy with HTTPS CONNECT tunneling** bound **only to the hotspot interface** (never to all interfaces, never to the upstream Wi‑Fi).
- **Pins all outbound proxy traffic to the Wi‑Fi network** via `Network.socketFactory` / `Network.getAllByName`. If home Wi‑Fi drops, proxied traffic fails instead of silently using mobile data.
- Shows a dashboard: upstream Wi‑Fi (SSID, validated internet, metered), hotspot (SSID/passphrase, Android-generated), proxy (bind address/port, PAC URL), live byte/request counters, uptime, clients seen (last 10 minutes), active connections.
- Serves a tiny status page and an optional **PAC** (`/proxy.pac`) file on the hotspot address; QR code on the client-guide screen encodes the PAC URL.
- Foreground service with a persistent notification (**Stop** action), partial wake lock, live counters in the notification.
- Diagnostics screen: real network checks (Android version, upstream presence/validation, cellular-only detection, DNS resolution, POST_NOTIFICATIONS, hotspot permission readiness) with PASS/WARN/FAIL/INFO.
- Settings: proxy port, PAC on/off, theme (System/Light/Dark), keep-screen-on, passphrase visibility, One UI battery guidance, clear-data.
- **No analytics, no telemetry, no ads, no external VPN provider, no accounts, no servers.** Everything happens on the phone and your LAN.

## What it deliberately does NOT do

- It is **not** a transparent repeater, not mesh Wi‑Fi, not roaming, not a bridge. Proxy sharing requires each client to set its Wi‑Fi proxy manually (or via PAC). Apps that ignore the Wi‑Fi proxy get **no internet** through this hotspot.
- It never uses mobile data — not as a fallback, not "temporarily". If there is no Wi‑Fi network, the proxy refuses to connect out.
- It does not decrypt HTTPS. CONNECT tunnels carry opaque bytes; certificates stay end-to-end.
- It cannot (and does not try to) route itself around Samsung/Android battery management, Wi‑Fi power save, or hotspot restrictions. It guides you through the official settings instead.
- It does not auto-start after reboot. Android restricts that; a manual tap is the only supported path (see TROUBLESHOOTING.md).
- It does not claim bit rates or per-client byte counts. Android does not expose them reliably to a non-privileged app, so those are shown as "None yet" / count-based facts, or omitted — never invented.

## Hardware & software targets

| Role | Device | Notes |
|---|---|---|
| Sharing host | Samsung Galaxy M01 SM-M015G/DS | Android 12, One UI 4.1, ARM64, **no root, no bootloader unlock, no ROM, no system mods** |
| Client | Samsung Galaxy M36 5G | Android 17 (API compatibility checked at runtime via `maxSdkVersion`-safe code; no private APIs used) |
| Build toolchain | JDK 17, Gradle 9.7.1 (wrapper), AGP 9.3.2, Kotlin 2.3.0, compileSdk 37/targetSdk 36 | All pinned in `app/build.gradle.kts` / `gradle/wrapper/gradle-wrapper.properties` |
| Min SDK | 26 (Android 8.0) — required by `startLocalOnlyHotspot` | Android 12 is the primary target |

## Install

1. Download `homerelay-debug.apk` from the [latest release](https://github.com/prr006/homerelay/releases) (or the CI artifact `homerelay-apks` from any green `Build APKs` run).
2. On the M01: Settings → Biometrics and security → Install unknown apps → allow your browser/files app → open the APK and install.
3. Grant **Nearby devices** (Android 13+) or **Location** (Android 12 and below — required by the hotspot API) when asked, and allow notifications.
4. Tap **Start sharing** on the dashboard.

`homerelay-release.apk` is **signed only if the repository owner configured `HOMERELAY_RELEASE_*` secrets**; otherwise it is unsigned and will not install. See BUILDING.md for signing your own release build. The debug APK (v1.1.0+) is signed with the committed project debug keystore so every CI build updates cleanly over the last.

**Upgrading from v1.0.0:** uninstall it first. The v1.0.0 debug APK was signed on an ephemeral CI runner key that cannot be recovered, so Android will refuse an in-place update with a signature-mismatch error. One uninstall, then install the new APK — every release from v1.1.0 on shares one stable debug signature.

## Client quick start (5 steps)

1. On HomeRelay tap **Start sharing** — wait for "Sharing" and note the hotspot name + password.
2. On the client, connect its Wi‑Fi to that hotspot.
3. Client Wi‑Fi → advanced options → **Proxy: Manual** → host = the hotspot IP shown in the app (e.g. `192.168.x.y`), port = `8080` (or your configured port). Android/iOS/Windows/macOS/ChromeOS/Firefox instructions are in the in-app Guide and in TROUBLESHOOTING.md.
4. Open `http://connectivitycheck.gstatic.com/generate_204` and `https://www.wikipedia.org` on the client — both should work.
5. Done. Stop sharing from the notification or the app when finished.

The PAC file at `http://<hotspot-ip>:<port>/proxy.pac` can be used with "Proxy auto-config" instead of manual host/port. The QR code on the Guide screen only encodes that URL — see TROUBLESHOOTING.md for why no QR can auto-configure a proxy.

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
    hotspot/                   LocalOnlyHotspot lifecycle + hotspot interface locator (bind safety)
    net/                       Upstream monitor + Wi-Fi-pinned outbound connector (no mobile-data fallback)
    service/SharingService.kt  Foreground service, notification with Stop action, wake lock
    diagnostics/               Real network/permission checks
    state/AppState.kt          MutableStateFlow-based shared UI state (writes: service; reads: Compose)
    ui/                        Compose screens: Dashboard, Guide, Checks (diagnostics), Settings
    qr/, data/, util/          QR encoder (bit-identical verified), settings store, format helpers
tests/, tools/                 Framework-free Java test suite + portable runner
.github/workflows/build.yml    CI: core JVM tests + APK build (+ release on v* tags)
docs: README.md, BUILDING.md, NETWORK_ARCHITECTURE.md, TROUBLESHOOTING.md, PRIVACY.md, LICENSE
```

## Feature status lists

### Works (by construction; built in CI)
- Home Wi‑Fi upstream detection with validation status and cellular-active warning.
- LocalOnlyHotspot creation via official API; Android-assigned SSID/passphrase displayed live.
- HTTP proxy with CONNECT tunneling, bound to the hotspot interface only (fails closed otherwise).
- Wi‑Fi-only egress for proxy traffic and DNS (`Network.socketFactory` / `Network.getAllByName`).
- Live session counters: bytes up/down, request counts, CONNECT tunnels, active connections, clients seen (10-min window), uptime.
- Persistent foreground notification with live counters and a Stop action; partial wake lock with 8 h cap.
- Client Guide screen: per-OS proxy setup, PAC URL, QR of the PAC URL, copy buttons.
- Diagnostics (Checks tab): Android version, upstream presence/validation, cellular-only check, DNS, POST_NOTIFICATIONS, hotspot permission readiness.
- Settings: proxy port, PAC toggle, System/Light/Dark theme, keep screen on, passphrase visibility, clear data.
- M3 UI, dark/light themes, no nonfunctional buttons (every control wired).

### Restricted (platform-imposed; app guides you instead)
- Hotspot name/password are random per activation on Android 12+; the app displays them but cannot set them.
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
- Bypassing Samsung/MDM/enterprise hotspot restrictions (ERROR_TETHERING_DISALLOWED is reported, not circumvented).
- Auto-start after reboot, silent battery-optimization bypass, or any use of private/privileged APIs.

## Compatibility summary

| Setup | Status |
|---|---|
| M01 (Android 12) shares home Wi‑Fi; M36 sets Wi‑Fi proxy manually | **Supported target** (not physically tested by author) |
| Client via PAC auto-config | Supported (PAC served by the app) |
| Client apps that ignore Wi‑Fi proxy settings | **No internet** — inherent to proxy sharing |
| Transparent bridging / repeater / mesh | **Not possible** without root; not what this app does |
| Mobile data sharing | **Never** — hard-wired off |
| Auto-start on boot | **Not supported** — manual start required |
| Any Android < 8.0 | Unsupported (LocalOnlyHotspot requires API 26) |

## Honest limitations

- **No hardware verification by the author.** CI builds the APKs; nobody has run them on an SM-M015G/DS or an M36. Samsung may also restrict background/hotspot behavior per region and One UI version.
- Hotspot credentials are random per activation on Android 12+ and cannot be chosen by HomeRelay.
- "Clients seen" counts unique client IPs seen talking to the proxy in the last 10 minutes — it is not a DHCP lease list and not proof a specific app is connected.
- Foreground-service survival depends on the user excluding HomeRelay from One UI battery optimization; the app cannot enforce this.
- Release APK is unsigned unless signing secrets are configured by the repo owner.

## License

MIT — see [LICENSE](LICENSE).
