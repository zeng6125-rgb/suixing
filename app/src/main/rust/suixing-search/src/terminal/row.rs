// SPDX-License-Identifier: Apache-2.0
// Termux v0.118.3; see THIRD_PARTY_NOTICES.md.

use super::width::{code_point, code_point_len, width, width_at, write_code_point};

pub(super) struct Row {
    pub columns: i32,
    pub text: Vec<u16>,
    pub used: usize,
    pub wrap: bool,
    pub styles: Vec<i64>,
    pub uniform_style: i64,
    pub complex: bool,
}

impl Row {
    pub fn new(columns: i32, style: i64) -> Self {
        Self {
            columns,
            text: vec![32; columns as usize * 3 / 2],
            used: columns as usize,
            wrap: false,
            styles: Vec::new(),
            uniform_style: style,
            complex: false,
        }
    }

    pub fn clear(&mut self, style: i64) {
        self.text.fill(32);
        self.uniform_style = style;
        self.styles = Vec::new();
        self.used = self.columns as usize;
        self.complex = false;
    }

    pub fn style(&self, column: i32) -> i64 {
        if self.styles.is_empty() {
            self.uniform_style
        } else {
            self.styles[column as usize]
        }
    }

    pub fn set_style(&mut self, column: i32, style: i64) {
        if self.styles.is_empty() {
            if style == self.uniform_style {
                return;
            }
            self.styles = vec![self.uniform_style; self.columns as usize];
        }
        self.styles[column as usize] = style;
    }

    pub fn compress_style(&mut self) {
        if let Some(&first) = self.styles.first()
            && self.styles.iter().all(|&style| style == first)
        {
            self.uniform_style = first;
            self.styles = Vec::new();
        }
    }

    pub fn blank(&self) -> bool {
        self.text[..self.used].iter().all(|&unit| unit == 32)
    }

    pub fn column_start(&self, column: i32) -> usize {
        if column == self.columns {
            return self.used;
        }
        let mut current_column = 0;
        let mut index = 0;
        loop {
            let cp = code_point(&self.text, index);
            let mut next = index + code_point_len(cp);
            let display_width = width(cp);
            if display_width > 0 {
                current_column += display_width;
                if current_column == column {
                    while next < self.used {
                        let cp = code_point(&self.text, next);
                        if width(cp) > 0 {
                            break;
                        }
                        next += code_point_len(cp);
                    }
                    return next;
                }
                if current_column > column {
                    return index;
                }
            }
            index = next;
        }
    }

    pub fn wide_at(&self, column: i32) -> bool {
        let mut index = 0;
        let mut current_column = 0;
        while index < self.used {
            let cp = code_point(&self.text, index);
            index += code_point_len(cp);
            let display_width = width(cp);
            if display_width > 0 {
                if current_column == column && display_width == 2 {
                    return true;
                }
                current_column += display_width;
                if current_column > column {
                    return false;
                }
            }
        }
        false
    }

    pub fn set_char(&mut self, mut column: i32, cp: i32, style: i64) {
        self.set_style(column, style);
        let new_width = width(cp);
        if !self.complex {
            if cp >= 0x10000 || new_width != 1 {
                self.complex = true;
            } else {
                self.text[column as usize] = cp as u16;
                return;
            }
        }

        let combining = new_width <= 0;
        let second_half = column > 0 && self.wide_at(column - 1);
        if combining {
            if second_half {
                column -= 1;
            }
        } else {
            if second_half {
                self.set_char(column - 1, 32, style);
            }
            if new_width == 2 && self.wide_at(column + 1) {
                self.set_char(column + 1, 32, style);
            }
        }

        let old_start = self.column_start(column);
        let old_width = width_at(&self.text, old_start);
        let old_end = if column + old_width < self.columns {
            self.column_start(column + old_width)
        } else {
            self.used
        };
        let old_count = old_end - old_start;
        if combining {
            let mut index = old_start;
            let mut count = 0;
            while index < old_end {
                let cp = code_point(&self.text, index);
                count += i32::from(width(cp) <= 0);
                index += code_point_len(cp);
            }
            if count >= 15 {
                return;
            }
        }

        let new_count = code_point_len(cp) + if combining { old_count } else { 0 };
        let new_end = old_start + new_count;
        let new_used = self.used + new_count - old_count;
        if new_used > self.text.len() {
            self.text.resize(self.text.len() + self.columns as usize, 0);
        }
        if new_count != old_count {
            self.text.copy_within(old_end..self.used, new_end);
        }
        self.used = new_used;
        write_code_point(
            cp,
            &mut self.text,
            old_start + if combining { old_count } else { 0 },
        );

        if old_width == 2 && new_width == 1 {
            if self.used + 1 > self.text.len() {
                self.text.resize(self.text.len() + self.columns as usize, 0);
            }
            self.text.copy_within(new_end..self.used, new_end + 1);
            self.text[new_end] = 32;
            self.used += 1;
        } else if old_width == 1 && new_width == 2 {
            if column == self.columns - 1 {
                panic!("Cannot put wide character in last column");
            } else if column == self.columns - 2 {
                self.used = new_end;
            } else {
                let next_end = new_end + code_point_len(code_point(&self.text, new_end));
                self.text.copy_within(next_end..self.used, new_end);
                self.used -= next_end - new_end;
            }
        }
    }

    pub fn copy_interval(
        &mut self,
        source: Option<&Row>,
        mut from: i32,
        end: i32,
        mut destination: i32,
    ) {
        let line = source.unwrap_or(self);
        let mut index = line.column_start(from);
        let end_index = line.column_start(end);
        let mut second_half = from > 0 && line.wide_at(from - 1);
        let complex = line.complex;
        let owned = if source.is_none() {
            self.text.clone()
        } else {
            Vec::new()
        };
        self.complex |= complex;
        let mut latest_width = 0;
        while index < end_index {
            let chars = source.map_or(owned.as_slice(), |line| line.text.as_slice());
            let mut cp = code_point(chars, index);
            index += code_point_len(cp);
            if second_half {
                cp = 32;
                second_half = false;
            }
            let display_width = width(cp);
            if display_width > 0 {
                destination += latest_width;
                from += latest_width;
                latest_width = display_width;
            }
            let style = source.map_or_else(|| self.style(from), |line| line.style(from));
            self.set_char(destination, cp, style);
        }
    }
}
