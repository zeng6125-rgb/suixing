//! Native plugin command blacklist scanner, template variable extractor, and template renderer.
//! Preserves exact Java Regex (non-Unicode class), JSON string-state parity, and URLEncoder behavior.

use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer};
use memchr::{memchr, memchr2};
use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;

const STATUS_COMPATIBILITY: i32 = 4;

const MAX_INPUT_UNITS: usize = 4 * 1024 * 1024;
const MAX_PACKED_VARS_BYTES: usize = 8 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 16 * 1024 * 1024;

pub const MODE_RAW: i32 = 0;
pub const MODE_SHELL_QUOTE: i32 = 1;
pub const MODE_HEADER_SAFE: i32 = 2;
pub const MODE_JSON_AWARE: i32 = 3;
pub const MODE_URL_ENCODE: i32 = 4;

const SAFE_RM_PREFIXES: &[&str] = &["/sdcard", "/storage", "/data/local/tmp"];
const HEX_UPPER: &[u8; 16] = b"0123456789ABCDEF";
const HEX_LOWER: &[u8; 16] = b"0123456789abcdef";

#[inline]
fn is_java_ws(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\n' | 0x0B | 0x0C | b'\r')
}

#[inline]
fn is_java_word(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

#[inline]
fn is_var_start(b: u8) -> bool {
    b.is_ascii_alphabetic() || b == b'_'
}

#[inline]
fn is_var_cont(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_' || b == b'.'
}

fn finish_bytes_output(bytes: Vec<u8>, output: *mut SearchBuffer) -> i32 {
    if bytes.len() > MAX_OUTPUT_BYTES {
        return STATUS_COMPATIBILITY;
    }
    if bytes.is_empty() {
        return STATUS_OK;
    }
    let owned = bytes.into_boxed_slice();
    let len = owned.len();
    let raw = Box::into_raw(owned) as *mut u8;
    // SAFETY: Caller passed writable SearchBuffer initialized to null/0 at entry.
    unsafe {
        output.write(SearchBuffer { data: raw, len });
    }
    STATUS_OK
}

fn init_output(output: *mut SearchBuffer) -> bool {
    if output.is_null() {
        return false;
    }
    // SAFETY: Caller guarantees `output` points to writable SearchBuffer storage.
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        });
    }
    true
}

fn decode_utf16(input: *const u16, len: usize) -> Result<String, i32> {
    if len > MAX_INPUT_UNITS || (len != 0 && input.is_null()) {
        return Err(STATUS_COMPATIBILITY);
    }
    if len == 0 {
        return Ok(String::new());
    }
    // SAFETY: Non-empty `len` is validated non-null for this synchronous JNI call.
    let units = unsafe { slice::from_raw_parts(input, len) };
    String::from_utf16(units).map_err(|_| STATUS_COMPATIBILITY)
}

#[inline]
fn push_u32(out: &mut Vec<u8>, v: usize) -> Result<(), i32> {
    let val = u32::try_from(v).map_err(|_| STATUS_COMPATIBILITY)?;
    out.extend_from_slice(&val.to_le_bytes());
    Ok(())
}

