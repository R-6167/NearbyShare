# WarPair connection and UX direction

## Research distilled into product principles

WarPair should learn from the interaction patterns of established sharing products without reproducing their screens or visual identity.

- **Google Quick Share:** nearby-device sharing is the primary path; QR is a useful alternate when discovery does not surface the target. Receiving is an explicit mode, and the recipient sees the sender/content and accepts or declines. Keep that two-path resilience, but make WarPair's QR invitation the universal rendezvous.
  - Reference: https://support.google.com/android/answer/9286773
- **Apple AirDrop:** keep the send action short, make receiving visibility deliberate and temporary, and preserve a clear accept/decline decision. AirDrop also demonstrates that discovery and transfer depend on both Bluetooth and Wi-Fi being enabled.
  - Reference: https://support.apple.com/en-ie/guide/iphone/iphcd8b9f0af/ios
- **Android Bluetooth guidance:** stop discovery before opening an RFCOMM connection; socket connect, accept, read and write operations belong on worker threads because they block.
  - References: https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices
    https://developer.android.com/develop/connectivity/bluetooth/transfer-data

## WarPair's own visual and interaction concept

Working name: **Signal / Orbit**. A compact, dark-or-light adaptive interface with a distinctive ring/radar motif around the active peer or transfer. The motif is a WarPair identity element, not a copy of another app.

### Home

- Two dominant icon-led actions: **Send** (paper plane / arrow-up-right) and **Receive** (inbox / QR).
- One secondary **Scan** affordance for joining an existing receive session.
- A small recent-transfers row and a single settings entry.
- Avoid transport-specific buttons on the home screen. Bluetooth, Wi-Fi Direct, LAN and SoftAP are implementation details, not separate user journeys.
- Buttons use a clear icon plus a short label; destructive or ambiguous actions retain text labels for accessibility.

### Send flow

1. Choose files first; show selected count, combined size and removable file chips.
2. Tap the prominent scan icon and scan the receiver's invitation.
3. Show the receiver identity and a compact route state (for example, "Finding the fastest route").
4. Ask for confirmation when identity or trust cannot be established automatically.
5. Show one transfer screen with total progress, current file, speed, pause/cancel and verified completion.

### Receive flow

1. Tap Receive; WarPair starts every permitted receiver route it can actually bring up.
2. Show one large, high-contrast QR invitation with a short rotating session code and expiry countdown.
3. Keep a small row of live route indicators (Wi-Fi, Wi-Fi Direct, Bluetooth) with plain status dots; no manual transport selection.
4. On an incoming offer, show sender, file count, total size and destination; accept, reject or resume.
5. Expire the invitation and stop advertising/listening when the user leaves Receive or explicitly stops it.

### QR invitation contract

QR is a rendezvous/bootstrap mechanism, not a magic radio switch and not the data channel.

- Versioned, short-lived invitation with a cryptographically random session ID/nonce, expiry, display name and supported protocol version.
- Advertise only route hints the receiver has actually started and can currently accept.
- Never put file contents, private keys, long-lived credentials or a permanent Bluetooth identity in the QR.
- On scan, validate the invitation, resolve/probe available route candidates, then authenticate the peer using the existing WarPair handshake before showing files.
- LAN can use a reachable local endpoint. Wi-Fi Direct still needs Android P2P discovery/group negotiation and a routable socket. Bluetooth still needs Android Bluetooth permissions, a discoverable/known peer and an RFCOMM socket. The QR itself cannot grant permissions, force pairing, or bypass Android radio prompts.
- If a route fails, retry through another advertised route using the same batch/file identities and receiver-side durable checkpoints. User cancellation and recipient rejection remain terminal and must never be bypassed by fallback.

### Icon language

Use a consistent set of simple filled/outlined Material-style glyphs: send, inbox, qr-code-scanner, folder-plus, history, wifi, bluetooth, shield-check, pause, play, x-circle and settings. Icons carry the visual weight; short text labels and content descriptions preserve discoverability and accessibility. Do not use unlabeled icon-only controls for destructive actions.

## Engineering sequence

1. Fix and physically verify Bluetooth RFCOMM transfer.
2. Replace the current host/IP-only QR payload with the versioned invitation contract and route capability model.
3. Add scan-to-rendezvous route negotiation without changing the existing transfer protocol's identity, consent or resume guarantees.
4. Replace the transport-heavy screen with the icon-led Send / Receive / Scan home and focused transfer/receive states.
5. Verify every visible control has a real action, then test LAN, Wi-Fi Direct, Bluetooth and fallback on physical phones.

## Current known issue

Bluetooth RFCOMM connection currently attempts `BluetoothSocket.connect()` without first cancelling local device discovery. Android explicitly recommends cancelling discovery before connecting because discovery degrades connection performance. The implementation now cancels discovery immediately before creating/connecting the RFCOMM socket; physical transfer verification is still required.
