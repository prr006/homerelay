# NETWORK_ARCHITECTURE.md

How HomeRelay actually moves bytes — and why. Written against stock Android 12 / One UI 4.1,
no root, no system mods.

## The problem on stock Android

Android separates two radio roles:

- **Upstream:** the Wi‑Fi client association to your home router (`192.168.0.x` DHCP, internet via NAT).
- **Downstream:** a hotspot for other devices.

Since Android 8.0, the **system** can run both at once, but an app cannot create a *second*
client association on the same radio, and there is no supported "Wi‑Fi repeater" mode for
third-party apps. The supported APIs are:

1. `WifiManager.startLocalOnlyHotspot(callback, handler)` — an app-created hotspot. On
   Android 10+ the **system** assigns a random SSID and passphrase every activation
   (Android 12+). It carries **no DHCP/NAT by itself** and is explicitly *local-only*: it does
   not route to the upstream network.
2. `SoftApConfiguration` (Android 11+) — read-only view of what the system created (SSID,
   passphrase, BSSID, security type). HomeRelay uses it to show you the credentials.
3. Tethering APIs (`ConnectivityManager` / `TetheringManager`, privileged) — **not available**
   to normal apps; they require `TETHERING`-class permissions held by system apps only.
4. `VpnService` — can intercept the phone's own traffic, not other devices' traffic, and is a
   poor fit for sharing; HomeRelay does not use it.
5. Wi‑Fi Direct — device-to-device, not internet sharing; not used.

So the only legitimate shape is NetShare-style: **hotspot + on-device proxy**.

## What HomeRelay does, step by step

```
            Internet
               │
        ┌──────┴───────┐
        │ Home router  │
        └──────┬───────┘
               │ Wi‑Fi (upstream, DHCP, internet)
        ┌──────┴───────────────────┐
        │  Galaxy M01 (HomeRelay)  │
        │                          │
        │  ┌────────────────────┐  │
        │  │ ProxyServer :8080  │  │  HTTP proxy + CONNECT tunnel
        │  │ bound to hotspot   │  │  + /proxy.pac + /status.json
        │  │ interface only     │  │  + tiny status page
        │  └──────┬─────────────┘  │
        │         │ upstream sockets via Network.socketFactory
        │         │ DNS via Network.getAllByName — both pinned to Wi‑Fi
        │         ▼                 │
        │  ┌────────────────────┐  │
        │  │ LocalOnlyHotspot   │  │  Android picks SSID/password
        │  └──────┬─────────────┘  │
        └─────────┼────────────────┘
                  │ Wi‑Fi (hotspot, WPA2/WPA3, no internet of its own)
        ┌─────────┴─────────┐
        │ Galaxy M36 (client)│  Wi‑Fi proxy = 192.168.x.y:8080 (manual or PAC)
        └───────────────────┘
```

1. **You connect the phone to home Wi‑Fi** exactly as usual. HomeRelay observes the upstream
   network (`ConnectivityManager`), prefers the validated Wi‑Fi network, and detects when only
   cellular is active (and warns).
2. **Start sharing** → `SharingService` (foreground service, `specialUse` type, persistent
   notification with a Stop action) calls `startLocalOnlyHotspot`. Android creates the hotspot
   and reports the credentials through `LocalOnlyHotspotCallback`.
3. HomeRelay locates the hotspot's interface/IPv4 (`HotspotInterfaceLocator`) and binds the
   proxy to **that address only**. If the hotspot interface cannot be identified with
   confidence, binding **fails closed** — the proxy never falls back to 0.0.0.0.
4. The proxy (`proxy/ProxyServer.java`) speaks:
   - **HTTP forwarding** to absolute-URI requests (`GET http://example.com/ HTTP/1.1`),
   - **CONNECT tunneling** (`CONNECT host:443 HTTP/1.1`) with raw byte relay —
     used for HTTPS; bytes are never parsed or decrypted,
   - a GET on `/proxy.pac` serving a PAC whose single rule is
     `PROXY <bind-address>:<port>; DIRECT` — note the `DIRECT` fallback is intentional so
     clients don't hard-fail on non-proxyable schemes (see the PAC caveat below),
   - `/status.json` and a plain GET `/` status page (no internals beyond name+counters).
