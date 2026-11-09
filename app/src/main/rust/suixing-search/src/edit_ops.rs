//! Literal edits over original UTF-16 units. Matching and output never normalize surrogates or
//! newlines. Only the first eight match line numbers cross JNI; no per-match index vector exists.
use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer, check_cancelled, task};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;
use std::sync::atomic::AtomicBool;

const MAGIC: u32 = 0x3145_5853;
const MAX_UNITS: usize = 4 * 1024 * 1024;
const SUMMARY: usize = 8;
const POLL: usize = 8192;
const COMPATIBILITY: i32 = 4;
const SUMMARY_ONLY: u32 = 0;
const CHANGED: u32 = 1;
const TOO_LARGE: u32 = 2;

/// # Safety
/// All nonempty UTF-16 inputs remain readable throughout the synchronous call, and output is
/// writable aligned SearchBuffer storage. oversized_replacement permits an empty replacement
/// instead of pinning an input that already exceeds the output character bound.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_edit_apply(
    id: u64,
    text: *const u16,
    text_len: usize,
    old: *const u16,
    old_len: usize,
    replacement: *const u16,
    replacement_len: usize,
    replace_all: bool,
    oversized_replacement: bool,
    max_units: u32,
    output: *mut SearchBuffer,
) -> i32 {
    if output.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        });
    }
    if max_units == 0
        || max_units as usize > MAX_UNITS
        || old_len == 0
        || (text_len != 0 && text.is_null())
        || old.is_null()
        || (replacement_len != 0 && replacement.is_null())
        || (oversized_replacement && replacement_len != 0)
        || old_len > i32::MAX as usize
        || replacement_len > max_units as usize
    {
        return STATUS_REQUEST_ERROR;
    }
    if text_len > max_units as usize {
        return COMPATIBILITY;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let cancelled = task(id).ok_or(STATUS_REQUEST_ERROR)?;
        check_cancelled(&cancelled)?;
        // Check the impossible needle before constructing its slice: on 32-bit targets a Java
        // string's declared length can otherwise exceed Rust's isize-sized u16 slice bound.
        let bytes = if old_len > text_len {
            response(SUMMARY_ONLY, 0, &[], &[], &cancelled)?
        } else {
            let text = if text_len == 0 {
                &[]
            } else {
                unsafe { slice::from_raw_parts(text, text_len) }
            };
            let old = unsafe { slice::from_raw_parts(old, old_len) };
            let replacement = if replacement_len == 0 {
                &[]
            } else {
                unsafe { slice::from_raw_parts(replacement, replacement_len) }
            };
            edit(
                text,
                old,
                replacement,
                replace_all,
                oversized_replacement,
                max_units as usize,
                &cancelled,
            )?
        };
        check_cancelled(&cancelled)?;
        let owned = bytes.into_boxed_slice();
        let len = owned.len();
        unsafe {
            output.write(SearchBuffer {
                data: Box::into_raw(owned) as *mut u8,
                len,
            });
        }
        Ok::<(), i32>(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

struct Needle<'a> {
    units: &'a [u16],
    prefix: Vec<u32>,
    newlines: u32,
}

fn poll(cancelled: &AtomicBool, work: &mut usize) -> Result<(), i32> {
    *work += 1;
    if *work % POLL == 0 {
        check_cancelled(cancelled)?;
    }
    Ok(())
}

impl<'a> Needle<'a> {
    fn new(units: &'a [u16], cancelled: &AtomicBool) -> Result<Self, i32> {
        let mut prefix = vec![0u32; units.len()];
        let mut matched = 0;
        let mut work = 0;
        let mut newlines = u32::from(units[0] == 10);
        for at in 1..units.len() {
            poll(cancelled, &mut work)?;
            newlines += u32::from(units[at] == 10);
            while matched > 0 && units[at] != units[matched] {
                poll(cancelled, &mut work)?;
                matched = prefix[matched - 1] as usize;
            }
            if units[at] == units[matched] {
                matched += 1;
            }
            prefix[at] = matched as u32;
        }
        check_cancelled(cancelled)?;
        Ok(Self {
            units,
            prefix,
            newlines,
        })
    }

    fn scan(
        &self,
        text: &[u16],
        cancelled: &AtomicBool,
        mut hit: impl FnMut(usize, u32) -> Result<(), i32>,
    ) -> Result<(), i32> {
        let mut matched = 0;
        let mut work = 0;
        let mut line = 1u32;
        for (at, &unit) in text.iter().enumerate() {
            poll(cancelled, &mut work)?;
            // This is intentionally LF-only, matching lineOf/lineNumbers in edit_file.
            line += u32::from(unit == 10);
            while matched > 0 && unit != self.units[matched] {
                poll(cancelled, &mut work)?;
                matched = self.prefix[matched - 1] as usize;
            }
            if unit == self.units[matched] {
                matched += 1;
            }
            if matched == self.units.len() {
                hit(at + 1 - matched, line - self.newlines)?;
                // A completed match cannot overlap the next one, as String.indexOf(start + len).
                matched = 0;
            }
        }
        check_cancelled(cancelled)
    }
}

fn append_units(output: &mut Vec<u16>, units: &[u16], cancelled: &AtomicBool) -> Result<(), i32> {
    for chunk in units.chunks(POLL) {
        check_cancelled(cancelled)?;
        output.extend_from_slice(chunk);
    }
    Ok(())
}

/// String.toByteArray(UTF_8) emits '?' for each unpaired surrogate. Measure the final joined
/// units, because deleting/replacing half a pair may create a new pair at a replacement boundary.
fn fits_utf8(units: &[u16], max: usize, cancelled: &AtomicBool) -> Result<bool, i32> {
    let mut at = 0;
    let mut bytes = 0usize;
    let mut work = 0;
    while at < units.len() {
        poll(cancelled, &mut work)?;
        let unit = units[at];
        bytes += if unit < 0x80 {
            1
        } else if unit < 0x800 {
            2
        } else if (0xd800..=0xdbff).contains(&unit) {
            if units
                .get(at + 1)
                .is_some_and(|unit| (0xdc00..=0xdfff).contains(unit))
            {
                at += 1;
                4
            } else {
                1
            }
        } else if (0xdc00..=0xdfff).contains(&unit) {
            1
        } else {
            3
        };
        if bytes > max {
            return Ok(false);
        }
        at += 1;
    }
    check_cancelled(cancelled)?;
    Ok(true)
}

fn response(
    kind: u32,
    count: u32,
    lines: &[u32],
    changed: &[u16],
    cancelled: &AtomicBool,
) -> Result<Vec<u8>, i32> {
    let mut bytes = Vec::with_capacity(24 + lines.len() * 4 + changed.len() * 2);
    for integer in [
        MAGIC,
        kind,
        count,
        lines.len() as u32,
        changed.len() as u32,
        0,
    ] {
        bytes.extend_from_slice(&integer.to_le_bytes());
    }
    for line in lines {
        bytes.extend_from_slice(&line.to_le_bytes());
    }
    let mut hash = 0u32;
    for (at, &unit) in changed.iter().enumerate() {
        if at % POLL == 0 {
            check_cancelled(cancelled)?;
        }
        hash = hash.wrapping_mul(31).wrapping_add(unit as u32);
        bytes.extend_from_slice(&unit.to_le_bytes());
    }
    bytes[20..24].copy_from_slice(&hash.to_le_bytes());
    check_cancelled(cancelled)?;
    Ok(bytes)
}

fn edit(
    text: &[u16],
    old: &[u16],
    replacement: &[u16],
    replace_all: bool,
    oversized_replacement: bool,
    max: usize,
    cancelled: &AtomicBool,
) -> Result<Vec<u8>, i32> {
    if old.len() > text.len() {
        return response(SUMMARY_ONLY, 0, &[], &[], cancelled);
    }
    let needle = Needle::new(old, cancelled)?;
    let mut count = 0u32;
    let mut lines = Vec::with_capacity(SUMMARY);
    needle.scan(text, cancelled, |_, line| {
        count += 1;
        if lines.len() < SUMMARY {
            lines.push(line);
        }
        Ok(())
    })?;
    if count == 0 || (!replace_all && count > 1) {
        return response(SUMMARY_ONLY, count, &lines, &[], cancelled);
    }
    if oversized_replacement {
        return response(TOO_LARGE, count, &lines, &[], cancelled);
    }
    let remaining = text.len() - count as usize * old.len();
    let Some(length) = (count as usize)
        .checked_mul(replacement.len())
        .and_then(|n| remaining.checked_add(n))
    else {
        return response(TOO_LARGE, count, &lines, &[], cancelled);
    };
    if length > max {
        return response(TOO_LARGE, count, &lines, &[], cancelled);
    }
    let mut changed = Vec::with_capacity(length);
    let mut copied = 0;
    needle.scan(text, cancelled, |start, _| {
        append_units(&mut changed, &text[copied..start], cancelled)?;
        append_units(&mut changed, replacement, cancelled)?;
        copied = start + old.len();
        Ok(())
    })?;
    append_units(&mut changed, &text[copied..], cancelled)?;
    if !fits_utf8(&changed, max, cancelled)? {
        return response(TOO_LARGE, count, &lines, &[], cancelled);
    }
    response(CHANGED, count, &lines, &changed, cancelled)
}
