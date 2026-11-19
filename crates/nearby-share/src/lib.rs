//! NearbyShare – Offline peer-to-peer nearby file sharing core.
//!
//! This crate contains the platform-agnostic logic:
//! - Device identity & cryptography
//! - Discovery abstractions
//! - Secure session (handshake + AEAD channel)
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
pub mod session;
pub mod transfer;

// Re-export the most commonly used types at the crate root for convenience.
pub use crypto::{
    decrypt, derive_pin, encrypt, hash_bytes, perform_key_exchange, CryptoError, DeviceIdentity,
    PublicKey, SecretKey, SessionSecrets,
};
pub use discovery::{DiscoveryError, DiscoveryEvent, DiscoveryService, Peer};
pub use protocol::{FileInfo, Medium, Message, PROTOCOL_VERSION};
pub use session::{framing, HelloPayload, Role, SecureSession, SessionError, SessionPhase};
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
    fn end_to_end_secure_session_and_transfer_smoke() {
        let alice_id = DeviceIdentity::generate_ephemeral("Alice");
        let bob_id = DeviceIdentity::generate_ephemeral("Bob");

        let mut alice = SecureSession::new(
            alice_id,
            Role::Initiator,
            vec![Medium::WifiLan, Medium::Ble],
        );
        let mut bob = SecureSession::new(
            bob_id,
            Role::Responder,
            vec![Medium::WifiLan, Medium::SoftAp],
        );

        let a_hello = alice.begin_handshake().unwrap();
        let b_hello = bob.begin_handshake().unwrap();
        alice.complete_handshake(&b_hello).unwrap();
        bob.complete_handshake(&a_hello).unwrap();

        assert_eq!(alice.pin(), bob.pin());
        assert_eq!(alice.negotiated_mediums(), vec![Medium::WifiLan]);

        // Encrypted application data
        let frame = framing::encode_frame(&alice.seal(b"chunk-0").unwrap());
        let (payload, _) = framing::try_decode_frame(&frame).unwrap();
        assert_eq!(bob.open(&payload).unwrap(), b"chunk-0");

        // Transfer state machine still works independently
        let files = vec![FileInfo {
            file_id: 1,
            name: "photo.jpg".into(),
            size: 1_024_000,
            mime_type: Some("image/jpeg".into()),
            relative_path: None,
        }];
        let mut transfer = TransferSession::new_outgoing(
            "sess-1".into(),
            bob.peer_fingerprint().unwrap().into(),
            files,
        );
        transfer.accept();
        transfer.start(Some(Medium::WifiLan));
        transfer.update_progress(512_000, 0);
        transfer.complete();
        assert!(!transfer.is_active());
    }
}
