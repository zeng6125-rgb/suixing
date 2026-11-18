//! A VT cell viewport. Parsing, resize execution and PTY writes belong to the actor.
use crate::vt::{Color, Frame, Geometry, Input, Key, Modifiers, Palette, Style};
use eframe::egui::{
    self, Color32, FontId, Galley, Pos2, Rect, Sense, Stroke, TextFormat, Vec2, text::LayoutJob,
};
use std::{collections::HashMap, sync::Arc};
mod blink;

#[derive(Default)]
pub struct TerminalViewResponse {
    pub inputs: Vec<Input>,
    pub resize: Option<Geometry>,
    pub paste_confirmation: Option<String>,
    pub keyboard_requested: bool,
}
#[derive(Clone, Copy)]
struct Anchor {
    row: u64,
    column: usize,
}
#[derive(Default)]
pub struct TerminalRenderer {
    view_id: Option<egui::Id>,
    session: u64,
    geometry: u64,
    top: Option<u64>,
    first_index: usize,
    follow: bool,
    alternate: bool,
    main_reading: Option<(Option<u64>, usize, bool)>,
    selection: Option<(Anchor, Anchor)>,
    selecting: bool,
    selecting_start: bool,
    selection_scroll: f32,
    scroll_fraction: f32,
    preedit: String,
    sticky: Modifiers,
    resize_sent: Option<(u16, u16)>,
    resize_version: Option<u64>,
    last_focus: bool,
    mouse_buttons: u8,
    glyphs: HashMap<(String, Style, u32), Arc<Galley>>,
    glyph_bytes: usize,
    cursor_blink: blink::CursorBlink,
    native_key: Option<crate::runtime_host::TerminalKey>,
    native_focus: Option<crate::platform::terminal_keys::FocusLease>,
    native_running: bool,
    native_budget: usize,
}
impl TerminalRenderer {
    pub fn resize_failed(&mut self) {
        self.resize_sent = None;
        self.resize_version = None;
    }
    pub fn resize_rejected(&mut self, version: u64) -> bool {
        if self.resize_version == Some(version) {
            self.resize_failed();
            true
        } else {
            false
        }
    }
    pub fn show_key(
        &mut self,
        ui: &mut egui::Ui,
        frame: &Frame,
        running: bool,
        key: &crate::runtime_host::TerminalKey,
    ) -> TerminalViewResponse {
        self.show_key_with_budget(ui, frame, running, key, 64)
    }
    pub fn show_key_with_budget(
        &mut self,
        ui: &mut egui::Ui,
        frame: &Frame,
        running: bool,
        key: &crate::runtime_host::TerminalKey,
        input_budget: usize,
    ) -> TerminalViewResponse {
        self.view_id = Some(egui::Id::unique(("vt_view", key)));
        self.native_key = Some(key.clone());
        self.native_running = running;
        self.native_budget = input_budget;
        self.show(ui, frame, running && input_budget > 0)
    }
    pub fn new() -> Self {
        Self {
            follow: true,
            ..Default::default()
        }
    }
    pub fn focus(&mut self, ui: &egui::Ui, session: u64) {
        let id = self
            .view_id
            .unwrap_or_else(|| egui::Id::unique(("vt_view", session)));
        ui.memory_mut(|memory| memory.request_focus(id));
    }
    pub fn show(
        &mut self,
        ui: &mut egui::Ui,
        frame: &Frame,
        running: bool,
    ) -> TerminalViewResponse {
        let mut output = TerminalViewResponse::default();
        let native_key = self.native_key.take();
        let native_running = std::mem::take(&mut self.native_running);
        let native_budget = std::mem::take(&mut self.native_budget);
        if self.session != frame.session_id {
            self.session = frame.session_id;
            self.geometry = frame.geometry.version;
            self.top = None;
            self.first_index = 0;
            self.follow = true;
            self.alternate = frame.modes.alternate;
            self.main_reading = None;
            self.selection = None;
            self.selecting = false;
            self.selection_scroll = 0.0;
            self.preedit.clear();
            self.sticky = Modifiers::default();
            self.resize_sent = None;
            self.resize_version = None;
            self.glyphs.clear();
            self.glyph_bytes = 0;
            self.mouse_buttons = 0;
            self.native_focus = None;
            self.cursor_blink = Default::default();
        }
        if self.alternate != frame.modes.alternate {
            if frame.modes.alternate {
                self.main_reading = Some((self.top, self.first_index, self.follow));
                self.top = None;
                self.first_index = 0;
                self.follow = true;
            } else if let Some((top, first, follow)) = self.main_reading.take() {
                self.top = top;
                self.first_index = first;
                self.follow = follow;
            }
            self.alternate = frame.modes.alternate;
            self.selection = None;
            self.selecting = false;
            self.mouse_buttons = 0;
        }
        if self.geometry != frame.geometry.version {
            self.geometry = frame.geometry.version;
            // Row-only maps cannot prove a selection's new column offsets.
            self.selection = None;
            self.selecting = false;
            if !self.alternate {
                self.top = self
                    .top
                    .map(|id| frame.reflow_rows.get(&id).copied().unwrap_or(id));
            }
            if let Some((top, _, _)) = &mut self.main_reading {
                *top = top.map(|id| frame.reflow_rows.get(&id).copied().unwrap_or(id));
            }
            self.resize_sent = None;
            self.resize_version = None;
        }
        if frame.modes.mouse_tracking == 0 {
            self.mouse_buttons = 0;
        }
        ui.horizontal(|ui| {
            if ui.button("复制选择").clicked() {
                if let Some(text) = self.selected_text(frame) {
                    ui.ctx().copy_text(text);
                }
            }
            if ui.button("复制全部").clicked() {
                ui.ctx().copy_text(frame.copy_all());
            }
            if ui.button("回到底部").clicked() {
                self.follow = true;
                self.top = None;
            }
            if ui.add_enabled(running, egui::Button::new("清屏")).clicked() {
                output.inputs.push(Input::Clear);
            }
        });
        let key_height = 32.0;
        let size = Vec2::new(
            ui.available_width().max(1.0),
            (ui.available_height() - key_height - ui.spacing().item_spacing.y).max(1.0),
        );
        let (rect, _) = ui.allocate_exact_size(size, Sense::hover());
        let id = self
            .view_id
            .unwrap_or_else(|| egui::Id::unique(("vt_view", frame.session_id)));
        let response = ui.interact(rect, id, Sense::click_and_drag());
        let font = FontId::monospace(14.0);
        let (cell_width, line_height) = ui.fonts_mut(|fonts| {
            (
                fonts.glyph_width(&font, 'M').max(1.0),
                fonts.row_height(&font).max(1.0),
            )
        });
        let columns = (rect.width() / cell_width).floor().clamp(1.0, 240.0) as u16;
        let rows = (rect.height() / line_height).floor().clamp(1.0, 120.0) as u16;
        if running
            && (columns != frame.geometry.columns || rows != frame.geometry.rows)
            && self.resize_sent != Some((columns, rows))
        {
            output.resize = Some(Geometry {
                columns,
                rows,
                version: frame.geometry.version.saturating_add(1),
            });
            self.resize_sent = Some((columns, rows));
            self.resize_version = Some(frame.geometry.version.saturating_add(1));
        }
        let visible_rows = (rect.height() / line_height).ceil().max(1.0) as usize;
        let maximum = frame
            .lines
            .len()
            .saturating_sub(usize::from(frame.geometry.rows));
        let mut first = if self.follow {
            maximum
        } else {
            self.top
                .and_then(|id| frame.lines.iter().position(|line| line.id == id))
                .or_else(|| {
                    self.top
                        .and_then(|id| frame.reflow_rows.get(&id))
                        .and_then(|id| frame.lines.iter().position(|line| line.id == *id))
                })
                .unwrap_or(self.first_index)
                .min(maximum)
        };
        if self.selection.is_some_and(|(a, b)| {
            !frame.lines.iter().any(|row| row.id == a.row)
                || !frame.lines.iter().any(|row| row.id == b.row)
        }) {
            self.selection = None;
            self.selecting = false;
        }
        if response.clicked() {
            response.request_focus();
            output.keyboard_requested = running;
        }
        if response.hovered() {
            let wheel = ui.input(|input| input.smooth_scroll_delta.y);
            if wheel != 0.0 {
                self.scroll_fraction += wheel / line_height;
                let delta = self.scroll_fraction.trunc() as isize;
                self.scroll_fraction -= delta as f32;
                if delta != 0 {
                    self.scroll(
                        frame,
                        delta,
                        rect,
                        cell_width,
                        line_height,
                        &mut first,
                        &mut output,
                        running,
                        ui.input(|input| input.pointer.hover_pos()),
                        mods(ui.input(|input| input.modifiers)),
                    );
                }
                ui.input_mut(|input| {
                    input.smooth_scroll_delta = Vec2::ZERO;
                });
            }
        }
        let pointer = response.interact_pointer_pos();
        let anchor_at = |position: Pos2, first: usize| -> Option<Anchor> {
            let row = first + ((position.y - rect.top()) / line_height).floor().max(0.0) as usize;
            let row = frame
                .lines
                .get(row.min(frame.lines.len().saturating_sub(1)))?;
            let mut column = ((position.x - rect.left()) / cell_width).floor().max(0.0) as usize;
            column = column.min(row.cells.len().saturating_sub(1));
            if row.cells.get(column).is_some_and(|cell| cell.width == 0) {
                column = column.saturating_sub(1);
            }
            Some(Anchor {
                row: row.id,
                column,
            })
        };
        let pointer_is_touch = ui.input(|input| {
            input
                .events
                .iter()
                .any(|event| matches!(event, egui::Event::Touch { .. }))
        });
        let selection_override =
            frame.modes.mouse_tracking == 0 || ui.input(|input| input.modifiers.shift);
        if (response.long_touched() || response.double_clicked() || response.secondary_clicked())
            && selection_override
        {
            if let Some(anchor) = pointer.and_then(|pos| anchor_at(pos, first)) {
                self.selection = Some(word_selection(frame, anchor));
                self.selecting = true;
                self.selecting_start = false;
                self.follow = false;
            }
        }
        if response.clicked() && ui.input(|input| input.modifiers.shift) && selection_override {
            if let Some(anchor) = pointer.and_then(|pos| anchor_at(pos, first)) {
                if let Some((_, end)) = &mut self.selection {
                    *end = anchor;
                } else {
                    self.selection = Some((anchor, anchor));
                }
                self.follow = false;
            }
        }
        if response.drag_started() {
            let handle = self.selection.and_then(|(start, end)| {
                pointer.and_then(|position| {
                    for (anchor, is_start) in [(start, true), (end, false)] {
                        if let Some(center) = selection_handle(
                            frame,
                            anchor,
                            is_start,
                            first,
                            rect,
                            cell_width,
                            line_height,
                        ) {
                            if center.distance(position) <= 14.0 {
                                return Some(is_start);
                            }
                        }
                    }
                    None
                })
            });
            if let Some(is_start) = handle.filter(|_| selection_override) {
                self.selecting = true;
                self.selecting_start = is_start;
                self.follow = false;
            } else if !pointer_is_touch && selection_override {
                if let Some(anchor) = pointer.and_then(|pos| anchor_at(pos, first)) {
                    if ui.input(|input| input.modifiers.shift) {
                        if let Some((_, end)) = &mut self.selection {
                            *end = anchor;
                        } else {
                            self.selection = Some((anchor, anchor));
                        }
                    } else {
                        self.selection = Some((anchor, anchor));
                    }
                    self.selecting = true;
                    self.selecting_start = false;
                    self.follow = false;
                }
            }
        }
        if response.dragged() {
            if self.selecting {
                if let Some(position) = pointer {
                    let edge = line_height.min(20.0);
                    let direction = if position.y < rect.top() + edge {
                        -1.0
                    } else if position.y > rect.bottom() - edge {
                        1.0
                    } else {
                        0.0
                    };
                    self.selection_scroll +=
                        direction * ui.input(|input| input.stable_dt).clamp(0.0, 0.1) * 12.0;
                    let delta = self.selection_scroll.trunc() as isize;
                    self.selection_scroll -= delta as f32;
                    first = first.saturating_add_signed(delta).min(maximum);
                    if direction != 0.0 {
                        ui.ctx().request_repaint();
                    }
                }
                if let Some(anchor) = pointer.and_then(|pos| anchor_at(pos, first)) {
                    if let Some((start, end)) = &mut self.selection {
                        if self.selecting_start {
                            *start = anchor;
                        } else {
                            *end = anchor;
                        }
                    }
                }
            } else if frame.modes.mouse_tracking == 0 {
                let delta = response.drag_delta().y;
                self.scroll_fraction += delta / line_height;
                let delta = self.scroll_fraction.trunc() as isize;
                self.scroll_fraction -= delta as f32;
                if delta != 0 {
                    self.scroll(
                        frame,
                        delta,
                        rect,
                        cell_width,
                        line_height,
                        &mut first,
                        &mut output,
                        running,
                        pointer,
                        mods(ui.input(|input| input.modifiers)),
                    );
                }
            }
        }
        if response.drag_stopped() {
            self.selecting = false;
            self.selection_scroll = 0.0;
        }
        if frame.modes.mouse_tracking != 0
            && (!self.selecting || self.mouse_buttons != 0)
            && (response.hovered() || self.mouse_buttons != 0)
            && running
        {
            for event in ui.input(|input| input.events.clone()) {
                if let egui::Event::PointerButton {
                    pos,
                    button,
                    pressed,
                    modifiers,
                } = event
                {
                    let button_code = match button {
                        egui::PointerButton::Primary => 0,
                        egui::PointerButton::Middle => 1,
                        _ => 2,
                    };
                    let bit = 1u8 << button_code;
                    let captured = self.mouse_buttons & bit != 0;
                    if (!pressed && captured)
                        || (!self.selecting && rect.contains(pos) && !modifiers.shift)
                    {
                        if pressed {
                            self.mouse_buttons |= bit;
                        } else {
                            self.mouse_buttons &= !bit;
                        }
                        output.inputs.push(Input::Mouse {
                            column: ((pos.x - rect.left()) / cell_width)
                                .floor()
                                .max(0.0)
                                .min(f32::from(frame.geometry.columns - 1))
                                as u16,
                            row: ((pos.y - rect.top()) / line_height)
                                .floor()
                                .max(0.0)
                                .min(f32::from(frame.geometry.rows - 1))
                                as u16,
                            button: button_code,
                            pressed,
                            modifiers: mods(modifiers),
                        });
                    }
                } else if let egui::Event::PointerMoved(pos) = event {
                    let report = frame.modes.mouse_tracking == 1003
                        || (frame.modes.mouse_tracking == 1002 && self.mouse_buttons != 0);
                    if report
                        && !self.selecting
                        && !ui.input(|input| input.modifiers.shift)
                        && (rect.contains(pos) || self.mouse_buttons != 0)
                    {
                        output.inputs.push(Input::Mouse {
                            column: ((pos.x - rect.left()) / cell_width)
                                .floor()
                                .max(0.0)
                                .min(f32::from(frame.geometry.columns - 1))
                                as u16,
                            row: ((pos.y - rect.top()) / line_height)
                                .floor()
                                .max(0.0)
                                .min(f32::from(frame.geometry.rows - 1))
                                as u16,
                            button: 32
                                + if self.mouse_buttons & 1 != 0 {
                                    0
                                } else if self.mouse_buttons & 2 != 0 {
                                    1
                                } else if self.mouse_buttons & 4 != 0 {
                                    2
                                } else {
                                    3
                                },
                            pressed: true,
                            modifiers: mods(ui.input(|input| input.modifiers)),
                        });
                    }
                }
            }
        }
        self.top = frame.lines.get(first).map(|line| line.id);
        self.first_index = first;
        let focused = response.has_focus();
        if focused && native_running && ui.input(|input| input.focused) {
            if let Some(key) = native_key {
                if !self
                    .native_focus
                    .as_ref()
                    .is_some_and(|lease| lease.current(&key, id))
                {
                    self.native_focus =
                        crate::platform::terminal_keys::bind(key, id, ui.ctx().clone());
                }
                let allowance = native_budget
                    .saturating_sub(output.inputs.len() + usize::from(output.resize.is_some()) + 1);
                if let Some(lease) = &self.native_focus {
                    output.inputs.extend(lease.drain(allowance));
                }
            } else {
                self.native_focus = None;
            }
        } else {
            self.native_focus = None;
        }
        if focused != self.last_focus {
            output.inputs.push(Input::Focus(focused));
            self.last_focus = focused;
            if !focused {
                self.preedit.clear();
            }
        }
        if focused && running {
            ui.memory_mut(|memory| {
                memory.set_focus_lock_filter(
                    id,
                    egui::EventFilter {
                        tab: true,
                        horizontal_arrows: true,
                        vertical_arrows: true,
                        escape: true,
                    },
                )
            });
            let events = ui.input_mut(|input| {
                let mut handled = Vec::new();
                input.events.retain(|event| {
                    if matches!(
                        event,
                        egui::Event::Text(_)
                            | egui::Event::Key { .. }
                            | egui::Event::Ime(_)
                            | egui::Event::Paste(_)
                            | egui::Event::Copy
                            | egui::Event::Cut
                    ) {
                        handled.push(event.clone());
                        false
                    } else {
                        true
                    }
                });
                handled
            });
            let global_modifiers = mods(ui.input(|input| input.modifiers));
            let mut text_modifiers = None;
            for (event_index, event) in events.iter().enumerate() {
                if !matches!(
                    event,
                    egui::Event::Text(_) | egui::Event::Key { pressed: true, .. }
                ) {
                    text_modifiers = None;
                }
                match event {
                    egui::Event::Copy | egui::Event::Cut => {
                        if let Some(text) = self.selected_text(frame) {
                            ui.ctx().copy_text(text);
                        } else {
                            output.inputs.push(Input::Text {
                                text: if matches!(event, egui::Event::Copy) {
                                    "c"
                                } else {
                                    "x"
                                }
                                .into(),
                                modifiers: Modifiers {
                                    control: true,
                                    ..Default::default()
                                },
                            });
                        }
                    }
                    egui::Event::Paste(text) => {
                        if text.len() <= 16 * 1024 {
                            if text
                                .chars()
                                .any(|ch| (ch.is_control() && ch != '\t') || ch == '\u{7f}')
                            {
                                output.paste_confirmation = Some(text.clone());
                            } else {
                                output.inputs.push(Input::Paste(text.clone()));
                            }
                        }
                    }
                    egui::Event::Text(text) => {
                        // egui-winit forwards IME commits independently and filters
                        // IME-processed key text. Equal Text/Commit payloads may be
                        // two real inputs in the same frame; never deduplicate by text.
                        if text.len() <= 16 * 1024 {
                            let modifiers = merge_mods(
                                text_modifiers.take().unwrap_or(global_modifiers),
                                self.sticky,
                            );
                            output.inputs.push(Input::Text {
                                text: text.clone(),
                                modifiers,
                            });
                            self.sticky = Modifiers::default();
                        }
                    }
                    egui::Event::Ime(egui::ImeEvent::Preedit { text, .. }) => {
                        self.preedit = text.chars().take(1024).collect()
                    }
                    egui::Event::Ime(egui::ImeEvent::Commit(text)) => {
                        self.preedit.clear();
                        if text.len() <= 16 * 1024 {
                            output.inputs.push(Input::Text {
                                text: text.clone(),
                                modifiers: self.sticky,
                            });
                            self.sticky = Modifiers::default();
                        }
                    }
                    egui::Event::Ime(egui::ImeEvent::DeleteSurrounding {
                        before_chars,
                        after_chars,
                    }) => {
                        for _ in 0..(*before_chars).min(256) {
                            output.inputs.push(Input::Key {
                                key: Key::Backspace,
                                modifiers: Modifiers::default(),
                                toolbar: false,
                            });
                        }
                        for _ in 0..(*after_chars).min(256) {
                            output.inputs.push(Input::Key {
                                key: Key::Delete,
                                modifiers: Modifiers::default(),
                                toolbar: false,
                            });
                        }
                    }
                    egui::Event::Key {
                        key,
                        pressed: true,
                        modifiers,
                        ..
                    } => {
                        text_modifiers = Some(mods(*modifiers));
                        let modifiers = merge_mods(mods(*modifiers), self.sticky);
                        if let Some(key) = map_key(*key) {
                            text_modifiers = None;
                            output.inputs.push(Input::Key {
                                key,
                                modifiers,
                                toolbar: false,
                            });
                            self.sticky = Modifiers::default();
                        } else if modifiers.control {
                            if let Some(text) = control_text(*key) {
                                // The bridge's text immediately follows its key
                                // event. Do not search equal strings across a frame.
                                let forwarded = matches!(
                                    events.get(event_index + 1),
                                    Some(egui::Event::Text(_))
                                ) || (*key == egui::Key::C
                                    && matches!(
                                        events.get(event_index + 1),
                                        Some(egui::Event::Copy)
                                    ))
                                    || (*key == egui::Key::X
                                        && matches!(
                                            events.get(event_index + 1),
                                            Some(egui::Event::Cut)
                                        ));
                                if !forwarded {
                                    output.inputs.push(Input::Text { text, modifiers });
                                    text_modifiers = None;
                                    self.sticky = Modifiers::default();
                                }
                            }
                        }
                    }
                    _ => (),
                }
            }
        }
        let painter = ui.painter().with_clip_rect(rect);
        let default_foreground = rgb(frame.palette.foreground);
        let default_background = rgb(frame.palette.background);
        painter.rect_filled(
            rect,
            0.0,
            if frame.modes.reverse_video {
                default_foreground
            } else {
                default_background
            },
        );
        let selected_range = self.selection.and_then(|(a, b)| {
            let a = frame
                .lines
                .iter()
                .position(|row| row.id == a.row)
                .map(|row| (row, a.column))?;
            let b = frame
                .lines
                .iter()
                .position(|row| row.id == b.row)
                .map(|row| (row, b.column))?;
            Some(if a <= b { (a, b) } else { (b, a) })
        });
        for (index, line) in frame
            .lines
            .iter()
            .enumerate()
            .skip(first)
            .take(visible_rows)
        {
            let y = rect.top() + ((index - first) as f32) * line_height;
            for (column, cell) in line.cells.iter().enumerate() {
                if cell.width == 0 {
                    continue;
                }
                let x = rect.left() + column as f32 * cell_width;
                let cell_rect = Rect::from_min_size(
                    Pos2::new(x, y),
                    Vec2::new(f32::from(cell.width) * cell_width, line_height),
                );
                if !cell_rect.intersects(rect) {
                    continue;
                }
                let bold = cell.style.bold || cell.style.blink;
                let foreground = match cell.style.foreground {
                    Color::Indexed(index) if bold && index < 8 => Color::Indexed(index + 8),
                    other => other,
                };
                let mut fg = resolve_color(foreground, default_foreground, &frame.palette);
                let mut bg =
                    resolve_color(cell.style.background, default_background, &frame.palette);
                if cell.style.inverse ^ frame.modes.reverse_video {
                    std::mem::swap(&mut fg, &mut bg);
                }
                if cell.style.faint {
                    fg = fg.gamma_multiply(0.6);
                }
                let selected = selected_range
                    .is_some_and(|(a, b)| (index, column) >= a && (index, column) <= b);
                if selected {
                    bg = ui.visuals().selection.bg_fill;
                    fg = ui.visuals().selection.stroke.color;
                }
                painter.rect_filled(cell_rect, 0.0, bg);
                if !cell.style.hidden && cell.text != " " {
                    let key = (cell.text.clone(), cell.style, font.size.to_bits());
                    let galley = if let Some(galley) = self.glyphs.get(&key) {
                        galley.clone()
                    } else {
                        let mut job = LayoutJob::default();
                        job.append(
                            &cell.text,
                            0.0,
                            TextFormat {
                                font_id: font.clone(),
                                color: Color32::WHITE,
                                italics: cell.style.italic,
                                ..Default::default()
                            },
                        );
                        let galley = ui.fonts_mut(|fonts| fonts.layout_job(job));
                        if self.glyphs.len() >= 2048
                            || self.glyph_bytes + cell.text.len() > 256 * 1024
                        {
                            self.glyphs.clear();
                            self.glyph_bytes = 0;
                        }
                        self.glyph_bytes += cell.text.len();
                        self.glyphs.insert(key, galley.clone());
                        galley
                    };
                    let glyph_painter = painter.with_clip_rect(cell_rect.intersect(rect));
                    glyph_painter.galley_with_override_text_color(
                        Pos2::new(x, y),
                        galley.clone(),
                        fg,
                    );
                    if bold {
                        glyph_painter.galley_with_override_text_color(
                            Pos2::new(x + 0.5, y),
                            galley,
                            fg,
                        );
                    }
                }
                if cell.style.underline && !cell.style.hidden {
                    let color = if selected {
                        fg
                    } else {
                        resolve_color(cell.style.underline_color, fg, &frame.palette)
                    };
                    draw_underline(&painter, cell_rect, cell.style.underline_kind, color);
                }
                if cell.style.strike && !cell.style.hidden {
                    painter.line_segment(
                        [
                            Pos2::new(x, y + line_height * 0.55),
                            Pos2::new(cell_rect.right(), y + line_height * 0.55),
                        ],
                        Stroke::new(1.0, fg),
                    );
                }
            }
        }
        if let Some((start, end)) = self.selection {
            for (anchor, is_start) in [(start, true), (end, false)] {
                if let Some(center) = selection_handle(
                    frame,
                    anchor,
                    is_start,
                    first,
                    rect,
                    cell_width,
                    line_height,
                ) {
                    if rect.contains(center) {
                        painter.circle_filled(center, 5.0, ui.visuals().selection.bg_fill);
                    }
                }
            }
        }
        let cursor_row = frame.screen_start + frame.cursor.row;
        let cursor_rect = Rect::from_min_size(
            Pos2::new(
                rect.left() + frame.cursor.column as f32 * cell_width,
                rect.top() + (cursor_row as f32 - first as f32) * line_height,
            ),
            Vec2::new(cell_width, line_height),
        );
        let cursor_on_screen =
            frame.cursor.visible && cursor_row >= first && cursor_row < first + visible_rows;
        let cursor_phase = self.cursor_blink.visible(
            ui.ctx(),
            frame,
            (running || native_running)
                && focused
                && ui.input(|input| input.focused)
                && cursor_on_screen
                && ui.is_rect_visible(rect),
            !output.inputs.is_empty(),
        );
        if cursor_on_screen && cursor_phase {
            let color = rgb(frame.palette.cursor);
            match frame.cursor.shape {
                3 | 4 => {
                    painter.line_segment(
                        [cursor_rect.left_bottom(), cursor_rect.right_bottom()],
                        Stroke::new(2.0, color),
                    );
                }
                5 | 6 => {
                    painter.line_segment(
                        [cursor_rect.left_top(), cursor_rect.left_bottom()],
                        Stroke::new(2.0, color),
                    );
                }
                _ => {
                    painter.rect_stroke(
                        cursor_rect,
                        0.0,
                        Stroke::new(if focused { 2.0 } else { 1.0 }, color),
                        egui::StrokeKind::Inside,
                    );
                }
            }
        }
        if !self.preedit.is_empty() {
            painter.text(
                cursor_rect.left_top(),
                egui::Align2::LEFT_TOP,
                &self.preedit,
                font.clone(),
                ui.visuals().selection.stroke.color,
            );
        }
        if focused && running {
            ui.output_mut(|output| {
                output.mutable_text_under_cursor = true;
                output.ime = Some(egui::output::IMEOutput {
                    purpose: egui::IMEPurpose::Terminal,
                    rect,
                    cursor_rect,
                    should_interrupt_composition: false,
                });
            });
        }
        ui.horizontal(|ui| {
            ui.spacing_mut().item_spacing.x = 2.0;
            ui.spacing_mut().button_padding.x = 4.0;
            for (label, key) in [("Esc", Key::Escape), ("Tab", Key::Tab)] {
                if ui.add_enabled(running, egui::Button::new(label)).clicked() {
                    output.inputs.push(Input::Key {
                        key,
                        modifiers: self.sticky,
                        toolbar: true,
                    });
                    self.sticky = Modifiers::default();
                    response.request_focus();
                }
            }
            if ui
                .add_enabled(
                    running,
                    egui::Button::new("Ctrl").selected(self.sticky.control),
                )
                .clicked()
            {
                self.sticky.control = !self.sticky.control;
                self.sticky.alt = false;
                response.request_focus();
            }
            if ui
                .add_enabled(running, egui::Button::new("Alt").selected(self.sticky.alt))
                .clicked()
            {
                self.sticky.alt = !self.sticky.alt;
                self.sticky.control = false;
                response.request_focus();
            }
            for (label, key) in [
                ("←", Key::Left),
                ("↑", Key::Up),
                ("↓", Key::Down),
                ("→", Key::Right),
            ] {
                if ui.add_enabled(running, egui::Button::new(label)).clicked() {
                    output.inputs.push(Input::Key {
                        key,
                        modifiers: self.sticky,
                        toolbar: true,
                    });
                    self.sticky = Modifiers::default();
                    response.request_focus();
                }
            }
            if ui.add_enabled(running, egui::Button::new("键盘")).clicked() {
                response.request_focus();
                output.keyboard_requested = true;
            }
        });
        output
    }
    fn scroll(
        &mut self,
        frame: &Frame,
        delta: isize,
        rect: Rect,
        width: f32,
        height: f32,
        first: &mut usize,
        output: &mut TerminalViewResponse,
        running: bool,
        pointer: Option<Pos2>,
        modifiers: Modifiers,
    ) {
        if frame.modes.mouse_tracking != 0 && !modifiers.shift {
            if !running {
                return;
            }
            let pointer = pointer.unwrap_or(rect.left_top());
            let column = ((pointer.x - rect.left()) / width)
                .floor()
                .max(0.0)
                .min(f32::from(frame.geometry.columns - 1)) as u16;
            let row = ((pointer.y - rect.top()) / height)
                .floor()
                .max(0.0)
                .min(f32::from(frame.geometry.rows - 1)) as u16;
            for _ in 0..delta.unsigned_abs().min(120) {
                output.inputs.push(Input::Mouse {
                    column,
                    row,
                    button: if delta > 0 { 64 } else { 65 },
                    pressed: true,
                    modifiers,
                });
            }
        } else if frame.modes.alternate {
            if !running {
                return;
            }
            for _ in 0..delta.unsigned_abs().min(120) {
                output.inputs.push(Input::Key {
                    key: if delta > 0 { Key::Up } else { Key::Down },
                    modifiers: Modifiers::default(),
                    toolbar: false,
                });
            }
        } else {
            let maximum = frame
                .lines
                .len()
                .saturating_sub(usize::from(frame.geometry.rows));
            *first = first.saturating_add_signed(-delta).min(maximum);
            self.follow = *first == maximum;
        }
    }
    fn selected_text(&self, frame: &Frame) -> Option<String> {
        let (a, b) = self.selection?;
        let a = (
            frame.lines.iter().position(|row| row.id == a.row)?,
            a.column,
        );
        let b = (
            frame.lines.iter().position(|row| row.id == b.row)?,
            b.column,
        );
        let (start, end) = if a <= b { (a, b) } else { (b, a) };
        let mut text = String::new();
        for index in start.0..=end.0 {
            let row = &frame.lines[index];
            let from = if index == start.0 { start.1 } else { 0 };
            let to = if index == end.0 {
                end.1 + 1
            } else {
                row.cells.len()
            };
            let mut line = String::new();
            for cell in row.cells.iter().skip(from).take(to.saturating_sub(from)) {
                if cell.width != 0 {
                    line.push_str(&cell.text);
                }
            }
            text.push_str(if row.wrapped {
                &line
            } else {
                line.trim_end_matches(' ')
            });
            if index < end.0 && !row.wrapped {
                text.push('\n');
            }
            if text.len() > 8 * 1024 * 1024 {
                return None;
            }
        }
        Some(text)
    }
}
fn mods(modifiers: egui::Modifiers) -> Modifiers {
    Modifiers {
        control: modifiers.ctrl,
        alt: modifiers.alt,
        shift: modifiers.shift,
    }
}
fn merge_mods(a: Modifiers, b: Modifiers) -> Modifiers {
    Modifiers {
        control: a.control || b.control,
        alt: a.alt || b.alt,
        shift: a.shift || b.shift,
    }
}
fn control_text(key: egui::Key) -> Option<String> {
    let text = match key {
        egui::Key::Space => " ".into(),
        egui::Key::OpenBracket => "[".into(),
        egui::Key::CloseBracket => "]".into(),
        egui::Key::Backslash => "\\".into(),
        egui::Key::Num2 => "@".into(),
        egui::Key::Num6 => "^".into(),
        egui::Key::Minus => "_".into(),
        egui::Key::Num8 => "\u{7f}".into(),
        _ => {
            let name = format!("{key:?}");
            if name.len() == 1 && name.as_bytes()[0].is_ascii_uppercase() {
                name.to_ascii_lowercase()
            } else {
                return None;
            }
        }
    };
    Some(text)
}
fn map_key(key: egui::Key) -> Option<Key> {
    Some(match key {
        egui::Key::Escape => Key::Escape,
        egui::Key::Tab => Key::Tab,
        egui::Key::Enter => Key::Enter,
        egui::Key::NumLock => Key::NumLock,
        egui::Key::Backspace => Key::Backspace,
        egui::Key::Delete => Key::Delete,
        egui::Key::Insert => Key::Insert,
        egui::Key::ArrowLeft => Key::Left,
        egui::Key::ArrowUp => Key::Up,
        egui::Key::ArrowDown => Key::Down,
        egui::Key::ArrowRight => Key::Right,
        egui::Key::Home => Key::Home,
        egui::Key::End => Key::End,
        egui::Key::PageUp => Key::PageUp,
        egui::Key::PageDown => Key::PageDown,
        _ => {
            let name = format!("{key:?}");
            if let Some(number) = name
                .strip_prefix('F')
                .and_then(|number| number.parse::<u8>().ok())
                .filter(|number| (1..=12).contains(number))
            {
                Key::Function(number)
            } else {
                return None;
            }
        }
    })
}
fn rgb([red, green, blue]: [u8; 3]) -> Color32 {
    Color32::from_rgb(red, green, blue)
}
fn resolve_color(color: Color, default: Color32, palette: &Palette) -> Color32 {
    match color {
        Color::Default => default,
        Color::Rgb(r, g, b) => Color32::from_rgb(r, g, b),
        Color::Indexed(index) => rgb(palette.colors[usize::from(index)]),
    }
}
fn word_selection(frame: &Frame, anchor: Anchor) -> (Anchor, Anchor) {
    let Some(row) = frame.lines.iter().find(|row| row.id == anchor.row) else {
        return (anchor, anchor);
    };
    if row
        .cells
        .get(anchor.column)
        .is_none_or(|cell| cell.text.chars().all(char::is_whitespace))
    {
        return (anchor, anchor);
    }
    let member = |column: usize| {
        row.cells
            .get(column)
            .is_some_and(|cell| cell.width == 0 || !cell.text.chars().all(char::is_whitespace))
    };
    let mut left = anchor.column;
    let mut right = anchor.column;
    while left > 0 && member(left - 1) {
        left -= 1;
    }
    while right + 1 < row.cells.len() && member(right + 1) {
        right += 1;
    }
    if row.cells[right].width == 0 {
        right = right.saturating_sub(1);
    }
    (
        Anchor {
            row: anchor.row,
            column: left,
        },
        Anchor {
            row: anchor.row,
            column: right,
        },
    )
}
fn selection_handle(
    frame: &Frame,
    anchor: Anchor,
    start: bool,
    first: usize,
    rect: Rect,
    width: f32,
    height: f32,
) -> Option<Pos2> {
    let index = frame.lines.iter().position(|row| row.id == anchor.row)?;
    let cell = frame.lines[index].cells.get(anchor.column)?;
    let column = anchor.column
        + if start {
            0
        } else {
            usize::from(cell.width.max(1))
        };
    Some(Pos2::new(
        rect.left() + column as f32 * width,
        rect.top() + (index as f32 - first as f32 + 1.0) * height - 4.0,
    ))
}
fn draw_underline(painter: &egui::Painter, rect: Rect, kind: u8, color: Color32) {
    let y = rect.bottom() - 2.0;
    let line = |y| {
        painter.line_segment(
            [Pos2::new(rect.left(), y), Pos2::new(rect.right(), y)],
            Stroke::new(1.0, color),
        )
    };
    match kind {
        2 => {
            line(y);
            line(y - 2.0);
        }
        3 => {
            let mut x = rect.left();
            let mut high = false;
            // At most one point per 2 pixels within a bounded terminal cell.
            while x < rect.right() {
                let next = (x + 2.0).min(rect.right());
                painter.line_segment(
                    [
                        Pos2::new(x, y - if high { 2.0 } else { 0.0 }),
                        Pos2::new(next, y - if high { 0.0 } else { 2.0 }),
                    ],
                    Stroke::new(1.0, color),
                );
                high = !high;
                x = next;
            }
        }
        4 | 5 => {
            let length = if kind == 4 { 1.0 } else { 4.0 };
            let mut x = rect.left();
            while x < rect.right() {
                painter.line_segment(
                    [
                        Pos2::new(x, y),
                        Pos2::new((x + length).min(rect.right()), y),
                    ],
                    Stroke::new(1.0, color),
                );
                x += length + 2.0;
            }
        }
        _ => {
            line(y);
        }
    }
}
