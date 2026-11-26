//! Bounded single-pass parser for `uiautomator dump` XML and `dumpsys window` focus lines.

use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer, java_whitespace};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;

const MAGIC: u32 = 0x3155_5853; // "SXU1"
const COMPATIBILITY: i32 = 4;
const MAX_UNITS: usize = 4 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 16 * 1024 * 1024;

#[derive(Debug, PartialEq, Eq)]
pub struct UiNode<'a> {
    pub cls: &'a [u16],
    pub text: Vec<u16>,
    pub desc: Vec<u16>,
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
    pub clickable: bool,
    pub scrollable: bool,
    pub editable: bool,
    pub id: &'a [u16],
}

/// # Safety
/// `xml` must reference `len` readable UTF-16 units and `output` must be writable for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_ui_dump_parse(
    xml: *const u16,
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
    if len > MAX_UNITS || (len != 0 && xml.is_null()) {
        return STATUS_REQUEST_ERROR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let units = if len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(xml, len) }
        };
        let bytes = parse_ui_dump_encoded(units)?;
        let owned = bytes.into_boxed_slice();
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
/// `dump` must reference `len` readable UTF-16 units and `output` must be writable for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_ui_focused_package(
    dump: *const u16,
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
    if len > MAX_UNITS || (len != 0 && dump.is_null()) {
        return STATUS_REQUEST_ERROR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let units = if len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(dump, len) }
        };
        let bytes = focused_package_encoded(units)?;
        let owned = bytes.into_boxed_slice();
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
fn is_java_word(unit: u16) -> bool {
    matches!(unit, 0x30..=0x39 | 0x41..=0x5a | 0x5f | 0x61..=0x7a)
}

#[inline]
fn starts_ascii(haystack: &[u16], at: usize, needle: &[u8]) -> bool {
    let Some(slice) = haystack.get(at..at + needle.len()) else {
        return false;
    };
    slice
        .iter()
        .zip(needle.iter())
        .all(|(&unit, &byte)| unit == u16::from(byte))
}

#[inline]
fn find_ascii(haystack: &[u16], mut at: usize, needle: &[u8]) -> Option<usize> {
    if needle.is_empty() {
        return Some(at);
    }
    let first = u16::from(needle[0]);
    let limit = haystack.len().checked_sub(needle.len())?;
    while at <= limit {
        if haystack[at] == first && starts_ascii(haystack, at, needle) {
            return Some(at);
        }
        at += 1;
    }
    None
}

fn find_word_attr(tag: &[u16], mut at: usize, prefix: &[u8]) -> Option<usize> {
    while let Some(pos) = find_ascii(tag, at, prefix) {
        if pos == 0 || !is_java_word(tag[pos - 1]) {
            return Some(pos);
        }
        at = pos + 1;
    }
    None
}

fn find_attr_value<'a>(tag: &'a [u16], attr_prefix: &[u8]) -> Option<&'a [u16]> {
    let mut search_from = 0usize;
    while let Some(pos) = find_word_attr(tag, search_from, attr_prefix) {
        let start = pos + attr_prefix.len();
        if let Some(rel_end) = tag[start..]
            .iter()
            .position(|&unit| unit == u16::from(b'"'))
        {
            return Some(&tag[start..start + rel_end]);
        }
        search_from = pos + 1;
    }
    None
}

fn has_word_literal(tag: &[u16], literal: &[u8]) -> bool {
    find_word_attr(tag, 0, literal).is_some()
}

fn has_editable_class(tag: &[u16]) -> bool {
    let mut search_from = 0usize;
    while let Some(pos) = find_word_attr(tag, search_from, b"class=\"") {
        let start = pos + 7;
        if let Some(rel_end) = tag[start..]
            .iter()
            .position(|&unit| unit == u16::from(b'"'))
        {
            let value = &tag[start..start + rel_end];
            if find_ascii(value, 0, b"EditText").is_some()
                || find_ascii(value, 0, b"AutoCompleteTextView").is_some()
            {
                return true;
            }
            search_from = start + rel_end + 1;
        } else {
            break;
        }
    }
    false
}

