//! Incremental BLAKE3 hasher exposed to Android for streaming I/O.

use std::collections::HashMap;
use std::ptr;
use std::slice;
use std::sync::{Mutex, OnceLock};

use blake3::Hasher;

type Handle = i64;

struct Store {
    next: Handle,
    map: HashMap<Handle, Hasher>,
}

fn store() -> &'static Mutex<Store> {
    static STORE: OnceLock<Mutex<Store>> = OnceLock::new();
    STORE.get_or_init(|| {
        Mutex::new(Store {
            next: 1,
            map: HashMap::new(),
        })
    })
}

/// Create a new hasher. Returns positive handle or negative error.
#[no_mangle]
pub extern "C" fn nearby_c_hasher_new() -> i64 {
    let mut guard = match store().lock() {
        Ok(g) => g,
        Err(_) => return -1,
    };
    let h = guard.next;
    guard.next += 1;
    guard.map.insert(h, Hasher::new());
    h
}

/// Absorb more bytes into the hasher.
#[no_mangle]
pub extern "C" fn nearby_c_hasher_update(handle: i64, data: *const u8, len: usize) -> i32 {
    if len > 0 && data.is_null() {
        return -1;
    }
    let bytes = if len == 0 {
        &[][..]
    } else {
        unsafe { slice::from_raw_parts(data, len) }
    };
    let mut guard = match store().lock() {
        Ok(g) => g,
        Err(_) => return -2,
    };
    let Some(hasher) = guard.map.get_mut(&handle) else {
        return -3;
    };
    hasher.update(bytes);
    0
}

/// Finalize into 32-byte `out` and free the handle.
#[no_mangle]
pub extern "C" fn nearby_c_hasher_finalize(handle: i64, out: *mut u8) -> i32 {
    if out.is_null() {
        return -1;
    }
    let mut guard = match store().lock() {
        Ok(g) => g,
        Err(_) => return -2,
    };
    let Some(hasher) = guard.map.remove(&handle) else {
        return -3;
    };
    let hash = hasher.finalize();
    let bytes = hash.as_bytes();
    unsafe {
        ptr::copy_nonoverlapping(bytes.as_ptr(), out, 32);
    }
    32
}

/// Drop hasher without finalizing.
#[no_mangle]
pub extern "C" fn nearby_c_hasher_free(handle: i64) {
    if let Ok(mut guard) = store().lock() {
        guard.map.remove(&handle);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::hash_bytes;

    #[test]
    fn incremental_matches_oneshot() {
        let data = b"streaming-nearby-share";
        let expected = hash_bytes(data);
        let h = nearby_c_hasher_new();
        assert!(h > 0);
        assert_eq!(nearby_c_hasher_update(h, data.as_ptr(), data.len()), 0);
        let mut out = [0u8; 32];
        assert_eq!(nearby_c_hasher_finalize(h, out.as_mut_ptr()), 32);
        assert_eq!(out, expected);
    }
}
