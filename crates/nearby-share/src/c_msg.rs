//! C ABI helpers for protocol `Message` encode/decode (post-handshake payloads).

use std::ptr;
use std::slice;

use crate::crypto::hash_bytes;
use crate::protocol::{FileInfo, Message};

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

fn read_str(ptr: *const u8, len: usize) -> Option<String> {
    if len == 0 {
        return Some(String::new());
    }
    if ptr.is_null() {
        return None;
    }
    let bytes = unsafe { slice::from_raw_parts(ptr, len) };
    Some(String::from_utf8_lossy(bytes).into_owned())
}

#[no_mangle]
pub extern "C" fn nearby_c_msg_encode_offer(
    session_id_ptr: *const u8,
    session_id_len: usize,
    name_ptr: *const u8,
    name_len: usize,
    size: u64,
    out: *mut u8,
    out_len: usize,
) -> i32 {
    let Some(session_id) = read_str(session_id_ptr, session_id_len) else {
        return -1;
    };
    let Some(name) = read_str(name_ptr, name_len) else {
        return -1;
    };
    let msg = Message::Offer {
        session_id,
        files: vec![FileInfo {
            file_id: 1,
            name,
            size,
            mime_type: None,
            relative_path: None,
        }],
        total_bytes: size,
    };
    write_bytes(out, out_len, &msg.encode())
}

#[no_mangle]
pub extern "C" fn nearby_c_msg_encode_response(
    session_id_ptr: *const u8,
    session_id_len: usize,
    accepted: i32,
    out: *mut u8,
    out_len: usize,
) -> i32 {
    let Some(session_id) = read_str(session_id_ptr, session_id_len) else {
        return -1;
    };
    let msg = Message::Response {
        session_id,
        accepted: accepted != 0,
        reason: None,
    };
    write_bytes(out, out_len, &msg.encode())
}

#[no_mangle]
pub extern "C" fn nearby_c_msg_encode_chunk(
    session_id_ptr: *const u8,
    session_id_len: usize,
    file_id: u32,
    seq: u64,
    data_ptr: *const u8,
    data_len: usize,
    is_last: i32,
    out: *mut u8,
    out_len: usize,
) -> i32 {
    let Some(session_id) = read_str(session_id_ptr, session_id_len) else {
        return -1;
    };
    let data = if data_len == 0 {
        Vec::new()
    } else if data_ptr.is_null() {
        return -1;
    } else {
        unsafe { slice::from_raw_parts(data_ptr, data_len).to_vec() }
    };
    let msg = Message::Chunk {
        session_id,
        file_id,
        seq,
        data,
        is_last: is_last != 0,
    };
    write_bytes(out, out_len, &msg.encode())
}

#[no_mangle]
pub extern "C" fn nearby_c_msg_encode_complete(
    session_id_ptr: *const u8,
    session_id_len: usize,
    file_hash: *const u8,
    out: *mut u8,
    out_len: usize,
) -> i32 {
    let Some(session_id) = read_str(session_id_ptr, session_id_len) else {
        return -1;
    };
    if file_hash.is_null() {
        return -1;
    }
    let mut hash = [0u8; 32];
    unsafe {
        ptr::copy_nonoverlapping(file_hash, hash.as_mut_ptr(), 32);
    }
    let msg = Message::Complete {
        session_id,
        checksums: vec![(1, hash)],
    };
    write_bytes(out, out_len, &msg.encode())
}

#[no_mangle]
pub extern "C" fn nearby_c_msg_tag(data_ptr: *const u8, data_len: usize) -> i32 {
    if data_ptr.is_null() || data_len == 0 {
        return -1;
    }
    let data = unsafe { slice::from_raw_parts(data_ptr, data_len) };
    match Message::decode(data) {
        Ok(Message::Hello { .. }) => 1,
        Ok(Message::Offer { .. }) => 2,
        Ok(Message::Response { .. }) => 3,
        Ok(Message::SessionReady { .. }) => 4,
        Ok(Message::Chunk { .. }) => 5,
        Ok(Message::Progress { .. }) => 6,
        Ok(Message::Complete { .. }) => 7,
        Ok(Message::Cancel { .. }) => 8,
        Err(_) => -4,
    }
}

/// Parse Offer. Writes session id / name bytes and their lengths.
#[no_mangle]
pub extern "C" fn nearby_c_msg_parse_offer(
    data_ptr: *const u8,
    data_len: usize,
    sid_out: *mut u8,
    sid_cap: usize,
    sid_len_out: *mut i32,
    name_out: *mut u8,
    name_cap: usize,
    name_len_out: *mut i32,
    size_out: *mut u64,
) -> i32 {
    if data_ptr.is_null() || size_out.is_null() || sid_len_out.is_null() || name_len_out.is_null() {
        return -1;
    }
    let data = unsafe { slice::from_raw_parts(data_ptr, data_len) };
    let msg = match Message::decode(data) {
        Ok(m) => m,
        Err(_) => return -4,
    };
    let Message::Offer {
        session_id,
        files,
        total_bytes,
    } = msg
    else {
        return -5;
    };
    let file = match files.first() {
        Some(f) => f,
        None => return -6,
    };
    let sn = write_bytes(sid_out, sid_cap, session_id.as_bytes());
    if sn < 0 {
        return sn;
    }
    let nn = write_bytes(name_out, name_cap, file.name.as_bytes());
    if nn < 0 {
        return nn;
    }
    unsafe {
        *sid_len_out = sn;
        *name_len_out = nn;
        *size_out = total_bytes;
    }
    0
}

#[no_mangle]
pub extern "C" fn nearby_c_msg_parse_response(
    data_ptr: *const u8,
    data_len: usize,
    accepted_out: *mut i32,
) -> i32 {
    if data_ptr.is_null() || accepted_out.is_null() {
        return -1;
    }
    let data = unsafe { slice::from_raw_parts(data_ptr, data_len) };
    match Message::decode(data) {
        Ok(Message::Response { accepted, .. }) => {
            unsafe {
                *accepted_out = if accepted { 1 } else { 0 };
            }
            0
        }
        Ok(_) => -5,
        Err(_) => -4,
    }
}

#[no_mangle]
pub extern "C" fn nearby_c_msg_parse_chunk(
    data_ptr: *const u8,
    data_len: usize,
    seq_out: *mut u64,
    is_last_out: *mut i32,
    data_out: *mut u8,
    data_out_len: usize,
) -> i32 {
    if data_ptr.is_null() || seq_out.is_null() || is_last_out.is_null() {
        return -1;
    }
    let data = unsafe { slice::from_raw_parts(data_ptr, data_len) };
    match Message::decode(data) {
        Ok(Message::Chunk {
            seq,
            data: chunk,
            is_last,
            ..
        }) => {
            unsafe {
                *seq_out = seq;
                *is_last_out = if is_last { 1 } else { 0 };
            }
            write_bytes(data_out, data_out_len, &chunk)
        }
        Ok(_) => -5,
        Err(_) => -4,
    }
}

#[no_mangle]
pub extern "C" fn nearby_c_hash(data_ptr: *const u8, data_len: usize, out: *mut u8) -> i32 {
    if out.is_null() {
        return -1;
    }
    let data = if data_len == 0 {
        &[][..]
    } else if data_ptr.is_null() {
        return -1;
    } else {
        unsafe { slice::from_raw_parts(data_ptr, data_len) }
    };
    let h = hash_bytes(data);
    unsafe {
        ptr::copy_nonoverlapping(h.as_ptr(), out, 32);
    }
    32
}
