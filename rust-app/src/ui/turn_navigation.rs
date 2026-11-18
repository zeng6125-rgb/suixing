//! The permanent right edge rail: at most twelve consecutive, real turns.
//! Drawing emits bounded directory reads and exact identity jumps, never file reads.
use super::history::HistoryViews;
use crate::display::{
    SourceStamp, ViewSource,
    turn_navigation::{TurnPage, TurnRequest, TurnTarget},
};
use eframe::egui::{self, Color32, Pos2, Rect, Stroke};
use std::time::Duration;

const SLOTS: usize = 12;
const RAIL: f32 = 24.0;
const ROW: f32 = 8.0;
const GAP: f32 = 3.0;
const PITCH: f32 = ROW + GAP;
const HOLD: f64 = 0.220;
const DRAG: f32 = 18.0;
const EXIT: f32 = 28.0;
const WAIT: f64 = 10.0;

pub(super) enum Intent {
    Read(TurnRequest),
    Jump(TurnTarget),
}
struct Pending {
    id: String,
    stamp: SourceStamp,
    started: f64,
    reading: String,
}
struct Gesture {
    previous: Pos2,
    started: f64,
    travel: f32,
    dragged: bool,
    shown: bool,
    current: usize,
    pull: usize,
    row: usize,
    held: Option<String>,
    count: usize,
}
#[derive(Default)]
struct Pulse {
    position: f32,
    velocity: f32,
    target: f32,
    outward: bool,
    frame: Option<f64>,
}
impl Pulse {
    fn start(&mut self, direction: f32) {
        self.target = direction * 6.0;
        self.outward = true;
    }
    fn step(&mut self, now: f64, scale: f32) -> bool {
        if scale == 0.0 {
            *self = Self::default();
            return false;
        }
        if self.position == 0.0 && self.target == 0.0 && self.velocity == 0.0 {
            self.frame = Some(now);
            return false;
        }
        let delta = (self
            .frame
            .replace(now)
            .map_or(1.0 / 60.0, |frame| (now - frame) as f32)
            / scale)
            .clamp(0.0, 0.032);
        let omega = if self.outward {
            1500.0_f32.sqrt()
        } else {
            600.0_f32.sqrt()
        };
        let error = self.position - self.target;
        let term = self.velocity + omega * error;
        let decay = (-omega * delta).exp();
        self.position = self.target + (error + term * delta) * decay;
        self.velocity = (self.velocity - omega * term * delta) * decay;
        if (self.position - self.target).abs() < 0.03 && self.velocity.abs() < 0.1 {
            if self.outward {
                self.target = 0.0;
                self.outward = false;
            } else {
                *self = Self::default();
                return false;
            }
        }
        true
    }
}
#[derive(Default)]
pub(super) struct Navigation {
    source: Option<ViewSource>,
    generation: Option<String>,
    page: Option<TurnPage>,
    pending: Option<Pending>,
    reading: String,
    next_request: f64,
    gesture: Option<Gesture>,
    jump: Option<(TurnTarget, f64)>,
    deferred: Option<(usize, SourceStamp, f64)>,
    motion_scale: Option<f32>,
    reading_start: Option<usize>,
    pulse: Pulse,
    label: Option<(usize, usize, bool, String)>,
}
fn start(total: usize, current: usize, pull: usize) -> usize {
    let size = total.min(SLOTS);
    if size == 0 {
        return 0;
    }
    current
        .clamp(1, total)
        .saturating_sub(size / 2)
        .clamp(1, total - size + 1)
        .saturating_sub(pull)
        .max(1)
}
fn hit(y: f32, count: usize) -> usize {
    (((y - ROW / 2.0) / PITCH).round() as isize).clamp(0, count.saturating_sub(1) as isize) as usize
}
impl Navigation {
    pub(super) fn set_motion_scale(&mut self, scale: f32) {
        self.motion_scale = Some(if scale.is_finite() && scale >= 0.0 {
            scale
        } else {
            1.0
        });
    }
    pub(super) fn interacting(&self) -> bool {
        self.gesture.is_some()
    }
    pub(super) fn apply(&mut self, page: TurnPage, stamp: Option<&SourceStamp>) -> bool {
        let Some(pending) = &self.pending else {
            return false;
        };
        if pending.id != page.request_id || self.source.as_ref() != Some(&page.source) {
            return false;
        }
        let reading = pending.reading.clone();
        self.pending = None;
        if stamp.is_none_or(|stamp| stamp.generation != page.stamp.generation) {
            return false;
        }
        self.reading = reading;
        self.page = Some(page);
        true
    }
    pub(super) fn failed(&mut self, source: &ViewSource, id: &str) -> bool {
        if self.source.as_ref() == Some(source)
            && self
                .pending
                .as_ref()
                .is_some_and(|pending| pending.id == id)
        {
            self.pending = None;
            self.deferred = None;
            return true;
        }
        false
    }
    pub(super) fn disconnected(&mut self) {
        self.pending = None;
        self.gesture = None;
        self.jump = None;
        self.deferred = None;
        self.pulse = Pulse::default();
    }
    pub(super) fn turn_changed(&mut self, source: &ViewSource) {
        if self.source.as_ref() == Some(source) {
            self.disconnected();
            self.page = None;
            self.reading.clear();
            self.next_request = 0.0;
        }
    }
    fn entry_ref(&self, ordinal: usize) -> Option<&TurnTarget> {
        let page = self.page.as_ref()?;
        page.entries.get(ordinal.checked_sub(page.first)?)
    }
    fn entry(&self, ordinal: usize) -> Option<TurnTarget> {
        self.entry_ref(ordinal).cloned()
    }
    fn read(
        &mut self,
        source: &ViewSource,
        stamp: &SourceStamp,
        first: Option<usize>,
        reading: String,
        now: f64,
        intents: &mut Vec<Intent>,
    ) {
        if self.pending.is_some() || now < self.next_request {
            return;
        }
        let request_id = crate::controller::unique_id();
        self.pending = Some(Pending {
            id: request_id.clone(),
            stamp: stamp.clone(),
            started: now,
            reading: reading.clone(),
        });
        self.next_request = now + 0.25;
        intents.push(Intent::Read(TurnRequest {
            request_id,
            source: source.clone(),
            stamp: stamp.clone(),
            first,
            reading_message_id: reading,
            max_turns: crate::display::turn_navigation::MAX_TURN_PAGE,
        }));
    }
    fn jump(&mut self, ordinal: usize, stamp: &SourceStamp, now: f64, intents: &mut Vec<Intent>) {
        if let Some(target) = self
            .entry(ordinal)
            .filter(|entry| entry.stamp.generation == stamp.generation)
        {
            self.jump = Some((target.clone(), now));
            intents.push(Intent::Jump(target));
        } else {
            self.deferred = Some((ordinal, stamp.clone(), now));
        }
    }
    pub(super) fn show(
        &mut self,
        ui: &mut egui::Ui,
        views: &HistoryViews,
        source: &ViewSource,
        viewport: Option<Rect>,
        presented: Option<&SourceStamp>,
        reading_message: Option<&str>,
    ) -> Vec<Intent> {
        let mut intents = Vec::new();
        let Some(view) = views.view(source) else {
            return intents;
        };
        let Some(stamp) = &view.stamp else {
            return intents;
        };
        let now = ui.input(|input| input.time);
        if self.source.as_ref() != Some(source)
            || self.generation.as_ref() != Some(&stamp.generation)
        {
            let motion_scale = self.motion_scale;
            *self = Self {
                source: Some(source.clone()),
                generation: Some(stamp.generation.clone()),
                motion_scale,
                ..Default::default()
            };
        }
        if self.pending.as_ref().is_some_and(|pending| {
            pending.stamp.generation != stamp.generation || now - pending.started > WAIT
        }) {
            self.pending = None;
        }
        if self.jump.as_ref().is_some_and(|(target, started)| {
            target.stamp.generation != stamp.generation || now - started > WAIT + 15.0
        }) {
            self.jump = None;
        }
        if self.deferred.as_ref().is_some_and(|(_, old, started)| {
            old.generation != stamp.generation || now - started > WAIT
        }) {
            self.deferred = None;
        }
        let reading = if let Some(reading) = reading_message {
            reading.to_owned()
        } else if view.position.follow_tail {
            String::new()
        } else {
            view.position.message_id.clone()
        };
        let current_user = view
            .rows
            .iter()
            .find(|row| row.id == reading)
            .and_then(|row| row.turn_id.as_deref());
        let current = self.page.as_ref().and_then(|page| {
            if view.position.follow_tail && reading_message.is_none() {
                Some(page.total)
            } else {
                current_user
                    .and_then(|id| page.entries.iter().find(|entry| entry.message_id == id))
                    .map(|entry| entry.ordinal)
                    .or_else(|| (self.reading == reading).then_some(page.current))
            }
        });
        let valid_page = self
            .page
            .as_ref()
            .is_some_and(|page| page.stamp.generation == stamp.generation);
        if !valid_page || current.is_none() {
            self.read(source, stamp, None, reading.clone(), now, &mut intents);
        }
        if let Some((ordinal, old, _)) = self.deferred.clone() {
            if old.generation == stamp.generation && self.entry_ref(ordinal).is_some() && valid_page
            {
                self.deferred = None;
                self.jump(ordinal, stamp, now, &mut intents);
            } else if old.generation == stamp.generation {
                self.read(
                    source,
                    stamp,
                    Some(ordinal.saturating_sub(16).max(1)),
                    reading.clone(),
                    now,
                    &mut intents,
                );
            }
        }
        let Some(page) = &self.page else {
            return intents;
        };
        if page.total == 0 {
            return intents;
        }
        let total = page.total;
        let Some(actual_current) = current else {
            return intents;
        };
        if self.jump.as_ref().is_some_and(|(target, _)| {
            target.ordinal == actual_current && presented == Some(stamp) && !view.window_pending()
        }) {
            self.jump = None;
        }
        let current = self
            .jump
            .as_ref()
            .map_or(actual_current, |(target, _)| target.ordinal);
        let Some(viewport) = viewport.filter(|rect| rect.is_positive()) else {
            return intents;
        };
        let count = total.min(SLOTS);
        let height = ROW * count as f32 + GAP * count.saturating_sub(1) as f32;
        let rail = Rect::from_min_size(
            egui::pos2(
                viewport.right() - 4.0 - RAIL,
                viewport.center().y - height / 2.0,
            ),
            egui::vec2(RAIL, height),
        );
        let ready = valid_page;
        let motion_scale = self.motion_scale.unwrap_or(1.0);
        let reading_start = start(total, actual_current, 0);
        if let Some(previous) = self.reading_start.replace(reading_start) {
            if ready && self.gesture.is_none() && previous != reading_start {
                self.pulse
                    .start(if reading_start > previous { -1.0 } else { 1.0 });
            }
        }
        if !ready || motion_scale == 0.0 {
            self.pulse = Pulse::default();
        } else if self.gesture.is_some() {
            self.pulse.target = 0.0;
            self.pulse.outward = false;
        }
        if self.pulse.step(now, motion_scale) {
            ui.ctx().request_repaint();
        }
        let nudge = self.pulse.position;
        let pin = self
            .gesture
            .as_ref()
            .map_or(current, |gesture| gesture.current);
        let pull = self.gesture.as_ref().map_or(0, |gesture| gesture.pull);
        let first = start(total, pin, pull);
        let have_slots = (first..first + count).all(|ordinal| self.entry_ref(ordinal).is_some());
        if !have_slots {
            self.read(
                source,
                stamp,
                Some(first.saturating_sub(16).max(1)),
                reading.clone(),
                now,
                &mut intents,
            );
        }
        let id = ui.id().with(("turn_navigation", source));
        let response = ui.interact(
            rail,
            id,
            if (ready && have_slots) || self.gesture.is_some() {
                egui::Sense::click_and_drag()
            } else {
                egui::Sense::hover()
            },
        );
        response.widget_info(|| {
            egui::WidgetInfo::slider(
                ready && have_slots,
                current as f64,
                format!("对话轮次导航：第{current}轮，共{total}轮"),
            )
        });
        ui.ctx().accesskit_node_builder(id, |node| {
            use egui::accesskit::{Action, Orientation};
            node.set_orientation(Orientation::Vertical);
            node.set_min_numeric_value(1.0);
            node.set_max_numeric_value(total as f64);
            node.set_numeric_value_step(1.0);
            // A screen reader changes this range; an unpositioned click has no turn.
            node.remove_action(Action::Click);
            if ready && have_slots && total > 1 {
                node.add_action(Action::SetValue);
                if current < total {
                    node.add_action(Action::Increment);
                }
                if current > 1 {
                    node.add_action(Action::Decrement);
                }
            }
        });
        let mut accessible_turn = None;
        ui.input_mut(|input| {
            use egui::accesskit::{Action, ActionData};
            input.consume_accesskit_action_requests(id, |request| {
                let previous = accessible_turn.unwrap_or(current);
                let target = match request.action {
                    Action::Increment => Some(previous.saturating_add(1).min(total)),
                    Action::Decrement => Some(previous.saturating_sub(1).max(1)),
                    Action::SetValue => match request.data.as_ref() {
                        Some(ActionData::NumericValue(value)) if value.is_finite() => {
                            Some(value.round().clamp(1.0, total as f64) as usize)
                        }
                        _ => None,
                    },
                    _ => return false,
                };
                if ready && have_slots {
                    accessible_turn = target.or(accessible_turn);
                }
                true
            });
        });
        if let Some(ordinal) = accessible_turn.filter(|ordinal| *ordinal != current) {
            self.gesture = None;
            self.label = None;
            self.jump(ordinal, stamp, now, &mut intents);
            if self.deferred.is_some() {
                // Direct range selection can leave the cached directory page.
                // Keep the same bounded read and stamp checks as a dragged jump.
                self.read(
                    source,
                    stamp,
                    Some(ordinal.saturating_sub(16).max(1)),
                    reading.clone(),
                    now,
                    &mut intents,
                );
                ui.ctx().request_repaint_after(Duration::from_millis(250));
            }
        }
        let (pointer, pressed, down, released, focused) = ui.input(|input| {
            (
                input.pointer.interact_pos(),
                input.pointer.primary_pressed(),
                input.pointer.primary_down(),
                input.pointer.primary_released(),
                input.focused,
            )
        });
        if pressed
            && self.gesture.is_none()
            && pointer.is_some_and(|point| viewport.contains(point) && !rail.contains(point))
        {
            self.jump = None;
            self.deferred = None;
        }
        if !focused
            || self
                .gesture
                .as_ref()
                .is_some_and(|gesture| gesture.count != count)
        {
            self.gesture = None;
        }
        if ready && have_slots && pressed && response.contains_pointer() {
            if let Some(pointer) = pointer {
                response.request_focus();
                self.label = None;
                self.gesture = Some(Gesture {
                    previous: pointer,
                    started: now,
                    travel: 0.0,
                    dragged: false,
                    shown: false,
                    current,
                    pull: 0,
                    row: hit(pointer.y - rail.top(), count),
                    held: None,
                    count,
                });
            }
        }
        let mut hold = None;
        let mut commit = None;
        if let Some(gesture) = &mut self.gesture {
            if let Some(pointer) = pointer {
                let delta = pointer - gesture.previous;
                gesture.travel += delta.x.abs().max(delta.y.abs());
                gesture.previous = pointer;
                if gesture.travel >= DRAG {
                    gesture.dragged = true;
                    gesture.shown = true;
                    gesture.row = hit(pointer.y - rail.top(), count);
                    let overshoot = ROW / 2.0 + PITCH / 2.0 - (pointer.y - rail.top());
                    if overshoot >= 0.0 {
                        gesture.pull = gesture.pull.max((overshoot / PITCH) as usize + 1);
                    }
                }
                if down && !gesture.dragged && !gesture.shown && now - gesture.started >= HOLD {
                    gesture.shown = true;
                    hold = Some(start(total, gesture.current, gesture.pull) + gesture.row);
                }
                if released || !down {
                    let cancelled = gesture.dragged
                        && (pointer.x < rail.left() - EXIT
                            || pointer.x > rail.right() + EXIT
                            || pointer.y > rail.bottom() + EXIT);
                    if !cancelled {
                        commit = Some((
                            start(total, gesture.current, gesture.pull) + gesture.row,
                            gesture.held.clone(),
                        ));
                    }
                }
            } else if !down {
                self.gesture = None;
            }
        }
        if let Some(ordinal) = hold {
            if let Some(entry) = self.entry(ordinal) {
                if let Some(gesture) = &mut self.gesture {
                    gesture.held = Some(entry.message_id);
                }
                self.jump(ordinal, stamp, now, &mut intents);
            }
        }
        if let Some((ordinal, held)) = commit {
            self.gesture = None;
            if self
                .entry_ref(ordinal)
                .as_ref()
                .is_none_or(|target| Some(&target.message_id) != held.as_ref())
            {
                self.jump(ordinal, stamp, now, &mut intents);
            }
        }
        if response.has_focus() && ready {
            let direction = ui.input(|input| {
                if input.key_pressed(egui::Key::ArrowUp) {
                    -1
                } else if input.key_pressed(egui::Key::ArrowDown) {
                    1
                } else {
                    0
                }
            });
            if direction != 0 {
                self.jump(
                    current.saturating_add_signed(direction).clamp(1, total),
                    stamp,
                    now,
                    &mut intents,
                );
            }
        }
        let gesture = self.gesture.as_ref();
        let first = start(
            total,
            gesture.map_or(current, |gesture| gesture.current),
            gesture.map_or(0, |gesture| gesture.pull),
        );
        let selected = gesture.map(|gesture| first + gesture.row);
        let shown = gesture.is_some_and(|gesture| gesture.shown);
        let draw_first = if (first..first + count).all(|ordinal| self.entry_ref(ordinal).is_some())
        {
            first
        } else {
            // Keep the fixed rail and its real cached turns while an earlier directory
            // page arrives. The label identifies the pending read; no placeholder jumps.
            let page = self.page.as_ref().unwrap();
            first.clamp(
                page.first,
                page.first + page.entries.len().saturating_sub(count),
            )
        };
        let painter = ui.painter().with_clip_rect(viewport);
        for (slot, ordinal) in (draw_first..draw_first + count).enumerate() {
            if self.entry_ref(ordinal).is_none() {
                continue;
            }
            let distance = ordinal.abs_diff(current);
            let active = ordinal == current;
            let width: f32 = if active {
                20.0
            } else if distance == 1 {
                16.0
            } else if distance <= 3 {
                12.0
            } else {
                8.0
            };
            let scale = if selected == Some(ordinal) {
                if shown { 1.4 } else { 1.12 }
            } else {
                1.0
            };
            let width = ui.ctx().animate_value_with_time(
                id.with((ordinal, "width")),
                (width * scale).min(RAIL),
                if ready { 0.12 * motion_scale } else { 0.0 },
            );
            let thickness = ui.ctx().animate_value_with_time(
                id.with((ordinal, "height")),
                if active { 3.0 } else { 2.0 },
                if ready { 0.12 * motion_scale } else { 0.0 },
            );
            let opacity = ui.ctx().animate_value_with_time(
                id.with((ordinal, "color")),
                if active || selected == Some(ordinal) {
                    1.0
                } else if ordinal < actual_current {
                    0.48
                } else {
                    0.24
                },
                if ready { 0.12 * motion_scale } else { 0.0 },
            );
            let color = ui.visuals().selection.stroke.color.gamma_multiply(opacity);
            let center = egui::pos2(
                rail.center().x,
                rail.top() + slot as f32 * PITCH + ROW / 2.0 + nudge,
            );
            painter.rect_filled(
                Rect::from_center_size(center, egui::vec2(width, thickness * scale)),
                1,
                color,
            );
        }
        if let Some(gesture) = &self.gesture {
            if !gesture.shown && !gesture.dragged {
                ui.ctx().request_repaint_after(Duration::from_secs_f64(
                    (HOLD - (now - gesture.started)).max(0.001),
                ));
            }
            if gesture.shown {
                let ordinal = first + gesture.row;
                let waiting = self.entry_ref(ordinal).is_none();
                if self
                    .label
                    .as_ref()
                    .is_none_or(|(previous, row, loading, _)| {
                        *previous != ordinal || *row != gesture.row || *loading != waiting
                    })
                {
                    let opening = self
                        .entry_ref(ordinal)
                        .and_then(|entry| view.rows.iter().find(|row| row.id == entry.message_id))
                        .and_then(|row| row.preview.as_ref())
                        .map(|preview| {
                            preview
                                .text
                                .lines()
                                .find(|line| !line.trim().is_empty())
                                .unwrap_or("")
                                .trim()
                                .chars()
                                .take(8)
                                .collect::<String>()
                        })
                        .unwrap_or_default();
                    let label = if self.entry_ref(ordinal).is_none() {
                        format!("正在读取第{ordinal}轮…")
                    } else if opening.is_empty() {
                        format!("第{ordinal}轮")
                    } else {
                        format!("第{ordinal}轮 · {opening}")
                    };
                    self.label = Some((ordinal, gesture.row, waiting, label));
                }
            }
        }
        let alpha = ui.ctx().animate_bool_with_time(
            id.with("label_visibility"),
            self.gesture.as_ref().is_some_and(|gesture| gesture.shown),
            if shown {
                0.090 * motion_scale
            } else {
                0.100 * motion_scale
            },
        );
        if alpha > 0.0 {
            if let Some((_, row, _, label)) = &self.label {
                let mut painter = painter.clone();
                painter.multiply_opacity(alpha);
                let scale = 0.94 + 0.06 * alpha;
                let galley = painter.layout_no_wrap(
                    label.clone(),
                    egui::FontId::proportional(13.0 * scale),
                    ui.visuals().text_color(),
                );
                let y = ui.ctx().animate_value_with_time(
                    id.with("label_y"),
                    rail.top() + *row as f32 * PITCH + ROW / 2.0 + nudge,
                    0.12 * motion_scale,
                );
                let width = (galley.size().x + 20.0)
                    .min(180.0)
                    .min((rail.left() - viewport.left() - 6.0).max(0.0));
                let rect = Rect::from_min_size(
                    egui::pos2(rail.left() - 6.0 - width, y - 15.0 * scale),
                    egui::vec2(width, 30.0 * scale),
                );
                painter.rect_filled(rect, 8, ui.visuals().window_fill);
                painter.rect_stroke(
                    rect,
                    8,
                    Stroke::new(1.0, ui.visuals().window_stroke.color),
                    egui::StrokeKind::Inside,
                );
                painter.with_clip_rect(rect.shrink(4.0)).galley(
                    egui::pos2(rect.left() + 10.0, y - galley.size().y / 2.0),
                    galley,
                    Color32::WHITE,
                );
            }
        }
        intents
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture() -> (HistoryViews, Navigation, ViewSource, SourceStamp) {
        let source = ViewSource::Main {
            conversation_id: "chat".into(),
        };
        let stamp = SourceStamp {
            generation: "live-run".into(),
            revision: 1,
            projection_revision: 1,
        };
        let mut views = HistoryViews::default();
        views.open(source.clone(), None);
        let mut later = stamp.clone();
        later.revision = 50;
        later.projection_revision = 50;
        views.invalidate(&source, later).unwrap();
        let entries = (1..=2)
            .map(|ordinal| TurnTarget {
                source: source.clone(),
                stamp: stamp.clone(),
                ordinal,
                message_id: format!("question-{ordinal}"),
                run_id: Some(format!("reply-{ordinal}")),
            })
            .collect();
        let navigation = Navigation {
            source: Some(source.clone()),
            generation: Some(stamp.generation.clone()),
            page: Some(TurnPage {
                request_id: "directory".into(),
                source: source.clone(),
                stamp: stamp.clone(),
                total: 2,
                current: 2,
                first: 1,
                entries,
            }),
            ..Default::default()
        };
        (views, navigation, source, stamp)
    }

    #[test]
    fn metadata_directory_reply_survives_streaming_invalidation() {
        let (views, mut navigation, source, stamp) = fixture();
        let page = navigation.page.take().unwrap();
        navigation.pending = Some(Pending {
            id: "directory".into(),
            stamp,
            started: 0.0,
            reading: String::new(),
        });
        let context = egui::Context::default();
        let _ = context.run_ui(egui::RawInput::default(), |ui| {
            assert!(
                navigation
                    .show(ui, &views, &source, None, None, None)
                    .is_empty()
            );
        });
        assert_eq!(navigation.pending.as_ref().unwrap().id, "directory");
        assert!(navigation.apply(page, views.view(&source).unwrap().stamp.as_ref()));
    }

    #[test]
    fn metadata_rail_can_jump_while_streaming_window_reloads() {
        let (mut views, mut navigation, source, stamp) = fixture();
        let context = egui::Context::default();
        let viewport = Rect::from_min_size(Pos2::ZERO, egui::vec2(400.0, 600.0));
        let pointer = egui::pos2(384.0, 294.5);
        let mut intents = Vec::new();
        for (frame, pressed) in [None, Some(true), Some(false)].into_iter().enumerate() {
            let mut events = vec![egui::Event::PointerMoved(pointer)];
            if let Some(pressed) = pressed {
                events.push(egui::Event::PointerButton {
                    pos: pointer,
                    button: egui::PointerButton::Primary,
                    pressed,
                    modifiers: egui::Modifiers::NONE,
                });
            }
            let input = egui::RawInput {
                screen_rect: Some(viewport),
                time: Some(frame as f64 * 0.1),
                events,
                ..Default::default()
            };
            let _ = context.run_ui(input, |ui| {
                intents.extend(navigation.show(
                    ui,
                    &views,
                    &source,
                    Some(viewport),
                    Some(&stamp),
                    None,
                ));
            });
        }
        assert!(
            !intents
                .iter()
                .any(|intent| matches!(intent, Intent::Read(_)))
        );
        let target = intents
            .into_iter()
            .find_map(|intent| match intent {
                Intent::Jump(target) => Some(target),
                _ => None,
            })
            .unwrap();
        assert_eq!(target.ordinal, 1);
        let mut renderer = super::super::history_view::HistoryRenderer::default();
        assert!(renderer.navigate_turn(&mut views, target).is_ok());
    }
}
