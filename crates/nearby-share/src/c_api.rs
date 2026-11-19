//! Stable C ABI for Android JNA (smoke + session handles).
//!
//! Session handles let the demo run a real handshake over TCP without UniFFI generate.

use std::collections::HashMap;
use std::io::Write;
use std::ptr;
use std::slice;
use std::sync::{Mutex, OnceLock};

use crate::crypto::DeviceIdentity;
use crate::protocol::Medium;
use crate::session::{framing, Role, SecureSession};

type Handle = i64;

struct SessionStore {
    next: Handle,
    map: HashMap<Handle, SecureSession>,
}

fn store() -> &'static Mutex<SessionStore> {
    static STORE: OnceLock<Mutex<SessionStore>> = OnceLock::new();
    STORE.get_or_init(|| {
        Mutex::new(SessionStore {
            next: 1,
            map: HashMap::new(),
        })
    })
}

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

fn write_bytes(buf: *mut u8, len: usize, data: &[u8]) -> i32 {
    if buf.is_null() {
        return data.len() as i32;
    }
    if len < data.len() {
        return -2;
    }
    unsafe {
        ptr::copy_nonoverlapping(data.as_ptr(), buf, data.len());
    }
    data.len() as i32
}

/// Write library version string into `buf`.
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

/// Create a session. `initiator` non-zero = initiator role.
///
/// Returns a positive handle, or negative on error.
#[no_mangle]
pub extern "C" fn nearby_c_session_create(
    name_ptr: *const u8,
    name_len: usize,
    initiator: i32,
) -> i64 {
    if name_ptr.is_null() && name_len > 0 {
        return -1;
    }
    let name = if name_len == 0 {
        "Android".to_string()
    } else {
        let bytes = unsafe { slice::from_raw_parts(name_ptr, name_len) };
        String::from_utf8_lossy(bytes).into_owned()
    };
    let role = if initiator != 0 {
        Role::Initiator
    } else {
        Role::Responder
    };
    let id = DeviceIdentity::generate_ephemeral(&name);
    let session = SecureSession::new(id, role, vec![Medium::WifiLan, Medium::Ble, Medium::SoftAp]);

    let mut guard = match store().lock() {
        Ok(g) => g,
        Err(_) => return -2,
    };
    let handle = guard.next;
    guard.next += 1;
    guard.map.insert(handle, session);
    handle
}

/// Free a session handle.
#[no_mangle]
pub extern "C" fn nearby_c_session_free(handle: i64) {
    if let Ok(mut guard) = store().lock() {
        guard.map.remove(&handle);
    }
}

/// Begin handshake; write local Hello bytes to `out`.
#[no_mangle]
pub extern "C" fn nearby_c_session_begin_handshake(
    handle: i64,
    out: *mut u8,
    out_len: usize,
) -> i32 {
    let mut guard = match store().lock() {
        Ok(g) => g,
        Err(_) => return -1,
    };
    let Some(session) = guard.map.get_mut(&handle) else {
        return -3;
    };
    match session.begin_handshake() {
        Ok(bytes) => write_bytes(out, out_len, &bytes),
        Err(_) => -4,
    }
}

/// Complete handshake with peer Hello bytes.
#[no_mangle]
pub extern "C" fn nearby_c_session_complete_handshake(
    handle: i64,
    peer_hello: *const u8,
    peer_len: usize,
) -> i32 {
    if peer_hello.is_null() || peer_len == 0 {
        return -1;
    }
    let peer = unsafe { slice::from_raw_parts(peer_hello, peer_len) };
    let mut guard = match store().lock() {
        Ok(g) => g,
        Err(_) => return -2,
    };
    let Some(session) = guard.map.get_mut(&handle) else {
        return -3;
    };
    match session.complete_handshake(peer) {
        Ok(()) => 0,
        Err(_) => -4,
    }
}

/// Write 4-digit PIN (or empty) into `out`.
#[no_mangle]
pub extern "C" fn nearby_c_session_pin(handle: i64, out: *mut u8, out_len: usize) -> i32 {
    let guard = match store().lock() {
        Ok(g) => g,
        Err(_) => return -1,
    };
    let Some(session) = guard.map.get(&handle) else {
        return -3;
    };
    write_out(out, out_len, session.pin().unwrap_or(""))
}

/// Write peer name into `out`.
#[no_mangle]
pub extern "C" fn nearby_c_session_peer_name(handle: i64, out: *mut u8, out_len: usize) -> i32 {
    let guard = match store().lock() {
        Ok(g) => g,
        Err(_) => return -1,
    };
    let Some(session) = guard.map.get(&handle) else {
        return -3;
    };
    write_out(out, out_len, session.peer_name().unwrap_or(""))
}

