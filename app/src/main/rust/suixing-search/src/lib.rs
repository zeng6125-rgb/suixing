//! Bounded, case-sensitive literal file search behind a small C ABI.
//!
//! Kotlin owns traversal order, permissions and tool formatting. A scan receives at most sixteen
//! paths and returns UTF-16 snippets so Kotlin's `trim().take(200)` semantics are preserved.
//! Each file is a bounded read before matching. Changed or unreadable candidates return a
//! compatibility sentinel before emitting rows, so Kotlin can preserve its streaming semantics.

use std::collections::{HashMap, VecDeque};
use std::fs::{File, OpenOptions};
use std::io::{self, Read};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::ptr;
use std::slice;
use std::str;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

const MAGIC: u32 = 0x3152_5853;
const MAX_REQUEST_BYTES: usize = 1024 * 1024;
const MAX_QUERY_BYTES: usize = 256 * 1024;
const MAX_PATHS: usize = 16;
const MAX_RESULTS: usize = 500;
const MAX_FILE_BYTES: usize = 2 * 1024 * 1024;
const READ_CHUNK_BYTES: usize = 8192;
const BINARY_PROBE_BYTES: usize = 2048;
const MAX_SNIPPET_UNITS: usize = 200;
const NO_COMPATIBILITY_FILE: u32 = u32::MAX;

const STATUS_OK: i32 = 0;
const STATUS_CANCELLED: i32 = 1;
const STATUS_REQUEST_ERROR: i32 = 2;
const STATUS_PANIC: i32 = 3;

/// Owned result storage. `sx_search_free` must receive the exact returned pointer and length.
#[repr(C)]
pub struct SearchBuffer {
    pub data: *mut u8,
    pub len: usize,
}

#[derive(Default)]
struct Tasks {
    last_id: u64,
    entries: HashMap<u64, Arc<AtomicBool>>,
}

static TASKS: OnceLock<Mutex<Tasks>> = OnceLock::new();

fn tasks() -> &'static Mutex<Tasks> {
    TASKS.get_or_init(|| Mutex::new(Tasks::default()))
}

fn task(id: u64) -> Option<Arc<AtomicBool>> {
    if id == 0 || id > i64::MAX as u64 {
        return None;
    }
    tasks()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .entries
        .get(&id)
        .cloned()
}

/// Create a monotonically numbered cancellation owner, never a raw pointer handle.
#[unsafe(no_mangle)]
pub extern "C" fn sx_search_create() -> u64 {
    catch_unwind(|| {
        let mut registry = tasks()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let Some(id) = registry
            .last_id
            .checked_add(1)
            .filter(|id| *id <= i64::MAX as u64)
        else {
            return 0;
        };
        registry.last_id = id;
        registry
            .entries
            .insert(id, Arc::new(AtomicBool::new(false)));
        id
    })
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "C" fn sx_search_cancel(id: u64) {
    let _ = catch_unwind(|| {
        if let Some(cancelled) = task(id) {
            cancelled.store(true, Ordering::Release);
        }
    });
}

/// Remove the registry owner; an in-flight scan retains its own Arc until it exits.
#[unsafe(no_mangle)]
pub extern "C" fn sx_search_release(id: u64) {
    let _ = catch_unwind(|| {
        if let Some(cancelled) = tasks()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .entries
            .remove(&id)
        {
            cancelled.store(true, Ordering::Release);
        }
    });
}

/// Scan a validated batch request. Non-success statuses return an empty output buffer.
///
/// # Safety
/// `output` must be writable and aligned for SearchBuffer. The non-null request must contain
/// `length` readable bytes, and neither memory region may be concurrently mutated by the caller.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_search_scan(
    id: u64,
    request: *const u8,
    length: usize,
    output: *mut SearchBuffer,
) -> i32 {
    if output.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    // SAFETY: the caller provides a valid writable output pointer by the ABI contract.
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        });
    }
    if request.is_null() || length == 0 || length > MAX_REQUEST_BYTES {
        return STATUS_REQUEST_ERROR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let cancelled = task(id).ok_or(STATUS_REQUEST_ERROR)?;
        if cancelled.load(Ordering::Acquire) {
            return Err(STATUS_CANCELLED);
        }
        // SAFETY: the caller owns these request bytes throughout this synchronous scan.
        let bytes = unsafe { slice::from_raw_parts(request, length) };
        let parsed = Request::parse(bytes).ok_or(STATUS_REQUEST_ERROR)?;
        let result = scan(&parsed, &cancelled)?;
        let owned = result.into_boxed_slice();
        let len = owned.len();
        let data = Box::into_raw(owned) as *mut u8;
        // SAFETY: output remains valid for the duration of this call.
        unsafe { output.write(SearchBuffer { data, len }) };
        Ok(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

/// # Safety
/// A non-null pointer must be the exact result returned by sx_search_scan, paired with its
/// original length, and must be released once. Null with length zero is allowed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_search_free(data: *mut u8, len: usize) {
    if data.is_null() {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: the ABI owner returns the unchanged Box<[u8]> pointer and length exactly once.
        let owned = unsafe { Box::from_raw(ptr::slice_from_raw_parts_mut(data, len)) };
        drop(owned);
    }));
}

