// SPDX-License-Identifier: Apache-2.0
// Termux v0.118.3; see THIRD_PARTY_NOTICES.md.

use super::buffer::Buffer;
use super::colors::{BACKGROUND, CURSOR, Colors, FOREGROUND, encode_style};
use super::width::width;
use super::{TerminalCallbacks, TerminalText};
use std::collections::HashMap;

const ESC_NONE: i32 = 0;
const ESC: i32 = 1;
const ESC_POUND: i32 = 2;
const ESC_SELECT_LEFT_PAREN: i32 = 3;
const ESC_SELECT_RIGHT_PAREN: i32 = 4;
const ESC_CSI: i32 = 6;
const ESC_CSI_QUESTIONMARK: i32 = 7;
const ESC_CSI_DOLLAR: i32 = 8;
const ESC_PERCENT: i32 = 9;
const ESC_OSC: i32 = 10;
const ESC_OSC_ESC: i32 = 11;
const ESC_CSI_BIGGERTHAN: i32 = 12;
const ESC_P: i32 = 13;
const ESC_CSI_QUESTIONMARK_ARG_DOLLAR: i32 = 14;
const ESC_CSI_ARGS_SPACE: i32 = 15;
const ESC_CSI_ARGS_ASTERIX: i32 = 16;
const ESC_CSI_DOUBLE_QUOTE: i32 = 17;
const ESC_CSI_SINGLE_QUOTE: i32 = 18;
const ESC_CSI_EXCLAMATION: i32 = 19;
const ESC_APC: i32 = 20;
const ESC_APC_ESCAPE: i32 = 21;
const ESC_CSI_UNSUPPORTED_PARAMETER_BYTE: i32 = 22;
const ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE: i32 = 23;

const APPLICATION_CURSOR: i32 = 1;
const REVERSE_VIDEO: i32 = 1 << 1;
const ORIGIN_MODE: i32 = 1 << 2;
const AUTOWRAP: i32 = 1 << 3;
const CURSOR_ENABLED: i32 = 1 << 4;
const APPLICATION_KEYPAD: i32 = 1 << 5;
const MOUSE_PRESS_RELEASE: i32 = 1 << 6;
const MOUSE_BUTTON: i32 = 1 << 7;
const SEND_FOCUS: i32 = 1 << 8;
const MOUSE_SGR: i32 = 1 << 9;
const BRACKETED_PASTE: i32 = 1 << 10;
const LEFT_RIGHT_MARGIN: i32 = 1 << 11;
const RECTANGULAR_ATTRIBUTE: i32 = 1 << 12;
const BOLD: i32 = 1;
const ITALIC: i32 = 1 << 1;
const UNDERLINE: i32 = 1 << 2;
const BLINK: i32 = 1 << 3;
const INVERSE: i32 = 1 << 4;
const INVISIBLE: i32 = 1 << 5;
const STRIKETHROUGH: i32 = 1 << 6;
const PROTECTED: i32 = 1 << 7;
const DIM: i32 = 1 << 8;
const REPLACEMENT: i32 = 0xfffd;

#[derive(Clone, Copy)]
struct SavedState {
    row: i32,
    column: i32,
    effect: i32,
    foreground: i32,
    background: i32,
    flags: i32,
    g0: bool,
    g1: bool,
    using_g0: bool,
}

impl Default for SavedState {
    fn default() -> Self {
        Self {
            row: 0,
            column: 0,
            effect: 0,
            foreground: 0,
            background: 0,
            flags: 0,
            g0: false,
            g1: false,
            using_g0: true,
        }
    }
}

pub(super) struct Event {
    pub kind: i32,
    pub text: Option<Vec<u16>>,
    pub previous: Option<Vec<u16>>,
}

pub(super) struct Emulator {
    pub main: Buffer,
    pub alt: Buffer,
    pub alternate: bool,
    pub rows: i32,
    pub columns: i32,
    pub cell_width: i32,
    pub cell_height: i32,
    pub cursor_row: i32,
    pub cursor_col: i32,
    pub cursor_style: i32,
    pub flags: i32,
    pub scroll_counter: i32,
    pub colors: Colors,
    pub events: Vec<Event>,
    pub callbacks: Option<TerminalCallbacks>,
    client_cursor_style: i32,
    title: Option<Vec<u16>>,
    title_stack: Vec<Option<Vec<u16>>>,
    arg_index: usize,
    args: [i32; 32],
    subparams: i32,
    osc: Vec<u16>,
    continuing: bool,
    escape: i32,
    saved_main: SavedState,
    saved_alt: SavedState,
    g0: bool,
    g1: bool,
    using_g0: bool,
    saved_flags: i32,
    insert: bool,
    tabs: Vec<bool>,
    top: i32,
    bottom: i32,
    left: i32,
    right: i32,
    auto_wrap_pending: bool,
    foreground: i32,
    background: i32,
    underline_color: i32,
    effect: i32,
    utf8_follow: usize,
    utf8_index: usize,
    utf8: [u8; 4],
    last_emitted: i32,
    categories: HashMap<i32, u8>,
}

impl Emulator {
    pub fn new(
        columns: i32,
        rows: i32,
        cell_width: i32,
        cell_height: i32,
        history: i32,
        cursor: i32,
        palette: [i32; 259],
    ) -> Self {
        let history = if (100..=50000).contains(&history) {
            history
        } else {
            2000
        };
        let mut result = Self {
            main: Buffer::new(columns, history, rows),
            alt: Buffer::new(columns, rows, rows),
            alternate: false,
            rows,
            columns,
            cell_width,
            cell_height,
            cursor_row: 0,
            cursor_col: 0,
            cursor_style: cursor,
            flags: 0,
            scroll_counter: 0,
            colors: Colors::new(palette),
            events: Vec::new(),
            callbacks: None,
            client_cursor_style: cursor,
            title: None,
            title_stack: Vec::new(),
            arg_index: 0,
            args: [0; 32],
            subparams: 0,
            osc: Vec::new(),
            continuing: false,
            escape: ESC_NONE,
            saved_main: SavedState::default(),
            saved_alt: SavedState::default(),
            g0: false,
            g1: false,
            using_g0: true,
            saved_flags: 0,
            insert: false,
            tabs: vec![false; columns as usize],
            top: 0,
            bottom: 0,
            left: 0,
            right: 0,
            auto_wrap_pending: false,
            foreground: 0,
            background: 0,
            underline_color: 0,
            effect: 0,
            utf8_follow: 0,
            utf8_index: 0,
            utf8: [0; 4],
            last_emitted: -1,
            categories: HashMap::new(),
        };
        result.reset();
        result
    }

    pub fn screen(&self) -> &Buffer {
        if self.alternate {
            &self.alt
        } else {
            &self.main
        }
    }
    pub fn screen_mut(&mut self) -> &mut Buffer {
        if self.alternate {
            &mut self.alt
        } else {
            &mut self.main
        }
    }

    fn flag(&self, bit: i32) -> bool {
        self.flags & bit != 0
    }

    fn set_flag(&mut self, bit: i32, set: bool) {
        if set {
            if bit == MOUSE_PRESS_RELEASE {
                self.set_flag(MOUSE_BUTTON, false);
            } else if bit == MOUSE_BUTTON {
                self.set_flag(MOUSE_PRESS_RELEASE, false);
            }
            self.flags |= bit;
        } else {
            self.flags &= !bit;
        }
    }

