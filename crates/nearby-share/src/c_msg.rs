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
    reason_ptr: *const u8,
    reason_len: usize,
    out: *mut u8,
    out_len: usize,
) -> i32 {
    let Some(session_id) = read_str(session_id_ptr, session_id_len) else {
        return -1;
    };
    let reason = if reason_len == 0 || reason_ptr.is_null() {
        None
    } else {
        read_str(reason_ptr, reason_len).filter(|s| !s.is_empty())
    };
    let msg = Message::Response {
        session_id,
        accepted: accepted != 0,
        reason,
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
pub extern "C" fn nearby_c_msg_encode_batch_complete(
    session_id_ptr: *const u8,
    session_id_len: usize,
    file_count: u32,
    out: *mut u8,
    out_len: usize,
) -> i32 {
    let Some(session_id) = read_str(session_id_ptr, session_id_len) else {
        return -1;
    };
    let msg = Message::BatchComplete { session_id, file_count };
    write_bytes(out, out_len, &msg.encode())
}

#[no_mangle]
pub extern "C" fn nearby_c_msg_encode_cancel(
    session_id_ptr: *const u8,
    session_id_len: usize,
    reason_ptr: *const u8,
    reason_len: usize,
    out: *mut u8,
    out_len: usize,
) -> i32 {
    let Some(session_id) = read_str(session_id_ptr, session_id_len) else {
        return -1;
    };
    let reason = if reason_len == 0 || reason_ptr.is_null() {
        None
    } else {
        read_str(reason_ptr, reason_len)
    };
    let msg = Message::Cancel {
        session_id,
        reason,
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
        Ok(Message::BatchComplete { .. }) => 9,
        Err(_) => -4,
    }
}

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

/// Parse Response. accepted_out, optional reason into reason_out.
#[no_mangle]
pub extern "C" fn nearby_c_msg_parse_response(
    data_ptr: *const u8,
    data_len: usize,
    accepted_out: *mut i32,
    reason_out: *mut u8,
    reason_cap: usize,
    reason_len_out: *mut i32,
) -> i32 {
    if data_ptr.is_null() || accepted_out.is_null() {
        return -1;
    }
    let data = unsafe { slice::from_raw_parts(data_ptr, data_len) };
    match Message::decode(data) {
        Ok(Message::Response {
            accepted,
            reason,
            ..
        }) => {
            unsafe {
                *accepted_out = if accepted { 1 } else { 0 };
            }
            if !reason_len_out.is_null() {
                let r = reason.unwrap_or_default();
                let n = write_bytes(reason_out, reason_cap, r.as_bytes());
                if n < 0 {
                    return n;
                }
                unsafe {
                    *reason_len_out = n;
                }
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
    sid_out: *mut u8,
    sid_cap: usize,
    sid_len_out: *mut i32,
    file_id_out: *mut u32,
    seq_out: *mut u64,
    is_last_out: *mut i32,
    data_out: *mut u8,
    data_out_len: usize,
) -> i32 {
    if data_ptr.is_null() || sid_len_out.is_null() || file_id_out.is_null()
        || seq_out.is_null() || is_last_out.is_null() {
        return -1;
    }
    let data = unsafe { slice::from_raw_parts(data_ptr, data_len) };
    match Message::decode(data) {
        Ok(Message::Chunk { session_id, file_id, seq, data: chunk, is_last }) => {
            let n = write_bytes(sid_out, sid_cap, session_id.as_bytes());
            if n < 0 { return n; }
            unsafe {
                *sid_len_out = n;
                *file_id_out = file_id;
                *seq_out = seq;
                *is_last_out = if is_last { 1 } else { 0 };
            }
            write_bytes(data_out, data_out_len, &chunk)
        }
        Ok(_) => -5,
        Err(_) => -4,
    }
}

/// Parse a single-file Complete message, including its session, file id and checksum.
#[no_mangle]
pub extern "C" fn nearby_c_msg_parse_complete(
    data_ptr: *const u8,
    data_len: usize,
    sid_out: *mut u8,
    sid_cap: usize,
    sid_len_out: *mut i32,
    file_id_out: *mut u32,
    hash_out: *mut u8,
) -> i32 {
    if data_ptr.is_null() || sid_len_out.is_null() || file_id_out.is_null() || hash_out.is_null() {
        return -1;
    }
    let data = unsafe { slice::from_raw_parts(data_ptr, data_len) };
    match Message::decode(data) {
        Ok(Message::Complete { session_id, checksums }) if checksums.len() == 1 => {
            let n = write_bytes(sid_out, sid_cap, session_id.as_bytes());
            if n < 0 { return n; }
            let (file_id, hash) = &checksums[0];
            unsafe {
                *sid_len_out = n;
                *file_id_out = *file_id;
                ptr::copy_nonoverlapping(hash.as_ptr(), hash_out, 32);
            }
            0
        }
        Ok(Message::Complete { .. }) => -6,
        Ok(_) => -5,
        Err(_) => -4,
    }
}

/// Parse the explicit end-of-batch marker.
#[no_mangle]
pub extern "C" fn nearby_c_msg_parse_batch_complete(
    data_ptr: *const u8,
    data_len: usize,
    sid_out: *mut u8,
    sid_cap: usize,
    sid_len_out: *mut i32,
    file_count_out: *mut u32,
) -> i32 {
    if data_ptr.is_null() || sid_len_out.is_null() || file_count_out.is_null() {
        return -1;
    }
    let data = unsafe { slice::from_raw_parts(data_ptr, data_len) };
    match Message::decode(data) {
        Ok(Message::BatchComplete { session_id, file_count }) => {
            let n = write_bytes(sid_out, sid_cap, session_id.as_bytes());
            if n < 0 { return n; }
            unsafe {
                *sid_len_out = n;
                *file_count_out = file_count;
            }
            0
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


#[cfg(test)]
mod transfer_metadata_tests {
    use super::*;

    #[test]
    fn chunk_parser_preserves_session_file_and_sequence_metadata() {
        let message = Message::Chunk {
            session_id: "session-abc".into(),
            file_id: 7,
            seq: 42,
            data: vec![1, 2, 3],
            is_last: true,
        }.encode();
        let mut sid = [0u8; 64];
        let mut sid_len = 0;
        let mut file_id = 0;
        let mut seq = 0;
        let mut last = 0;
        let mut payload = [0u8; 16];
        let n = nearby_c_msg_parse_chunk(
            message.as_ptr(), message.len(), sid.as_mut_ptr(), sid.len(),
            &mut sid_len, &mut file_id, &mut seq, &mut last,
            payload.as_mut_ptr(), payload.len(),
        );
        assert_eq!(n, 3);
        assert_eq!(&sid[..sid_len as usize], b"session-abc");
        assert_eq!(file_id, 7);
        assert_eq!(seq, 42);
        assert_eq!(last, 1);
        assert_eq!(&payload[..3], &[1, 2, 3]);
    }

    #[test]
    fn complete_parser_returns_session_file_and_checksum() {
        let expected = [0xA5; 32];
        let message = Message::Complete {
            session_id: "session-xyz".into(),
            checksums: vec![(1, expected)],
        }.encode();
        let mut sid = [0u8; 64];
        let mut sid_len = 0;
        let mut file_id = 0;
        let mut hash = [0u8; 32];
        let rc = nearby_c_msg_parse_complete(
            message.as_ptr(), message.len(), sid.as_mut_ptr(), sid.len(),
            &mut sid_len, &mut file_id, hash.as_mut_ptr(),
        );
        assert_eq!(rc, 0);
        assert_eq!(&sid[..sid_len as usize], b"session-xyz");
        assert_eq!(file_id, 1);
        assert_eq!(hash, expected);
    }
}
