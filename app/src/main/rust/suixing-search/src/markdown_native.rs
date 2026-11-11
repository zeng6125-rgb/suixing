//! The app's existing small Markdown dialect, batched for settled cold documents only.
use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer, check_cancelled, task};
use std::borrow::Cow;
use std::collections::{BTreeMap, HashSet};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;
use std::sync::atomic::AtomicBool;

const MAGIC: u32 = 0x314d_5853;
const COMPATIBILITY: i32 = 4;
const MAX_REQUEST: usize = 4 * 1024 * 1024;
const MAX_RESPONSE: usize = 16 * 1024 * 1024;
const MAX_DOCUMENT: usize = 512 * 1024;
const MAX_NODES: usize = 50_000;
const POLL: usize = 8192;

/// # Safety
/// Request bytes remain readable and output remains writable throughout this synchronous call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_markdown_parse(
    id: u64,
    request: *const u8,
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
        })
    };
    if request.is_null() || !(8..=MAX_REQUEST).contains(&len) {
        return STATUS_REQUEST_ERROR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let cancelled = task(id).ok_or(STATUS_REQUEST_ERROR)?;
        check_cancelled(&cancelled)?;
        // SAFETY: JNI retains this request until the synchronous call exits.
        let bytes = unsafe { slice::from_raw_parts(request, len) };
        let mut input = crate::Input { bytes, at: 0 };
        if input.u32() != Some(MAGIC) {
            return Err(STATUS_REQUEST_ERROR);
        }
        let count = input.u32().ok_or(STATUS_REQUEST_ERROR)? as usize;
        if !(1..=16).contains(&count) {
            return Err(STATUS_REQUEST_ERROR);
        }
        let mut response = Writer::new(MAX_RESPONSE);
        response.u32(MAGIC)?;
        response.u32(count as u32)?;
        for index in 0..count {
            check_cancelled(&cancelled)?;
            let text = input.string().ok_or(STATUS_REQUEST_ERROR)?;
            let parsed = if text.len() > MAX_DOCUMENT {
                Err(COMPATIBILITY)
            } else {
                parse_document(text, &cancelled)
            };
            match parsed {
                Ok(document)
                    if response.bytes.len() + 8 + document.len() + 4 * (count - index - 1)
                        <= MAX_RESPONSE =>
                {
                    response.u32(0)?;
                    response.u32(document.len() as u32)?;
                    response.extend(&document)?;
                }
                Ok(_) | Err(COMPATIBILITY) => response.u32(COMPATIBILITY as u32)?,
                Err(status) => return Err(status),
            }
        }
        if input.at != bytes.len() {
            return Err(STATUS_REQUEST_ERROR);
        }
        check_cancelled(&cancelled)?;
        let owned = response.bytes.into_boxed_slice();
        let len = owned.len();
        let data = Box::into_raw(owned) as *mut u8;
        unsafe { output.write(SearchBuffer { data, len }) };
        Ok(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

struct Writer {
    bytes: Vec<u8>,
    limit: usize,
}
impl Writer {
    fn new(limit: usize) -> Self {
        Self {
            bytes: Vec::new(),
            limit,
        }
    }
    fn extend(&mut self, value: &[u8]) -> Result<(), i32> {
        if self
            .bytes
            .len()
            .checked_add(value.len())
            .is_none_or(|n| n > self.limit)
        {
            return Err(COMPATIBILITY);
        }
        self.bytes.extend_from_slice(value);
        Ok(())
    }
    fn u32(&mut self, value: u32) -> Result<(), i32> {
        self.extend(&value.to_le_bytes())
    }
    fn string(&mut self, value: &str) -> Result<(), i32> {
        self.u32(value.len() as u32)?;
        self.extend(value.as_bytes())
    }
}

enum Block {
    Heading(u32, String),
    Paragraph(String),
    Bullet(Bullet),
    Code(String, String),
    Quote(String),
    Rule,
    Table(Vec<String>, Vec<Vec<String>>),
}
struct Bullet {
    items: Vec<String>,
    ordered: bool,
    depth: u32,
    start: u32,
    children: BTreeMap<usize, Vec<Bullet>>,
}
struct ListMarker<'a> {
    text: &'a str,
    ordered: bool,
    depth: u32,
    start: u32,
}
enum LineKind<'a> {
    Empty,
    Fence(&'a str),
    Heading(u32, &'a str),
    Rule,
    Quote,
    Table,
    List(ListMarker<'a>),
    Paragraph,
}

fn regex_space(byte: u8) -> bool {
    matches!(byte, b' ' | b'\t' | 0x0b | 0x0c)
}
fn trim(text: &str) -> &str {
    text.trim_matches(crate::java_whitespace)
}
fn trim_start(text: &str) -> &str {
    text.trim_start_matches(crate::java_whitespace)
}

fn fence(line: &str) -> Option<&str> {
    let bytes = line.as_bytes();
    let ticks = bytes.iter().take_while(|&&b| b == b'`').count();
    if ticks < 3 {
        return None;
    }
    let mut start = ticks;
    while start < bytes.len() && regex_space(bytes[start]) {
        start += 1;
    }
    if bytes[start..].iter().any(|&b| b == b'`' || regex_space(b)) {
        return None;
    }
    Some(&line[start..])
}
fn fence_close(line: &str) -> bool {
    let value = trim(line);
    value.len() >= 3 && value.bytes().all(|b| b == b'`')
}
fn heading(line: &str) -> Option<(u32, &str)> {
    let bytes = line.as_bytes();
    let count = bytes.iter().take_while(|&&b| b == b'#').count();
    if !(1..=6).contains(&count) || count >= bytes.len() || !regex_space(bytes[count]) {
        return None;
    }
    let mut at = count;
    while at < bytes.len() && regex_space(bytes[at]) {
        at += 1;
    }
    Some((count as u32, trim(&line[at..])))
}
fn rule(line: &str) -> bool {
    let mut marker = None;
    let mut count = 0;
    for b in line.bytes() {
        if regex_space(b) {
            continue;
        }
        if !matches!(b, b'-' | b'*' | b'_') || marker.is_some_and(|m| m != b) {
            return false;
        }
        marker = Some(b);
        count += 1;
    }
    count >= 3
}
fn list_marker(line: &str) -> Option<ListMarker<'_>> {
    let bytes = line.as_bytes();
    let mut at = 0;
    let mut spaces = 0;
    while at < bytes.len() && regex_space(bytes[at]) {
        spaces += if bytes[at] == b'\t' { 2 } else { 1 };
        at += 1;
    }
    let marker_at = at;
    let (ordered, start) = if bytes
        .get(at)
        .is_some_and(|&b| matches!(b, b'-' | b'*' | b'+'))
    {
        at += 1;
        (false, 1)
    } else {
        while at < bytes.len() && bytes[at].is_ascii_digit() {
            at += 1;
        }
        if at == marker_at || !bytes.get(at).is_some_and(|&b| matches!(b, b'.' | b')')) {
            return None;
        }
        let number = line[marker_at..at]
            .parse::<i32>()
            .unwrap_or(1)
            .clamp(0, 9999) as u32;
        at += 1;
        (true, number)
    };
    if !bytes.get(at).is_some_and(|&b| regex_space(b)) {
        return None;
    }
    while at < bytes.len() && regex_space(bytes[at]) {
        at += 1;
    }
    Some(ListMarker {
        text: &line[at..],
        ordered,
        depth: (spaces / 2).min(4),
        start,
    })
}
fn table_cells(line: &str) -> Vec<String> {
    let value = trim(line);
    let mut start = 0;
    let mut end = value.len();
    if value.starts_with('|') {
        start += 1;
    }
    if end > start && value.ends_with('|') && !value.ends_with("\\|") {
        end -= 1;
    }
    let bytes = value.as_bytes();
    let mut cells = Vec::new();
    let mut unescaped = String::new();
    let mut cell_start = start;
    let mut segment_start = start;
    let mut at = start;
    loop {
        if at + 1 < end && bytes[at] == b'\\' && bytes[at + 1] == b'|' {
            unescaped.push_str(&value[segment_start..at]);
            unescaped.push('|');
            at += 2;
            segment_start = at;
            continue;
        }
        if at == end || bytes[at] == b'|' {
            let cell = if unescaped.is_empty() {
                trim(&value[cell_start..at]).to_owned()
            } else {
                unescaped.push_str(&value[segment_start..at]);
                let cell = trim(&unescaped).to_owned();
                unescaped.clear();
                cell
            };
            cells.push(cell);
            if at == end {
                break;
            }
            cell_start = at + 1;
            segment_start = cell_start;
        }
        at += 1;
    }
    cells
}
fn table_start(lines: &[&str], index: usize) -> bool {
    if !lines[index].contains('|') {
        return false;
    }
    let Some(separator) = lines.get(index + 1).map(|line| trim(line)) else {
        return false;
    };
    if !separator.contains('-') || !separator.contains('|') {
        return false;
    }
    let cells = table_cells(separator);
    let valid = cells.iter().all(|cell| {
        let value = cell.strip_prefix(':').unwrap_or(cell);
        let value = value.strip_suffix(':').unwrap_or(value);
        !value.is_empty() && value.bytes().all(|b| b == b'-')
    });
    valid && cells.len() == table_cells(lines[index]).len()
}
fn line_kind<'a>(lines: &[&'a str], index: usize) -> LineKind<'a> {
    let value = trim(lines[index]);
    if value.is_empty() {
        return LineKind::Empty;
    }
    if let Some(language) = fence(value) {
        return LineKind::Fence(language);
    }
    if let Some((level, text)) = heading(value) {
        return LineKind::Heading(level, text);
    }
    if rule(value) {
        return LineKind::Rule;
    }
    if value.starts_with('>') {
        return LineKind::Quote;
    }
    if table_start(lines, index) {
        return LineKind::Table;
    }
    if let Some(marker) = list_marker(lines[index]) {
        return LineKind::List(marker);
    }
    LineKind::Paragraph
}

