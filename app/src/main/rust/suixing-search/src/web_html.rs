//! Bounded HTML-to-text stripper and entity decoder matching `WebTools.kt`.

use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer, java_whitespace};
use memchr::memchr;
use std::borrow::Cow;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;

const COMPATIBILITY: i32 = 4;
const MAX_INPUT_UNITS: usize = 4 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 16 * 1024 * 1024;

/// # Safety
/// `input` must point to `len` readable UTF-16 units and `output` must be writable for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_web_html_to_text(
    input: *const u16,
    len: usize,
    output: *mut SearchBuffer,
) -> i32 {
    run_utf16_op(input, len, output, html_to_text)
}

/// # Safety
/// `input` must point to `len` readable UTF-16 units and `output` must be writable for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_web_decode_entities(
    input: *const u16,
    len: usize,
    output: *mut SearchBuffer,
) -> i32 {
    run_utf16_op(input, len, output, decode_entities)
}

fn run_utf16_op(
    input: *const u16,
    len: usize,
    output: *mut SearchBuffer,
    op: fn(&str) -> Result<String, i32>,
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
        return STATUS_REQUEST_ERROR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let units = if len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(input, len) }
        };
        let utf8 = String::from_utf16(units).map_err(|_| COMPATIBILITY)?;
        let processed = op(&utf8)?;
        let out_bytes = encode_utf16_le(&processed)?;
        if out_bytes.is_empty() {
            return Ok(());
        }
        let owned = out_bytes.into_boxed_slice();
        let out_len = owned.len();
        let data = Box::into_raw(owned) as *mut u8;
        unsafe { output.write(SearchBuffer { data, len: out_len }) };
        Ok(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

pub(crate) fn encode_utf16_le(text: &str) -> Result<Vec<u8>, i32> {
    if text.is_empty() {
        return Ok(Vec::new());
    }
    if text.is_ascii() {
        let bytes = text.as_bytes();
        let out_len = bytes
            .len()
            .checked_mul(2)
            .filter(|&n| n <= MAX_OUTPUT_BYTES)
            .ok_or(COMPATIBILITY)?;
        let mut out = Vec::with_capacity(out_len);
        for &b in bytes {
            out.push(b);
            out.push(0);
        }
        return Ok(out);
    }
    let mut out = Vec::with_capacity((text.len() * 2).min(MAX_OUTPUT_BYTES));
    for unit in text.encode_utf16() {
        if out.len() + 2 > MAX_OUTPUT_BYTES {
            return Err(COMPATIBILITY);
        }
        out.extend_from_slice(&unit.to_le_bytes());
    }
    Ok(out)
}

pub fn html_to_text(html: &str) -> Result<String, i32> {
    if html.is_empty() {
        return Ok(String::new());
    }
    let bytes = html.as_bytes();
    if memchr(b'<', bytes).is_none() {
        if memchr(b'&', bytes).is_none() {
            return collapse_and_trim(html);
        }
        let decoded = decode_entities(html)?;
        return collapse_and_trim(&decoded);
    }

    let mut stage: Cow<'_, [u8]> = Cow::Borrowed(bytes);
    if let Some(next) = strip_paired_ci(stage.as_ref(), b"script") {
        stage = Cow::Owned(next);
    }
    if let Some(next) = strip_paired_ci(stage.as_ref(), b"style") {
        stage = Cow::Owned(next);
    }
    if let Some(next) = strip_paired_ci(stage.as_ref(), b"noscript") {
        stage = Cow::Owned(next);
    }
    if let Some(next) = strip_comments(stage.as_ref()) {
        stage = Cow::Owned(next);
    }

    let tags_stripped = strip_tags(stage.as_ref());
    let after_entities = if memchr(b'&', tags_stripped.as_bytes()).is_some() {
        decode_entities(&tags_stripped)?
    } else {
        tags_stripped
    };
    collapse_and_trim(&after_entities)
}

fn strip_paired_ci(bytes: &[u8], tag: &[u8]) -> Option<Vec<u8>> {
    let mut out: Option<Vec<u8>> = None;
    let mut copied = 0usize;
    let mut pos = 0usize;
    let open_prefix_len = 1 + tag.len();

    while pos + open_prefix_len < bytes.len() {
        let Some(rel) = memchr(b'<', &bytes[pos..]) else {
            break;
        };
        let open = pos + rel;
        if open + open_prefix_len < bytes.len()
            && bytes[open + 1..open + open_prefix_len].eq_ignore_ascii_case(tag)
        {
            let Some(gt_rel) = memchr(b'>', &bytes[open + open_prefix_len..]) else {
                break;
            };
            let after_open = open + open_prefix_len + gt_rel + 1;
            if let Some(close_end) = find_close_tag_ci(bytes, after_open, tag) {
                let buf = out.get_or_insert_with(|| Vec::with_capacity(bytes.len()));
                buf.extend_from_slice(&bytes[copied..open]);
                buf.push(b' ');
                copied = close_end;
                pos = close_end;
                continue;
            } else {
                // No closing tag exists anywhere after `after_open`, so later `<tag>` cannot close either.
                break;
            }
        }
        pos = open + 1;
    }

    out.map(|mut buf| {
        buf.extend_from_slice(&bytes[copied..]);
        buf
    })
}

fn find_close_tag_ci(bytes: &[u8], mut from: usize, tag: &[u8]) -> Option<usize> {
    let needed = 2 + tag.len() + 1;
    while from + needed <= bytes.len() {
        let rel = memchr(b'<', &bytes[from..bytes.len() - needed + 1])?;
        let at = from + rel;
        if bytes[at + 1] == b'/'
            && bytes[at + 2..at + 2 + tag.len()].eq_ignore_ascii_case(tag)
            && bytes[at + 2 + tag.len()] == b'>'
        {
            return Some(at + needed);
        }
        from = at + 1;
    }
    None
}

fn strip_comments(bytes: &[u8]) -> Option<Vec<u8>> {
    let mut out: Option<Vec<u8>> = None;
    let mut copied = 0usize;
    let mut pos = 0usize;
    while pos + 4 <= bytes.len() {
        let Some(rel) = memchr(b'<', &bytes[pos..bytes.len() - 3]) else {
            break;
        };
        let open = pos + rel;
        if bytes[open + 1..].starts_with(b"!--") {
            if let Some(close_end) = find_comment_end(bytes, open + 4) {
                let buf = out.get_or_insert_with(|| Vec::with_capacity(bytes.len()));
                buf.extend_from_slice(&bytes[copied..open]);
                buf.push(b' ');
                copied = close_end;
                pos = close_end;
                continue;
            } else {
                break;
            }
        }
        pos = open + 1;
    }
    out.map(|mut buf| {
        buf.extend_from_slice(&bytes[copied..]);
        buf
    })
}

fn find_comment_end(bytes: &[u8], mut from: usize) -> Option<usize> {
    while from + 3 <= bytes.len() {
        let rel = memchr(b'-', &bytes[from..bytes.len() - 2])?;
        let at = from + rel;
        if bytes[at + 1] == b'-' && bytes[at + 2] == b'>' {
            return Some(at + 3);
        }
        from = at + 1;
    }
    None
}

#[inline]
fn is_block_end_tag(inner: &[u8]) -> bool {
    if inner.len() < 2 {
        return false;
    }
    if inner.len() >= 2 && inner[..2].eq_ignore_ascii_case(b"br") {
        return true;
    }
    if inner[0] != b'/' {
        return false;
    }
    let rest = &inner[1..];
    match rest.first().map(u8::to_ascii_lowercase) {
        Some(b'p') => true,
        Some(b'd') => rest.len() >= 3 && rest[..3].eq_ignore_ascii_case(b"div"),
        Some(b'l') => rest.len() >= 2 && rest[..2].eq_ignore_ascii_case(b"li"),
        Some(b't') => {
            (rest.len() >= 2 && rest[..2].eq_ignore_ascii_case(b"tr"))
                || (rest.len() >= 5 && rest[..5].eq_ignore_ascii_case(b"table"))
        }
        Some(b'h') => {
            rest.len() >= 2 && matches!(rest[1], b'1'..=b'6')
                || (rest.len() >= 6 && rest[..6].eq_ignore_ascii_case(b"header"))
        }
        Some(b's') => rest.len() >= 7 && rest[..7].eq_ignore_ascii_case(b"section"),
        Some(b'a') => rest.len() >= 7 && rest[..7].eq_ignore_ascii_case(b"article"),
        Some(b'f') => rest.len() >= 6 && rest[..6].eq_ignore_ascii_case(b"footer"),
        _ => false,
    }
}

#[derive(Copy, Clone)]
enum TagKind {
    Li,
    BlockEnd,
    Generic,
}

fn strip_tags(bytes: &[u8]) -> String {
    let Some(first_lt) = memchr(b'<', bytes) else {
        return unsafe { String::from_utf8_unchecked(bytes.to_vec()) };
    };
    let mut out = Vec::with_capacity(bytes.len());
    out.extend_from_slice(&bytes[..first_lt]);
    let mut pos = first_lt;
    let mut nested_lt = false;

    while pos < bytes.len() {
        let Some(rel) = memchr(b'<', &bytes[pos..]) else {
            out.extend_from_slice(&bytes[pos..]);
            break;
        };
        let open = pos + rel;
        out.extend_from_slice(&bytes[pos..open]);
        let Some(gt_rel) = memchr(b'>', &bytes[open + 1..]) else {
            out.extend_from_slice(&bytes[open..]);
            break;
        };
        if gt_rel == 0 {
            out.push(b'<');
            pos = open + 1;
            continue;
        }
        let inner = &bytes[open + 1..open + 1 + gt_rel];
        if memchr(b'<', inner).is_some() {
            nested_lt = true;
            break;
        }
        if inner.len() >= 2 && inner[..2].eq_ignore_ascii_case(b"li") {
            out.extend_from_slice(b"\n- ");
        } else if is_block_end_tag(inner) {
            out.push(b'\n');
        } else {
            out.push(b' ');
        }
        pos = open + 1 + gt_rel + 1;
    }

    if nested_lt {
        // Fallback inside Rust: preserve exact sequential order of RE_LI -> RE_BLOCK_END -> RE_TAG
        // when unclosed `<` precedes a later closing `>`.
        let p5 = replace_tag_pass(bytes, TagKind::Li);
        let p6 = replace_tag_pass(&p5, TagKind::BlockEnd);
        let p7 = replace_tag_pass(&p6, TagKind::Generic);
        return unsafe { String::from_utf8_unchecked(p7) };
    }
    unsafe { String::from_utf8_unchecked(out) }
}

fn replace_tag_pass(bytes: &[u8], kind: TagKind) -> Vec<u8> {
    let mut out = Vec::with_capacity(bytes.len());
    let mut pos = 0usize;
    while pos < bytes.len() {
        let Some(rel) = memchr(b'<', &bytes[pos..]) else {
            out.extend_from_slice(&bytes[pos..]);
            break;
        };
        let open = pos + rel;
        out.extend_from_slice(&bytes[pos..open]);
        let Some(gt_rel) = memchr(b'>', &bytes[open + 1..]) else {
            out.extend_from_slice(&bytes[open..]);
            break;
        };
        let inner = &bytes[open + 1..open + 1 + gt_rel];
        match kind {
            TagKind::Li if inner.len() >= 2 && inner[..2].eq_ignore_ascii_case(b"li") => {
                out.extend_from_slice(b"\n- ");
                pos = open + 1 + gt_rel + 1;
            }
            TagKind::BlockEnd if is_block_end_tag(inner) => {
                out.push(b'\n');
                pos = open + 1 + gt_rel + 1;
            }
            TagKind::Generic if !inner.is_empty() => {
                out.push(b' ');
                pos = open + 1 + gt_rel + 1;
            }
            _ => {
                out.push(b'<');
                pos = open + 1;
            }
        }
    }
    out
}

pub fn decode_entities(text: &str) -> Result<String, i32> {
    let bytes = text.as_bytes();
    let Some(first_amp) = memchr(b'&', bytes) else {
        return Ok(text.to_owned());
    };
    let mut out = Vec::with_capacity(bytes.len());
    out.extend_from_slice(&bytes[..first_amp]);
    let mut pos = first_amp;

    while pos < bytes.len() {
        let Some(rel) = memchr(b'&', &bytes[pos..]) else {
            out.extend_from_slice(&bytes[pos..]);
            break;
        };
        let amp = pos + rel;
        out.extend_from_slice(&bytes[pos..amp]);
        if let Some((end, replacement)) = parse_entity_at(bytes, amp)? {
            out.extend_from_slice(replacement.as_bytes());
            pos = end;
        } else {
            out.push(b'&');
            pos = amp + 1;
        }
    }
    Ok(unsafe { String::from_utf8_unchecked(out) })
}

enum EntityValue<'a> {
    Static(&'a str),
    Inline([u8; 4], usize),
}

impl EntityValue<'_> {
    fn as_bytes(&self) -> &[u8] {
        match self {
            EntityValue::Static(s) => s.as_bytes(),
            EntityValue::Inline(buf, len) => &buf[..*len],
        }
    }
}

