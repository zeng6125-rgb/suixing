// SPDX-License-Identifier: Apache-2.0
// Termux v0.118.3; see THIRD_PARTY_NOTICES.md.

use super::colors::{NORMAL, background, encode_style, foreground};
use super::row::Row;
use super::width::{code_point, code_point_len, width};

pub(super) struct Buffer {
    pub lines: Vec<Option<Row>>,
    pub total_rows: i32,
    pub screen_rows: i32,
    pub columns: i32,
    pub transcript_rows: i32,
    pub first_row: i32,
}

impl Buffer {
    pub fn new(columns: i32, total_rows: i32, screen_rows: i32) -> Self {
        let mut result = Self {
            lines: (0..total_rows).map(|_| None).collect(),
            total_rows,
            screen_rows,
            columns,
            transcript_rows: 0,
            first_row: 0,
        };
        result.block_set(0, 0, columns, screen_rows, 32, NORMAL);
        result
    }

    pub fn internal_row(&self, external: i32) -> usize {
        let row = self.first_row + external;
        if row < 0 {
            (self.total_rows + row) as usize
        } else {
            (row % self.total_rows) as usize
        }
    }

    pub fn allocate(&mut self, row: usize) -> &mut Row {
        self.lines[row].get_or_insert_with(|| Row::new(self.columns, 0))
    }

    pub fn line(&mut self, external: i32) -> &mut Row {
        let row = self.internal_row(external);
        self.allocate(row)
    }

    pub fn set_wrap(&mut self, external: i32, wrap: bool) {
        self.line(external).wrap = wrap;
    }

    pub fn wrap(&self, external: i32) -> bool {
        self.lines[self.internal_row(external)]
            .as_ref()
            .unwrap()
            .wrap
    }

    pub fn selected(
        &self,
        x1: i32,
        mut y1: i32,
        x2: i32,
        mut y2: i32,
        join_wrapped: bool,
        join_full: bool,
    ) -> Vec<u16> {
        y1 = y1.max(-self.transcript_rows);
        y2 = y2.min(self.screen_rows - 1);
        let mut result = Vec::new();
        for row in y1..=y2 {
            let from = if row == y1 { x1 } else { 0 };
            let end = if row == y2 {
                (x2 + 1).min(self.columns)
            } else {
                self.columns
            };
            let line = self.lines[self.internal_row(row)].as_ref().unwrap();
            let first = line.column_start(from);
            let mut last = if end < self.columns {
                line.column_start(end)
            } else {
                line.used
            };
            if last == first {
                last = line.column_start(end + 1);
            }
            let printing_end = if line.wrap && end == self.columns {
                Some(last)
            } else {
                line.text[first..last]
                    .iter()
                    .rposition(|&unit| unit != 32)
                    .map(|index| first + index + 1)
            };
            if let Some(end) = printing_end.filter(|&end| end > first) {
                result.extend_from_slice(&line.text[first..end]);
            }
            let fills_width = printing_end == Some(last);
            if (!join_wrapped || !line.wrap)
                && (!join_full || !fills_width)
                && row < y2
                && row < self.screen_rows - 1
            {
                result.push(10);
            }
        }
        result
    }

    pub fn word(&self, x: i32, y: i32) -> Vec<u16> {
        let mut first = y;
        let mut last = y;
        while first > 0
            && !self
                .selected(0, first - 1, self.columns, y, true, true)
                .contains(&10)
        {
            first -= 1;
        }
        while last < self.screen_rows
            && !self
                .selected(0, y, self.columns, last + 1, true, true)
                .contains(&10)
        {
            last += 1;
        }
        let text = self.selected(0, first, self.columns, last, true, true);
        let offset = ((y - first) * self.columns + x) as usize;
        if offset >= text.len() {
            return Vec::new();
        }
        let start = text[..=offset]
            .iter()
            .rposition(|&unit| unit == 32)
            .map_or(0, |index| index + 1);
        let end = text[offset..]
            .iter()
            .position(|&unit| unit == 32)
            .map_or(text.len(), |index| offset + index);
        if start > end {
            Vec::new()
        } else {
            text[start..end].to_vec()
        }
    }

