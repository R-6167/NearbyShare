//! NearbyShare – Offline peer-to-peer nearby file sharing core.
//!
//! This crate contains the platform-agnostic logic:
//! - Device discovery
//! - Protocol messages
//! - Secure transfer
//! - Cryptography / identity
//!
//! Platform bindings (Android via UniFFI, later iOS) live on top of this crate.

#![deny(missing_docs)]

pub mod crypto;
pub mod discovery;
pub mod protocol;
pub mod transfer;

/// Library version
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// Simple health-check function (useful for verifying bindings later)
pub fn hello() -> String {
    format!("NearbyShare core v{}", VERSION)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_is_set() {
        assert!(!VERSION.is_empty());
    }

    #[test]
    fn hello_works() {
        let msg = hello();
        assert!(msg.contains("NearbyShare"));
    }
}