    fn dec_bit(mode: i32) -> i32 {
        match mode {
            1 => APPLICATION_CURSOR,
            5 => REVERSE_VIDEO,
            6 => ORIGIN_MODE,
            7 => AUTOWRAP,
            25 => CURSOR_ENABLED,
            66 => APPLICATION_KEYPAD,
            69 => LEFT_RIGHT_MARGIN,
            1000 => MOUSE_PRESS_RELEASE,
            1002 => MOUSE_BUTTON,
            1004 => SEND_FOCUS,
            1006 => MOUSE_SGR,
            2004 => BRACKETED_PASTE,
            _ => -1,
        }
    }

    fn event(&mut self, kind: i32, text: Vec<u16>) {
        self.events.push(Event {
            kind,
            text: Some(text),
            previous: Some(Vec::new()),
        });
    }

    fn write(&mut self, text: impl AsRef<str>) {
        self.event(1, text.as_ref().encode_utf16().collect());
    }

    fn style(&self) -> i64 {
        encode_style(self.foreground, self.background, self.effect)
    }

    fn block_clear(&mut self, x: i32, y: i32, columns: i32, rows: i32) {
        let style = self.style();
        self.screen_mut().block_set(x, y, columns, rows, 32, style);
    }

    fn block_copy(&mut self, x: i32, y: i32, columns: i32, rows: i32, dx: i32, dy: i32) {
        self.screen_mut().block_copy(x, y, columns, rows, dx, dy);
    }

    fn default_tabs(&mut self) {
        for (index, stop) in self.tabs.iter_mut().enumerate() {
            *stop = index & 7 == 0 && index != 0;
        }
    }

    pub fn resize(&mut self, columns: i32, rows: i32, cell_width: i32, cell_height: i32) {
        self.cell_width = cell_width;
        self.cell_height = cell_height;
        if self.rows == rows && self.columns == columns {
            return;
        }
        assert!(columns >= 2 && rows >= 2, "rows={rows}, columns={columns}");
        if self.rows != rows {
            self.rows = rows;
            self.top = 0;
            self.bottom = rows;
        }
        if self.columns != columns {
            let old = std::mem::replace(&mut self.tabs, vec![false; columns as usize]);
            self.columns = columns;
            self.default_tabs();
            let count = old.len().min(self.tabs.len());
            self.tabs[..count].copy_from_slice(&old[..count]);
            self.left = 0;
            self.right = columns;
        }
        self.resize_screen();
        for buffer in [&mut self.main, &mut self.alt] {
            for row in buffer.lines.iter_mut().flatten() {
                row.compress_style();
            }
        }
    }

    fn resize_screen(&mut self) {
        let mut cursor = [self.cursor_col, self.cursor_row];
        let total = if self.alternate {
            self.rows
        } else {
            self.main.total_rows
        };
        let (columns, rows, style, alternate) =
            (self.columns, self.rows, self.style(), self.alternate);
        self.screen_mut()
            .resize(columns, rows, total, &mut cursor, style, alternate);
        [self.cursor_col, self.cursor_row] = cursor;
    }

    pub fn set_cursor_style(&mut self, style: i32) {
        self.client_cursor_style = style;
        self.cursor_style = style;
    }

    pub fn paste(&mut self, text: &[u16]) {
        let filtered: Vec<u16> = text
            .iter()
            .copied()
            .filter(|&unit| unit != 27 && !(0x80..=0x9f).contains(&unit))
            .collect();
        let mut result = Vec::with_capacity(filtered.len());
        let mut index = 0;
        while index < filtered.len() {
            let unit = filtered[index];
            if unit == 13 && filtered.get(index + 1) == Some(&10) {
                index += 1;
            }
            result.push(if unit == 10 { 13 } else { unit });
            index += 1;
        }
        let bracketed = self.flag(BRACKETED_PASTE);
        if bracketed {
            self.write("\x1b[200~");
        }
        self.event(1, result);
        if bracketed {
            self.write("\x1b[201~");
        }
    }

    pub fn mouse(&mut self, mut button: i32, column: i32, row: i32, pressed: bool) {
        let column = column.clamp(1, self.columns);
        let row = row.clamp(1, self.rows);
        if button == 32 && !self.flag(MOUSE_BUTTON) {
            return;
        }
        if self.flag(MOUSE_SGR) {
            self.write(format!(
                "\x1b[<{button};{column};{row}{}",
                if pressed { 'M' } else { 'm' }
            ));
        } else {
            if !pressed {
                button = 3;
            }
            if column <= 223 && row <= 223 {
                self.event(
                    8,
                    vec![
                        27,
                        91,
                        77,
                        (32 + button) as u8 as u16,
                        (32 + column) as u16,
                        (32 + row) as u16,
                    ],
                );
            }
        }
    }

    fn cursor_row(&mut self, row: i32) {
        self.cursor_row = row;
        self.auto_wrap_pending = false;
    }
    fn cursor_col(&mut self, column: i32) {
        self.cursor_col = column;
        self.auto_wrap_pending = false;
    }
    fn cursor(&mut self, row: i32, column: i32) {
        self.cursor_row = row.clamp(0, self.rows - 1);
        self.cursor_col = column.clamp(0, self.columns - 1);
        self.auto_wrap_pending = false;
    }

    pub fn clear_screen(&mut self) {
        self.block_clear(0, 0, self.columns, self.rows);
        for row in 0..self.rows {
            self.screen_mut().set_wrap(row, false);
        }
        self.screen_mut().clear_transcript();
        self.cursor(0, 0);
        self.scroll_counter = 0;
    }

    pub fn reset(&mut self) {
        self.cursor_style = self.client_cursor_style;
        self.arg_index = 0;
        self.continuing = false;
        self.escape = ESC_NONE;
        self.insert = false;
        self.top = 0;
        self.left = 0;
        self.bottom = self.rows;
        self.right = self.columns;
        self.auto_wrap_pending = false;
        self.foreground = FOREGROUND as i32;
        self.background = BACKGROUND as i32;
        self.default_tabs();
        self.g0 = false;
        self.g1 = false;
        self.using_g0 = true;
        self.flags = 0;
        self.set_flag(AUTOWRAP, true);
        self.set_flag(CURSOR_ENABLED, true);
        self.saved_flags = self.flags;
        for saved in [&mut self.saved_main, &mut self.saved_alt] {
            saved.row = 0;
            saved.column = 0;
            saved.effect = 0;
            saved.flags = self.flags;
            saved.foreground = self.foreground;
            saved.background = self.background;
        }
        self.utf8_index = 0;
        self.utf8_follow = 0;
        self.colors.reset();
        self.event(3, Vec::new());
    }

    fn set_title(&mut self, title: Option<Vec<u16>>) {
        let old = std::mem::replace(&mut self.title, title);
        if old != self.title {
            self.events.push(Event {
                kind: 4,
                text: self.title.clone(),
                previous: old,
            });
        }
    }

    fn prepare_types(&mut self, input: &[u8]) {
        self.categories.clear();
        let mut pending = self.utf8[..self.utf8_index].to_vec();
        pending.extend_from_slice(input);
        let mut points = Vec::new();
        let mut at = 0;
        while at < pending.len() {
            let first = pending[at];
            let count = if first & 0xe0 == 0xc0 {
                2
            } else if first & 0xf0 == 0xe0 {
                3
            } else if first & 0xf8 == 0xf0 {
                4
            } else {
                1
            };
            if count == 1 || at + count > pending.len() {
                at += 1;
                continue;
            }
            let mut cp = (first
                & if count == 2 {
                    31
                } else if count == 3 {
                    15
                } else {
                    7
                }) as i32;
            let mut valid = true;
            for &next in &pending[at + 1..at + count] {
                if next & 0xc0 != 0x80 {
                    valid = false;
                    break;
                }
                cp = (cp << 6) | (next & 63) as i32;
            }
            if valid {
                if cp <= 0x10ffff
                    && !(0xd800..=0xdfff).contains(&cp)
                    && self.categories.insert(cp, 255).is_none()
                {
                    points.push(cp);
                }
                at += count;
            } else {
                at += 1;
            }
        }
        if !points.is_empty() {
            if self.categories.insert(REPLACEMENT, 255).is_none() {
                points.push(REPLACEMENT);
            }
            let callback = self.callbacks.unwrap();
            let mut types = vec![255; points.len()];
            if unsafe {
                (callback.types)(
                    callback.context,
                    points.as_ptr(),
                    points.len(),
                    types.as_mut_ptr(),
                )
            } == 0
            {
                for (point, kind) in points.into_iter().zip(types) {
                    self.categories.insert(point, kind);
                }
            }
        }
    }

