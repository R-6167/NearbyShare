# NearbyShare Design Document

**Offline, peer-to-peer nearby file sharing foundation**  
Rust core + native platform bindings (Android first, iOS later). No cloud. Ever.

---

## Vision

Build a high-performance, privacy-first nearby sharing engine that:

- Works completely offline
- Uses modern cryptography
- Supports multiple discovery and transport mediums
- Exposes a clean UniFFI API so native apps (and later other apps) can embed it
- Feels as smooth as AirDrop / Quick Share while remaining fully open and auditable

---

## Core Principles

1. **Zero cloud** – data never leaves the local link between devices
2. **Platform-agnostic core** – all protocol, crypto, and session logic lives in Rust
3. **Platform-owned radios** – Android/iOS code owns BLE, Wi-Fi Direct, mDNS, SoftAP, NFC, etc.
4. **Security by default** – encrypted sessions, integrity checks, explicit consent
5. **Progressive enhancement** – start simple (LAN), upgrade to faster mediums when available

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    Native App (Kotlin / Swift)           │
│  UI • Permissions • File pickers • Notifications         │
└──────────────────────┬──────────────────────────────────┘
                       │ UniFFI
┌──────────────────────▼──────────────────────────────────┐
│                 nearby-share (Rust core)                 │
│                                                         │
│  identity/   discovery/   session/   transfer/   crypto/ │
│  protocol/   api/                                       │
└──────────────────────┬──────────────────────────────────┘
                       │ Traits (DiscoveryService, Transport)
┌──────────────────────▼──────────────────────────────────┐
│              Platform Adapters                          │
│  BLE • mDNS • Wi-Fi Direct • SoftAP • NFC • QR          │
└─────────────────────────────────────────────────────────┘
```

### Module Responsibilities

| Module | Responsibility |
|--------|----------------|
| `identity` | Long-term + ephemeral device keys, fingerprints, trusted device store |
| `discovery` | Peer representation, discovery events (platform injects real scanning) |
| `session` | Handshake, medium negotiation, encryption setup, connection lifecycle |
| `protocol` | Versioned message types and framing |
| `transfer` | Chunking, resume, progress, multi-file queues, integrity |
| `crypto` | X25519, AEAD, BLAKE3, PIN derivation, key schedules |
| `api` | UniFFI-exported surface (stable, minimal, async-friendly) |

---

## Feature Roadmap

### Phase 0 – Foundation (current)
- [x] Workspace + crate scaffolding
- [x] Placeholder types
- [ ] Real `DeviceIdentity` with key material
- [ ] Expanded protocol messages
- [ ] Crypto primitives (X25519 + AEAD + BLAKE3)
- [ ] Transfer session with basic state machine
- [ ] UniFFI surface sketch

### Phase 1 – Minimum Viable Nearby
- LAN / mDNS discovery + TCP transport
- Offer → Accept/Reject → Progress → Complete flow
- End-to-end encrypted sessions
- Single + multi-file transfer
- Basic Android binding via UniFFI
- Progress reporting and cancellation

### Phase 2 – Robustness & UX
- Resumable transfers (chunk + sequence numbers)
- Folder transfer with structure preservation
- PIN protection + Favorites (auto-accept trusted devices)
- Checksums (BLAKE3)
- Concurrent transfer queue
- SoftAP / hotspot fallback
- QR-code out-of-band pairing

### Phase 3 – Differentiation
- Multi-receiver / broadcast send
- Bandwidth upgrade (BLE/LAN → Wi-Fi Direct)
- Stream payloads (live camera / screen / audio)
- Persistent trusted device cryptographic pairing
- Roaming-aware transfers
- Local web receiver (QR → browser download without app)

### Phase 4 – Power Features
- Clipboard sync while nearby
- Compression (zstd) option
- Transfer history (local only)
- Ghost mode (discoverable only by paired devices)
- Developer embedding API polish
- CLI companion

---

## Detailed Feature List

### Must-Have (Parity)

- Multi-medium discovery (BLE presence + mDNS + Wi-Fi Direct)
- Encrypted sessions (X25519 + AEAD)
- Files, folders, text, clipboard snippets
- Progress, cancel, resume
- Accept / reject + optional PIN
- Favorites with auto-accept
- No artificial size limits
- Multi-file queue
- Integrity verification

### Differentiators

**Smart Medium Selection**  
Start on the lowest-power/available medium, automatically upgrade when both peers support a faster path.

**SoftAP + QR Pairing**  
Works even when devices are not on the same Wi-Fi (common in hotels, guest networks, or when mDNS is blocked).

**Persistent Trusted Devices**  
Pair once → cryptographic trust → future transfers can be frictionless or require only silent confirmation.

**Multi-Receiver**  
One send action → multiple nearby devices.

**Stream Payloads**  
Support live data streams in addition to discrete files (inspired by Google Nearby Connections).

**Resumable + Roaming**  
Survive interruptions and brief network changes.

---

## Security Model

- Ephemeral session keys derived via X25519
- AEAD (ChaCha20-Poly1305 or AES-GCM) for all application data
- BLAKE3 for chunk and final integrity
- PIN derived from shared secret (similar to Nearby Share / Quick Share UX)
- Long-term identity keys for trusted-device recognition
- Explicit user consent for every new peer (unless favorited)
- No telemetry, no accounts, no phone-home

---

## Protocol Sketch (high level)

```
Hello { version, device_name, identity_fingerprint, supported_mediums }
Offer { files: [FileInfo], total_size, session_id }
Response { accepted: bool, pin_required?: bool }
SessionAccept { ... }
Chunk { session_id, file_id, seq, data, is_last }
Progress { bytes_transferred, total }
Complete { checksums }
Cancel { reason }
```

All messages are length-prefixed and encrypted after the handshake.

---

## UniFFI Surface (initial target)

```rust
// Conceptual – will be refined
fn create_identity(name: String) -> DeviceIdentity;
fn start_discovery(listener: Arc<dyn DiscoveryListener>);
fn stop_discovery();
fn send_files(peer_id: String, paths: Vec<String>) -> TransferHandle;
fn accept_transfer(session_id: String);
fn reject_transfer(session_id: String);
fn cancel_transfer(session_id: String);
```

Events flow back through listener callbacks / streams.

---

## Non-Goals (for now)

- Internet / relay / TURN paths
- Account systems
- Cloud storage integration
- Compatibility with Google Quick Share wire protocol (we may add an optional compatibility layer later)
- Windows / desktop first-class support (can come after mobile is solid)

---

## Inspiration & References

- LocalSend (UX, HTTPS local model, favorites, share-via-link)
- Google Nearby Connections / Quick Share (medium upgrade, payload types, BLE triggering)
- NearDrop (protocol documentation)
- DashBeam / Iroh-style ideas (resume, multi-peer, integrity)
- Apple AirDrop (proximity feel, consent model)

---

## Next Immediate Steps

1. Expand core types and add real crypto scaffolding
2. Define stable message and session state machines
3. Sketch UniFFI-exported API
4. Implement a pure-Rust in-memory / loopback transport for tests
5. Android discovery + TCP transport adapter

---

*This document is living. Update it as decisions are made.*
