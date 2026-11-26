//! Fast LLM endpoint payload helpers (model catalog, API error extraction, JSON object probe).
//! Keeps exact Kotlin formatting, whitespace, error, and compatibility fallback semantics.

use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer, java_whitespace};
use serde_json::Value;
use std::borrow::Cow;
use std::collections::HashSet;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;

const STATUS_COMPATIBILITY: i32 = 4;
const STATUS_EMPTY: i32 = 10;
const STATUS_MISSING_FIELD: i32 = 11;
const STATUS_NO_MODELS: i32 = 12;

const MAX_OBJECT_UNITS: usize = 1024 * 1024;
const MAX_PAYLOAD_UNITS: usize = 4 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 16 * 1024 * 1024;

/// Returns `1` for valid strict JSON objects, `0` when the input cannot be a JSON object,
/// and `STATUS_COMPATIBILITY` (`4`) when edge cases should fall back to Kotlin.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_llm_is_json_object(input: *const u16, len: usize) -> i32 {
    if len == 0 {
        return 0;
    }
    if input.is_null() || len > MAX_OBJECT_UNITS {
        return STATUS_COMPATIBILITY;
    }
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: JNI pins/copies the input UTF-16 slice for the synchronous call duration.
        let units = unsafe { slice::from_raw_parts(input, len) };
        let Ok(payload) = String::from_utf16(units) else {
            return STATUS_COMPATIBILITY;
        };
        let trimmed = payload.trim_matches(|ch| matches!(ch, ' ' | '\t' | '\n' | '\r'));
        if !trimmed.starts_with('{') || !trimmed.ends_with('}') {
            return 0;
        }
        match serde_json::from_str::<Value>(trimmed) {
            Ok(Value::Object(_)) => 1,
            Ok(_) => 0,
            Err(_) => STATUS_COMPATIBILITY,
        }
    }))
    .unwrap_or(STATUS_COMPATIBILITY)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_llm_parse_models(
    input: *const u16,
    len: usize,
    with_details: u8,
    output: *mut SearchBuffer,
) -> i32 {
    run_utf16_op(input, len, output, |units| {
        parse_models_units(units, with_details != 0)
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_llm_extract_error(
    input: *const u16,
    len: usize,
    output: *mut SearchBuffer,
) -> i32 {
    run_utf16_op(input, len, output, extract_error_units)
}

fn run_utf16_op<F>(input: *const u16, len: usize, output: *mut SearchBuffer, op: F) -> i32
where
    F: FnOnce(&[u16]) -> Result<Vec<u8>, i32>,
{
    if output.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    // SAFETY: Caller passed writable SearchBuffer storage for out-parameter initialization.
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        });
    }
    if len > MAX_PAYLOAD_UNITS || (len != 0 && input.is_null()) {
        return STATUS_COMPATIBILITY;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: Non-empty `len` is validated non-null above for this synchronous JNI call.
        let units = if len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(input, len) }
        };
        let bytes = op(units)?;
        if bytes.len() > MAX_OUTPUT_BYTES {
            return Err(STATUS_COMPATIBILITY);
        }
        let owned = bytes.into_boxed_slice();
        let len = owned.len();
        let data = Box::into_raw(owned) as *mut u8;
        // SAFETY: Output storage remains writable until return; freed later via `sx_search_free`.
        unsafe { output.write(SearchBuffer { data, len }) };
        Ok(())
    })) {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

fn trim_java_units(units: &[u16]) -> &[u16] {
    let start = units
        .iter()
        .position(|&unit| unit > 0x20)
        .unwrap_or(units.len());
    let end = units
        .iter()
        .rposition(|&unit| unit > 0x20)
        .map_or(start, |index| index + 1);
    &units[start..end]
}

fn is_kotlin_blank(text: &str) -> Result<bool, i32> {
    if text.is_ascii() {
        return Ok(text.bytes().all(|b| matches!(b, 0x09..=0x0d | 0x1c..=0x20)));
    }
    for ch in text.chars() {
        if ch == '\u{180e}' {
            return Err(STATUS_COMPATIBILITY);
        }
        if !java_whitespace(ch) && !ch.is_whitespace() {
            return Ok(false);
        }
    }
    Ok(true)
}

fn primitive_str(value: Option<&Value>) -> Result<Option<Cow<'_, str>>, i32> {
    match value {
        Some(Value::String(s)) => Ok(Some(Cow::Borrowed(s.as_str()))),
        Some(Value::Bool(true)) => Ok(Some(Cow::Borrowed("true"))),
        Some(Value::Bool(false)) => Ok(Some(Cow::Borrowed("false"))),
        Some(Value::Number(_)) => Err(STATUS_COMPATIBILITY),
        _ => Ok(None),
    }
}

fn non_blank_primitive(value: Option<&Value>) -> Result<Option<Cow<'_, str>>, i32> {
    let Some(text) = primitive_str(value)? else {
        return Ok(None);
    };
    if is_kotlin_blank(&text)? {
        Ok(None)
    } else {
        Ok(Some(text))
    }
}