fn parse_list(
    lines: &[&str],
    index: &mut usize,
    depth: u32,
    ordered: bool,
    cancelled: &AtomicBool,
) -> Result<Bullet, i32> {
    let LineKind::List(first) = line_kind(lines, *index) else {
        return Err(COMPATIBILITY);
    };
    let mut result = Bullet {
        items: Vec::new(),
        ordered,
        depth,
        start: if ordered { first.start } else { 1 },
        children: BTreeMap::new(),
    };
    while *index < lines.len() {
        check_cancelled(cancelled)?;
        let LineKind::List(marker) = line_kind(lines, *index) else {
            break;
        };
        if marker.depth < depth {
            break;
        }
        if marker.depth > depth && !result.items.is_empty() {
            let child = parse_list(lines, index, marker.depth, marker.ordered, cancelled)?;
            result
                .children
                .entry(result.items.len() - 1)
                .or_default()
                .push(child);
            continue;
        }
        if marker.depth != depth || marker.ordered != ordered {
            break;
        }
        result.items.push(marker.text.to_owned());
        *index += 1;
    }
    Ok(result)
}

fn parse_blocks(text: &str, cancelled: &AtomicBool) -> Result<Vec<Block>, i32> {
    let lines: Vec<_> = text.split('\n').collect();
    let mut result = Vec::new();
    let mut index = 0;
    while index < lines.len() {
        check_cancelled(cancelled)?;
        if result.len() >= MAX_NODES {
            return Err(COMPATIBILITY);
        }
        match line_kind(&lines, index) {
            LineKind::Empty => index += 1,
            LineKind::Fence(language) => {
                index += 1;
                let start = index;
                while index < lines.len() && !fence_close(lines[index]) {
                    check_cancelled(cancelled)?;
                    index += 1;
                }
                let code = lines[start..index].join("\n");
                if index < lines.len() {
                    index += 1;
                }
                if !trim(&code).is_empty() {
                    result.push(Block::Code(language.to_owned(), code));
                }
            }
            LineKind::Heading(level, value) => {
                result.push(Block::Heading(level, value.to_owned()));
                index += 1;
            }
            LineKind::Rule => {
                result.push(Block::Rule);
                index += 1;
            }
            LineKind::Quote => {
                let mut quoted = Vec::new();
                while index < lines.len() && trim_start(lines[index]).starts_with('>') {
                    check_cancelled(cancelled)?;
                    let value = trim_start(lines[index]).strip_prefix('>').unwrap();
                    quoted.push(value.strip_prefix(' ').unwrap_or(value));
                    index += 1;
                }
                result.push(Block::Quote(quoted.join("\n")));
            }
            LineKind::Table => {
                let header = table_cells(lines[index]);
                let mut rows = Vec::new();
                index += 2;
                while index < lines.len() {
                    check_cancelled(cancelled)?;
                    let value = trim(lines[index]);
                    if value.is_empty() || !value.contains('|') {
                        break;
                    }
                    let mut cells = table_cells(value);
                    cells.resize(header.len(), String::new());
                    rows.push(cells);
                    index += 1;
                }
                result.push(Block::Table(header, rows));
            }
            LineKind::List(marker) => result.push(Block::Bullet(parse_list(
                &lines,
                &mut index,
                marker.depth,
                marker.ordered,
                cancelled,
            )?)),
            LineKind::Paragraph => {
                let mut paragraph = Vec::new();
                while index < lines.len() && matches!(line_kind(&lines, index), LineKind::Paragraph)
                {
                    check_cancelled(cancelled)?;
                    paragraph.push(trim(lines[index]));
                    index += 1;
                }
                result.push(Block::Paragraph(paragraph.join("\n")));
            }
        }
    }
    Ok(result)
}

