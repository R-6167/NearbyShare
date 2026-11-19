//! UniFFI-exported API surface.
//!
//! This module defines the stable interface that Android (Kotlin) and later iOS (Swift)
//! bind against. Keep it small, explicit, and free of internal types that are awkward
//! across the FFI boundary.
//!
//! Enable with the `ffi` feature (on by default).

use std::sync::{Arc, Mutex};

use crate::crypto::DeviceIdentity;
use crate::protocol::Medium;
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

/// Phase of a secure session, for UI / state machines on the native side.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum FfiSessionPhase {
    /// Not started.
    New,
    /// Handshake in progress.
    Handshaking,
    /// Keys established; `pin` is available for confirmation UX.
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
// Identity handle
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
// Secure session handle
// ---------------------------------------------------------------------------

/// Secure session between this device and one peer.
///
/// Typical flow:
/// 1. `FfiSession::new(...)`
/// 2. `begin_handshake()` → send bytes to peer
/// 3. Receive peer hello bytes → `complete_handshake(peer_hello)`
/// 4. `seal` / `open` application payloads (already length-framed by caller if needed)
#[derive(uniffi::Object)]
pub struct FfiSession {
    inner: Mutex<SecureSession>,
}

#[uniffi::export]
impl FfiSession {
    /// Create a new session.
    ///
    /// * `identity` – local identity
    /// * `initiator` – true if we are opening the connection
    /// * `mediums` – mediums we support (order = preference)
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

    /// 4-digit PIN once established; empty string otherwise.
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

    /// Mediums supported by both peers (local preference order).
    pub fn negotiated_mediums(&self) -> Vec<FfiMedium> {
        let guard = self.inner.lock().expect("session lock");
        guard
            .negotiated_mediums()
            .into_iter()
            .map(Into::into)
            .collect()
    }

    /// Produce our Hello bytes and move to handshaking.
    pub fn begin_handshake(&self) -> Result<Vec<u8>, NearbyError> {
        let mut guard = self.inner.lock().expect("session lock");
        guard.begin_handshake().map_err(Into::into)
    }

    /// Finish handshake with the peer's Hello bytes.
    pub fn complete_handshake(&self, peer_hello: Vec<u8>) -> Result<(), NearbyError> {
        let mut guard = self.inner.lock().expect("session lock");
        guard.complete_handshake(&peer_hello).map_err(Into::into)
    }

    /// Encrypt an application payload (session must be established).
    pub fn seal(&self, plaintext: Vec<u8>) -> Result<Vec<u8>, NearbyError> {
        let guard = self.inner.lock().expect("session lock");
        guard.seal(&plaintext).map_err(Into::into)
    }

    /// Decrypt an application payload (session must be established).
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
// Free functions
// ---------------------------------------------------------------------------

/// Library version string.
#[uniffi::export]
pub fn library_version() -> String {
    crate::VERSION.to_string()
}

/// Health-check / smoke string for verifying the native binding loads.
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
    /// Number of bytes consumed from the front of the buffer (0 if incomplete).
    pub consumed: u32,
}

/// Try to decode one length-prefixed frame from `buffer`.
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
