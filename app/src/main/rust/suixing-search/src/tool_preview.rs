//! Secret-safe single-line tool argument preview off the JVM heap.
use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer, java_whitespace};
use serde_json::Value;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;

const STATUS_EMPTY: i32 = 1;
const STATUS_COMPATIBILITY: i32 = 4;
const MAX_INPUT_UNITS: usize = 4 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 16 * 1024 * 1024;
const MAX_PREVIEW_UNITS: usize = 512;

fn is_valid_key_prefix(prefix: &str) -> bool {
    if prefix.is_empty() {
        return true;
    }
    let bytes = prefix.as_bytes();
    if !matches!(bytes[bytes.len() - 1], b'_' | b'-') {
        return false;
    }
    let mut expect_alnum = true;
    for &b in &bytes[..bytes.len() - 1] {
        if b.is_ascii_alphanumeric() {
            expect_alnum = false;
        } else if matches!(b, b'_' | b'-') {
            if expect_alnum {
                return false;
            }
            expect_alnum = true;
        } else {
            return false;
        }
    }
    !expect_alnum
}

fn is_secret_key(key: &str) -> bool {
    const SUFFIXES: &[&str] = &[
        "api_key",
        "api-key",
        "apikey",
        "access_token",
        "access-token",
        "accesstoken",
        "refresh_token",
        "refresh-token",
        "refreshtoken",
        "token",
        "password",
        "passwd",
        "secret",
        "authorization",
        "cookie",
        "credential",
    ];
    for suffix in SUFFIXES {
        if key.len() >= suffix.len() {
            let split = key.len() - suffix.len();
            if key.is_char_boundary(split)
                && key[split..].eq_ignore_ascii_case(suffix)
                && is_valid_key_prefix(&key[..split])
            {
                return true;
            }
        }
    }
    false
}

fn contains_unsupported_control(s: &str) -> bool {
    s.bytes()
        .any(|b| b < 0x20 && !matches!(b, b'\x08' | b'\t' | b'\n' | b'\r'))
}

fn validate_json_controls(value: &Value) -> bool {
    match value {
        Value::String(s) => !contains_unsupported_control(s),
        Value::Array(items) => items.iter().all(validate_json_controls),
        Value::Object(map) => map
            .iter()
            .all(|(k, v)| !contains_unsupported_control(k) && validate_json_controls(v)),
        _ => true,
    }
}

fn write_json_str(out: &mut String, s: &str) {
    out.push('"');
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\x08' => out.push_str("\\b"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            _ => out.push(ch),
        }
    }
    out.push('"');
}

fn write_masked_json(out: &mut String, value: &Value) {
    match value {
        Value::Null => out.push_str("null"),
        Value::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Value::Number(n) => out.push_str(&n.to_string()),
        Value::String(s) => write_json_str(out, s),
        Value::Array(items) => {
            out.push('[');
            for (idx, item) in items.iter().enumerate() {
                if idx > 0 {
                    out.push(',');
                }
                write_masked_json(out, item);
            }
            out.push(']');
        }
        Value::Object(map) => {
            out.push('{');
            for (idx, (k, v)) in map.iter().enumerate() {
                if idx > 0 {
                    out.push(',');
                }
                write_json_str(out, k);
                out.push(':');
                if is_secret_key(k) {
                    write_json_str(out, "•••");
                } else {
                    write_masked_json(out, v);
                }
            }
            out.push('}');
        }
    }
}

#[inline]
fn is_ascii_word(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

#[inline]
fn is_java_ws_byte(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\n' | 0x0B | 0x0C | b'\r')
}

fn contains_ascii_ci(haystack: &str, needle: &[u8]) -> bool {
    let bytes = haystack.as_bytes();
    if bytes.len() < needle.len() {
        return false;
    }
    bytes
        .windows(needle.len())
        .any(|win| win.eq_ignore_ascii_case(needle))
}

fn mask_url_credentials(input: &str) -> String {
    if !input.contains("://") {
        return input.to_owned();
    }
    let bytes = input.as_bytes();
    let mut out = String::with_capacity(input.len());
    let mut last = 0usize;
    let mut i = 0usize;
    while i < bytes.len() {
        let boundary = i == 0 || !is_ascii_word(bytes[i - 1]);
        if boundary && bytes[i].is_ascii_alphabetic() {
            let mut s = i + 1;
            while s < bytes.len()
                && (bytes[s].is_ascii_alphanumeric() || matches!(bytes[s], b'+' | b'.' | b'-'))
            {
                s += 1;
            }
            if s + 3 < bytes.len() && &bytes[s..s + 3] == b"://" {
                let cred_start = s + 3;
                let mut c = cred_start;
                while c < bytes.len()
                    && bytes[c] != b'/'
                    && !is_java_ws_byte(bytes[c])
                    && bytes[c] != b'@'
                {
                    c += 1;
                }
                if c > cred_start && c < bytes.len() && bytes[c] == b'@' {
                    out.push_str(&input[last..cred_start]);
                    out.push_str("•••@");
                    i = c + 1;
                    last = i;
                    continue;
                }
            }
        }
        i += 1;
    }
    if last == 0 {
        return input.to_owned();
    }
    out.push_str(&input[last..]);
    out
}

#[inline]
fn is_bearer_token_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'~' | b'+' | b'/' | b'=' | b'-')
}

