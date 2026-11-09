//! Read-only projection of a pinned, committed JSONL range. No transcript mutation or cache rules.

use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer, check_cancelled, task};
use serde::de::{self, DeserializeSeed, MapAccess, SeqAccess, Visitor};
use serde::{Deserialize, Deserializer};
use serde_json::value::RawValue;
use std::fmt;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::sync::atomic::AtomicBool;

const MAGIC: u32 = 0x3155_5853;
const STATUS_COMPATIBILITY: i32 = 4;
const READ_BYTES: usize = 64 * 1024;
const MAX_LINE_BYTES: usize = 32 * 1024 * 1024;
const MAX_RESPONSE_BYTES: usize = 16 * 1024 * 1024;
const MAX_MESSAGES: u32 = 1_000_000;
const MAX_NESTED_ITEMS: usize = 65_536;

/// # Safety
/// `fd` is a readable descriptor retained by Kotlin until this synchronous call exits. Rust borrows
/// it without closing it or changing its seek offset. `output` is writable and correctly aligned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_usage_project(
    id: u64,
    fd: i32,
    start: u64,
    end: u64,
    output: *mut SearchBuffer,
) -> i32 {
    if output.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    // SAFETY: the caller provides this writable output for the duration of the call.
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        })
    };
    if fd < 0 || start > end || end > i64::MAX as u64 {
        return STATUS_REQUEST_ERROR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let cancelled = task(id).ok_or(STATUS_REQUEST_ERROR)?;
        check_cancelled(&cancelled)?;
        // Match the existing Kotlin committed-reader size contract; unsupported spans fall back.
        if end - start > i32::MAX as u64 {
            return Err(STATUS_COMPATIBILITY);
        }
        let bytes = read_project(fd, start, end, &cancelled)?;
        check_cancelled(&cancelled)?;
        let owned = bytes.into_boxed_slice();
        let len = owned.len();
        let data = Box::into_raw(owned) as *mut u8;
        // SAFETY: the caller still owns this output and releases the result with sx_search_free.
        unsafe { output.write(SearchBuffer { data, len }) };
        Ok(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

#[cfg(unix)]
fn read_project(fd: i32, start: u64, end: u64, cancelled: &AtomicBool) -> Result<Vec<u8>, i32> {
    use std::fs::File;
    use std::io;
    use std::mem::ManuallyDrop;
    use std::os::fd::FromRawFd;
    use std::os::unix::fs::FileExt;

    // SAFETY: the JNI owner retains this descriptor. ManuallyDrop prevents Rust from closing it.
    let file = ManuallyDrop::new(unsafe { File::from_raw_fd(fd) });
    let meta = file.metadata().map_err(|_| STATUS_REQUEST_ERROR)?;
    if !meta.is_file() || meta.len() < end {
        return Err(STATUS_REQUEST_ERROR);
    }
    if start > 0 && start < end {
        let mut preceding = [0u8; 1];
        file.read_exact_at(&mut preceding, start - 1)
            .map_err(|_| STATUS_REQUEST_ERROR)?;
        if preceding[0] != b'\n' {
            // A supplied mid-line watermark is not native-compatible; Kotlin determines damage.
            return Err(STATUS_COMPATIBILITY);
        }
    }
    let mut response = ProjectionResponse::new();
    let mut line = Vec::new();
    let mut buffer = [0u8; READ_BYTES];
    let mut at = start;
    while at < end {
        check_cancelled(cancelled)?;
        let wanted = (end - at).min(READ_BYTES as u64) as usize;
        let count = match file.read_at(&mut buffer[..wanted], at) {
            Ok(0) => return Err(STATUS_REQUEST_ERROR),
            Ok(count) => count,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(_) => return Err(STATUS_REQUEST_ERROR),
        };
        let mut part = 0;
        for (index, &byte) in buffer[..count].iter().enumerate() {
            if byte == b'\n' {
                extend_line(&mut line, &buffer[part..index])?;
                response.line(&line, cancelled)?;
                line.clear();
                part = index + 1;
            }
        }
        extend_line(&mut line, &buffer[part..count])?;
        at += count as u64;
    }
    // Existing readEntries accepts a final nonempty committed line without a trailing LF.
    response.line(&line, cancelled)?;
    check_cancelled(cancelled)?;
    Ok(response.finish(start, end))
}

#[cfg(not(unix))]
fn read_project(_fd: i32, _start: u64, _end: u64, cancelled: &AtomicBool) -> Result<Vec<u8>, i32> {
    check_cancelled(cancelled)?;
    Err(STATUS_COMPATIBILITY)
}

fn extend_line(line: &mut Vec<u8>, bytes: &[u8]) -> Result<(), i32> {
    if line
        .len()
        .checked_add(bytes.len())
        .is_none_or(|size| size > MAX_LINE_BYTES)
    {
        return Err(STATUS_COMPATIBILITY);
    }
    line.extend_from_slice(bytes);
    Ok(())
}

struct ProjectionResponse {
    bytes: Vec<u8>,
    messages: u32,
    assistants: u32,
}

impl ProjectionResponse {
    fn new() -> Self {
        Self {
            bytes: vec![0; 20],
            messages: 0,
            assistants: 0,
        }
    }

    fn line(&mut self, bytes: &[u8], cancelled: &AtomicBool) -> Result<(), i32> {
        check_cancelled(cancelled)?;
        if bytes.is_empty() {
            return Ok(());
        }
        if self.messages >= MAX_MESSAGES {
            return Err(STATUS_COMPATIBILITY);
        }
        let text = std::str::from_utf8(bytes).map_err(|_| STATUS_COMPATIBILITY)?;
        // Kotlin's replacement UTF-8 decoder and permissive UTF-16 strings remain authoritative.
        // Conservatively fall back on surrogate escapes, even when a valid pair could be decoded.
        if contains_surrogate_escape(bytes, cancelled)? {
            return Err(STATUS_COMPATIBILITY);
        }
        let message: ProjectedMessage =
            serde_json::from_str(text).map_err(|_| STATUS_COMPATIBILITY)?;
        check_cancelled(cancelled)?;
        if message.role == "assistant" {
            let model = message.model.as_bytes();
            let added = 32usize
                .checked_add(model.len())
                .ok_or(STATUS_COMPATIBILITY)?;
            if self
                .bytes
                .len()
                .checked_add(added)
                .is_none_or(|size| size > MAX_RESPONSE_BYTES)
            {
                return Err(STATUS_COMPATIBILITY);
            }
            self.bytes.extend_from_slice(&self.messages.to_le_bytes());
            self.bytes
                .extend_from_slice(&message.timestamp.to_le_bytes());
            for value in [
                message.prompt,
                message.completion,
                message.reasoning,
                message.cached,
            ] {
                self.bytes.extend_from_slice(&value.to_le_bytes());
            }
            self.bytes
                .extend_from_slice(&(model.len() as u32).to_le_bytes());
            self.bytes.extend_from_slice(model);
            self.assistants += 1;
        }
        self.messages += 1;
        Ok(())
    }

    fn finish(mut self, start: u64, end: u64) -> Vec<u8> {
        self.bytes[0..4].copy_from_slice(&MAGIC.to_le_bytes());
        self.bytes[4..8].copy_from_slice(&self.messages.to_le_bytes());
        self.bytes[8..12].copy_from_slice(&self.assistants.to_le_bytes());
        self.bytes[12..20]
            .copy_from_slice(&(if self.messages == 0 { start } else { end }).to_le_bytes());
        self.bytes
    }
}

fn contains_surrogate_escape(bytes: &[u8], cancelled: &AtomicBool) -> Result<bool, i32> {
    for (index, part) in bytes.windows(6).enumerate() {
        if index % READ_BYTES == 0 {
            check_cancelled(cancelled)?;
        }
        if part[0] == b'\\'
            && part[1] == b'u'
            && part[2].eq_ignore_ascii_case(&b'd')
            && matches!(part[3].to_ascii_lowercase(), b'8'..=b'f')
            && part[4].is_ascii_hexdigit()
            && part[5].is_ascii_hexdigit()
        {
            return Ok(true);
        }
    }
    Ok(false)
}

struct ProjectedMessage {
    role: String,
    timestamp: i64,
    model: String,
    prompt: i32,
    completion: i32,
    reasoning: i32,
    cached: i32,
}

impl<'de> Deserialize<'de> for ProjectedMessage {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct MessageVisitor;
        impl<'de> Visitor<'de> for MessageVisitor {
            type Value = ProjectedMessage;
            fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
                formatter.write_str("a stored message")
            }
            fn visit_map<M: MapAccess<'de>>(self, mut map: M) -> Result<Self::Value, M::Error> {
                let mut id = false;
                let mut role = None;
                let mut timestamp = None;
                let mut model = String::new();
                let (mut prompt, mut completion, mut reasoning, mut cached) = (0, 0, 0, 0);
                // RawValue borrows each serialized value. Large text/image/tool bodies are scanned
                // for JSON syntax but never materialized as a Message or decoded String.
                while let Some((key, raw)) = map.next_entry::<String, &'de RawValue>()? {
                    let value = raw.get();
                    if !message_field_valid(&key, value) {
                        return Err(de::Error::custom("incompatible message field"));
                    }
                    let parse_error =
                        || <M::Error as de::Error>::custom("incompatible usage field");
                    match key.as_str() {
                        "id" => id = true,
                        "role" => {
                            role = Some(serde_json::from_str(value).map_err(|_| parse_error())?)
                        }
                        "timestamp" => {
                            timestamp =
                                Some(serde_json::from_str(value).map_err(|_| parse_error())?)
                        }
                        "model" => {
                            model = serde_json::from_str(value).map_err(|_| parse_error())?
                        }
                        "promptTokens" => {
                            prompt = serde_json::from_str(value).map_err(|_| parse_error())?
                        }
                        "completionTokens" => {
                            completion = serde_json::from_str(value).map_err(|_| parse_error())?
                        }
                        "reasoningTokens" => {
                            reasoning = serde_json::from_str(value).map_err(|_| parse_error())?
                        }
                        "cachedTokens" => {
                            cached = serde_json::from_str(value).map_err(|_| parse_error())?
                        }
                        _ => {}
                    }
                }
                if !id {
                    return Err(de::Error::custom("missing message id"));
                }
                // A missing timestamp invokes Kotlin's clock-valued default, so defer that record.
                Ok(ProjectedMessage {
                    role: role.ok_or_else(|| de::Error::custom("missing role"))?,
                    timestamp: timestamp.ok_or_else(|| de::Error::custom("missing timestamp"))?,
                    model,
                    prompt,
                    completion,
                    reasoning,
                    cached,
                })
            }
        }
        deserializer.deserialize_map(MessageVisitor)
    }
}