struct Request<'a> {
    max_results: usize,
    context: usize,
    files_only: bool,
    query: &'a str,
    paths: Vec<&'a str>,
}

impl<'a> Request<'a> {
    fn parse(bytes: &'a [u8]) -> Option<Self> {
        let mut input = Input { bytes, at: 0 };
        if input.u32()? != MAGIC {
            return None;
        }
        let count = input.u32()? as usize;
        let max_results = input.u32()? as usize;
        let context = input.u32()? as usize;
        let files_only = input.u32()?;
        let max_file_bytes = input.u32()? as usize;
        if !(1..=MAX_PATHS).contains(&count)
            || !(1..=MAX_RESULTS).contains(&max_results)
            || context > 5
            || files_only > 1
            || max_file_bytes != MAX_FILE_BYTES
        {
            return None;
        }
        let query = input.string()?;
        if query.is_empty() || query.len() > MAX_QUERY_BYTES {
            return None;
        }
        let mut paths = Vec::with_capacity(count);
        for _ in 0..count {
            let path = input.string()?;
            if !Path::new(path).is_absolute() || path.contains('\0') {
                return None;
            }
            paths.push(path);
        }
        if input.at != bytes.len() {
            return None;
        }
        Some(Self {
            max_results,
            context,
            files_only: files_only != 0,
            query,
            paths,
        })
    }
}

struct Input<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Input<'a> {
    fn u32(&mut self) -> Option<u32> {
        let end = self.at.checked_add(4)?;
        let value = u32::from_le_bytes(self.bytes.get(self.at..end)?.try_into().ok()?);
        self.at = end;
        Some(value)
    }

    fn string(&mut self) -> Option<&'a str> {
        let length = self.u32()? as usize;
        let end = self.at.checked_add(length)?;
        let value = str::from_utf8(self.bytes.get(self.at..end)?).ok()?;
        self.at = end;
        Some(value)
    }
}

struct Response {
    bytes: Vec<u8>,
    rows: usize,
}

impl Response {
    fn new() -> Self {
        Self {
            bytes: vec![0; 16],
            rows: 0,
        }
    }