    fn category(&self, cp: i32) -> i32 {
        if let Some(&kind) = self.categories.get(&cp).filter(|&&kind| kind != 255) {
            kind as i32
        } else {
            let callback = self.callbacks.unwrap();
            unsafe { (callback.category)(callback.context, cp) }
        }
    }

    pub fn append(&mut self, input: &[u8]) {
        self.prepare_types(input);
        for &byte in input {
            self.process_byte(byte);
        }
        self.categories.clear();
    }

    fn process_byte(&mut self, byte: u8) {
        if self.utf8_follow > 0 {
            if byte & 0xc0 == 0x80 {
                self.utf8[self.utf8_index] = byte;
                self.utf8_index += 1;
                self.utf8_follow -= 1;
                if self.utf8_follow == 0 {
                    let mask = if self.utf8_index == 2 {
                        31
                    } else if self.utf8_index == 3 {
                        15
                    } else {
                        7
                    };
                    let mut cp = (self.utf8[0] & mask) as i32;
                    for &next in &self.utf8[1..self.utf8_index] {
                        cp = (cp << 6) | (next & 63) as i32;
                    }
                    if (cp <= 127 && self.utf8_index > 1)
                        || (cp < 2047 && self.utf8_index > 2)
                        || (cp < 65535 && self.utf8_index > 3)
                    {
                        cp = REPLACEMENT;
                    }
                    self.utf8_index = 0;
                    self.utf8_follow = 0;
                    if !(0x80..=0x9f).contains(&cp) {
                        if matches!(self.category(cp), 0 | 19) {
                            cp = REPLACEMENT;
                        }
                        self.process_code_point(cp);
                    }
                }
            } else {
                self.utf8_index = 0;
                self.utf8_follow = 0;
                self.emit(REPLACEMENT);
                self.process_byte(byte);
            }
        } else {
            if byte & 0x80 == 0 {
                self.process_code_point(byte as i32);
                return;
            }
            self.utf8_follow = if byte & 0xe0 == 0xc0 {
                1
            } else if byte & 0xf0 == 0xe0 {
                2
            } else if byte & 0xf8 == 0xf0 {
                3
            } else {
                self.process_code_point(REPLACEMENT);
                return;
            };
            self.utf8[self.utf8_index] = byte;
            self.utf8_index += 1;
        }
    }

    pub fn process_code_point(&mut self, b: i32) {
        if self.escape == ESC_APC {
            if b == 27 {
                self.continue_sequence(ESC_APC_ESCAPE);
            }
            return;
        }
        if self.escape == ESC_APC_ESCAPE {
            if b == b'\\' as i32 {
                self.finish_sequence();
            } else {
                self.continue_sequence(ESC_APC);
            }
            return;
        }
        match b {
            0 => {}
            7 => {
                if self.escape == ESC_OSC {
                    self.do_osc(b);
                } else {
                    self.event(2, Vec::new());
                }
            }
            8 => {
                if self.left == self.cursor_col {
                    let row = self.cursor_row - 1;
                    if row >= 0 && self.screen().wrap(row) {
                        self.screen_mut().set_wrap(row, false);
                        self.cursor(row, self.right - 1);
                    }
                } else {
                    self.cursor_col(self.cursor_col - 1);
                }
            }
            9 => self.cursor_col = self.next_tab(1),
            10..=12 => self.linefeed(),
            13 => self.cursor_col(self.left),
            14 => self.using_g0 = false,
            15 => self.using_g0 = true,
            24 | 26 => {
                if self.escape != ESC_NONE {
                    self.escape = ESC_NONE;
                    self.emit(127);
                }
            }
            27 => {
                if self.escape == ESC_P {
                    return;
                }
                if self.escape != ESC_OSC {
                    self.start_escape();
                } else {
                    self.do_osc(b);
                }
            }
            _ => {
                self.continuing = false;
                match self.escape {
                    ESC_NONE => {
                        if b >= 32 {
                            self.emit(b);
                        }
                    }
                    ESC => self.do_esc(b),
                    ESC_POUND => self.esc_pound(b),
                    ESC_SELECT_LEFT_PAREN => self.g0 = b == b'0' as i32,
                    ESC_SELECT_RIGHT_PAREN => self.g1 = b == b'0' as i32,
                    ESC_CSI => self.do_csi(b),
                    ESC_CSI_UNSUPPORTED_PARAMETER_BYTE | ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE => {
                        self.unsupported_csi(b)
                    }
                    ESC_CSI_EXCLAMATION => {
                        if b == b'p' as i32 {
                            self.reset();
                        } else {
                            self.finish_sequence();
                        }
                    }
                    ESC_CSI_QUESTIONMARK => self.csi_question(b),
                    ESC_CSI_BIGGERTHAN => self.csi_bigger(b),
                    ESC_CSI_DOLLAR => self.csi_rectangle(b),
                    ESC_CSI_DOUBLE_QUOTE => {
                        if b == b'q' as i32 {
                            match self.arg0(0) {
                                0 | 2 => self.effect &= !PROTECTED,
                                1 => self.effect |= PROTECTED,
                                _ => self.finish_sequence(),
                            }
                        } else {
                            self.finish_sequence();
                        }
                    }
                    ESC_CSI_SINGLE_QUOTE => {
                        let after = self.right - self.cursor_col;
                        let count = self.arg0(1).min(after);
                        if b == b'}' as i32 {
                            self.block_copy(
                                self.cursor_col,
                                0,
                                after - count,
                                self.rows,
                                self.cursor_col + count,
                                0,
                            );
                            self.block_clear(self.cursor_col, 0, count, self.rows);
                        } else if b == b'~' as i32 {
                            self.block_copy(
                                self.cursor_col + count,
                                0,
                                after - count,
                                self.rows,
                                self.cursor_col,
                                0,
                            );
                        } else {
                            self.finish_sequence();
                        }
                    }
                    ESC_PERCENT => {}
                    ESC_OSC => self.do_osc(b),
                    ESC_OSC_ESC => self.osc_escape(b),
                    ESC_P => self.device_control(b),
                    ESC_CSI_QUESTIONMARK_ARG_DOLLAR => {
                        if b == b'p' as i32 {
                            let mode = self.arg0(0);
                            let value = if matches!(mode, 47 | 1047 | 1049) {
                                if self.alternate { 1 } else { 2 }
                            } else {
                                let bit = Self::dec_bit(mode);
                                if bit != -1 {
                                    if self.flag(bit) { 1 } else { 2 }
                                } else {
                                    self.event(
                                        5,
                                        format!(
                                            "Got DECRQM for unrecognized private DEC mode={mode}"
                                        )
                                        .encode_utf16()
                                        .collect(),
                                    );
                                    0
                                }
                            };
                            self.write(format!("\x1b[?{mode};{value}$y"));
                        } else {
                            self.finish_sequence();
                        }
                    }
                    ESC_CSI_ARGS_SPACE => {
                        if b == b'q' as i32 {
                            match self.arg0(0) {
                                0..=2 => self.cursor_style = 0,
                                3 | 4 => self.cursor_style = 1,
                                5 | 6 => self.cursor_style = 2,
                                _ => {}
                            }
                        } else if b != b't' as i32 && b != b'u' as i32 {
                            self.finish_sequence();
                        }
                    }
                    ESC_CSI_ARGS_ASTERIX => {
                        let extent = self.arg0(0);
                        if b == b'x' as i32 && (0..=2).contains(&extent) {
                            self.set_flag(RECTANGULAR_ATTRIBUTE, extent == 2);
                        } else {
                            self.finish_sequence();
                        }
                    }
                    _ => self.finish_sequence(),
                }
                if !self.continuing {
                    self.escape = ESC_NONE;
                }
            }
        }
    }

