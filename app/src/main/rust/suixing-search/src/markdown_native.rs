//! The app's existing small Markdown dialect, batched for settled cold documents only.
use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer, check_cancelled, task};
use std::borrow::Cow;
use std::collections::{BTreeMap, HashSet};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;
use std::sync::atomic::AtomicBool;
#[cfg(feature = "rust-app")]
use std::sync::atomic::Ordering;

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

#[cfg_attr(feature = "rust-app", derive(Clone, Debug, PartialEq, Eq))]
pub enum Block {
    Heading(u32, String),
    Paragraph(String),
    Bullet(Bullet),
    Code(String, String),
    Quote(String),
    Rule,
    Table(Vec<String>, Vec<Vec<String>>),
}
#[cfg_attr(feature = "rust-app", derive(Clone, Debug, PartialEq, Eq))]
pub struct Bullet {
    pub items: Vec<String>,
    pub ordered: bool,
    pub depth: u32,
    pub start: u32,
    pub children: BTreeMap<usize, Vec<Bullet>>,
}
/// A complete parse. Inline runs are keyed by their exact block/list/cell source.
#[cfg(feature = "rust-app")]
#[derive(Debug)]
pub struct Document {
    pub blocks: Vec<Block>,
    pub inline: BTreeMap<String, Vec<InlineRun>>,
}

#[cfg(feature = "rust-app")]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ParseError {
    Cancelled,
    Unsupported,
    Budget,
    Failed,
}

/// Callers may lower the native ceilings; raising them never disables a ceiling.
#[cfg(feature = "rust-app")]
#[derive(Clone, Copy, Debug)]
pub struct ParseLimits {
    pub source_bytes: usize,
    pub nodes: usize,
    pub output_bytes: usize,
    pub list_depth: u32,
    pub inline_bytes: usize,
}

#[cfg(feature = "rust-app")]
impl Default for ParseLimits {
    fn default() -> Self {
        Self {
            source_bytes: MAX_DOCUMENT,
            nodes: MAX_NODES,
            output_bytes: MAX_RESPONSE,
            list_depth: 32,
            inline_bytes: MAX_DOCUMENT,
        }
    }
}

/// Parse without JNI/task ownership. Failure never supplies a partial document.
#[cfg(feature = "rust-app")]
pub fn parse(text: &str, cancelled: &AtomicBool) -> Result<Document, ParseError> {
    parse_with_limits(text, cancelled, ParseLimits::default())
}

/// Incremental renderers use exactly the same fence dialect as the block parser.
#[cfg(feature = "rust-app")]
pub fn is_fence_start(line: &str) -> bool {
    fence(trim(line)).is_some()
}

#[cfg(feature = "rust-app")]
pub fn is_fence_end(line: &str) -> bool {
    fence_close(line)
}

#[cfg(feature = "rust-app")]
pub fn parse_with_limits(
    text: &str,
    cancelled: &AtomicBool,
    limits: ParseLimits,
) -> Result<Document, ParseError> {
    match catch_unwind(AssertUnwindSafe(|| parse_typed(text, cancelled, limits))) {
        Ok(result) => result,
        Err(_) => Err(ParseError::Failed),
    }
}

#[cfg(feature = "rust-app")]
fn typed_cancel(cancelled: &AtomicBool) -> Result<(), ParseError> {
    if cancelled.load(Ordering::Relaxed) {
        Err(ParseError::Cancelled)
    } else {
        Ok(())
    }
}

#[cfg(feature = "rust-app")]
fn typed_status(status: i32, cancelled: &AtomicBool) -> ParseError {
    if cancelled.load(Ordering::Relaxed) {
        ParseError::Cancelled
    } else if status == COMPATIBILITY {
        ParseError::Budget
    } else {
        ParseError::Failed
    }
}

#[cfg(feature = "rust-app")]
struct TypedBudget {
    nodes: usize,
    bytes: usize,
    max_nodes: usize,
    max_bytes: usize,
}