5. **Every outbound socket and every DNS lookup** from the proxy goes through the Wi‑Fi
   `Network` object (`UpstreamConnector`): `network.socketFactory.createSocket()` and
   `network.getAllByName(host)`. There is **no code path that opens an upstream connection
   over cellular**. If Wi‑Fi disappears, requests fail with an error instead of using mobile
   data. (Cellular being merely *on* is fine and common; HomeRelay only requires that Wi‑Fi
   exists to send traffic.)
6. Counters (`ProxyStats`) measure bytes actually relayed up/down, request counts, CONNECT
   tunnel counts, active connections, and distinct client IPs seen in a rolling 10-minute
   window. All of it is in-memory, reset per session, and shown live in the UI and in the
   notification.
7. **Stop sharing** — from the app or the notification — stops the proxy, releases the
   hotspot reservation, releases the wake lock, and the foreground service stops itself.

## What the client must do (and why)

The hotspot has no route to the internet. The only way out is the proxy. Therefore the client
must tell its apps to use it: on Android, Settings → Wi‑Fi → network → Advanced → Proxy. That
setting is honored by the browser and by most (not all) apps — see "Proxy limits" below.

PAC (`/proxy.pac`) is served so clients that support "Proxy auto-config" can fetch the
same settings automatically each time they join. **No app and no QR code can set a Wi‑Fi proxy
on stock Android/iOS today.** The QR on the Guide screen merely encodes the PAC URL so you can
verify it from a browser; auto-configuration is not claimed, because it isn't possible.

## Proxy limits (inherent, not bugs)

- **Apps that ignore the Wi‑Fi proxy get zero internet** through this hotspot. Some games,
  VPN apps, and "direct-connection" apps bypass proxy settings by design.
- **HTTPS works, blind** — CONNECT tunnels are opaque; TLS certificates remain end-to-end.
  Anything that requires inspecting content (rare) cannot work through any proxy.
- **UDP is not relayed** (proxy is TCP). Things like QUIC fall back to TCP for clients that
  support it; hard-UDP-only protocols fail.
- **No per-client bandwidth limits, no auth.** Anyone who joins the hotspot can use the proxy
  while it is running. Stop sharing when you are done, and use the Android-assigned WPA2/WPA3
  password as your access control.
- **LocalOnlyHotspot throughput** depends on the phone chip and antenna — typically fine for
  browsing/streaming, well below modern router AP rates, and the M01 is a 2019 entry-level
  device.

## Terms used honestly

| Term | What it is | HomeRelay |
|---|---|---|
| Proxy sharing | Client sets HTTP proxy; traffic relayed by app | **This.** |
| PAC auto-config | Client fetches `FindProxyForURL` script from a URL | Supported (`/proxy.pac`) |
| Hotspot | Wi‑Fi AP created by the phone | Yes — local-only, Android-generated credentials |
| Same SSID/password | Two APs sharing name+ passphrase | Possible to mimic manually; **not automatic, not mesh, no roaming, no bridging** |
| Mesh / roaming | Seamless L2 handoff across nodes | **Impossible** for a stock app |
| Transparent repeater | Client unaware; L2/L3 relay | **Impossible** without root; not this |
| Transparent proxy at OS level | All traffic hijacked | Not on stock Android for third-party apps; not used |

## Memory/lifetime model

`SharingService` is a `specialUse` foreground service (Android 14+ rules; `FOREGROUND_SERVICE_SPECIAL_USE` on 34+, plain `FOREGROUND_SERVICE` on 12–13 per platform rules). A partial wake lock is held so Doze doesn't suspend relay mid-transfer (hard-capped at 8 h, released on stop). The UI observes `AppState` (a `MutableStateFlow`) — the service writes, Compose reads, nothing else mutates. Session stats die with the process by design; nothing is persisted except your settings (SharedPreferences, no secrets).
