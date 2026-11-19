//! Protocol definitions and binary message codec.
//!
//! High-level messages exchanged between peers during a transfer session.
//! Post-handshake messages are typically encrypted via [`crate::session::SecureSession`].
//!
//! # Wire format
//!
//! Each message is:
//! ```text
//! u8  tag
//! …   tag-specific fields (little-endian integers, length-prefixed strings/bytes)
//! ```
//!
//! Strings and byte arrays use a `u32` LE length prefix.
//! Optional values use a `u8` flag (0 = None, 1 = Some) then the value.

use crate::crypto::PublicKey;

/// Protocol version.
pub const PROTOCOL_VERSION: u16 = 1;

/// Supported transport mediums (platform may support a subset).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Medium {
    /// Classic Bluetooth
    Bluetooth,
    /// Bluetooth Low Energy (discovery / low bandwidth)
    Ble,
    /// Same Wi-Fi / LAN (mDNS + TCP)
    WifiLan,
    /// Wi-Fi Direct / P2P
    WifiDirect,
    /// One device acts as SoftAP
    SoftAp,
    /// Out-of-band (QR / NFC) only – no radio discovery
    OutOfBand,
}

impl Medium {
    fn to_tag(self) -> u8 {
        match self {
            Medium::Bluetooth => 1,
            Medium::Ble => 2,
            Medium::WifiLan => 3,
            Medium::WifiDirect => 4,
            Medium::SoftAp => 5,
            Medium::OutOfBand => 6,
        }
    }

    fn from_tag(tag: u8) -> Option<Self> {
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
}

/// Metadata about a file being shared.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileInfo {
    /// Unique id within the session (assigned by sender).
    pub file_id: u32,
    /// File name (including extension).
    pub name: String,
    /// Size in bytes.
    pub size: u64,
    /// Optional MIME type.
    pub mime_type: Option<String>,
    /// Relative path when sending a folder (preserves structure).
    pub relative_path: Option<String>,
}

/// High-level message types.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Message {
    /// Initial handshake / capability exchange.
    Hello {
        /// Protocol version the sender supports.
        version: u16,
        /// Device name.
        device_name: String,
        /// Identity public key.
        public_key: PublicKey,
        /// Fingerprint for quick visual check.
        fingerprint: String,
        /// Mediums this device can use.
        supported_mediums: Vec<Medium>,
    },

    /// Offer to send one or more files.
    Offer {
        /// Session identifier chosen by the sender.
        session_id: String,
        /// List of files being offered.
        files: Vec<FileInfo>,
        /// Total bytes across all files.
        total_bytes: u64,
    },

    /// Accept or reject an offer.
    Response {
        session_id: String,
        /// Whether the receiver accepts.
        accepted: bool,
        /// Optional human reason when rejecting.
        reason: Option<String>,
    },

    /// Receiver confirms it is ready and optionally selects a preferred medium.
    SessionReady {
        session_id: String,
        preferred_medium: Option<Medium>,
    },

    /// A chunk of file data.
    Chunk {
        session_id: String,
        file_id: u32,
        /// Sequence number (0-based) for resume support.
        seq: u64,
        /// Raw chunk bytes.
        data: Vec<u8>,
        /// True if this is the final chunk of the file.
        is_last: bool,
    },

    /// Progress update (can be sent by either side).
    Progress {
        session_id: String,
        bytes_transferred: u64,
        total_bytes: u64,
    },

    /// Transfer completed successfully.
    Complete {
        session_id: String,
        /// Final integrity hashes per file (file_id → hash).
        checksums: Vec<(u32, [u8; 32])>,
    },

    /// Transfer cancelled by either side.
    Cancel {
        session_id: String,
        reason: Option<String>,
    },
}

/// Errors from the binary codec.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CodecError {
    /// Buffer ended before a full field could be read.
    Truncated,
    /// Unknown or reserved message tag.
    UnknownTag(u8),
    /// Invalid optional flag or enum tag.
    InvalidValue,
    /// String was not valid UTF-8.
    InvalidUtf8,
}

impl std::fmt::Display for CodecError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CodecError::Truncated => write!(f, "truncated message"),
            CodecError::UnknownTag(t) => write!(f, "unknown message tag {t}"),
            CodecError::InvalidValue => write!(f, "invalid field value"),
            CodecError::InvalidUtf8 => write!(f, "invalid utf-8 string"),
        }
    }
}