fn write_strings(writer: &mut Writer, values: &[String]) -> Result<(), i32> {
    if values.len() > MAX_NODES {
        return Err(COMPATIBILITY);
    }
    writer.u32(values.len() as u32)?;
    for value in values {
        writer.string(value)?;
    }
    Ok(())
}
fn write_bullet<'a>(
    writer: &mut Writer,
    value: &'a Bullet,
    leaves: &mut Vec<&'a str>,
    nodes: &mut usize,
    cancelled: &AtomicBool,
) -> Result<(), i32> {
    check_cancelled(cancelled)?;
    *nodes += 1;
    if *nodes > MAX_NODES {
        return Err(COMPATIBILITY);
    }
    writer.u32(3)?;
    writer.u32(u32::from(value.ordered))?;
    writer.u32(value.depth)?;
    writer.u32(value.start)?;
    write_strings(writer, &value.items)?;
    leaves.extend(value.items.iter().map(String::as_str));
    writer.u32(value.children.len() as u32)?;
    for (&index, children) in &value.children {
        writer.u32(index as u32)?;
        writer.u32(children.len() as u32)?;
        for child in children {
            write_bullet(writer, child, leaves, nodes, cancelled)?;
        }
    }
    Ok(())
}
fn write_blocks<'a>(
    writer: &mut Writer,
    blocks: &'a [Block],
    leaves: &mut Vec<&'a str>,
    cancelled: &AtomicBool,
) -> Result<(), i32> {
    writer.u32(blocks.len() as u32)?;
    let mut nodes = 0;
    for block in blocks {
        check_cancelled(cancelled)?;
        match block {
            Block::Bullet(value) => {
                write_bullet(writer, value, leaves, &mut nodes, cancelled)?;
                continue;
            }
            Block::Heading(level, text) => {
                writer.u32(1)?;
                writer.u32(*level)?;
                writer.string(text)?;
                leaves.push(text);
            }
            Block::Paragraph(text) => {
                writer.u32(2)?;
                writer.string(text)?;
                leaves.push(text);
            }
            Block::Code(language, code) => {
                writer.u32(4)?;
                writer.string(language)?;
                writer.string(code)?;
            }
            Block::Quote(text) => {
                writer.u32(5)?;
                writer.string(text)?;
                leaves.push(text);
            }
            Block::Rule => writer.u32(6)?,
            Block::Table(header, rows) => {
                if rows.len() > MAX_NODES {
                    return Err(COMPATIBILITY);
                }
                writer.u32(7)?;
                write_strings(writer, header)?;
                writer.u32(rows.len() as u32)?;
                leaves.extend(header.iter().map(String::as_str));
                for row in rows {
                    check_cancelled(cancelled)?;
                    write_strings(writer, row)?;
                    leaves.extend(row.iter().map(String::as_str));
                }
            }
        }
        nodes += 1;
        if nodes > MAX_NODES {
            return Err(COMPATIBILITY);
        }
    }
    Ok(())
}

