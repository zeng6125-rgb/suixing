//! Bounded HTML-to-text and entity decoding for the Rust frontend.
use crate::java_whitespace;

const COMPATIBILITY: i32 = 4;
const MAX_SOURCE: usize = 1_048_576;
const MAX_BYTES: usize = 16 * 1024 * 1024;
const MAX_OUTPUT: usize = MAX_BYTES / 2 - 32;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TextError {
    Limit,
    Compatibility,
}
/// Typed callers use the same lexical dialect as the legacy ABI. Unsupported
/// platform-sensitive cases remain explicit so callers can run a real fallback.
pub fn html_text(source: &str) -> Result<String, TextError> {
    typed(source, html)
}
pub fn decode_entities(source: &str) -> Result<String, TextError> {
    typed(source, entities)
}
fn typed(
    source: &str,
    operation: fn(&[u16]) -> Result<Vec<u16>, i32>,
) -> Result<String, TextError> {
    let count = source.encode_utf16().count();
    if count > MAX_SOURCE {
        return Err(TextError::Limit);
    }
    let source: Vec<u16> = source.encode_utf16().collect();
    let result = operation(&source).map_err(|_| TextError::Compatibility)?;
    Ok(String::from_utf16_lossy(&result))
}

fn append(out: &mut Vec<u16>, text: &[u16]) -> Result<(), i32> {
    if text.len() > MAX_OUTPUT - out.len() {
        return Err(COMPATIBILITY);
    }
    out.try_reserve(text.len()).map_err(|_| COMPATIBILITY)?;
    out.extend_from_slice(text);
    Ok(())
}
fn push(out: &mut Vec<u16>, unit: u16) -> Result<(), i32> {
    append(out, &[unit])
}
fn literal(out: &mut Vec<u16>, text: &str) -> Result<(), i32> {
    for unit in text.encode_utf16() {
        push(out, unit)?;
    }
    Ok(())
}
fn copy(text: &[u16]) -> Result<Vec<u16>, i32> {
    let mut out = Vec::new();
    append(&mut out, text)?;
    Ok(out)
}
fn at(text: &[u16], index: usize, pattern: &str, ignore_case: bool) -> bool {
    let Some(tail) = text.get(index..) else {
        return false;
    };
    if tail.len() < pattern.len() {
        return false;
    }
    tail.iter().zip(pattern.bytes()).all(|(&a, b)| {
        if ignore_case {
            a <= 0x7f && (a as u8).eq_ignore_ascii_case(&b)
        } else {
            a == u16::from(b)
        }
    })
}
fn find(text: &[u16], from: usize, pattern: &str, ignore_case: bool) -> Option<usize> {
    (from..text.len()).find(|&index| at(text, index, pattern, ignore_case))
}
fn whitespace(unit: u16) -> bool {
    char::from_u32(u32::from(unit)).is_some_and(java_whitespace)
}
fn trim(text: &[u16]) -> Result<&[u16], i32> {
    // Android ICU-sensitive classification must use the exact host implementation.
    if text
        .iter()
        .any(|&unit| unit == 0x180e || unit > 0x7f && whitespace(unit))
    {
        return Err(COMPATIBILITY);
    }
    let start = text
        .iter()
        .position(|&unit| !whitespace(unit))
        .unwrap_or(text.len());
    let end = text
        .iter()
        .rposition(|&unit| !whitespace(unit))
        .map_or(start, |index| index + 1);
    Ok(&text[start..end])
}
/// Replace the same non-overlapping regex matches, with ASCII case folding for markup.
fn strip_paired(
    source: &[u16],
    opening: &str,
    closing: &str,
    ignore_case: bool,
) -> Result<Vec<u16>, i32> {
    let mut out = Vec::new();
    let mut cursor = 0;
    let mut search = 0;
    while let Some(start) = find(source, search, opening, ignore_case) {
        let Some(open_end) = source[start + opening.len()..]
            .iter()
            .position(|&u| u == 62)
            .map(|offset| start + opening.len() + offset)
        else {
            break;
        };
        let Some(end) = find(source, open_end + 1, closing, ignore_case) else {
            break;
        };
        append(&mut out, &source[cursor..start])?;
        push(&mut out, 32)?;
        cursor = end + closing.len();
        search = cursor;
    }
    append(&mut out, &source[cursor..])?;
    Ok(out)
}
fn strip_comment(source: &[u16]) -> Result<Vec<u16>, i32> {
    let mut out = Vec::new();
    let mut cursor = 0;
    while let Some(start) = find(source, cursor, "<!--", false) {
        let Some(end) = find(source, start + 4, "-->", false) else {
            break;
        };
        append(&mut out, &source[cursor..start])?;
        push(&mut out, 32)?;
        cursor = end + 3;
    }
    append(&mut out, &source[cursor..])?;
    Ok(out)
}
fn replace_tags(
    source: &[u16],
    prefixes: &[&str],
    replacement: &[u16],
    generic: bool,
) -> Result<Vec<u16>, i32> {
    let mut out = Vec::new();
    let mut cursor = 0;
    let mut index = 0;
    while index < source.len() {
        if source[index] != 60
            || !generic
                && !prefixes
                    .iter()
                    .any(|prefix| at(source, index + 1, prefix, true))
        {
            index += 1;
            continue;
        }
        let Some(end) = source[index + 1..]
            .iter()
            .position(|&u| u == 62)
            .map(|offset| index + 1 + offset)
        else {
            break;
        };
        // The final generic regex is <[^>]+>, so <> itself is left untouched.
        if generic && end == index + 1 {
            index += 1;
            continue;
        }
        append(&mut out, &source[cursor..index])?;
        append(&mut out, replacement)?;
        cursor = end + 1;
        index = cursor;
    }
    append(&mut out, &source[cursor..])?;
    Ok(out)
}
fn ascii_letter(unit: u16) -> bool {
    (65..=90).contains(&unit) || (97..=122).contains(&unit)
}
fn ascii_digit(unit: u16) -> bool {
    (48..=57).contains(&unit)
}
fn hex(unit: u16) -> Option<i32> {
    match unit {
        48..=57 => Some(i32::from(unit - 48)),
        65..=70 => Some(i32::from(unit - 65 + 10)),
        97..=102 => Some(i32::from(unit - 97 + 10)),
        _ => None,
    }
}
fn number(text: &[u16], radix: i32) -> Option<i32> {
    let mut value = 0i32;
    for &unit in text {
        let digit = hex(unit)?;
        if digit >= radix {
            return None;
        }
        value = value.checked_mul(radix)?.checked_add(digit)?;
    }
    Some(value)
}
fn named(body: &[u16]) -> Option<&'static str> {
    const VALUES: &[(&str, &str)] = &[
        ("amp", "&"),
        ("lt", "<"),
        ("gt", ">"),
        ("quot", "\""),
        ("apos", "'"),
        ("nbsp", " "),
        ("mdash", "—"),
        ("ndash", "–"),
        ("hellip", "…"),
        ("ldquo", "“"),
        ("rdquo", "”"),
        ("lsquo", "‘"),
        ("rsquo", "’"),
        ("middot", "·"),
        ("times", "×"),
        ("divide", "÷"),
        ("copy", "©"),
        ("reg", "®"),
        ("trade", "™"),
        ("yen", "¥"),
        ("euro", "€"),
        ("pound", "£"),
        ("deg", "°"),
        ("plusmn", "±"),
        ("laquo", "«"),
        ("raquo", "»"),
        ("bull", "•"),
        ("dagger", "†"),
        ("sect", "§"),
        ("para", "¶"),
        ("micro", "µ"),
        ("prime", "′"),
    ];
    VALUES
        .iter()
        .find(|(key, _)| key.len() == body.len() && at(body, 0, key, true))
        .map(|(_, value)| *value)
}
fn entities(source: &[u16]) -> Result<Vec<u16>, i32> {
    let mut out = Vec::new();
    let mut cursor = 0;
    while cursor < source.len() {
        if source[cursor] != 38 {
            push(&mut out, source[cursor])?;
            cursor += 1;
            continue;
        }
        let start = cursor + 1;
        let mut end = start;
        let numeric = source.get(start) == Some(&35);
        if numeric {
            end += 1;
            if source.get(end) == Some(&120) {
                end += 1;
            }
            let digits = end;
            while source.get(end).is_some_and(|&unit| hex(unit).is_some()) {
                end += 1;
            }
            if end == digits {
                push(&mut out, 38)?;
                cursor += 1;
                continue;
            }
        } else {
            if !source.get(end).is_some_and(|&unit| ascii_letter(unit)) {
                push(&mut out, 38)?;
                cursor += 1;
                continue;
            }
            end += 1;
            while source
                .get(end)
                .is_some_and(|&unit| ascii_letter(unit) || ascii_digit(unit))
            {
                end += 1;
            }
        }
        if source.get(end) != Some(&59) {
            push(&mut out, 38)?;
            cursor += 1;
            continue;
        }
        let body = &source[start..end];
        if numeric {
            let is_hex = body.get(1) == Some(&120);
            if let Some(value) = number(
                &body[if is_hex { 2 } else { 1 }..],
                if is_hex { 16 } else { 10 },
            ) {
                // Character.toChars accepts a BMP surrogate, but rejects out-of-range code points.
                if value <= 0xffff {
                    push(&mut out, value as u16)?;
                } else if value <= 0x10ffff {
                    let scalar = value as u32 - 0x10000;
                    push(&mut out, (0xd800 + (scalar >> 10)) as u16)?;
                    push(&mut out, (0xdc00 + (scalar & 1023)) as u16)?;
                }
            } else {
                append(&mut out, &source[cursor..=end])?;
            }
        } else if let Some(value) = named(body) {
            literal(&mut out, value)?;
        } else {
            append(&mut out, &source[cursor..=end])?;
        }
        cursor = end + 1;
    }
    Ok(out)
}
fn html(source: &[u16]) -> Result<Vec<u16>, i32> {
    // ICU/Java Unicode folding of ASCII markup varies; the exact platform regex owns these cases.
    if source
        .iter()
        .any(|unit| matches!(*unit, 0x017f | 0x212a | 0x0130 | 0x0131))
    {
        return Err(COMPATIBILITY);
    }
    let mut text = strip_paired(source, "<script", "</script>", true)?;
    text = strip_paired(&text, "<style", "</style>", true)?;
    text = strip_paired(&text, "<noscript", "</noscript>", true)?;
    text = strip_comment(&text)?;
    text = replace_tags(&text, &["li"], &[10, 45, 32], false)?;
    text = replace_tags(
        &text,
        &[
            "br", "/p", "/div", "/li", "/tr", "/h1", "/h2", "/h3", "/h4", "/h5", "/h6", "/table",
            "/section", "/article", "/header", "/footer",
        ],
        &[10],
        false,
    )?;
    text = replace_tags(&text, &[], &[32], true)?;
    text = entities(&text)?;
    let mut spaces = Vec::new();
    let mut in_space = false;
    for unit in text {
        if matches!(unit, 32 | 9 | 0x00a0 | 0x3000) {
            if !in_space {
                push(&mut spaces, 32)?;
            }
            in_space = true;
        } else {
            push(&mut spaces, unit)?;
            in_space = false;
        }
    }
    let mut lines = Vec::new();
    for (index, &unit) in spaces.iter().enumerate() {
        if unit == 32 && (lines.last() == Some(&10) || spaces.get(index + 1) == Some(&10)) {
            continue;
        }
        push(&mut lines, unit)?;
    }
    let mut out = Vec::new();
    let mut blank = 0;
    for unit in lines {
        if unit == 10 {
            blank += 1;
            if blank <= 2 {
                push(&mut out, unit)?;
            }
        } else {
            blank = 0;
            push(&mut out, unit)?;
        }
    }
    copy(trim(&out)?)
}