    fn csi_rectangle(&mut self, b: i32) {
        let origin = self.flag(ORIGIN_MODE);
        let (top_margin, bottom_margin, left_margin, right_margin) = if origin {
            (self.top, self.bottom, self.left, self.right)
        } else {
            (0, self.rows, 0, self.columns)
        };
        match char::from_u32(b as u32) {
            Some('v') => {
                let top = (self.arg(0, 1, true) - 1 + top_margin).min(self.rows);
                let left = (self.arg(1, 1, true) - 1 + left_margin).min(self.columns);
                let bottom = (self.arg(2, self.rows, true) + top_margin)
                    .max(top)
                    .min(self.rows);
                let right = (self.arg(3, self.columns, true) + left_margin)
                    .max(left)
                    .min(self.columns);
                let dy = (self.arg(5, 1, true) - 1 + top_margin).min(self.rows);
                let dx = (self.arg(6, 1, true) - 1 + left_margin).min(self.columns);
                self.block_copy(
                    left,
                    top,
                    (self.columns - dx).min(right - left),
                    (self.rows - dy).min(bottom - top),
                    dx,
                    dy,
                );
            }
            Some('{') | Some('x') | Some('z') => {
                let erase = b != b'x' as i32;
                let selective = b == b'{' as i32;
                let mut index = 0;
                let cp = if erase {
                    32
                } else {
                    index += 1;
                    self.arg(0, -1, true)
                };
                if (32..=126).contains(&cp) || (160..=255).contains(&cp) {
                    let top = (self.arg(index, 1, true) + top_margin).min(bottom_margin + 1);
                    index += 1;
                    let left = (self.arg(index, 1, true) + left_margin).min(right_margin + 1);
                    index += 1;
                    let bottom = (self.arg(index, self.rows, true) + top_margin).min(bottom_margin);
                    index += 1;
                    let right =
                        (self.arg(index, self.columns, true) + left_margin).min(right_margin);
                    let style = self.style();
                    for row in top - 1..bottom {
                        for column in left - 1..right {
                            let previous = self.screen_mut().style_at(row, column);
                            if !selective || previous as i32 & PROTECTED == 0 {
                                self.screen_mut().set_char(
                                    column,
                                    row,
                                    cp,
                                    if erase && selective { previous } else { style },
                                );
                            }
                        }
                    }
                }
            }
            Some('r') | Some('t') => {
                let reverse = b == b't' as i32;
                let top = (self.arg(0, 1, true) - 1).min(bottom_margin) + top_margin;
                let left = (self.arg(1, 1, true) - 1).min(right_margin) + left_margin;
                let bottom = (self.arg(2, self.rows, true) + 1).min(bottom_margin - 1) + top_margin;
                let right =
                    (self.arg(3, self.columns, true) + 1).min(right_margin - 1) + left_margin;
                if self.arg_index >= 4 {
                    self.arg_index = self.arg_index.min(self.args.len() - 1);
                    for index in 4..=self.arg_index {
                        let (bits, set) = match self.arg(index, 0, false) {
                            0 => (BOLD | UNDERLINE | BLINK | INVERSE, reverse),
                            1 => (BOLD, true),
                            4 => (UNDERLINE, true),
                            5 => (BLINK, true),
                            7 => (INVERSE, true),
                            22 => (BOLD, false),
                            24 => (UNDERLINE, false),
                            25 => (BLINK, false),
                            27 => (INVERSE, false),
                            _ => (0, true),
                        };
                        if !reverse || set {
                            let rectangular = self.flag(RECTANGULAR_ATTRIBUTE);
                            self.screen_mut().set_effect(
                                bits,
                                set,
                                reverse,
                                rectangular,
                                left_margin,
                                right_margin,
                                top,
                                left,
                                bottom,
                                right,
                            );
                        }
                    }
                }
            }
            _ => self.finish_sequence(),
        }
    }

    fn continue_sequence(&mut self, state: i32) {
        self.escape = state;
        self.continuing = true;
    }
    fn finish_sequence(&mut self) {
        self.escape = ESC_NONE;
    }
    fn start_escape(&mut self) {
        self.escape = ESC;
        self.arg_index = 0;
        self.args.fill(-1);
        self.subparams = 0;
    }
    fn arg(&self, index: usize, default: i32, zero_default: bool) -> i32 {
        let value = self.args[index];
        if value < 0 || (value == 0 && zero_default) {
            default
        } else {
            value
        }
    }
    fn arg0(&self, default: i32) -> i32 {
        self.arg(0, default, true)
    }
    fn arg1(&self, default: i32) -> i32 {
        self.arg(1, default, true)
    }

    fn parse_arg(&mut self, b: i32) {
        if (48..=57).contains(&b) {
            let old = self.args[self.arg_index];
            self.args[self.arg_index] = (old.max(0) * 10 + b - 48).min(9999);
            self.continue_sequence(self.escape);
        } else if b == 59 || b == 58 {
            if self.arg_index + 1 < self.args.len() {
                self.arg_index += 1;
                if b == 58 {
                    self.subparams |= 1_i32.wrapping_shl(self.arg_index as u32);
                }
            }
            self.continue_sequence(self.escape);
        } else {
            self.finish_sequence();
        }
    }

    fn next_tab(&self, mut count: i32) -> i32 {
        for column in self.cursor_col + 1..self.columns {
            if self.tabs[column as usize] {
                count -= 1;
                if count == 0 {
                    return column.min(self.right);
                }
            }
        }
        self.right - 1
    }

    fn linefeed(&mut self) {
        let below = self.cursor_row >= self.bottom;
        let mut row = self.cursor_row + 1;
        if below {
            if self.cursor_row != self.rows - 1 {
                self.cursor_row(row);
            }
        } else {
            if row == self.bottom {
                self.scroll();
                row = self.bottom - 1;
            }
            self.cursor_row(row);
        }
    }

    fn scroll(&mut self) {
        self.scroll_counter += 1;
        let style = self.style();
        if self.left != 0 || self.right != self.columns {
            self.block_copy(
                self.left,
                self.top + 1,
                self.right - self.left,
                self.bottom - self.top - 1,
                self.left,
                self.top,
            );
            self.block_clear(self.left, self.bottom - 1, self.right - self.left, 1);
        } else {
            let (top, bottom) = (self.top, self.bottom);
            self.screen_mut().scroll(top, bottom, style);
        }
    }

    fn cursor_position(&mut self, x: i32, y: i32) {
        let (top, bottom, left, right) = if self.flag(ORIGIN_MODE) {
            (self.top, self.bottom, self.left, self.right)
        } else {
            (0, self.rows, 0, self.columns)
        };
        self.cursor(
            (top + y).clamp(top, bottom - 1),
            (left + x).clamp(left, right - 1),
        );
    }

