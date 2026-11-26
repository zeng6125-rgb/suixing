//! Single-pass multi-stage Markdown cleanup for `Speaker.cleanForSpeech`, preserving exact
//! Java regex precedence over standard LF-normalized UTF-8 strings.

use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer, java_whitespace};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;

const COMPATIBILITY: i32 = 4;
const MAX_INPUT_BYTES: usize = 8 * 1024 * 1024;
const MAX_INPUT_UNITS: usize = 4 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 8 * 1024 * 1024;
const MAX_SPEECH_CHARS: usize = 4000;

/// # Safety
/// `input` must point to `len` readable UTF-16 units and `output` must be writable for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_speech_clean_utf16(
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
        return STATUS_REQUEST_ERROR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let units = if len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(input, len) }
        };
        let utf8 = String::from_utf16(units).map_err(|_| COMPATIBILITY)?;
        let cleaned = clean_for_speech_str(&utf8)?;
        if cleaned.is_empty() {
            return Ok(());
        }
        let encoded = if cleaned.is_ascii() {
            let bytes = cleaned.as_bytes();
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
            out
        } else {
            let mut out = Vec::with_capacity((cleaned.len() * 2).min(MAX_OUTPUT_BYTES));
            for unit in cleaned.encode_utf16() {
                if out.len() + 2 > MAX_OUTPUT_BYTES {
                    return Err(COMPATIBILITY);
                }
                out.extend_from_slice(&unit.to_le_bytes());
            }
            out
        };
        let owned = encoded.into_boxed_slice();
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

/// # Safety
/// `input_utf8` must stay readable for `len` bytes and `output` writable for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_speech_clean(
    input_utf8: *const u8,
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
    if len > MAX_INPUT_BYTES || (len != 0 && input_utf8.is_null()) {
        return STATUS_REQUEST_ERROR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let bytes = if len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(input_utf8, len) }
        };
        let text = std::str::from_utf8(bytes).map_err(|_| STATUS_REQUEST_ERROR)?;
        let cleaned = clean_for_speech_str(text)?;
        let owned = cleaned.into_bytes().into_boxed_slice();
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

#[inline]
fn is_ascii_ws(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\n')
}

fn check_supported_whitespace(text: &str) -> Result<(), i32> {
    if text.bytes().any(|b| matches!(b, b'\r' | 0x0b | 0x0c)) {
        return Err(COMPATIBILITY);
    }
    if !text.is_ascii() {
        for ch in text.chars() {
            if !ch.is_ascii()
                && (ch.is_whitespace()
                    || java_whitespace(ch)
                    || ch == '\u{0085}'
                    || ch == '\u{180e}'
                    || ch == '\u{2028}'
                    || ch == '\u{2029}')
            {
                return Err(COMPATIBILITY);
            }
        }
    }
    Ok(())
}

/// 1. `Regex("```[\\s\\S]*?```") -> " 代码块省略 "`
fn pass_fenced_code(src: &str, out: &mut String) {
    out.clear();
    let mut at = 0usize;
    while let Some(open_rel) = src[at..].find("```") {
        let open = at + open_rel;
        if let Some(close_rel) = src[open + 3..].find("```") {
            out.push_str(&src[at..open]);
            out.push_str(" 代码块省略 ");
            at = open + 3 + close_rel + 3;
        } else {
            break;
        }
    }
    out.push_str(&src[at..]);
}

/// 2. `Regex("`([^`]*)`") -> "$1"`
fn pass_inline_code(src: &str, out: &mut String) {
    out.clear();
    let bytes = src.as_bytes();
    let mut at = 0usize;
    while let Some(open_rel) = bytes[at..].iter().position(|&b| b == b'`') {
        let open = at + open_rel;
        if let Some(close_rel) = bytes[open + 1..].iter().position(|&b| b == b'`') {
            let close = open + 1 + close_rel;
            out.push_str(&src[at..open]);
            out.push_str(&src[open + 1..close]);
            at = close + 1;
        } else {
            break;
        }
    }
    out.push_str(&src[at..]);
}

/// 3. `Regex("!\\[[^\\]]*\\]\\([^)]*\\)") -> " "`
fn pass_images(src: &str, out: &mut String) {
    out.clear();
    let bytes = src.as_bytes();
    let mut at = 0usize;
    let mut search_from = 0usize;
    while let Some(rel) = src[search_from..].find("![") {
        let open = search_from + rel;
        let Some(rbr_rel) = bytes[open + 2..].iter().position(|&b| b == b']') else {
            break;
        };
        let rbr = open + 2 + rbr_rel;
        if bytes.get(rbr + 1) != Some(&b'(') {
            search_from = rbr + 1;
            continue;
        }
        let Some(rparen_rel) = bytes[rbr + 2..].iter().position(|&b| b == b')') else {
            search_from = rbr + 2;
            continue;
        };
        let end = rbr + 2 + rparen_rel + 1;
        out.push_str(&src[at..open]);
        out.push(' ');
        at = end;
        search_from = end;
    }
    out.push_str(&src[at..]);
}

