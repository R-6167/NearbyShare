//! File transfer session management.
//!
//! Handles the lifecycle of sending or receiving files once a peer is connected.

use crate::protocol::{FileInfo, Medium};

/// Direction of a transfer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransferDirection {
    /// This device is sending.
    Outgoing,
    /// This device is receiving.
    Incoming,
}

/// Current state of a transfer session.
#[derive(Debug, Clone)]
pub enum TransferState {
    /// Waiting for the other side to accept.
    Pending,
    /// Handshake / medium negotiation in progress.
    Negotiating,
    /// Transfer in progress.
    Active {
        /// Bytes transferred so far (across all files).
        bytes_transferred: u64,
        /// Total bytes expected.
        total_bytes: u64,
        /// Current file being transferred (index into `files`).
        current_file_index: usize,
    },
    /// Successfully completed.
    Completed,
    /// Cancelled by either side.
    Cancelled {
        reason: Option<String>,
    },
    /// Failed.
    Failed {
        /// Human-readable reason.
        reason: String,
    },
}

/// A single transfer session.
#[derive(Debug)]
pub struct TransferSession {
    /// Unique session id.
    pub id: String,
    /// Direction.
    pub direction: TransferDirection,
    /// Peer fingerprint or id this session is with.
    pub peer_id: String,
    /// Files involved.
    pub files: Vec<FileInfo>,
    /// Total bytes across all files.
    pub total_bytes: u64,
    /// Current state.
    pub state: TransferState,
    /// Medium currently in use (if known).
    pub medium: Option<Medium>,
    /// Last acknowledged sequence number (for resume).
    pub last_acked_seq: u64,
}

impl TransferSession {
    /// Create a new outgoing session.
    pub fn new_outgoing(id: String, peer_id: String, files: Vec<FileInfo>) -> Self {
        let total_bytes = files.iter().map(|f| f.size).sum();
        Self {
            id,
            direction: TransferDirection::Outgoing,
            peer_id,
            files,
            total_bytes,
            state: TransferState::Pending,
            medium: None,
            last_acked_seq: 0,
        }
    }

    /// Create a new incoming session from an Offer.
    pub fn new_incoming(id: String, peer_id: String, files: Vec<FileInfo>) -> Self {
        let total_bytes = files.iter().map(|f| f.size).sum();
        Self {
            id,
            direction: TransferDirection::Incoming,
            peer_id,
            files,
            total_bytes,
            state: TransferState::Pending,
            medium: None,
            last_acked_seq: 0,
        }
    }

    /// Mark the session as accepted and move to negotiating.
    pub fn accept(&mut self) {
        if matches!(self.state, TransferState::Pending) {
            self.state = TransferState::Negotiating;
        }
    }

    /// Mark the session as active.
    pub fn start(&mut self, medium: Option<Medium>) {
        self.medium = medium;
        self.state = TransferState::Active {
            bytes_transferred: 0,
            total_bytes: self.total_bytes,
            current_file_index: 0,
        };
    }

    /// Update progress.
    pub fn update_progress(&mut self, bytes_transferred: u64, current_file_index: usize) {
        if let TransferState::Active {
            total_bytes,
            ..
        } = self.state
        {
            self.state = TransferState::Active {
                bytes_transferred,
                total_bytes,
                current_file_index,
            };
        }
    }

    /// Mark completed.
    pub fn complete(&mut self) {
        self.state = TransferState::Completed;
    }

    /// Mark cancelled.
    pub fn cancel(&mut self, reason: Option<String>) {
        self.state = TransferState::Cancelled { reason };
    }

    /// Mark failed.
    pub fn fail(&mut self, reason: impl Into<String>) {
        self.state = TransferState::Failed {
            reason: reason.into(),
        };
    }

    /// Returns true if the session is still in a non-terminal state.
    pub fn is_active(&self) -> bool {
        matches!(
            self.state,
            TransferState::Pending | TransferState::Negotiating | TransferState::Active { .. }
        )
    }
}