fn mask_bearer(input: &str) -> String {
    if !contains_ascii_ci(input, b"bearer") && !contains_ascii_ci(input, b"basic") {
        return input.to_owned();
    }
    let bytes = input.as_bytes();
    let mut out = String::with_capacity(input.len());
    let mut last = 0usize;
    let mut i = 0usize;
    while i < bytes.len() {
        let boundary = i == 0 || !is_ascii_word(bytes[i - 1]);
        let kw_len = if boundary
            && bytes
                .get(i..i + 6)
                .is_some_and(|w| w.eq_ignore_ascii_case(b"bearer"))
        {
            6
        } else if boundary
            && bytes
                .get(i..i + 5)
                .is_some_and(|w| w.eq_ignore_ascii_case(b"basic"))
        {
            5
        } else {
            0
        };
        if kw_len > 0 {
            let mut p = i + kw_len;
            let ws_start = p;
            while p < bytes.len() && is_java_ws_byte(bytes[p]) {
                p += 1;
            }
            if p > ws_start {
                let tok_start = p;
                while p < bytes.len() && is_bearer_token_byte(bytes[p]) {
                    p += 1;
                }
                if p > tok_start {
                    out.push_str(&input[last..i + kw_len]);
                    out.push_str(" •••");
                    i = p;
                    last = i;
                    continue;
                }
            }
        }
        i += 1;
    }
    if last == 0 {
        return input.to_owned();
    }
    out.push_str(&input[last..]);
    out
}

fn ends_with_assignment_keyword(ident: &[u8]) -> bool {
    const KEYWORDS: &[&[u8]] = &[
        b"api_key",
        b"api-key",
        b"apikey",
        b"token",
        b"password",
        b"secret",
    ];
    KEYWORDS.iter().any(|kw| {
        ident.len() >= kw.len() && ident[ident.len() - kw.len()..].eq_ignore_ascii_case(kw)
    })
}

fn mask_assignment(input: &str) -> String {
    if (!input.contains('=') && !input.contains(':'))
        || (!contains_ascii_ci(input, b"api")
            && !contains_ascii_ci(input, b"token")
            && !contains_ascii_ci(input, b"password")
            && !contains_ascii_ci(input, b"secret"))
    {
        return input.to_owned();
    }
    let bytes = input.as_bytes();
    let mut out = String::with_capacity(input.len());
    let mut last = 0usize;
    let mut i = 0usize;
    while i < bytes.len() {
        if is_ascii_word(bytes[i]) || bytes[i] == b'-' {
            let start = i;
            while i < bytes.len() && (is_ascii_word(bytes[i]) || bytes[i] == b'-') {
                i += 1;
            }
            if ends_with_assignment_keyword(&bytes[start..i]) {
                let mut p = i;
                while p < bytes.len() && is_java_ws_byte(bytes[p]) {
                    p += 1;
                }
                if p < bytes.len() && matches!(bytes[p], b'=' | b':') {
                    p += 1;
                    while p < bytes.len() && is_java_ws_byte(bytes[p]) {
                        p += 1;
                    }
                    let prefix_end = p;
                    let val_end = if p < bytes.len() && bytes[p] == b'"' {
                        let q = p + 1;
                        let mut k = q;
                        while k < bytes.len() && bytes[k] != b'"' {
                            k += 1;
                        }
                        if k < bytes.len() { Some(k + 1) } else { None }
                    } else if p < bytes.len() && bytes[p] == b'\'' {
                        let q = p + 1;
                        let mut k = q;
                        while k < bytes.len() && bytes[k] != b'\'' {
                            k += 1;
                        }
                        if k < bytes.len() { Some(k + 1) } else { None }
                    } else {
                        None
                    };
                    let val_end = val_end.or_else(|| {
                        let mut k = p;
                        while k < bytes.len()
                            && !is_java_ws_byte(bytes[k])
                            && !matches!(bytes[k], b',' | b';' | b'&' | b'}')
                        {
                            k += 1;
                        }
                        (k > p).then_some(k)
                    });
                    if let Some(end) = val_end {
                        out.push_str(&input[last..prefix_end]);
                        out.push_str("•••");
                        i = end;
                        last = i;
                        continue;
                    }
                }
            }
            continue;
        }
        i += 1;
    }
    if last == 0 {
        return input.to_owned();
    }
    out.push_str(&input[last..]);
    out
}