impl std::error::Error for CodecError {}

// --- low-level writers / readers ---

struct Writer {
    buf: Vec<u8>,
}

impl Writer {
    fn new() -> Self {
        Self { buf: Vec::new() }
    }

    fn u8(&mut self, v: u8) {
        self.buf.push(v);
    }

    fn u16(&mut self, v: u16) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }

    fn u32(&mut self, v: u32) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }

    fn u64(&mut self, v: u64) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }

    fn bool(&mut self, v: bool) {
        self.u8(if v { 1 } else { 0 });
    }

    fn bytes(&mut self, data: &[u8]) {
        self.u32(data.len() as u32);
        self.buf.extend_from_slice(data);
    }

    fn str(&mut self, s: &str) {
        self.bytes(s.as_bytes());
    }

    fn opt_str(&mut self, s: &Option<String>) {
        match s {
            None => self.u8(0),
            Some(v) => {
                self.u8(1);
                self.str(v);
            }
        }
    }

    fn fixed32(&mut self, data: &[u8; 32]) {
        self.buf.extend_from_slice(data);
    }

    fn medium(&mut self, m: Medium) {
        self.u8(m.to_tag());
    }

    fn opt_medium(&mut self, m: &Option<Medium>) {
        match m {
            None => self.u8(0),
            Some(v) => {
                self.u8(1);
                self.medium(*v);
            }
        }
    }

    fn file_info(&mut self, f: &FileInfo) {
        self.u32(f.file_id);
        self.str(&f.name);
        self.u64(f.size);
        self.opt_str(&f.mime_type);
        self.opt_str(&f.relative_path);
    }

    fn into_vec(self) -> Vec<u8> {
        self.buf
    }
}

struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    fn remaining(&self) -> usize {
        self.data.len().saturating_sub(self.pos)
    }

    fn u8(&mut self) -> Result<u8, CodecError> {
        if self.remaining() < 1 {
            return Err(CodecError::Truncated);
        }
        let v = self.data[self.pos];
        self.pos += 1;
        Ok(v)
    }

    fn u16(&mut self) -> Result<u16, CodecError> {
        if self.remaining() < 2 {
            return Err(CodecError::Truncated);
        }
        let v = u16::from_le_bytes([self.data[self.pos], self.data[self.pos + 1]]);
        self.pos += 2;
        Ok(v)
    }

    fn u32(&mut self) -> Result<u32, CodecError> {
        if self.remaining() < 4 {
            return Err(CodecError::Truncated);
        }
        let v = u32::from_le_bytes([
            self.data[self.pos],
            self.data[self.pos + 1],
            self.data[self.pos + 2],
            self.data[self.pos + 3],
        ]);
        self.pos += 4;
        Ok(v)
    }

    fn u64(&mut self) -> Result<u64, CodecError> {
        if self.remaining() < 8 {
            return Err(CodecError::Truncated);
        }
        let mut b = [0u8; 8];
        b.copy_from_slice(&self.data[self.pos..self.pos + 8]);
        self.pos += 8;
        Ok(u64::from_le_bytes(b))
    }

    fn bool(&mut self) -> Result<bool, CodecError> {
        match self.u8()? {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err(CodecError::InvalidValue),
        }
    }

    fn bytes(&mut self) -> Result<Vec<u8>, CodecError> {
        let len = self.u32()? as usize;
        if self.remaining() < len {
            return Err(CodecError::Truncated);
        }
        let out = self.data[self.pos..self.pos + len].to_vec();
        self.pos += len;
        Ok(out)
    }

    fn str(&mut self) -> Result<String, CodecError> {
        let raw = self.bytes()?;
        String::from_utf8(raw).map_err(|_| CodecError::InvalidUtf8)
    }

    fn opt_str(&mut self) -> Result<Option<String>, CodecError> {
        match self.u8()? {
            0 => Ok(None),
            1 => Ok(Some(self.str()?)),
            _ => Err(CodecError::InvalidValue),
        }
    }

    fn fixed32(&mut self) -> Result<[u8; 32], CodecError> {
        if self.remaining() < 32 {
            return Err(CodecError::Truncated);
        }
        let mut out = [0u8; 32];
        out.copy_from_slice(&self.data[self.pos..self.pos + 32]);
        self.pos += 32;
        Ok(out)
    }

    fn medium(&mut self) -> Result<Medium, CodecError> {
        Medium::from_tag(self.u8()?).ok_or(CodecError::InvalidValue)
    }

    fn opt_medium(&mut self) -> Result<Option<Medium>, CodecError> {
        match self.u8()? {
            0 => Ok(None),
            1 => Ok(Some(self.medium()?)),
            _ => Err(CodecError::InvalidValue),
        }
    }

    fn file_info(&mut self) -> Result<FileInfo, CodecError> {
        Ok(FileInfo {
            file_id: self.u32()?,
            name: self.str()?,
            size: self.u64()?,
            mime_type: self.opt_str()?,
            relative_path: self.opt_str()?,
        })
    }
}

