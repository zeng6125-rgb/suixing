use eframe::egui::{self, Event, PointerButton, Pos2, Rect};
use suixing_search::drawer_gesture::{DragAxis, INPUT_GAIN, drag_axis, drag_offset, release_open};

const EDGE: f32 = 32.0;
const SLOP: f32 = 8.0;
const SPRING_STIFFNESS: f32 = 850.0;
const SPRING_THRESHOLD: f32 = 0.003;
const HOME_CORNER_RADIUS: f32 = 57.33;
const HOME_SHADOW_EXTENT: u8 = 16;
// Compose's shadow color is modulated by the platform light. Match its visible penumbra.
const HOME_SHADOW_ALPHA: f32 = 0.026;

fn home_corner_radius(viewport: Rect) -> f32 {
    HOME_CORNER_RADIUS.min(viewport.width().min(viewport.height()) * 0.5)
}

pub(super) fn sheet_frame(visuals: &egui::Visuals) -> egui::Frame {
    egui::Frame::NONE
        .fill(visuals.faint_bg_color)
        .inner_margin(12)
}

pub(super) fn home_layer(
    parent: &mut egui::Ui,
    viewport: Rect,
    shell: Rect,
    progress: f32,
    backdrop: egui::Color32,
) -> egui::Ui {
    let ctx = parent.ctx();
    let layer = egui::LayerId::new(
        parent.layer_id().order,
        parent.scope_id().with("drawer_home"),
    );
    let width = DrawerMotion::width(viewport);
    let scale = 1.0 - 0.05 * progress;
    let translation = shell.center().to_vec2() * (1.0 - scale) + egui::vec2(width * progress, 0.0);
    let transform = egui::emath::TSTransform::new(translation, scale);
    if progress > 0.0 {
        parent.painter().rect_filled(shell, 0, backdrop);
    }
    ctx.set_sublayer(parent.layer_id(), layer);
    ctx.set_transform_layer(layer, transform);
    let clip = transform.inverse() * shell.intersect(parent.clip_rect());
    if progress > 0.0 {
        let shadow = egui::epaint::Shadow {
            blur: HOME_SHADOW_EXTENT * 2,
            color: egui::Color32::from_black_alpha(
                (255.0 * HOME_SHADOW_ALPHA * progress).round() as u8
            ),
            ..Default::default()
        };
        let painter = egui::Painter::new(ctx.clone(), layer, clip);
        painter.add(shadow.as_shape(shell, home_corner_radius(shell)));
        painter.rect_filled(shell, 0, parent.visuals().panel_fill);
    }
    let mut home = parent.new_child(
        egui::UiBuilder::new()
            .id_salt("drawer_home")
            .layer_id(layer)
            .max_rect(viewport),
    );
    home.set_clip_rect(clip.intersect(viewport));
    home
}

pub(super) fn paint_home_corners(
    home: &egui::Ui,
    viewport: Rect,
    progress: f32,
    backdrop: egui::Color32,
) {
    if progress == 0.0 {
        return;
    }
    let radius = home_corner_radius(viewport);
    let extent = f32::from(HOME_SHADOW_EXTENT);
    let feather = 1.0 / home.ctx().pixels_per_point();
    let mut mask = egui::Mesh::default();
    // The drawer ends to the left of the page. Its solid backing is also behind the corners.
    for (corner, sign) in [
        (viewport.left_top(), egui::vec2(-1.0, -1.0)),
        (viewport.right_top(), egui::vec2(1.0, -1.0)),
        (viewport.right_bottom(), egui::vec2(1.0, 1.0)),
        (viewport.left_bottom(), egui::vec2(-1.0, 1.0)),
    ] {
        let center = corner - sign * radius;
        let base = mask.vertices.len() as u32;
        const STEPS: u32 = 20;
        for step in 0..=STEPS {
            let angle = std::f32::consts::FRAC_PI_2 * step as f32 / STEPS as f32;
            let (sin, cos) = angle.sin_cos();
            let direction = egui::vec2(cos * sign.x, sin * sign.y);
            let outer = radius / cos.max(sin);
            mask.colored_vertex(
                center + direction * (radius - feather).max(0.0),
                egui::Color32::TRANSPARENT,
            );
            for distance in [radius, (radius + extent).min(outer), outer] {
                let shadow = 0.5
                    * HOME_SHADOW_ALPHA
                    * progress
                    * (1.0 - (distance - radius) / extent).max(0.0);
                let [r, g, b, _] = backdrop.gamma_multiply(1.0 - shadow).to_array();
                mask.colored_vertex(
                    center + direction * distance,
                    egui::Color32::from_rgb(r, g, b),
                );
            }
        }
        for step in 0..STEPS {
            for band in 0..3 {
                let i = base + 4 * step + band;
                mask.add_triangle(i, i + 1, i + 4);
                mask.add_triangle(i + 1, i + 5, i + 4);
            }
        }
    }
    let ctx = home.ctx();
    let layer = home.layer_id();
    let to_global = ctx.layer_transform_to_global(layer).unwrap_or_default();
    let clip = to_global.inverse() * ctx.viewport_rect();
    egui::Painter::new(ctx.clone(), layer, clip.intersect(viewport)).add(egui::Shape::mesh(mask));
}