fn positive_i32_from_value(value: Option<&Value>) -> Result<Option<i32>, i32> {
    match value {
        Some(Value::Number(num)) => {
            if let Some(int) = num.as_i64() {
                Ok((int > 0 && int <= i32::MAX as i64).then_some(int as i32))
            } else {
                Ok(None)
            }
        }
        Some(Value::String(text)) => {
            if !text.is_ascii() {
                return Err(STATUS_COMPATIBILITY);
            }
            Ok(text.parse::<i32>().ok().filter(|&v| v > 0))
        }
        _ => Ok(None),
    }
}

fn context_length_of(obj: &serde_json::Map<String, Value>) -> Result<Option<i32>, i32> {
    let top_provider_len = obj
        .get("top_provider")
        .and_then(Value::as_object)
        .and_then(|tp| tp.get("context_length"));
    let candidates = [
        obj.get("context_window"),
        obj.get("context_length"),
        top_provider_len,
        obj.get("max_context_length"),
        obj.get("max_model_len"),
        obj.get("max_input_tokens"),
    ];
    for candidate in candidates {
        if let Some(len) = positive_i32_from_value(candidate)? {
            return Ok(Some(len));
        }
    }
    Ok(None)
}

fn parse_models_units(units: &[u16], with_details: bool) -> Result<Vec<u8>, i32> {
    let trimmed_units = trim_java_units(units);
    if trimmed_units.is_empty() {
        return Err(STATUS_EMPTY);
    }
    let Ok(trimmed) = String::from_utf16(trimmed_units) else {
        return Err(STATUS_COMPATIBILITY);
    };
    if !matches!(trimmed.as_bytes().first(), Some(b'{' | b'[')) {
        return Err(STATUS_COMPATIBILITY);
    }
    let Ok(root) = serde_json::from_str::<Value>(&trimmed) else {
        return Err(STATUS_COMPATIBILITY);
    };
    let array = match &root {
        Value::Array(items) => items.as_slice(),
        Value::Object(map) => map
            .get("data")
            .and_then(Value::as_array)
            .or_else(|| map.get("models").and_then(Value::as_array))
            .or_else(|| map.get("result").and_then(Value::as_array))
            .map(Vec::as_slice)
            .ok_or(STATUS_MISSING_FIELD)?,
        _ => return Err(STATUS_COMPATIBILITY),
    };

    let mut seen = HashSet::with_capacity(array.len().min(512));
    let mut models: Vec<(String, i32)> = Vec::with_capacity(array.len().min(512));

    for item in array {
        match item {
            Value::Object(obj) => {
                let field = obj
                    .get("id")
                    .or_else(|| obj.get("name"))
                    .or_else(|| obj.get("model"));
                let Some(id) = non_blank_primitive(field)? else {
                    continue;
                };
                if seen.contains(id.as_ref()) {
                    continue;
                }
                let ctx = if with_details {
                    context_length_of(obj)?.unwrap_or(-1)
                } else {
                    -1
                };
                let owned = id.into_owned();
                seen.insert(owned.clone());
                models.push((owned, ctx));
            }
            primitive => {
                let Some(id) = non_blank_primitive(Some(primitive))? else {
                    continue;
                };
                if seen.contains(id.as_ref()) {
                    continue;
                }
                let owned = id.into_owned();
                seen.insert(owned.clone());
                models.push((owned, -1));
            }
        }
    }

    if models.is_empty() {
        return Err(STATUS_NO_MODELS);
    }

    let total_utf16: usize = models.iter().map(|(id, _)| id.encode_utf16().count()).sum();
    let byte_len = 4usize
        .checked_add(models.len().saturating_mul(8))
        .and_then(|n| n.checked_add(total_utf16.saturating_mul(2)))
        .ok_or(STATUS_COMPATIBILITY)?;
    if byte_len > MAX_OUTPUT_BYTES {
        return Err(STATUS_COMPATIBILITY);
    }

    let mut out = Vec::with_capacity(byte_len);
    out.extend_from_slice(&(models.len() as u32).to_le_bytes());
    for (id, ctx) in models {
        out.extend_from_slice(&ctx.to_le_bytes());
        let unit_count = id.encode_utf16().count() as u32;
        out.extend_from_slice(&unit_count.to_le_bytes());
        for unit in id.encode_utf16() {
            out.extend_from_slice(&unit.to_le_bytes());
        }
    }
    Ok(out)
}

