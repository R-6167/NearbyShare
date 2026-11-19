//! Minimal C ABI for Android JNA smoke tests.
//!
//! These exports always exist in `libnearby_share.so` and do not require
//! `uniffi-bindgen` output. The demo app calls them via JNA.

use std::io::Write;
use std::ptr;
use std::slice;

use crate::crypto::DeviceIdentity;
use crate::protocol::Medium;
use crate::session::{Role, SecureSession};

/// Copy `text` into `buf` (UTF-8). Returns bytes written, or `-1` on error.
///
//! If `buf` is null or too small, returns the required size as a negative
/// sentinel only when null; if too small returns -2.
fn write_out(buf: *mut u8, len: usize, text: &str) -> i32 {
    let bytes = text.as_bytes();
    if buf.is_null() {
        return bytes.len() as i32;
    }
    if len < bytes.len() {
        return -2;
    }
    unsafe {
        ptr::copy_nonoverlapping(bytes.as_ptr(), buf, bytes.len());
    }
    bytes.len() as i32
}

/// Write library version string into `buf`.
///
/// Returns number of bytes written, or negative on error.
#[no_mangle]
pub extern "C" fn nearby_c_version(buf: *mut u8, len: usize) -> i32 {
    write_out(buf, len, crate::VERSION)
}

/// Write hello string into `buf`.
#[no_mangle]
pub extern "C" fn nearby_c_hello(buf: *mut u8, len: usize) -> i32 {
    write_out(buf, len, &crate::hello())
}

/// Run an in-process crypto/session smoke test and write a multi-line report.
///
/// Covers: identity generation, handshake, PIN agreement, seal/open.
#[no_mangle]
pub extern "C" fn nearby_c_smoke_test(buf: *mut u8, len: usize) -> i32 {
    let report = match run_smoke() {
        Ok(s) => s,
        Err(e) => format!("SMOKE FAILED: {e}"),
    };
    write_out(buf, len, &report)
}

fn run_smoke() -> Result<String, String> {
    let alice_id = DeviceIdentity::generate_ephemeral("Android-Alice");
    let bob_id = DeviceIdentity::generate_ephemeral("Android-Bob");

    let mut alice = SecureSession::new(
        alice_id.clone(),
        Role::Initiator,
        vec![Medium::WifiLan, Medium::Ble],
    );
    let mut bob = SecureSession::new(
        bob_id,
        Role::Responder,
        vec![Medium::WifiLan, Medium::SoftAp],
    );

    let a_hello = alice
        .begin_handshake()
        .map_err(|e| format!("alice hello: {e}"))?;
    let b_hello = bob
        .begin_handshake()
        .map_err(|e| format!("bob hello: {e}"))?;
    alice
        .complete_handshake(&b_hello)
        .map_err(|e| format!("alice complete: {e}"))?;
    bob.complete_handshake(&a_hello)
        .map_err(|e| format!("bob complete: {e}"))?;

    let pin_a = alice.pin().unwrap_or("").to_string();
    let pin_b = bob.pin().unwrap_or("").to_string();
    if pin_a.is_empty() || pin_a != pin_b {
        return Err(format!("PIN mismatch: {pin_a:?} vs {pin_b:?}"));
    }

    let sealed = alice
        .seal(b"nearby-share-ping")
        .map_err(|e| format!("seal: {e}"))?;
    let opened = bob
        .open(&sealed)
        .map_err(|e| format!("open: {e}"))?;
    if opened != b"nearby-share-ping" {
        return Err("decrypt mismatch".into());
    }

    let mut out = Vec::new();
    let _ = writeln!(out, "OK");
    let _ = writeln!(out, "version={}", crate::VERSION);
    let _ = writeln!(out, "hello={}", crate::hello());
    let _ = writeln!(out, "alice_fp={}", alice_id.fingerprint());
    let _ = writeln!(out, "peer={}", bob.peer_name().unwrap_or("?"));
    let _ = writeln!(out, "pin={pin_a}");
    let _ = writeln!(out, "seal_open=ok");
    let _ = writeln!(out, "negotiated={:?}", alice.negotiated_mediums());
    String::from_utf8(out).map_err(|e| e.to_string())
}

/// Optional helper: copy up to `len` bytes from a C string-ish test (unused).
#[no_mangle]
pub extern "C" fn nearby_c_buffer_len_check(buf: *const u8, len: usize) -> i32 {
    if buf.is_null() {
        return -1;
    }
    let _ = unsafe { slice::from_raw_parts(buf, len) };
    len as i32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn smoke_writes_ok() {
        let mut buf = vec![0u8; 2048];
        let n = nearby_c_smoke_test(buf.as_mut_ptr(), buf.len());
        assert!(n > 0);
        let s = std::str::from_utf8(&buf[..n as usize]).unwrap();
        assert!(s.starts_with("OK"), "{s}");
        assert!(s.contains("pin="));
        assert!(s.contains("seal_open=ok"));
    }
}
