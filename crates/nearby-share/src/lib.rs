//! NearbyShare – Offline peer-to-peer nearby file sharing core.
//!
//! This crate contains the platform-agnostic logic:
//! - Device identity & cryptography
//! - Discovery abstractions
//! - Secure session (handshake + AEAD channel)
//! - Protocol messages + binary codec
//! - Transfer session management
//! - UniFFI surface for Android / iOS (`api` module, `ffi` feature)
//!
//! See `docs/DESIGN.md` in the repository root for the full architecture and roadmap.

#![deny(missing_docs)]

#[cfg(feature = "ffi")]
uniffi::setup_scaffolding!();

pub mod crypto;
pub mod discovery;
pub mod protocol;
pub mod session;
pub mod transfer;

#[cfg(feature = "ffi")]
pub mod api;

// Re-export the most commonly used types at the crate root for convenience.
pub use crypto::{
    decrypt, derive_pin, encrypt, hash_bytes, perform_key_exchange, CryptoError, DeviceIdentity,
    PublicKey, SecretKey, SessionSecrets,
};
pub use discovery::{DiscoveryError, DiscoveryEvent, DiscoveryService, Peer};
pub use protocol::{CodecError, FileInfo, Medium, Message, PROTOCOL_VERSION};
pub use session::{framing, HelloPayload, Role, SecureSession, SessionError, SessionPhase};
pub use transfer::{TransferDirection, TransferSession, TransferState};

#[cfg(feature = "ffi")]
pub use api::{
    frame_encode, frame_try_decode, library_hello, library_version, FfiIdentity, FfiMedium,
    FfiSession, FfiSessionPhase, FrameDecodeResult, IdentityInfo, NearbyError,
};

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
    fn end_to_end_codec_over_secure_session() {
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

        let offer = Message::Offer {
            session_id: "sess-42".into(),
            files: vec![FileInfo {
                file_id: 1,
                name: "photo.jpg".into(),
                size: 1_024_000,
                mime_type: Some("image/jpeg".into()),
                relative_path: None,
            }],
            total_bytes: 1_024_000,
        };

        let sealed = alice.seal(&offer.encode()).unwrap();
        let wire = framing::encode_frame(&sealed);
        let (payload, _) = framing::try_decode_frame(&wire).unwrap();
        let opened = bob.open(&payload).unwrap();
        let decoded = Message::decode(&opened).unwrap();
        assert_eq!(decoded, offer);
    }

    #[cfg(feature = "ffi")]
    #[test]
    fn ffi_session_handshake_roundtrip() {
        let alice_id = FfiIdentity::generate("Alice".into());
        let bob_id = FfiIdentity::generate("Bob".into());

        let alice = FfiSession::new(
            alice_id,
            true,
            vec![FfiMedium::WifiLan, FfiMedium::Ble],
        );
        let bob = FfiSession::new(
            bob_id,
            false,
            vec![FfiMedium::WifiLan, FfiMedium::SoftAp],
        );

        let a_hello = alice.begin_handshake().unwrap();
        let b_hello = bob.begin_handshake().unwrap();
        alice.complete_handshake(b_hello).unwrap();
        bob.complete_handshake(a_hello).unwrap();

        assert!(!alice.pin().is_empty());
        assert_eq!(alice.pin(), bob.pin());
        assert_eq!(bob.peer_name().as_deref(), Some("Alice"));

        let sealed = alice.seal(b"ping".to_vec()).unwrap();
        let opened = bob.open(sealed).unwrap();
        assert_eq!(opened, b"ping");

        assert_eq!(library_version(), VERSION);
        assert!(library_hello().contains("NearbyShare"));
    }
}