/// 4. `Regex("\\[([^\\]]*)\\]\\([^)]*\\)") -> "$1"`
fn pass_links(src: &str, out: &mut String) {
    out.clear();
    let bytes = src.as_bytes();
    let mut at = 0usize;
    let mut search_from = 0usize;
    while let Some(rel) = bytes[search_from..].iter().position(|&b| b == b'[') {
        let open = search_from + rel;
        let Some(rbr_rel) = bytes[open + 1..].iter().position(|&b| b == b']') else {
            break;
        };
        let rbr = open + 1 + rbr_rel;
        if bytes.get(rbr + 1) != Some(&b'(') {
            search_from = rbr + 1;
            continue;
        }
        let Some(rparen_rel) = bytes[rbr + 2..].iter().position(|&b| b == b')') else {
            search_from = rbr + 2;
            continue;
        };
        let end = rbr + 2 + rparen_rel + 1;
        out.push_str(&src[at..open]);
        out.push_str(&src[open + 1..rbr]);
        at = end;
        search_from = end;
    }
    out.push_str(&src[at..]);
}

#[inline]
fn at_line_start(bytes: &[u8], i: usize) -> bool {
    // Java (?m)^ matches index 0 and right after '\n' except at EOF after trailing '\n'.
    i < bytes.len() && (i == 0 || bytes[i - 1] == b'\n')
}

/// 5. `Regex("(?m)^\\s{0,3}#{1,6}\\s*") -> ""`
fn pass_headings(src: &str, out: &mut String) {
    out.clear();
    let bytes = src.as_bytes();
    let mut copied = 0usize;
    let mut i = 0usize;
    while i < bytes.len() {
        if !at_line_start(bytes, i) {
            i += 1;
            continue;
        }
        let mut ws = 0usize;
        while ws < 3 && i + ws < bytes.len() && is_ascii_ws(bytes[i + ws]) {
            if bytes[i + ws] == b'#' {
                break;
            }
            ws += 1;
        }
        let mut matched_end = None;
        // Greedy \s{0,3}: test k from 3 down to 0 so behavior matches Java NFA cleanly.
        for k in (0..=ws).rev() {
            let p = i + k;
            if p < bytes.len() && bytes[p] == b'#' {
                let mut hashes = 1usize;
                while hashes < 6 && p + hashes < bytes.len() && bytes[p + hashes] == b'#' {
                    hashes += 1;
                }
                let mut tail = p + hashes;
                while tail < bytes.len() && is_ascii_ws(bytes[tail]) {
                    tail += 1;
                }
                matched_end = Some(tail);
                break;
            }
        }
        if let Some(end) = matched_end {
            out.push_str(&src[copied..i]);
            copied = end;
            i = end;
        } else {
            while i < bytes.len() && bytes[i] != b'\n' {
                i += 1;
            }
            if i < bytes.len() {
                i += 1;
            }
        }
    }
    out.push_str(&src[copied..]);
}

/// 6 & 7 & 12: line-start prefixes with leading `\s*`:
/// - `(?m)^\s*[-*+]\s+` -> ""
/// - `(?m)^\s*\d+\.\s+` -> ""
/// - `(?m)^\s*>\s?` -> ""
fn pass_line_prefix(src: &str, out: &mut String, kind: u8) {
    out.clear();
    let bytes = src.as_bytes();
    let mut copied = 0usize;
    let mut i = 0usize;
    while i < bytes.len() {
        if !at_line_start(bytes, i) {
            i += 1;
            continue;
        }
        let mut p = i;
        while p < bytes.len() && is_ascii_ws(bytes[p]) {
            p += 1;
        }
        let matched_end = match kind {
            0 => {
                if p < bytes.len()
                    && matches!(bytes[p], b'-' | b'*' | b'+')
                    && p + 1 < bytes.len()
                    && is_ascii_ws(bytes[p + 1])
                {
                    let mut q = p + 1;
                    while q < bytes.len() && is_ascii_ws(bytes[q]) {
                        q += 1;
                    }
                    Some(q)
                } else {
                    None
                }
            }
            1 => {
                let mut q = p;
                while q < bytes.len() && bytes[q].is_ascii_digit() {
                    q += 1;
                }
                if q > p
                    && q < bytes.len()
                    && bytes[q] == b'.'
                    && q + 1 < bytes.len()
                    && is_ascii_ws(bytes[q + 1])
                {
                    let mut r = q + 1;
                    while r < bytes.len() && is_ascii_ws(bytes[r]) {
                        r += 1;
                    }
                    Some(r)
                } else {
                    None
                }
            }
            _ => {
                if p < bytes.len() && bytes[p] == b'>' {
                    let mut q = p + 1;
                    if q < bytes.len() && is_ascii_ws(bytes[q]) {
                        q += 1;
                    }
                    Some(q)
                } else {
                    None
                }
            }
        };
        if let Some(end) = matched_end {
            out.push_str(&src[copied..i]);
            copied = end;
            i = end;
        } else {
            while i < bytes.len() && bytes[i] != b'\n' {
                i += 1;
            }
            if i < bytes.len() {
                i += 1;
            }
        }
    }
    out.push_str(&src[copied..]);
}

