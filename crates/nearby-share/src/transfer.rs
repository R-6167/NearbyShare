//! File transfer session management.
//!
//! Handles the lifecycle of sending or receiving files once a peer is connected.

use crate::protocol::FileInfo;

/// Direction of a transfer
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransferDirection {
    /// This device is sending
    Outgoing,
    /// This device is receiving
    Incoming,
}

/// Current state of a transfer session
#[derive(Debug, Clone)]
pub enum TransferState {
    /// Waiting for the other side to accept
    Pending,
    /// Transfer in progress
    Active {
        /// Bytes transferred so far
        bytes_transferred: u64,
        /// Total bytes expected
        total_bytes: u64,
    },
    /// Successfully completed
    Completed,
    /// Cancelled by either side
    Cancelled,
    /// Failed
    Failed {
        /// Human-readable reason
        reason: String,
    },
}

/// A single transfer session
#[derive(Debug)]
pub struct TransferSession {
    /// Unique session id
    pub id: String,
    /// Direction
    pub direction: TransferDirection,
    /// Files involved
    pub files: Vec<FileInfo>,
    /// Current state
    pub state: TransferState,
}
