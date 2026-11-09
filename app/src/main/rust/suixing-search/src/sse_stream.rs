//! Stateful SSE byte framing and JSON decoding. One completed event is returned per call so an
//! error in later buffered bytes never discards a delta that the caller has already received.
//! Network reads, protocol event handling and retries remain owned by Kotlin/OkHttp.

use crate::{STATUS_CANCELLED, STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer};
use serde_json::Value;
use std::borrow::Cow;
use std::collections::{HashMap, HashSet};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;
use std::str;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

const MAGIC: u32 = 0x3153_5853;
const READ_BYTES: usize = 8192;
const MAX_LINE_BYTES: usize = 3 * 1_048_576 + 16;
const MAX_FRAME_UNITS: usize = 1_048_576;
const MAX_TREE_BYTES: usize = 8 * 1_048_576;
const MAX_DEPTH: usize = 128;
const MAX_NODES: usize = 131_072;
const FLAG_DONE: u32 = 1;
const FLAG_EOF: u32 = 2;
const KIND_NONE: u32 = 0;
const KIND_TREE: u32 = 1;
const KIND_COMPATIBILITY: u32 = 2;
const KIND_ERROR: u32 = 3;
const KIND_NORMALIZE_LINE: u32 = 4;

#[derive(Default)]
struct Registry {
    last_id: u64,
    entries: HashMap<u64, Arc<Stream>>,
}

struct Stream {
    cancelled: AtomicBool,
    state: Mutex<State>,
}

static STREAMS: OnceLock<Mutex<Registry>> = OnceLock::new();

fn registry() -> &'static Mutex<Registry> {
    STREAMS.get_or_init(|| Mutex::new(Registry::default()))
}

fn stream(id: u64) -> Option<Arc<Stream>> {
    if id == 0 || id > i64::MAX as u64 {
        return None;
    }
    registry()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .entries
        .get(&id)
        .cloned()
}

