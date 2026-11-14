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
   Android 10+ the **system** assigns a random SSID and passphrase every activation. It
   carries **no DHCP/NAT by itself** and is explicitly *local-only*. HomeRelay used this in
   v1.0–v1.1; it is removed in v1.2.0 in favour of Wi‑Fi Direct, which allows fresh,
   app-chosen credentials and standard legacy-client join semantics.
2. `WifiP2pManager.createGroup(...)` — **the v1.2.0 path.** Creates a P2P group with this
   device as Group Owner. The group *can* carry legacy (non-P2P) Wi‑Fi clients — i.e. a plain
   phone joining an SSID with a WPA2 password — which is exactly what the M36 does.
   - API 29+: `createGroup(channel, config, listener)` with a `WifiP2pConfig.Builder`
     configuration (network name `DIRECT-<2 chars><4 chars>` from `[A-Za-z0-9]`, passphrase
     8–63 printable ASCII, 2.4 GHz GO band, persistent mode off, group-owner intent 15).
     HomeRelay generates fresh credentials per session — they are never reused or persisted.
   - API 26–28: legacy `createGroup(channel, listener)` overload; the system assigns the
     credentials, which HomeRelay reads back via `requestGroupInfo()`.
3. Tethering APIs (`TetheringManager`, privileged) — **not available** to normal apps.
4. `VpnService` — can intercept the phone's own traffic, not other devices' traffic, and is a
   poor fit for sharing; HomeRelay does not use it.

So the only legitimate shape is NetShare-style: **Wi‑Fi Direct Group Owner + on-device proxy**.

## The critical caveat: STA + P2P concurrency

Both the home Wi‑Fi association and the P2P group live in the **same Wi‑Fi chip**. Whether a
given firmware can run a STA association and a P2P Group Owner role simultaneously is a
hardware/driver/ROM question — the public APIs do not guarantee it, and this document does not
assume it. What HomeRelay does about it:

1. It selects the upstream home Wi‑Fi `Network` **before** the group is created and keeps that
   exact object. The P2P group's network carries `TRANSPORT_WIFI` too, so it is explicitly
   rejected by interface name (`p2p*`, `softap*`, …) and by object identity — the pre-creation
   pin can never be the group.
2. Immediately after group formation it re-checks the pinned network. If it is gone or no
   longer Wi‑Fi, the session **fails closed** with an explicit message and Diagnostics records
   `upstream_survived = FAIL` — no proxy, no fake "sharing" state, no mobile-data fallback.
3. During the session a dedicated `NetworkCallback` on the pinned network flips the UI to
   **Upstream unavailable** the moment it degrades or vanishes, and back to sharing when it
   validates again. Nothing ever reroutes to cellular or to the P2P network.

Whether the SM-M015G/DS on One UI 4.1 passes this test is **not known** (no hardware has been
available to the author). The diagnostics are built so the first person to run it gets a clear
answer either way.

## What HomeRelay does, step by step

```
            Internet
               │
        ┌──────┴───────┐
        │ Home router  │
        └──────┬───────┘
               │ Wi‑Fi STA (upstream, pinned Network object)
        ┌──────┴───────────────────┐
        │  Galaxy M01 (HomeRelay)  │
        │                          │
        │  ┌────────────────────┐  │
        │  │ ProxyServer :8080  │  │  HTTP proxy + CONNECT tunnel
        │  │ bound to the group │  │  + /proxy.pac + /status.json
        │  │ interface only     │  │  + tiny status page
        │  └──────┬─────────────┘  │
        │         │ upstream sockets via Network.socketFactory
        │         │ DNS via Network.getAllByName — both pinned to the upstream network
        │         ▼                 │
        │  ┌────────────────────┐  │
        │  │ Wi‑Fi Direct group │  │  DIRECT-xxxxxx, WPA2, app-generated per session
        │  │ (Group Owner)      │  │  interface/IP discovered, never hardcoded
        │  └──────┬─────────────┘  │
        └─────────┼────────────────┘
                  │ Wi‑Fi (legacy client join, WPA2)
        ┌─────────┴─────────┐
        │ Galaxy M36 (client)│  Wi‑Fi proxy = group IP:8080 (manual or PAC)
        └───────────────────┘
```

1. **You connect the phone to home Wi‑Fi** exactly as usual. HomeRelay enumerates networks
   (`ConnectivityManager.allNetworks`), rejects cellular and any P2P/SoftAP-interface network,
   prefers the validated home Wi‑Fi network, and keeps that `Network` object.
2. **Start sharing** → `SharingService` (foreground service, `specialUse` type, persistent
   notification with a Stop action) creates the Wi‑Fi Direct group via `P2pGroupController`
   (`WifiP2pManager.createGroup`). Fresh `DIRECT-xxxxxx` credentials are generated per session.
   Group state is read back from the framework via `requestGroupInfo()` (interface name,
   frequency, security type, passphrase, client list) and `requestConnectionInfo()`
   (`groupFormed`, `isGroupOwner`, `groupOwnerAddress`) — **nothing is hardcoded**, and
   `192.168.49.1` is never assumed.