    fn row(
        &mut self,
        path: usize,
        line: u32,
        marker: u32,
        snippet: &str,
        cancelled: &AtomicBool,
    ) -> Result<(), i32> {
        let trimmed = trim_java_whitespace(snippet, cancelled)?;
        let mut units = [0u16; MAX_SNIPPET_UNITS];
        let mut count = 0;
        for unit in trimmed.encode_utf16().take(MAX_SNIPPET_UNITS) {
            units[count] = unit;
            count += 1;
        }
        self.bytes.extend_from_slice(&(path as u32).to_le_bytes());
        self.bytes.extend_from_slice(&line.to_le_bytes());
        self.bytes.extend_from_slice(&marker.to_le_bytes());
        self.bytes.extend_from_slice(&(count as u32).to_le_bytes());
        for unit in &units[..count] {
            self.bytes.extend_from_slice(&unit.to_le_bytes());
        }
        self.rows += 1;
        Ok(())
    }

    fn finish(mut self, processed: usize, compatibility: u32) -> Vec<u8> {
        self.bytes[0..4].copy_from_slice(&MAGIC.to_le_bytes());
        self.bytes[4..8].copy_from_slice(&(processed as u32).to_le_bytes());
        self.bytes[8..12].copy_from_slice(&(self.rows as u32).to_le_bytes());
        self.bytes[12..16].copy_from_slice(&compatibility.to_le_bytes());
        self.bytes
    }
}

fn java_whitespace(ch: char) -> bool {
    matches!(
        ch,
        '\u{0009}'..='\u{000d}'
            | '\u{001c}'..='\u{0020}'
            | '\u{00a0}'
            | '\u{1680}'
            | '\u{2000}'..='\u{200a}'
            | '\u{2028}'
            | '\u{2029}'
            | '\u{202f}'
            | '\u{205f}'
            | '\u{3000}'
    )
}

fn check_cancelled(cancelled: &AtomicBool) -> Result<(), i32> {
    if cancelled.load(Ordering::Acquire) {
        Err(STATUS_CANCELLED)
    } else {
        Ok(())
    }
}

/// Bound cancellation latency even inside long matching fallback chains or whitespace runs.
struct CancellationPoll<'a> {
    cancelled: &'a AtomicBool,
    remaining: usize,
}

impl<'a> CancellationPoll<'a> {
    fn new(cancelled: &'a AtomicBool) -> Result<Self, i32> {
        check_cancelled(cancelled)?;
        Ok(Self {
            cancelled,
            remaining: READ_CHUNK_BYTES,
        })
    }

    fn advance(&mut self, work: usize) -> Result<(), i32> {
        if work >= self.remaining {
            check_cancelled(self.cancelled)?;
            self.remaining = READ_CHUNK_BYTES;
        } else {
            self.remaining -= work;
        }
        Ok(())
    }
}

/// Match UTF-8 bytes with a reusable prefix table. Valid UTF-8 queries and inputs make byte
/// matching equivalent to literal string matching, including across read/chunk boundaries.
struct LiteralNeedle<'a> {
    bytes: &'a [u8],
    prefix: Vec<usize>,
}

impl<'a> LiteralNeedle<'a> {
    fn new(query: &'a str, cancelled: &AtomicBool) -> Result<Self, i32> {
        let bytes = query.as_bytes();
        let mut prefix = vec![0; bytes.len()];
        let mut matched = 0;
        let mut poll = CancellationPoll::new(cancelled)?;
        for at in 1..bytes.len() {
            poll.advance(1)?;
            while matched > 0 && bytes[at] != bytes[matched] {
                poll.advance(1)?;
                matched = prefix[matched - 1];
            }
            if bytes[at] == bytes[matched] {
                matched += 1;
            }
            prefix[at] = matched;
        }
        check_cancelled(cancelled)?;
        Ok(Self { bytes, prefix })
    }