fn string(value: &str) -> bool {
    value.starts_with('"')
}
fn optional_string(value: &str) -> bool {
    value == "null" || string(value)
}
fn integer(value: &str) -> bool {
    serde_json::from_str::<i32>(value).is_ok()
}
fn long(value: &str) -> bool {
    serde_json::from_str::<i64>(value).is_ok()
}
fn boolean(value: &str) -> bool {
    matches!(value, "true" | "false")
}

fn message_field_valid(key: &str, value: &str) -> bool {
    match key {
        "id" | "role" | "text" | "model" => string(value),
        "reasoning" | "toolName" | "toolArgs" | "toolOutput" | "toolCallId" | "imageBase64"
        | "runId" | "traceNote" => optional_string(value),
        "toolOk" => value == "null" || boolean(value),
        "edited" | "steer" => boolean(value),
        "promptTokens" | "completionTokens" | "reasoningTokens" | "cachedTokens" => integer(value),
        "elapsedMs" | "firstTokenMs" | "timestamp" => long(value),
        "imagesBase64" => validate_sequence(value, SchemaKind::String),
        "toolCalls" => validate_sequence(value, SchemaKind::ToolCall),
        "fileActivities" => validate_sequence(value, SchemaKind::FileActivity),
        // ChatStore's ignoreUnknownKeys=true remains supported without interpreting future fields.
        _ => true,
    }
}

