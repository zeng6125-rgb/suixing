//! Original WebTools lexical HTML dialect and bounded response charset decoding.
use regex::Regex;
use std::sync::LazyLock;
use suixing_search::text_parsers;

static HEADER_CHARSET: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?i)charset=[\"']?([A-Za-z0-9_-]+)"#).expect("constant charset regex")
});
static META_CHARSET: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?i)<meta[^>]+charset=[\"']?([A-Za-z0-9_-]+)"#).expect("constant meta regex")
});
static HTML_RULES: LazyLock<Vec<(Regex, &'static str)>> = LazyLock::new(|| {
    [
        (r"(?s)<[sS][cC][rR][iI][pP][tT][^>]*>.*?</[sS][cC][rR][iI][pP][tT]>", " "),
        (r"(?s)<[sS][tT][yY][lL][eE][^>]*>.*?</[sS][tT][yY][lL][eE]>", " "),
        (r"(?s)<[nN][oO][sS][cC][rR][iI][pP][tT][^>]*>.*?</[nN][oO][sS][cC][rR][iI][pP][tT]>", " "),
        (r"(?s)<!--.*?-->", " "),
        (r"<[lL][iI][^>]*>", "\n- "),
        (r"<([bB][rR]|/[pP]|/[dD][iI][vV]|/[lL][iI]|/[tT][rR]|/[hH][1-6]|/[tT][aA][bB][lL][eE]|/[sS][eE][cC][tT][iI][oO][nN]|/[aA][rR][tT][iI][cC][lL][eE]|/[hH][eE][aA][dD][eE][rR]|/[fF][oO][oO][tT][eE][rR])[^>]*>", "\n"),
        (r"(?s)<[^>]+>", " "),
    ].into_iter().map(|(pattern,replacement)| (Regex::new(pattern).expect("constant HTML regex"),replacement)).collect()
});
static SPACES: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("[ \\t\\u{00A0}\\u{3000}]+").expect("constant spaces regex"));
static LINE_SPACES: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(" *\\n *").expect("constant line spaces regex"));
static BLANKS: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("\\n{3,}").expect("constant blanks regex"));
static ENTITY: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"&(#x?[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]*);").expect("constant entities regex")
});

pub(super) fn html_to_text(source: &str) -> Result<String, String> {
    if source.len() > 4 * 1024 * 1024 + 4096 {
        return Err("网页解码文本超过处理预算".into());
    }
    if let Ok(text) = text_parsers::html_text(source) {
        return Ok(text);
    }
    // A shared scanner Compatibility/Limit result cannot fall back to raw HTML.
    // This mirrors the existing Kotlin regex path, including its prefix tags.
    let mut text = source.to_owned();
    for (regex, replacement) in HTML_RULES.iter() {
        text = regex.replace_all(&text, *replacement).into_owned();
    }
    text = entities(&text)?;
    text = SPACES.replace_all(&text, " ").into_owned();
    text = LINE_SPACES.replace_all(&text, "\n").into_owned();
    text = BLANKS.replace_all(&text, "\n\n").into_owned();
    Ok(text.trim_matches(kotlin_whitespace).to_owned())
}
fn kotlin_whitespace(ch: char) -> bool {
    matches!(ch,'\u{0009}'..='\u{000d}'|'\u{001c}'..='\u{0020}'|'\u{00a0}'|'\u{1680}'|'\u{2000}'..='\u{200a}'|'\u{2028}'|'\u{2029}'|'\u{202f}'|'\u{205f}'|'\u{3000}')
}
pub(super) fn entities(source: &str) -> Result<String, String> {
    if source.len() > 4 * 1024 * 1024 + 4096 {
        return Err("实体文本超过处理预算".into());
    }
    if let Ok(text) = text_parsers::decode_entities(source) {
        return Ok(text);
    }
    Ok(ENTITY
        .replace_all(source, |captures: &regex::Captures<'_>| {
            let body = &captures[1];
            if let Some(numeric) = body.strip_prefix("#x") {
                return i32::from_str_radix(numeric, 16)
                    .ok()
                    .map(|value| {
                        char::from_u32(value as u32)
                            .map(|ch| ch.to_string())
                            .unwrap_or_default()
                    })
                    .unwrap_or_else(|| captures[0].into());
            }
            if let Some(numeric) = body.strip_prefix('#') {
                return numeric
                    .parse::<i32>()
                    .ok()
                    .map(|value| {
                        char::from_u32(value as u32)
                            .map(|ch| ch.to_string())
                            .unwrap_or_default()
                    })
                    .unwrap_or_else(|| captures[0].into());
            }
            let replacement = match body.to_ascii_lowercase().as_str() {
                "amp" => "&",
                "lt" => "<",
                "gt" => ">",
                "quot" => "\"",
                "apos" => "'",
                "nbsp" => " ",
                "mdash" => "—",
                "ndash" => "–",
                "hellip" => "…",
                "ldquo" => "“",
                "rdquo" => "”",
                "lsquo" => "‘",
                "rsquo" => "’",
                "middot" => "·",
                "times" => "×",
                "divide" => "÷",
                "copy" => "©",
                "reg" => "®",
                "trade" => "™",
                "yen" => "¥",
                "euro" => "€",
                "pound" => "£",
                "deg" => "°",
                "plusmn" => "±",
                "laquo" => "«",
                "raquo" => "»",
                "bull" => "•",
                "dagger" => "†",
                "sect" => "§",
                "para" => "¶",
                "micro" => "µ",
                "prime" => "′",
                _ => return captures[0].into(),
            };
            replacement.into()
        })
        .into_owned())
}

