//! Device discovery layer.
//!
//! Responsible for finding nearby peers (mDNS, BLE, Wi-Fi Direct, etc.).
//! Platform-specific advertising/scanning is injected from the native side
//! via the `DiscoveryService` trait.

use crate::crypto::PublicKey;
use crate::protocol::Medium;

/// A discovered peer.
#[derive(Debug, Clone)]
pub struct Peer {
    /// Human-readable name.
    pub name: String,
    /// Stable or session id (often the fingerprint).
    pub id: String,
    /// Public identity key (if known at discovery time).
    pub public_key: Option<PublicKey>,
    /// Fingerprint for quick comparison.
    pub fingerprint: Option<String>,
    /// Mediums the peer advertised.
    pub supported_mediums: Vec<Medium>,
    /// Signal / proximity hint (higher = closer), if available.
    pub rssi: Option<i32>,
}

impl Peer {
    /// Convenience constructor for simple LAN peers.
    pub fn new(name: impl Into<String>, id: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            id: id.into(),
            public_key: None,
            fingerprint: None,
            supported_mediums: vec![Medium::WifiLan],
            rssi: None,
        }
    }
}

/// Events emitted by a discovery implementation.
#[derive(Debug, Clone)]
pub enum DiscoveryEvent {
    /// A new peer appeared or was updated.
    PeerFound(Peer),
    /// A previously seen peer is gone.
    PeerLost { id: String },
}

/// Discovery service trait – implemented by platform adapters.
pub trait DiscoveryService {
    /// Start advertising this device so others can find it.
    fn start_advertising(&mut self) -> Result<(), DiscoveryError>;

    /// Stop advertising.
    fn stop_advertising(&mut self) -> Result<(), DiscoveryError>;

    /// Start scanning for peers.
    fn start_scanning(&mut self) -> Result<(), DiscoveryError>;

    /// Stop scanning.
    fn stop_scanning(&mut self) -> Result<(), DiscoveryError>;
}

/// Errors that can occur during discovery.
#[derive(Debug)]
pub enum DiscoveryError {
    /// Discovery is not supported on this platform / build.
    Unsupported,
    /// Permission denied (Bluetooth, Location, etc.).
    PermissionDenied,
    /// Internal / platform error.
    Internal(String),
}

impl std::fmt::Display for DiscoveryError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DiscoveryError::Unsupported => {
                write!(f, "Discovery is not supported on this platform")
            }
            DiscoveryError::PermissionDenied => {
                write!(f, "Required permission denied")
            }
            DiscoveryError::Internal(msg) => write!(f, "Internal error: {}", msg),
        }
    }
}

impl std::error::Error for DiscoveryError {}
