# WarPair

Official product name: **WarPair**  
Android application id: `com.crypticsystems.warpair`

The GitHub repository may still be named NearbyShare; the shipped app is WarPair.

## Packages

| Layer | Package |
|-------|---------|
| App UI | `com.crypticsystems.warpair` |
| Native bridge | `com.crypticsystems.warpair.core` |
| Rust crate | `nearby-share` (library name `nearby_share`, unchanged for ABI stability) |

## Storage

Received files: `/storage/emulated/0/WarPair/{Music,Videos,Images,Documents,Archives,Other}/`

## Discovery

- mDNS service type: `_warpair._tcp.`
- Default TCP port: `19730`

## SoftAP (no shared router)

Host can start a **local-only hotspot** (`WifiManager.startLocalOnlyHotspot`).  
Credentials + suggested host IP are shown and embedded in the QR payload so the peer can join Wi‑Fi, then TCP.

## QR bootstrap

URI form:

```text
warpair://connect?host=192.168.43.1&port=19730&ssid=AndroidShare_xxxx&pass=********&name=Host
```

- Host: **Show QR** encodes current SoftAP / LAN endpoint.
- Client: paste URI or open `warpair://` link; fields fill automatically.

Wi‑Fi Direct group owner path can layer on the same TCP stack later; SoftAP covers the offline case for the current demo.

## Next

Product UI redesign on top of this core (Host / Join / Library / QR / SoftAP).