fn parse_signed_digits(slice: &[u16], at: &mut usize) -> Option<(bool, usize, usize)> {
    let mut idx = *at;
    if idx < slice.len() && slice[idx] == u16::from(b'-') {
        idx += 1;
    }
    let digit_start = idx;
    while idx < slice.len() && (u16::from(b'0')..=u16::from(b'9')).contains(&slice[idx]) {
        idx += 1;
    }
    if idx == digit_start {
        return None;
    }
    let end = idx;
    let start = *at;
    *at = idx;
    Some((true, start, end))
}

fn parse_i32_token(slice: &[u16]) -> Option<i32> {
    if slice.is_empty() {
        return None;
    }
    let (negative, digits) = if slice[0] == u16::from(b'-') {
        if slice.len() == 1 {
            return None;
        }
        (true, &slice[1..])
    } else {
        (false, slice)
    };
    let mut acc: i64 = 0;
    for &unit in digits {
        if !(u16::from(b'0')..=u16::from(b'9')).contains(&unit) {
            return None;
        }
        acc = acc * 10 + i64::from(unit - u16::from(b'0'));
        if !negative && acc > i32::MAX as i64 {
            return None;
        }
        if negative && -acc < i32::MIN as i64 {
            return None;
        }
    }
    if negative {
        Some((-acc) as i32)
    } else {
        Some(acc as i32)
    }
}

fn find_bounds(tag: &[u16]) -> Option<[i32; 4]> {
    let mut search_from = 0usize;
    let prefix = b"bounds=\"[";
    while let Some(pos) = find_word_attr(tag, search_from, prefix) {
        let mut cursor = pos + prefix.len();
        let Some((_, s1, e1)) = parse_signed_digits(tag, &mut cursor) else {
            search_from = pos + 1;
            continue;
        };
        if tag.get(cursor) != Some(&u16::from(b',')) {
            search_from = pos + 1;
            continue;
        }
        cursor += 1;
        let Some((_, s2, e2)) = parse_signed_digits(tag, &mut cursor) else {
            search_from = pos + 1;
            continue;
        };
        if !starts_ascii(tag, cursor, b"][") {
            search_from = pos + 1;
            continue;
        }
        cursor += 2;
        let Some((_, s3, e3)) = parse_signed_digits(tag, &mut cursor) else {
            search_from = pos + 1;
            continue;
        };
        if tag.get(cursor) != Some(&u16::from(b',')) {
            search_from = pos + 1;
            continue;
        }
        cursor += 1;
        let Some((_, s4, e4)) = parse_signed_digits(tag, &mut cursor) else {
            search_from = pos + 1;
            continue;
        };
        if !starts_ascii(tag, cursor, b"]\"") {
            search_from = pos + 1;
            continue;
        }
        // Match first regex hit, then mirror Kotlin's `toIntOrNull() ?: continue`.
        let left = parse_i32_token(&tag[s1..e1])?;
        let top = parse_i32_token(&tag[s2..e2])?;
        let right = parse_i32_token(&tag[s3..e3])?;
        let bottom = parse_i32_token(&tag[s4..e4])?;
        return Some([left, top, right, bottom]);
    }
    None
}

#[inline]
fn is_kotlin_whitespace_ascii(unit: u16) -> bool {
    matches!(unit, 0x09..=0x0d | 0x1c..=0x20)
}

fn check_trim_end_unit(unit: u16) -> Result<bool, i32> {
    if unit < 0x80 {
        return Ok(is_kotlin_whitespace_ascii(unit));
    }
    if unit == 0x0085 || unit == 0x180e || (0xd800..=0xdfff).contains(&unit) {
        return Err(COMPATIBILITY);
    }
    if let Some(ch) = char::from_u32(u32::from(unit)) {
        if java_whitespace(ch) || ch.is_whitespace() {
            return Ok(true);
        }
    }
    Ok(false)
}

fn trim_units(slice: &[u16]) -> Result<&[u16], i32> {
    let mut start = 0usize;
    let mut end = slice.len();
    while start < end && check_trim_end_unit(slice[start])? {
        start += 1;
    }
    while end > start && check_trim_end_unit(slice[end - 1])? {
        end -= 1;
    }
    Ok(&slice[start..end])
}