#[inline]
fn match_rm_rf_at(bytes: &[u8], idx: usize) -> Option<(usize, usize)> {
    let len = bytes.len();
    if idx + 1 >= len || bytes[idx] != b'r' || bytes[idx + 1] != b'm' {
        return None;
    }
    if idx > 0 && is_java_word(bytes[idx - 1]) {
        return None;
    }
    let mut j = idx + 2;
    if j >= len || !is_java_ws(bytes[j]) {
        return None;
    }
    while j < len && is_java_ws(bytes[j]) {
        j += 1;
    }
    if j >= len || bytes[j] != b'-' {
        return None;
    }
    j += 1;
    let mut saw_r = false;
    let mut saw_f = false;
    while j < len && bytes[j].is_ascii_alphabetic() {
        let ch = bytes[j];
        if !saw_r {
            if ch == b'r' {
                saw_r = true;
            }
        } else if !saw_f && ch == b'f' {
            saw_f = true;
        }
        j += 1;
    }
    if !(saw_r && saw_f) {
        return None;
    }
    if j >= len || !is_java_ws(bytes[j]) {
        return None;
    }
    while j < len && is_java_ws(bytes[j]) {
        j += 1;
    }
    Some((idx, j))
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum DangerHit<'a> {
    RmForce(&'a str),
    Blacklist(u8),
}

pub(crate) fn scan_command_danger(command: &str) -> Vec<DangerHit<'_>> {
    let bytes = command.as_bytes();
    let len = bytes.len();
    let mut hits = Vec::new();

    // 1. RM_FORCE: \brm\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\s+([^\s;&|]+)
    let mut pos = 0usize;
    while pos < len {
        let Some(rel) = memchr(b'r', &bytes[pos..]) else {
            break;
        };
        let idx = pos + rel;
        if let Some((_, mut j)) = match_rm_rf_at(bytes, idx) {
            let t_start = j;
            while j < len
                && !is_java_ws(bytes[j])
                && bytes[j] != b';'
                && bytes[j] != b'&'
                && bytes[j] != b'|'
            {
                j += 1;
            }
            if j > t_start {
                let target = &command[t_start..j];
                if !SAFE_RM_PREFIXES.iter().any(|p| target.starts_with(p)) {
                    hits.push(DangerHit::RmForce(target));
                }
                pos = j;
                continue;
            }
        }
        pos = idx + 1;
    }

    // 2. SHELL_BLACKLIST[0]: \bmkfs(\.[a-z0-9]+)?\b
    // Since '.' is \W under Java default Regex, any match starts and ends as \bmkfs\b.
    let mut p0 = 0usize;
    while p0 + 4 <= len {
        let Some(rel) = memchr(b'm', &bytes[p0..]) else {
            break;
        };
        let idx = p0 + rel;
        if idx + 4 <= len
            && &bytes[idx..idx + 4] == b"mkfs"
            && (idx == 0 || !is_java_word(bytes[idx - 1]))
            && (idx + 4 == len || !is_java_word(bytes[idx + 4]))
        {
            hits.push(DangerHit::Blacklist(0));
            break;
        }
        p0 = idx + 1;
    }

    // 3. SHELL_BLACKLIST[1]: \bdd\b[^\n]*\bof=/dev/
    let mut p1 = 0usize;
    while p1 + 2 <= len {
        let Some(rel) = memchr(b'd', &bytes[p1..]) else {
            break;
        };
        let idx = p1 + rel;
        if idx + 1 < len
            && bytes[idx + 1] == b'd'
            && (idx == 0 || !is_java_word(bytes[idx - 1]))
            && (idx + 2 == len || !is_java_word(bytes[idx + 2]))
        {
            let line_end = memchr(b'\n', &bytes[idx + 2..]).map_or(len, |e| idx + 2 + e);
            let mut k = idx + 2;
            let mut matched = false;
            while k + 8 <= line_end {
                let Some(orel) = memchr(b'o', &bytes[k..line_end]) else {
                    break;
                };
                let oidx = k + orel;
                if oidx + 8 <= line_end
                    && &bytes[oidx..oidx + 8] == b"of=/dev/"
                    && !is_java_word(bytes[oidx - 1])
                {
                    matched = true;
                    break;
                }
                k = oidx + 1;
            }
            if matched {
                hits.push(DangerHit::Blacklist(1));
                break;
            }
            p1 = line_end;
            continue;
        }
        p1 = idx + 1;
    }

    // 4. SHELL_BLACKLIST[2]: \|\s*(sh|bash)\b
    let mut p2 = 0usize;
    while p2 < len {
        let Some(rel) = memchr(b'|', &bytes[p2..]) else {
            break;
        };
        let mut j = p2 + rel + 1;
        while j < len && is_java_ws(bytes[j]) {
            j += 1;
        }
        let is_sh = j + 2 <= len
            && &bytes[j..j + 2] == b"sh"
            && (j + 2 == len || !is_java_word(bytes[j + 2]));
        let is_bash = j + 4 <= len
            && &bytes[j..j + 4] == b"bash"
            && (j + 4 == len || !is_java_word(bytes[j + 4]));
        if is_sh || is_bash {
            hits.push(DangerHit::Blacklist(2));
            break;
        }
        p2 = p2 + rel + 1;
    }

    // 5. SHELL_BLACKLIST[3]: \bchmod\s+777\s+/
    let mut p3 = 0usize;
    while p3 + 5 <= len {
        let Some(rel) = memchr(b'c', &bytes[p3..]) else {
            break;
        };
        let idx = p3 + rel;
        if idx + 5 <= len
            && &bytes[idx..idx + 5] == b"chmod"
            && (idx == 0 || !is_java_word(bytes[idx - 1]))
        {
            let mut j = idx + 5;
            if j < len && is_java_ws(bytes[j]) {
                while j < len && is_java_ws(bytes[j]) {
                    j += 1;
                }
                if j + 3 <= len && &bytes[j..j + 3] == b"777" {
                    j += 3;
                    if j < len && is_java_ws(bytes[j]) {
                        while j < len && is_java_ws(bytes[j]) {
                            j += 1;
                        }
                        if j < len && bytes[j] == b'/' {
                            hits.push(DangerHit::Blacklist(3));
                            break;
                        }
                    }
                }
            }
        }
        p3 = idx + 1;
    }

    // 6. SHELL_BLACKLIST[4]: \$\([^)]*\$\([^)]*\)
    let mut p4 = 0usize;
    while p4 + 2 <= len {
        let Some(rel) = memchr(b'$', &bytes[p4..]) else {
            break;
        };
        let idx = p4 + rel;
        if idx + 1 < len && bytes[idx + 1] == b'(' {
            let mut k = idx + 2;
            let mut nested_at: Option<usize> = None;
            let mut found_close = false;
            while k < len {
                match bytes[k] {
                    b')' => {
                        if nested_at.is_some() {
                            found_close = true;
                        }
                        break;
                    }
                    b'$' if k + 1 < len && bytes[k + 1] == b'(' => {
                        if nested_at.is_none() {
                            nested_at = Some(k);
                        }
                        k += 2;
                    }
                    _ => k += 1,
                }
            }
            if found_close {
                hits.push(DangerHit::Blacklist(4));
                break;
            }
            // No ')' exists anywhere before EOF, or no inner '$(' existed before first ')'.
            if k >= len {
                break;
            }
            p4 = k + 1;
            continue;
        }
        p4 = idx + 1;
    }

    // 7. SHELL_BLACKLIST[5]: \brm\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\s+/(\s|$)
    let mut p5 = 0usize;
    while p5 < len {
        let Some(rel) = memchr(b'r', &bytes[p5..]) else {
            break;
        };
        let idx = p5 + rel;
        if let Some((_, j)) = match_rm_rf_at(bytes, idx)
            && j < len
            && bytes[j] == b'/'
            && (j + 1 == len || is_java_ws(bytes[j + 1]))
        {
            hits.push(DangerHit::Blacklist(5));
            break;
        }
        p5 = idx + 1;
    }

    hits
}

