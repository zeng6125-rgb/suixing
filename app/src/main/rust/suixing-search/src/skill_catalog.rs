//! Skill document ranges and prompt catalogue formatting over exact UTF-16.
//! Filesystem discovery, enabled/origin state, install paths and arbitrary Java regex stay Kotlin.

use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer, java_whitespace};
use std::collections::{HashMap, HashSet};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::{ptr, slice};

const MAGIC: u32 = 0x314b_5853;
const STATUS_COMPATIBILITY: i32 = 4;
const MAX_UNITS: usize = 2 * 1024 * 1024;
const MAX_METADATA_BYTES: usize = 8 * 1024 * 1024;
const MAX_OUTPUT_UNITS: usize = 4 * 1024 * 1024;
const MAX_ENTRIES: usize = 4096;
const MAX_SKILLS: usize = 16_384;
const MAX_KEYS: usize = 65_536;

fn emit(output: *mut SearchBuffer, work: impl FnOnce() -> Result<Vec<u8>, i32>) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let owned = work()?.into_boxed_slice();
        let len = owned.len();
        let data = Box::into_raw(owned) as *mut u8;
        // SAFETY: all entry points validate the ABI's writable output before calling emit.
        unsafe { output.write(SearchBuffer { data, len }) };
        Ok(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

fn initialize(output: *mut SearchBuffer) -> bool {
    if output.is_null() {
        return false;
    }
    // SAFETY: the entry point's caller provides aligned writable output storage.
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        })
    };
    true
}

/// # Safety
/// Nonempty text is aligned readable immutable UTF-16 for length units. Output is writable
/// SearchBuffer storage. Both are retained until this call returns; release with sx_search_free.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_skill_document(
    text: *const u16,
    length: usize,
    output: *mut SearchBuffer,
) -> i32 {
    if !initialize(output) {
        return STATUS_REQUEST_ERROR;
    }
    if length > MAX_UNITS {
        return STATUS_COMPATIBILITY;
    }
    if length > 0 && text.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    emit(output, || {
        // SAFETY: nonempty pointer and bounded length were checked by the ABI contract.
        let text = if length == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(text, length) }
        };
        document(text)
    })
}

/// # Safety
/// The same UTF-16/output ownership contract as sx_skill_document applies.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_skill_one_line(
    text: *const u16,
    length: usize,
    limit: i32,
    output: *mut SearchBuffer,
) -> i32 {
    if !initialize(output) {
        return STATUS_REQUEST_ERROR;
    }
    if length > MAX_UNITS || limit < 0 {
        return STATUS_COMPATIBILITY;
    }
    if length > 0 && text.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    emit(output, || {
        // SAFETY: nonempty pointer and bounded length were checked by the ABI contract.
        let text = if length == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(text, length) }
        };
        utf16_bytes(&one_line(text, limit as usize)?)
    })
}

/// # Safety
/// Request is a bounded immutable readable byte range and output is aligned writable storage.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_skill_catalog(
    request: *const u8,
    length: usize,
    output: *mut SearchBuffer,
) -> i32 {
    if !initialize(output) {
        return STATUS_REQUEST_ERROR;
    }
    if length > MAX_METADATA_BYTES {
        return STATUS_COMPATIBILITY;
    }
    if request.is_null() || length < 20 {
        return STATUS_REQUEST_ERROR;
    }
    emit(output, || {
        // SAFETY: the caller retains this nonempty bounded request throughout the call.
        catalog(unsafe { slice::from_raw_parts(request, length) })
    })
}

#[derive(Clone, Copy)]
struct Range {
    start: usize,
    end: usize,
}

impl Range {
    fn len(self) -> usize {
        self.end - self.start
    }
}

fn whitespace(unit: u16) -> bool {
    char::from_u32(u32::from(unit)).is_some_and(java_whitespace)
}

fn trim_start(text: &[u16], mut range: Range, predicate: fn(u16) -> bool) -> Range {
    while range.start < range.end && predicate(text[range.start]) {
        range.start += 1;
    }
    range
}