pub(super) fn overlay<R>(
    ctx: &egui::Context,
    viewport: Rect,
    progress: f32,
    backdrop: egui::Color32,
    draw: impl FnOnce(&mut egui::Ui) -> Option<R>,
) -> Option<R> {
    if progress == 0.0 {
        return None;
    }
    let width = DrawerMotion::width(viewport);
    let sheet = Rect::from_min_size(
        viewport.min - egui::vec2(width * (1.0 - progress), 0.0),
        egui::vec2(width, viewport.height()),
    );
    egui::Area::new(egui::Id::unique("narrow_app_drawer"))
        .fixed_pos(viewport.min)
        .default_size(viewport.size())
        .constrain(false)
        .fade_in(false)
        .sense(egui::Sense::click_and_drag())
        .show(ctx, |ui| {
            ui.set_min_size(viewport.size());
            let mut panel =
                ui.new_child(egui::UiBuilder::new().id_salt("app_drawer").max_rect(sheet));
            panel.set_clip_rect(sheet.intersect(viewport));
            egui::Frame::NONE
                .fill(backdrop)
                .show(&mut panel, |ui| {
                    ui.set_min_size(sheet.size());
                    draw(ui)
                })
                .inner
        })
        .inner
}

#[derive(Default)]
pub(super) struct DrawerMotion {
    position: f32,
    velocity: f32,
    pointer: Option<DrawerPointer>,
}

struct DrawerPointer {
    down: Pos2,
    last: Pos2,
    on_shield: bool,
    moved: bool,
    axis: DragAxis,
    start: f32,
    offset: f32,
}

impl DrawerMotion {
    pub(super) fn width(viewport: Rect) -> f32 {
        300.0_f32.min((viewport.width() - EDGE).max(1.0))
    }

    pub(super) fn progress(&self) -> f32 {
        self.position.clamp(0.0, 1.0)
    }

    pub(super) fn active(&self) -> bool {
        self.progress() > 0.0
            || self
                .pointer
                .as_ref()
                .is_some_and(|p| p.axis == DragAxis::Horizontal)
    }

    pub(super) fn cancel(&mut self) {
        self.pointer = None;
    }