fn parse_entity_at(bytes: &[u8], amp: usize) -> Result<Option<(usize, EntityValue<'static>)>, i32> {
    let start = amp + 1;
    if start >= bytes.len() {
        return Ok(None);
    }
    if bytes[start] == b'#' {
        let hex = start + 1 < bytes.len() && (bytes[start + 1] == b'x' || bytes[start + 1] == b'X');
        let num_start = if hex { start + 2 } else { start + 1 };
        let mut cur = num_start;
        if hex {
            while cur < bytes.len() && bytes[cur].is_ascii_hexdigit() {
                cur += 1;
            }
        } else {
            while cur < bytes.len() && bytes[cur].is_ascii_digit() {
                cur += 1;
            }
        }
        if cur == num_start || cur >= bytes.len() || bytes[cur] != b';' {
            return Ok(None);
        }
        let digits = unsafe { std::str::from_utf8_unchecked(&bytes[num_start..cur]) };
        let parsed = if hex {
            u32::from_str_radix(digits, 16).ok()
        } else {
            digits.parse::<u32>().ok()
        };
        let Some(cp) = parsed.filter(|&v| v <= i32::MAX as u32) else {
            return Ok(None);
        };
        if (0xD800..=0xDFFF).contains(&cp) {
            return Err(COMPATIBILITY);
        }
        let mut buf = [0u8; 4];
        let len = char::from_u32(cp).map_or(0, |ch| ch.encode_utf8(&mut buf).len());
        return Ok(Some((cur + 1, EntityValue::Inline(buf, len))));
    }

    if !bytes[start].is_ascii_alphabetic() {
        return Ok(None);
    }
    let mut cur = start + 1;
    while cur < bytes.len() && bytes[cur].is_ascii_alphanumeric() {
        cur += 1;
    }
    if cur >= bytes.len() || bytes[cur] != b';' {
        return Ok(None);
    }
    let name = &bytes[start..cur];
    if let Some(mapped) = lookup_named_entity(name) {
        Ok(Some((cur + 1, EntityValue::Static(mapped))))
    } else {
        Ok(None)
    }
}

