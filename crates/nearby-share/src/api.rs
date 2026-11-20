//! UniFFI-exported API surface for Android (Kotlin) and iOS (Swift).
//!
//! Enable with the `ffi` feature (on by default).
//! The Android demo currently also uses a stable C ABI (`c_api` / `c_msg`) via JNA
//! so CI can ship APKs without running `uniffi-bindgen` on the agent. Both surfaces
//! call the same session/crypto core.

use std::sync::{Arc, Mutex};

use crate::crypto::{hash_bytes, DeviceIdentity};
use crate::protocol::{FileInfo, Medium, Message};
use crate::session::{Role, SecureSession, SessionError, SessionPhase};

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/// Public error type for the FFI layer.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error, thiserror::Error)]
pub enum NearbyError {
    /// Session or API used in an invalid state.
    #[error("invalid state: {reason}")]
    InvalidState {
        /// Human-readable reason.
        reason: String,
    },
    /// Cryptography failure.
    #[error("crypto error: {reason}")]
    Crypto {
        /// Human-readable reason.
        reason: String,
    },
    /// Protocol / codec failure.
    #[error("protocol error: {reason}")]
    Protocol {
        /// Human-readable reason.
        reason: String,
    },
    /// Generic internal error.
    #[error("{message}")]
    Internal {
        /// Error message.
        message: String,
    },
}

impl From<SessionError> for NearbyError {
    fn from(value: SessionError) -> Self {
        match value {
            SessionError::InvalidState => NearbyError::InvalidState {
                reason: "invalid session state".into(),
            },
            SessionError::Closed => NearbyError::InvalidState {
                reason: "session closed".into(),
            },
            SessionError::VersionMismatch { peer } => NearbyError::Protocol {
                reason: format!("unsupported peer protocol version {peer}"),
            },
            SessionError::MalformedPeerHello => NearbyError::Protocol {
                reason: "malformed peer hello".into(),
            },
            SessionError::Crypto(e) => NearbyError::Crypto {
                reason: e.to_string(),
            },
        }
    }
}

// ---------------------------------------------------------------------------
// Shared enums / records
// ---------------------------------------------------------------------------

/// Transport medium exposed to native code.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiMedium {
    /// Classic Bluetooth
    Bluetooth,
    /// Bluetooth Low Energy
    Ble,
    /// Same Wi-Fi / LAN
    WifiLan,
    /// Wi-Fi Direct
    WifiDirect,
    /// SoftAP hotspot
    SoftAp,
    /// QR / NFC only
    OutOfBand,
}

impl From<Medium> for FfiMedium {
    fn from(value: Medium) -> Self {
        match value {
            Medium::Bluetooth => FfiMedium::Bluetooth,
            Medium::Ble => FfiMedium::Ble,
            Medium::WifiLan => FfiMedium::WifiLan,
            Medium::WifiDirect => FfiMedium::WifiDirect,
            Medium::SoftAp => FfiMedium::SoftAp,
            Medium::OutOfBand => FfiMedium::OutOfBand,
        }
    }
}

impl From<FfiMedium> for Medium {
    fn from(value: FfiMedium) -> Self {
        match value {
            FfiMedium::Bluetooth => Medium::Bluetooth,
            FfiMedium::Ble => Medium::Ble,
            FfiMedium::WifiLan => Medium::WifiLan,
            FfiMedium::WifiDirect => Medium::WifiDirect,
            FfiMedium::SoftAp => Medium::SoftAp,
            FfiMedium::OutOfBand => Medium::OutOfBand,
        }
    }
}

/// Snapshot of a device identity safe to show in UI.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct IdentityInfo {
    /// Display name.
    pub name: String,
    /// Short fingerprint (hex).
    pub fingerprint: String,
    /// Public key bytes (32).
    pub public_key: Vec<u8>,
}

/// File metadata in an offer.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiFileInfo {
    /// Id within the session (1-based typical).
    pub file_id: u32,
    /// File name including extension.
    pub name: String,
    /// Size in bytes.
    pub size: u64,
}

