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

Partial transfers remain in the matching category folder as `.ns-partial` files with a sequence checkpoint. A failed connection must not be treated as a user cancellation.

## Discovery

- mDNS service type: `_warpair._tcp.`
- Default TCP port: `19730`

## SoftAP (no shared router)

Host can start a **local-only hotspot** (`WifiManager.startLocalOnlyHotspot`).  
Credentials + suggested host IP are shown and embedded in the QR payload so the peer can join Wi-Fi, then TCP.

## QR bootstrap

URI form:

```text
warpair://connect?host=192.168.43.1&port=19730&ssid=AndroidShare_xxxx&pass=********&name=Host
```

- Host: **Show QR** encodes current SoftAP / LAN endpoint.
- Client: scan QR, paste URI or open `warpair://` link; fields fill automatically.
- A QR code supplies connection details; it does not itself switch the phone onto the advertised Wi-Fi network.

## Transport failover direction

WarPair will treat Wi-Fi LAN, SoftAP, Wi-Fi Direct and Bluetooth as interchangeable carriers for one logical transfer. A route change must preserve the selected files and resume from the receiver's validated partial-file checkpoint.

The Phase 3 policy foundation currently orders available transport candidates and supports deterministic fallback. Wi-Fi Direct and Bluetooth adapters and automatic radio-level switching are not yet implemented.

## Next

Wi-Fi Direct group-owner/client adapter, followed by Bluetooth and end-to-end automatic resume across transports.