fn encode_danger_hits(command: &str) -> Result<Vec<u8>, i32> {
    let hits = scan_command_danger(command);
    if hits.is_empty() {
        return Ok(Vec::new());
    }
    let mut out = Vec::with_capacity(4 + hits.len() * 12);
    push_u32(&mut out, hits.len())?;
    for hit in hits {
        match hit {
            DangerHit::RmForce(target) => {
                out.push(0);
                push_u32(&mut out, target.len())?;
                out.extend_from_slice(target.as_bytes());
            }
            DangerHit::Blacklist(index) => {
                out.push(1 + index);
            }
        }
    }
    Ok(out)
}

#[inline]
fn next_template_var(bytes: &[u8], from: usize) -> Option<(usize, usize, usize, usize)> {
    let len = bytes.len();
    let mut pos = from;
    while pos + 5 <= len {
        let rel = memchr(b'{', &bytes[pos..])?;
        let idx = pos + rel;
        if idx + 1 >= len || bytes[idx + 1] != b'{' {
            pos = idx + 1;
            continue;
        }
        let mut k = idx + 2;
        while k < len && is_java_ws(bytes[k]) {
            k += 1;
        }
        if k >= len || !is_var_start(bytes[k]) {
            pos = if k > idx + 2 { k } else { idx + 1 };
            continue;
        }
        let name_start = k;
        k += 1;
        while k < len && is_var_cont(bytes[k]) {
            k += 1;
        }
        let name_end = k;
        while k < len && is_java_ws(bytes[k]) {
            k += 1;
        }
        if k + 1 < len && bytes[k] == b'}' && bytes[k + 1] == b'}' {
            return Some((idx, name_start, name_end, k + 2));
        }
        pos = if k > idx + 2 { k } else { idx + 1 };
    }
    None
}