/// Parsed offer for UI.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiOffer {
    /// Session id chosen by sender.
    pub session_id: String,
    /// Files being offered.
    pub files: Vec<FfiFileInfo>,
    /// Total bytes.
    pub total_bytes: u64,
}

/// Phase of a secure session.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum FfiSessionPhase {
    /// Not started.
    New,
    /// Handshake in progress.
    Handshaking,
    /// Keys established.
    Established {
        /// 4-digit PIN.
        pin: String,
    },
    /// Closed.
    Closed {
        /// Optional reason.
        reason: Option<String>,
    },
}

impl From<&SessionPhase> for FfiSessionPhase {
    fn from(value: &SessionPhase) -> Self {
        match value {
            SessionPhase::New => FfiSessionPhase::New,
            SessionPhase::Handshaking => FfiSessionPhase::Handshaking,
            SessionPhase::Established { pin } => FfiSessionPhase::Established { pin: pin.clone() },
            SessionPhase::Closed { reason } => FfiSessionPhase::Closed {
                reason: reason.clone(),
            },
        }
    }
}

// ---------------------------------------------------------------------------
// Identity
// ---------------------------------------------------------------------------

/// Opaque handle to a device identity (X25519 keypair).
#[derive(uniffi::Object)]
pub struct FfiIdentity {
    inner: DeviceIdentity,
}

#[uniffi::export]
impl FfiIdentity {
    /// Generate a new random ephemeral identity.
    #[uniffi::constructor]
    pub fn generate(name: String) -> Arc<Self> {
        Arc::new(Self {
            inner: DeviceIdentity::generate_ephemeral(&name),
        })
    }

    /// Display name.
    pub fn name(&self) -> String {
        self.inner.name.clone()
    }

    /// Short fingerprint for UI comparison.
    pub fn fingerprint(&self) -> String {
        self.inner.fingerprint.clone()
    }

    /// Full identity snapshot.
    pub fn info(&self) -> IdentityInfo {
        IdentityInfo {
            name: self.inner.name.clone(),
            fingerprint: self.inner.fingerprint.clone(),
            public_key: self.inner.public_key.to_vec(),
        }
    }
}

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

/// Secure session between this device and one peer.
#[derive(uniffi::Object)]
pub struct FfiSession {
    inner: Mutex<SecureSession>,
}

#[uniffi::export]
impl FfiSession {
    /// Create a new session.
    #[uniffi::constructor]
    pub fn new(identity: Arc<FfiIdentity>, initiator: bool, mediums: Vec<FfiMedium>) -> Arc<Self> {
        let role = if initiator {
            Role::Initiator
        } else {
            Role::Responder
        };
        let mediums: Vec<Medium> = mediums.into_iter().map(Into::into).collect();
        Arc::new(Self {
            inner: Mutex::new(SecureSession::new(identity.inner.clone(), role, mediums)),
        })
    }

    /// Current session phase.
    pub fn phase(&self) -> FfiSessionPhase {
        let guard = self.inner.lock().expect("session lock");
        FfiSessionPhase::from(guard.phase())
    }

    /// 4-digit PIN once established; empty otherwise.
    pub fn pin(&self) -> String {
        let guard = self.inner.lock().expect("session lock");
        guard.pin().unwrap_or("").to_string()
    }

    /// Peer display name once known.
    pub fn peer_name(&self) -> Option<String> {
        let guard = self.inner.lock().expect("session lock");
        guard.peer_name().map(str::to_string)
    }

    /// Peer fingerprint once known.
    pub fn peer_fingerprint(&self) -> Option<String> {
        let guard = self.inner.lock().expect("session lock");
        guard.peer_fingerprint().map(str::to_string)
    }

    /// Mediums supported by both peers.
    pub fn negotiated_mediums(&self) -> Vec<FfiMedium> {
        let guard = self.inner.lock().expect("session lock");
        guard
            .negotiated_mediums()
            .into_iter()
            .map(Into::into)
            .collect()
    }

