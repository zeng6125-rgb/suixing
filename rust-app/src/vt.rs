//! Bounded, effect-free VT screen and input models. PTY ownership stays with the actor.
use std::{collections::VecDeque, sync::Arc};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::{UnicodeWidthChar, UnicodeWidthStr};
mod extensions;
mod keypad;
mod margins;
pub use extensions::Palette;

const MAX_CELLS: usize = 65_536;
const HISTORY_ROWS: usize = 10_000;
const HISTORY_BYTES: usize = 8 * 1024 * 1024;
const STRING_BYTES: usize = 8_192;
const CLUSTER_BYTES: usize = 256;
const REPLY_BYTES: usize = 16 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Geometry {
    pub columns: u16,
    pub rows: u16,
    pub version: u64,
}
impl Default for Geometry {
    fn default() -> Self {
        Self {
            columns: 80,
            rows: 24,
            version: 0,
        }
    }
}
impl Geometry {
    pub fn validate(self) -> Result<Self, String> {
        if self.columns == 0
            || self.rows == 0
            || self.columns > 1000
            || self.rows > 1000
            || usize::from(self.columns) * usize::from(self.rows) > MAX_CELLS
        {
            Err("终端尺寸超过有界屏幕预算".into())
        } else {
            Ok(self)
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum Color {
    #[default]
    Default,
    Indexed(u8),
    Rgb(u8, u8, u8),
}
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub struct Style {
    pub foreground: Color,
    pub background: Color,
    pub bold: bool,
    pub faint: bool,
    pub italic: bool,
    pub underline: bool,
    pub inverse: bool,
    pub hidden: bool,
    pub strike: bool,
    pub blink: bool,
    pub protected: bool,
    pub underline_color: Color,
    pub underline_kind: u8,
}
#[derive(Clone, Debug)]
pub struct Cell {
    pub text: String,
    pub width: u8,
    pub style: Style,
}
impl Cell {
    pub fn estimated_bytes(&self) -> usize {
        std::mem::size_of::<Self>() + self.text.capacity()
    }
    fn blank(style: Style) -> Self {
        Self {
            text: " ".into(),
            width: 1,
            style,
        }
    }
    fn continuation(style: Style) -> Self {
        Self {
            text: String::new(),
            width: 0,
            style,
        }
    }
}
#[derive(Clone, Debug)]
pub struct Row {
    pub id: u64,
    pub revision: u64,
    pub wrapped: bool,
    pub cells: Vec<Cell>,
}
impl Row {
    fn blank(id: u64, columns: usize, style: Style) -> Self {
        Self {
            id,
            revision: 0,
            wrapped: false,
            cells: vec![Cell::blank(style); columns],
        }
    }
    pub fn estimated_bytes(&self) -> usize {
        std::mem::size_of::<Self>()
            + self.cells.capacity() * std::mem::size_of::<Cell>()
            + self
                .cells
                .iter()
                .map(|cell| cell.text.capacity())
                .sum::<usize>()
    }
    pub fn text(&self) -> String {
        self.cells
            .iter()
            .filter(|cell| cell.width != 0)
            .map(|cell| cell.text.as_str())
            .collect()
    }
    fn clear_cell(&mut self, column: usize, style: Style) {
        if column >= self.cells.len() {
            return;
        }
        let lead = if self.cells[column].width == 0 && column > 0 {
            column - 1
        } else {
            column
        };
        let width = usize::from(self.cells[lead].width.max(1));
        for slot in lead..(lead + width).min(self.cells.len()) {
            self.cells[slot] = Cell::blank(style);
        }
    }
    fn repair(&mut self, style: Style) {
        for column in 0..self.cells.len() {
            if self.cells[column].width == 0 && (column == 0 || self.cells[column - 1].width != 2) {
                self.cells[column] = Cell::blank(style);
            }
            if self.cells[column].width == 2 {
                if column + 1 == self.cells.len() {
                    self.cells[column] = Cell::blank(style);
                } else {
                    self.cells[column + 1] = Cell::continuation(self.cells[column].style);
                }
            }
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Cursor {
    pub column: usize,
    pub row: usize,
    pub visible: bool,
    pub shape: u8,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Modes {
    pub alternate: bool,
    pub application_cursor: bool,
    pub application_keypad: bool,
    pub bracketed_paste: bool,
    pub mouse_tracking: u16,
    pub sgr_mouse: bool,
    pub focus_reporting: bool,
    pub reverse_video: bool,
    pub auto_wrap: bool,
    pub left_right_margins: bool,
    pub cursor_blink: bool,
}
impl Default for Modes {
    fn default() -> Self {
        Self {
            alternate: false,
            application_cursor: false,
            application_keypad: false,
            bracketed_paste: false,
            mouse_tracking: 0,
            sgr_mouse: false,
            focus_reporting: false,
            reverse_video: false,
            auto_wrap: true,
            left_right_margins: false,
            cursor_blink: true,
        }
    }
}
#[derive(Clone, Debug)]
pub struct Frame {
    pub session_id: u64,
    pub revision: u64,
    pub geometry: Geometry,
    pub lines: Vec<Arc<Row>>,
    pub screen_start: usize,
    pub cursor: Cursor,
    pub modes: Modes,
    pub title: String,
    pub trimmed: bool,
    pub bells: u64,
    /// Count of ignored unsupported or over-budget controls, never executable UI actions.
    pub unsupported: u64,
    pub palette: Arc<Palette>,
    pub reflow_rows: Arc<std::collections::BTreeMap<u64, u64>>,
}
impl Frame {
    pub fn estimated_bytes(&self) -> usize {
        std::mem::size_of::<Self>()
            + std::mem::size_of::<Palette>()
            + self.reflow_rows.len() * 64
            + self.title.capacity()
            + self.lines.capacity() * std::mem::size_of::<Arc<Row>>()
            + self
                .lines
                .iter()
                .map(|row| row.estimated_bytes())
                .sum::<usize>()
    }
    pub fn copy_all(&self) -> String {
        let mut text = String::new();
        for (index, row) in self.lines.iter().enumerate() {
            let line = row.text();
            text.push_str(if row.wrapped {
                &line
            } else {
                line.trim_end_matches(' ')
            });
            if !row.wrapped && index + 1 < self.lines.len() {
                text.push('\n');
            }
        }
        text
    }
}

#[derive(Clone)]
struct Saved {
    cursor: Cursor,
    style: Style,
    origin: bool,
    wrap: bool,
    charset: [bool; 2],
    active_charset: usize,
}
#[derive(Clone)]
struct Screen {
    rows: Vec<Arc<Row>>,
    cursor: Cursor,
    saved: Option<Saved>,
    wrap_pending: bool,
}
impl Screen {
    fn new(geometry: Geometry, next_id: &mut u64) -> Self {
        let rows = (0..geometry.rows)
            .map(|_| {
                *next_id += 1;
                Arc::new(Row::blank(
                    *next_id,
                    usize::from(geometry.columns),
                    Style::default(),
                ))
            })
            .collect();
        Self {
            rows,
            cursor: Cursor {
                column: 0,
                row: 0,
                visible: true,
                shape: 0,
            },
            saved: None,
            wrap_pending: false,
        }
    }
}
#[derive(Clone)]
enum Parser {
    Ground,
    Escape,
    Charset(usize),
    Hash,
    Csi(Vec<u8>),
    DiscardCsi,
    String {
        bytes: Vec<u8>,
        osc: bool,
        dcs: bool,
        utf8: extensions::StringUtf8,
        escaped: bool,
        dropped: bool,
    },
}

#[derive(Clone, Default)]
pub struct VtEffects {
    pub reply_bytes: Vec<u8>,
    pub changed: bool,
}

#[derive(Clone)]
pub struct VtEngine {
    session_id: u64,
    geometry: Geometry,
    main: Screen,
    alternate: Screen,
    history: VecDeque<Arc<Row>>,
    history_bytes: usize,
    next_id: u64,
    parser: Parser,
    utf8: Vec<u8>,
    utf8_needed: usize,
    style: Style,
    modes: Modes,
    origin: bool,
    insert: bool,
    newline: bool,
    top: usize,
    bottom: usize,
    left: usize,
    right: usize,
    tabs: Vec<bool>,
    charset: [bool; 2],
    active_charset: usize,
    title: String,
    bells: u64,
    unsupported: u64,
    trimmed: bool,
    revision: u64,
    effects: VtEffects,
    last_print: Option<(u64, usize)>,
    palette: Arc<Palette>,
    rectangular_attributes: bool,
    reflow_rows: Arc<std::collections::BTreeMap<u64, u64>>,
}
impl VtEngine {
    pub fn new(session_id: u64, geometry: Geometry) -> Result<Self, String> {
        let geometry = geometry.validate()?;
        let mut next_id = 0;
        let main = Screen::new(geometry, &mut next_id);
        let alternate = Screen::new(geometry, &mut next_id);
        Ok(Self {
            session_id,
            geometry,
            main,
            alternate,
            history: VecDeque::new(),
            history_bytes: 0,
            next_id,
            parser: Parser::Ground,
            utf8: Vec::with_capacity(4),
            utf8_needed: 0,
            style: Style::default(),
            modes: Modes::default(),
            origin: false,
            insert: false,
            newline: false,
            top: 0,
            bottom: usize::from(geometry.rows),
            left: 0,
            right: usize::from(geometry.columns),
            tabs: (0..geometry.columns)
                .map(|column| column > 0 && column % 8 == 0)
                .collect(),
            charset: [false; 2],
            active_charset: 0,
            title: String::new(),
            bells: 0,
            unsupported: 0,
            trimmed: false,
            revision: 0,
            effects: VtEffects::default(),
            last_print: None,
            palette: Arc::new(Palette::default()),
            rectangular_attributes: false,
            reflow_rows: Arc::new(Default::default()),
        })
    }
    fn screen(&self) -> &Screen {
        if self.modes.alternate {
            &self.alternate
        } else {
            &self.main
        }
    }
    fn screen_mut(&mut self) -> &mut Screen {
        if self.modes.alternate {
            &mut self.alternate
        } else {
            &mut self.main
        }
    }
    fn dirty(&mut self) {
        self.effects.changed = true;
    }
    fn row_mut(&mut self, row: usize) -> &mut Row {
        self.dirty();
        let row = Arc::make_mut(&mut self.screen_mut().rows[row]);
        row.revision = row.revision.saturating_add(1);
        row
    }
    fn blank_row(&mut self) -> Arc<Row> {
        self.next_id = self.next_id.saturating_add(1);
        Arc::new(Row::blank(
            self.next_id,
            usize::from(self.geometry.columns),
            self.style,
        ))
    }
    fn unsupported(&mut self) {
        self.unsupported = self.unsupported.saturating_add(1);
        self.dirty();
    }
    fn reply(&mut self, bytes: &[u8]) {
        if bytes.len() <= REPLY_BYTES.saturating_sub(self.effects.reply_bytes.len()) {
            self.effects.reply_bytes.extend_from_slice(bytes);
        } else {
            self.unsupported();
        }
    }
    pub fn advance(&mut self, bytes: &[u8]) -> VtEffects {
        self.effects = VtEffects::default();
        for &byte in bytes {
            self.byte(byte);
        }
        if self.effects.changed {
            self.revision = self.revision.saturating_add(1);
        }
        std::mem::take(&mut self.effects)
    }
    pub fn finish(&mut self) {
        if !self.utf8.is_empty() {
            self.utf8.clear();
            self.utf8_needed = 0;
            self.print('\u{fffd}');
        }
        self.parser = Parser::Ground;
        self.revision = self.revision.saturating_add(1);
    }
    pub fn frame(&self) -> Arc<Frame> {
        let screen_start = if self.modes.alternate {
            0
        } else {
            self.history.len()
        };
        let mut lines = Vec::with_capacity(screen_start + self.screen().rows.len());
        if !self.modes.alternate {
            lines.extend(self.history.iter().cloned());
        }
        lines.extend(self.screen().rows.iter().cloned());
        Arc::new(Frame {
            session_id: self.session_id,
            revision: self.revision,
            geometry: self.geometry,
            lines,
            screen_start,
            cursor: self.screen().cursor,
            modes: self.modes,
            title: self.title.clone(),
            trimmed: self.trimmed,
            bells: self.bells,
            unsupported: self.unsupported,
            palette: self.palette.clone(),
            reflow_rows: self.reflow_rows.clone(),
        })
    }
    pub fn modes(&self) -> Modes {
        self.modes
    }
    pub fn geometry(&self) -> Geometry {
        self.geometry
    }
    pub fn clear(&mut self) {
        self.history.clear();
        self.history_bytes = 0;
        self.trimmed = false;
        let columns = usize::from(self.geometry.columns);
        let style = self.style;
        for row in 0..usize::from(self.geometry.rows) {
            let line = self.row_mut(row);
            line.cells = vec![Cell::blank(style); columns];
            line.wrapped = false;
        }
        self.screen_mut().cursor.column = 0;
        self.screen_mut().cursor.row = 0;
        self.screen_mut().wrap_pending = false;
        self.last_print = None;
        self.revision = self.revision.saturating_add(1);
    }
    fn byte(&mut self, byte: u8) {
        let state = std::mem::replace(&mut self.parser, Parser::Ground);
        match state {
            Parser::Ground => self.ground(byte),
            Parser::Escape => {
                self.last_print = None;
                match byte {
                    b'[' => self.parser = Parser::Csi(Vec::new()),
                    b']' => self.start_string(true),
                    b'P' => self.start_dcs(),
                    b'_' | b'^' | b'X' => self.start_string(false),
                    b'(' => self.parser = Parser::Charset(0),
                    b')' => self.parser = Parser::Charset(1),
                    b'#' => self.parser = Parser::Hash,
                    b'7' => self.save_cursor(),
                    b'8' => self.restore_cursor(),
                    b'D' => self.index(),
                    b'E' => {
                        self.carriage();
                        self.index();
                    }
                    b'M' => self.reverse_index(),
                    b'6' => self.horizontal_index(true),
                    b'9' => self.horizontal_index(false),
                    b'H' => {
                        let column = self.screen().cursor.column;
                        self.tabs[column] = true;
                    }
                    b'=' => {
                        self.modes.application_keypad = true;
                        self.dirty();
                    }
                    b'>' => {
                        self.modes.application_keypad = false;
                        self.dirty();
                    }
                    b'c' => self.reset(),
                    b'Z' => self.reply(b"\x1b[?1;2c"),
                    0x1b => self.parser = Parser::Escape,
                    0x00..=0x1f => {
                        self.control(byte);
                        self.parser = Parser::Escape;
                    }
                    _ => self.unsupported(),
                }
            }
            Parser::Charset(slot) => {
                self.charset[slot] = byte == b'0';
                if !matches!(byte, b'0' | b'B') {
                    self.unsupported();
                }
            }
            Parser::Hash => {
                if byte == b'8' {
                    let columns = usize::from(self.geometry.columns);
                    let style = self.style;
                    for row in 0..usize::from(self.geometry.rows) {
                        let line = self.row_mut(row);
                        line.cells = vec![
                            Cell {
                                text: "E".into(),
                                width: 1,
                                style
                            };
                            columns
                        ];
                        line.wrapped = false;
                    }
                } else {
                    self.unsupported();
                }
            }
            Parser::Csi(mut bytes) => {
                if byte == 0x1b {
                    self.parser = Parser::Escape;
                } else if matches!(byte, 0x18 | 0x1a) {
                    self.last_print = None;
                } else if byte < 0x20 {
                    self.control(byte);
                    self.parser = Parser::Csi(bytes);
                } else if (0x40..=0x7e).contains(&byte) {
                    self.csi(&bytes, byte);
                } else if bytes.len() < 256 && (0x20..=0x3f).contains(&byte) {
                    bytes.push(byte);
                    self.parser = Parser::Csi(bytes);
                } else {
                    self.unsupported();
                    self.parser = Parser::DiscardCsi;
                }
            }
            Parser::DiscardCsi => {
                if byte == 0x1b {
                    self.parser = Parser::Escape;
                } else if !(0x40..=0x7e).contains(&byte) {
                    self.parser = Parser::DiscardCsi;
                }
            }
            Parser::String {
                mut bytes,
                osc,
                dcs,
                mut utf8,
                mut escaped,
                mut dropped,
            } => {
                if matches!(byte, 0x18 | 0x1a) {
                    self.last_print = None;
                    return;
                }
                let utf8_tail = utf8.consume(byte);
                if (escaped && byte == b'\\') || (byte == 0x9c && !utf8_tail) || (osc && byte == 7)
                {
                    if !dropped && dcs {
                        self.dcs(&bytes);
                    } else if !dropped && osc {
                        self.osc(&bytes);
                    } else if !dropped && !osc {
                        self.unsupported();
                    }
                    return;
                }
                if escaped && !dropped {
                    if bytes.len() + 1 <= STRING_BYTES {
                        bytes.push(0x1b);
                    } else {
                        bytes.clear();
                        dropped = true;
                        self.unsupported();
                    }
                }
                escaped = false;
                if byte == 0x1b {
                    escaped = true;
                } else if !dropped {
                    if bytes.len() < STRING_BYTES {
                        bytes.push(byte);
                    } else {
                        bytes.clear();
                        dropped = true;
                        self.unsupported();
                    }
                }
                self.parser = Parser::String {
                    bytes,
                    osc,
                    dcs,
                    utf8,
                    escaped,
                    dropped,
                };
            }
        }
    }
    fn start_string(&mut self, osc: bool) {
        self.parser = Parser::String {
            bytes: Vec::new(),
            osc,
            dcs: false,
            utf8: Default::default(),
            escaped: false,
            dropped: false,
        };
    }
    fn ground(&mut self, byte: u8) {
        if !self.utf8.is_empty() {
            if byte & 0xc0 == 0x80 {
                self.utf8.push(byte);
                if self.utf8.len() == self.utf8_needed {
                    let ch = std::str::from_utf8(&self.utf8)
                        .ok()
                        .and_then(|text| text.chars().next())
                        .unwrap_or('\u{fffd}');
                    self.utf8.clear();
                    self.utf8_needed = 0;
                    self.print(ch);
                }
                return;
            }
            self.utf8.clear();
            self.utf8_needed = 0;
            self.print('\u{fffd}');
        }
        match byte {
            0x1b => {
                self.last_print = None;
                self.parser = Parser::Escape;
            }
            0x00..=0x1f | 0x7f => self.control(byte),
            0x20..=0x7e => self.print(char::from(byte)),
            0x9b => {
                self.last_print = None;
                self.parser = Parser::Csi(Vec::new());
            }
            0x9d => {
                self.last_print = None;
                self.start_string(true);
            }
            0x90 => {
                self.last_print = None;
                self.start_dcs();
            }
            0x98 | 0x9e | 0x9f => {
                self.last_print = None;
                self.start_string(false);
            }
            0x84 => self.index(),
            0x85 => {
                self.carriage();
                self.index();
            }
            0x88 => {
                let column = self.screen().cursor.column;
                self.tabs[column] = true;
            }
            0x8d => self.reverse_index(),
            0x9c => (),
            0xc2..=0xdf => {
                self.utf8.push(byte);
                self.utf8_needed = 2;
            }
            0xe0..=0xef => {
                self.utf8.push(byte);
                self.utf8_needed = 3;
            }
            0xf0..=0xf4 => {
                self.utf8.push(byte);
                self.utf8_needed = 4;
            }
            _ => self.print('\u{fffd}'),
        }
    }
    fn control(&mut self, byte: u8) {
        self.last_print = None;
        match byte {
            7 => {
                self.bells = self.bells.saturating_add(1);
                self.dirty();
            }
            8 => {
                let left = if self.in_horizontal_region(self.screen().cursor.column) {
                    self.horizontal_bounds().0
                } else {
                    0
                };
                let screen = self.screen_mut();
                screen.cursor.column = screen.cursor.column.saturating_sub(1).max(left);
                screen.wrap_pending = false;
                self.dirty();
            }
            9 => self.tab(1, false),
            10..=12 => {
                self.index();
                if self.newline {
                    self.carriage();
                }
            }
            13 => self.carriage(),
            14 => self.active_charset = 1,
            15 => self.active_charset = 0,
            _ => (),
        }
    }
    fn dec_char(&self, ch: char) -> char {
        if !self.charset[self.active_charset] {
            return ch;
        }
        match ch {
            'j' => '┘',
            'k' => '┐',
            'l' => '┌',
            'm' => '└',
            'n' => '┼',
            'q' => '─',
            't' => '├',
            'u' => '┤',
            'v' => '┴',
            'w' => '┬',
            'x' => '│',
            'a' => '▒',
            'f' => '°',
            'g' => '±',
            'o' => '⎺',
            'p' => '⎻',
            'r' => '⎼',
            's' => '⎽',
            'y' => '≤',
            'z' => '≥',
            '{' => 'π',
            '|' => '≠',
            '}' => '£',
            '~' => '·',
            _ => ch,
        }
    }
    fn print(&mut self, ch: char) {
        let ch = self.dec_char(ch);
        // Unicode clusters survive PTY chunk boundaries, including combining and ZWJ emoji.
        if let Some((id, column)) = self.last_print {
            let row_index = self.screen().cursor.row;
            let line = &self.screen().rows[row_index];
            if line.id == id
                && column < line.cells.len()
                && line.cells[column].text.len() + ch.len_utf8() <= CLUSTER_BYTES
            {
                let mut combined = line.cells[column].text.clone();
                combined.push(ch);
                if combined.graphemes(true).count() == 1 {
                    let old_width = line.cells[column].width;
                    let width = UnicodeWidthStr::width(combined.as_str()).clamp(1, 2) as u8;
                    let columns = self.print_bounds().1;
                    if column + usize::from(width) <= columns {
                        let style = self.style;
                        let row = self.row_mut(row_index);
                        if width != old_width {
                            row.clear_cell(column, style);
                            if width == 2 {
                                row.clear_cell(column + 1, style);
                                row.cells[column + 1] = Cell::continuation(style);
                            }
                        }
                        row.cells[column] = Cell {
                            text: combined,
                            width,
                            style,
                        };
                        let screen = self.screen_mut();
                        screen.cursor.column = (column + usize::from(width)).min(columns - 1);
                        screen.wrap_pending = column + usize::from(width) >= columns;
                        return;
                    }
                }
            }
        }
        let mut width = ch.width().unwrap_or(0).min(2);
        if width == 0 {
            if ch.is_control() {
                return;
            }
            // A combining mark after a cursor/style control still attaches to the
            // preceding screen cell; the mutable parser cache is not its identity.
            let cursor = self.screen().cursor;
            let mut column = if self.screen().wrap_pending {
                cursor.column
            } else {
                cursor.column.saturating_sub(1)
            };
            if self.screen().rows[cursor.row].cells[column].width == 0 {
                column = column.saturating_sub(1);
            }
            let row = self.row_mut(cursor.row);
            if row.cells[column].text.len() + ch.len_utf8() <= CLUSTER_BYTES {
                row.cells[column].text.push(ch);
                self.last_print = Some((row.id, column));
            } else {
                self.unsupported();
            }
            return;
        }
        let columns = self.print_bounds().1;
        if width == 2 && self.print_bounds().1 - self.print_bounds().0 == 1 {
            width = 1;
        }
        if self.screen().wrap_pending || self.screen().cursor.column + width > columns {
            if self.modes.auto_wrap {
                let row = self.screen().cursor.row;
                let (left, right) = self.print_bounds();
                self.row_mut(row).wrapped =
                    left == 0 && right == usize::from(self.geometry.columns);
                self.carriage();
                self.index();
            } else {
                self.screen_mut().wrap_pending = false;
                if self.screen().cursor.column + width > columns {
                    width = 1;
                }
            }
        }
        let cursor = self.screen().cursor;
        if self.insert {
            self.insert_cells(width);
        }
        let style = self.style;
        let row = self.row_mut(cursor.row);
        row.clear_cell(cursor.column, style);
        if width == 2 {
            row.clear_cell(cursor.column + 1, style);
            row.cells[cursor.column + 1] = Cell::continuation(style);
        }
        row.cells[cursor.column] = Cell {
            text: ch.to_string(),
            width: width as u8,
            style,
        };
        let id = row.id;
        let screen = self.screen_mut();
        screen.cursor.column = (cursor.column + width).min(columns - 1);
        screen.wrap_pending = cursor.column + width >= columns;
        self.last_print = Some((id, cursor.column));
    }
    fn carriage(&mut self) {
        let left = self.horizontal_bounds().0;
        self.screen_mut().cursor.column = left;
        self.screen_mut().wrap_pending = false;
        self.last_print = None;
        self.dirty();
    }
    fn index(&mut self) {
        let row = self.screen().cursor.row;
        if row + 1 == self.bottom && self.in_horizontal_region(self.screen().cursor.column) {
            self.scroll_up(1);
        } else if row + 1 < usize::from(self.geometry.rows) {
            self.screen_mut().cursor.row += 1;
        }
        self.screen_mut().wrap_pending = false;
        self.last_print = None;
        self.dirty();
    }
    fn reverse_index(&mut self) {
        if self.screen().cursor.row == self.top
            && self.in_horizontal_region(self.screen().cursor.column)
        {
            self.scroll_down(1);
        } else {
            self.screen_mut().cursor.row = self.screen().cursor.row.saturating_sub(1);
        }
        self.screen_mut().wrap_pending = false;
        self.dirty();
    }
    fn retain_history(&mut self, row: Arc<Row>) {
        self.history_bytes = self.history_bytes.saturating_add(row.estimated_bytes());
        self.history.push_back(row);
        while self.history.len() > HISTORY_ROWS || self.history_bytes > HISTORY_BYTES {
            if let Some(row) = self.history.pop_front() {
                self.history_bytes = self.history_bytes.saturating_sub(row.estimated_bytes());
                self.trimmed = true;
            } else {
                break;
            }
        }
    }
    fn scroll_up(&mut self, count: usize) {
        self.scroll_region_up(count, true);
    }
    fn scroll_region_up(&mut self, count: usize, retain: bool) {
        if self.scroll_partial(count, false) {
            return;
        }
        for _ in 0..count.min(self.bottom - self.top) {
            let top = self.top;
            let bottom = self.bottom;
            let blank = self.blank_row();
            let old = self.screen_mut().rows.remove(top);
            self.screen_mut().rows.insert(bottom - 1, blank);
            if retain
                && !self.modes.alternate
                && top == 0
                && bottom == usize::from(self.geometry.rows)
            {
                self.retain_history(old);
            }
        }
        self.dirty();
    }
    fn scroll_down(&mut self, count: usize) {
        if self.scroll_partial(count, true) {
            return;
        }
        for _ in 0..count.min(self.bottom - self.top) {
            let top = self.top;
            let bottom = self.bottom;
            let blank = self.blank_row();
            self.screen_mut().rows.remove(bottom - 1);
            self.screen_mut().rows.insert(top, blank);
        }
        self.dirty();
    }
    fn move_cursor(&mut self, column: usize, row: usize) {
        let (top, bottom) = if self.origin {
            (self.top, self.bottom)
        } else {
            (0, usize::from(self.geometry.rows))
        };
        let (left, right) = if self.origin {
            self.horizontal_bounds()
        } else {
            (0, usize::from(self.geometry.columns))
        };
        let screen = self.screen_mut();
        screen.cursor.column = column.clamp(left, right - 1);
        screen.cursor.row = row.clamp(top, bottom - 1);
        screen.wrap_pending = false;
        self.last_print = None;
        self.dirty();
    }
    fn tab(&mut self, count: usize, back: bool) {
        let mut column = self.screen().cursor.column;
        let (left, columns) = if self.in_horizontal_region(column) {
            self.horizontal_bounds()
        } else {
            (0, usize::from(self.geometry.columns))
        };
        for _ in 0..count.min(columns) {
            column = if back {
                (left..column)
                    .rev()
                    .find(|&next| self.tabs[next])
                    .unwrap_or(left)
            } else {
                (column + 1..columns)
                    .find(|&next| self.tabs[next])
                    .unwrap_or(columns - 1)
            };
        }
        self.move_cursor(column, self.screen().cursor.row);
    }
    fn erase_line_range(&mut self, row: usize, from: usize, to: usize) {
        let style = self.style;
        let line = self.row_mut(row);
        for column in from..to.min(line.cells.len()) {
            line.clear_cell(column, style);
        }
        if from == 0 && to >= line.cells.len() {
            line.wrapped = false;
        }
    }
    fn erase_line(&mut self, mode: u16) {
        let cursor = self.screen().cursor;
        let columns = usize::from(self.geometry.columns);
        match mode {
            0 => self.erase_line_range(cursor.row, cursor.column, columns),
            1 => self.erase_line_range(cursor.row, 0, cursor.column + 1),
            2 => self.erase_line_range(cursor.row, 0, columns),
            _ => self.unsupported(),
        }
    }
    fn erase_display(&mut self, mode: u16) {
        let cursor = self.screen().cursor;
        let columns = usize::from(self.geometry.columns);
        let rows = usize::from(self.geometry.rows);
        match mode {
            0 => {
                self.erase_line(0);
                for row in cursor.row + 1..rows {
                    self.erase_line_range(row, 0, columns);
                }
            }
            1 => {
                for row in 0..cursor.row {
                    self.erase_line_range(row, 0, columns);
                }
                self.erase_line(1);
            }
            2 => {
                for row in 0..rows {
                    self.erase_line_range(row, 0, columns);
                }
            }
            3 => {
                self.history.clear();
                self.history_bytes = 0;
                self.trimmed = false;
                self.dirty();
            }
            _ => self.unsupported(),
        }
    }
    fn insert_cells(&mut self, count: usize) {
        let cursor = self.screen().cursor;
        let right = if self.in_horizontal_region(cursor.column) {
            self.horizontal_bounds().1
        } else {
            usize::from(self.geometry.columns)
        };
        self.shift_rectangle(
            cursor.row,
            cursor.row + 1,
            cursor.column,
            right,
            count,
            true,
            self.style,
        );
    }
    fn delete_cells(&mut self, count: usize) {
        let cursor = self.screen().cursor;
        let right = if self.in_horizontal_region(cursor.column) {
            self.horizontal_bounds().1
        } else {
            usize::from(self.geometry.columns)
        };
        self.shift_rectangle(
            cursor.row,
            cursor.row + 1,
            cursor.column,
            right,
            count,
            false,
            self.style,
        );
    }
    fn save_cursor(&mut self) {
        let saved = Saved {
            cursor: self.screen().cursor,
            style: self.style,
            origin: self.origin,
            wrap: self.screen().wrap_pending,
            charset: self.charset,
            active_charset: self.active_charset,
        };
        self.screen_mut().saved = Some(saved);
    }
    fn restore_cursor(&mut self) {
        if let Some(saved) = self.screen().saved.clone() {
            self.style = saved.style;
            self.origin = saved.origin;
            self.charset = saved.charset;
            self.active_charset = saved.active_charset;
            self.move_cursor(saved.cursor.column, saved.cursor.row);
            self.screen_mut().wrap_pending = saved.wrap;
        }
    }
    fn reset(&mut self) {
        self.palette = Arc::new(Palette::default());
        self.rectangular_attributes = false;
        self.reflow_rows = Arc::new(Default::default());
        self.modes = Modes::default();
        self.style = Style::default();
        self.origin = false;
        self.insert = false;
        self.newline = false;
        self.top = 0;
        self.bottom = usize::from(self.geometry.rows);
        self.left = 0;
        self.right = usize::from(self.geometry.columns);
        self.charset = [false; 2];
        self.active_charset = 0;
        self.tabs = (0..self.geometry.columns)
            .map(|column| column > 0 && column % 8 == 0)
            .collect();
        self.main = Screen::new(self.geometry, &mut self.next_id);
        self.alternate = Screen::new(self.geometry, &mut self.next_id);
        self.history.clear();
        self.history_bytes = 0;
        self.trimmed = false;
        self.last_print = None;
        self.dirty();
    }
    fn set_mode(&mut self, mode: u16, enabled: bool, private: bool) {
        if !private {
            match mode {
                4 => self.insert = enabled,
                20 => self.newline = enabled,
                _ => self.unsupported(),
            };
            return;
        }
        match mode {
            1 => self.modes.application_cursor = enabled,
            5 => self.modes.reverse_video = enabled,
            6 => {
                self.origin = enabled;
                self.move_cursor(0, if enabled { self.top } else { 0 });
            }
            7 => self.modes.auto_wrap = enabled,
            25 => {
                self.main.cursor.visible = enabled;
                self.alternate.cursor.visible = enabled;
            }
            12 => self.modes.cursor_blink = enabled,
            69 => {
                self.modes.left_right_margins = enabled;
                if !enabled {
                    self.left = 0;
                    self.right = usize::from(self.geometry.columns);
                }
                self.screen_mut().wrap_pending = false;
            }
            66 => self.modes.application_keypad = enabled,
            47 | 1047 | 1049 => {
                if self.modes.alternate != enabled {
                    if mode == 1049 && enabled {
                        self.save_cursor();
                    }
                    if enabled && matches!(mode, 1047 | 1049) {
                        let visible = self.screen().cursor.visible;
                        let shape = self.screen().cursor.shape;
                        self.alternate = Screen::new(self.geometry, &mut self.next_id);
                        self.alternate.cursor.visible = visible;
                        self.alternate.cursor.shape = shape;
                    }
                    self.modes.alternate = enabled;
                    self.top = 0;
                    self.bottom = usize::from(self.geometry.rows);
                    self.left = 0;
                    self.right = usize::from(self.geometry.columns);
                    self.last_print = None;
                    if mode == 1049 && !enabled {
                        self.restore_cursor();
                    }
                }
            }
            1048 => {
                if enabled {
                    self.save_cursor();
                } else {
                    self.restore_cursor();
                }
            }
            1000 | 1002 | 1003 => {
                if enabled {
                    self.modes.mouse_tracking = mode;
                } else if self.modes.mouse_tracking == mode {
                    self.modes.mouse_tracking = 0;
                }
            }
            1006 => self.modes.sgr_mouse = enabled,
            1004 => self.modes.focus_reporting = enabled,
            2004 => self.modes.bracketed_paste = enabled,
            12 => (),
            _ => self.unsupported(),
        }
        self.dirty();
    }
    fn csi(&mut self, raw: &[u8], command: u8) {
        self.last_print = None;
        if self.extended_csi(raw, command) {
            return;
        }
        let private = raw.first() == Some(&b'?');
        let secondary = raw.first() == Some(&b'>');
        // Private parameter namespaces have their own grammar. Never execute
        // an unknown private cursor, erase, margin or SGR control as plain CSI.
        if (private && !matches!(command, b'h' | b'l' | b'n'))
            || (secondary && command != b'c')
            || (raw.contains(&b':') && command != b'm')
        {
            self.unsupported();
            return;
        }
        let body = if private || secondary { &raw[1..] } else { raw };
        let mut params = Vec::new();
        let mut number = 0u32;
        let mut digits = false;
        for &byte in body {
            if byte.is_ascii_digit() {
                number = (number * 10 + u32::from(byte - b'0')).min(65535);
                digits = true;
            } else if byte == b';' || byte == b':' {
                params.push(if digits { Some(number as u16) } else { None });
                number = 0;
                digits = false;
            } else if byte >= 0x30 {
                self.unsupported();
                return;
            }
            if params.len() > 64 {
                self.unsupported();
                return;
            }
        }
        params.push(if digits { Some(number as u16) } else { None });
        let p =
            |index: usize, default: u16| params.get(index).copied().flatten().unwrap_or(default);
        let count = usize::from(p(0, 1).max(1));
        let cursor = self.screen().cursor;
        match command {
            b'A' => self.move_cursor(cursor.column, cursor.row.saturating_sub(count)),
            b'B' | b'e' => self.move_cursor(cursor.column, cursor.row.saturating_add(count)),
            b'C' | b'a' => self.horizontal_move(count, false),
            b'D' => self.horizontal_move(count, true),
            b'E' => self.move_cursor(
                if self.origin {
                    self.horizontal_bounds().0
                } else {
                    0
                },
                cursor.row.saturating_add(count),
            ),
            b'F' => self.move_cursor(
                if self.origin {
                    self.horizontal_bounds().0
                } else {
                    0
                },
                cursor.row.saturating_sub(count),
            ),
            b'G' | b'`' => self.move_cursor(
                count - 1
                    + if self.origin {
                        self.horizontal_bounds().0
                    } else {
                        0
                    },
                cursor.row,
            ),
            b'H' | b'f' => {
                let row = usize::from(p(0, 1).max(1)) - 1 + if self.origin { self.top } else { 0 };
                let col = usize::from(p(1, 1).max(1)) - 1
                    + if self.origin {
                        self.horizontal_bounds().0
                    } else {
                        0
                    };
                self.move_cursor(col, row);
            }
            b'd' => self.move_cursor(
                cursor.column,
                count - 1 + if self.origin { self.top } else { 0 },
            ),
            b'J' => self.erase_display(p(0, 0)),
            b'K' => self.erase_line(p(0, 0)),
            b'X' => self.erase_line_range(
                cursor.row,
                cursor.column,
                cursor.column.saturating_add(count),
            ),
            b'@' => self.insert_cells(count),
            b'P' => self.delete_cells(count),
            b'L' | b'M' => {
                if (self.top..self.bottom).contains(&cursor.row)
                    && self.in_horizontal_region(cursor.column)
                {
                    let top = self.top;
                    self.top = cursor.row;
                    if command == b'L' {
                        self.scroll_down(count);
                    } else {
                        self.scroll_region_up(count, false);
                    }
                    self.top = top;
                }
            }
            b'S' => self.scroll_up(count),
            b'T' => self.scroll_down(count),
            b'I' => self.tab(count, false),
            b'Z' => self.tab(count, true),
            b'g' => match p(0, 0) {
                0 => self.tabs[cursor.column] = false,
                3 => self.tabs.fill(false),
                _ => self.unsupported(),
            },
            b'm' => self.sgr_raw(body),
            b'h' | b'l' => {
                for mode in params.iter().map(|value| value.unwrap_or(0)) {
                    self.set_mode(mode, command == b'h', private);
                }
            }
            b'r' if !private => {
                let top = usize::from(p(0, 1).max(1)) - 1;
                let bottom = usize::from(p(1, self.geometry.rows).max(1));
                if top < bottom && bottom <= usize::from(self.geometry.rows) {
                    self.top = top;
                    self.bottom = bottom;
                    self.move_cursor(
                        if self.origin {
                            self.horizontal_bounds().0
                        } else {
                            0
                        },
                        if self.origin { top } else { 0 },
                    );
                }
            }
            b's' if self.modes.left_right_margins => self.set_horizontal_margins(
                usize::from(p(0, 1).max(1)) - 1,
                usize::from(p(1, self.geometry.columns).max(1)),
            ),
            b's' => self.save_cursor(),
            b'u' => self.restore_cursor(),
            b'n' => match p(0, 0) {
                5 => self.reply(b"\x1b[0n"),
                6 => {
                    let row = cursor
                        .row
                        .saturating_sub(if self.origin { self.top } else { 0 })
                        + 1;
                    let reply = format!(
                        "\x1b[{}{};{}R",
                        if private { "?" } else { "" },
                        row,
                        cursor.column.saturating_sub(if self.origin {
                            self.horizontal_bounds().0
                        } else {
                            0
                        }) + 1
                    );
                    self.reply(reply.as_bytes());
                }
                _ => self.unsupported(),
            },
            b'c' => {
                if secondary {
                    self.reply(b"\x1b[>41;320;0c");
                } else {
                    self.reply(b"\x1b[?1;2c");
                }
            }
            b't' => match p(0, 0) {
                18 => {
                    let reply = format!("\x1b[8;{};{}t", self.geometry.rows, self.geometry.columns);
                    self.reply(reply.as_bytes());
                }
                _ => self.unsupported(),
            },
            b'q' if body.contains(&b' ') => {
                let shape = p(0, 0).min(6) as u8;
                self.main.cursor.shape = shape;
                self.alternate.cursor.shape = shape;
                self.dirty();
            }
            b'p' if body.contains(&b'!') => self.reset(),
            _ => self.unsupported(),
        }
    }
    fn sgr_raw(&mut self, raw: &[u8]) {
        let mut plain = Vec::new();
        for field in raw.split(|&byte| byte == b';') {
            if field.contains(&b':') {
                if !plain.is_empty() {
                    self.sgr(&plain, false);
                    plain.clear();
                }
                let values: Vec<Option<u16>> =
                    field.split(|&byte| byte == b':').map(sgr_number).collect();
                self.sgr(&values, true);
            } else {
                plain.push(sgr_number(field));
            }
        }
        if !plain.is_empty() {
            self.sgr(&plain, false);
        }
    }
    fn sgr(&mut self, params: &[Option<u16>], colon: bool) {
        let mut index = 0;
        while index < params.len() {
            let value = params[index].unwrap_or(0);
            index += 1;
            match value {
                0 => self.style = Style::default(),
                1 => self.style.bold = true,
                2 => self.style.faint = true,
                3 => self.style.italic = true,
                4 | 21 => {
                    self.style.underline = true;
                    self.style.underline_kind = if value == 21 { 2 } else { 1 };
                    if colon {
                        if let Some(kind) = params.get(index).copied().flatten() {
                            index = params.len();
                            if kind <= 5 {
                                self.style.underline = kind != 0;
                                self.style.underline_kind = kind as u8;
                            } else {
                                self.unsupported();
                            }
                        }
                    }
                }
                5 | 6 => self.style.blink = true,
                7 => self.style.inverse = true,
                8 => self.style.hidden = true,
                9 => self.style.strike = true,
                22 => {
                    self.style.bold = false;
                    self.style.faint = false;
                }
                23 => self.style.italic = false,
                24 => {
                    self.style.underline = false;
                    self.style.underline_kind = 0;
                }
                25 => self.style.blink = false,
                27 => self.style.inverse = false,
                28 => self.style.hidden = false,
                29 => self.style.strike = false,
                30..=37 => self.style.foreground = Color::Indexed((value - 30) as u8),
                40..=47 => self.style.background = Color::Indexed((value - 40) as u8),
                90..=97 => self.style.foreground = Color::Indexed((value - 90 + 8) as u8),
                100..=107 => self.style.background = Color::Indexed((value - 100 + 8) as u8),
                39 => self.style.foreground = Color::Default,
                49 => self.style.background = Color::Default,
                59 => self.style.underline_color = Color::Default,
                38 | 48 | 58 => {
                    let mode = params.get(index).copied().flatten();
                    index += 1;
                    let color = match mode {
                        Some(5) => {
                            let color = params
                                .get(index)
                                .copied()
                                .flatten()
                                .filter(|value| *value <= 255)
                                .map(|value| Color::Indexed(value as u8));
                            index += 1;
                            color
                        }
                        Some(2) => {
                            if colon
                                && params.len() >= index + 4
                                && matches!(params.get(index), Some(None | Some(0)))
                            {
                                index += 1;
                            }
                            let red = params.get(index).copied().flatten();
                            let green = params.get(index + 1).copied().flatten();
                            let blue = params.get(index + 2).copied().flatten();
                            index += 3;
                            red.zip(green)
                                .zip(blue)
                                .filter(|&((r, g), b)| r <= 255 && g <= 255 && b <= 255)
                                .map(|((r, g), b)| Color::Rgb(r as u8, g as u8, b as u8))
                        }
                        _ => None,
                    };
                    if let Some(color) = color {
                        if value == 38 {
                            self.style.foreground = color;
                        } else if value == 48 {
                            self.style.background = color;
                        } else {
                            self.style.underline_color = color;
                        }
                    } else {
                        self.unsupported();
                    }
                }
                _ => self.unsupported(),
            }
            // A colon field is one attribute atom. Its trailing subparameters
            // must never become independent SGR resets/bold/underline codes.
            if colon {
                index = params.len();
            }
        }
    }
    fn osc(&mut self, bytes: &[u8]) {
        if self.osc_palette(bytes) {
            return;
        }
        let text = String::from_utf8_lossy(bytes);
        let Some((code, body)) = text.split_once(';') else {
            self.unsupported();
            return;
        };
        match code {
            "0" | "1" | "2" => {
                self.title = body
                    .chars()
                    .filter(|ch| !ch.is_control())
                    .take(512)
                    .collect();
                self.dirty();
            }
            // No OSC output is allowed to read/write the user's clipboard or open a URI.
            "52" => self.unsupported(),
            _ => self.unsupported(),
        }
    }
    /// Resize the main screen with soft-line reflow; the alternate screen remains a
    /// positional grid. The actor applies TIOCSWINSZ only after this succeeds.
    pub fn resize(&mut self, geometry: Geometry) -> Result<(), String> {
        let geometry = geometry.validate()?;
        if geometry.version <= self.geometry.version {
            return Err("终端几何版本已过期".into());
        }
        if geometry.columns == self.geometry.columns && geometry.rows == self.geometry.rows {
            self.geometry = geometry;
            self.revision = self.revision.saturating_add(1);
            return Ok(());
        }
        let columns = usize::from(geometry.columns);
        let rows = usize::from(geometry.rows);
        let old_columns = usize::from(self.geometry.columns);
        let old_cursor = self.main.cursor;
        let old_wrap = self.main.wrap_pending;
        let cursor_id = self.main.rows[old_cursor.row].id;
        let mut old = Vec::with_capacity(self.history.len() + self.main.rows.len());
        old.extend(self.history.iter().cloned());
        old.extend(self.main.rows.iter().cloned());
        let mut rebuilt: VecDeque<Arc<Row>> = VecDeque::new();
        let mut rebuilt_bytes = 0usize;
        let mut discarded = 0usize;
        let mut total_rows = 0usize;
        let limit = HISTORY_BYTES.saturating_add(
            rows.saturating_mul(columns)
                .saturating_mul(std::mem::size_of::<Cell>() + CLUSTER_BYTES),
        );
        let mut output = self.blank_row_for(columns);
        let mut reflow_rows = std::collections::BTreeMap::new();
        let mut column = 0;
        let mut new_cursor = None;
        for line in old {
            let last = if line.wrapped {
                line.cells.len()
            } else {
                line.cells
                    .iter()
                    .rposition(|cell| {
                        cell.width != 0 && (cell.text != " " || cell.style != Style::default())
                    })
                    .map_or(0, |index| index + usize::from(line.cells[index].width))
            };
            let last = if line.id == cursor_id {
                last.max(old_cursor.column + 1)
            } else {
                last
            };
            let mut mapped = false;
            for (old_column, cell) in line.cells.iter().enumerate().take(last) {
                if cell.width == 0 {
                    continue;
                }
                let width = usize::from(cell.width).min(columns);
                if column + width > columns {
                    Arc::make_mut(&mut output).wrapped = true;
                    push_reflow(
                        &mut rebuilt,
                        output,
                        &mut rebuilt_bytes,
                        &mut discarded,
                        rows,
                        limit,
                    );
                    total_rows += 1;
                    output = self.blank_row_for(columns);
                    column = 0;
                }
                if !mapped {
                    reflow_rows.insert(line.id, output.id);
                    mapped = true;
                }
                if line.id == cursor_id
                    && (old_column..old_column + usize::from(cell.width))
                        .contains(&old_cursor.column)
                {
                    new_cursor = Some((
                        total_rows,
                        column
                            + if old_wrap {
                                width
                            } else {
                                old_cursor.column - old_column
                            },
                    ));
                }
                let target = Arc::make_mut(&mut output);
                let mut cell = cell.clone();
                cell.width = width as u8;
                target.cells[column] = cell;
                if width == 2 {
                    target.cells[column + 1] = Cell::continuation(target.cells[column].style);
                }
                column += width;
            }
            if !mapped {
                reflow_rows.insert(line.id, output.id);
            }
            if !line.wrapped {
                push_reflow(
                    &mut rebuilt,
                    output,
                    &mut rebuilt_bytes,
                    &mut discarded,
                    rows,
                    limit,
                );
                total_rows += 1;
                output = self.blank_row_for(columns);
                column = 0;
            }
        }
        if column > 0 {
            push_reflow(
                &mut rebuilt,
                output,
                &mut rebuilt_bytes,
                &mut discarded,
                rows,
                limit,
            );
            total_rows += 1;
        }
        while rebuilt.len() < rows {
            push_reflow(
                &mut rebuilt,
                self.blank_row_for(columns),
                &mut rebuilt_bytes,
                &mut discarded,
                rows,
                limit,
            );
            total_rows += 1;
        }
        let cursor = new_cursor.unwrap_or((total_rows.saturating_sub(1), 0));
        // Remove only blank rows below the cursor when shrinking; preserve nonblank
        // content in scrollback rather than cutting the middle of a logical line.
        while rebuilt.len() > rows
            && discarded + rebuilt.len() - 1 > cursor.0
            && rebuilt.back().is_some_and(|row| {
                row.cells
                    .iter()
                    .all(|cell| cell.text == " " || cell.width == 0)
            })
        {
            rebuilt.pop_back();
        }
        let start = rebuilt.len().saturating_sub(rows);
        self.history.clear();
        self.history_bytes = 0;
        for row in rebuilt.iter().take(start) {
            self.retain_history(row.clone());
        }
        self.main.rows = rebuilt.into_iter().skip(start).collect();
        self.main.cursor.column = cursor.1.min(columns - 1);
        self.main.cursor.row = cursor.0.saturating_sub(discarded + start).min(rows - 1);
        self.main.wrap_pending = old_wrap && cursor.1 >= columns;
        let retained: std::collections::BTreeSet<_> = self
            .history
            .iter()
            .chain(self.main.rows.iter())
            .map(|row| row.id)
            .collect();
        reflow_rows.retain(|_, target| retained.contains(target));
        // Compose skipped resize frames, but bound older aliases to one extra
        // history-sized window. Every target must still be a retained row.
        let alias_limit = reflow_rows.len().saturating_add(HISTORY_ROWS + 1000);
        for (old_id, previous_id) in self.reflow_rows.iter() {
            if reflow_rows.len() >= alias_limit {
                break;
            }
            if let Some(target) = reflow_rows.get(previous_id).copied() {
                reflow_rows.entry(*old_id).or_insert(target);
            }
        }
        self.reflow_rows = Arc::new(reflow_rows);
        if discarded > 0 {
            self.trimmed = true;
        }
        let mut alternate = Vec::with_capacity(rows);
        for index in 0..rows {
            let mut line = if let Some(line) = self.alternate.rows.get(index) {
                (**line).clone()
            } else {
                (*self.blank_row_for(columns)).clone()
            };
            line.cells.resize(columns, Cell::blank(self.style));
            line.cells.truncate(columns);
            line.repair(self.style);
            line.revision = line.revision.saturating_add(1);
            alternate.push(Arc::new(line));
        }
        self.alternate.rows = alternate;
        self.alternate.cursor.column = self.alternate.cursor.column.min(columns - 1);
        self.alternate.cursor.row = self.alternate.cursor.row.min(rows - 1);
        self.alternate.wrap_pending = false;
        self.geometry = geometry;
        self.top = 0;
        self.bottom = rows;
        self.left = 0;
        self.right = columns;
        self.tabs.resize(columns, false);
        for column in old_columns..columns {
            self.tabs[column] = column > 0 && column % 8 == 0;
        }
        self.last_print = None;
        self.revision = self.revision.saturating_add(1);
        Ok(())
    }
    fn blank_row_for(&mut self, columns: usize) -> Arc<Row> {
        self.next_id = self.next_id.saturating_add(1);
        Arc::new(Row::blank(self.next_id, columns, Style::default()))
    }
}

fn sgr_number(bytes: &[u8]) -> Option<u16> {
    if bytes.is_empty() {
        return None;
    }
    Some(bytes.iter().fold(0u32, |value, byte| {
        (value * 10 + u32::from(*byte - b'0')).min(u16::MAX as u32)
    }) as u16)
}

fn push_reflow(
    rows: &mut VecDeque<Arc<Row>>,
    row: Arc<Row>,
    bytes: &mut usize,
    discarded: &mut usize,
    screen_rows: usize,
    limit: usize,
) {
    *bytes = bytes.saturating_add(row.estimated_bytes());
    rows.push_back(row);
    while rows.len() > screen_rows && (rows.len() > HISTORY_ROWS + screen_rows || *bytes > limit) {
        if let Some(old) = rows.pop_front() {
            *bytes = bytes.saturating_sub(old.estimated_bytes());
            *discarded = discarded.saturating_add(1);
        } else {
            break;
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Modifiers {
    pub control: bool,
    pub alt: bool,
    pub shift: bool,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Key {
    Escape,
    Tab,
    Enter,
    Backspace,
    Delete,
    Insert,
    Left,
    Up,
    Down,
    Right,
    Home,
    End,
    PageUp,
    PageDown,
    Function(u8),
    Keypad(char),
    NumLock,
}
#[derive(Clone, Debug)]
pub enum Input {
    HardwareKeypad {
        key: char,
        num_lock: bool,
        modifiers: Modifiers,
    },
    Key {
        key: Key,
        modifiers: Modifiers,
        toolbar: bool,
    },
    Text {
        text: String,
        modifiers: Modifiers,
    },
    Paste(String),
    Mouse {
        column: u16,
        row: u16,
        button: u8,
        pressed: bool,
        modifiers: Modifiers,
    },
    Focus(bool),
    Clear,
}
pub fn encode_input(input: &Input, modes: Modes) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    match input {
        Input::HardwareKeypad {
            key,
            num_lock,
            modifiers,
        } => return keypad::encode_hardware(*key, *num_lock, *modifiers, modes),
        Input::Clear => (),
        Input::Focus(focused) => {
            if modes.focus_reporting {
                bytes.extend_from_slice(if *focused { b"\x1b[I" } else { b"\x1b[O" });
            }
        }
        Input::Text { text, modifiers } => {
            if text.len() > 16 * 1024 {
                return Err("终端单次输入超过16 KiB".into());
            }
            for ch in text.chars() {
                if modifiers.alt {
                    bytes.push(0x1b);
                }
                if modifiers.control {
                    let code = match ch {
                        ' ' | '@' | '2' => Some(0),
                        '3' | '[' => Some(27),
                        '4' | '\\' => Some(28),
                        '5' | ']' => Some(29),
                        '6' | '^' => Some(30),
                        '7' | '_' => Some(31),
                        '8' | '?' => Some(127),
                        '@'..='_' | 'a'..='z' => Some((ch as u8) & 0x1f),
                        '\u{7f}' => Some(0x7f),
                        _ => None,
                    };
                    if let Some(code) = code {
                        bytes.push(code);
                        continue;
                    }
                }
                let mut buffer = [0; 4];
                bytes.extend_from_slice(ch.encode_utf8(&mut buffer).as_bytes());
            }
        }
        Input::Paste(text) => {
            if text.len() > 16 * 1024 {
                return Err("终端每次粘贴最多16 KiB".into());
            }
            let filtered: String = text
                .chars()
                .filter(|ch| *ch != '\u{1b}' && !('\u{80}'..='\u{9f}').contains(ch))
                .collect();
            let filtered = filtered.replace("\r\n", "\r").replace('\n', "\r");
            if modes.bracketed_paste {
                bytes.extend_from_slice(b"\x1b[200~");
            }
            bytes.extend_from_slice(filtered.as_bytes());
            if modes.bracketed_paste {
                bytes.extend_from_slice(b"\x1b[201~");
            }
        }
        Input::Key {
            key,
            modifiers,
            toolbar,
        } => {
            let modified = modifiers.control || modifiers.alt || modifiers.shift;
            let modifier = 1
                + u8::from(modifiers.shift)
                + 2 * u8::from(modifiers.alt)
                + 4 * u8::from(modifiers.control);
            let arrow = match key {
                Key::Up => Some('A'),
                Key::Down => Some('B'),
                Key::Right => Some('C'),
                Key::Left => Some('D'),
                Key::Home => Some('H'),
                Key::End => Some('F'),
                _ => None,
            };
            let sequence = if let Some(arrow) = arrow {
                if *toolbar && modifiers.alt && !modifiers.control {
                    format!("\x1b\x1b[{arrow}")
                } else if modified {
                    format!("\x1b[1;{modifier}{arrow}")
                } else {
                    format!(
                        "\x1b{}{arrow}",
                        if modes.application_cursor { "O" } else { "[" }
                    )
                }
            } else {
                match key {
                    Key::NumLock => if modes.application_keypad {
                        "\x1bOP"
                    } else {
                        ""
                    }
                    .into(),
                    Key::Escape => "\x1b".into(),
                    Key::Tab => if modifiers.shift { "\x1b[Z" } else { "\t" }.into(),
                    Key::Enter => if modifiers.alt { "\x1b\r" } else { "\r" }.into(),
                    Key::Backspace => {
                        if modifiers.alt {
                            bytes.push(0x1b);
                        }
                        if modifiers.control { "\x08" } else { "\x7f" }.into()
                    }
                    Key::Keypad(ch) => {
                        if *ch == ',' {
                            ",".into()
                        } else if modes.application_keypad {
                            let code = match ch {
                                '0'..='9' => char::from(b'p' + (*ch as u8 - b'0')),
                                '.' => 'n',
                                '+' => 'k',
                                '-' => 'm',
                                '*' => 'j',
                                '/' => 'o',
                                '=' => 'X',
                                '\r' | '\n' => 'M',
                                _ => return Err("不支持的终端数字键".into()),
                            };
                            if modified {
                                format!("\x1bO;{modifier}{code}")
                            } else {
                                format!("\x1bO{code}")
                            }
                        } else if matches!(ch, '\r' | '\n') {
                            "\n".into()
                        } else {
                            ch.to_string()
                        }
                    }
                    Key::Insert | Key::Delete | Key::PageUp | Key::PageDown => {
                        let code = match key {
                            Key::Insert => 2,
                            Key::Delete => 3,
                            Key::PageUp => 5,
                            _ => 6,
                        };
                        if modified {
                            format!("\x1b[{code};{modifier}~")
                        } else {
                            format!("\x1b[{code}~")
                        }
                    }
                    Key::Function(number) if (1..=4).contains(number) => {
                        let letter = char::from(b'P' + number - 1);
                        if modified {
                            format!("\x1b[1;{modifier}{letter}")
                        } else {
                            format!("\x1bO{letter}")
                        }
                    }
                    Key::Function(number) if (5..=12).contains(number) => {
                        let code = [15, 17, 18, 19, 20, 21, 23, 24][usize::from(*number - 5)];
                        if modified {
                            format!("\x1b[{code};{modifier}~")
                        } else {
                            format!("\x1b[{code}~")
                        }
                    }
                    _ => return Err("不支持的终端功能键".into()),
                }
            };
            if *toolbar && modifiers.alt && matches!(key, Key::Escape | Key::Tab) {
                bytes.push(0x1b);
            }
            bytes.extend_from_slice(sequence.as_bytes());
        }
        Input::Mouse {
            column,
            row,
            button,
            pressed,
            modifiers,
        } => {
            if modes.mouse_tracking == 0 {
                return Ok(bytes);
            }
            let code = u16::from(*button)
                + 4 * u16::from(modifiers.shift)
                + 8 * u16::from(modifiers.alt)
                + 16 * u16::from(modifiers.control);
            if modes.sgr_mouse {
                bytes.extend_from_slice(
                    format!(
                        "\x1b[<{code};{};{}{}",
                        column.saturating_add(1),
                        row.saturating_add(1),
                        if *pressed { 'M' } else { 'm' }
                    )
                    .as_bytes(),
                );
            } else if *column < 223 && *row < 223 {
                bytes.extend_from_slice(&[
                    0x1b,
                    b'[',
                    b'M',
                    if *pressed {
                        code.min(223) as u8 + 32
                    } else {
                        35
                    },
                    *column as u8 + 33,
                    *row as u8 + 33,
                ]);
            }
        }
    }
    if bytes.len() > 16 * 1024 + 16 {
        return Err("终端输入编码超过预算".into());
    }
    Ok(bytes)
}