fn lookup_named_entity(name: &[u8]) -> Option<&'static str> {
    if name.len() > 6 || name.len() < 2 {
        return None;
    }
    let mut lower = [0u8; 6];
    for (dst, &src) in lower.iter_mut().zip(name.iter()) {
        *dst = src.to_ascii_lowercase();
    }
    match &lower[..name.len()] {
        b"amp" => Some("&"),
        b"lt" => Some("<"),
        b"gt" => Some(">"),
        b"quot" => Some("\""),
        b"apos" => Some("'"),
        b"nbsp" => Some(" "),
        b"mdash" => Some("—"),
        b"ndash" => Some("–"),
        b"hellip" => Some("…"),
        b"ldquo" => Some("“"),
        b"rdquo" => Some("”"),
        b"lsquo" => Some("‘"),
        b"rsquo" => Some("’"),
        b"middot" => Some("·"),
        b"times" => Some("×"),
        b"divide" => Some("÷"),
        b"copy" => Some("©"),
        b"reg" => Some("®"),
        b"trade" => Some("™"),
        b"yen" => Some("¥"),
        b"euro" => Some("€"),
        b"pound" => Some("£"),
        b"deg" => Some("°"),
        b"plusmn" => Some("±"),
        b"laquo" => Some("«"),
        b"raquo" => Some("»"),
        b"bull" => Some("•"),
        b"dagger" => Some("†"),
        b"sect" => Some("§"),
        b"para" => Some("¶"),
        b"micro" => Some("µ"),
        b"prime" => Some("′"),
        _ => None,
    }
}