fn unescape_and_trim(raw: &[u16]) -> Result<Vec<u16>, i32> {
    let amp = u16::from(b'&');
    if !raw.contains(&amp) {
        return Ok(trim_units(raw)?.to_vec());
    }
    let mut out = Vec::with_capacity(raw.len());
    let mut i = 0usize;
    while i < raw.len() {
        let unit = raw[i];
        if unit != amp {
            out.push(unit);
            i += 1;
            continue;
        }
        if starts_ascii(raw, i, b"&lt;") {
            out.push(u16::from(b'<'));
            i += 4;
        } else if starts_ascii(raw, i, b"&gt;") {
            out.push(u16::from(b'>'));
            i += 4;
        } else if starts_ascii(raw, i, b"&quot;") {
            out.push(u16::from(b'"'));
            i += 6;
        } else if starts_ascii(raw, i, b"&apos;") {
            out.push(u16::from(b'\''));
            i += 6;
        } else if starts_ascii(raw, i, b"&#10;") {
            out.push(u16::from(b'\n'));
            i += 5;
        } else if starts_ascii(raw, i, b"&amp;") {
            out.push(u16::from(b'&'));
            i += 5;
        } else {
            out.push(unit);
            i += 1;
        }
    }
    let trimmed = trim_units(&out)?;
    Ok(trimmed.to_vec())
}

fn after_last(slice: &[u16], sep: u8) -> &[u16] {
    let target = u16::from(sep);
    match slice.iter().rposition(|&unit| unit == target) {
        Some(pos) => &slice[pos + 1..],
        None => slice,
    }
}

pub fn parse_ui_dump_units<'a>(units: &'a [u16]) -> Result<Vec<UiNode<'a>>, i32> {
    let mut nodes = Vec::with_capacity(64);
    let mut cursor = 0usize;
    let node_prefix = b"<node";
    let close_angle = u16::from(b'>');
    while let Some(pos) = find_ascii(units, cursor, node_prefix) {
        let after_name = pos + node_prefix.len();
        if after_name < units.len() && is_java_word(units[after_name]) {
            cursor = after_name;
            continue;
        }
        let Some(rel_end) = units[after_name..]
            .iter()
            .position(|&unit| unit == close_angle)
        else {
            break;
        };
        let tag_end = after_name + rel_end + 1;
        let tag = &units[pos..tag_end];
        cursor = tag_end;

        let Some([left, top, right, bottom]) = find_bounds(tag) else {
            continue;
        };
        if right <= left || bottom <= top {
            continue;
        }
        let text = match find_attr_value(tag, b"text=\"") {
            Some(raw) => unescape_and_trim(raw)?,
            None => Vec::new(),
        };
        let desc = match find_attr_value(tag, b"content-desc=\"") {
            Some(raw) => unescape_and_trim(raw)?,
            None => Vec::new(),
        };
        let clickable = has_word_literal(tag, b"clickable=\"true\"");
        let scrollable = has_word_literal(tag, b"scrollable=\"true\"");
        let editable = has_editable_class(tag);
        if text.is_empty() && desc.is_empty() && !clickable && !scrollable && !editable {
            continue;
        }
        let cls = find_attr_value(tag, b"class=\"")
            .map(|raw| after_last(raw, b'.'))
            .unwrap_or(&[]);
        let id = find_attr_value(tag, b"resource-id=\"")
            .map(|raw| after_last(raw, b'/'))
            .unwrap_or(&[]);

        nodes.push(UiNode {
            cls,
            text,
            desc,
            left,
            top,
            right,
            bottom,
            clickable,
            scrollable,
            editable,
            id,
        });
    }
    Ok(nodes)
}

fn push_u32(out: &mut Vec<u8>, value: u32) -> Result<(), i32> {
    if out.len() + 4 > MAX_OUTPUT_BYTES {
        return Err(COMPATIBILITY);
    }
    out.extend_from_slice(&value.to_le_bytes());
    Ok(())
}

fn push_u16_slice(out: &mut Vec<u8>, slice: &[u16]) -> Result<(), i32> {
    let needed = 4 + slice.len().saturating_mul(2);
    if out.len() + needed > MAX_OUTPUT_BYTES {
        return Err(COMPATIBILITY);
    }
    out.extend_from_slice(&(slice.len() as u32).to_le_bytes());
    for &unit in slice {
        out.extend_from_slice(&unit.to_le_bytes());
    }
    Ok(())
}