fn parse_document(text: &str, cancelled: &AtomicBool) -> Result<Vec<u8>, i32> {
    for (index, ch) in text.char_indices() {
        if index % POLL == 0 {
            check_cancelled(cancelled)?;
        }
        // Preserve Java/ICU regex behavior via compatibility fallback for platform-sensitive
        // whitespace/digit classes and line separators. CJK letters and emoji remain supported.
        if (!ch.is_ascii() && (crate::java_whitespace(ch) || ch.is_numeric()))
            || matches!(ch, '\u{0085}' | '\u{2028}' | '\u{2029}')
        {
            return Err(COMPATIBILITY);
        }
    }
    let normalized = if text.contains('\r') {
        Cow::Owned(text.replace("\r\n", "\n").replace('\r', "\n"))
    } else {
        Cow::Borrowed(text)
    };
    let blocks = parse_blocks(&normalized, cancelled)?;
    let mut writer = Writer::new(MAX_RESPONSE - 136);
    let mut leaves = Vec::new();
    write_blocks(&mut writer, &blocks, &mut leaves, cancelled)?;
    let mut seen = HashSet::new();
    leaves.retain(|leaf| {
        leaf.bytes()
            .any(|b| matches!(b, b'\\' | b'`' | b'*' | b'_' | b'~' | b'[' | b'/'))
            && seen.insert(*leaf)
    });
    if leaves.len() > MAX_NODES {
        return Err(COMPATIBILITY);
    }
    writer.u32(leaves.len() as u32)?;
    for text in leaves {
        check_cancelled(cancelled)?;
        let runs = InlineParser::new(text, cancelled).parse()?;
        writer.string(text)?;
        writer.u32(runs.len() as u32)?;
        for run in runs {
            writer.string(&run.text)?;
            writer.u32(run.style)?;
            writer.string(run.link.as_deref().unwrap_or(""))?;
        }
    }
    Ok(writer.bytes)
}