    pub(super) fn update(
        &mut self,
        ctx: &egui::Context,
        viewport: Rect,
        gestures: bool,
        motion_scale: f32,
        open: &mut bool,
    ) {
        if ctx.current_pass_index() != 0 {
            return;
        }
        if ctx.any_popup_open() || ctx.memory(|memory| memory.top_modal_layer().is_some()) {
            self.cancel();
        } else {
            let width = Self::width(viewport);
            let claimed = ctx.input(|input| {
                let mut claimed = false;
                let mut touch_end = None;
                for event in &input.events {
                    let previous_touch_end = touch_end.take();
                    match *event {
                        Event::Touch {
                            phase: egui::TouchPhase::End,
                            pos,
                            ..
                        } => {
                            touch_end = Some(pos);
                        }
                        Event::PointerButton {
                            pos,
                            button: PointerButton::Primary,
                            pressed: true,
                            ..
                        } => {
                            let progress = self.progress();
                            let on_shield = (progress > 0.0 || *open)
                                && pos.x >= viewport.left() + width * progress;
                            if viewport.contains(pos)
                                && (on_shield
                                    || (gestures
                                        && progress == 0.0
                                        && pos.x <= viewport.left() + EDGE))
                            {
                                self.pointer = Some(DrawerPointer {
                                    down: pos,
                                    last: pos,
                                    on_shield,
                                    moved: false,
                                    axis: DragAxis::Pending,
                                    start: progress,
                                    offset: 0.0,
                                });
                            }
                        }
                        Event::PointerMoved(pos) => {
                            claimed |= self.move_pointer(pos, width, gestures)
                        }
                        Event::PointerButton {
                            pos,
                            button: PointerButton::Primary,
                            pressed: false,
                            ..
                        } => {
                            claimed |= self.move_pointer(
                                previous_touch_end.unwrap_or(pos),
                                width,
                                gestures,
                            );
                            if let Some(pointer) = self.pointer.take() {
                                if pointer.axis == DragAxis::Horizontal {
                                    let velocity_points = input.pointer.velocity().x;
                                    *open = release_open(
                                        self.progress(),
                                        velocity_points * input.pixels_per_point(),
                                        pointer.start,
                                    );
                                    self.velocity = velocity_points / width;
                                } else if pointer.on_shield && !pointer.moved {
                                    *open = false;
                                }
                            }
                        }
                        Event::PointerGone | Event::WindowFocused(false) => self.cancel(),
                        _ => {}
                    }
                }
                claimed
            });
            if claimed {
                ctx.stop_dragging();
                if self.pointer.is_none() {
                    // Draw the release position before advancing the spring.
                    ctx.request_repaint();
                    return;
                }
            }
        }
        if self.pointer.is_none() {
            let delta = ctx.input(|input| input.stable_dt);
            if self.advance(*open, delta, motion_scale) {
                ctx.request_repaint();
            }
        }
    }

    fn move_pointer(&mut self, pos: Pos2, width: f32, gestures: bool) -> bool {
        let Some(pointer) = &mut self.pointer else {
            return false;
        };
        let travel = pos - pointer.down;
        pointer.moved |= travel.x.abs().max(travel.y.abs()) > SLOP;
        let mut delta = (pos - pointer.last).x;
        pointer.last = pos;
        if gestures && pointer.axis == DragAxis::Pending {
            pointer.axis = match drag_axis(travel.x, travel.y, SLOP) {
                DragAxis::Horizontal
                    if (travel.x > 0.0 && pointer.start < 1.0)
                        || (travel.x < 0.0 && pointer.start > 0.0) =>
                {
                    delta = travel.x;
                    DragAxis::Horizontal
                }
                DragAxis::Pending => DragAxis::Pending,
                _ => DragAxis::Vertical,
            };
        }
        if pointer.axis != DragAxis::Horizontal {
            return false;
        }
        pointer.offset = drag_offset(
            pointer.offset,
            delta * INPUT_GAIN,
            -pointer.start * width,
            (1.0 - pointer.start) * width,
        );
        self.position = pointer.start + pointer.offset / width;
        self.velocity = 0.0;
        true
    }