    pub fn valid_cursor_x(&self, x: i32, y: i32) -> i32 {
        let text = self.selected(0, y, x, y, true, false);
        let mut column = 0;
        let mut index = 0;
        while index < text.len() {
            if text[index] == 0 {
                break;
            }
            let cp = if (0xd800..=0xdbff).contains(&text[index]) && index + 1 == text.len() {
                text[index] as i32
            } else {
                code_point(&text, index)
            };
            index += if cp >= 0x10000 { 2 } else { 1 };
            let end = column + width(cp);
            if x > column && x < end {
                return end;
            }
            if end == column {
                return column;
            }
            column = end;
        }
        x
    }

    pub fn set_char(&mut self, column: i32, row: i32, cp: i32, style: i64) {
        self.line(row).set_char(column, cp, style);
    }

    pub fn style_at(&mut self, row: i32, column: i32) -> i64 {
        self.line(row).style(column)
    }

    pub fn block_set(&mut self, x: i32, y: i32, columns: i32, rows: i32, cp: i32, style: i64) {
        for row in y..y + rows {
            let line = self.line(row);
            for column in x..x + columns {
                line.set_char(column, cp, style);
            }
            if x == 0 && columns == line.columns {
                line.compress_style();
            }
        }
    }

    pub fn block_copy(&mut self, x: i32, y: i32, columns: i32, rows: i32, dx: i32, dy: i32) {
        if columns == 0 {
            return;
        }
        for row in 0..rows {
            let offset = if y > dy { row } else { rows - row - 1 };
            let source = self.internal_row(y + offset);
            let destination = self.internal_row(dy + offset);
            self.allocate(source);
            self.allocate(destination);
            if source == destination {
                self.allocate(source)
                    .copy_interval(None, x, x + columns, dx);
            } else if source < destination {
                let (left, right) = self.lines.split_at_mut(destination);
                right[0]
                    .as_mut()
                    .unwrap()
                    .copy_interval(left[source].as_ref(), x, x + columns, dx);
            } else {
                let (left, right) = self.lines.split_at_mut(source);
                left[destination].as_mut().unwrap().copy_interval(
                    right[0].as_ref(),
                    x,
                    x + columns,
                    dx,
                );
            }
        }
    }

    fn move_lines_down(&mut self, start: i32, count: i32) {
        if count == 0 {
            return;
        }
        for offset in (0..count).rev() {
            let current = ((start + offset) % self.total_rows) as usize;
            let next = ((start + offset + 1) % self.total_rows) as usize;
            self.lines.swap(current, next);
        }
    }

    pub fn scroll(&mut self, top: i32, bottom: i32, style: i64) {
        self.move_lines_down(self.first_row, top);
        self.move_lines_down(self.internal_row(bottom) as i32, self.screen_rows - bottom);
        self.first_row = (self.first_row + 1) % self.total_rows;
        if self.transcript_rows < self.total_rows - self.screen_rows {
            self.transcript_rows += 1;
        }
        self.line(bottom - 1).clear(style);
    }

    pub fn set_effect(
        &mut self,
        bits: i32,
        set: bool,
        reverse: bool,
        rectangular: bool,
        left_margin: i32,
        right_margin: i32,
        top: i32,
        left: i32,
        bottom: i32,
        right: i32,
    ) {
        for y in top..bottom {
            let first = if rectangular || y == top {
                left
            } else {
                left_margin
            };
            let last = if rectangular || y + 1 == bottom {
                right
            } else {
                right_margin
            };
            let line = self.line(y);
            for x in first..last {
                let style = line.style(x);
                let effect = style as i32 & 0x7ff;
                let effect = if reverse {
                    (effect & !bits) | (bits & !effect)
                } else if set {
                    effect | bits
                } else {
                    effect & !bits
                };
                line.set_style(
                    x,
                    encode_style(foreground(style), background(style), effect),
                );
            }
        }
    }

    pub fn clear_transcript(&mut self) {
        for row in -self.transcript_rows..0 {
            let index = self.internal_row(row);
            self.lines[index] = None;
        }
        self.transcript_rows = 0;
    }

