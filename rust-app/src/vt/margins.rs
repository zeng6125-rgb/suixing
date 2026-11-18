//! VT510 rectangular scrolling. Partial-width scrolls retain physical row IDs
//! and never enter scrollback; complete page scrolling keeps the existing path.
use super::{Cell, Row, VtEngine};

impl VtEngine {
    pub(super) fn horizontal_bounds(&self) -> (usize, usize) {
        if self.modes.left_right_margins {
            (self.left, self.right)
        } else {
            (0, usize::from(self.geometry.columns))
        }
    }
    pub(super) fn print_bounds(&self) -> (usize, usize) {
        let bounds = self.horizontal_bounds();
        if (bounds.0..bounds.1).contains(&self.screen().cursor.column) {
            bounds
        } else {
            (0, usize::from(self.geometry.columns))
        }
    }
    pub(super) fn in_horizontal_region(&self, column: usize) -> bool {
        let (left, right) = self.horizontal_bounds();
        (left..right).contains(&column)
    }
    pub(super) fn set_horizontal_margins(&mut self, left: usize, right: usize) {
        let columns = usize::from(self.geometry.columns);
        if left >= right || right > columns || (columns > 1 && right - left < 2) {
            return;
        }
        self.left = left;
        self.right = right;
        self.move_cursor(
            if self.origin { left } else { 0 },
            if self.origin { self.top } else { 0 },
        );
    }
    pub(super) fn horizontal_move(&mut self, count: usize, back: bool) {
        let cursor = self.screen().cursor;
        let (left, right) = if self.in_horizontal_region(cursor.column) {
            self.horizontal_bounds()
        } else {
            (0, usize::from(self.geometry.columns))
        };
        let column = if back {
            cursor.column.saturating_sub(count).max(left)
        } else {
            cursor.column.saturating_add(count).min(right - 1)
        };
        self.move_cursor(column, cursor.row);
    }
    /// A wide cell crossing a rectangle edge cannot leave an orphan half.
    fn clip_edges(row: &mut Row, left: usize, right: usize, style: super::Style) {
        if left > 0 && row.cells[left].width == 0 {
            row.clear_cell(left, style);
        }
        if right < row.cells.len() && row.cells[right].width == 0 {
            row.clear_cell(right, style);
        }
    }
    fn repair_region(row: &mut Row, left: usize, right: usize, style: super::Style) {
        // A horizontal shift can land only one half of a wide cell at either
        // edge. Do not let full-row repair manufacture a continuation outside
        // the rectangle and overwrite a neighboring pane's cell.
        if row.cells[left].width == 0 {
            row.cells[left] = Cell::blank(style);
        }
        if row.cells[right - 1].width == 2 {
            row.cells[right - 1] = Cell::blank(style);
        }
        row.repair(style);
    }
    pub(super) fn scroll_partial(&mut self, count: usize, down: bool) -> bool {
        let (left, right) = self.horizontal_bounds();
        if left == 0 && right == usize::from(self.geometry.columns) {
            return false;
        }
        let (top, bottom) = (self.top, self.bottom);
        let count = count.min(bottom - top);
        let style = self.style;
        for y in top..bottom {
            Self::clip_edges(self.row_mut(y), left, right, style);
        }
        let source: Vec<_> = self.screen().rows[top..bottom]
            .iter()
            .map(|row| row.cells[left..right].to_vec())
            .collect();
        for y in top..bottom {
            let relative = y - top;
            let source_index = if down {
                relative.checked_sub(count)
            } else {
                relative
                    .checked_add(count)
                    .filter(|index| *index < source.len())
            };
            let row = self.row_mut(y);
            if let Some(index) = source_index {
                row.cells[left..right].clone_from_slice(&source[index]);
            } else {
                row.cells[left..right].fill(Cell::blank(style));
            }
            row.wrapped = false;
            Self::repair_region(row, left, right, style);
        }
        self.last_print = None;
        true
    }
    pub(super) fn shift_rectangle(
        &mut self,
        top: usize,
        bottom: usize,
        left: usize,
        right: usize,
        count: usize,
        insert: bool,
        style: super::Style,
    ) {
        let count = count.min(right - left);
        for y in top..bottom {
            let row = self.row_mut(y);
            Self::clip_edges(row, left, right, style);
            let source = row.cells[left..right].to_vec();
            for x in left..right {
                let relative = x - left;
                let source_index = if insert {
                    relative.checked_sub(count)
                } else {
                    relative
                        .checked_add(count)
                        .filter(|index| *index < source.len())
                };
                row.cells[x] =
                    source_index.map_or_else(|| Cell::blank(style), |index| source[index].clone());
            }
            Self::repair_region(row, left, right, style);
        }
        self.last_print = None;
    }
    pub(super) fn horizontal_index(&mut self, back: bool) {
        let cursor = self.screen().cursor;
        let (left, right) = self.horizontal_bounds();
        // Preserve Termux's real margin-edge scroll contract. Outside the
        // horizontal margins, the cursor still moves toward the page border.
        if (back && cursor.column == left) || (!back && cursor.column == right - 1) {
            let blank = super::Style {
                foreground: self.style.foreground,
                background: self.style.background,
                ..Default::default()
            };
            self.shift_rectangle(self.top, self.bottom, left, right, 1, back, blank);
        } else {
            let columns = usize::from(self.geometry.columns);
            let column = if back {
                cursor.column.saturating_sub(1)
            } else {
                cursor.column.saturating_add(1).min(columns - 1)
            };
            // DECBI/DECFI bypass DECOM's absolute-position clamp, as in the
            // original emulator's direct cursor-column updates.
            self.screen_mut().cursor.column = column;
        }
        self.screen_mut().wrap_pending = false;
        self.last_print = None;
        self.dirty();
    }
}