    fn save_cursor(&mut self) {
        let saved = SavedState {
            row: self.cursor_row,
            column: self.cursor_col,
            effect: self.effect,
            foreground: self.foreground,
            background: self.background,
            flags: self.flags,
            g0: self.g0,
            g1: self.g1,
            using_g0: self.using_g0,
        };
        if self.alternate {
            self.saved_alt = saved;
        } else {
            self.saved_main = saved;
        }
    }

    fn restore_cursor(&mut self) {
        let saved = if self.alternate {
            self.saved_alt
        } else {
            self.saved_main
        };
        self.cursor(saved.row, saved.column);
        self.effect = saved.effect;
        self.foreground = saved.foreground;
        self.background = saved.background;
        let mask = AUTOWRAP | ORIGIN_MODE;
        self.flags = (self.flags & !mask) | (saved.flags & mask);
        self.g0 = saved.g0;
        self.g1 = saved.g1;
        self.using_g0 = saved.using_g0;
    }

    fn esc_pound(&mut self, b: i32) {
        if b == b'8' as i32 {
            let (columns, rows, style) = (self.columns, self.rows, self.style());
            self.screen_mut().block_set(0, 0, columns, rows, 69, style);
        } else {
            self.finish_sequence();
        }
    }

    fn do_esc(&mut self, b: i32) {
        match char::from_u32(b as u32) {
            Some('#') => self.continue_sequence(ESC_POUND),
            Some('(') => self.continue_sequence(ESC_SELECT_LEFT_PAREN),
            Some(')') => self.continue_sequence(ESC_SELECT_RIGHT_PAREN),
            Some('6') => {
                if self.cursor_col > self.left {
                    self.cursor_col -= 1;
                } else {
                    self.block_copy(
                        self.left,
                        self.top,
                        self.right - self.left - 1,
                        self.bottom - self.top,
                        self.left + 1,
                        self.top,
                    );
                    let (left, top, rows, style) = (
                        self.left,
                        self.top,
                        self.bottom - self.top,
                        encode_style(self.foreground, self.background, 0),
                    );
                    self.screen_mut().block_set(left, top, 1, rows, 32, style);
                }
            }
            Some('7') => self.save_cursor(),
            Some('8') => self.restore_cursor(),
            Some('9') => {
                if self.cursor_col < self.right - 1 {
                    self.cursor_col += 1;
                } else {
                    self.block_copy(
                        self.left + 1,
                        self.top,
                        self.right - self.left - 1,
                        self.bottom - self.top,
                        self.left,
                        self.top,
                    );
                    let (right, top, rows, style) = (
                        self.right - 1,
                        self.top,
                        self.bottom - self.top,
                        encode_style(self.foreground, self.background, 0),
                    );
                    self.screen_mut().block_set(right, top, 1, rows, 32, style);
                }
            }
            Some('c') => {
                self.reset();
                self.main.clear_transcript();
                self.block_clear(0, 0, self.columns, self.rows);
                self.cursor_position(0, 0);
            }
            Some('D') => self.linefeed(),
            Some('E') => {
                self.cursor_col(if self.flag(ORIGIN_MODE) { self.left } else { 0 });
                self.linefeed();
            }
            Some('F') => self.cursor(0, self.bottom - 1),
            Some('H') => self.tabs[self.cursor_col as usize] = true,
            Some('M') => {
                if self.cursor_row <= self.top {
                    self.block_copy(
                        self.left,
                        self.top,
                        self.right - self.left,
                        self.bottom - self.top - 1,
                        self.left,
                        self.top + 1,
                    );
                    self.block_clear(self.left, self.top, self.right - self.left, 1);
                } else {
                    self.cursor_row -= 1;
                }
            }
            Some('N') | Some('0') => {}
            Some('P') => {
                self.osc.clear();
                self.continue_sequence(ESC_P);
            }
            Some('[') => self.continue_sequence(ESC_CSI),
            Some('=') => self.set_flag(APPLICATION_KEYPAD, true),
            Some(']') => {
                self.osc.clear();
                self.continue_sequence(ESC_OSC);
            }
            Some('>') => self.set_flag(APPLICATION_KEYPAD, false),
            Some('_') => self.continue_sequence(ESC_APC),
            _ => self.finish_sequence(),
        }
    }

    fn unsupported_csi(&mut self, b: i32) {
        if self.escape == ESC_CSI_UNSUPPORTED_PARAMETER_BYTE && (0x30..=0x3f).contains(&b) {
            self.continue_sequence(ESC_CSI_UNSUPPORTED_PARAMETER_BYTE);
        } else if (0x20..=0x2f).contains(&b) {
            self.continue_sequence(ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE);
        } else {
            self.finish_sequence();
        }
    }

    fn csi_question(&mut self, b: i32) {
        match char::from_u32(b as u32) {
            Some('J') | Some('K') => {
                self.auto_wrap_pending = false;
                let just_row = b == b'K' as i32;
                let (x1, y1, x2, y2) = match self.arg0(0) {
                    0 => (
                        self.cursor_col,
                        self.cursor_row,
                        self.columns,
                        if just_row {
                            self.cursor_row + 1
                        } else {
                            self.rows
                        },
                    ),
                    1 => (
                        0,
                        if just_row { self.cursor_row } else { 0 },
                        self.cursor_col + 1,
                        self.cursor_row + 1,
                    ),
                    2 => (
                        0,
                        if just_row { self.cursor_row } else { 0 },
                        self.columns,
                        if just_row {
                            self.cursor_row + 1
                        } else {
                            self.rows
                        },
                    ),
                    _ => {
                        self.finish_sequence();
                        return;
                    }
                };
                let style = self.style();
                for row in y1..y2 {
                    for column in x1..x2 {
                        if self.screen_mut().style_at(row, column) as i32 & PROTECTED == 0 {
                            self.screen_mut().set_char(column, row, 32, style);
                        }
                    }
                }
            }
            Some('h') | Some('l') => {
                for index in 0..=self.arg_index {
                    self.dec_set(b == b'h' as i32, self.args[index]);
                }
            }
            Some('n') => {
                if self.arg0(-1) == 6 {
                    self.write(format!(
                        "\x1b[?{};{};1R",
                        self.cursor_row + 1,
                        self.cursor_col + 1
                    ));
                } else {
                    self.finish_sequence();
                }
            }
            Some('r') | Some('s') => {
                for index in 0..=self.arg_index {
                    let external = self.args[index];
                    let bit = Self::dec_bit(external);
                    if bit == -1 {
                        self.event(
                            7,
                            format!("Ignoring request to save/recall decset bit={external}")
                                .encode_utf16()
                                .collect(),
                        );
                    } else if b == b's' as i32 {
                        self.saved_flags |= bit;
                    } else {
                        self.dec_set(self.saved_flags & bit != 0, external);
                    }
                }
            }
            Some('$') => self.continue_sequence(ESC_CSI_QUESTIONMARK_ARG_DOLLAR),
            _ => self.parse_arg(b),
        }
    }