fn encode_template_vars(text: &str) -> Result<Vec<u8>, i32> {
    let bytes = text.as_bytes();
    let mut names = Vec::new();
    let mut pos = 0usize;
    while let Some((_, n_start, n_end, m_end)) = next_template_var(bytes, pos) {
        names.push(&bytes[n_start..n_end]);
        pos = m_end;
    }
    if names.is_empty() {
        return Ok(Vec::new());
    }
    let mut out = Vec::with_capacity(4 + names.len() * 16);
    push_u32(&mut out, names.len())?;
    for name in names {
        push_u32(&mut out, name.len())?;
        out.extend_from_slice(name);
    }
    Ok(out)
}

#[derive(Default)]
struct JsonStringTracker {
    cursor: usize,
    in_string: bool,
}

impl JsonStringTracker {
    fn is_in_string_at(&mut self, bytes: &[u8], index: usize) -> bool {
        let limit = index.min(bytes.len());
        while self.cursor < limit {
            let Some(rel) = memchr2(b'"', b'\\', &bytes[self.cursor..limit]) else {
                self.cursor = limit;
                break;
            };
            let hit = self.cursor + rel;
            if bytes[hit] == b'\\' {
                self.cursor = hit + 2;
            } else {
                self.in_string = !self.in_string;
                self.cursor = hit + 1;
            }
        }
        self.in_string
    }
}

#[inline]
fn append_shell_quoted(out: &mut Vec<u8>, value: &str) {
    let bytes = value.as_bytes();
    out.push(b'\'');
    let mut start = 0usize;
    while let Some(rel) = memchr(b'\'', &bytes[start..]) {
        let idx = start + rel;
        out.extend_from_slice(&bytes[start..idx]);
        out.extend_from_slice(b"'\\''");
        start = idx + 1;
    }
    out.extend_from_slice(&bytes[start..]);
    out.push(b'\'');
}

#[inline]
fn append_header_safe(out: &mut Vec<u8>, value: &str) {
    let bytes = value.as_bytes();
    let mut start = 0usize;
    while let Some(rel) = memchr2(b'\r', b'\n', &bytes[start..]) {
        let idx = start + rel;
        out.extend_from_slice(&bytes[start..idx]);
        out.push(b' ');
        start = idx + 1;
    }
    out.extend_from_slice(&bytes[start..]);
}

#[inline]
fn append_json_escaped(out: &mut Vec<u8>, value: &str) {
    let bytes = value.as_bytes();
    let Some(first_special) = bytes
        .iter()
        .position(|&b| b == b'"' || b == b'\\' || b < b' ')
    else {
        out.extend_from_slice(bytes);
        return;
    };
    out.extend_from_slice(&bytes[..first_special]);
    for &b in &bytes[first_special..] {
        match b {
            b'"' => out.extend_from_slice(b"\\\""),
            b'\\' => out.extend_from_slice(b"\\\\"),
            b'\n' => out.extend_from_slice(b"\\n"),
            b'\r' => out.extend_from_slice(b"\\r"),
            b'\t' => out.extend_from_slice(b"\\t"),
            c if c < b' ' => {
                out.extend_from_slice(b"\\u00");
                out.push(HEX_LOWER[(c >> 4) as usize]);
                out.push(HEX_LOWER[(c & 0x0f) as usize]);
            }
            c => out.push(c),
        }
    }
}

