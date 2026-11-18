//! Stateful SSE byte framing and JSON decoding. One completed event is returned per call so an
//! error in later buffered bytes never discards a delta that the caller has already received.
//! Common text and reasoning events cross the bridge as strings; other events keep their tree.

use crate::{STATUS_CANCELLED, STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR};
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
const HEADER_BYTES: usize = 20;
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
const KIND_DELTA: u32 = 5;

#[repr(C)]
pub struct SseBuffer {
    pub data: *mut u8,
    pub len: usize,
    pub capacity: usize,
}

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
pub extern "C" fn sx_sse_create(protocol: u8) -> u64 {
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
                state: Mutex::new(State {
                    protocol,
                    ..State::default()
                }),
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
/// `output` must point to writable aligned SseBuffer storage. A non-empty input must contain
/// `length` readable immutable bytes for the duration of this synchronous call.
/// Release a returned buffer exactly once with `sx_sse_free`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_sse_feed(
    id: u64,
    input: *const u8,
    length: usize,
    mode: u8,
    output: *mut SseBuffer,
) -> i32 {
    if output.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    // SAFETY: storage is writable by the ABI contract.
    unsafe {
        output.write(SseBuffer {
            data: ptr::null_mut(),
            len: 0,
            capacity: 0,
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
        let mut bytes = state.feed(bytes, mode, &stream.cancelled)?;
        check_cancel(&stream.cancelled)?;
        let len = bytes.len();
        let capacity = bytes.capacity();
        let data = bytes.as_mut_ptr();
        std::mem::forget(bytes);
        // SAFETY: the output storage is still valid for this call.
        unsafe {
            output.write(SseBuffer {
                data,
                len,
                capacity,
            })
        };
        Ok(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

/// Release an SSE response without shrinking its original allocation first.
///
/// # Safety
/// All three values must be the unchanged result of `sx_sse_feed`, released exactly once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_sse_free(data: *mut u8, len: usize, capacity: usize) {
    if data.is_null() {
        return;
    }
    // SAFETY: these are the original Vec pointer, initialized length and allocation capacity.
    unsafe { drop(Vec::from_raw_parts(data, len, capacity)) };
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
    protocol: u8,
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
        let mut bytes = std::mem::take(&mut self.line);
        // Non-data fields are ignored by the existing reader even when they contain invalid UTF-8.
        if !bytes.is_empty() && !bytes.starts_with(b"data:") {
            bytes.clear();
            self.line = bytes;
            return Ok(None);
        }
        match str::from_utf8(&bytes) {
            Ok(line) => {
                let event = self.accept_line(line, cancelled);
                bytes.clear();
                self.line = bytes;
                event
            }
            Err(_) => {
                self.normalizing = true;
                let mut body = response_buffer(bytes.len());
                body.extend_from_slice(&bytes);
                Ok(Some(Event {
                    kind: KIND_NORMALIZE_LINE,
                    body,
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
            if let Some(event) = decode_event(data, cancelled, self.protocol)? {
                return Ok(Some(event));
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
        let units = self.pending_units;
        self.pending_units = 0;
        self.frame = Frame::default();
        if let Some(event) = decode_event(&payload, cancelled, self.protocol)? {
            Ok(Some(event))
        } else {
            let mut body = response_buffer(4 + units * 2);
            put_string(&mut body, &payload, units);
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
        let mut body = response_buffer(4);
        body.extend_from_slice(&code.to_le_bytes());
        Event {
            kind: KIND_ERROR,
            body,
        }
    }
}

fn kotlin_whitespace(ch: char) -> bool {
    matches!(ch, '\u{0009}'..='\u{000d}' | '\u{001c}'..='\u{0020}' | '\u{00a0}' |
        '\u{1680}' | '\u{2000}'..='\u{200a}' | '\u{2028}' | '\u{2029}' | '\u{202f}' |
        '\u{205f}' | '\u{3000}')
}

fn response_buffer(body_bytes: usize) -> Vec<u8> {
    let mut output = Vec::with_capacity(HEADER_BYTES + body_bytes);
    output.resize(HEADER_BYTES, 0);
    output
}

fn response(consumed: usize, flags: u32, event: Option<Event>) -> Vec<u8> {
    let (kind, mut body) = match event {
        Some(Event { kind, body }) => (kind, body),
        None => (KIND_NONE, Vec::with_capacity(HEADER_BYTES)),
    };
    let body_bytes = if body.is_empty() {
        0
    } else {
        body.len() - HEADER_BYTES
    };
    let header = [MAGIC, consumed as u32, flags, kind, body_bytes as u32];
    if body.is_empty() {
        for value in header {
            body.extend_from_slice(&value.to_le_bytes());
        }
    } else {
        for (index, value) in header.into_iter().enumerate() {
            body[index * 4..index * 4 + 4].copy_from_slice(&value.to_le_bytes());
        }
    }
    body
}

fn put_string(output: &mut Vec<u8>, text: &str, units: usize) {
    output.extend_from_slice(&(units as u32).to_le_bytes());
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

fn compact_delta(chunk: &Value, protocol: u8) -> Option<(&str, &str)> {
    // Preserve the Kotlin error path even for an explicit JSON null.
    if chunk.get("error").is_some() {
        return None;
    }
    match protocol {
        1 => {
            if chunk.get("usage").is_some()
                || chunk.get("reasoning_content").is_some()
                || chunk.get("reasoning").is_some()
            {
                return None;
            }
            let choice = chunk.get("choices")?.as_array()?.first()?.as_object()?;
            if choice
                .get("finish_reason")
                .is_some_and(|value| !value.is_null())
                || ["message", "reasoning_content", "reasoning"]
                    .iter()
                    .any(|key| choice.contains_key(*key))
            {
                return None;
            }
            let delta = choice.get("delta")?.as_object()?;
            if delta.contains_key("tool_calls") {
                return None;
            }
            let string = |key| match delta.get(key) {
                None | Some(Value::Null) => Some(""),
                Some(value) => value.as_str(),
            };
            let content = string("content")?;
            let primary = string("reasoning_content")?;
            let secondary = string("reasoning")?;
            Some((
                content,
                if primary.trim_matches(kotlin_whitespace).is_empty() {
                    secondary
                } else {
                    primary
                },
            ))
        }
        2 if chunk.get("type")?.as_str()? == "content_block_delta" => {
            let delta = chunk.get("delta")?;
            match delta.get("type")?.as_str()? {
                "text_delta" => Some((delta.get("text")?.as_str()?, "")),
                "thinking_delta" => Some(("", delta.get("thinking")?.as_str()?)),
                _ => None,
            }
        }
        3 => match chunk.get("type")?.as_str()? {
            "response.output_text.delta" => Some((chunk.get("delta")?.as_str()?, "")),
            "response.reasoning_summary_text.delta" | "response.reasoning_text.delta" => {
                Some(("", chunk.get("delta")?.as_str()?))
            }
            _ => None,
        },
        _ => None,
    }
}

fn decode_event(payload: &str, cancelled: &AtomicBool, protocol: u8) -> Result<Option<Event>, i32> {
    let Some((value, tokens)) = parse_json(payload, cancelled)? else {
        return Ok(None);
    };
    if !value.is_object() {
        return Ok(None);
    }
    if let Some((text, reasoning)) = compact_delta(&value, protocol) {
        let text_units = text.encode_utf16().count();
        let reasoning_units = reasoning.encode_utf16().count();
        let mut body = response_buffer(8 + 2 * (text_units + reasoning_units));
        put_string(&mut body, text, text_units);
        put_string(&mut body, reasoning, reasoning_units);
        return Ok(Some(Event {
            kind: KIND_DELTA,
            body,
        }));
    }
    Ok(
        encode_tree(&value, tokens, cancelled, true)?.map(|body| Event {
            kind: KIND_TREE,
            body,
        }),
    )
}

#[cfg(test)]
mod delta_tests {
    use super::*;

    #[test]
    fn projects_text_and_reasoning_through_the_stream_wire() {
        let cases = [
            (
                1,
                r#"{"choices":[{"finish_reason":null,"delta":{"content":"\n🙂","reasoning_content":" ","reasoning":"想"}}]}"#,
                "\n🙂",
                "想",
            ),
            (
                2,
                r#"{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"想"}}"#,
                "",
                "想",
            ),
            (
                3,
                r#"{"type":"response.output_text.delta","delta":"\n🙂"}"#,
                "\n🙂",
                "",
            ),
        ];
        for (protocol, json, text, reasoning) in cases {
            let mut state = State {
                protocol,
                ..State::default()
            };
            let frame = format!("data: {json}\n\n");
            let output = state
                .feed(frame.as_bytes(), 0, &AtomicBool::new(false))
                .unwrap();
            let mut body = response_buffer(32);
            for piece in [text, reasoning] {
                put_string(&mut body, piece, piece.encode_utf16().count());
            }
            assert_eq!(&output[12..16], &KIND_DELTA.to_le_bytes());
            assert_eq!(&output[HEADER_BYTES..], &body[HEADER_BYTES..]);
        }
    }

    #[test]
    fn errors_and_non_delta_fields_keep_their_tree() {
        for json in [
            r#"{"error":null,"choices":[{"delta":{"content":"a"}}]}"#,
            r#"{"choices":[{"finish_reason":"stop","delta":{"content":"a"}}]}"#,
            r#"{"choices":[{"delta":{"content":"a","tool_calls":[]}}]}"#,
        ] {
            assert_eq!(
                decode_event(json, &AtomicBool::new(false), 1)
                    .unwrap()
                    .unwrap()
                    .kind,
                KIND_TREE
            );
        }
    }
}

pub(super) fn decode_value(
    payload: &str,
    cancelled: &AtomicBool,
    object_only: bool,
) -> Result<Option<Vec<u8>>, i32> {
    let Some((value, tokens)) = parse_json(payload, cancelled)? else {
        return Ok(None);
    };
    if object_only && !value.is_object() {
        return Ok(None);
    }
    encode_tree(&value, tokens, cancelled, false)
}

fn parse_json<'a>(
    payload: &'a str,
    cancelled: &AtomicBool,
) -> Result<Option<(Value, Vec<&'a str>)>, i32> {
    let Some(tokens) = number_tokens(payload, cancelled)? else {
        return Ok(None);
    };
    let Ok(value) = serde_json::from_str::<Value>(payload) else {
        return Ok(None);
    };
    check_cancel(cancelled)?;
    Ok(Some((value, tokens)))
}

fn encode_tree(
    value: &Value,
    tokens: Vec<&str>,
    cancelled: &AtomicBool,
    framed: bool,
) -> Result<Option<Vec<u8>>, i32> {
    let mut encoder = Encoder {
        output: if framed {
            // Start geometric growth at a power of two after reserving the wire header.
            response_buffer(HEADER_BYTES.next_power_of_two() - HEADER_BYTES)
        } else {
            Vec::new()
        },
        max_bytes: MAX_TREE_BYTES + if framed { HEADER_BYTES } else { 0 },
        tokens: tokens.into_iter(),
        nodes: 0,
        cancelled,
    };
    if !encoder.value(value, 0)? || encoder.tokens.next().is_some() {
        return Ok(None);
    }
    Ok(Some(encoder.output))
}

struct Encoder<'a> {
    output: Vec<u8>,
    max_bytes: usize,
    tokens: std::vec::IntoIter<&'a str>,
    nodes: usize,
    cancelled: &'a AtomicBool,
}

impl Encoder<'_> {
    fn value(&mut self, value: &Value, depth: usize) -> Result<bool, i32> {
        check_cancel(self.cancelled)?;
        self.nodes += 1;
        if depth > MAX_DEPTH || self.nodes > MAX_NODES || self.output.len() >= self.max_bytes {
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
        Ok(self.output.len() <= self.max_bytes)
    }

    fn string(&mut self, text: &str) -> Result<bool, i32> {
        let units = text.encode_utf16().count();
        if self.output.len() + 4 + units * 2 > self.max_bytes {
            return Ok(false);
        }
        self.output.reserve(4 + units * 2);
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