    pub fn dec_set(&mut self, set: bool, external: i32) {
        let bit = Self::dec_bit(external);
        if bit != -1 {
            self.set_flag(bit, set);
        }
        match external {
            3 => {
                self.left = 0;
                self.top = 0;
                self.bottom = self.rows;
                self.right = self.columns;
                self.set_flag(LEFT_RIGHT_MARGIN, false);
                self.block_clear(0, 0, self.columns, self.rows);
                self.cursor(0, 0);
            }
            6 => {
                if set {
                    self.cursor_position(0, 0);
                }
            }
            7 | 8 | 9 | 12 | 25 => self.event(6, if set { vec![49] } else { vec![48] }),
            69 => {
                if !set {
                    self.left = 0;
                    self.right = self.columns;
                }
            }
            1048 => {
                if set {
                    self.save_cursor();
                } else {
                    self.restore_cursor();
                }
            }
            47 | 1047 | 1049 => {
                if set != self.alternate {
                    let new_screen = if set { &self.alt } else { &self.main };
                    let resized =
                        new_screen.columns != self.columns || new_screen.screen_rows != self.rows;
                    if set {
                        self.save_cursor();
                    }
                    self.alternate = set;
                    if !set {
                        let saved = self.saved_main;
                        self.restore_cursor();
                        if resized {
                            self.cursor_col = saved.column;
                            self.cursor_row = saved.row;
                        }
                    }
                    if resized {
                        self.resize_screen();
                    }
                    if set {
                        self.block_clear(0, 0, self.columns, self.rows);
                    }
                }
            }
            1 | 4 | 5 | 40 | 45 | 66 | 1000..=1006 | 1015 | 1034 | 2004 => {}
            _ => self.finish_sequence(),
        }
    }

    fn csi_bigger(&mut self, b: i32) {
        if b == b'c' as i32 {
            self.write("\x1b[>41;320;0c");
        } else if b == b'm' as i32 {
            self.event(
                5,
                format!(
                    "(ignored) CSI > MODIFY RESOURCE: {} to {}",
                    self.arg0(-1),
                    self.arg1(-1)
                )
                .encode_utf16()
                .collect(),
            );
        } else {
            self.parse_arg(b);
        }
    }

    fn do_csi(&mut self, b: i32) {
        match char::from_u32(b as u32) {
            Some('!') => self.continue_sequence(ESC_CSI_EXCLAMATION),
            Some('"') => self.continue_sequence(ESC_CSI_DOUBLE_QUOTE),
            Some('\'') => self.continue_sequence(ESC_CSI_SINGLE_QUOTE),
            Some('$') => self.continue_sequence(ESC_CSI_DOLLAR),
            Some('*') => self.continue_sequence(ESC_CSI_ARGS_ASTERIX),
            Some('@') => {
                self.auto_wrap_pending = false;
                let after = self.columns - self.cursor_col;
                let count = self.arg0(1).min(after);
                self.block_copy(
                    self.cursor_col,
                    self.cursor_row,
                    after - count,
                    1,
                    self.cursor_col + count,
                    self.cursor_row,
                );
                self.block_clear(self.cursor_col, self.cursor_row, count, 1);
            }
            Some('A') => self.cursor_row((self.cursor_row - self.arg0(1)).max(0)),
            Some('B') => self.cursor_row((self.cursor_row + self.arg0(1)).min(self.rows - 1)),
            Some('C') | Some('a') => {
                self.cursor_col((self.cursor_col + self.arg0(1)).min(self.right - 1))
            }
            Some('D') => self.cursor_col((self.cursor_col - self.arg0(1)).max(self.left)),
            Some('E') => self.cursor_position(0, self.cursor_row + self.arg0(1)),
            Some('F') => self.cursor_position(0, self.cursor_row - self.arg0(1)),
            Some('G') => self.cursor_col(self.arg0(1).clamp(1, self.columns) - 1),
            Some('H') | Some('f') => self.cursor_position(self.arg1(1) - 1, self.arg0(1) - 1),
            Some('I') => self.cursor_col(self.next_tab(self.arg0(1))),
            Some('J') => {
                match self.arg0(0) {
                    0 => {
                        self.block_clear(
                            self.cursor_col,
                            self.cursor_row,
                            self.columns - self.cursor_col,
                            1,
                        );
                        self.block_clear(
                            0,
                            self.cursor_row + 1,
                            self.columns,
                            self.rows - self.cursor_row - 1,
                        );
                    }
                    1 => {
                        self.block_clear(0, 0, self.columns, self.cursor_row);
                        self.block_clear(0, self.cursor_row, self.cursor_col + 1, 1);
                    }
                    2 => self.block_clear(0, 0, self.columns, self.rows),
                    3 => self.main.clear_transcript(),
                    _ => {
                        self.finish_sequence();
                        return;
                    }
                }
                self.auto_wrap_pending = false;
            }
            Some('K') => {
                match self.arg0(0) {
                    0 => self.block_clear(
                        self.cursor_col,
                        self.cursor_row,
                        self.columns - self.cursor_col,
                        1,
                    ),
                    1 => self.block_clear(0, self.cursor_row, self.cursor_col + 1, 1),
                    2 => self.block_clear(0, self.cursor_row, self.columns, 1),
                    _ => {
                        self.finish_sequence();
                        return;
                    }
                }
                self.auto_wrap_pending = false;
            }
            Some('L') => {
                let after = self.bottom - self.cursor_row;
                let count = self.arg0(1).min(after);
                self.block_copy(
                    0,
                    self.cursor_row,
                    self.columns,
                    after - count,
                    0,
                    self.cursor_row + count,
                );
                self.block_clear(0, self.cursor_row, self.columns, count);
            }
            Some('M') => {
                self.auto_wrap_pending = false;
                let after = self.bottom - self.cursor_row;
                let count = self.arg0(1).min(after);
                self.block_copy(
                    0,
                    self.cursor_row + count,
                    self.columns,
                    after - count,
                    0,
                    self.cursor_row,
                );
                self.block_clear(0, self.cursor_row + after - count, self.columns, count);
            }
            Some('P') => {
                self.auto_wrap_pending = false;
                let after = self.columns - self.cursor_col;
                let count = self.arg0(1).min(after);
                self.block_copy(
                    self.cursor_col + count,
                    self.cursor_row,
                    after - count,
                    1,
                    self.cursor_col,
                    self.cursor_row,
                );
                self.block_clear(self.cursor_col + after - count, self.cursor_row, count, 1);
            }
            Some('S') => {
                for _ in 0..self.arg0(1) {
                    self.scroll();
                }
            }
            Some('T') => {
                if self.arg_index == 0 {
                    let count = self.arg0(1).min(self.bottom - self.top);
                    self.block_copy(
                        self.left,
                        self.top,
                        self.right - self.left,
                        self.bottom - self.top - count,
                        self.left,
                        self.top + count,
                    );
                    self.block_clear(self.left, self.top, self.right - self.left, count);
                } else {
                    self.finish_sequence();
                }
            }
            Some('X') => {
                self.auto_wrap_pending = false;
                self.block_clear(
                    self.cursor_col,
                    self.cursor_row,
                    self.arg0(1).min(self.columns - self.cursor_col),
                    1,
                );
            }
            Some('Z') => {
                let mut count = self.arg0(1);
                let mut column = self.left;
                for index in (0..self.cursor_col).rev() {
                    if self.tabs[index as usize] {
                        count -= 1;
                        if count == 0 {
                            column = index.max(self.left);
                            break;
                        }
                    }
                }
                self.cursor_col = column;
            }
            Some('?') => self.continue_sequence(ESC_CSI_QUESTIONMARK),
            Some('>') => self.continue_sequence(ESC_CSI_BIGGERTHAN),
            Some('<') | Some('=') => self.continue_sequence(ESC_CSI_UNSUPPORTED_PARAMETER_BYTE),
            Some('`') => self.cursor_position(self.arg0(1) - 1, self.cursor_row),
            Some('b') => {
                if self.last_emitted != -1 {
                    for _ in 0..self.arg0(1) {
                        self.emit(self.last_emitted);
                    }
                }
            }
            Some('c') => {
                if self.arg0(0) == 0 {
                    self.write("\x1b[?64;1;2;6;9;15;18;21;22c");
                }
            }
            Some('d') => self.cursor_row(self.arg0(1).clamp(1, self.rows) - 1),
            Some('e') => self.cursor_position(self.cursor_col, self.cursor_row + self.arg0(1)),
            Some('g') => match self.arg0(0) {
                0 => self.tabs[self.cursor_col as usize] = false,
                3 => self.tabs.fill(false),
                _ => {}
            },
            Some('h') | Some('l') => {
                if self.arg0(0) == 4 {
                    self.insert = b == b'h' as i32;
                } else if self.arg0(0) != 34 {
                    self.finish_sequence();
                }
            }
            Some('m') => self.rendition(),
            Some('n') => match self.arg0(0) {
                5 => self.write("\x1b[0n"),
                6 => self.write(format!(
                    "\x1b[{};{}R",
                    self.cursor_row + 1,
                    self.cursor_col + 1
                )),
                _ => {}
            },
            Some('r') => {
                self.top = (self.arg0(1) - 1).clamp(0, self.rows - 2);
                self.bottom = self.arg1(self.rows).min(self.rows).max(self.top + 2);
                self.cursor_position(0, 0);
            }
            Some('s') => {
                if self.flag(LEFT_RIGHT_MARGIN) {
                    self.left = (self.arg0(1) - 1).min(self.columns - 2);
                    self.right = self.arg1(self.columns).min(self.columns).max(self.left + 1);
                    self.cursor_position(0, 0);
                } else {
                    self.save_cursor();
                }
            }
            Some('t') => self.window_control(),
            Some('u') => self.restore_cursor(),
            Some(' ') => self.continue_sequence(ESC_CSI_ARGS_SPACE),
            _ => self.parse_arg(b),
        }
    }