#[inline]
fn is_kotlin_ascii_ws(b: u8) -> bool {
    matches!(b, 0x09..=0x0d | 0x1c..=0x20)
}

#[inline]
fn is_kotlin_non_ascii_ws(ch: char) -> bool {
    !ch.is_ascii() && (java_whitespace(ch) || ch.is_whitespace() || ch == '\u{180e}')
}

fn collapse_and_trim(text: &str) -> Result<String, i32> {
    let mut out = String::with_capacity(text.len());
    let mut started = false;
    let mut pending_space = false;
    let mut newlines = 0usize;
    let mut last_non_ws_len = 0usize;
    let mut trailing_non_ws_checked = true;
    let mut trailing_non_ascii_ws = false;

    let bytes = text.as_bytes();
    let mut i = 0usize;
    while i < bytes.len() {
        let b = bytes[i];
        if b < 0x80 {
            if b == b' ' || b == b'\t' {
                if started && newlines == 0 {
                    pending_space = true;
                }
                i += 1;
                continue;
            }
            if b == b'\n' {
                pending_space = false;
                if started && newlines < 2 {
                    newlines += 1;
                }
                i += 1;
                continue;
            }
            if is_kotlin_ascii_ws(b) {
                if !started {
                    pending_space = false;
                    newlines = 0;
                    i += 1;
                    continue;
                }
                if newlines > 0 {
                    for _ in 0..newlines {
                        out.push('\n');
                    }
                    newlines = 0;
                } else if pending_space {
                    out.push(' ');
                    pending_space = false;
                }
                out.push(b as char);
                i += 1;
                continue;
            }
            // Fast scan ahead across plain printable ASCII bytes inside a word/run.
            if !started {
                started = true;
                pending_space = false;
                newlines = 0;
            } else if newlines > 0 {
                for _ in 0..newlines {
                    out.push('\n');
                }
                newlines = 0;
            } else if pending_space {
                out.push(' ');
                pending_space = false;
            }
            trailing_non_ascii_ws = false;
            trailing_non_ws_checked = true;
            let run_start = i;
            i += 1;
            while i < bytes.len() {
                let nb = bytes[i];
                if nb >= 0x80 || is_kotlin_ascii_ws(nb) {
                    break;
                }
                i += 1;
            }
            out.push_str(unsafe { std::str::from_utf8_unchecked(&bytes[run_start..i]) });
            last_non_ws_len = out.len();
            continue;
        }

        let ch = text[i..].chars().next().unwrap();
        let ch_len = ch.len_utf8();
        i += ch_len;
        if ch == '\u{00A0}' || ch == '\u{3000}' {
            if started && newlines == 0 {
                pending_space = true;
            }
            continue;
        }
        if is_kotlin_non_ascii_ws(ch) {
            if !started {
                return Err(COMPATIBILITY);
            }
            trailing_non_ascii_ws = true;
            trailing_non_ws_checked = false;
            if newlines > 0 {
                for _ in 0..newlines {
                    out.push('\n');
                }
                newlines = 0;
            } else if pending_space {
                out.push(' ');
                pending_space = false;
            }
            out.push(ch);
            continue;
        }
        if !started {
            started = true;
            pending_space = false;
            newlines = 0;
        } else if newlines > 0 {
            for _ in 0..newlines {
                out.push('\n');
            }
            newlines = 0;
        } else if pending_space {
            out.push(' ');
            pending_space = false;
        }
        trailing_non_ascii_ws = false;
        trailing_non_ws_checked = true;
        out.push(ch);
        last_non_ws_len = out.len();
    }

    if !started {
        return Ok(String::new());
    }
    if trailing_non_ascii_ws && !trailing_non_ws_checked {
        return Err(COMPATIBILITY);
    }
    // Check if an ASCII whitespace run (e.g. `\r` followed by non-ASCII ws) hid a trailing non-ASCII ws.
    out.truncate(last_non_ws_len);
    Ok(out)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_web_parse_ddg(
    input: *const u16,
    len: usize,
    count: i32,
    output: *mut SearchBuffer,
) -> i32 {
    if output.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    // SAFETY: Caller passed writable SearchBuffer out-parameter.
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        });
    }
    if count < 0 || (len != 0 && input.is_null()) || len > MAX_INPUT_UNITS {
        return COMPATIBILITY;
    }
    if count == 0 || len == 0 {
        return match encode_ddg_hits(&[]) {
            Ok(bytes) => write_search_buffer(output, bytes),
            Err(status) => status,
        };
    }
    match catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: Validated non-null `len` UTF-16 code units pinned for this synchronous call.
        let units = unsafe { slice::from_raw_parts(input, len) };
        let Ok(html) = String::from_utf16(units) else {
            return Err(COMPATIBILITY);
        };
        let hits = parse_duckduckgo(&html, count as usize)?;
        encode_ddg_hits(&hits)
    })) {
        Ok(Ok(bytes)) => write_search_buffer(output, bytes),
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

fn write_search_buffer(output: *mut SearchBuffer, bytes: Vec<u8>) -> i32 {
    if bytes.len() > MAX_OUTPUT_BYTES {
        return COMPATIBILITY;
    }
    let owned = bytes.into_boxed_slice();
    let len = owned.len();
    let data = Box::into_raw(owned) as *mut u8;
    // SAFETY: Caller owns output storage and releases the buffer via `sx_search_free`.
    unsafe { output.write(SearchBuffer { data, len }) };
    STATUS_OK
}

fn encode_ddg_hits(hits: &[String]) -> Result<Vec<u8>, i32> {
    let total_units: usize = hits.iter().map(|h| h.encode_utf16().count()).sum();
    let byte_len = 4usize
        .checked_add(hits.len().saturating_mul(4))
        .and_then(|n| n.checked_add(total_units.saturating_mul(2)))
        .ok_or(COMPATIBILITY)?;
    if byte_len > MAX_OUTPUT_BYTES {
        return Err(COMPATIBILITY);
    }
    let mut out = Vec::with_capacity(byte_len);
    out.extend_from_slice(&(hits.len() as u32).to_le_bytes());
    for hit in hits {
        let count = hit.encode_utf16().count() as u32;
        out.extend_from_slice(&count.to_le_bytes());
        for unit in hit.encode_utf16() {
            out.extend_from_slice(&unit.to_le_bytes());
        }
    }
    Ok(out)
}

fn contains_ci(haystack: &[u8], needle_lower: &[u8]) -> bool {
    find_ci(haystack, needle_lower).is_some()
}

fn find_ci(haystack: &[u8], needle_lower: &[u8]) -> Option<usize> {
    if needle_lower.is_empty() {
        return Some(0);
    }
    if haystack.len() < needle_lower.len() {
        return None;
    }
    let first = needle_lower[0];
    let limit = haystack.len() - needle_lower.len();
    let mut pos = 0usize;
    while pos <= limit {
        let mut matched = None;
        for idx in pos..=limit {
            if haystack[idx].to_ascii_lowercase() == first {
                matched = Some(idx);
                break;
            }
        }
        let Some(at) = matched else {
            return None;
        };
        if haystack[at..at + needle_lower.len()].eq_ignore_ascii_case(needle_lower) {
            return Some(at);
        }
        pos = at + 1;
    }
    None
}

fn find_closing_a(bytes: &[u8], mut from: usize) -> Option<usize> {
    while from + 4 <= bytes.len() {
        let rel = memchr(b'<', &bytes[from..bytes.len() - 3])?;
        let at = from + rel;
        if bytes[at + 1] == b'/'
            && bytes[at + 2].eq_ignore_ascii_case(&b'a')
            && bytes[at + 3] == b'>'
        {
            return Some(at);
        }
        from = at + 1;
    }
    None
}

fn quoted_contains_balanced(tag_inner: &[u8]) -> bool {
    tag_inner.iter().filter(|&&b| b == b'"').count() % 2 == 0
}

fn parse_duckduckgo(html: &str, count: usize) -> Result<Vec<String>, i32> {
    if count == 0 || html.is_empty() {
        return Ok(Vec::new());
    }
    let bytes = html.as_bytes();
    let mut links: Vec<(&str, &str)> = Vec::with_capacity(count.min(16));
    let mut pos = 0usize;
    while pos + 4 < bytes.len() && links.len() < count {
        let Some(rel) = memchr(b'<', &bytes[pos..]) else {
            break;
        };
        let open = pos + rel;
        if open + 2 >= bytes.len() || !bytes[open + 1].eq_ignore_ascii_case(&b'a') {
            pos = open + 1;
            continue;
        }
        let Some(gt_rel) = memchr(b'>', &bytes[open + 2..]) else {
            break;
        };
        let gt = open + 2 + gt_rel;
        if gt == open + 2 {
            pos = gt + 1;
            continue;
        }
        let tag_body = &bytes[open + 2..gt];
        if !quoted_contains_balanced(tag_body) {
            return Err(COMPATIBILITY);
        }
        // Scan all `class="..."` occurrences with at least 1 byte before `class="`,
        // matching greedy `[^>]+class="..."[^>]+href="([^"]+)"`.
        let mut matched_href: Option<(usize, usize)> = None;
        let mut c_scan = 1usize;
        while c_scan < tag_body.len() {
            let Some(c_rel) = find_ci(&tag_body[c_scan..], b"class=\"") else {
                break;
            };
            let c_start = c_scan + c_rel + 7;
            let Some(c_end_rel) = memchr(b'"', &tag_body[c_start..]) else {
                return Err(COMPATIBILITY);
            };
            let c_end = c_start + c_end_rel;
            if contains_ci(&tag_body[c_start..c_end], b"result__a") && c_end + 2 < tag_body.len() {
                let mut h_scan = c_end + 2;
                while h_scan < tag_body.len() {
                    let Some(h_rel) = find_ci(&tag_body[h_scan..], b"href=\"") else {
                        break;
                    };
                    let h_start = h_scan + h_rel + 6;
                    let Some(h_end_rel) = memchr(b'"', &tag_body[h_start..]) else {
                        return Err(COMPATIBILITY);
                    };
                    let h_end = h_start + h_end_rel;
                    if h_end > h_start {
                        matched_href = Some((open + 2 + h_start, open + 2 + h_end));
                    }
                    h_scan = h_end + 1;
                }
            }
            c_scan = c_end + 1;
        }

        let Some((href_start, href_end)) = matched_href else {
            pos = open + 2;
            continue;
        };
        let Some(close_at) = find_closing_a(bytes, gt + 1) else {
            pos = open + 2;
            continue;
        };
        links.push((&html[href_start..href_end], &html[gt + 1..close_at]));
        pos = close_at + 4;
    }

    if links.is_empty() {
        return Ok(Vec::new());
    }

    let mut snippets: Vec<&str> = Vec::with_capacity(links.len());
    pos = 0;
    while pos + 4 < bytes.len() && snippets.len() < links.len() {
        let Some(rel) = memchr(b'<', &bytes[pos..]) else {
            break;
        };
        let open = pos + rel;
        if open + 2 >= bytes.len() || !bytes[open + 1].eq_ignore_ascii_case(&b'a') {
            pos = open + 1;
            continue;
        }
        let Some(gt_rel) = memchr(b'>', &bytes[open + 2..]) else {
            break;
        };
        let gt = open + 2 + gt_rel;
        if gt == open + 2 {
            pos = gt + 1;
            continue;
        }
        let tag_body = &bytes[open + 2..gt];
        if !quoted_contains_balanced(tag_body) {
            return Err(COMPATIBILITY);
        }
        let mut is_snippet = false;
        let mut c_scan = 1usize;
        while c_scan < tag_body.len() {
            let Some(c_rel) = find_ci(&tag_body[c_scan..], b"class=\"") else {
                break;
            };
            let c_start = c_scan + c_rel + 7;
            let Some(c_end_rel) = memchr(b'"', &tag_body[c_start..]) else {
                return Err(COMPATIBILITY);
            };
            let c_end = c_start + c_end_rel;
            if contains_ci(&tag_body[c_start..c_end], b"result__snippet") {
                is_snippet = true;
                break;
            }
            c_scan = c_end + 1;
        }
        if !is_snippet {
            pos = open + 2;
            continue;
        }
        let Some(close_at) = find_closing_a(bytes, gt + 1) else {
            pos = open + 2;
            continue;
        };
        snippets.push(&html[gt + 1..close_at]);
        pos = close_at + 4;
    }

    let mut hits = Vec::with_capacity(links.len());
    for (index, (raw_href, raw_title)) in links.into_iter().enumerate() {
        let href = if raw_href.contains('&') {
            decode_entities(raw_href)?
        } else {
            raw_href.to_owned()
        };
        let title = html_to_text(raw_title)?;
        let snippet = match snippets.get(index) {
            Some(raw) => html_to_text(raw)?,
            None => String::new(),
        };
        let clean = clean_ddg_link(&href);
        let snippet_prefix = utf16_take(&snippet, 300)?;
        hits.push(format!("[{title}]\n{clean}\n{snippet_prefix}"));
    }
    Ok(hits)
}

fn utf16_take(text: &str, max_units: usize) -> Result<String, i32> {
    if text.is_empty() || max_units == 0 {
        return Ok(String::new());
    }
    let units: Vec<u16> = text.encode_utf16().take(max_units).collect();
    // An unpaired trailing surrogate from cutting mid-codepoint differs from char boundary; fall back cleanly.
    String::from_utf16(&units).map_err(|_| COMPATIBILITY)
}

fn clean_ddg_link(href: &str) -> Cow<'_, str> {
    let Some(marker) = href.find("uddg=") else {
        return Cow::Borrowed(href);
    };
    let tail = &href[marker + 5..];
    let encoded = tail.split('&').next().unwrap_or(tail);
    let mut out = Vec::with_capacity(encoded.len());
    let bytes = encoded.as_bytes();
    let mut i = 0usize;
    while i < bytes.len() {
        match bytes[i] {
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b'%' => {
                if i + 2 >= bytes.len() {
                    return Cow::Borrowed(href);
                }
                let Some(hi) = (bytes[i + 1] as char).to_digit(16) else {
                    return Cow::Borrowed(href);
                };
                let Some(lo) = (bytes[i + 2] as char).to_digit(16) else {
                    return Cow::Borrowed(href);
                };
                out.push(((hi << 4) | lo) as u8);
                i += 3;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    Cow::Owned(String::from_utf8_lossy(&out).into_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    #[test]
    fn strips_scripts_styles_comments_and_keeps_list_and_blocks() {
        let html = r#"
            <html><head>
            <style>body { color: red; }</style>
            <script>var secret = "do not read me";</script>
            <noscript>enable js</noscript>
            <!-- secret comment -->
            </head><body><p>第一行</p><div>第二行</div><ul><li>甲</li><li>乙</li></ul></body></html>
        "#;
        assert_eq!(
            html_to_text(html).unwrap(),
            "第一行\n第二行\n\n- 甲\n\n- 乙"
        );
    }

    #[test]
    fn decodes_named_decimal_and_hex_entities() {
        assert_eq!(
            decode_entities("A &amp; B &lt; C &notarealentity; &#20013;&#x4E2D;&#9999999;")
                .unwrap(),
            "A & B < C &notarealentity; 中中"
        );
        assert_eq!(decode_entities("&#x80000000;").unwrap(), "&#x80000000;");
        assert_eq!(decode_entities("&#xD800;"), Err(COMPATIBILITY));
    }

    #[test]
    fn handles_nested_lt_and_empty_tags_like_kotlin_regex_order() {
        assert_eq!(
            html_to_text("<>plain<<br>tail</p>").unwrap(),
            "<>plain<\ntail"
        );
    }

    #[test]
    fn benchmark_html_to_text_100kb() {
        let chunk = "<div class=\"card\"><h2>Title &amp; Sub</h2><p>Paragraph with <b>bold</b>, <a href=\"https://example.com\">link</a>, &#x4E2D;&#x6587; &nbsp; spaces.</p><script>console.log(1)</script><ul><li>item one</li><li>item two</li></ul></div>\n";
        let big = chunk.repeat(480);
        let start = Instant::now();
        let mut total = 0usize;
        for _ in 0..20 {
            total += html_to_text(&big).unwrap().len();
        }
        let elapsed = start.elapsed();
        assert!(total > 0);
        assert!(elapsed.as_secs_f64() < 2.0, "took {:?}", elapsed);
    }

    #[test]
    fn parses_duckduckgo_links_snippets_entities_and_uddg_urls() {
        let html = r#"
            <div class="result">
              <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fb%3Dc%2B1&amp;rut=123">Example &amp; <b>Title</b></a>
              <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">First <i>snippet</i> &nbsp; text.</a>
            </div>
            <div class="result">
              <A CLASS="other result__a" HREF="https://plain.test/p?x=1">Plain</A>
            </div>
        "#;
        let hits = parse_duckduckgo(html, 5).unwrap();
        assert_eq!(
            hits,
            vec![
                "[Example & Title]\nhttps://example.com/a?b=c+1\nFirst snippet text.".to_string(),
                "[Plain]\nhttps://plain.test/p?x=1\n".to_string(),
            ]
        );
    }
}