#[unsafe(no_mangle)]
pub extern "C" fn sx_sse_create() -> u64 {
    catch_unwind(|| {
        let mut registry = registry()
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
        registry.entries.insert(
            id,
            Arc::new(Stream {
                cancelled: AtomicBool::new(false),
                state: Mutex::new(State::default()),
            }),
        );
        id
    })
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "C" fn sx_sse_cancel(id: u64) {
    let _ = catch_unwind(|| {
        if let Some(stream) = stream(id) {
            // Never wait for the parser's state mutex when cancellation races with feed.
            stream.cancelled.store(true, Ordering::Release);
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn sx_sse_release(id: u64) {
    let _ = catch_unwind(|| {
        if let Some(stream) = registry()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .entries
            .remove(&id)
        {
            stream.cancelled.store(true, Ordering::Release);
        }
    });
}

/// Mode 0 consumes socket bytes, mode 1 consumes EOF, and mode 2 resumes a single line whose
/// malformed UTF-8 was decoded by Kotlin with the platform's existing readUtf8 replacement rules.
/// The returned header records exactly how many input bytes were consumed. Unconsumed bytes stay
/// in the caller's current read buffer; there is no accumulating output queue or batching delay.
///
/// # Safety
/// `output` must point to writable aligned SearchBuffer storage. A non-empty input must contain
/// `length` readable immutable bytes for the duration of this synchronous call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_sse_feed(
    id: u64,
    input: *const u8,
    length: usize,
    mode: u8,
    output: *mut SearchBuffer,
) -> i32 {
    if output.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    // SAFETY: storage is writable by the ABI contract.
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        })
    };
    let bound = if mode == 2 {
        MAX_LINE_BYTES * 3
    } else {
        READ_BYTES
    };
    if mode > 2 || length > bound || (mode == 1 && length != 0) || (length > 0 && input.is_null()) {
        return STATUS_REQUEST_ERROR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let stream = stream(id).ok_or(STATUS_REQUEST_ERROR)?;
        check_cancel(&stream.cancelled)?;
        // SAFETY: non-empty input was checked; an empty slice never dereferences input.
        let bytes = if length == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(input, length) }
        };
        let mut state = stream
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let bytes = state.feed(bytes, mode, &stream.cancelled)?;
        check_cancel(&stream.cancelled)?;
        let owned = bytes.into_boxed_slice();
        let len = owned.len();
        let data = Box::into_raw(owned) as *mut u8;
        // SAFETY: the output storage is still valid for this call.
        unsafe { output.write(SearchBuffer { data, len }) };
        Ok(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

fn check_cancel(cancelled: &AtomicBool) -> Result<(), i32> {
    if cancelled.load(Ordering::Acquire) {
        Err(STATUS_CANCELLED)
    } else {
        Ok(())
    }
}

#[derive(Default)]
struct Frame {
    depth: i32,
    in_string: bool,
    escaped: bool,
}

impl Frame {
    fn feed(&mut self, data: &str, cancelled: &AtomicBool) -> Result<bool, i32> {
        for (index, byte) in data.bytes().enumerate() {
            if index % 1024 == 0 {
                check_cancel(cancelled)?;
            }
            if self.escaped {
                self.escaped = false;
            } else if byte == b'\\' && self.in_string {
                self.escaped = true;
            } else if byte == b'"' {
                self.in_string = !self.in_string;
            } else if !self.in_string {
                match byte {
                    b'{' | b'[' => self.depth += 1,
                    b'}' | b']' => self.depth -= 1,
                    _ => {}
                }
            }
        }
        Ok(self.depth == 0 && !self.in_string)
    }
}

#[derive(Default)]
struct State {
    line: Vec<u8>,
    pending: String,
    pending_units: usize,
    frame: Frame,
    stopped: u32,
    normalizing: bool,
    normalization_at_eof: bool,
}

struct Event {
    kind: u32,
    body: Vec<u8>,
}

impl State {
    fn feed(&mut self, bytes: &[u8], mode: u8, cancelled: &AtomicBool) -> Result<Vec<u8>, i32> {
        if self.stopped != 0 {
            return Ok(response(0, self.stopped, None));
        }
        if self.normalizing {
            if mode != 2 {
                return Err(STATUS_REQUEST_ERROR);
            }
            let line = str::from_utf8(bytes).map_err(|_| STATUS_REQUEST_ERROR)?;
            self.normalizing = false;
            let mut event = self.accept_line(line, cancelled)?;
            if self.normalization_at_eof && self.stopped == 0 {
                self.stopped = FLAG_EOF;
                if event.is_none() {
                    event = self.flush(cancelled)?;
                }
            }
            self.normalization_at_eof = false;
            return Ok(response(bytes.len(), self.stopped, event));
        }
        if mode == 2 {
            return Err(STATUS_REQUEST_ERROR);
        }
        if mode == 1 {
            let mut event = if self.line.is_empty() {
                None
            } else {
                self.accept_bytes(false, cancelled)?
            };
            if self.normalizing {
                self.normalization_at_eof = true;
            } else if self.stopped == 0 {
                self.stopped = FLAG_EOF;
                if event.is_none() {
                    event = self.flush(cancelled)?;
                }
            }
            return Ok(response(0, self.stopped, event));
        }
        for (index, byte) in bytes.iter().copied().enumerate() {
            if index % 1024 == 0 {
                check_cancel(cancelled)?;
            }
            if byte == b'\n' {
                let event = self.accept_bytes(true, cancelled)?;
                if event.is_some() || self.stopped != 0 {
                    return Ok(response(index + 1, self.stopped, event));
                }
            } else if self.line.len() == MAX_LINE_BYTES {
                return Ok(response(index + 1, 0, Some(self.error(1))));
            } else {
                self.line.push(byte);
            }
        }
        Ok(response(bytes.len(), 0, None))
    }

    fn accept_bytes(
        &mut self,
        terminated: bool,
        cancelled: &AtomicBool,
    ) -> Result<Option<Event>, i32> {
        if terminated && self.line.last() == Some(&b'\r') {
            self.line.pop();
        }
        let bytes = std::mem::take(&mut self.line);
        // Non-data fields are ignored by the existing reader even when they contain invalid UTF-8.
        if !bytes.is_empty() && !bytes.starts_with(b"data:") {
            return Ok(None);
        }
        match str::from_utf8(&bytes) {
            Ok(line) => self.accept_line(line, cancelled),
            Err(_) => {
                self.normalizing = true;
                Ok(Some(Event {
                    kind: KIND_NORMALIZE_LINE,
                    body: bytes,
                }))
            }
        }
    }

    fn accept_line(&mut self, line: &str, cancelled: &AtomicBool) -> Result<Option<Event>, i32> {
        if line.is_empty() {
            return self.flush(cancelled);
        }
        let Some(data) = line.strip_prefix("data:") else {
            return Ok(None);
        };
        let data = data.strip_prefix(' ').unwrap_or(data);
        if data.is_empty() {
            return Ok(None);
        }
        let units = data.encode_utf16().count();
        if self.pending_units + units + usize::from(!self.pending.is_empty()) > MAX_FRAME_UNITS {
            return Ok(Some(self.error(2)));
        }
        if data.trim_matches(kotlin_whitespace) == "[DONE]" {
            self.stopped = FLAG_DONE;
            return self.flush(cancelled);
        }
        if self.pending.is_empty() {
            // Deliver complete single-line objects immediately, without waiting for a blank line.
            if let Some(tree) = decode_object(data, cancelled)? {
                return Ok(Some(Event {
                    kind: KIND_TREE,
                    body: tree,
                }));
            }
        }
        if !self.pending.is_empty() {
            self.pending.push('\n');
            self.pending_units += 1;
        }
        self.pending.push_str(data);
        self.pending_units += units;
        if self.frame.feed(data, cancelled)? {
            self.flush(cancelled)
        } else {
            Ok(None)
        }
    }

    fn flush(&mut self, cancelled: &AtomicBool) -> Result<Option<Event>, i32> {
        if self.pending.is_empty() {
            return Ok(None);
        }
        let payload = std::mem::take(&mut self.pending);
        self.pending_units = 0;
        self.frame = Frame::default();
        if let Some(tree) = decode_object(&payload, cancelled)? {
            Ok(Some(Event {
                kind: KIND_TREE,
                body: tree,
            }))
        } else {
            let mut body = Vec::with_capacity(4 + payload.len() * 2);
            put_string(&mut body, &payload);
            Ok(Some(Event {
                kind: KIND_COMPATIBILITY,
                body,
            }))
        }
    }

    fn error(&mut self, code: u32) -> Event {
        // Error is terminal for this attempt. The caller receives previously returned events first.
        self.line.clear();
        self.pending.clear();
        self.pending_units = 0;
        self.frame = Frame::default();
        Event {
            kind: KIND_ERROR,
            body: code.to_le_bytes().to_vec(),
        }
    }
}

fn kotlin_whitespace(ch: char) -> bool {
    matches!(ch, '\u{0009}'..='\u{000d}' | '\u{001c}'..='\u{0020}' | '\u{00a0}' |
        '\u{1680}' | '\u{2000}'..='\u{200a}' | '\u{2028}' | '\u{2029}' | '\u{202f}' |
        '\u{205f}' | '\u{3000}')
}

fn response(consumed: usize, flags: u32, event: Option<Event>) -> Vec<u8> {
    let event = event.unwrap_or(Event {
        kind: KIND_NONE,
        body: Vec::new(),
    });
    let mut output = Vec::with_capacity(20 + event.body.len());
    for value in [
        MAGIC,
        consumed as u32,
        flags,
        event.kind,
        event.body.len() as u32,
    ] {
        output.extend_from_slice(&value.to_le_bytes());
    }
    output.extend_from_slice(&event.body);
    output
}

fn put_string(output: &mut Vec<u8>, text: &str) {
    output.extend_from_slice(&(text.encode_utf16().count() as u32).to_le_bytes());
    for unit in text.encode_utf16() {
        output.extend_from_slice(&unit.to_le_bytes());
    }
}

/// serde's arbitrary_precision still normalizes -0 and exponent spelling. Preserve original
/// number tokens alongside the ordered Value tree. Duplicate keys or reserved serde keys use the
/// per-frame Kotlin compatibility parser, since they can change Value traversal/token alignment.
fn number_tokens<'a>(
    payload: &'a str,
    cancelled: &AtomicBool,
) -> Result<Option<Vec<&'a str>>, i32> {
    struct Context<'a> {
        keys: Option<HashSet<Cow<'a, str>>>,
        expecting_key: bool,
    }
    let bytes = payload.as_bytes();
    let mut contexts: Vec<Context<'a>> = Vec::new();
    let mut numbers = Vec::new();
    let mut nodes = 0usize;
    let mut index = 0;
    while index < bytes.len() {
        if index % 1024 == 0 {
            check_cancel(cancelled)?;
        }
        match bytes[index] {
            b'{' | b'[' => {
                let object = bytes[index] == b'{';
                if contexts.len() >= MAX_DEPTH {
                    return Ok(None);
                }
                contexts.push(Context {
                    keys: object.then(HashSet::new),
                    expecting_key: object,
                });
                nodes += 1;
                index += 1;
            }
            b'}' | b']' => {
                contexts.pop();
                index += 1;
            }
            b',' => {
                if let Some(context) = contexts.last_mut() {
                    context.expecting_key = context.keys.is_some();
                }
                index += 1;
            }
            b':' => {
                if let Some(context) = contexts.last_mut() {
                    context.expecting_key = false;
                }
                index += 1;
            }
            b'"' => {
                let start = index;
                let mut escaped = false;
                let mut has_escape = false;
                index += 1;
                while index < bytes.len() {
                    if index % 1024 == 0 {
                        check_cancel(cancelled)?;
                    }
                    let byte = bytes[index];
                    index += 1;
                    if escaped {
                        escaped = false;
                    } else if byte == b'\\' {
                        escaped = true;
                        has_escape = true;
                    } else if byte == b'"' {
                        break;
                    }
                }
                if index > bytes.len() || bytes.get(index - 1) != Some(&b'"') {
                    return Ok(None);
                }
                if let Some(context) = contexts.last_mut().filter(|context| context.expecting_key) {
                    let key = if has_escape {
                        let Ok(key) = serde_json::from_str::<String>(&payload[start..index]) else {
                            return Ok(None);
                        };
                        Cow::Owned(key)
                    } else {
                        Cow::Borrowed(&payload[start + 1..index - 1])
                    };
                    if key.starts_with("$serde_json::private::")
                        || !context.keys.as_mut().is_some_and(|keys| keys.insert(key))
                    {
                        return Ok(None);
                    }
                }
                nodes += 1;
            }
            b'-' | b'0'..=b'9' => {
                let start = index;
                index += 1;
                while index < bytes.len()
                    && matches!(bytes[index], b'0'..=b'9' | b'e' | b'E' | b'+' | b'-' | b'.')
                {
                    if index % 1024 == 0 {
                        check_cancel(cancelled)?;
                    }
                    index += 1;
                }
                numbers.push(&payload[start..index]);
                nodes += 1;
            }
            b't' | b'f' | b'n' => {
                nodes += 1;
                while index < bytes.len() && bytes[index].is_ascii_alphabetic() {
                    index += 1;
                }
            }
            _ => {
                index += 1;
            }
        }
        if nodes > MAX_NODES {
            return Ok(None);
        }
    }
    Ok(Some(numbers))
}