fn trim_non_blank_first_line(text: &str) -> Result<Option<&str>, i32> {
    if text
        .chars()
        .any(|ch| ch as u32 > 0x7f && java_whitespace(ch))
    {
        return Err(STATUS_COMPATIBILITY);
    }
    for raw_line in text.split('\n') {
        let stripped_cr = raw_line.strip_suffix('\r').unwrap_or(raw_line);
        let trimmed = stripped_cr.trim_matches(java_whitespace);
        if !trimmed.is_empty() {
            return Ok(Some(trimmed));
        }
    }
    Ok(None)
}

pub(crate) fn format_preview(text: &str) -> Result<Option<Vec<u16>>, i32> {
    if text.chars().all(java_whitespace) {
        if text
            .chars()
            .any(|ch| ch as u32 > 0x7f && java_whitespace(ch))
        {
            return Err(STATUS_COMPATIBILITY);
        }
        return Ok(None);
    }
    let Ok(parsed) = serde_json::from_str::<Value>(text) else {
        return Err(STATUS_COMPATIBILITY);
    };
    if !validate_json_controls(&parsed) {
        return Err(STATUS_COMPATIBILITY);
    }
    let mut structured = String::with_capacity(text.len().min(4096));
    write_masked_json(&mut structured, &parsed);
    let url_masked = mask_url_credentials(&structured);
    let bearer_masked = mask_bearer(&url_masked);
    let assigned_masked = mask_assignment(&bearer_masked);
    let Some(line) = trim_non_blank_first_line(&assigned_masked)? else {
        return Ok(None);
    };
    if line == "{}" {
        return Ok(None);
    }
    let mut units: Vec<u16> = line.encode_utf16().take(MAX_PREVIEW_UNITS).collect();
    if units.is_empty() {
        return Ok(None);
    }
    units.shrink_to_fit();
    Ok(Some(units))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_tool_argument_preview(
    input: *const u16,
    len: usize,
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
    if len > MAX_INPUT_UNITS || (len != 0 && input.is_null()) {
        return STATUS_COMPATIBILITY;
    }
    if len == 0 {
        return STATUS_EMPTY;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        let units = unsafe { slice::from_raw_parts(input, len) };
        let Ok(text) = String::from_utf16(units) else {
            return Err(STATUS_COMPATIBILITY);
        };
        let Some(preview_units) = format_preview(&text)? else {
            return Ok(STATUS_EMPTY);
        };
        let byte_len = preview_units.len() * 2;
        if byte_len > MAX_OUTPUT_BYTES {
            return Err(STATUS_COMPATIBILITY);
        }
        let mut bytes = Vec::with_capacity(byte_len);
        for u in preview_units {
            bytes.extend_from_slice(&u.to_le_bytes());
        }
        let owned = bytes.into_boxed_slice();
        let len = owned.len();
        let data = Box::into_raw(owned) as *mut u8;
        unsafe {
            output.write(SearchBuffer { data, len });
        }
        Ok(STATUS_OK)
    })) {
        Ok(Ok(status)) => status,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn preview_str(raw: &str) -> Result<Option<String>, i32> {
        format_preview(raw).map(|opt| opt.map(|u| String::from_utf16_lossy(&u)))
    }

    #[test]
    fn masks_json_secrets_bearer_urls_and_keeps_ordinary_tokens() {
        let bash =
            r#"{"command":"curl -H 'Authorization: Bearer private-token' https://example.test"}"#;
        let out = preview_str(bash).unwrap().unwrap();
        assert!(!out.contains("private-token"));
        assert!(out.contains("example.test"));

        let nested = r#"{"headers":{"Authorization":"Bearer abc123"},"api_key":"secret-key","path":"/workspace/project"}"#;
        let out_nested = preview_str(nested).unwrap().unwrap();
        assert!(!out_nested.contains("abc123"));
        assert!(!out_nested.contains("secret-key"));
        assert!(out_nested.contains("/workspace/project"));

        let url_and_assign = r#"{"cmd":"curl 'https://alice:user-password@example.test/?token=token-value&x=1' API_KEY=secret-value"}"#;
        let out_url = preview_str(url_and_assign).unwrap().unwrap();
        for s in ["token-value", "secret-value", "alice", "user-password"] {
            assert!(
                !out_url.contains(s),
                "unexpected secret kept: {s} in {out_url}"
            );
        }
        assert!(out_url.contains("example.test"));

        let ordinary = r#"{"command":"ls -la","max_tokens":100}"#;
        assert_eq!(preview_str(ordinary).unwrap().unwrap(), ordinary);
        assert_eq!(preview_str("{}").unwrap(), None);
    }

    #[test]
    fn truncates_to_512_utf16_units_and_handles_assignment_edge_cases() {
        let long = format!(r#"{{"content":"{}"}}"#, "a".repeat(1000));
        let out = format_preview(&long).unwrap().unwrap();
        assert_eq!(out.len(), 512);

        let unquoted = r#"{"cmd":"export MY_TOKEN=topsecret && run"}"#;
        let out_unq = preview_str(unquoted).unwrap().unwrap();
        assert!(!out_unq.contains("topsecret"));
        assert!(out_unq.contains("MY_TOKEN=•••"));
    }
}