struct InlineRun {
    text: String,
    style: u32,
    link: Option<String>,
}
struct InlineParser<'a> {
    text: Vec<u16>,
    runs: Vec<InlineRun>,
    failed: [Option<(usize, usize)>; 6],
    cancelled: &'a AtomicBool,
}
const ROOTS: [&str; 6] = [
    "/sdcard/",
    "/storage/emulated/",
    "/storage/self/primary/",
    "/data/data/",
    "/data/local/tmp/",
    "/data/user/",
];
fn unit_space(unit: u16) -> bool {
    char::from_u32(unit as u32).is_some_and(crate::java_whitespace)
}
fn unit_ascii_word(unit: u16) -> bool {
    matches!(unit, 0x41..=0x5a | 0x61..=0x7a | 0x30..=0x39 | 0x5f)
}

impl<'a> InlineParser<'a> {
    fn new(text: &str, cancelled: &'a AtomicBool) -> Self {
        Self {
            text: text.encode_utf16().collect(),
            runs: Vec::new(),
            failed: [None; 6],
            cancelled,
        }
    }
    fn parse(mut self) -> Result<Vec<InlineRun>, i32> {
        self.emit(0, self.text.len(), 0, None, 0)?;
        Ok(self.runs)
    }
    fn add(&mut self, units: &[u16], style: u32, link: Option<&str>) -> Result<(), i32> {
        if units.is_empty() {
            return Ok(());
        }
        if self.runs.len() >= MAX_NODES {
            return Err(COMPATIBILITY);
        }
        let text = String::from_utf16(units).map_err(|_| COMPATIBILITY)?;
        self.runs.push(InlineRun {
            text,
            style,
            link: link.map(str::to_owned),
        });
        Ok(())
    }
    fn closing(
        &mut self,
        marker: u16,
        width: usize,
        from: usize,
        to: usize,
    ) -> Result<Option<usize>, i32> {
        let slot = match (marker, width) {
            (96, _) => 0,
            (42, 1) => 1,
            (42, _) => 2,
            (95, 1) => 3,
            (95, _) => 4,
            (126, _) => 5,
            _ => return Err(COMPATIBILITY),
        };
        if let Some((failed_from, failed_to)) = self.failed[slot] {
            if to == failed_to
                && from >= failed_from
                && !(from > failed_from && from > 0 && self.text[from - 1] == 92)
            {
                return Ok(None);
            }
        }
        let mut at = from;
        while at < to {
            if at % POLL == 0 {
                check_cancelled(self.cancelled)?;
            }
            if self.text[at] == 92 && at + 1 < to {
                at += 2;
                continue;
            }
            if self.text[at] == marker {
                let mut end = at + 1;
                while end < to && self.text[end] == marker {
                    if end % POLL == 0 {
                        check_cancelled(self.cancelled)?;
                    }
                    end += 1;
                }
                let available = end - at;
                let has_width = if width == 1 {
                    available == 1
                } else {
                    available >= width
                };
                if (at == 0 || self.text[at - 1] != marker) && has_width {
                    return Ok(Some(at));
                }
                at = end;
            } else {
                at += 1;
            }
        }
        if self.failed[slot].is_none_or(|(previous, _)| from < previous) {
            self.failed[slot] = Some((from, to));
        }
        Ok(None)
    }
    fn starts(&self, at: usize, to: usize, value: &str) -> bool {
        let bytes = value.as_bytes();
        at + bytes.len() <= to
            && bytes
                .iter()
                .enumerate()
                .all(|(i, &b)| self.text[at + i] == b as u16)
    }
    fn starts_url(&self, at: usize, to: usize) -> bool {
        (at == 0 || !unit_ascii_word(self.text[at - 1]))
            && (self.starts(at, to, "https://") || self.starts(at, to, "http://"))
    }
    fn starts_path(&self, at: usize, to: usize) -> bool {
        (at == 0 || (!unit_ascii_word(self.text[at - 1]) && self.text[at - 1] != 47))
            && ROOTS.iter().any(|root| self.starts(at, to, root))
    }
    fn target_end(&self, from: usize, to: usize, path: bool) -> Result<usize, i32> {
        let mut end = from;
        let (mut opens, mut closes) = (0, 0);
        while end < to {
            if end % POLL == 0 {
                check_cancelled(self.cancelled)?;
            }
            let c = self.text[end];
            if unit_space(c)
                || matches!(c, 34 | 60 | 62 | 96)
                || (path
                    && matches!(
                        c,
                        0xff0c
                            | 0x3002
                            | 0xff1b
                            | 0xff1a
                            | 0x3001
                            | 0xff09
                            | 0x300b
                            | 0x3011
                            | 0x300d
                    ))
            {
                break;
            }
            if c == 40 {
                opens += 1;
            }
            if c == 41 {
                closes += 1;
            }
            end += 1;
        }
        while end > from {
            if end % POLL == 0 {
                check_cancelled(self.cancelled)?;
            }
            let c = self.text[end - 1];
            if matches!(c, 46 | 44 | 59 | 58 | 33 | 63 | 42 | 95 | 126 | 39 | 34)
                || (path && c == 47)
            {
                end -= 1;
                continue;
            }
            if c == 41 && closes > opens {
                closes -= 1;
                end -= 1;
                continue;
            }
            break;
        }
        Ok(end)
    }
    fn link(&self, start: usize, to: usize) -> Result<Option<(usize, usize, String, usize)>, i32> {
        let mut cursor = start + 1;
        while cursor < to {
            if cursor % POLL == 0 {
                check_cancelled(self.cancelled)?;
            }
            if self.text[cursor] == 92 && cursor + 1 < to {
                cursor += 2;
                continue;
            }
            if self.text[cursor] == 91 {
                return Ok(None);
            }
            if self.text[cursor] == 93 {
                break;
            }
            cursor += 1;
        }
        if cursor >= to || cursor == start + 1 || cursor + 1 >= to || self.text[cursor + 1] != 40 {
            return Ok(None);
        }
        let mut end = cursor + 2;
        while end < to {
            if end % POLL == 0 {
                check_cancelled(self.cancelled)?;
            }
            if self.text[end] == 41 {
                break;
            }
            if self.text[end] == 92 && end + 1 < to {
                end += 1;
            }
            end += 1;
        }
        if end >= to {
            return Ok(None);
        }
        let target = String::from_utf16(&self.text[cursor + 2..end]).map_err(|_| COMPATIBILITY)?;
        let target = trim(&target);
        if target.is_empty() {
            return Ok(None);
        }
        Ok(Some((start + 1, cursor, target.to_owned(), end + 1)))
    }
    fn emit(
        &mut self,
        from: usize,
        to: usize,
        style: u32,
        link: Option<&str>,
        depth: usize,
    ) -> Result<(), i32> {
        check_cancelled(self.cancelled)?;
        if depth > 32 {
            return Err(COMPATIBILITY);
        }
        let mut literal = Vec::new();
        let mut at = from;
        while at < to {
            if at % POLL == 0 {
                check_cancelled(self.cancelled)?;
            }
            let c = self.text[at];
            if c == 92
                && at + 1 < to
                && matches!(
                    self.text[at + 1],
                    42 | 95 | 96 | 126 | 91 | 93 | 40 | 41 | 92
                )
            {
                literal.push(self.text[at + 1]);
                at += 2;
                continue;
            }
            if c == 96 {
                if let Some(close) = self.closing(c, 1, at + 1, to)? {
                    self.add(&literal, style, link)?;
                    literal.clear();
                    let mut code = Vec::with_capacity(close - at + 1);
                    code.push(0x2009);
                    code.extend_from_slice(&self.text[at + 1..close]);
                    code.push(0x2009);
                    self.add(&code, style | 8, link)?;
                    at = close + 1;
                    continue;
                }
            } else if c == 42 || c == 95 || (c == 126 && at + 1 < to && self.text[at + 1] == 126) {
                let width = if at + 1 < to && self.text[at + 1] == c {
                    2
                } else {
                    1
                };
                if let Some(close) = self.closing(c, width, at + width, to)? {
                    self.add(&literal, style, link)?;
                    literal.clear();
                    let inner = if c == 126 {
                        (style | 4) & !16
                    } else {
                        style | if width == 2 { 1 } else { 2 }
                    };
                    self.emit(at + width, close, inner, link, depth + 1)?;
                    at = close + width;
                    continue;
                }
                literal.extend_from_slice(&self.text[at..at + width]);
                at += width;
                continue;
            } else if c == 91 {
                if let Some((start, end, target, next)) = self.link(at, to)? {
                    self.add(&literal, style, link)?;
                    literal.clear();
                    let path = ROOTS.iter().any(|root| target.starts_with(root));
                    let styled = if path {
                        style | 32
                    } else {
                        (style | 16 | 32) & !4
                    };
                    let target = if path {
                        format!("file:{target}")
                    } else {
                        target
                    };
                    self.emit(start, end, styled, Some(&target), depth + 1)?;
                    at = next;
                    continue;
                }
            }
            if link.is_none()
                && ((c == 104 && self.starts_url(at, to)) || (c == 47 && self.starts_path(at, to)))
            {
                let path = c == 47;
                let end = self.target_end(at, to, path)?;
                if end > at {
                    self.add(&literal, style, link)?;
                    literal.clear();
                    let units = self.text[at..end].to_vec();
                    let target = String::from_utf16(&units).map_err(|_| COMPATIBILITY)?;
                    let target = if path {
                        format!("file:{target}")
                    } else {
                        target
                    };
                    let styled = if path {
                        style | 32
                    } else {
                        (style | 16 | 32) & !4
                    };
                    self.add(&units, styled, Some(&target))?;
                    at = end;
                    continue;
                }
            }
            literal.push(c);
            at += 1;
        }
        self.add(&literal, style, link)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strings<'a>(input: &mut crate::Input<'a>) -> Vec<&'a str> {
        (0..input.u32().unwrap())
            .map(|_| input.string().unwrap())
            .collect()
    }

    #[test]
    fn escaped_pipes_keep_the_command_in_one_table_cell() {
        let source =
            "| command | detail |\n| --- | --- |\n| `ls /sdcard/Download \\| head` | files |";
        let blocks = parse_blocks(source, &AtomicBool::new(false)).unwrap();
        let [Block::Table(header, rows)] = blocks.as_slice() else {
            panic!("expected one table");
        };
        assert_eq!(header, &["command", "detail"]);
        assert_eq!(rows, &[vec!["`ls /sdcard/Download | head`", "files"]]);
    }

    #[test]
    fn body_rows_keep_the_header_width() {
        let source = "| a | b |\n| --- | --- |\n| full | row |\n| short |\n| extra | row | ignored |\n| `raw | pipe` | ignored |";
        let blocks = parse_blocks(source, &AtomicBool::new(false)).unwrap();
        let [Block::Table(header, rows)] = blocks.as_slice() else {
            panic!("expected one table");
        };
        assert_eq!(header, &["a", "b"]);
        assert_eq!(
            rows,
            &[
                vec!["full", "row"],
                vec!["short", ""],
                vec!["extra", "row"],
                vec!["`raw", "pipe`"],
            ]
        );
    }

    #[test]
    fn escaped_final_pipes_and_code_backslashes_stay_in_the_cell() {
        let source = r"| command | detail |
| --- | --- |
| `a\\|b` | trailing\|";
        let blocks = parse_blocks(source, &AtomicBool::new(false)).unwrap();
        let [Block::Table(header, rows)] = blocks.as_slice() else {
            panic!("expected one table");
        };
        assert_eq!(header, &["command", "detail"]);
        assert_eq!(rows, &[vec![r"`a\|b`", "trailing|"]]);
    }

    #[test]
    fn gfm_escaped_pipes_reach_document_cells_and_formatted_inline_runs() {
        let source = "| f\\|oo |\n| --- |\n| b `\\|` az |\n| b **\\|** im |";
        let document = parse_document(source, &AtomicBool::new(false)).unwrap();
        let mut input = crate::Input {
            bytes: &document,
            at: 0,
        };
        assert_eq!(input.u32(), Some(1));
        assert_eq!(input.u32(), Some(7));
        assert_eq!(strings(&mut input), vec!["f|oo"]);
        assert_eq!(input.u32(), Some(2));
        assert_eq!(strings(&mut input), vec!["b `|` az"]);
        assert_eq!(strings(&mut input), vec!["b **|** im"]);
        assert_eq!(input.u32(), Some(2));
        for (source, runs) in [
            (
                "b `|` az",
                [("b ", 0), ("\u{2009}|\u{2009}", 8), (" az", 0)],
            ),
            ("b **|** im", [("b ", 0), ("|", 1), (" im", 0)]),
        ] {
            assert_eq!(input.string(), Some(source));
            assert_eq!(input.u32(), Some(3));
            for (text, style) in runs {
                assert_eq!(input.string(), Some(text));
                assert_eq!(input.u32(), Some(style));
                assert_eq!(input.string(), Some(""));
            }
        }
        assert_eq!(input.at, document.len());
    }
}