fn extract_error_units(units: &[u16]) -> Result<Vec<u8>, i32> {
    let trimmed_units = trim_java_units(units);
    if trimmed_units.is_empty() {
        return Ok(Vec::new());
    }
    if let Ok(trimmed) = String::from_utf16(trimmed_units)
        && trimmed.as_bytes().first() == Some(&b'{')
        && let Ok(Value::Object(parsed)) = serde_json::from_str::<Value>(&trimmed)
    {
        let error = parsed.get("error");
        let message = match error {
            Some(Value::Object(envelope)) => non_blank_primitive(envelope.get("message"))?,
            other => non_blank_primitive(other)?,
        }
        .or(non_blank_primitive(parsed.get("message"))?)
        .or(non_blank_primitive(parsed.get("detail"))?)
        .or(non_blank_primitive(parsed.get("msg"))?);

        if let Some(message) = message {
            let mut out_units = Vec::with_capacity(384);
            for unit in message.encode_utf16().take(300) {
                out_units.extend_from_slice(&unit.to_le_bytes());
            }
            if let Some(Value::Object(envelope)) = error {
                if let Some(param) = non_blank_primitive(envelope.get("param"))? {
                    push_utf16_str(&mut out_units, "；param=");
                    push_utf16_str(&mut out_units, &param);
                }
                if let Some(code) = non_blank_primitive(envelope.get("code"))? {
                    push_utf16_str(&mut out_units, "；code=");
                    push_utf16_str(&mut out_units, &code);
                }
            }
            return Ok(out_units);
        }
    }

    let prefix = &trimmed_units[..trimmed_units.len().min(200)];
    let mut collapsed = Vec::with_capacity(prefix.len() * 2);
    let mut in_space = false;
    for &unit in prefix {
        if matches!(unit, 0x20 | 0x09..=0x0d) {
            if !in_space {
                collapsed.extend_from_slice(&0x0020u16.to_le_bytes());
                in_space = true;
            }
        } else {
            in_space = false;
            collapsed.extend_from_slice(&unit.to_le_bytes());
        }
    }
    Ok(collapsed)
}

fn push_utf16_str(out: &mut Vec<u8>, s: &str) {
    for unit in s.encode_utf16() {
        out.extend_from_slice(&unit.to_le_bytes());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn to_units(s: &str) -> Vec<u16> {
        s.encode_utf16().collect()
    }

    fn decode_models_blob(bytes: &[u8]) -> Vec<(String, i32)> {
        let count = u32::from_le_bytes(bytes[0..4].try_into().unwrap()) as usize;
        let mut at = 4usize;
        let mut out = Vec::with_capacity(count);
        for _ in 0..count {
            let ctx = i32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
            at += 4;
            let len = u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap()) as usize;
            at += 4;
            let units: Vec<u16> = (0..len)
                .map(|i| u16::from_le_bytes(bytes[at + i * 2..at + i * 2 + 2].try_into().unwrap()))
                .collect();
            at += len * 2;
            out.push((String::from_utf16(&units).unwrap(), ctx));
        }
        assert_eq!(at, bytes.len());
        out
    }

    fn decode_utf16(bytes: &[u8]) -> String {
        let units: Vec<u16> = bytes
            .chunks_exact(2)
            .map(|c| u16::from_le_bytes([c[0], c[1]]))
            .collect();
        String::from_utf16(&units).unwrap()
    }

    #[test]
    fn parses_model_details_and_dedups_matching_kotlin_rules() {
        let json = r#"{
            "data": [
                {"id": "gpt-4o", "context_window": 128000},
                {"id": "gpt-4o", "context_window": 64000},
                {"id": null, "name": "skip-null-id"},
                {"name": "claude-3", "top_provider": {"context_length": "200000"}},
                {"model": "float-ctx", "context_window": 8192.0, "max_input_tokens": 4096},
                {"id": "zero-ctx", "max_model_len": 0},
                "bare-model"
            ]
        }"#;
        let bytes = parse_models_units(&to_units(json), true).unwrap();
        let parsed = decode_models_blob(&bytes);
        assert_eq!(
            parsed,
            vec![
                ("gpt-4o".into(), 128000),
                ("claude-3".into(), 200000),
                ("float-ctx".into(), 4096),
                ("zero-ctx".into(), -1),
                ("bare-model".into(), -1),
            ]
        );
    }

    #[test]
    fn extracts_api_error_envelope_and_collapses_non_json_whitespace() {
        let json = r#"{"error":{"message":"Invalid request parameters","param":"reasoning_effort","code":"unsupported_parameter"}}"#;
        let msg = decode_utf16(&extract_error_units(&to_units(json)).unwrap());
        assert_eq!(
            msg,
            "Invalid request parameters；param=reasoning_effort；code=unsupported_parameter"
        );

        let raw = "  upstream\n\t error \u{00a0}  502   ";
        let plain = decode_utf16(&extract_error_units(&to_units(raw)).unwrap());
        assert_eq!(plain, "upstream error \u{00a0} 502");
    }

    #[test]
    fn checks_json_object_fast_and_safely() {
        let obj = to_units(r#"  {"path":"/a","offset":1}  "#);
        let arr = to_units("[1, 2]");
        let txt = to_units("hello");
        assert_eq!(unsafe { sx_llm_is_json_object(obj.as_ptr(), obj.len()) }, 1);
        assert_eq!(unsafe { sx_llm_is_json_object(arr.as_ptr(), arr.len()) }, 0);
        assert_eq!(unsafe { sx_llm_is_json_object(txt.as_ptr(), txt.len()) }, 0);
    }
}
