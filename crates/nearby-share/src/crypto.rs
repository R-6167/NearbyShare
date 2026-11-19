//! Cryptography and device identity.
//!
//! Handles key generation, session encryption material, and authentication helpers.
//! Real implementations will use x25519-dalek, chacha20poly1305 / aes-gcm, and blake3.
//! For now we provide the shape and deterministic placeholders so the rest of the
//! crate can be developed against a stable API.

use std::fmt;

/// 32-byte secret key material (placeholder).
pub type SecretKey = [u8; 32];

/// 32-byte public key material (placeholder).
pub type PublicKey = [u8; 32];

/// Fingerprint of a public key (short human-readable or hex form).
pub type Fingerprint = String;

/// Long-term or ephemeral device identity.
#[derive(Clone)]
pub struct DeviceIdentity {
    /// Human-readable name shown to peers.
    pub name: String,
    /// Public identity key (placeholder bytes).
    pub public_key: PublicKey,
    /// Secret identity key – never leave the device / Rust side.
    pub(crate) secret_key: SecretKey,
    /// Short fingerprint derived from the public key.
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
    /// Generate a new ephemeral identity.
    ///
    /// In production this will be a real X25519 / Ed25519 keypair.
    /// Currently uses a simple deterministic derivation from the name
    /// so tests are reproducible.
    pub fn generate_ephemeral(name: &str) -> Self {
        let mut secret_key = [0u8; 32];
        let mut public_key = [0u8; 32];

        // Placeholder derivation – replace with real CSPRNG + X25519.
        let name_bytes = name.as_bytes();
        for (i, b) in secret_key.iter_mut().enumerate() {
            *b = name_bytes
                .get(i % name_bytes.len().max(1))
                .copied()
                .unwrap_or(0)
                ^ (i as u8).wrapping_mul(31);
        }
        for (i, b) in public_key.iter_mut().enumerate() {
            *b = secret_key[i].wrapping_add(0xA5).wrapping_mul(17);
        }

        let fingerprint = fingerprint_from_public(&public_key);

        Self {
            name: name.to_string(),
            public_key,
            secret_key,
            fingerprint,
        }
    }

    /// Returns the public key.
    pub fn public_key(&self) -> &PublicKey {
        &self.public_key
    }

    /// Returns a short fingerprint suitable for display / comparison.
    pub fn fingerprint(&self) -> &str {
        &self.fingerprint
    }
}

/// Derive a short fingerprint from a public key.
fn fingerprint_from_public(pk: &PublicKey) -> Fingerprint {
    // Placeholder – real version will use BLAKE3 or SHA-256 truncated.
    let mut acc: u32 = 0;
    for (i, b) in pk.iter().enumerate() {
        acc = acc.wrapping_add((*b as u32).wrapping_mul((i as u32).wrapping_add(1)));
    }
    format!("{:08x}", acc)
}

fn hex_prefix(bytes: &[u8; 32]) -> String {
    bytes[..4]
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect::<Vec<_>>()
        .join("")
}

/// Session secrets established after a successful handshake.
#[derive(Clone)]
pub struct SessionSecrets {
    /// Key used to encrypt data sent by us.
    pub send_key: SecretKey,
    /// Key used to decrypt data received from the peer.
    pub recv_key: SecretKey,
    /// Shared root secret (useful for PIN derivation, etc.).
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

/// Perform a key exchange between our identity and a peer public key.
///
/// Placeholder implementation. Real version will use X25519 + HKDF.
pub fn perform_key_exchange(
    local: &DeviceIdentity,
    peer_public: &PublicKey,
) -> SessionSecrets {
    let mut root = [0u8; 32];
    for i in 0..32 {
        root[i] = local.secret_key[i] ^ peer_public[i] ^ 0x5A;
    }

    let mut send_key = [0u8; 32];
    let mut recv_key = [0u8; 32];
    for i in 0..32 {
        send_key[i] = root[i].wrapping_add(1);
        recv_key[i] = root[i].wrapping_add(2);
    }

    SessionSecrets {
        send_key,
        recv_key,
        root,
    }
}

/// Derive a 4-digit PIN from session material (Nearby Share style UX).
pub fn derive_pin(secrets: &SessionSecrets) -> String {
    let mut acc: u32 = 0;
    for (i, b) in secrets.root.iter().enumerate() {
        acc = acc.wrapping_add((*b as u32).wrapping_mul((i as u32).wrapping_add(3)));
    }
    format!("{:04}", acc % 10000)
}

/// Simple integrity hash placeholder (will become BLAKE3).
pub fn hash_bytes(data: &[u8]) -> [u8; 32] {
    let mut out = [0u8; 32];
    for (i, chunk) in data.chunks(32).enumerate() {
        for (j, b) in chunk.iter().enumerate() {
            out[j] ^= b.wrapping_add(i as u8);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_generation_is_deterministic_for_same_name() {
        let a = DeviceIdentity::generate_ephemeral("Pixel");
        let b = DeviceIdentity::generate_ephemeral("Pixel");
        assert_eq!(a.fingerprint, b.fingerprint);
        assert_eq!(a.public_key, b.public_key);
    }

    #[test]
    fn different_names_produce_different_fingerprints() {
        let a = DeviceIdentity::generate_ephemeral("Pixel");
        let b = DeviceIdentity::generate_ephemeral("Galaxy");
        assert_ne!(a.fingerprint, b.fingerprint);
    }

    #[test]
    fn key_exchange_and_pin() {
        let alice = DeviceIdentity::generate_ephemeral("Alice");
        let bob = DeviceIdentity::generate_ephemeral("Bob");
        let secrets = perform_key_exchange(&alice, bob.public_key());
        let pin = derive_pin(&secrets);
        assert_eq!(pin.len(), 4);
        assert!(pin.chars().all(|c| c.is_ascii_digit()));
    }
}
