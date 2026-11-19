//! Cryptography and device identity.
//!
//! - Identity keys: X25519 (`x25519-dalek`)
//! - Session AEAD: ChaCha20-Poly1305
//! - Integrity: BLAKE3
//! - Key derivation: HKDF-SHA256
//! - PIN: derived from session root for Nearby Share–style UX

use std::fmt;

use blake3::Hasher as Blake3;
use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Nonce};
use hkdf::Hkdf;
use rand::rngs::OsRng;
use rand_core::RngCore;
use sha2::Sha256;
use x25519_dalek::{PublicKey as X25519Public, StaticSecret};
use zeroize::{Zeroize, ZeroizeOnDrop};

/// 32-byte key material.
pub type SecretKey = [u8; 32];

/// 32-byte public key material (X25519).
pub type PublicKey = [u8; 32];

/// Short fingerprint of a public key.
pub type Fingerprint = String;

/// Long-term or ephemeral device identity.
///
/// The secret key is zeroized on drop.
#[derive(Clone, ZeroizeOnDrop)]
pub struct DeviceIdentity {
    /// Human-readable name shown to peers.
    #[zeroize(skip)]
    pub name: String,
    /// X25519 public key bytes.
    #[zeroize(skip)]
    pub public_key: PublicKey,
    /// X25519 static secret – never leave the device / Rust side.
    pub(crate) secret_key: SecretKey,
    /// Short fingerprint derived from the public key (BLAKE3).
    #[zeroize(skip)]
    pub fingerprint: Fingerprint,
}

impl fmt::Debug for DeviceIdentity {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("DeviceIdentity")
            .field("name", &self.name)
            .field("fingerprint", &self.fingerprint)
            .field("public_key", &hex_prefix(&self.public_key))
            .finish_non_exhaustive()
    }
}

impl DeviceIdentity {
    /// Generate a new random ephemeral identity.
    pub fn generate_ephemeral(name: &str) -> Self {
        let secret = StaticSecret::random_from_rng(OsRng);
        let public = X25519Public::from(&secret);

        let secret_key: SecretKey = secret.to_bytes();
        let public_key: PublicKey = *public.as_bytes();
        let fingerprint = fingerprint_from_public(&public_key);

        Self {
            name: name.to_string(),
            public_key,
            secret_key,
            fingerprint,
        }
    }

    /// Returns the public key bytes.
    pub fn public_key(&self) -> &PublicKey {
        &self.public_key
    }

    /// Returns a short fingerprint suitable for display / comparison.
    pub fn fingerprint(&self) -> &str {
        &self.fingerprint
    }

    /// Reconstruct the dalek static secret (for key exchange).
    fn static_secret(&self) -> StaticSecret {
        StaticSecret::from(self.secret_key)
    }
}

/// Derive a short fingerprint from a public key using BLAKE3.
fn fingerprint_from_public(pk: &PublicKey) -> Fingerprint {
    let hash = Blake3::new().update(pk).finalize();
    // First 8 bytes as hex → 16 character fingerprint
    hash.as_bytes()[..8]
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect()
}

fn hex_prefix(bytes: &[u8; 32]) -> String {
    bytes[..4]
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect::<Vec<_>>()
        .join("")
}

/// Session secrets established after a successful X25519 + HKDF handshake.
#[derive(Clone, Zeroize, ZeroizeOnDrop)]
pub struct SessionSecrets {
    /// Key used to encrypt data we send.
    pub send_key: SecretKey,
    /// Key used to decrypt data we receive.
    pub recv_key: SecretKey,
    /// Shared root secret (PIN derivation, further KDFs).
    pub root: SecretKey,
}

impl fmt::Debug for SessionSecrets {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SessionSecrets")
            .field("send_key", &hex_prefix(&self.send_key))
            .field("recv_key", &hex_prefix(&self.recv_key))
            .finish_non_exhaustive()
    }
}

/// Perform X25519 key exchange + HKDF to produce directional session keys.
///
/// The `info` parameter should be a protocol-specific label (e.g. b"NearbyShare-v1").
/// Keys are derived so that both sides compute matching send/recv keys when they
/// swap roles correctly (we use the lexicographic order of public keys to decide
/// which direction is "initiator").
pub fn perform_key_exchange(
    local: &DeviceIdentity,
    peer_public: &PublicKey,
) -> SessionSecrets {
    let local_secret = local.static_secret();
    let peer_pub = X25519Public::from(*peer_public);
    let shared = local_secret.diffie_hellman(&peer_pub);

    // HKDF-SHA256 extract + expand
    let hk = Hkdf::<Sha256>::new(None, shared.as_bytes());

    let mut root = [0u8; 32];
    hk.expand(b"NearbyShare-v1-root", &mut root)
        .expect("hkdf expand root");

    // Directional keys: sort public keys so both sides agree on "low" vs "high"
    let (send_label, recv_label) = if local.public_key < *peer_public {
        (b"NearbyShare-v1-send-low".as_slice(), b"NearbyShare-v1-send-high".as_slice())
    } else {
        (b"NearbyShare-v1-send-high".as_slice(), b"NearbyShare-v1-send-low".as_slice())
    };

    let mut send_key = [0u8; 32];
    let mut recv_key = [0u8; 32];
    hk.expand(send_label, &mut send_key).expect("hkdf expand send");
    hk.expand(recv_label, &mut recv_key).expect("hkdf expand recv");

    SessionSecrets {
        send_key,
        recv_key,
        root,
    }
}