#[inline]
fn append_url_encoded(out: &mut Vec<u8>, value: &str) {
    for &b in value.as_bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => out.push(b),
            b' ' => out.push(b'+'),
            other => {
                out.push(b'%');
                out.push(HEX_UPPER[(other >> 4) as usize]);
                out.push(HEX_UPPER[(other & 0x0f) as usize]);
            }
        }
    }
}

fn unpack_vars<'a>(packed: &'a [u8]) -> Result<HashMap<&'a str, &'a str>, i32> {
    if packed.is_empty() {
        return Ok(HashMap::new());
    }
    if packed.len() < 4 {
        return Err(STATUS_COMPATIBILITY);
    }
    let count = u32::from_le_bytes(packed[0..4].try_into().unwrap()) as usize;
    let mut off = 4usize;
    if count > packed.len() / 8 {
        return Err(STATUS_COMPATIBILITY);
    }
    let mut map = HashMap::with_capacity(count);
    for _ in 0..count {
        if off + 4 > packed.len() {
            return Err(STATUS_COMPATIBILITY);
        }
        let k_len = u32::from_le_bytes(packed[off..off + 4].try_into().unwrap()) as usize;
        off += 4;
        if packed.len() - off < k_len + 4 {
            return Err(STATUS_COMPATIBILITY);
        }
        let key =
            std::str::from_utf8(&packed[off..off + k_len]).map_err(|_| STATUS_COMPATIBILITY)?;
        off += k_len;
        let v_len = u32::from_le_bytes(packed[off..off + 4].try_into().unwrap()) as usize;
        off += 4;
        if packed.len() - off < v_len {
            return Err(STATUS_COMPATIBILITY);
        }
        let val =
            std::str::from_utf8(&packed[off..off + v_len]).map_err(|_| STATUS_COMPATIBILITY)?;
        off += v_len;
        map.insert(key, val);
    }
    if off != packed.len() {
        return Err(STATUS_COMPATIBILITY);
    }
    Ok(map)
}

fn render_template_internal<'a>(
    template: &str,
    vars: &HashMap<&'a str, &'a str>,
    mode: i32,
) -> Result<(String, Vec<String>), i32> {
    if !matches!(
        mode,
        MODE_RAW | MODE_SHELL_QUOTE | MODE_HEADER_SAFE | MODE_JSON_AWARE | MODE_URL_ENCODE
    ) {
        return Err(STATUS_COMPATIBILITY);
    }
    let bytes = template.as_bytes();
    if !template.contains("{{") {
        return Ok((template.to_owned(), Vec::new()));
    }
    let mut out = Vec::with_capacity(bytes.len());
    let mut missing: Vec<String> = Vec::new();
    let mut tracker = JsonStringTracker::default();
    let mut copied = 0usize;
    let mut pos = 0usize;

    while let Some((m_start, n_start, n_end, m_end)) = next_template_var(bytes, pos) {
        out.extend_from_slice(&bytes[copied..m_start]);
        let key = &template[n_start..n_end];
        if let Some(&val) = vars.get(key) {
            match mode {
                MODE_RAW => out.extend_from_slice(val.as_bytes()),
                MODE_SHELL_QUOTE => append_shell_quoted(&mut out, val),
                MODE_HEADER_SAFE => append_header_safe(&mut out, val),
                MODE_JSON_AWARE => {
                    if tracker.is_in_string_at(bytes, m_start) {
                        append_json_escaped(&mut out, val);
                    } else {
                        out.extend_from_slice(val.as_bytes());
                    }
                }
                MODE_URL_ENCODE => append_url_encoded(&mut out, val),
                _ => return Err(STATUS_COMPATIBILITY),
            }
        } else {
            if !missing.iter().any(|existing| existing == key) {
                missing.push(key.to_owned());
            }
            out.extend_from_slice(&bytes[m_start..m_end]);
        }
        copied = m_end;
        pos = m_end;
    }
    out.extend_from_slice(&bytes[copied..]);
    let rendered = String::from_utf8(out).map_err(|_| STATUS_COMPATIBILITY)?;
    Ok((rendered, missing))
}