/// 8 & 10: paired `**([^*]*)**` / `~~([^~]*)~~`
fn pass_paired_double(src: &str, out: &mut String, marker: u8) {
    out.clear();
    let bytes = src.as_bytes();
    let mut copied = 0usize;
    let mut i = 0usize;
    while i + 3 < bytes.len() {
        if bytes[i] == marker && bytes[i + 1] == marker {
            if let Some(rel) = bytes[i + 2..].iter().position(|&b| b == marker) {
                let close = i + 2 + rel;
                if bytes.get(close + 1) == Some(&marker) {
                    out.push_str(&src[copied..i]);
                    out.push_str(&src[i + 2..close]);
                    copied = close + 2;
                    i = copied;
                    continue;
                }
            } else {
                break;
            }
        }
        i += 1;
    }
    out.push_str(&src[copied..]);
}

/// 9: `(?<!\*)\*([^* \n]+)\*(?!\*)`
fn pass_single_italic(src: &str, out: &mut String) {
    out.clear();
    let bytes = src.as_bytes();
    let mut copied = 0usize;
    let mut i = 0usize;
    while i < bytes.len() {
        if bytes[i] == b'*' && (i == 0 || bytes[i - 1] != b'*') {
            let mut j = i + 1;
            while j < bytes.len() && bytes[j] != b'*' && bytes[j] != b'\n' {
                j += 1;
            }
            if j > i + 1 && j < bytes.len() && bytes[j] == b'*' && bytes.get(j + 1) != Some(&b'*') {
                out.push_str(&src[copied..i]);
                out.push_str(&src[i + 1..j]);
                copied = j + 1;
                i = copied;
                continue;
            }
            i = j + 1;
        } else {
            i += 1;
        }
    }
    out.push_str(&src[copied..]);
}

/// 11. `Regex("(?m)^\\s*\\|.*\\|\\s*$") -> " 表格省略 "`
fn pass_tables(src: &str, out: &mut String) {
    out.clear();
    let bytes = src.as_bytes();
    let mut copied = 0usize;
    let mut i = 0usize;
    while i < bytes.len() {
        if !at_line_start(bytes, i) {
            i += 1;
            continue;
        }
        let mut p = i;
        while p < bytes.len() && is_ascii_ws(bytes[p]) {
            p += 1;
        }
        if p < bytes.len() && bytes[p] == b'|' {
            let line_end = bytes[p + 1..]
                .iter()
                .position(|&b| b == b'\n')
                .map_or(bytes.len(), |rel| p + 1 + rel);
            if let Some(last_pipe_rel) = bytes[p + 1..line_end].iter().rposition(|&b| b == b'|') {
                let mut end = p + 1 + last_pipe_rel + 1;
                let mut scan = end;
                // Greedy `\s*$`: can extend through blank `\n` lines up to the latest `(?m)$` boundary.
                while scan < bytes.len() && is_ascii_ws(bytes[scan]) {
                    scan += 1;
                }
                if scan == bytes.len() {
                    end = scan;
                } else {
                    while scan > end && bytes[scan] != b'\n' {
                        scan -= 1;
                    }
                    if scan >= end && bytes.get(scan) == Some(&b'\n') {
                        end = scan;
                    } else {
                        end = line_end;
                    }
                }
                out.push_str(&src[copied..i]);
                out.push_str(" 表格省略 ");
                copied = end;
                i = end;
                continue;
            }
        }
        while i < bytes.len() && bytes[i] != b'\n' {
            i += 1;
        }
        if i < bytes.len() {
            i += 1;
        }
    }
    out.push_str(&src[copied..]);
}

/// 13. `Regex("https?://\\S+") -> " 链接 "`
fn pass_urls(src: &str, out: &mut String) {
    out.clear();
    let bytes = src.as_bytes();
    let mut copied = 0usize;
    let mut i = 0usize;
    while i + 7 < bytes.len() {
        let scheme_len = if bytes[i..].starts_with(b"https://") {
            8usize
        } else if bytes[i..].starts_with(b"http://") {
            7usize
        } else {
            0usize
        };
        if scheme_len > 0 && i + scheme_len < bytes.len() && !is_ascii_ws(bytes[i + scheme_len]) {
            let mut end = i + scheme_len + 1;
            while end < bytes.len() && !is_ascii_ws(bytes[end]) {
                end += 1;
            }
            out.push_str(&src[copied..i]);
            out.push_str(" 链接 ");
            copied = end;
            i = end;
        } else {
            i += 1;
        }
    }
    out.push_str(&src[copied..]);
}