pub fn parse_ui_dump_encoded(units: &[u16]) -> Result<Vec<u8>, i32> {
    let nodes = parse_ui_dump_units(units)?;
    let approx = 8 + nodes
        .iter()
        .map(|node| 36 + (node.cls.len() + node.text.len() + node.desc.len() + node.id.len()) * 2)
        .sum::<usize>();
    if approx > MAX_OUTPUT_BYTES {
        return Err(COMPATIBILITY);
    }
    let mut out = Vec::with_capacity(approx);
    push_u32(&mut out, MAGIC)?;
    push_u32(&mut out, nodes.len() as u32)?;
    for node in nodes {
        push_u32(&mut out, node.left as u32)?;
        push_u32(&mut out, node.top as u32)?;
        push_u32(&mut out, node.right as u32)?;
        push_u32(&mut out, node.bottom as u32)?;
        let flags = (u32::from(node.clickable))
            | (u32::from(node.scrollable) << 1)
            | (u32::from(node.editable) << 2);
        push_u32(&mut out, flags)?;
        push_u16_slice(&mut out, node.cls)?;
        push_u16_slice(&mut out, &node.text)?;
        push_u16_slice(&mut out, &node.desc)?;
        push_u16_slice(&mut out, node.id)?;
    }
    Ok(out)
}

#[inline]
fn is_pkg_char(unit: u16) -> bool {
    matches!(
        unit,
        0x30..=0x39 | 0x41..=0x5a | 0x61..=0x7a | 0x5f | 0x2e
    )
}

#[inline]
fn is_component_char(unit: u16) -> bool {
    is_pkg_char(unit) || unit == u16::from(b'$')
}

pub fn focused_package_units(units: &[u16]) -> Option<&[u16]> {
    let cr = u16::from(b'\r');
    let lf = u16::from(b'\n');
    let slash = u16::from(b'/');
    let mut line_start = 0usize;
    let n = units.len();
    loop {
        let mut line_end = line_start;
        while line_end < n && units[line_end] != lf && units[line_end] != cr {
            line_end += 1;
        }
        let line = &units[line_start..line_end];
        if find_ascii(line, 0, b"mCurrentFocus").is_some()
            || find_ascii(line, 0, b"mFocusedApp").is_some()
        {
            let mut i = 0usize;
            while i < line.len() {
                if !is_pkg_char(line[i]) {
                    i += 1;
                    continue;
                }
                let pkg_start = i;
                while i < line.len() && is_pkg_char(line[i]) {
                    i += 1;
                }
                if i < line.len()
                    && line[i] == slash
                    && i + 1 < line.len()
                    && is_component_char(line[i + 1])
                {
                    return Some(&line[pkg_start..i]);
                }
            }
            return None;
        }
        if line_end >= n {
            break;
        }
        if units[line_end] == cr && line_end + 1 < n && units[line_end + 1] == lf {
            line_start = line_end + 2;
        } else {
            line_start = line_end + 1;
        }
        if line_start > n {
            break;
        }
    }
    None
}