    fn matches(&self, line: &str, cancelled: &AtomicBool) -> Result<bool, i32> {
        let mut poll = CancellationPoll::new(cancelled)?;
        if line.len() < self.bytes.len() {
            return Ok(false);
        }
        let mut matched = 0;
        for chunk in line.as_bytes().chunks(READ_CHUNK_BYTES) {
            check_cancelled(cancelled)?;
            for &byte in chunk {
                poll.advance(1)?;
                while matched > 0 && byte != self.bytes[matched] {
                    poll.advance(1)?;
                    matched = self.prefix[matched - 1];
                }
                if byte == self.bytes[matched] {
                    matched += 1;
                    if matched == self.bytes.len() {
                        check_cancelled(cancelled)?;
                        return Ok(true);
                    }
                }
            }
        }
        check_cancelled(cancelled)?;
        Ok(false)
    }
}

fn trim_java_whitespace<'a>(text: &'a str, cancelled: &AtomicBool) -> Result<&'a str, i32> {
    let mut poll = CancellationPoll::new(cancelled)?;
    let mut start = 0;
    for (at, ch) in text.char_indices() {
        poll.advance(ch.len_utf8())?;
        if !java_whitespace(ch) {
            start = at;
            break;
        }
        start = at + ch.len_utf8();
    }
    if start == text.len() {
        check_cancelled(cancelled)?;
        return Ok("");
    }
    let mut end = text.len();
    for (at, ch) in text.char_indices().rev() {
        poll.advance(ch.len_utf8())?;
        if at < start || !java_whitespace(ch) {
            break;
        }
        end = at;
    }
    check_cancelled(cancelled)?;
    Ok(&text[start..end])
}

enum FileBytes {
    Skip,
    Compatibility,
    Bytes(Vec<u8>),
}

fn read_file(path: &str, cancelled: &AtomicBool) -> Result<FileBytes, i32> {
    if cancelled.load(Ordering::Acquire) {
        return Err(STATUS_CANCELLED);
    }
    // Check the path before open, then the opened descriptor too, to reject non-regular files.
    let metadata = match std::fs::metadata(path) {
        Ok(metadata) if !metadata.is_file() => return Ok(FileBytes::Skip),
        Ok(metadata) if metadata.len() <= MAX_FILE_BYTES as u64 => metadata,
        _ => return Ok(FileBytes::Compatibility),
    };
    let mut file = match open_regular(path) {
        Ok(file) => file,
        Err(_) => return Ok(FileBytes::Skip),
    };
    match file.metadata() {
        Ok(opened) if !opened.is_file() => return Ok(FileBytes::Skip),
        Ok(opened) if opened.len() <= MAX_FILE_BYTES as u64 => {}
        _ => return Ok(FileBytes::Compatibility),
    }
    let mut bytes = Vec::with_capacity((metadata.len() as usize).min(MAX_FILE_BYTES));
    let mut buffer = [0u8; READ_CHUNK_BYTES];
    loop {
        if cancelled.load(Ordering::Acquire) {
            return Err(STATUS_CANCELLED);
        }
        let remaining = MAX_FILE_BYTES + 1 - bytes.len();
        let count = match file.read(&mut buffer[..remaining.min(READ_CHUNK_BYTES)]) {
            Ok(0) => break,
            Ok(count) => count,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(_) => return Ok(FileBytes::Compatibility),
        };
        let prefix_remaining = BINARY_PROBE_BYTES.saturating_sub(bytes.len());
        if buffer[..count.min(prefix_remaining)].contains(&0) {
            return Ok(FileBytes::Skip);
        }
        bytes.extend_from_slice(&buffer[..count]);
        if bytes.len() > MAX_FILE_BYTES {
            return Ok(FileBytes::Compatibility);
        }
    }
    Ok(FileBytes::Bytes(bytes))
}

fn open_regular(path: &str) -> io::Result<File> {
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        use std::os::unix::fs::OpenOptionsExt;
        // Linux/Android O_NONBLOCK prevents a raced FIFO replacement from blocking at open.
        options.custom_flags(0x800);
    }
    options.open(path)
}