    /// Produce our Hello bytes.
    pub fn begin_handshake(&self) -> Result<Vec<u8>, NearbyError> {
        let mut guard = self.inner.lock().expect("session lock");
        guard.begin_handshake().map_err(Into::into)
    }

    /// Finish handshake with the peer's Hello bytes.
    pub fn complete_handshake(&self, peer_hello: Vec<u8>) -> Result<(), NearbyError> {
        let mut guard = self.inner.lock().expect("session lock");
        guard.complete_handshake(&peer_hello).map_err(Into::into)
    }

    /// Encrypt an application payload.
    pub fn seal(&self, plaintext: Vec<u8>) -> Result<Vec<u8>, NearbyError> {
        let guard = self.inner.lock().expect("session lock");
        guard.seal(&plaintext).map_err(Into::into)
    }

    /// Decrypt an application payload.
    pub fn open(&self, ciphertext: Vec<u8>) -> Result<Vec<u8>, NearbyError> {
        let guard = self.inner.lock().expect("session lock");
        guard.open(&ciphertext).map_err(Into::into)
    }

    /// Close the session.
    pub fn close(&self, reason: Option<String>) {
        let mut guard = self.inner.lock().expect("session lock");
        guard.close(reason);
    }
}

// ---------------------------------------------------------------------------
// Streaming hasher
// ---------------------------------------------------------------------------

/// Incremental BLAKE3 hasher for large file transfers.
#[derive(uniffi::Object)]
pub struct FfiHasher {
    inner: Mutex<blake3::Hasher>,
}

#[uniffi::export]
impl FfiHasher {
    /// Create a new hasher.
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(blake3::Hasher::new()),
        })
    }

    /// Absorb more bytes.
    pub fn update(&self, data: Vec<u8>) {
        let mut guard = self.inner.lock().expect("hasher lock");
        guard.update(&data);
    }

    /// Finalize and return 32-byte digest (hasher should not be reused).
    pub fn finalize(&self) -> Vec<u8> {
        let guard = self.inner.lock().expect("hasher lock");
        guard.finalize().as_bytes().to_vec()
    }
}

// ---------------------------------------------------------------------------
// Protocol helpers
// ---------------------------------------------------------------------------

/// Encode a single-file Offer message.
#[uniffi::export]
pub fn msg_encode_offer(session_id: String, file_name: String, size: u64) -> Vec<u8> {
    Message::Offer {
        session_id,
        files: vec![FileInfo {
            file_id: 1,
            name: file_name,
            size,
            mime_type: None,
            relative_path: None,
        }],
        total_bytes: size,
    }
    .encode()
}

/// Encode a multi-file Offer message.
#[uniffi::export]
pub fn msg_encode_offer_multi(session_id: String, files: Vec<FfiFileInfo>) -> Vec<u8> {
    let total: u64 = files.iter().map(|f| f.size).sum();
    let mapped: Vec<FileInfo> = files
        .into_iter()
        .map(|f| FileInfo {
            file_id: f.file_id,
            name: f.name,
            size: f.size,
            mime_type: None,
            relative_path: None,
        })
        .collect();
    Message::Offer {
        session_id,
        files: mapped,
        total_bytes: total,
    }
    .encode()
}

/// Encode Response.
#[uniffi::export]
pub fn msg_encode_response(session_id: String, accepted: bool) -> Vec<u8> {
    Message::Response {
        session_id,
        accepted,
        reason: None,
    }
    .encode()
}

/// Encode Chunk.
#[uniffi::export]
pub fn msg_encode_chunk(
    session_id: String,
    file_id: u32,
    seq: u64,
    data: Vec<u8>,
    is_last: bool,
) -> Vec<u8> {
    Message::Chunk {
        session_id,
        file_id,
        seq,
        data,
        is_last,
    }
    .encode()
}