fn trim_end(text: &[u16], mut range: Range, predicate: fn(u16) -> bool) -> Range {
    while range.start < range.end && predicate(text[range.end - 1]) {
        range.end -= 1;
    }
    range
}

fn trim(text: &[u16], range: Range, predicate: fn(u16) -> bool) -> Range {
    trim_end(text, trim_start(text, range, predicate), predicate)
}

fn body_prefix(unit: u16) -> bool {
    matches!(unit, 0x0a | 0x0d | 0x20 | 0x09)
}
fn quotes(unit: u16) -> bool {
    matches!(unit, 0x22 | 0x27 | 0x20)
}

/// Kotlin lineSequence recognizes CRLF, LF and CR, including a final empty line.
fn line_at(text: &[u16], at: usize, end: usize) -> (Range, usize) {
    let mut stop = at;
    while stop < end && !matches!(text[stop], 0x0a | 0x0d) {
        stop += 1;
    }
    let next = if stop == end {
        end + 1
    } else if text[stop] == 0x0d && stop + 1 < end && text[stop + 1] == 0x0a {
        stop + 2
    } else {
        stop + 1
    };
    (
        Range {
            start: at,
            end: stop,
        },
        next,
    )
}

fn ascii(text: &[u16], range: Range, value: &[u8]) -> bool {
    range.len() == value.len()
        && text[range.start..range.end]
            .iter()
            .zip(value)
            .all(|(&left, &right)| left == u16::from(right))
}

fn document(text: &[u16]) -> Result<Vec<u8>, i32> {
    let full = Range {
        start: 0,
        end: text.len(),
    };
    let beginning = trim_start(text, full, whitespace);
    let mut body = full;
    let mut header = None;
    if text.get(beginning.start..beginning.start + 3) == Some(&[0x2d, 0x2d, 0x2d]) {
        let mut at = beginning.start + 3;
        while at + 4 <= text.len() {
            if text[at..at + 4] == [0x0a, 0x2d, 0x2d, 0x2d] {
                header = Some(trim(
                    text,
                    Range {
                        start: beginning.start + 3,
                        end: at,
                    },
                    whitespace,
                ));
                body = trim_start(
                    text,
                    Range {
                        start: at + 4,
                        end: text.len(),
                    },
                    body_prefix,
                );
                break;
            }
            at += 1;
        }
    }
    if header.is_none() {
        body = trim(text, full, whitespace);
    }
    let mut entries: Vec<(Range, Range)> = Vec::new();
    let mut indices: HashMap<&[u16], usize> = HashMap::new();
    if let Some(header) = header {
        let mut at = header.start;
        while at <= header.end {
            let (line, next) = line_at(text, at, header.end);
            at = next;
            let line = trim(text, line, whitespace);
            if line.len() == 0 || text[line.start] == 0x23 {
                continue;
            }
            let colon = text[line.start..line.end]
                .iter()
                .position(|&unit| unit == 0x3a);
            let Some(colon) = colon.map(|offset| line.start + offset) else {
                continue;
            };
            let key = trim(
                text,
                Range {
                    start: line.start,
                    end: colon,
                },
                whitespace,
            );
            let value = trim(
                text,
                trim(
                    text,
                    Range {
                        start: colon + 1,
                        end: line.end,
                    },
                    whitespace,
                ),
                quotes,
            );
            let key_text = &text[key.start..key.end];
            if let Some(&index) = indices.get(key_text) {
                entries[index].1 = value;
            } else {
                if entries.len() >= MAX_ENTRIES {
                    return Err(STATUS_COMPATIBILITY);
                }
                indices.insert(key_text, entries.len());
                entries.push((key, value));
            }
        }
    }
    let mut name = None;
    let mut description = None;
    for &(key, value) in &entries {
        if trim(text, value, whitespace).len() == 0 {
            continue;
        }
        if ascii(text, key, b"name") {
            name = Some(value);
        }
        if ascii(text, key, b"description") {
            description = Some(value);
        }
    }
    let description = description.unwrap_or_else(|| {
        let mut at = body.start;
        while at <= body.end {
            let (line, next) = line_at(text, at, body.end);
            at = next;
            if trim(text, line, whitespace).len() > 0 {
                return Range {
                    start: line.start,
                    end: line.start + line.len().min(120),
                };
            }
        }
        Range { start: 0, end: 0 }
    });
    let mut output = Vec::with_capacity(32 + entries.len() * 16);
    output.extend_from_slice(&MAGIC.to_le_bytes());
    write_range(&mut output, body);
    if let Some(name) = name {
        write_range(&mut output, name);
    } else {
        output.extend_from_slice(&u32::MAX.to_le_bytes());
        output.extend_from_slice(&0u32.to_le_bytes());
    }
    write_range(&mut output, description);
    output.extend_from_slice(&(entries.len() as u32).to_le_bytes());
    for (key, value) in entries {
        write_range(&mut output, key);
        write_range(&mut output, value);
    }
    Ok(output)
}

