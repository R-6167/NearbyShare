//! Secure session layer.
//!
//! Ties identity, X25519 key exchange, and ChaCha20-Poly1305 into a
//! connection state machine. The transport (TCP, BLE, …) is supplied by
//! the platform; this module only handles handshake and encryption.

use crate::crypto::{
    decrypt, derive_pin, encrypt, perform_key_exchange, CryptoError, DeviceIdentity, PublicKey,
    SessionSecrets,
};
use crate::protocol::{Medium, PROTOCOL_VERSION};

/// Role in the connection (who initiated).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    /// We initiated the connection.
    Initiator,
    /// We accepted an incoming connection.
    Responder,
}

/// Lifecycle of a secure session.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionPhase {
    /// Created, not yet handshaking.
    New,
    /// Hello sent / waiting for peer Hello.
    Handshaking,
    /// Keys established; application messages may flow.
    Established {
        /// 4-digit PIN for optional user confirmation.
        pin: String,
    },
    /// Closed cleanly or after error.
    Closed {
        reason: Option<String>,
    },
}

/// Errors from the session layer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionError {
    /// Operation is not valid in the current phase.
    InvalidState,
    /// Protocol version mismatch.
    VersionMismatch {
        peer: u16,
    },
    /// Underlying crypto failure.
    Crypto(CryptoError),
    /// Peer data was malformed.
    MalformedPeerHello,
    /// Session already closed.
    Closed,
}

impl std::fmt::Display for SessionError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SessionError::InvalidState => write!(f, "invalid session state for this operation"),
            SessionError::VersionMismatch { peer } => {
                write!(f, "peer protocol version {peer} is not supported")
            }
            SessionError::Crypto(e) => write!(f, "crypto error: {e}"),
            SessionError::MalformedPeerHello => write!(f, "malformed peer hello"),
            SessionError::Closed => write!(f, "session is closed"),
        }
    }
}

impl std::error::Error for SessionError {}

impl From<CryptoError> for SessionError {
    fn from(value: CryptoError) -> Self {
        SessionError::Crypto(value)
    }
}

/// Wire format for the unencrypted Hello used during handshake.
///
/// Layout (little-endian):
/// - u16 version
/// - u8  name_len
/// - name bytes
/// - 32-byte public key
/// - u8  medium_count
/// - medium_count × u8 medium tags
#[derive(Debug, Clone)]
pub struct HelloPayload {
    /// Protocol version.
    pub version: u16,
    /// Device display name.
    pub device_name: String,
    /// X25519 public key.
    pub public_key: PublicKey,
    /// Mediums we support.
    pub supported_mediums: Vec<Medium>,
}

impl HelloPayload {
    /// Encode to bytes for sending on the wire (pre-encryption).
    pub fn encode(&self) -> Vec<u8> {
        let name_bytes = self.device_name.as_bytes();
        let name_len = name_bytes.len().min(255) as u8;
        let mut out = Vec::with_capacity(2 + 1 + name_len as usize + 32 + 1 + self.supported_mediums.len());
        out.extend_from_slice(&self.version.to_le_bytes());
        out.push(name_len);
        out.extend_from_slice(&name_bytes[..name_len as usize]);
        out.extend_from_slice(&self.public_key);
        out.push(self.supported_mediums.len().min(255) as u8);
        for m in &self.supported_mediums {
            out.push(medium_to_tag(*m));
        }
        out
    }

    /// Decode from bytes received on the wire.
    pub fn decode(data: &[u8]) -> Result<Self, SessionError> {
        if data.len() < 2 + 1 + 32 + 1 {
            return Err(SessionError::MalformedPeerHello);
        }
        let version = u16::from_le_bytes([data[0], data[1]]);
        let name_len = data[2] as usize;
        let mut offset = 3;
        if data.len() < offset + name_len + 32 + 1 {
            return Err(SessionError::MalformedPeerHello);
        }
        let device_name = String::from_utf8_lossy(&data[offset..offset + name_len]).into_owned();
        offset += name_len;
        let mut public_key = [0u8; 32];
        public_key.copy_from_slice(&data[offset..offset + 32]);
        offset += 32;
        let medium_count = data[offset] as usize;
        offset += 1;
        if data.len() < offset + medium_count {
            return Err(SessionError::MalformedPeerHello);
        }
        let mut supported_mediums = Vec::with_capacity(medium_count);
        for i in 0..medium_count {
            if let Some(m) = tag_to_medium(data[offset + i]) {
                supported_mediums.push(m);
            }
        }
        Ok(Self {
            version,
            device_name,
            public_key,
            supported_mediums,
        })
    }
}

fn medium_to_tag(m: Medium) -> u8 {
    match m {
        Medium::Bluetooth => 1,
        Medium::Ble => 2,
        Medium::WifiLan => 3,
        Medium::WifiDirect => 4,
        Medium::SoftAp => 5,
        Medium::OutOfBand => 6,
    }
}