pub fn focused_package_encoded(units: &[u16]) -> Result<Vec<u8>, i32> {
    let mut out = Vec::with_capacity(64);
    push_u32(&mut out, MAGIC)?;
    match focused_package_units(units) {
        Some(pkg) => {
            push_u32(&mut out, 1)?;
            push_u16_slice(&mut out, pkg)?;
        }
        None => {
            push_u32(&mut out, 0)?;
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    fn utf16(s: &str) -> Vec<u16> {
        s.encode_utf16().collect()
    }

    fn str_from_u16(units: &[u16]) -> String {
        String::from_utf16_lossy(units)
    }

    #[test]
    fn parses_interactive_nodes_entities_bounds_and_skips_empty() {
        let xml = r#"<hierarchy rotation="0">
            <nodex text="ignored" clickable="true" bounds="[0,0][10,10]" />
            <node class="android.widget.Button" text=" 确定 " bounds="[10,20][110,80]" clickable="true" scrollable="false" />
            <node class="android.view.View" bounds="[0,0][100,100]" clickable="false" scrollable="false" />
            <node class="android.widget.EditText" bounds="[-10,0][90,50]" resource-id="com.x:id/input_box" />
            <node class="android.widget.TextView" text="a &lt;b&gt; &amp;lt; &quot;c&quot;&#10;line2" content-desc="  desc&#10;  " bounds="[50,50][50,50]" clickable="true" />
            <node class="android.widget.TextView" text="a &lt;b&gt; &amp; &quot;c&quot; &apos;d&apos;&#10;" content-desc="  hi  " bounds="[0,0][10,10]" clickable="true" />
        </hierarchy>"#;
        let xml_units = utf16(xml);
        let nodes = parse_ui_dump_units(&xml_units).unwrap();
        assert_eq!(nodes.len(), 3);
        assert_eq!(str_from_u16(nodes[0].cls), "Button");
        assert_eq!(str_from_u16(&nodes[0].text), "确定");
        assert!(nodes[0].clickable);
        assert!(!nodes[0].scrollable);
        assert!(!nodes[0].editable);
        assert_eq!(
            (nodes[0].left, nodes[0].top, nodes[0].right, nodes[0].bottom),
            (10, 20, 110, 80)
        );

        assert_eq!(str_from_u16(nodes[1].cls), "EditText");
        assert!(nodes[1].editable);
        assert_eq!(str_from_u16(nodes[1].id), "input_box");
        assert_eq!(nodes[1].left, -10);

        assert_eq!(str_from_u16(&nodes[2].text), "a <b> & \"c\" 'd'");
        assert_eq!(str_from_u16(&nodes[2].desc), "hi");
    }

    #[test]
    fn word_boundary_and_attribute_order_match_regex_contract() {
        let xml = r#"<node mytext="wrong" mybounds="[0,0][1,1]" foo-text="picked" class="x.AutoCompleteTextView" bounds="[1,2][3,4]" scrollable="true" />"#;
        let xml_units = utf16(xml);
        let nodes = parse_ui_dump_units(&xml_units).unwrap();
        assert_eq!(nodes.len(), 1);
        assert_eq!(str_from_u16(&nodes[0].text), "picked");
        assert!(nodes[0].scrollable);
        assert!(nodes[0].editable);

        // Overflowing bounds integer skips node even if another bounds appears later in tag.
        let overflow = r#"<node text="ok" bounds="[999999999999,0][10,10]" bounds="[0,0][10,10]" clickable="true" />"#;
        let overflow_units = utf16(overflow);
        assert!(parse_ui_dump_units(&overflow_units).unwrap().is_empty());
    }

    #[test]
    fn focused_package_matches_first_focus_or_app_line() {
        let dump = "WINDOW MANAGER WINDOWS\n  mCurrentFocus=Window{2f1a3b u0 com.bilibili.app/com.bilibili.ui.MainActivity}\n  mFocusedApp=AppWindowToken{abc}";
        let pkg = focused_package_units(&utf16(dump)).map(str_from_u16);
        assert_eq!(pkg.as_deref(), Some("com.bilibili.app"));

        let none = "WINDOW MANAGER WINDOWS\n  nothing here";
        assert!(focused_package_units(&utf16(none)).is_none());
    }

    #[test]
    fn benchmark_ui_dump_200_nodes() {
        let mut xml = String::from("<hierarchy rotation=\"0\">");
        for i in 0..200 {
            xml.push_str(&format!(
                "<node index=\"{}\" text=\"Item &amp; Label {}\" resource-id=\"com.demo:id/item_{}\" class=\"android.widget.TextView\" package=\"com.demo\" content-desc=\"desc {}\" checkable=\"false\" checked=\"false\" clickable=\"{}\" enabled=\"true\" focusable=\"true\" focused=\"false\" scrollable=\"{}\" bounds=\"[0,{}][1080,{}]\" />",
                i, i, i, i, i % 2 == 0, i % 5 == 0, i * 20, i * 20 + 18
            ));
        }
        xml.push_str("</hierarchy>");
        let units = utf16(&xml);
        let start = Instant::now();
        let mut total = 0usize;
        for _ in 0..100 {
            let encoded = parse_ui_dump_encoded(&units).unwrap();
            total += encoded.len();
        }
        let elapsed = start.elapsed();
        assert!(total > 0);
        assert!(elapsed.as_secs_f64() < 2.0, "took {:?}", elapsed);
    }
}