#[cfg(feature = "rust-app")]
impl TypedBudget {
    fn add(&mut self, nodes: usize, bytes: usize) -> Result<(), ParseError> {
        self.nodes = self.nodes.checked_add(nodes).ok_or(ParseError::Budget)?;
        self.bytes = self.bytes.checked_add(bytes).ok_or(ParseError::Budget)?;
        if self.nodes > self.max_nodes || self.bytes > self.max_bytes {
            return Err(ParseError::Budget);
        }
        Ok(())
    }
}

#[cfg(feature = "rust-app")]
fn parse_typed(
    text: &str,
    cancelled: &AtomicBool,
    limits: ParseLimits,
) -> Result<Document, ParseError> {
    typed_cancel(cancelled)?;
    if text.len() > limits.source_bytes.min(MAX_DOCUMENT) {
        return Err(ParseError::Budget);
    }
    // Preflight lines/depth before allocating a block tree or entering list recursion.
    let max_nodes = limits.nodes.min(MAX_NODES);
    let mut line_count = 0usize;
    for line in text.split(['\r', '\n']) {
        typed_cancel(cancelled)?;
        line_count += 1;
        if line_count > max_nodes {
            return Err(ParseError::Budget);
        }
        if list_marker(line).is_some_and(|marker| marker.depth > limits.list_depth.min(32)) {
            return Err(ParseError::Budget);
        }
        for (index, ch) in line.char_indices() {
            if index % POLL == 0 {
                typed_cancel(cancelled)?;
            }
            if (!ch.is_ascii() && (crate::java_whitespace(ch) || ch.is_numeric()))
                || matches!(ch, '\u{0085}' | '\u{2028}' | '\u{2029}')
            {
                return Err(ParseError::Unsupported);
            }
        }
    }
    let normalized = if text.contains('\r') {
        Cow::Owned(text.replace("\r\n", "\n").replace('\r', "\n"))
    } else {
        Cow::Borrowed(text)
    };
    let blocks =
        parse_blocks(&normalized, cancelled).map_err(|status| typed_status(status, cancelled))?;
    let mut budget = TypedBudget {
        nodes: 0,
        bytes: 0,
        max_nodes,
        max_bytes: limits.output_bytes.min(MAX_RESPONSE),
    };
    let mut inline = BTreeMap::new();
    for block in &blocks {
        typed_cancel(cancelled)?;
        budget.add(1, std::mem::size_of::<Block>())?;
        match block {
            Block::Heading(_, text) | Block::Paragraph(text) | Block::Quote(text) => {
                typed_leaf(text, &mut inline, &mut budget, cancelled, limits)?;
            }
            Block::Bullet(bullet) => {
                typed_bullet(bullet, &mut inline, &mut budget, cancelled, limits)?;
            }
            Block::Code(language, code) => budget.add(0, language.len() + code.len())?,
            Block::Rule => {}
            Block::Table(header, rows) => {
                budget.add(rows.len(), 0)?;
                for row in std::iter::once(header).chain(rows) {
                    for cell in row {
                        typed_leaf(cell, &mut inline, &mut budget, cancelled, limits)?;
                    }
                }
            }
        }
    }
    typed_cancel(cancelled)?;
    Ok(Document { blocks, inline })
}

#[cfg(feature = "rust-app")]
fn typed_bullet(
    bullet: &Bullet,
    inline: &mut BTreeMap<String, Vec<InlineRun>>,
    budget: &mut TypedBudget,
    cancelled: &AtomicBool,
    limits: ParseLimits,
) -> Result<(), ParseError> {
    typed_cancel(cancelled)?;
    budget.add(1, std::mem::size_of::<Bullet>())?;
    for text in &bullet.items {
        typed_leaf(text, inline, budget, cancelled, limits)?;
    }
    for children in bullet.children.values() {
        for child in children {
            typed_bullet(child, inline, budget, cancelled, limits)?;
        }
    }
    Ok(())
}

