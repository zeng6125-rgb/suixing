//! Exact Java UTF-16 key ordering and take(400), over one immutable memory snapshot.

use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer};
use std::cmp::Ordering;
use std::collections::BinaryHeap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;

const MAGIC: u32 = 0x3150_5853;
const COMPATIBILITY: i32 = 4;
const MAX_REQUEST: usize = 4 * 1024 * 1024;
const MAX_RESPONSE: usize = 4 * 1024 * 1024;
const MAX_ENTRIES: usize = 65_536;
const TAKE_ENTRIES: usize = 40;
const TAKE_VALUE_UNITS: usize = 400;

/// # Safety
/// Request bytes remain readable and output remains writable/aligned throughout this pure call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_memory_prompt(
    request: *const u8,
    len: usize,
    output: *mut SearchBuffer,
) -> i32 {
    if output.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    // SAFETY: JNI retains the writable output until this synchronous call exits.
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        })
    };
    if request.is_null() || !(8..=MAX_REQUEST).contains(&len) {
        return STATUS_REQUEST_ERROR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: JNI owns these request bytes for this complete synchronous computation.
        let bytes = unsafe { slice::from_raw_parts(request, len) };
        let result = format(bytes)?.into_boxed_slice();
        let len = result.len();
        let data = Box::into_raw(result) as *mut u8;
        // SAFETY: output remains writable; JNI returns the exact pointer/length to sx_search_free.
        unsafe { output.write(SearchBuffer { data, len }) };
        Ok(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

struct Input<'a> {
    bytes: &'a [u8],
    at: usize,
}
impl<'a> Input<'a> {
    fn integer(&mut self) -> Result<u32, i32> {
        let end = self.at.checked_add(4).ok_or(STATUS_REQUEST_ERROR)?;
        let value = self.bytes.get(self.at..end).ok_or(STATUS_REQUEST_ERROR)?;
        self.at = end;
        Ok(u32::from_le_bytes([value[0], value[1], value[2], value[3]]))
    }
    fn utf16(&mut self) -> Result<&'a [u8], i32> {
        let units = self.integer()? as usize;
        let end = units
            .checked_mul(2)
            .and_then(|len| self.at.checked_add(len))
            .ok_or(STATUS_REQUEST_ERROR)?;
        let value = self.bytes.get(self.at..end).ok_or(STATUS_REQUEST_ERROR)?;
        self.at = end;
        Ok(value)
    }
}

#[derive(Clone, Copy)]
struct Entry<'a> {
    key: &'a [u8],
    value: &'a [u8],
    ordinal: usize,
}
fn compare_utf16(left: &[u8], right: &[u8]) -> Ordering {
    // Byte lexicographic ordering of UTF16LE is incorrect; compare unsigned Java Char values.
    left.chunks_exact(2)
        .map(|unit| u16::from_le_bytes([unit[0], unit[1]]))
        .cmp(
            right
                .chunks_exact(2)
                .map(|unit| u16::from_le_bytes([unit[0], unit[1]])),
        )
}
impl Ord for Entry<'_> {
    fn cmp(&self, other: &Self) -> Ordering {
        compare_utf16(self.key, other.key).then(self.ordinal.cmp(&other.ordinal))
    }
}
impl PartialOrd for Entry<'_> {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}
impl PartialEq for Entry<'_> {
    fn eq(&self, other: &Self) -> bool {
        self.ordinal == other.ordinal && self.key == other.key
    }
}
impl Eq for Entry<'_> {}

fn format(bytes: &[u8]) -> Result<Vec<u8>, i32> {
    let mut input = Input { bytes, at: 0 };
    if input.integer()? != MAGIC {
        return Err(STATUS_REQUEST_ERROR);
    }
    let count = input.integer()? as usize;
    if count > MAX_ENTRIES {
        return Err(COMPATIBILITY);
    }
    // Selection retains forty borrowed entries; the source map and all values stay Kotlin-owned.
    let mut chosen: BinaryHeap<Entry<'_>> = BinaryHeap::with_capacity(TAKE_ENTRIES);
    for ordinal in 0..count {
        let entry = Entry {
            key: input.utf16()?,
            value: input.utf16()?,
            ordinal,
        };
        if chosen.len() < TAKE_ENTRIES {
            chosen.push(entry);
        } else if chosen.peek().is_some_and(|greatest| entry < *greatest) {
            chosen.pop();
            chosen.push(entry);
        }
    }
    if input.at != bytes.len() {
        return Err(STATUS_REQUEST_ERROR);
    }
    let chosen = chosen.into_sorted_vec();
    let mut response = vec![0; 8];
    fn ascii(response: &mut Vec<u8>, bytes: &[u8]) {
        for &byte in bytes {
            response.extend_from_slice(&(byte as u16).to_le_bytes());
        }
    }
    for (index, entry) in chosen.into_iter().enumerate() {
        let value = &entry.value[..entry.value.len().min(TAKE_VALUE_UNITS * 2)];
        let added = entry
            .key
            .len()
            .checked_add(value.len())
            .and_then(|size| size.checked_add(if index == 0 { 8 } else { 10 }))
            .ok_or(COMPATIBILITY)?;
        if response
            .len()
            .checked_add(added)
            .is_none_or(|size| size > MAX_RESPONSE)
        {
            return Err(COMPATIBILITY);
        }
        if index > 0 {
            ascii(&mut response, b"\n");
        }
        ascii(&mut response, b"- ");
        response.extend_from_slice(entry.key);
        ascii(&mut response, b": ");
        response.extend_from_slice(value);
    }
    let units = (response.len() - 8) / 2;
    response[0..4].copy_from_slice(&MAGIC.to_le_bytes());
    response[4..8].copy_from_slice(&(units as u32).to_le_bytes());
    Ok(response)
}