#[derive(Debug)]
pub(super) struct Decoded {
    pub text: String,
    pub charset: String,
    pub replacement: bool,
}
pub(super) fn decode(bytes: &[u8], content_type: &str) -> Decoded {
    let declared = HEADER_CHARSET
        .captures(content_type)
        .map(|captures| captures[1].to_owned());
    let head: String = bytes
        .iter()
        .take(4096)
        .map(|&byte| char::from(byte))
        .collect();
    let sniffed = META_CHARSET
        .captures(&head)
        .map(|captures| captures[1].to_owned());
    let label = declared.or(sniffed).unwrap_or_else(|| "utf-8".into());
    let normalized = label.to_ascii_lowercase().replace(['_', '-'], "");
    match normalized.as_str() {
        "utf8" => utf8(bytes),
        "ascii" | "usascii" | "iso646us" | "ansix341968" => Decoded {
            text: bytes
                .iter()
                .map(|&byte| {
                    if byte < 128 {
                        char::from(byte)
                    } else {
                        '\u{fffd}'
                    }
                })
                .collect(),
            charset: "US-ASCII".into(),
            replacement: bytes.iter().any(|&byte| byte >= 128),
        },
        "iso88591" | "latin1" | "l1" | "isoir100" => Decoded {
            text: bytes.iter().map(|&byte| char::from(byte)).collect(),
            charset: "ISO-8859-1".into(),
            replacement: false,
        },
        "utf16" | "unicode" => {
            if bytes.starts_with(&[0xff, 0xfe]) {
                utf16(&bytes[2..], true, "UTF-16")
            } else if bytes.starts_with(&[0xfe, 0xff]) {
                utf16(&bytes[2..], false, "UTF-16")
            } else {
                utf16(bytes, false, "UTF-16")
            }
        }
        "utf16le" => utf16(bytes, true, "UTF-16LE"),
        "utf16be" => utf16(bytes, false, "UTF-16BE"),
        "utf32" => {
            if bytes.starts_with(&[0xff, 0xfe, 0, 0]) {
                utf32(&bytes[4..], true, "UTF-32")
            } else if bytes.starts_with(&[0, 0, 0xfe, 0xff]) {
                utf32(&bytes[4..], false, "UTF-32")
            } else {
                utf32(bytes, false, "UTF-32")
            }
        }
        "utf32le" => utf32(bytes, true, "UTF-32LE"),
        "utf32be" => utf32(bytes, false, "UTF-32BE"),
        _ => {
            if let Some(encoding) =
                encoding_rs::Encoding::for_label_no_replacement(label.as_bytes())
            {
                let (text, replacement) = encoding.decode_without_bom_handling(bytes);
                Decoded {
                    text: text.into_owned(),
                    charset: encoding.name().into(),
                    replacement,
                }
            } else {
                utf8(bytes)
            }
        }
    }
}
fn utf8(bytes: &[u8]) -> Decoded {
    Decoded {
        text: String::from_utf8_lossy(bytes).into_owned(),
        charset: "UTF-8".into(),
        replacement: std::str::from_utf8(bytes).is_err(),
    }
}
fn utf16(bytes: &[u8], little: bool, name: &str) -> Decoded {
    let units = bytes.chunks_exact(2).map(|part| {
        if little {
            u16::from_le_bytes([part[0], part[1]])
        } else {
            u16::from_be_bytes([part[0], part[1]])
        }
    });
    let mut replacement = bytes.len() % 2 != 0;
    let mut text = String::new();
    for scalar in char::decode_utf16(units) {
        match scalar {
            Ok(ch) => text.push(ch),
            Err(_) => {
                replacement = true;
                text.push('\u{fffd}');
            }
        }
    }
    if bytes.len() % 2 != 0 {
        text.push('\u{fffd}');
    }
    Decoded {
        text,
        charset: name.into(),
        replacement,
    }
}
fn utf32(bytes: &[u8], little: bool, name: &str) -> Decoded {
    let mut replacement = bytes.len() % 4 != 0;
    let mut text = String::new();
    for part in bytes.chunks_exact(4) {
        let array = [part[0], part[1], part[2], part[3]];
        let code = if little {
            u32::from_le_bytes(array)
        } else {
            u32::from_be_bytes(array)
        };
        if let Some(ch) = char::from_u32(code) {
            text.push(ch);
        } else {
            replacement = true;
            text.push('\u{fffd}');
        }
    }
    if bytes.len() % 4 != 0 {
        text.push('\u{fffd}');
    }
    Decoded {
        text,
        charset: name.into(),
        replacement,
    }
}
pub(super) fn utf16_prefix(text: &str, limit: usize) -> (&str, usize, bool) {
    let mut count = 0usize;
    let mut end = 0usize;
    for (offset, ch) in text.char_indices() {
        if count + ch.len_utf16() > limit {
            break;
        }
        count += ch.len_utf16();
        end = offset + ch.len_utf8();
    }
    let total = text.encode_utf16().count();
    (&text[..end], total, end < text.len())
}
pub(super) fn decode_form(value: &str) -> Result<String, ()> {
    let mut output = Vec::with_capacity(value.len());
    let bytes = value.as_bytes();
    let mut index = 0;
    while index < bytes.len() {
        match bytes[index] {
            b'+' => output.push(b' '),
            b'%' => {
                let high = bytes
                    .get(index + 1)
                    .and_then(|byte| char::from(*byte).to_digit(16))
                    .ok_or(())?;
                let low = bytes
                    .get(index + 2)
                    .and_then(|byte| char::from(*byte).to_digit(16))
                    .ok_or(())?;
                output.push((high * 16 + low) as u8);
                index += 2;
            }
            byte => output.push(byte),
        }
        index += 1;
    }
    Ok(String::from_utf8_lossy(&output).into_owned())
}