pub(super) fn decode_object(payload: &str, cancelled: &AtomicBool) -> Result<Option<Vec<u8>>, i32> {
    decode_value(payload, cancelled, true)
}

pub(super) fn decode_value(
    payload: &str,
    cancelled: &AtomicBool,
    object_only: bool,
) -> Result<Option<Vec<u8>>, i32> {
    let Some(tokens) = number_tokens(payload, cancelled)? else {
        return Ok(None);
    };
    let Ok(value) = serde_json::from_str::<Value>(payload) else {
        return Ok(None);
    };
    if object_only && !value.is_object() {
        return Ok(None);
    }
    check_cancel(cancelled)?;
    let mut encoder = Encoder {
        output: Vec::new(),
        tokens: tokens.into_iter(),
        nodes: 0,
        cancelled,
    };
    if !encoder.value(&value, 0)? || encoder.tokens.next().is_some() {
        return Ok(None);
    }
    Ok(Some(encoder.output))
}

struct Encoder<'a> {
    output: Vec<u8>,
    tokens: std::vec::IntoIter<&'a str>,
    nodes: usize,
    cancelled: &'a AtomicBool,
}

impl Encoder<'_> {
    fn value(&mut self, value: &Value, depth: usize) -> Result<bool, i32> {
        check_cancel(self.cancelled)?;
        self.nodes += 1;
        if depth > MAX_DEPTH || self.nodes > MAX_NODES || self.output.len() >= MAX_TREE_BYTES {
            return Ok(false);
        }
        match value {
            Value::Null => self.output.push(0),
            Value::Bool(false) => self.output.push(1),
            Value::Bool(true) => self.output.push(2),
            Value::String(text) => {
                self.output.push(3);
                if !self.string(text)? {
                    return Ok(false);
                }
            }
            Value::Number(_) => {
                let Some(raw) = self.tokens.next() else {
                    return Ok(false);
                };
                self.output.push(4);
                if !self.string(raw)? {
                    return Ok(false);
                }
            }
            Value::Array(items) => {
                self.output.push(5);
                self.output
                    .extend_from_slice(&(items.len() as u32).to_le_bytes());
                for item in items {
                    if !self.value(item, depth + 1)? {
                        return Ok(false);
                    }
                }
            }
            Value::Object(items) => {
                self.output.push(6);
                self.output
                    .extend_from_slice(&(items.len() as u32).to_le_bytes());
                for (key, item) in items {
                    if !self.string(key)? || !self.value(item, depth + 1)? {
                        return Ok(false);
                    }
                }
            }
        }
        Ok(self.output.len() <= MAX_TREE_BYTES)
    }

    fn string(&mut self, text: &str) -> Result<bool, i32> {
        let units = text.encode_utf16().count();
        if self.output.len() + 4 + units * 2 > MAX_TREE_BYTES {
            return Ok(false);
        }
        self.output.extend_from_slice(&(units as u32).to_le_bytes());
        for (index, unit) in text.encode_utf16().enumerate() {
            if index % 1024 == 0 {
                check_cancel(self.cancelled)?;
            }
            self.output.extend_from_slice(&unit.to_le_bytes());
        }
        Ok(true)
    }
}