    pub fn resize(
        &mut self,
        columns: i32,
        rows: i32,
        total_rows: i32,
        cursor: &mut [i32; 2],
        style: i64,
        alternate: bool,
    ) {
        if columns == self.columns && rows <= self.total_rows {
            let mut shift = self.screen_rows - rows;
            if shift > 0 && shift < self.screen_rows {
                for row in (1..self.screen_rows).rev() {
                    if cursor[1] >= row {
                        break;
                    }
                    let index = self.internal_row(row);
                    if self.lines[index].as_ref().is_none_or(Row::blank) {
                        shift -= 1;
                        if shift == 0 {
                            break;
                        }
                    }
                }
            } else if shift < 0 {
                let actual = shift.max(-self.transcript_rows);
                if shift != actual {
                    for row in 0..actual - shift {
                        let index =
                            ((self.first_row + self.screen_rows + row) % self.total_rows) as usize;
                        self.allocate(index).clear(style);
                    }
                    shift = actual;
                }
            }
            self.first_row = (self.first_row + shift).rem_euclid(self.total_rows);
            self.total_rows = total_rows;
            self.transcript_rows = if alternate {
                0
            } else {
                (self.transcript_rows + shift).max(0)
            };
            cursor[1] -= shift;
            self.screen_rows = rows;
        } else {
            let old_lines = std::mem::replace(
                &mut self.lines,
                (0..total_rows)
                    .map(|_| Some(Row::new(columns, style)))
                    .collect(),
            );
            let old_transcript = self.transcript_rows;
            let old_first = self.first_row;
            let old_rows = self.screen_rows;
            let old_total = self.total_rows;
            self.total_rows = total_rows;
            self.screen_rows = rows;
            self.transcript_rows = 0;
            self.first_row = 0;
            self.columns = columns;
            let mut new_cursor = [-1, -1];
            let old_cursor = *cursor;
            let mut placed = false;
            let mut output_row = 0;
            let mut output_column = 0;
            let mut skipped = 0;

            for external in -old_transcript..old_rows {
                let index = (old_first + external).rem_euclid(old_total) as usize;
                let at_cursor = external == old_cursor[1];
                let Some(line) = old_lines[index]
                    .as_ref()
                    .filter(|line| (!placed && at_cursor) || !line.blank())
                else {
                    skipped += 1;
                    continue;
                };
                for _ in 0..skipped {
                    if output_row == self.screen_rows - 1 {
                        self.scroll(0, self.screen_rows, style);
                    } else {
                        output_row += 1;
                    }
                    output_column = 0;
                }
                skipped = 0;
                let last = if at_cursor || line.wrap {
                    line.used
                } else {
                    line.text[..line.used]
                        .iter()
                        .rposition(|&unit| unit != 32)
                        .map_or(0, |index| index + 1)
                };
                let mut old_column = 0;
                let mut current_style = 0;
                let mut i = 0;
                while i < last {
                    let cp = code_point(&line.text, i);
                    i += code_point_len(cp);
                    let display_width = width(cp);
                    if display_width > 0 {
                        current_style = line.style(old_column);
                    }
                    if output_column + display_width > self.columns {
                        self.set_wrap(output_row, true);
                        if output_row == self.screen_rows - 1 {
                            if placed {
                                new_cursor[1] -= 1;
                            }
                            self.scroll(0, self.screen_rows, style);
                        } else {
                            output_row += 1;
                        }
                        output_column = 0;
                    }
                    let combining_offset = i32::from(display_width <= 0 && output_column > 0);
                    self.set_char(
                        output_column - combining_offset,
                        output_row,
                        cp,
                        current_style,
                    );
                    if display_width > 0 {
                        if at_cursor && old_cursor[0] == old_column {
                            new_cursor = [output_column, output_row];
                            placed = true;
                        }
                        old_column += display_width;
                        output_column += display_width;
                        if at_cursor && placed {
                            break;
                        }
                    }
                }
                if external != old_rows - 1 && !line.wrap {
                    if output_row == self.screen_rows - 1 {
                        if placed {
                            new_cursor[1] -= 1;
                        }
                        self.scroll(0, self.screen_rows, style);
                    } else {
                        output_row += 1;
                    }
                    output_column = 0;
                }
            }
            *cursor = new_cursor;
        }
        if cursor[0] < 0 || cursor[1] < 0 {
            *cursor = [0, 0];
        }
    }
}