    fn window_control(&mut self) {
        match self.arg0(0) {
            11 => self.write("\x1b[1t"),
            13 => self.write("\x1b[3;0;0t"),
            14 => self.write(format!(
                "\x1b[4;{};{}t",
                self.rows * self.cell_height,
                self.columns * self.cell_width
            )),
            16 => self.write(format!("\x1b[6;{};{}t", self.cell_height, self.cell_width)),
            18 => self.write(format!("\x1b[8;{};{}t", self.rows, self.columns)),
            19 => self.write(format!("\x1b[9;{};{}t", self.rows, self.columns)),
            20 => self.write("\x1b]LIconLabel\x1b\\"),
            21 => self.write("\x1b]l\x1b\\"),
            22 => {
                self.title_stack.push(self.title.clone());
                if self.title_stack.len() > 20 {
                    self.title_stack.remove(0);
                }
            }
            23 => {
                if let Some(title) = self.title_stack.pop() {
                    self.set_title(title);
                }
            }
            _ => {}
        }
    }

    fn rendition(&mut self) {
        let mut index = 0;
        while index <= self.arg_index {
            if self.subparams & 1_i32.wrapping_shl(index as u32) != 0 {
                index += 1;
                continue;
            }
            let code = self.arg(index, 0, false);
            match code {
                0 => {
                    self.foreground = FOREGROUND as i32;
                    self.background = BACKGROUND as i32;
                    self.effect = 0;
                }
                1 => self.effect |= BOLD,
                2 => self.effect |= DIM,
                3 => self.effect |= ITALIC,
                4 => {
                    if index < self.arg_index
                        && self.subparams & 1_i32.wrapping_shl((index + 1) as u32) != 0
                    {
                        index += 1;
                        if self.args[index] == 0 {
                            self.effect &= !UNDERLINE;
                        } else {
                            self.effect |= UNDERLINE;
                        }
                    } else {
                        self.effect |= UNDERLINE;
                    }
                }
                5 => self.effect |= BLINK,
                7 => self.effect |= INVERSE,
                8 => self.effect |= INVISIBLE,
                9 => self.effect |= STRIKETHROUGH,
                22 => self.effect &= !(BOLD | DIM),
                23 => self.effect &= !ITALIC,
                24 => self.effect &= !UNDERLINE,
                25 => self.effect &= !BLINK,
                27 => self.effect &= !INVERSE,
                30..=37 => self.foreground = code - 30,
                38 | 48 | 58 => {
                    if index + 2 > self.arg_index {
                        index += 1;
                        continue;
                    }
                    let first = self.args[index + 1];
                    let mut color = None;
                    if first == 2 {
                        if index + 4 > self.arg_index {
                            self.event(
                                7,
                                format!("Too few CSI{code};2 RGB arguments")
                                    .encode_utf16()
                                    .collect(),
                            );
                        } else {
                            let red = self.arg(index + 2, 0, false);
                            let green = self.arg(index + 3, 0, false);
                            let blue = self.arg(index + 4, 0, false);
                            if (0..=255).contains(&red)
                                && (0..=255).contains(&green)
                                && (0..=255).contains(&blue)
                            {
                                color = Some(
                                    (0xff00_0000_u32
                                        | ((red as u32) << 16)
                                        | ((green as u32) << 8)
                                        | blue as u32) as i32,
                                );
                            } else {
                                self.finish_sequence();
                            }
                            index += 4;
                        }
                    } else if first == 5 {
                        let value = self.arg(index + 2, 0, false);
                        if (0..259).contains(&value) {
                            color = Some(value);
                        }
                        index += 2;
                    } else {
                        self.finish_sequence();
                    }
                    if let Some(color) = color {
                        if code == 38 {
                            self.foreground = color;
                        } else if code == 48 {
                            self.background = color;
                        } else {
                            self.underline_color = color;
                        }
                    }
                }
                39 => self.foreground = FOREGROUND as i32,
                40..=47 => self.background = code - 40,
                49 => self.background = BACKGROUND as i32,
                59 => self.underline_color = FOREGROUND as i32,
                90..=97 => self.foreground = code - 90 + 8,
                100..=107 => self.background = code - 100 + 8,
                _ => {}
            }
            index += 1;
        }
    }

    fn emit(&mut self, mut cp: i32) {
        self.last_emitted = cp;
        if if self.using_g0 { self.g0 } else { self.g1 } {
            cp = match cp {
                95 => 32,
                96 => 0x25c6,
                48 => 0x2588,
                97 => 0x2592,
                98 => 0x2409,
                99 => 0x240c,
                100 => 13,
                101 => 0x240a,
                102 => 0xb0,
                103 => 0xb1,
                104 => 10,
                105 => 0x240b,
                106 => 0x2518,
                107 => 0x2510,
                108 => 0x250c,
                109 => 0x2514,
                110 => 0x253c,
                111 => 0x23ba,
                112 => 0x23bb,
                113 => 0x2500,
                114 => 0x23bc,
                115 => 0x23bd,
                116 => 0x251c,
                117 => 0x2524,
                118 => 0x2534,
                119 => 0x252c,
                120 => 0x2502,
                121 => 0x2264,
                122 => 0x2265,
                123 => 0x3c0,
                124 => 0x2260,
                125 => 0xa3,
                126 => 0xb7,
                _ => cp,
            };
        }
        let auto_wrap = self.flag(AUTOWRAP);
        let display_width = width(cp);
        let last_column = self.cursor_col == self.right - 1;
        if auto_wrap {
            if last_column && ((self.auto_wrap_pending && display_width == 1) || display_width == 2)
            {
                let row = self.cursor_row;
                self.screen_mut().set_wrap(row, true);
                self.cursor_col = self.left;
                if self.cursor_row + 1 < self.bottom {
                    self.cursor_row += 1;
                } else {
                    self.scroll();
                }
            }
        } else if last_column && display_width == 2 {
            return;
        }
        if self.insert && display_width > 0 {
            let destination = self.cursor_col + display_width;
            if destination < self.right {
                self.block_copy(
                    self.cursor_col,
                    self.cursor_row,
                    self.right - destination,
                    1,
                    destination,
                    self.cursor_row,
                );
            }
        }
        let offset =
            i32::from(display_width <= 0 && self.cursor_col > 0 && !self.auto_wrap_pending);
        let (column, row, style) = (
            (self.cursor_col - offset).max(0),
            self.cursor_row,
            self.style(),
        );
        self.screen_mut().set_char(column, row, cp, style);
        if auto_wrap && display_width > 0 {
            self.auto_wrap_pending = self.cursor_col == self.right - display_width;
        }
        self.cursor_col = (self.cursor_col + display_width).min(self.right - 1);
    }