#[cfg(feature = "rust-app")]
fn typed_leaf(
    text: &str,
    inline: &mut BTreeMap<String, Vec<InlineRun>>,
    budget: &mut TypedBudget,
    cancelled: &AtomicBool,
    limits: ParseLimits,
) -> Result<(), ParseError> {
    typed_cancel(cancelled)?;
    budget.add(1, text.len())?;
    if inline.contains_key(text)
        || !text
            .bytes()
            .any(|b| matches!(b, b'\\' | b'`' | b'*' | b'_' | b'~' | b'[' | b'/'))
    {
        return Ok(());
    }
    if text.len() > limits.inline_bytes.min(MAX_DOCUMENT) {
        return Err(ParseError::Budget);
    }
    let runs = InlineParser::new(text, cancelled)
        .parse()
        .map_err(|status| typed_status(status, cancelled))?;
    // Include both the exact source map key and all owned inline strings/targets.
    budget.add(0, text.len())?;
    for run in &runs {
        typed_cancel(cancelled)?;
        budget.add(
            1,
            std::mem::size_of::<InlineRun>()
                + run.text.len()
                + run.link.as_ref().map_or(0, String::len),
        )?;
    }
    inline.insert(text.to_owned(), runs);
    Ok(())
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
    parse_blocks_progress(text, cancelled, &mut Progress::default())
}
#[derive(Default)]
struct Progress { end: usize, count: usize, tail: Option<Continuation> }
enum Continuation { Paragraph(usize, String), Code(usize, usize, bool), Table(usize, bool) }
fn parse_blocks_progress(text: &str, cancelled: &AtomicBool, progress: &mut Progress) -> Result<Vec<Block>, i32> {
    let lines: Vec<_> = text.split('\n').collect();
    let mut at = 0;
    let offsets: Vec<_> = lines.iter().map(|line| { let start = at; at += line.len() + 1; start }).collect();
    let mut result = Vec::new();
    let mut index = 0;
    while index < lines.len() {
        check_cancelled(cancelled)?;
        if result.len() >= MAX_NODES {
            return Err(COMPATIBILITY);
        }
        match line_kind(&lines, index) {
            LineKind::Empty => {
                if lines[index].is_empty() && index > 0 && offsets[index] < text.len() {
                    progress.end = offsets[index] + 1; progress.count = result.len();
                }
                index += 1;
            }
            LineKind::Fence(language) => {
                let opening = index;
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
                    if index == lines.len() && !fence_close(lines[lines.len()-1]) {
                        progress.tail = Some(Continuation::Code(offsets[opening] + lines[opening].len() + 1, offsets[lines.len()-1], trim(lines[lines.len()-1]).bytes().any(|b| b != b'`')));
                    }
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
                if index == lines.len() && matches!(result.last(), Some(Block::Table(_, rows)) if !rows.is_empty()) {
                    progress.tail = Some(Continuation::Table(offsets[lines.len()-1], true));
                } else if index == lines.len()-1 && trim(lines[index]).is_empty() {
                    progress.tail = Some(Continuation::Table(offsets[index], false));
                }
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
                if index == lines.len() && trim(lines[lines.len()-1]).chars().next().is_some_and(|c| !"#`>-*+_|:".contains(c) && !c.is_ascii_digit()) {
                    let completed = if paragraph.len() <= 1 { String::new() } else { format!("{}\n", paragraph[..paragraph.len()-1].join("\n")) };
                    progress.tail = Some(Continuation::Paragraph(offsets[lines.len()-1], completed));
                }
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

#[cfg_attr(feature = "rust-app", derive(Clone, Debug, PartialEq, Eq))]
pub struct InlineRun {
    pub text: String,
    pub style: u32,
    pub link: Option<String>,
}

fn image_segments(units: &[u16], streaming: bool, writer: &mut Writer) -> Result<(), i32> {
    let mut i = 0; let mut fence = 0; let mut matches = Vec::new();
    let lower = |c: u16| if (65..=90).contains(&c) { c + 32 } else { c };
    while i < units.len() {
        if i == 0 || units[i - 1] == 10 {
            let marker = if units.get(i..i+3) == Some(&[96,96,96]) { 96 } else if units.get(i..i+3) == Some(&[126,126,126]) { 126 } else { 0 };
            if marker != 0 { fence = if fence == marker { 0 } else if fence == 0 { marker } else { fence }; }
        }
        if fence == 0 && units[i] == 92 && i + 1 < units.len() && matches!(units[i+1], 96 | 92 | 33) { i += 2; continue; }
        if fence == 0 && units[i] == 96 {
            let mut opening = i + 1; while opening < units.len() && units[opening] == 96 { opening += 1; }
            let line_end = units[opening..].iter().position(|&c| c == 10).map(|n| opening+n).unwrap_or(units.len());
            let mut next = opening; let mut span_end = opening;
            while next < line_end {
                if units[next] != 96 { next += 1; continue; }
                let mut closing = next + 1; while closing < units.len() && units[closing] == 96 { closing += 1; }
                if closing-next == opening-i { span_end = closing; break; }
                next = closing;
            }
            if span_end == opening && streaming && line_end == units.len() { span_end = line_end; }
            i = span_end; continue;
        }
        if fence == 0 && units.get(i..i+2) == Some(&[33,91]) {
            if let Some(close) = units[i+2..].iter().position(|&c| c == 93).map(|n| i+2+n) {
                if units.get(close+1) == Some(&40) {
                    let start = close+2; let mut end = start;
                    while end < units.len() && units[end] != 41 && !matches!(units[end], 9 | 10 | 11 | 12 | 13 | 32) { end += 1; }
                    if end > start && units.get(end) == Some(&41) {
                        let url = &units[start..end];
                        let renderable = [b"http://".as_slice(), b"https://", b"data:image/"].iter().any(|prefix| url.len() >= prefix.len() && url.iter().zip(*prefix).all(|(&c, &b)| lower(c) == b as u16));
                        if renderable {
                            matches.push([i, end+1, i+2, close-i-2, start, end-start]); i = end+1; continue;
                        }
                        if matches!(lower(url[0]), 104 | 100) && url.iter().take(11).any(|&c| c >= 128) { return Err(COMPATIBILITY); }
                    }
                }
            }
        }
        i += 1;
    }
    writer.u32(matches.len() as u32)?;
    for row in matches { for value in row { writer.u32(value as u32)?; } }
    Ok(())
}

fn runtime(op: u32, units: &[u16], parameter: usize, flag: bool) -> Result<Vec<u8>, i32> {
    if op == 4 {
        if units.iter().any(|&c| c == 10 || c == 124) { return Err(COMPATIBILITY); }
        return Ok(Vec::new());
    }
    if op == 3 {
        let mut writer = Writer::new(MAX_RESPONSE); image_segments(units, flag, &mut writer)?; return Ok(writer.bytes);
    }
    let cancelled = AtomicBool::new(false); let mut writer = Writer::new(MAX_RESPONSE);
    match op {
        1 => {
            let text = String::from_utf16(units).map_err(|_| COMPATIBILITY)?;
            for ch in text.chars() {
                if !ch.is_ascii() && (crate::java_whitespace(ch) || ch.is_numeric()) || matches!(ch, '\u{0085}' | '\u{2028}' | '\u{2029}') { return Err(COMPATIBILITY); }
            }
            let mut progress = Progress::default(); let blocks = parse_blocks_progress(&text, &cancelled, &mut progress)?;
            writer.u32(text[..progress.end].encode_utf16().count() as u32)?; writer.u32(progress.count as u32)?;
            write_blocks(&mut writer, &blocks, &mut Vec::new(), &cancelled)?;
            let units_at = |offset: usize| text[..offset.min(text.len())].encode_utf16().count() as u32;
            match progress.tail {
                Some(Continuation::Paragraph(start, completed)) => { writer.u32(1)?; writer.u32(units_at(start))?; writer.string(&completed)?; }
                Some(Continuation::Code(body, start, body_line)) => { writer.u32(2)?; writer.u32(units_at(body))?; writer.u32(units_at(start))?; writer.u32(u32::from(body_line))?; }
                Some(Continuation::Table(start, partial)) => { writer.u32(3)?; writer.u32(units_at(start))?; writer.u32(u32::from(partial))?; }
                None => writer.u32(0)?,
            }
        }
        2 => {
            let text = String::from_utf16(units).map_err(|_| COMPATIBILITY)?;
            let runs = InlineParser::new(&text, &cancelled).parse()?;
            writer.u32(runs.len() as u32)?;
            for run in runs { writer.string(&run.text)?; writer.u32(run.style)?; writer.string(run.link.as_deref().unwrap_or(""))?; }
        }
        5 => {
            let mut start = 0;
            if flag {
                let Some(newline) = units[parameter.min(units.len())..].iter().position(|&c| c == 10).map(|n| n + parameter) else {
                    writer.u32(0)?; writer.u32(1)?; return Ok(writer.bytes);
                };
                start = newline + 1;
            }
            loop {
                let end = units[start..].iter().position(|&c| c == 10).map(|n| start + n).unwrap_or(units.len());
                let mut line = &units[start..end];
                while line.first().is_some_and(|&c| crate::java_whitespace(char::from_u32(c as u32).unwrap_or('\0'))) { line = &line[1..]; }
                while line.last().is_some_and(|&c| crate::java_whitespace(char::from_u32(c as u32).unwrap_or('\0'))) { line = &line[..line.len()-1]; }
                if line.len() >= 3 && line.iter().all(|&c| c == 96) { return Err(COMPATIBILITY); }
                if end == units.len() { writer.u32(start as u32)?; writer.u32(u32::from(line.iter().any(|&c| c != 96)))?; break; }
                start = end + 1;
            }
        }
        6 => {
            let text = String::from_utf16(units).map_err(|_| COMPATIBILITY)?;
            let mut lines = text.split('\n').peekable(); let mut rows = Vec::new(); let mut start_units = 0;
            let mut partial = None;
            while let Some(line) = lines.next() {
                let last = lines.peek().is_none(); let row = trim(line);
                if row.is_empty() {
                    if !last { return Err(COMPATIBILITY); }
                } else {
                    if !row.contains('|') { return Err(COMPATIBILITY); }
                    let mut cells = table_cells(row); cells.resize(parameter, String::new());
                    if last { partial = Some(cells); } else { rows.push(cells); }
                }
                if !last { start_units += line.encode_utf16().count() + 1; }
            }
            writer.u32(start_units as u32)?; writer.u32(rows.len() as u32)?;
            for row in &rows { write_strings(&mut writer, row)?; }
            writer.u32(u32::from(partial.is_some()))?; if let Some(cells) = partial { write_strings(&mut writer, &cells)?; }
        }
        _ => return Err(STATUS_REQUEST_ERROR),
    }
    Ok(writer.bytes)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_markdown_runtime(op: u32, source: *const u16, len: usize, parameter: usize, flag: bool, output: *mut SearchBuffer) -> i32 {
    if output.is_null() || source.is_null() && len != 0 { return STATUS_REQUEST_ERROR; }
    unsafe { output.write(SearchBuffer { data: ptr::null_mut(), len: 0 }); }
    let units = if len == 0 { &[] } else { unsafe { slice::from_raw_parts(source, len) } };
    match catch_unwind(AssertUnwindSafe(|| runtime(op, units, parameter, flag))) {
        Ok(Ok(bytes)) => { let bytes = bytes.into_boxed_slice(); let len = bytes.len(); unsafe { output.write(SearchBuffer { data: Box::into_raw(bytes).cast(), len }); } STATUS_OK },
        Ok(Err(status)) => status, Err(_) => STATUS_PANIC,
    }
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
    fn streaming_suffix_handles_code_fences_and_table_rows() {
        let code: Vec<u16> = "😀".encode_utf16().collect();
        assert_eq!(runtime(5, &code, 0, true).unwrap(), [0, 0, 0, 0, 1, 0, 0, 0]);
        let close: Vec<u16> = "\n```".encode_utf16().collect();
        assert_eq!(runtime(5, &close, 0, true), Err(COMPATIBILITY));

        let table: Vec<u16> = "| 😀 | b |\n| c |".encode_utf16().collect();
        let bytes = runtime(6, &table, 2, false).unwrap();
        let mut row = crate::Input { bytes: &bytes, at: 0 };
        assert_eq!(row.u32().unwrap(), "| 😀 | b |\n".encode_utf16().count() as u32);
        assert_eq!(row.u32().unwrap(), 1);
        assert_eq!(strings(&mut row), ["😀", "b"]);
        assert_eq!(row.u32().unwrap(), 1);
        assert_eq!(strings(&mut row), ["c", ""]);
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