fn scan(request: &Request<'_>, cancelled: &AtomicBool) -> Result<Vec<u8>, i32> {
    let mut response = Response::new();
    let needle = LiteralNeedle::new(request.query, cancelled)?;
    let mut processed = 0;
    let mut compatibility = NO_COMPATIBILITY_FILE;
    for (path_index, path) in request.paths.iter().enumerate() {
        if cancelled.load(Ordering::Acquire) {
            return Err(STATUS_CANCELLED);
        }
        if response.rows >= request.max_results {
            break;
        }
        let bytes = read_file(path, cancelled)?;
        processed += 1;
        let bytes = match bytes {
            FileBytes::Skip => continue,
            FileBytes::Compatibility => {
                compatibility = path_index as u32;
                break;
            }
            FileBytes::Bytes(bytes) => bytes,
        };
        // Validate before emitting any rows from this file. Kotlin can rescan precisely this
        // candidate with its replacement decoder, then continue the rest of the batch.
        let text = match str::from_utf8(&bytes) {
            Ok(text) => text,
            Err(_) => {
                compatibility = path_index as u32;
                break;
            }
        };
        scan_lines(request, &needle, path_index, text, cancelled, &mut response)?;
    }
    if cancelled.load(Ordering::Acquire) {
        return Err(STATUS_CANCELLED);
    }
    Ok(response.finish(processed, compatibility))
}

fn scan_lines(
    request: &Request<'_>,
    needle: &LiteralNeedle<'_>,
    path: usize,
    text: &str,
    cancelled: &AtomicBool,
    response: &mut Response,
) -> Result<(), i32> {
    let mut previous: VecDeque<(u32, &str)> = VecDeque::with_capacity(request.context);
    let mut number = 0u32;
    let mut after = 0usize;
    let mut last_emitted = 0u32;
    let mut start = 0usize;
    let mut at = 0usize;
    let bytes = text.as_bytes();
    loop {
        if cancelled.load(Ordering::Acquire) {
            return Err(STATUS_CANCELLED);
        }
        while at < bytes.len() && bytes[at] != b'\n' && bytes[at] != b'\r' {
            at += 1;
            if at % READ_CHUNK_BYTES == 0 && cancelled.load(Ordering::Acquire) {
                return Err(STATUS_CANCELLED);
            }
        }
        number += 1;
        let line = &text[start..at];
        let matched = needle.matches(line, cancelled)?;
        if matched && request.files_only {
            response.row(path, 0, 2, "", cancelled)?;
            return Ok(());
        }
        if matched {
            for &(before_number, before_line) in &previous {
                emit(
                    response,
                    request.max_results,
                    path,
                    before_number,
                    1,
                    before_line,
                    &mut last_emitted,
                    cancelled,
                )?;
            }
            emit(
                response,
                request.max_results,
                path,
                number,
                0,
                line,
                &mut last_emitted,
                cancelled,
            )?;
            after = request.context;
        } else if after > 0 {
            emit(
                response,
                request.max_results,
                path,
                number,
                1,
                line,
                &mut last_emitted,
                cancelled,
            )?;
            after -= 1;
        }
        if response.rows >= request.max_results || at == bytes.len() {
            return Ok(());
        }
        if request.context > 0 {
            previous.push_back((number, line));
            if previous.len() > request.context {
                previous.pop_front();
            }
        }
        let delimiter = bytes[at];
        at += 1;
        if delimiter == b'\r' && at < bytes.len() && bytes[at] == b'\n' {
            at += 1;
        }
        start = at;
        // An empty final line is visited even if the file is empty or ends in CR/LF/CRLF.
    }
}

fn emit(
    response: &mut Response,
    max_results: usize,
    path: usize,
    number: u32,
    marker: u32,
    line: &str,
    last_emitted: &mut u32,
    cancelled: &AtomicBool,
) -> Result<(), i32> {
    check_cancelled(cancelled)?;
    if number > *last_emitted && response.rows < max_results {
        response.row(path, number, marker, line, cancelled)?;
        *last_emitted = number;
    }
    Ok(())
}