fn write_range(output: &mut Vec<u8>, range: Range) {
    output.extend_from_slice(&(range.start as u32).to_le_bytes());
    output.extend_from_slice(&(range.len() as u32).to_le_bytes());
}

fn cleaned(unit: u16) -> u16 {
    if unit <= 0x1f
        || (0x7f..=0x9f).contains(&unit)
        || matches!(unit, 0x200b | 0x200c | 0x200d | 0x2060 | 0xfeff)
    {
        0x20
    } else {
        unit
    }
}

/// This is the common ASCII subset of the platform's \s+ pattern, after cleanup.
/// Platform-sensitive Unicode whitespace selects the complete original Regex path below.
fn regex_whitespace(unit: u16) -> bool {
    matches!(unit, 0x20 | 0x09 | 0x0a | 0x0b | 0x0c | 0x0d)
}

fn one_line(text: &[u16], limit: usize) -> Result<Vec<u16>, i32> {
    let mut flat = Vec::with_capacity(text.len());
    let mut previous_space = false;
    for &unit in text {
        let unit = cleaned(unit);
        // Android/ICU and desktop Regex need not classify Unicode spaces identically.
        // Ordinary non-ASCII text remains native; only sensitive spaces select compatibility.
        if unit > 0x7f && (whitespace(unit) || unit == 0x180e) {
            return Err(STATUS_COMPATIBILITY);
        }
        let space = regex_whitespace(unit);
        if !space || !previous_space {
            flat.push(if space { 0x20 } else { unit });
        }
        previous_space = space;
    }
    let range = trim(
        &flat,
        Range {
            start: 0,
            end: flat.len(),
        },
        whitespace,
    );
    if range.len() <= limit {
        return Ok(flat[range.start..range.end].to_vec());
    }
    let clipped = trim_end(
        &flat,
        Range {
            start: range.start,
            end: range.start + limit,
        },
        whitespace,
    );
    let mut output = flat[clipped.start..clipped.end].to_vec();
    output.push(0x2026);
    Ok(output)
}

fn utf16_bytes(text: &[u16]) -> Result<Vec<u8>, i32> {
    if text.len() > MAX_OUTPUT_UNITS {
        return Err(STATUS_COMPATIBILITY);
    }
    let mut bytes = Vec::with_capacity(text.len() * 2);
    for &unit in text {
        bytes.extend_from_slice(&unit.to_le_bytes());
    }
    Ok(bytes)
}