fn encode_render_output(rendered: &str, missing: &[String]) -> Result<Vec<u8>, i32> {
    let missing_bytes: usize = missing.iter().map(|m| 4 + m.len()).sum();
    let mut out = Vec::with_capacity(8 + missing_bytes + rendered.len());
    push_u32(&mut out, missing.len())?;
    for key in missing {
        push_u32(&mut out, key.len())?;
        out.extend_from_slice(key.as_bytes());
    }
    push_u32(&mut out, rendered.len())?;
    out.extend_from_slice(rendered.as_bytes());
    Ok(out)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_plugin_scan_command(
    input: *const u16,
    len: usize,
    output: *mut SearchBuffer,
) -> i32 {
    if !init_output(output) {
        return STATUS_REQUEST_ERROR;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        let command = decode_utf16(input, len)?;
        let bytes = encode_danger_hits(&command)?;
        Ok::<i32, i32>(finish_bytes_output(bytes, output))
    })) {
        Ok(Ok(status)) => status,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_plugin_extract_template_vars(
    input: *const u16,
    len: usize,
    output: *mut SearchBuffer,
) -> i32 {
    if !init_output(output) {
        return STATUS_REQUEST_ERROR;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        let text = decode_utf16(input, len)?;
        let bytes = encode_template_vars(&text)?;
        Ok::<i32, i32>(finish_bytes_output(bytes, output))
    })) {
        Ok(Ok(status)) => status,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_plugin_render_template(
    template: *const u16,
    template_len: usize,
    packed_vars: *const u8,
    packed_vars_len: usize,
    mode: i32,
    output: *mut SearchBuffer,
) -> i32 {
    if !init_output(output) {
        return STATUS_REQUEST_ERROR;
    }
    if packed_vars_len > MAX_PACKED_VARS_BYTES || (packed_vars_len != 0 && packed_vars.is_null()) {
        return STATUS_COMPATIBILITY;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        let tmpl = decode_utf16(template, template_len)?;
        // SAFETY: Validated non-null when `packed_vars_len > 0` above for synchronous JNI call.
        let packed = if packed_vars_len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(packed_vars, packed_vars_len) }
        };
        let vars = unpack_vars(packed)?;
        let (rendered, missing) = render_template_internal(&tmpl, &vars, mode)?;
        let bytes = encode_render_output(&rendered, &missing)?;
        Ok::<i32, i32>(finish_bytes_output(bytes, output))
    })) {
        Ok(Ok(status)) => status,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn danger_scanner_matches_rm_force_and_safe_exemptions() {
        assert_eq!(scan_command_danger("rm -rf /sdcard/tmp && ls"), vec![]);
        assert_eq!(
            scan_command_danger("rm -rf /storage/emulated/0/foo"),
            vec![]
        );
        assert_eq!(scan_command_danger("rm -rf /data/local/tmp/cache"), vec![]);
        assert_eq!(
            scan_command_danger("rm -fr /etc"),
            vec![] // exact `-fr` does NOT match `r...f...` order
        );
        assert_eq!(
            scan_command_danger("rm -rf /etc/passwd; rm -rf /"),
            vec![
                DangerHit::RmForce("/etc/passwd"),
                DangerHit::RmForce("/"),
                DangerHit::Blacklist(5),
            ]
        );
        assert_eq!(
            scan_command_danger("rm -vfrf /data/app"),
            vec![DangerHit::RmForce("/data/app")]
        );
    }

    #[test]
    fn danger_scanner_matches_all_blacklist_rules() {
        assert_eq!(
            scan_command_danger("mkfs.ext4 /dev/sda1"),
            vec![DangerHit::Blacklist(0)]
        );
        assert_eq!(
            scan_command_danger("mkfs /dev/sda"),
            vec![DangerHit::Blacklist(0)]
        );
        assert_eq!(scan_command_danger("mymkfs /dev/sda"), vec![]);
        assert_eq!(
            scan_command_danger("dd if=/dev/zero of=/dev/block/boot"),
            vec![DangerHit::Blacklist(1)]
        );
        assert_eq!(scan_command_danger("dd\nof=/dev/null"), vec![]);
        assert_eq!(
            scan_command_danger("curl x | bash"),
            vec![DangerHit::Blacklist(2)]
        );
        assert_eq!(
            scan_command_danger("cat a | sh"),
            vec![DangerHit::Blacklist(2)]
        );
        assert_eq!(scan_command_danger("cat a | shfoo"), vec![]);
        assert_eq!(
            scan_command_danger("chmod 777 /tmp"),
            vec![DangerHit::Blacklist(3)]
        );
        assert_eq!(
            scan_command_danger("echo $(echo $(id))"),
            vec![DangerHit::Blacklist(4)]
        );
        assert_eq!(scan_command_danger("echo $(a) $(b)"), vec![]);
    }

    #[test]
    fn extract_vars_handles_adjacent_braces_and_spaces() {
        let text = "{{ env.TOKEN }} {{{name}}} {{_bad}} {{}} {{a.b_1}}";
        let bytes = encode_template_vars(text).unwrap();
        let count = u32::from_le_bytes(bytes[0..4].try_into().unwrap());
        let mut off = 4usize;
        let mut out = Vec::new();
        for _ in 0..count {
            let n = u32::from_le_bytes(bytes[off..off + 4].try_into().unwrap()) as usize;
            off += 4;
            out.push(
                std::str::from_utf8(&bytes[off..off + n])
                    .unwrap()
                    .to_owned(),
            );
            off += n;
        }
        assert_eq!(out, vec!["env.TOKEN", "name", "_bad", "a.b_1"]);
    }

    #[test]
    fn json_aware_render_tracks_escaped_quotes_and_controls() {
        let mut vars = HashMap::new();
        vars.insert("q", "她说\"hi\"\n\t\x01\\");
        vars.insert("n", "42");
        let tmpl = r#"{"a":"\"pre\"{{ q }}","b":{{n}},"miss":"{{nope}}{{nope}}"}"#;
        let (rendered, missing) = render_template_internal(tmpl, &vars, MODE_JSON_AWARE).unwrap();
        assert_eq!(
            rendered,
            r#"{"a":"\"pre\"她说\"hi\"\n\t\u0001\\","b":42,"miss":"{{nope}}{{nope}}"}"#
        );
        assert_eq!(missing, vec!["nope"]);
    }

    #[test]
    fn shell_header_and_url_modes_match_kotlin_semantics() {
        let mut vars = HashMap::new();
        vars.insert("arg", "a'b c\r\nd~!");
        let (sh, _) = render_template_internal("echo {{arg}}", &vars, MODE_SHELL_QUOTE).unwrap();
        assert_eq!(sh, "echo 'a'\\''b c\r\nd~!'");

        let (hdr, _) = render_template_internal("Bearer {{arg}}", &vars, MODE_HEADER_SAFE).unwrap();
        assert_eq!(hdr, "Bearer a'b c  d~!");

        let (url, _) =
            render_template_internal("https://x/?q={{arg}}", &vars, MODE_URL_ENCODE).unwrap();
        assert_eq!(url, "https://x/?q=a%27b+c%0D%0Ad%7E%21");
    }

    #[test]
    fn ffi_roundtrip_and_invalid_utf16_fallback() {
        let text: Vec<u16> = "curl x | bash".encode_utf16().collect();
        let mut out = SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        };
        let status = unsafe { sx_plugin_scan_command(text.as_ptr(), text.len(), &mut out) };
        assert_eq!(status, STATUS_OK);
        assert!(!out.data.is_null());
        unsafe { crate::sx_search_free(out.data, out.len) };

        let lone_surrogate = [0xD800u16];
        let status_bad =
            unsafe { sx_plugin_extract_template_vars(lone_surrogate.as_ptr(), 1, &mut out) };
        assert_eq!(status_bad, STATUS_COMPATIBILITY);
    }
}