/// Encrypt plaintext → ciphertext bytes in `out`.
#[no_mangle]
pub extern "C" fn nearby_c_session_seal(
    handle: i64,
    plain: *const u8,
    plain_len: usize,
    out: *mut u8,
    out_len: usize,
) -> i32 {
    if plain.is_null() && plain_len > 0 {
        return -1;
    }
    let data = if plain_len == 0 {
        &[][..]
    } else {
        unsafe { slice::from_raw_parts(plain, plain_len) }
    };
    let guard = match store().lock() {
        Ok(g) => g,
        Err(_) => return -2,
    };
    let Some(session) = guard.map.get(&handle) else {
        return -3;
    };
    match session.seal(data) {
        Ok(cipher) => write_bytes(out, out_len, &cipher),
        Err(_) => -4,
    }
}

/// Decrypt ciphertext → plaintext bytes in `out`.
#[no_mangle]
pub extern "C" fn nearby_c_session_open(
    handle: i64,
    cipher: *const u8,
    cipher_len: usize,
    out: *mut u8,
    out_len: usize,
) -> i32 {
    if cipher.is_null() || cipher_len == 0 {
        return -1;
    }
    let data = unsafe { slice::from_raw_parts(cipher, cipher_len) };
    let guard = match store().lock() {
        Ok(g) => g,
        Err(_) => return -2,
    };
    let Some(session) = guard.map.get(&handle) else {
        return -3;
    };
    match session.open(data) {
        Ok(plain) => write_bytes(out, out_len, &plain),
        Err(_) => -4,
    }
}

/// Length-prefix encode: writes frame to `out`, returns frame length.
#[no_mangle]
pub extern "C" fn nearby_c_frame_encode(
    payload: *const u8,
    payload_len: usize,
    out: *mut u8,
    out_len: usize,
) -> i32 {
    if payload.is_null() && payload_len > 0 {
        return -1;
    }
    let data = if payload_len == 0 {
        &[][..]
    } else {
        unsafe { slice::from_raw_parts(payload, payload_len) }
    };
    let frame = framing::encode_frame(data);
    write_bytes(out, out_len, &frame)
}

/// Try decode one frame from `buf`. Writes payload to `out`.
/// Returns payload length, or 0 if incomplete, or negative on error.
/// Writes bytes consumed into `consumed_out` (single i32) when non-null.
#[no_mangle]
pub extern "C" fn nearby_c_frame_try_decode(
    buf: *const u8,
    buf_len: usize,
    out: *mut u8,
    out_len: usize,
    consumed_out: *mut i32,
) -> i32 {
    if buf.is_null() {
        return -1;
    }
    let data = unsafe { slice::from_raw_parts(buf, buf_len) };
    match framing::try_decode_frame(data) {
        None => {
            if !consumed_out.is_null() {
                unsafe {
                    *consumed_out = 0;
                }
            }
            0
        }
        Some((payload, consumed)) => {
            if !consumed_out.is_null() {
                unsafe {
                    *consumed_out = consumed as i32;
                }
            }
            write_bytes(out, out_len, &payload)
        }
    }
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
    }

    #[test]
    fn session_handles_handshake_over_api() {
        let a = nearby_c_session_create(b"A".as_ptr(), 1, 1);
        let b = nearby_c_session_create(b"B".as_ptr(), 1, 0);
        assert!(a > 0 && b > 0);

        let mut ha = vec![0u8; 512];
        let mut hb = vec![0u8; 512];
        let na = nearby_c_session_begin_handshake(a, ha.as_mut_ptr(), ha.len());
        let nb = nearby_c_session_begin_handshake(b, hb.as_mut_ptr(), hb.len());
        assert!(na > 0 && nb > 0);

        assert_eq!(
            nearby_c_session_complete_handshake(a, hb.as_ptr(), nb as usize),
            0
        );
        assert_eq!(
            nearby_c_session_complete_handshake(b, ha.as_ptr(), na as usize),
            0
        );

        let mut pin = vec![0u8; 16];
        let pn = nearby_c_session_pin(a, pin.as_mut_ptr(), pin.len());
        assert_eq!(pn, 4);

        let msg = b"hello-lan";
        let mut sealed = vec![0u8; 256];
        let sn = nearby_c_session_seal(a, msg.as_ptr(), msg.len(), sealed.as_mut_ptr(), sealed.len());
        assert!(sn > 0);
        let mut opened = vec![0u8; 256];
        let on = nearby_c_session_open(b, sealed.as_ptr(), sn as usize, opened.as_mut_ptr(), opened.len());
        assert_eq!(on, msg.len() as i32);
        assert_eq!(&opened[..on as usize], msg);

        nearby_c_session_free(a);
        nearby_c_session_free(b);
    }
}
