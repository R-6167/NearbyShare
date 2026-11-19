//! Device discovery layer.
//!
//! Responsible for finding nearby peers (mDNS, BLE, Wi-Fi Direct, etc.).
//! Platform-specific advertising/scanning will be injected from the native side.

/// Placeholder for a discovered peer
#[derive(Debug, Clone)]
pub struct Peer {
    /// Human-readable name
    pub name: String,
    /// Unique device identifier
    pub id: String,
}

/// Discovery service trait (to be implemented / driven by platform code)
pub trait DiscoveryService {
    /// Start advertising this device
    fn start_advertising(&mut self) -> Result<(), DiscoveryError>;

    /// Stop advertising
    fn stop_advertising(&mut self) -> Result<(), DiscoveryError>;

    /// Start scanning for peers
    fn start_scanning(&mut self) -> Result<(), DiscoveryError>;

    /// Stop scanning
    fn stop_scanning(&mut self) -> Result<(), DiscoveryError>;
}

/// Errors that can occur during discovery
#[derive(Debug)]
pub enum DiscoveryError {
    /// Discovery is not supported on this platform
    Unsupported,
    /// Internal error
    Internal(String),
}

impl std::fmt::Display for DiscoveryError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DiscoveryError::Unsupported => write!(f, "Discovery is not supported on this platform"),
            DiscoveryError::Internal(msg) => write!(f, "Internal error: {}", msg),
        }
    }
}

impl std::error::Error for DiscoveryError {}