/// 14 & 15 + trim + take(4000 utf16 units)
fn pass_collapse_and_finish(src: &str, out: &mut String) -> Result<(), i32> {
    out.clear();
    let bytes = src.as_bytes();
    let mut i = 0usize;
    while i < bytes.len() {
        let b = bytes[i];
        if b == b' ' || b == b'\t' {
            while i < bytes.len() && (bytes[i] == b' ' || bytes[i] == b'\t') {
                i += 1;
            }
            out.push(' ');
        } else if b == b'\n' {
            while i < bytes.len() && bytes[i] == b'\n' {
                i += 1;
            }
            out.push('\n');
        } else if b < 0x80 {
            out.push(b as char);
            i += 1;
        } else {
            let ch = src[i..].chars().next().unwrap();
            out.push(ch);
            i += ch.len_utf8();
        }
    }
    let start = out
        .bytes()
        .position(|b| !is_ascii_ws(b))
        .unwrap_or(out.len());
    let end = out
        .bytes()
        .rposition(|b| !is_ascii_ws(b))
        .map_or(start, |idx| idx + 1);
    if start > 0 || end < out.len() {
        let trimmed = out[start..end].to_owned();
        *out = trimmed;
    }
    let mut units = 0usize;
    let mut cut = out.len();
    for (idx, ch) in out.char_indices() {
        let cu = ch.len_utf16();
        if units + cu > MAX_SPEECH_CHARS {
            if units < MAX_SPEECH_CHARS {
                return Err(COMPATIBILITY);
            }
            cut = idx;
            break;
        }
        units += cu;
    }
    out.truncate(cut);
    Ok(())
}

pub fn clean_for_speech_str(text: &str) -> Result<String, i32> {
    check_supported_whitespace(text)?;
    let mut a = String::with_capacity(text.len());
    let mut b = String::with_capacity(text.len());
    pass_fenced_code(text, &mut a);
    pass_inline_code(&a, &mut b);
    pass_images(&b, &mut a);
    pass_links(&a, &mut b);
    pass_headings(&b, &mut a);
    pass_line_prefix(&a, &mut b, 0);
    pass_line_prefix(&b, &mut a, 1);
    pass_paired_double(&a, &mut b, b'*');
    pass_single_italic(&b, &mut a);
    pass_paired_double(&a, &mut b, b'~');
    pass_tables(&b, &mut a);
    pass_line_prefix(&a, &mut b, 2);
    pass_urls(&b, &mut a);
    pass_collapse_and_finish(&a, &mut b)?;
    Ok(b)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    #[test]
    fn cleans_all_markdown_constructs_cleanly() {
        let input = "### 标题\n\n- 列表 **粗体** 和 *斜体* 以及 ~~删除线~~\n1. 有序 `code`\n> 引用 ![图](a.png) [链接](https://x.com)\n```rust\nfn main() {}\n```\n| a | b |\n| - | - |\n| 1 | 2 |\n访问 https://example.com/foo  结束";
        let actual = clean_for_speech_str(input).unwrap();
        assert!(actual.contains("标题"));
        assert!(actual.contains("粗体"));
        assert!(actual.contains("斜体"));
        assert!(actual.contains("删除线"));
        assert!(actual.contains("代码块省略"));
        assert!(actual.contains("表格省略"));
        assert!(actual.contains("链接"));
        assert!(!actual.contains("https://"));
        assert!(!actual.contains("**"));
        assert!(!actual.contains("~~"));
    }

    #[test]
    fn falls_back_on_cr_or_non_ascii_whitespace() {
        assert_eq!(clean_for_speech_str("a\r\nb"), Err(COMPATIBILITY));
        assert_eq!(clean_for_speech_str("a\u{00a0}b"), Err(COMPATIBILITY));
    }

    #[test]
    fn benchmark_speech_clean() {
        let sample = "## Summary\n\n- Check **result** cleanly with `cargo test` and [docs](https://example.com).\n```kotlin\nprintln(1)\n```\n| col | val |\n| a | b |\n> Quoted *important* text ~~old~~\n".repeat(40);
        let start = Instant::now();
        let mut total = 0usize;
        for _ in 0..100 {
            let s = clean_for_speech_str(&sample).unwrap();
            total += s.len();
        }
        let elapsed = start.elapsed();
        assert!(total > 0);
        assert!(elapsed.as_secs_f64() < 2.0, "took {:?}", elapsed);
    }
}