fn tag_to_medium(tag: u8) -> Option<Medium> {
    match tag {
        1 => Some(Medium::Bluetooth),
        2 => Some(Medium::Ble),
        3 => Some(Medium::WifiLan),
        4 => Some(Medium::WifiDirect),
        5 => Some(Medium::SoftAp),
        6 => Some(Medium::OutOfBand),
        _ => None,
    }
}

/// A secure session between local identity and one peer.
#[derive(Debug)]
pub struct SecureSession {
    /// Local identity.
    local: DeviceIdentity,
    /// Our role.
    role: Role,
    /// Current phase.
    phase: SessionPhase,
    /// Peer public key (set after handshake).
    peer_public_key: Option<PublicKey>,
    /// Peer display name.
    peer_name: Option<String>,
    /// Peer fingerprint.
    peer_fingerprint: Option<String>,
    /// Session secrets (set after handshake).
    secrets: Option<SessionSecrets>,
    /// Mediums advertised by the peer.
    peer_mediums: Vec<Medium>,
    /// Mediums we advertised.
    local_mediums: Vec<Medium>,
}

impl SecureSession {
    /// Start a new session as initiator or responder.
    pub fn new(local: DeviceIdentity, role: Role, local_mediums: Vec<Medium>) -> Self {
        Self {
            local,
            role,
            phase: SessionPhase::New,
            peer_public_key: None,
            peer_name: None,
            peer_fingerprint: None,
            secrets: None,
            peer_mediums: Vec::new(),
            local_mediums,
        }
    }

    /// Current phase.
    pub fn phase(&self) -> &SessionPhase {
        &self.phase
    }

    /// Local device name.
    pub fn local_name(&self) -> &str {
        &self.local.name
    }

    /// Peer name, if known.
    pub fn peer_name(&self) -> Option<&str> {
        self.peer_name.as_deref()
    }

    /// Peer fingerprint, if known.
    pub fn peer_fingerprint(&self) -> Option<&str> {
        self.peer_fingerprint.as_deref()
    }

    /// PIN once the session is established.
    pub fn pin(&self) -> Option<&str> {
        match &self.phase {
            SessionPhase::Established { pin } => Some(pin.as_str()),
            _ => None,
        }
    }

    /// Build our Hello payload (to send in cleartext during handshake).
    pub fn build_hello(&self) -> HelloPayload {
        HelloPayload {
            version: PROTOCOL_VERSION,
            device_name: self.local.name.clone(),
            public_key: self.local.public_key,
            supported_mediums: self.local_mediums.clone(),
        }
    }

    /// Encode our Hello for the wire and mark the session as handshaking.
    pub fn begin_handshake(&mut self) -> Result<Vec<u8>, SessionError> {
        match self.phase {
            SessionPhase::New | SessionPhase::Handshaking => {
                self.phase = SessionPhase::Handshaking;
                Ok(self.build_hello().encode())
            }
            SessionPhase::Closed { .. } => Err(SessionError::Closed),
            SessionPhase::Established { .. } => Err(SessionError::InvalidState),
        }
    }

    /// Process a peer Hello. On success the session becomes `Established`.
    pub fn complete_handshake(&mut self, peer_hello_bytes: &[u8]) -> Result<(), SessionError> {
        if matches!(self.phase, SessionPhase::Closed { .. }) {
            return Err(SessionError::Closed);
        }

        let peer = HelloPayload::decode(peer_hello_bytes)?;
        if peer.version != PROTOCOL_VERSION {
            return Err(SessionError::VersionMismatch {
                peer: peer.version,
            });
        }

        let secrets = perform_key_exchange(&self.local, &peer.public_key);
        let pin = derive_pin(&secrets);

        // Derive a display fingerprint from the peer public key (same method as identity).
        let peer_fp = {
            let hash = blake3::hash(&peer.public_key);
            hash.as_bytes()[..8]
                .iter()
                .map(|b| format!("{:02x}", b))
                .collect::<String>()
        };

        self.peer_public_key = Some(peer.public_key);
        self.peer_name = Some(peer.device_name);
        self.peer_fingerprint = Some(peer_fp);
        self.peer_mediums = peer.supported_mediums;
        self.secrets = Some(secrets);
        self.phase = SessionPhase::Established { pin };

        Ok(())
    }

    /// Encrypt an application payload (only valid once established).
    pub fn seal(&self, plaintext: &[u8]) -> Result<Vec<u8>, SessionError> {
        let secrets = self.secrets.as_ref().ok_or(SessionError::InvalidState)?;
        if !matches!(self.phase, SessionPhase::Established { .. }) {
            return Err(SessionError::InvalidState);
        }
        encrypt(&secrets.send_key, plaintext).map_err(Into::into)
    }

