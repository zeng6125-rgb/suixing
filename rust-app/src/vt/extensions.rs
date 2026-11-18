//! Additive controls from the original Termux emulator. Parsing/state only;
//! every response stays under the parent's existing 16 KiB reply budget.
use super::{Cell, Color, Input, Key, Modifiers, Parser, Style, VtEngine};
use std::sync::Arc;

#[derive(Clone, Debug)]
pub struct Palette {
    pub colors: [[u8; 3]; 256],
    pub foreground: [u8; 3],
    pub background: [u8; 3],
    pub cursor: [u8; 3],
}
impl Default for Palette {
    fn default() -> Self {
        let mut colors = [[0; 3]; 256];
        let base = [
            [0, 0, 0],
            [205, 0, 0],
            [0, 205, 0],
            [205, 205, 0],
            [100, 149, 237],
            [205, 0, 205],
            [0, 205, 205],
            [229, 229, 229],
            [127, 127, 127],
            [255, 0, 0],
            [0, 255, 0],
            [255, 255, 0],
            [92, 92, 255],
            [255, 0, 255],
            [0, 255, 255],
            [255, 255, 255],
        ];
        colors[..16].copy_from_slice(&base);
        for index in 16..232 {
            let value = index - 16;
            let level = |value: usize| if value == 0 { 0 } else { 55 + 40 * value as u8 };
            colors[index] = [level(value / 36), level(value / 6 % 6), level(value % 6)];
        }
        for index in 232..256 {
            colors[index] = [8 + 10 * (index - 232) as u8; 3];
        }
        Self {
            colors,
            foreground: [255; 3],
            background: [0; 3],
            cursor: [255; 3],
        }
    }
}
#[derive(Clone, Default)]
pub(super) struct StringUtf8 {
    remaining: u8,
    min: u8,
    max: u8,
}
impl StringUtf8 {
    /// Scalar state survives a dropped string buffer. A valid UTF-8 tail 0x9c
    /// cannot terminate OSC/DCS and leak the remaining payload into the screen.
    pub(super) fn consume(&mut self, byte: u8) -> bool {
        if self.remaining > 0 && byte >= self.min && byte <= self.max {
            self.remaining -= 1;
            self.min = 0x80;
            self.max = 0xbf;
            return true;
        }
        self.remaining = 0;
        let (remaining, min, max) = match byte {
            0xc2..=0xdf => (1, 0x80, 0xbf),
            0xe0 => (2, 0xa0, 0xbf),
            0xe1..=0xec | 0xee..=0xef => (2, 0x80, 0xbf),
            0xed => (2, 0x80, 0x9f),
            0xf0 => (3, 0x90, 0xbf),
            0xf1..=0xf3 => (3, 0x80, 0xbf),
            0xf4 => (3, 0x80, 0x8f),
            _ => (0, 0x80, 0xbf),
        };
        self.remaining = remaining;
        self.min = min;
        self.max = max;
        false
    }
}
impl VtEngine {
    pub(super) fn start_dcs(&mut self) {
        self.start_string(false);
        if let Parser::String { dcs, .. } = &mut self.parser {
            *dcs = true;
        }
    }
    pub(super) fn dcs(&mut self, bytes: &[u8]) {
        if bytes.len() > 8192 {
            self.unsupported();
            return;
        }
        if let Some(query) = bytes.strip_prefix(b"$q") {
            let value = match query {
                b"\"p" => Some("64;1\"p".into()),
                b"r" => Some(format!("{};{}r", self.top + 1, self.bottom)),
                b"s" => Some(format!(
                    "{};{}s",
                    self.horizontal_bounds().0 + 1,
                    self.horizontal_bounds().1
                )),
                b" q" => Some(format!(
                    "{} q",
                    (self.screen().cursor.shape.max(1) - 1) / 2 * 2
                        + if self.modes.cursor_blink { 1 } else { 2 }
                )),
                b"\"q" => Some(format!("{}\"q", u8::from(self.style.protected))),
                b"m" => Some(sgr_status(self.style)),
                _ => None,
            };
            let reply = match value {
                Some(value) => format!("\x1bP1$r{value}\x1b\\"),
                None => format!("\x1bP0$r{}\x1b\\", String::from_utf8_lossy(query)),
            };
            self.reply(reply.as_bytes());
            return;
        }
        if let Some(query) = bytes.strip_prefix(b"+q") {
            for (name_index, encoded) in query.split(|byte| *byte == b';').enumerate() {
                if name_index >= 32 {
                    self.unsupported();
                    break;
                }
                if encoded.is_empty()
                    || encoded.len() > 128
                    || encoded.len() % 2 != 0
                    || !encoded.iter().all(u8::is_ascii_hexdigit)
                {
                    self.unsupported();
                    continue;
                }
                let mut name = Vec::new();
                for pair in encoded.chunks_exact(2) {
                    name.push((digit(pair[0]) << 4) | digit(pair[1]));
                }
                let Ok(name) = std::str::from_utf8(&name) else {
                    self.unsupported();
                    continue;
                };
                let response = match name {
                    "Co" | "colors" => Some(b"256".to_vec()),
                    "TN" | "name" => Some(b"xterm".to_vec()),
                    _ => termcap(name, self.modes),
                };
                let encoded = String::from_utf8_lossy(encoded);
                let reply = match response {
                    Some(value) => format!("\x1bP1+r{encoded}={}\x1b\\", hex(&value)),
                    None => format!("\x1bP0+r{encoded}\x1b\\"),
                };
                self.reply(reply.as_bytes());
            }
            return;
        }
        self.unsupported();
    }
    /// Returning true means the complete sequence was consumed, including
    /// unsupported intermediates. It never falls through as a different CSI.
    pub(super) fn extended_csi(&mut self, raw: &[u8], command: u8) -> bool {
        let private = raw.first() == Some(&b'?');
        let secondary = raw.first() == Some(&b'>');
        if private
            && matches!(command, b'J' | b'K')
            && !raw.iter().any(|byte| (0x20..=0x2f).contains(byte))
        {
            let Some(params) = parameters(&raw[1..]) else {
                self.unsupported();
                return true;
            };
            let mode = params.first().copied().flatten().unwrap_or(0);
            self.selective_erase(mode, command == b'J');
            return true;
        }
        if secondary && command == b'm' {
            self.unsupported();
            return true;
        }
        let Some(split) = raw.iter().position(|byte| (0x20..=0x2f).contains(byte)) else {
            return false;
        };
        let body = if private || secondary {
            &raw[1..split]
        } else {
            &raw[..split]
        };
        let Some(params) = parameters(body) else {
            self.unsupported();
            return true;
        };
        let p = |index: usize, default: u16| {
            params
                .get(index)
                .copied()
                .flatten()
                .filter(|value| *value != 0)
                .unwrap_or(default)
        };
        let intermediate = &raw[split..];
        match (private, secondary, intermediate, command) {
            (true, false, b"$", b'p') => {
                let mode = params.first().copied().flatten().unwrap_or(0);
                let state = match mode {
                    1 => Some(self.modes.application_cursor),
                    5 => Some(self.modes.reverse_video),
                    6 => Some(self.origin),
                    7 => Some(self.modes.auto_wrap),
                    12 => Some(self.modes.cursor_blink),
                    69 => Some(self.modes.left_right_margins),
                    25 => Some(self.screen().cursor.visible),
                    66 => Some(self.modes.application_keypad),
                    47 | 1047 | 1049 => Some(self.modes.alternate),
                    1000 | 1002 | 1003 => Some(self.modes.mouse_tracking == mode),
                    1006 => Some(self.modes.sgr_mouse),
                    1004 => Some(self.modes.focus_reporting),
                    2004 => Some(self.modes.bracketed_paste),
                    _ => None,
                };
                let value = state.map_or(0, |enabled| if enabled { 1 } else { 2 });
                self.reply(format!("\x1b[?{mode};{value}$y").as_bytes());
            }
            (false, false, b" ", b'q') => {
                let shape = params.first().copied().flatten().unwrap_or(0);
                if shape <= 6 {
                    self.modes.cursor_blink = matches!(shape, 0 | 1 | 3 | 5);
                    self.main.cursor.shape = shape as u8;
                    self.alternate.cursor.shape = shape as u8;
                    self.dirty();
                } else {
                    self.unsupported();
                }
            }
            (false, false, b" ", b't' | b'u') => (),
            (false, false, b"!", b'p') => self.soft_reset(),
            (false, false, b"\"", b'q') => match params.first().copied().flatten().unwrap_or(0) {
                0 | 2 => self.style.protected = false,
                1 => self.style.protected = true,
                _ => self.unsupported(),
            },
            (false, false, b"*", b'x') => match params.first().copied().flatten().unwrap_or(0) {
                0 | 1 => self.rectangular_attributes = false,
                2 => self.rectangular_attributes = true,
                _ => self.unsupported(),
            },
            (false, false, b"$", b'x' | b'z' | b'{') => {
                let fill = if command == b'x' { p(0, 0) } else { 32 };
                if !((32..=126).contains(&fill) || (160..=255).contains(&fill)) {
                    return true;
                }
                let offset = usize::from(command == b'x');
                if let Some(rect) = self.rectangle(&params, offset) {
                    self.fill_rect(
                        rect,
                        char::from_u32(u32::from(fill)).unwrap_or(' '),
                        command == b'{',
                    );
                }
            }
            (false, false, b"$", b'v') => {
                if p(4, 1) > 1 || p(7, 1) > 1 {
                    self.unsupported();
                    return true;
                }
                if let Some(rect) = self.rectangle(&params, 0) {
                    let row = usize::from(p(5, 1)) - 1 + if self.origin { self.top } else { 0 };
                    let col = usize::from(p(6, 1)) - 1
                        + if self.origin {
                            self.horizontal_bounds().0
                        } else {
                            0
                        };
                    self.copy_rect(rect, col, row);
                }
            }
            (false, false, b"$", b'r' | b't') => {
                if let Some(rect) = self.rectangle(&params, 0) {
                    self.attributes_rect(rect, &params[params.len().min(4)..], command == b't');
                }
            }
            (false, false, b"'", b'}' | b'~') => {
                let count = usize::from(p(0, 1));
                self.columns_rect(count, command == b'}');
            }
            _ => self.unsupported(),
        }
        true
    }
    fn rectangle(
        &self,
        params: &[Option<u16>],
        offset: usize,
    ) -> Option<(usize, usize, usize, usize)> {
        let p = |index: usize, default: usize| {
            usize::from(
                params
                    .get(offset + index)
                    .copied()
                    .flatten()
                    .filter(|value| *value != 0)
                    .unwrap_or(default as u16),
            )
        };
        let top_margin = if self.origin { self.top } else { 0 };
        let bottom_margin = if self.origin {
            self.bottom
        } else {
            usize::from(self.geometry.rows)
        };
        let columns = usize::from(self.geometry.columns);
        let top = (p(0, 1) - 1 + top_margin).min(bottom_margin);
        let left_margin = if self.origin {
            self.horizontal_bounds().0
        } else {
            0
        };
        let right_margin = if self.origin {
            self.horizontal_bounds().1
        } else {
            columns
        };
        let left = (p(1, 1) - 1 + left_margin).min(right_margin);
        let bottom = (p(2, usize::from(self.geometry.rows)) + top_margin).min(bottom_margin);
        let right = (p(3, columns) + left_margin).min(right_margin);
        (top < bottom && left < right).then_some((top, left, bottom, right))
    }
    fn soft_reset(&mut self) {
        // DECSTR must not replace screen rows or erase scrollback. Preserve
        // the active grid and cursor position while resetting input modes.
        let alternate = self.modes.alternate;
        self.modes = super::Modes {
            alternate,
            ..Default::default()
        };
        self.style = Style::default();
        self.origin = false;
        self.insert = false;
        self.newline = false;
        self.top = 0;
        self.bottom = usize::from(self.geometry.rows);
        self.left = 0;
        self.right = usize::from(self.geometry.columns);
        self.rectangular_attributes = false;
        self.charset = [false; 2];
        self.active_charset = 0;
        self.tabs = (0..self.geometry.columns)
            .map(|column| column > 0 && column % 8 == 0)
            .collect();
        for screen in [&mut self.main, &mut self.alternate] {
            screen.wrap_pending = false;
            screen.saved = None;
            screen.cursor.visible = true;
            screen.cursor.shape = 0;
        }
        self.palette = Arc::new(Palette::default());
        self.last_print = None;
        self.dirty();
    }
    fn fill_rect(
        &mut self,
        (top, left, bottom, right): (usize, usize, usize, usize),
        fill: char,
        selective: bool,
    ) {
        let style = self.style;
        for y in top..bottom {
            let row = self.row_mut(y);
            for x in left..right {
                if selective && row.cells[x].style.protected {
                    continue;
                }
                let actual = if selective { row.cells[x].style } else { style };
                row.clear_cell(x, actual);
                row.cells[x] = Cell {
                    text: fill.to_string(),
                    width: 1,
                    style: actual,
                };
            }
            row.repair(style);
        }
        self.last_print = None;
    }
    fn copy_rect(
        &mut self,
        (top, left, bottom, right): (usize, usize, usize, usize),
        destination_left: usize,
        destination_top: usize,
    ) {
        let columns = usize::from(self.geometry.columns);
        let rows = usize::from(self.geometry.rows);
        if destination_left >= columns || destination_top >= rows {
            return;
        }
        let width = (right - left).min(columns - destination_left);
        let height = (bottom - top).min(rows - destination_top);
        let source: Vec<Vec<Cell>> = self.screen().rows[top..top + height]
            .iter()
            .map(|row| row.cells[left..left + width].to_vec())
            .collect();
        let style = self.style;
        for (y, mut cells) in source.into_iter().enumerate() {
            if cells.first().is_some_and(|cell| cell.width == 0) {
                cells[0] = Cell::blank(cells[0].style);
            }
            if cells.last().is_some_and(|cell| cell.width == 2) {
                let last = cells.len() - 1;
                cells[last] = Cell::blank(cells[last].style);
            }
            let row = self.row_mut(destination_top + y);
            for x in 0..width {
                row.clear_cell(destination_left + x, style);
            }
            row.cells[destination_left..destination_left + width].clone_from_slice(&cells);
            row.repair(style);
        }
        self.last_print = None;
    }
    fn attributes_rect(
        &mut self,
        (top, left, bottom, right): (usize, usize, usize, usize),
        attributes: &[Option<u16>],
        reverse: bool,
    ) {
        if attributes.is_empty() {
            return;
        }
        let rectangular = self.rectangular_attributes;
        let columns = usize::from(self.geometry.columns);
        for y in top..bottom {
            let from = if rectangular || y == top { left } else { 0 };
            let to = if rectangular || y + 1 == bottom {
                right
            } else {
                columns
            };
            let row = self.row_mut(y);
            for cell in &mut row.cells[from..to] {
                for attribute in attributes {
                    let attribute = attribute.unwrap_or(0);
                    if reverse {
                        match attribute {
                            0 => {
                                cell.style.bold ^= true;
                                cell.style.underline ^= true;
                                cell.style.blink ^= true;
                                cell.style.inverse ^= true
                            }
                            1 => cell.style.bold ^= true,
                            4 => cell.style.underline ^= true,
                            5 => cell.style.blink ^= true,
                            7 => cell.style.inverse ^= true,
                            _ => {}
                        }
                    } else {
                        match attribute {
                            0 => {
                                cell.style.bold = false;
                                cell.style.underline = false;
                                cell.style.blink = false;
                                cell.style.inverse = false
                            }
                            1 => cell.style.bold = true,
                            4 => cell.style.underline = true,
                            5 => cell.style.blink = true,
                            7 => cell.style.inverse = true,
                            22 => cell.style.bold = false,
                            24 => cell.style.underline = false,
                            25 => cell.style.blink = false,
                            27 => cell.style.inverse = false,
                            _ => {}
                        }
                    }
                }
            }
        }
        self.last_print = None;
    }
    fn columns_rect(&mut self, count: usize, insert: bool) {
        let cursor = self.screen().cursor;
        if self.in_horizontal_region(cursor.column) && (self.top..self.bottom).contains(&cursor.row)
        {
            self.shift_rectangle(
                self.top,
                self.bottom,
                cursor.column,
                self.horizontal_bounds().1,
                count,
                insert,
                self.style,
            );
        }
    }
    fn selective_erase(&mut self, mode: u16, display: bool) {
        if mode > 2 {
            self.unsupported();
            return;
        }
        let cursor = self.screen().cursor;
        let rows = usize::from(self.geometry.rows);
        let columns = usize::from(self.geometry.columns);
        for y in 0..rows {
            if !display && y != cursor.row {
                continue;
            }
            for x in 0..columns {
                let eligible = match mode {
                    0 => (y, x) >= (cursor.row, cursor.column),
                    1 => (y, x) <= (cursor.row, cursor.column),
                    _ => true,
                };
                if eligible && !self.screen().rows[y].cells[x].style.protected {
                    let style = self.style;
                    self.row_mut(y).clear_cell(x, style);
                }
            }
        }
        self.last_print = None;
    }
    pub(super) fn osc_palette(&mut self, bytes: &[u8]) -> bool {
        let Ok(text) = std::str::from_utf8(bytes) else {
            return false;
        };
        let (code, body) = text.split_once(';').unwrap_or((text, ""));
        let Some(code) = code
            .parse::<u16>()
            .ok()
            .filter(|code| matches!(code, 4 | 10 | 11 | 12 | 104 | 110 | 111 | 112))
        else {
            return false;
        };
        let base = Palette::default();
        if code == 4 {
            let parts: Vec<_> = body.split(';').collect();
            if parts.len() % 2 != 0 || parts.len() > 512 {
                self.unsupported();
                return true;
            }
            for pair in parts.chunks_exact(2) {
                let Some(index) = pair[0].parse::<usize>().ok().filter(|index| *index < 256) else {
                    self.unsupported();
                    continue;
                };
                if pair[1] == "?" {
                    self.color_reply(4, Some(index), self.palette.colors[index]);
                } else if let Some(color) = parse_color(pair[1]) {
                    Arc::make_mut(&mut self.palette).colors[index] = color;
                    self.dirty();
                } else {
                    self.unsupported();
                }
            }
        } else if code == 104 {
            if body.is_empty() {
                self.palette = Arc::new(base);
                self.dirty();
            } else {
                for (index, part) in body.split(';').enumerate() {
                    if index >= 256 {
                        self.unsupported();
                        break;
                    }
                    if let Some(index) = part.parse::<usize>().ok().filter(|index| *index < 256) {
                        Arc::make_mut(&mut self.palette).colors[index] = base.colors[index];
                        self.dirty();
                    } else {
                        self.unsupported();
                    }
                }
            }
        } else if code >= 110 {
            let palette = Arc::make_mut(&mut self.palette);
            match code {
                110 => palette.foreground = base.foreground,
                111 => palette.background = base.background,
                _ => palette.cursor = base.cursor,
            }
            self.dirty();
        } else {
            for (offset, spec) in body.split(';').enumerate() {
                let index = code as usize - 10 + offset;
                if index > 2 {
                    self.unsupported();
                    break;
                }
                let color = match index {
                    0 => self.palette.foreground,
                    1 => self.palette.background,
                    _ => self.palette.cursor,
                };
                if spec == "?" {
                    self.color_reply(10 + index as u16, None, color);
                } else if let Some(color) = parse_color(spec) {
                    let palette = Arc::make_mut(&mut self.palette);
                    match index {
                        0 => palette.foreground = color,
                        1 => palette.background = color,
                        _ => palette.cursor = color,
                    }
                    self.dirty();
                } else {
                    self.unsupported();
                }
            }
        }
        true
    }
    fn color_reply(&mut self, code: u16, index: Option<usize>, [r, g, b]: [u8; 3]) {
        let prefix = index.map_or(String::new(), |index| format!("{index};"));
        self.reply(
            format!(
                "\x1b]{code};{prefix}rgb:{:04x}/{:04x}/{:04x}\x1b\\",
                u16::from(r) * 257,
                u16::from(g) * 257,
                u16::from(b) * 257
            )
            .as_bytes(),
        );
    }
}
fn parameters(raw: &[u8]) -> Option<Vec<Option<u16>>> {
    if raw.len() > 256 {
        return None;
    }
    let mut result = Vec::new();
    for field in raw.split(|byte| *byte == b';') {
        if result.len() >= 64 {
            return None;
        }
        if field.is_empty() {
            result.push(None);
            continue;
        }
        if !field.iter().all(u8::is_ascii_digit) {
            return None;
        }
        let mut value = 0u32;
        for byte in field {
            value = (value * 10 + u32::from(*byte - b'0')).min(65535);
        }
        result.push(Some(value as u16));
    }
    Some(result)
}
fn parse_color(value: &str) -> Option<[u8; 3]> {
    if !value.is_ascii() {
        return None;
    }
    let parts: Vec<&str> = if let Some(value) = value.strip_prefix('#') {
        if !matches!(value.len(), 3 | 6 | 9 | 12) {
            return None;
        }
        let length = value.len() / 3;
        vec![
            &value[..length],
            &value[length..length * 2],
            &value[length * 2..],
        ]
    } else if let Some(value) = value.strip_prefix("rgb:") {
        value.split('/').collect()
    } else {
        return None;
    };
    if parts.len() != 3
        || parts.iter().any(|part| {
            !matches!(part.len(), 1..=4) || !part.bytes().all(|byte| byte.is_ascii_hexdigit())
        })
        || parts.iter().any(|part| part.len() != parts[0].len())
    {
        return None;
    }
    let mut result = [0; 3];
    for (index, part) in parts.iter().enumerate() {
        let value = u32::from_str_radix(part, 16).ok()?;
        let max = (1u32 << (part.len() * 4)) - 1;
        result[index] = (value * 255 / max) as u8;
    }
    Some(result)
}
fn digit(byte: u8) -> u8 {
    match byte {
        b'0'..=b'9' => byte - b'0',
        b'a'..=b'f' => byte - b'a' + 10,
        _ => byte - b'A' + 10,
    }
}
fn hex(bytes: &[u8]) -> String {
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        output.push(char::from(b"0123456789ABCDEF"[usize::from(byte >> 4)]));
        output.push(char::from(b"0123456789ABCDEF"[usize::from(byte & 15)]));
    }
    output
}
fn termcap(name: &str, modes: super::Modes) -> Option<Vec<u8>> {
    let mut modifiers = Modifiers::default();
    let key = match name {
        "%i" => {
            modifiers.shift = true;
            Key::Right
        }
        "#2" => {
            modifiers.shift = true;
            Key::Home
        }
        "#4" => {
            modifiers.shift = true;
            Key::Left
        }
        "*7" => {
            modifiers.shift = true;
            Key::End
        }
        "kb" => Key::Backspace,
        "kd" => Key::Down,
        "kh" | "K1" => Key::Home,
        "kl" => Key::Left,
        "kr" => Key::Right,
        "K3" | "kN" => Key::PageUp,
        "K4" | "@7" => Key::End,
        "K5" | "kP" => Key::PageDown,
        "ku" => Key::Up,
        "kB" => {
            modifiers.shift = true;
            Key::Tab
        }
        "kD" => Key::Delete,
        "kDN" | "kF" => {
            modifiers.shift = true;
            Key::Down
        }
        "kI" => Key::Insert,
        "kR" | "kUP" => {
            modifiers.shift = true;
            Key::Up
        }
        "@8" => Key::Keypad('\n'),
        "k;" => Key::Function(10),
        _ => {
            if name.len() == 2 && name.starts_with('k') {
                Key::Function(
                    name[1..]
                        .parse::<u8>()
                        .ok()
                        .filter(|number| (1..=9).contains(number))?,
                )
            } else if name.len() == 2 && name.starts_with('F') {
                let value = name.as_bytes()[1];
                let number = match value {
                    b'1' => 11,
                    b'2' => 12,
                    b'3'..=b'9' => {
                        modifiers.shift = true;
                        value - b'3' + 1
                    }
                    b'A'..=b'E' => {
                        modifiers.shift = true;
                        value - b'A' + 8
                    }
                    _ => return None,
                };
                Key::Function(number)
            } else {
                return None;
            }
        }
    };
    super::encode_input(
        &Input::Key {
            key,
            modifiers,
            toolbar: false,
        },
        modes,
    )
    .ok()
}
fn sgr_status(style: Style) -> String {
    let mut parts = vec!["0".to_owned()];
    for (enabled, code) in [
        (style.bold, 1),
        (style.faint, 2),
        (style.italic, 3),
        (style.underline, 4),
        (style.blink, 5),
        (style.inverse, 7),
        (style.hidden, 8),
        (style.strike, 9),
    ] {
        if enabled {
            parts.push(code.to_string());
        }
    }
    for (color, code) in [
        (style.foreground, 38),
        (style.background, 48),
        (style.underline_color, 58),
    ] {
        match color {
            Color::Default => {}
            Color::Indexed(index) => parts.push(format!("{code};5;{index}")),
            Color::Rgb(r, g, b) => parts.push(format!("{code};2;{r};{g};{b}")),
        }
    }
    format!("{}m", parts.join(";"))
}