/// Encode Complete for a single file hash.
#[uniffi::export]
pub fn msg_encode_complete(session_id: String, file_id: u32, hash32: Vec<u8>) -> Result<Vec<u8>, NearbyError> {
    if hash32.len() != 32 {
        return Err(NearbyError::Protocol {
            reason: "hash must be 32 bytes".into(),
        });
    }
    let mut h = [0u8; 32];
    h.copy_from_slice(&hash32);
    Ok(Message::Complete {
        session_id,
        checksums: vec![(file_id, h)],
    }
    .encode())
}

/// Message tag 1..=9.
#[uniffi::export]
pub fn msg_tag(data: Vec<u8>) -> Result<u8, NearbyError> {
    match Message::decode(&data) {
        Ok(Message::Hello { .. }) => Ok(1),
        Ok(Message::Offer { .. }) => Ok(2),
        Ok(Message::Response { .. }) => Ok(3),
        Ok(Message::SessionReady { .. }) => Ok(4),
        Ok(Message::Chunk { .. }) => Ok(5),
        Ok(Message::Progress { .. }) => Ok(6),
        Ok(Message::Complete { .. }) => Ok(7),
        Ok(Message::Cancel { .. }) => Ok(8),
        Ok(Message::BatchComplete { .. }) => Ok(9),
        Err(e) => Err(NearbyError::Protocol {
            reason: e.to_string(),
        }),
    }
}

/// Parse an Offer.
#[uniffi::export]
pub fn msg_parse_offer(data: Vec<u8>) -> Result<FfiOffer, NearbyError> {
    match Message::decode(&data) {
        Ok(Message::Offer {
            session_id,
            files,
            total_bytes,
        }) => Ok(FfiOffer {
            session_id,
            files: files
                .into_iter()
                .map(|f| FfiFileInfo {
                    file_id: f.file_id,
                    name: f.name,
                    size: f.size,
                })
                .collect(),
            total_bytes,
        }),
        Ok(_) => Err(NearbyError::Protocol {
            reason: "not an Offer".into(),
        }),
        Err(e) => Err(NearbyError::Protocol {
            reason: e.to_string(),
        }),
    }
}

/// Parse Response accepted flag.
#[uniffi::export]
pub fn msg_parse_response_accepted(data: Vec<u8>) -> Result<bool, NearbyError> {
    match Message::decode(&data) {
        Ok(Message::Response { accepted, .. }) => Ok(accepted),
        Ok(_) => Err(NearbyError::Protocol {
            reason: "not a Response".into(),
        }),
        Err(e) => Err(NearbyError::Protocol {
            reason: e.to_string(),
        }),
    }
}

/// One-shot BLAKE3.
#[uniffi::export]
pub fn blake3_hash(data: Vec<u8>) -> Vec<u8> {
    hash_bytes(&data).to_vec()
}

// ---------------------------------------------------------------------------
// Framing + health
// ---------------------------------------------------------------------------

/// Library version string.
#[uniffi::export]
pub fn library_version() -> String {
    crate::VERSION.to_string()
}

/// Health-check string.
#[uniffi::export]
pub fn library_hello() -> String {
    crate::hello()
}

/// Length-prefix a payload (`u32` LE length || bytes).
#[uniffi::export]
pub fn frame_encode(payload: Vec<u8>) -> Vec<u8> {
    crate::session::framing::encode_frame(&payload)
}

/// Result of trying to pull one frame from a buffer.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FrameDecodeResult {
    /// True if a full frame was available.
    pub complete: bool,
    /// Payload bytes (empty if incomplete).
    pub payload: Vec<u8>,
    /// Bytes consumed from the buffer front (0 if incomplete).
    pub consumed: u32,
}

/// Try to decode one length-prefixed frame.
#[uniffi::export]
pub fn frame_try_decode(buffer: Vec<u8>) -> FrameDecodeResult {
    match crate::session::framing::try_decode_frame(&buffer) {
        Some((payload, consumed)) => FrameDecodeResult {
            complete: true,
            payload,
            consumed: consumed as u32,
        },
        None => FrameDecodeResult {
            complete: false,
            payload: Vec::new(),
            consumed: 0,
        },
    }
}
