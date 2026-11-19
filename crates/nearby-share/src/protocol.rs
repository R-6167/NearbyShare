//! Protocol definitions and message framing.
//!
//! High-level messages exchanged between peers during a transfer session.

/// Protocol version
pub const PROTOCOL_VERSION: u16 = 1;

/// High-level message types (to be expanded)
#[derive(Debug, Clone)]
pub enum Message {
    /// Initial handshake / capability exchange
    Hello {
        /// Protocol version the sender supports
        version: u16,
        /// Device name
        device_name: String,
    },

    /// Offer to send one or more files
    Offer {
        /// List of files being offered
        files: Vec<FileInfo>,
    },

    /// Accept or reject an offer
    Response {
        /// Whether the receiver accepts
        accepted: bool,
    },

    // More message types will be added (chunk, progress, complete, cancel, etc.)
}

/// Metadata about a file being shared
#[derive(Debug, Clone)]
pub struct FileInfo {
    /// File name (including extension)
    pub name: String,
    /// Size in bytes
    pub size: u64,
    /// Optional MIME type
    pub mime_type: Option<String>,
}