    fn advance(&mut self, open: bool, delta: f32, motion_scale: f32) -> bool {
        let target = if open { 1.0 } else { 0.0 };
        let omega = SPRING_STIFFNESS.sqrt();
        let distance = self.position - target;
        if motion_scale <= 0.0
            || (distance.abs() < SPRING_THRESHOLD && self.velocity.abs() < SPRING_THRESHOLD * omega)
        {
            self.position = target;
            self.velocity = 0.0;
            return false;
        }
        let time = delta / motion_scale;
        let slope = self.velocity + omega * distance;
        let decay = (-omega * time).exp();
        self.position = target + (distance + slope * time) * decay;
        self.velocity = (self.velocity - omega * slope * time) * decay;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn point(x: f32, y: f32) -> Pos2 {
        Pos2::new(x, y)
    }

    fn button(pos: Pos2, pressed: bool) -> Event {
        Event::PointerButton {
            pos,
            button: PointerButton::Primary,
            pressed,
            modifiers: egui::Modifiers::NONE,
        }
    }

    #[test]
    fn edge_drag_follows_reversal_and_tapping_the_shield_closes() {
        let ctx = egui::Context::default();
        let viewport = Rect::from_min_max(Pos2::ZERO, point(385.0, 800.0));
        let mut motion = DrawerMotion::default();
        let mut open = false;
        let mut time = 0.0;
        let mut frame = |events, gestures| {
            time += 0.1;
            ctx.run_ui(
                egui::RawInput {
                    screen_rect: Some(viewport),
                    events,
                    time: Some(time),
                    ..Default::default()
                },
                |_ui| motion.update(&ctx, viewport, gestures, 1.0, &mut open),
            )
            .textures_delta
            .clear();
            (motion.progress(), open)
        };

        assert_eq!(
            frame(vec![button(point(10.0, 300.0), true)], true),
            (0.0, false)
        );
        assert_eq!(
            frame(vec![Event::PointerMoved(point(20.0, 312.0))], true),
            (0.0, false)
        );
        assert_eq!(
            frame(vec![Event::PointerMoved(point(350.0, 320.0))], true),
            (1.0, false)
        );
        let (reversed, _) = frame(vec![Event::PointerMoved(point(320.0, 320.0))], true);
        assert!((reversed - 0.88).abs() < 0.0001);
        frame(Vec::new(), true);
        assert!(frame(vec![button(point(320.0, 320.0), false)], true).1);
        for _ in 0..24 {
            frame(Vec::new(), true);
        }
        assert_eq!(
            frame(vec![button(point(355.0, 300.0), true)], true),
            (1.0, true)
        );
        assert!(!frame(vec![button(point(355.0, 300.0), false)], true).1);
    }

    #[test]
    fn overlay_shields_home_input_and_uses_the_shifted_layer() {
        let ctx = egui::Context::default();
        let viewport = Rect::from_min_max(Pos2::ZERO, point(385.0, 800.0));
        let run = |progress, events| {
            let mut clicked = false;
            ctx.run_ui(
                egui::RawInput {
                    screen_rect: Some(viewport),
                    events,
                    ..Default::default()
                },
                |ui| {
                    let backdrop = ui.visuals().faint_bg_color;
                    let mut home = home_layer(ui, viewport, viewport, progress, backdrop);
                    let transform = ctx
                        .layer_transform_to_global(home.layer_id())
                        .unwrap_or_default();
                    assert_eq!(transform.scaling, 1.0 - 0.05 * progress);
                    assert!(
                        ((transform * viewport.center()).x
                            - viewport.center().x
                            - 300.0 * progress)
                            .abs()
                            < 0.001
                    );
                    clicked |= home
                        .add_sized([380.0, 750.0], egui::Button::new("home"))
                        .clicked();
                    overlay(&ctx, viewport, progress, backdrop, |ui| {
                        ui.label("drawer");
                        None::<()>
                    });
                },
            )
            .textures_delta
            .clear();
            clicked
        };
        let pos = point(355.0, 300.0);
        run(0.0, Vec::new());
        run(0.0, vec![Event::PointerMoved(pos), button(pos, true)]);
        assert!(run(0.0, vec![button(pos, false)]));
        run(0.5, Vec::new());
        run(0.5, Vec::new());
        assert!(!run(0.5, vec![Event::PointerMoved(pos), button(pos, true)]));
        assert!(!run(0.5, vec![button(pos, false)]));
    }

    #[test]
    fn android_touch_end_keeps_the_final_position_when_the_pointer_up_is_stale() {
        let ctx = egui::Context::default();
        let viewport = Rect::from_min_max(Pos2::ZERO, point(385.0, 800.0));
        let start = point(10.0, 300.0);
        let end = point(110.0, 300.0);
        let mut motion = DrawerMotion::default();
        let mut open = false;
        for events in [
            vec![button(start, true)],
            vec![
                Event::Touch {
                    device_id: egui::TouchDeviceId(0),
                    id: egui::TouchId(0),
                    phase: egui::TouchPhase::End,
                    pos: end,
                    force: None,
                },
                button(start, false),
                Event::PointerGone,
            ],
        ] {
            ctx.run_ui(
                egui::RawInput {
                    screen_rect: Some(viewport),
                    events,
                    ..Default::default()
                },
                |_| motion.update(&ctx, viewport, true, 1.0, &mut open),
            )
            .textures_delta
            .clear();
        }
        assert!(open);
        assert!((motion.progress() - 0.4).abs() < 0.0001);
    }
}
