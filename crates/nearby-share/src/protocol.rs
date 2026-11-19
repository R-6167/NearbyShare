//! Protocol definitions and message framing.
//!
//! High-level messages exchanged between peers during a transfer session.
//! All post-handshake messages will be encrypted with the session keys.

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

/// Metadata about a file being shared.
#[derive(Debug, Clone)]
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
#[derive(Debug, Clone)]
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
        /// Raw chunk bytes (will be encrypted in real transport).
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