    fn collect_osc(&mut self, cp: i32) {
        if self.osc.len() < 8192 {
            if cp < 0x10000 {
                self.osc.push(cp as u16);
            } else {
                self.osc.push((0xd800 + ((cp - 0x10000) >> 10)) as u16);
                self.osc.push((0xdc00 + ((cp - 0x10000) & 1023)) as u16);
            }
            self.continue_sequence(self.escape);
        } else {
            self.finish_sequence();
        }
    }

    fn do_osc(&mut self, b: i32) {
        if b == 7 {
            self.osc_parameters("\x07");
        } else if b == 27 {
            self.continue_sequence(ESC_OSC_ESC);
        } else {
            self.collect_osc(b);
        }
    }

    fn osc_escape(&mut self, b: i32) {
        if b == b'\\' as i32 {
            self.osc_parameters("\x1b\\");
        } else {
            self.collect_osc(27);
            self.collect_osc(b);
            self.continue_sequence(ESC_OSC);
        }
    }

    fn osc_parameters(&mut self, terminator: &str) {
        let mut value: i32 = -1;
        let mut parameter = Vec::new();
        for (index, &unit) in self.osc.iter().enumerate() {
            if unit == 59 {
                parameter.extend_from_slice(&self.osc[index + 1..]);
                break;
            }
            if (48..=57).contains(&unit) {
                value = value.max(0).wrapping_mul(10).wrapping_add(unit as i32 - 48);
            } else {
                self.finish_sequence();
                return;
            }
        }
        match value {
            0..=2 => self.set_title(Some(parameter)),
            4 => {
                let mut color_index: i32 = -1;
                let mut start = None;
                for index in 0..=parameter.len() {
                    let unit = parameter.get(index).copied().unwrap_or(59);
                    if unit == 59 {
                        if let Some(first) = start {
                            if !(0..=255).contains(&color_index) {
                                self.finish_sequence();
                                return;
                            }
                            self.colors
                                .parse_color(color_index, &parameter[first..index]);
                            self.event(3, Vec::new());
                            color_index = -1;
                            start = None;
                        } else {
                            start = Some(index + 1);
                        }
                    } else if start.is_some() {
                    } else if (48..=57).contains(&unit) {
                        color_index = color_index
                            .max(0)
                            .wrapping_mul(10)
                            .wrapping_add(unit as i32 - 48);
                    } else {
                        self.finish_sequence();
                        return;
                    }
                }
            }
            10..=12 => {
                let mut special = FOREGROUND as i32 + value - 10;
                for spec in parameter.split(|&unit| unit == 59) {
                    if special > CURSOR as i32 {
                        break;
                    }
                    if spec == [63] {
                        let rgb = self.colors.current[special as usize] as u32;
                        let red = 65535 * ((rgb >> 16) & 255) / 255;
                        let green = 65535 * ((rgb >> 8) & 255) / 255;
                        let blue = 65535 * (rgb & 255) / 255;
                        self.write(format!(
                            "\x1b]{value};rgb:{red:04x}/{green:04x}/{blue:04x}{terminator}"
                        ));
                    } else {
                        self.colors.parse_color(special, spec);
                        self.event(3, Vec::new());
                    }
                    special += 1;
                }
            }
            52 => {}
            104 => {
                if parameter.is_empty() {
                    self.colors.reset();
                    self.event(3, Vec::new());
                } else {
                    for part in parameter.split(|&unit| unit == 59) {
                        if let Ok(index) = String::from_utf16_lossy(part).parse::<i32>()
                            && (0..259).contains(&index)
                        {
                            self.colors.reset_index(index);
                            self.event(3, Vec::new());
                        }
                    }
                }
            }
            110..=112 => {
                self.colors.reset_index(FOREGROUND as i32 + value - 110);
                self.event(3, Vec::new());
            }
            119 => {}
            _ => self.finish_sequence(),
        }
        self.finish_sequence();
    }

    fn termcap(&self, name: &[u16]) -> Option<Vec<u16>> {
        match String::from_utf16_lossy(name).as_str() {
            "Co" | "colors" => Some("256".encode_utf16().collect()),
            "TN" | "name" => Some("xterm".encode_utf16().collect()),
            _ => {
                let callbacks = self.callbacks.unwrap();
                let mut text = TerminalText {
                    data: std::ptr::null_mut(),
                    len: usize::MAX,
                };
                let status = unsafe {
                    (callbacks.termcap)(
                        callbacks.context,
                        name.as_ptr(),
                        name.len(),
                        self.flag(APPLICATION_CURSOR),
                        self.flag(APPLICATION_KEYPAD),
                        &mut text,
                    )
                };
                if status != 0 || text.len == usize::MAX {
                    None
                } else {
                    Some(
                        unsafe {
                            Box::from_raw(std::ptr::slice_from_raw_parts_mut(text.data, text.len))
                        }
                        .into_vec(),
                    )
                }
            }
        }
    }

    fn device_control(&mut self, b: i32) {
        if b != b'\\' as i32 {
            if self.osc.len() > 8192 {
                self.osc.clear();
                self.finish_sequence();
            } else {
                if b < 0x10000 {
                    self.osc.push(b as u16);
                } else {
                    self.osc.push((0xd800 + ((b - 0x10000) >> 10)) as u16);
                    self.osc.push((0xdc00 + ((b - 0x10000) & 1023)) as u16);
                }
                self.continue_sequence(self.escape);
            }
            return;
        }
        let dcs = String::from_utf16_lossy(&self.osc);
        if dcs.starts_with("$q") {
            if dcs == "$q\"p" {
                self.write("\x1bP1$r64;1\"p\x1b\\");
            }
        } else if let Some(encoded) = dcs.strip_prefix("+q") {
            let mut parts: Vec<&str> = encoded.split(';').collect();
            if !encoded.is_empty() {
                while parts.last() == Some(&"") {
                    parts.pop();
                }
            }
            for part in parts {
                if part.len() % 2 != 0 {
                    self.event(
                        5,
                        format!("Invalid device termcap/terminfo name of odd length: {part}")
                            .encode_utf16()
                            .collect(),
                    );
                    continue;
                }
                let mut name = Vec::new();
                for pair in part.as_bytes().chunks(2) {
                    let number = std::str::from_utf8(pair)
                        .ok()
                        .and_then(|value| u16::from_str_radix(value, 16).ok());
                    if let Some(unit) = number {
                        name.push(unit);
                    } else {
                        self.event(
                            5,
                            format!("Invalid device termcap/terminfo encoded name \"{part}\"")
                                .encode_utf16()
                                .collect(),
                        );
                    }
                }
                if let Some(response) = self.termcap(&name) {
                    let hex: String = response.iter().map(|unit| format!("{unit:02X}")).collect();
                    self.write(format!("\x1bP1+r{part}={hex}\x1b\\"));
                } else {
                    let name = String::from_utf16_lossy(&name);
                    if name != "%1" && name != "&8" {
                        self.event(
                            7,
                            format!("Unhandled termcap/terminfo name: '{name}'")
                                .encode_utf16()
                                .collect(),
                        );
                    }
                    self.write(format!("\x1bP0+r{part}\x1b\\"));
                }
            }
        }
        self.finish_sequence();
    }
}