struct Input<'a> {
    bytes: &'a [u8],
    at: usize,
}
impl<'a> Input<'a> {
    fn take(&mut self, count: usize) -> Result<&'a [u8], i32> {
        let end = self.at.checked_add(count).ok_or(STATUS_REQUEST_ERROR)?;
        let value = self.bytes.get(self.at..end).ok_or(STATUS_REQUEST_ERROR)?;
        self.at = end;
        Ok(value)
    }
    fn u32(&mut self) -> Result<u32, i32> {
        let value = self.take(4)?;
        Ok(u32::from_le_bytes([value[0], value[1], value[2], value[3]]))
    }
    fn index(&mut self, count: usize) -> Result<usize, i32> {
        let index = self.u32()? as usize;
        if index >= count {
            return Err(STATUS_REQUEST_ERROR);
        }
        Ok(index)
    }
}

struct TextOutput(Vec<u16>);
impl TextOutput {
    fn append(&mut self, text: &[u16]) -> Result<(), i32> {
        if self
            .0
            .len()
            .checked_add(text.len())
            .is_none_or(|size| size > MAX_OUTPUT_UNITS)
        {
            return Err(STATUS_COMPATIBILITY);
        }
        self.0.extend_from_slice(text);
        Ok(())
    }
    fn ascii(&mut self, text: &[u8]) -> Result<(), i32> {
        if self
            .0
            .len()
            .checked_add(text.len())
            .is_none_or(|size| size > MAX_OUTPUT_UNITS)
        {
            return Err(STATUS_COMPATIBILITY);
        }
        self.0.extend(text.iter().map(|&unit| u16::from(unit)));
        Ok(())
    }
}

fn summary<'a>(
    keys: &[Vec<u16>],
    cache: &'a mut [Option<Vec<u16>>],
    index: usize,
    limit: usize,
) -> Result<&'a [u16], i32> {
    if cache[index].is_none() {
        cache[index] = Some(one_line(&keys[index], limit)?);
    }
    cache[index].as_deref().ok_or(STATUS_REQUEST_ERROR)
}

fn catalog(bytes: &[u8]) -> Result<Vec<u8>, i32> {
    let mut input = Input { bytes, at: 0 };
    if input.u32()? != MAGIC {
        return Err(STATUS_REQUEST_ERROR);
    }
    let limit = input.u32()? as usize;
    let key_count = input.u32()? as usize;
    let row_count = input.u32()? as usize;
    let preamble = input.u32()? as usize;
    if preamble >= key_count
        || key_count > (bytes.len() - input.at) / 4
        || row_count > (bytes.len() - input.at) / 12
    {
        return Err(STATUS_REQUEST_ERROR);
    }
    if limit > MAX_UNITS || key_count > MAX_KEYS || row_count > MAX_SKILLS {
        return Err(STATUS_COMPATIBILITY);
    }
    let mut keys = Vec::with_capacity(key_count);
    let mut seen = HashSet::new();
    for _ in 0..key_count {
        let length = input.u32()? as usize;
        let raw = input.take(length.checked_mul(2).ok_or(STATUS_REQUEST_ERROR)?)?;
        let key: Vec<u16> = raw
            .chunks_exact(2)
            .map(|unit| u16::from_le_bytes([unit[0], unit[1]]))
            .collect();
        if !seen.insert(key.clone()) {
            return Err(STATUS_REQUEST_ERROR);
        }
        keys.push(key);
    }
    let mut output = TextOutput(Vec::new());
    let mut cache = vec![None; key_count];
    if row_count > 0 {
        output.append(&keys[preamble])?;
    }
    for _ in 0..row_count {
        let id = input.index(key_count)?;
        let name = input.index(key_count)?;
        let description = input.index(key_count)?;
        output.ascii(b"- ")?;
        output.append(summary(&keys, &mut cache, name, limit)?)?;
        output.ascii(b" (id: ")?;
        output.append(&keys[id])?;
        output.ascii(b"): ")?;
        output.append(summary(&keys, &mut cache, description, limit)?)?;
        output.ascii(b"\n")?;
    }
    if input.at != bytes.len() {
        return Err(STATUS_REQUEST_ERROR);
    }
    let range = trim(
        &output.0,
        Range {
            start: 0,
            end: output.0.len(),
        },
        whitespace,
    );
    utf16_bytes(&output.0[range.start..range.end])
}