3. **The pinned network is re-validated** after group formation (see the caveat above). If it
   did not survive, the session fails and the group is removed.
4. HomeRelay binds the proxy to **the group's IPv4 address only**. If it cannot be discovered,
   binding **fails closed** — the proxy never falls back to 0.0.0.0.
5. The proxy (`proxy/ProxyServer.java`) speaks:
   - **HTTP forwarding** to absolute-URI requests,
   - **CONNECT tunneling** with raw byte relay — used for HTTPS; bytes are never parsed or decrypted,
   - `GET /proxy.pac` (`PROXY <bind-address>:<port>; DIRECT` — the `DIRECT` fallback is
     intentional so clients don't hard-fail on non-proxyable schemes),
   - `/status.json` and a plain GET `/` status page.
6. **Every outbound socket and every DNS lookup** from the proxy goes through the pinned
   upstream `Network` (`UpstreamConnector`): `network.socketFactory.createSocket()` and
   `network.getAllByName(host)`. There is **no code path that opens an upstream connection
   over cellular or over the P2P group**. If the pinned network is lost, requests fail with an
   error instead of using mobile data — and the UI shows **Upstream unavailable** until the
   home Wi‑Fi returns and re-validates.
7. Client membership is read back each second (`requestGroupInfo`), so the dashboard and the
   notification always reflect the real group: waiting for a client → client(s) connected.
   If the group disappears (system removes it, Wi‑Fi toggles, a conflicting P2P app runs),
   the reader sees `null` and the session fails closed — **the UI never keeps showing
   “sharing” without a group**.
8. Counters (`ProxyStats`) measure bytes actually relayed up/down, request counts, CONNECT
   tunnel counts, active connections, and distinct client IPs seen in a rolling 10-minute
   window. All of it is in-memory, reset per session.
9. **Stop sharing** — from the app or the notification — stops the proxy, removes the P2P
   group (`removeGroup`), unregisters the pinned-network callback, releases the wake lock, and
   the service stops itself. Start/Stop cycles are race-safe via a generation token
   (`SessionGuard`): a stale callback can never tear down a newer session.

## What the client must do (and why)

The group has no route to the internet. The only way out is the proxy. Therefore the client
must tell its apps to use it: on Android, Settings → Wi‑Fi → the DIRECT-xxxxxx network →
Advanced → Proxy. That setting is honored by the browser and by most (not all) apps — see
"Proxy limits" below.

PAC (`/proxy.pac`) is served so clients that support "Proxy auto-config" can fetch the
same settings automatically each time they join. **No app and no QR code can set a Wi‑Fi proxy
on stock Android/iOS today.** The QR on the Guide screen merely encodes the PAC URL so you can
verify it from a browser; auto-configuration is not claimed, because it isn't possible.

## Proxy limits (inherent, not bugs)

- **Apps that ignore the Wi‑Fi proxy get zero internet** through this group. Some games,
  VPN apps, and "direct-connection" apps bypass proxy settings by design.
- **HTTPS works, blind** — CONNECT tunnels are opaque; TLS certificates remain end-to-end.
  Anything that requires inspecting content (rare) cannot work through any proxy.
- **UDP is not relayed** (proxy is TCP). Things like QUIC fall back to TCP for clients that
  support it; hard-UDP-only protocols fail.
- **No per-client bandwidth limits, no auth.** Anyone who joins the group can use the proxy
  while it is running. Stop sharing when you are done, and use the per-session WPA2
  password as your access control.
- **Wi‑Fi Direct throughput** depends on the phone chip and antenna — typically fine for
  browsing/streaming, well below modern router AP rates, and the M01 is a 2019 entry-level
  device.

## Terms used honestly

| Term | What it is | HomeRelay |
|---|---|---|
| Proxy sharing | Client sets HTTP proxy; traffic relayed by app | **This.** |
| PAC auto-config | Client fetches `FindProxyForURL` script from a URL | Supported (`/proxy.pac`) |
| Wi‑Fi Direct group | Wi‑Fi AP created by the phone as Group Owner | **This** — app-generated `DIRECT-xxxxxx` credentials per session |
| Same SSID/password | Two APs sharing name+passphrase | Not how the group works; the group has its own per-session credentials; **not mesh, no roaming, no bridging** |
| Mesh / roaming | Seamless L2 handoff across nodes | **Impossible** for a stock app |
| Transparent repeater | Client unaware; L2/L3 relay | **Impossible** without root; not this |
| Transparent proxy at OS level | All traffic hijacked | Not on stock Android for third-party apps; not used |

## Memory/lifetime model

`SharingService` is a `specialUse` foreground service (Android 14+ rules; `FOREGROUND_SERVICE_SPECIAL_USE` on 34+, plain `FOREGROUND_SERVICE` on 12–13 per platform rules). A partial wake lock is held so Doze doesn't suspend relay mid-transfer (hard-capped at 8 h, released on stop). The UI observes `AppState` (a `MutableStateFlow`) — the service writes, Compose reads, nothing else mutates. Session stats die with the process by design; nothing is persisted except your settings (SharedPreferences, no secrets).