    /// Decrypt an application payload (only valid once established).
    pub fn open(&self, ciphertext: &[u8]) -> Result<Vec<u8>, SessionError> {
        let secrets = self.secrets.as_ref().ok_or(SessionError::InvalidState)?;
        if !matches!(self.phase, SessionPhase::Established { .. }) {
            return Err(SessionError::InvalidState);
        }
        decrypt(&secrets.recv_key, ciphertext).map_err(Into::into)
    }

    /// Intersection of local and peer mediums (preference order: local order).
    pub fn negotiated_mediums(&self) -> Vec<Medium> {
        self.local_mediums
            .iter()
            .copied()
            .filter(|m| self.peer_mediums.contains(m))
            .collect()
    }

    /// Close the session.
    pub fn close(&mut self, reason: Option<String>) {
        self.phase = SessionPhase::Closed { reason };
        self.secrets = None;
    }

    /// Role of this endpoint.
    pub fn role(&self) -> Role {
        self.role
    }
}

/// Length-prefixed framing helpers for a byte stream.
///
/// Frame = u32 LE length || payload
pub mod framing {
    /// Encode a payload as a length-prefixed frame.
    pub fn encode_frame(payload: &[u8]) -> Vec<u8> {
        let len = payload.len() as u32;
        let mut out = Vec::with_capacity(4 + payload.len());
        out.extend_from_slice(&len.to_le_bytes());
        out.extend_from_slice(payload);
        out
    }

    /// Try to parse one frame from a buffer.
    ///
    /// Returns `(payload, bytes_consumed)` or `None` if more data is needed.
    pub fn try_decode_frame(buf: &[u8]) -> Option<(Vec<u8>, usize)> {
        if buf.len() < 4 {
            return None;
        }
        let len = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
        if buf.len() < 4 + len {
            return None;
        }
        let payload = buf[4..4 + len].to_vec();
        Some((payload, 4 + len))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::DeviceIdentity;
    use crate::protocol::Medium;

    fn mediums() -> Vec<Medium> {
        vec![Medium::WifiLan, Medium::Ble]
    }

    #[test]
    fn handshake_and_encrypted_roundtrip() {
        let alice_id = DeviceIdentity::generate_ephemeral("Alice");
        let bob_id = DeviceIdentity::generate_ephemeral("Bob");

        let mut alice = SecureSession::new(alice_id, Role::Initiator, mediums());
        let mut bob = SecureSession::new(bob_id, Role::Responder, mediums());

        let alice_hello = alice.begin_handshake().expect("alice hello");
        let bob_hello = bob.begin_handshake().expect("bob hello");

        alice
            .complete_handshake(&bob_hello)
            .expect("alice completes");
        bob.complete_handshake(&alice_hello)
            .expect("bob completes");

        assert!(matches!(alice.phase(), SessionPhase::Established { .. }));
        assert!(matches!(bob.phase(), SessionPhase::Established { .. }));
        assert_eq!(alice.pin(), bob.pin());
        assert_eq!(alice.peer_name(), Some("Bob"));
        assert_eq!(bob.peer_name(), Some("Alice"));

        let sealed = alice.seal(b"file chunk 1").expect("seal");
        let opened = bob.open(&sealed).expect("open");
        assert_eq!(opened, b"file chunk 1");

        let sealed2 = bob.seal(b"ack").expect("bob seal");
        let opened2 = alice.open(&sealed2).expect("alice open");
        assert_eq!(opened2, b"ack");
    }

    #[test]
    fn framing_roundtrip() {
        let payload = b"hello frame";
        let frame = framing::encode_frame(payload);
        let (decoded, consumed) = framing::try_decode_frame(&frame).expect("decode");
        assert_eq!(decoded, payload);
        assert_eq!(consumed, frame.len());
    }

    #[test]
    fn framing_needs_more_data() {
        let frame = framing::encode_frame(b"abc");
        assert!(framing::try_decode_frame(&frame[..2]).is_none());
        assert!(framing::try_decode_frame(&frame[..5]).is_none());
    }

    #[test]
    fn version_mismatch_rejected() {
        let alice_id = DeviceIdentity::generate_ephemeral("Alice");
        let mut alice = SecureSession::new(alice_id, Role::Initiator, mediums());
        let _ = alice.begin_handshake();

        // Craft a hello with wrong version
        let mut bad = HelloPayload {
            version: 99,
            device_name: "Eve".into(),
            public_key: [1u8; 32],
            supported_mediums: mediums(),
        }
        .encode();
        // public key must be valid length – already is
        let err = alice.complete_handshake(&bad).unwrap_err();
        assert!(matches!(err, SessionError::VersionMismatch { peer: 99 }));
        // silence unused warning if any
        let _ = bad.pop();
    }
}
