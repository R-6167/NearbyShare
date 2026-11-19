//! NearbyShare – Offline peer-to-peer nearby file sharing core.
//!
//! This crate contains the platform-agnostic logic:
//! - Device identity & cryptography
//! - Discovery abstractions
//! - Protocol messages
//! - Transfer session management
//!
//! Platform bindings (Android via UniFFI, later iOS) live on top of this crate.
//!
//! See `docs/DESIGN.md` in the repository root for the full architecture and roadmap.

#![deny(missing_docs)]

pub mod crypto;
pub mod discovery;
pub mod protocol;
pub mod transfer;

// Re-export the most commonly used types at the crate root for convenience.
pub use crypto::{
    decrypt, derive_pin, encrypt, hash_bytes, perform_key_exchange, CryptoError, DeviceIdentity,
    PublicKey, SecretKey, SessionSecrets,
};
pub use discovery::{DiscoveryError, DiscoveryEvent, DiscoveryService, Peer};
pub use protocol::{FileInfo, Medium, Message, PROTOCOL_VERSION};
pub use transfer::{TransferDirection, TransferSession, TransferState};

/// Library version.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// Simple health-check function (useful for verifying bindings later).
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

    #[test]
    fn end_to_end_identity_and_session_smoke() {
        let alice = DeviceIdentity::generate_ephemeral("Alice");
        let bob = DeviceIdentity::generate_ephemeral("Bob");

        let secrets_a = perform_key_exchange(&alice, bob.public_key());
        let secrets_b = perform_key_exchange(&bob, alice.public_key());

        // Symmetric root + swapped directional keys
        assert_eq!(secrets_a.root, secrets_b.root);
        assert_eq!(secrets_a.send_key, secrets_b.recv_key);

        let pin = derive_pin(&secrets_a);
        assert_eq!(pin.len(), 4);

        // Encrypt with Alice's send key, decrypt with Bob's recv key
        let sealed = encrypt(&secrets_a.send_key, b"hello peer").expect("encrypt");
        let opened = decrypt(&secrets_b.recv_key, &sealed).expect("decrypt");
        assert_eq!(opened, b"hello peer");

        let files = vec![FileInfo {
            file_id: 1,
            name: "photo.jpg".into(),
            size: 1_024_000,
            mime_type: Some("image/jpeg".into()),
            relative_path: None,
        }];

        let mut session =
            TransferSession::new_outgoing("sess-1".into(), bob.fingerprint().into(), files);
        assert!(session.is_active());
        session.accept();
        session.start(Some(Medium::WifiLan));
        session.update_progress(512_000, 0);
        session.complete();
        assert!(!session.is_active());
    }
}