// Message tags
const TAG_HELLO: u8 = 1;
const TAG_OFFER: u8 = 2;
const TAG_RESPONSE: u8 = 3;
const TAG_SESSION_READY: u8 = 4;
const TAG_CHUNK: u8 = 5;
const TAG_PROGRESS: u8 = 6;
const TAG_COMPLETE: u8 = 7;
const TAG_CANCEL: u8 = 8;

impl Message {
    /// Encode this message to the binary wire format.
    pub fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        match self {
            Message::Hello {
                version,
                device_name,
                public_key,
                fingerprint,
                supported_mediums,
            } => {
                w.u8(TAG_HELLO);
                w.u16(*version);
                w.str(device_name);
                w.fixed32(public_key);
                w.str(fingerprint);
                w.u32(supported_mediums.len() as u32);
                for m in supported_mediums {
                    w.medium(*m);
                }
            }
            Message::Offer {
                session_id,
                files,
                total_bytes,
            } => {
                w.u8(TAG_OFFER);
                w.str(session_id);
                w.u32(files.len() as u32);
                for f in files {
                    w.file_info(f);
                }
                w.u64(*total_bytes);
            }
            Message::Response {
                session_id,
                accepted,
                reason,
            } => {
                w.u8(TAG_RESPONSE);
                w.str(session_id);
                w.bool(*accepted);
                w.opt_str(reason);
            }
            Message::SessionReady {
                session_id,
                preferred_medium,
            } => {
                w.u8(TAG_SESSION_READY);
                w.str(session_id);
                w.opt_medium(preferred_medium);
            }
            Message::Chunk {
                session_id,
                file_id,
                seq,
                data,
                is_last,
            } => {
                w.u8(TAG_CHUNK);
                w.str(session_id);
                w.u32(*file_id);
                w.u64(*seq);
                w.bytes(data);
                w.bool(*is_last);
            }
            Message::Progress {
                session_id,
                bytes_transferred,
                total_bytes,
            } => {
                w.u8(TAG_PROGRESS);
                w.str(session_id);
                w.u64(*bytes_transferred);
                w.u64(*total_bytes);
            }
            Message::Complete {
                session_id,
                checksums,
            } => {
                w.u8(TAG_COMPLETE);
                w.str(session_id);
                w.u32(checksums.len() as u32);
                for (fid, hash) in checksums {
                    w.u32(*fid);
                    w.fixed32(hash);
                }
            }
            Message::Cancel { session_id, reason } => {
                w.u8(TAG_CANCEL);
                w.str(session_id);
                w.opt_str(reason);
            }
        }
        w.into_vec()
    }

    /// Decode a message from the binary wire format.
    pub fn decode(data: &[u8]) -> Result<Self, CodecError> {
        let mut r = Reader::new(data);
        let tag = r.u8()?;
        match tag {
            TAG_HELLO => {
                let version = r.u16()?;
                let device_name = r.str()?;
                let public_key = r.fixed32()?;
                let fingerprint = r.str()?;
                let n = r.u32()? as usize;
                let mut supported_mediums = Vec::with_capacity(n);
                for _ in 0..n {
                    supported_mediums.push(r.medium()?);
                }
                Ok(Message::Hello {
                    version,
                    device_name,
                    public_key,
                    fingerprint,
                    supported_mediums,
                })
            }
            TAG_OFFER => {
                let session_id = r.str()?;
                let n = r.u32()? as usize;
                let mut files = Vec::with_capacity(n);
                for _ in 0..n {
                    files.push(r.file_info()?);
                }
                let total_bytes = r.u64()?;
                Ok(Message::Offer {
                    session_id,
                    files,
                    total_bytes,
                })
            }
            TAG_RESPONSE => Ok(Message::Response {
                session_id: r.str()?,
                accepted: r.bool()?,
                reason: r.opt_str()?,
            }),
            TAG_SESSION_READY => Ok(Message::SessionReady {
                session_id: r.str()?,
                preferred_medium: r.opt_medium()?,
            }),
            TAG_CHUNK => Ok(Message::Chunk {
                session_id: r.str()?,
                file_id: r.u32()?,
                seq: r.u64()?,
                data: r.bytes()?,
                is_last: r.bool()?,
            }),
            TAG_PROGRESS => Ok(Message::Progress {
                session_id: r.str()?,
                bytes_transferred: r.u64()?,
                total_bytes: r.u64()?,
            }),
            TAG_COMPLETE => {
                let session_id = r.str()?;
                let n = r.u32()? as usize;
                let mut checksums = Vec::with_capacity(n);
                for _ in 0..n {
                    let fid = r.u32()?;
                    let hash = r.fixed32()?;
                    checksums.push((fid, hash));
                }
                Ok(Message::Complete {
                    session_id,
                    checksums,
                })
            }
            TAG_CANCEL => Ok(Message::Cancel {
                session_id: r.str()?,
                reason: r.opt_str()?,
            }),
            other => Err(CodecError::UnknownTag(other)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roundtrip(msg: Message) {
        let encoded = msg.encode();
        let decoded = Message::decode(&encoded).expect("decode");
        assert_eq!(msg, decoded);
    }

    #[test]
    fn codec_hello() {
        roundtrip(Message::Hello {
            version: PROTOCOL_VERSION,
            device_name: "Pixel 9".into(),
            public_key: [0xAB; 32],
            fingerprint: "deadbeefcafebabe".into(),
            supported_mediums: vec![Medium::WifiLan, Medium::Ble],
        });
    }

    #[test]
    fn codec_offer_and_chunk() {
        let files = vec![FileInfo {
            file_id: 1,
            name: "pic.jpg".into(),
            size: 999,
            mime_type: Some("image/jpeg".into()),
            relative_path: None,
        }];
        roundtrip(Message::Offer {
            session_id: "s1".into(),
            files: files.clone(),
            total_bytes: 999,
        });
        roundtrip(Message::Chunk {
            session_id: "s1".into(),
            file_id: 1,
            seq: 0,
            data: vec![1, 2, 3, 4, 5],
            is_last: false,
        });
        roundtrip(Message::Chunk {
            session_id: "s1".into(),
            file_id: 1,
            seq: 1,
            data: vec![],
            is_last: true,
        });
    }

    #[test]
    fn codec_control_messages() {
        roundtrip(Message::Response {
            session_id: "s1".into(),
            accepted: true,
            reason: None,
        });
        roundtrip(Message::Response {
            session_id: "s1".into(),
            accepted: false,
            reason: Some("busy".into()),
        });
        roundtrip(Message::SessionReady {
            session_id: "s1".into(),
            preferred_medium: Some(Medium::WifiDirect),
        });
        roundtrip(Message::Progress {
            session_id: "s1".into(),
            bytes_transferred: 100,
            total_bytes: 1000,
        });
        roundtrip(Message::Complete {
            session_id: "s1".into(),
            checksums: vec![(1, [9u8; 32])],
        });
        roundtrip(Message::Cancel {
            session_id: "s1".into(),
            reason: Some("user".into()),
        });
    }

    #[test]
    fn decode_truncated_fails() {
        assert!(matches!(
            Message::decode(&[TAG_OFFER, 0]),
            Err(CodecError::Truncated)
        ));
    }

    #[test]
    fn decode_unknown_tag_fails() {
        assert!(matches!(
            Message::decode(&[255]),
            Err(CodecError::UnknownTag(255))
        ));
    }
}