#[derive(Clone, Copy)]
enum SchemaKind {
    String,
    ToolCall,
    FileActivity,
}

struct SchemaSeed(SchemaKind);
impl<'de> DeserializeSeed<'de> for SchemaSeed {
    type Value = ();
    fn deserialize<D: Deserializer<'de>>(self, deserializer: D) -> Result<(), D::Error> {
        struct ObjectVisitor(SchemaKind);
        impl<'de> Visitor<'de> for ObjectVisitor {
            type Value = ();
            fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
                f.write_str("a stored metadata object")
            }
            fn visit_map<M: MapAccess<'de>>(self, mut map: M) -> Result<(), M::Error> {
                let mut required = 0u8;
                while let Some((key, raw)) = map.next_entry::<String, &'de RawValue>()? {
                    let value = raw.get();
                    let valid = match self.0 {
                        SchemaKind::ToolCall => match key.as_str() {
                            "id" => {
                                required |= 1;
                                string(value)
                            }
                            "name" => {
                                required |= 2;
                                string(value)
                            }
                            "arguments" => string(value),
                            _ => true,
                        },
                        SchemaKind::FileActivity => match key.as_str() {
                            "path" => {
                                required |= 1;
                                string(value)
                            }
                            "kind" => {
                                required |= 2;
                                serde_json::from_str::<String>(value).is_ok_and(|kind| {
                                    matches!(
                                        kind.as_str(),
                                        "CREATED"
                                            | "MODIFIED"
                                            | "DOWNLOADED"
                                            | "DELIVERED"
                                            | "DELETED"
                                            | "MOVED"
                                    )
                                })
                            }
                            "addedLines" | "removedLines" => value == "null" || integer(value),
                            "sizeBytes" => value == "null" || long(value),
                            "description" => string(value),
                            "previousPath" => optional_string(value),
                            _ => true,
                        },
                        SchemaKind::String => false,
                    };
                    if !valid {
                        return Err(de::Error::custom("incompatible nested field"));
                    }
                }
                if required != 3 {
                    return Err(de::Error::custom("missing nested field"));
                }
                Ok(())
            }
        }
        deserializer.deserialize_map(ObjectVisitor(self.0))
    }
}

fn validate_sequence(value: &str, kind: SchemaKind) -> bool {
    struct SequenceVisitor(SchemaKind);
    impl<'de> Visitor<'de> for SequenceVisitor {
        type Value = ();
        fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
            f.write_str("a stored list")
        }
        fn visit_seq<S: SeqAccess<'de>>(self, mut seq: S) -> Result<(), S::Error> {
            let mut count = 0;
            while let Some(raw) = seq.next_element::<&'de RawValue>()? {
                count += 1;
                if count > MAX_NESTED_ITEMS {
                    return Err(de::Error::custom("nested list budget"));
                }
                let valid = if matches!(self.0, SchemaKind::String) {
                    string(raw.get())
                } else {
                    let mut nested = serde_json::Deserializer::from_str(raw.get());
                    SchemaSeed(self.0).deserialize(&mut nested).is_ok() && nested.end().is_ok()
                };
                if !valid {
                    return Err(de::Error::custom("incompatible nested item"));
                }
            }
            Ok(())
        }
    }
    let mut deserializer = serde_json::Deserializer::from_str(value);
    deserializer.deserialize_seq(SequenceVisitor(kind)).is_ok() && deserializer.end().is_ok()
}