/// Derive a 4-digit PIN from the session root (Nearby Share style UX).
pub fn derive_pin(secrets: &SessionSecrets) -> String {
    let mut out = [0u8; 4];
    let hk = Hkdf::<Sha256>::new(None, &secrets.root);
    hk.expand(b"NearbyShare-v1-pin", &mut out)
        .expect("hkdf expand pin");
    let value = u32::from_be_bytes(out) % 10_000;
    format!("{:04}", value)
}

/// BLAKE3 hash of arbitrary data (32-byte digest).
pub fn hash_bytes(data: &[u8]) -> [u8; 32] {
    *Blake3::new().update(data).finalize().as_bytes()
}

/// Encrypt plaintext with ChaCha20-Poly1305 using the given key.
///
/// Returns `nonce || ciphertext+tag`. Nonce is randomly generated.
pub fn encrypt(key: &SecretKey, plaintext: &[u8]) -> Result<Vec<u8>, CryptoError> {
    let cipher = ChaCha20Poly1305::new_from_slice(key)
        .map_err(|_| CryptoError::InvalidKey)?;

    let mut nonce_bytes = [0u8; 12];
    OsRng.fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);

    let ciphertext = cipher
        .encrypt(nonce, plaintext)
        .map_err(|_| CryptoError::EncryptionFailed)?;

    let mut out = Vec::with_capacity(12 + ciphertext.len());
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

/// Decrypt a blob produced by [`encrypt`].
pub fn decrypt(key: &SecretKey, data: &[u8]) -> Result<Vec<u8>, CryptoError> {
    if data.len() < 12 {
        return Err(CryptoError::InvalidCiphertext);
    }

    let (nonce_bytes, ciphertext) = data.split_at(12);
    let cipher = ChaCha20Poly1305::new_from_slice(key)
        .map_err(|_| CryptoError::InvalidKey)?;
    let nonce = Nonce::from_slice(nonce_bytes);

    cipher
        .decrypt(nonce, ciphertext)
        .map_err(|_| CryptoError::DecryptionFailed)
}

/// Cryptography-related errors.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CryptoError {
    /// Key material was rejected by the AEAD implementation.
    InvalidKey,
    /// Encryption failed.
    EncryptionFailed,
    /// Ciphertext was too short or authentication failed.
    InvalidCiphertext,
    /// Decryption / authentication failed.
    DecryptionFailed,
}

impl fmt::Display for CryptoError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CryptoError::InvalidKey => write!(f, "invalid key material"),
            CryptoError::EncryptionFailed => write!(f, "encryption failed"),
            CryptoError::InvalidCiphertext => write!(f, "invalid ciphertext"),
            CryptoError::DecryptionFailed => write!(f, "decryption or authentication failed"),
        }
    }
}

impl std::error::Error for CryptoError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_generation_produces_valid_keys() {
        let id = DeviceIdentity::generate_ephemeral("Pixel");
        assert_eq!(id.name, "Pixel");
        assert_eq!(id.fingerprint().len(), 16); // 8 bytes hex
        assert_ne!(id.public_key, [0u8; 32]);
    }

    #[test]
    fn different_identities_differ() {
        let a = DeviceIdentity::generate_ephemeral("Alice");
        let b = DeviceIdentity::generate_ephemeral("Bob");
        assert_ne!(a.public_key, b.public_key);
        assert_ne!(a.fingerprint, b.fingerprint);
    }

    #[test]
    fn key_exchange_is_symmetric() {
        let alice = DeviceIdentity::generate_ephemeral("Alice");
        let bob = DeviceIdentity::generate_ephemeral("Bob");

        let secrets_a = perform_key_exchange(&alice, bob.public_key());
        let secrets_b = perform_key_exchange(&bob, alice.public_key());

        // Root must match
        assert_eq!(secrets_a.root, secrets_b.root);

        // Send/recv are swapped from each side's perspective
        assert_eq!(secrets_a.send_key, secrets_b.recv_key);
        assert_eq!(secrets_a.recv_key, secrets_b.send_key);
    }

    #[test]
    fn pin_is_four_digits_and_stable() {
        let alice = DeviceIdentity::generate_ephemeral("Alice");
        let bob = DeviceIdentity::generate_ephemeral("Bob");
        let secrets = perform_key_exchange(&alice, bob.public_key());

        let pin1 = derive_pin(&secrets);
        let pin2 = derive_pin(&secrets);
        assert_eq!(pin1, pin2);
        assert_eq!(pin1.len(), 4);
        assert!(pin1.chars().all(|c| c.is_ascii_digit()));
    }

    #[test]
    fn encrypt_decrypt_roundtrip() {
        let key = [0x42u8; 32];
        let plaintext = b"hello nearby share";

        let sealed = encrypt(&key, plaintext).expect("encrypt");
        let opened = decrypt(&key, &sealed).expect("decrypt");
        assert_eq!(opened, plaintext);
    }

    #[test]
    fn decrypt_fails_on_tamper() {
        let key = [0x42u8; 32];
        let mut sealed = encrypt(&key, b"secret").expect("encrypt");
        // Flip a bit in the ciphertext
        if let Some(b) = sealed.last_mut() {
            *b ^= 0xff;
        }
        assert!(decrypt(&key, &sealed).is_err());
    }

    #[test]
    fn hash_bytes_stable() {
        let h1 = hash_bytes(b"test");
        let h2 = hash_bytes(b"test");
        assert_eq!(h1, h2);
        assert_ne!(h1, hash_bytes(b"other"));
    }
}
