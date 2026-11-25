use super::super::mobile_chat::mix;
use eframe::egui::{self, Color32, FontId, Rect, Response, Sense, Stroke, pos2, vec2};

pub(in crate::ui) fn text(
    ui: &mut egui::Ui,
    value: &str,
    size: f32,
    height: f32,
    color: Color32,
) -> Response {
    let body = egui::TextStyle::Body.resolve(ui.style());
    let scale = body.size / 15.0;
    let mut job = egui::text::LayoutJob::simple(
        value.into(),
        FontId::new(size * scale, body.family),
        color,
        ui.available_width(),
    );
    job.sections[0].format.line_height = Some(height * scale);
    ui.add(egui::Label::new(job).wrap())
}

pub(in crate::ui) fn description(ui: &mut egui::Ui, value: &str) {
    text(ui, value, 13.0, 18.0, ui.visuals().weak_text_color());
}

pub(in crate::ui) fn section(ui: &mut egui::Ui, title: &str) {
    ui.add_space(10.0);
    first_section(ui, title);
}

pub(in crate::ui) fn first_section(ui: &mut egui::Ui, title: &str) {
    text(ui, title, 14.0, 20.0, ui.visuals().hyperlink_color);
    ui.add_space(4.0);
}

pub(in crate::ui) fn group_header(ui: &mut egui::Ui, title: &str) {
    ui.add_space(10.0);
    text(ui, title, 14.0, 20.0, ui.visuals().hyperlink_color);
    ui.add_space(10.0);
}

pub(in crate::ui) fn card(ui: &egui::Ui, padding: i8) -> egui::Frame {
    egui::Frame::NONE
        .fill(ui.visuals().window_fill)
        .stroke(ui.visuals().window_stroke)
        .corner_radius(ui.visuals().window_corner_radius)
        .inner_margin(padding - 1)
}

pub(in crate::ui) fn link(ui: &mut egui::Ui, title: &str, subtitle: &str) -> bool {
    let start = ui.cursor().min;
    let width = ui.available_width();
    let mut child = ui.new_child(
        egui::UiBuilder::new()
            .max_rect(Rect::from_min_size(
                start + vec2(16.0, 12.0),
                vec2(width - 62.0, 1000.0),
            ))
            .layout(egui::Layout::top_down(egui::Align::Min)),
    );
    child.spacing_mut().item_spacing.y = 0.0;
    text(&mut child, title, 17.0, 24.0, ui.visuals().text_color());
    description(&mut child, subtitle);
    let height = (child.min_rect().height() + 24.0).max(48.0);
    let (rect, response) = ui.allocate_exact_size(vec2(width, height), Sense::click());
    response.widget_info(|| egui::WidgetInfo::labeled(egui::Role::Button, ui.is_enabled(), title));
    let center = pos2(rect.right() - 25.0, rect.center().y);
    let stroke = Stroke::new(1.7, ui.visuals().weak_text_color());
    ui.painter()
        .line_segment([center + vec2(-3.0, -5.0), center + vec2(2.0, 0.0)], stroke);
    ui.painter()
        .line_segment([center + vec2(2.0, 0.0), center + vec2(-3.0, 5.0)], stroke);
    if response.hovered() && ui.input(|input| input.pointer.any_down()) {
        ui.painter().rect_filled(
            rect,
            ui.visuals().window_corner_radius,
            ui.visuals().text_color().gamma_multiply(0.06),
        );
    }
    response.clicked()
}

pub(in crate::ui) fn toggle(ui: &mut egui::Ui, value: &mut bool) -> Response {
    let (rect, mut response) = ui.allocate_exact_size(vec2(48.0, 48.0), Sense::click_and_drag());
    if response.clicked() {
        *value = !*value;
        response.mark_changed();
    }
    if response.dragged()
        && let Some(pointer) = response.interact_pointer_pos()
    {
        let selected = pointer.x > rect.center().x;
        if selected != *value {
            *value = selected;
            response.mark_changed();
        }
    }
    let progress = ui
        .ctx()
        .animate_bool_with_time(response.id, *value, ui.style().animation_time);
    let track = Rect::from_center_size(rect.center(), vec2(46.0, 28.0));
    let off = mix(ui.visuals().panel_fill, ui.visuals().text_color(), 0.12);
    let alpha = if ui.is_enabled() { 1.0 } else { 0.38 };
    ui.painter().rect_filled(
        track,
        14,
        mix(off, ui.visuals().hyperlink_color, progress).gamma_multiply(alpha),
    );
    let center = pos2(track.left() + 14.0 + 18.0 * progress, track.center().y);
    ui.painter().circle(
        center,
        10.0,
        ui.visuals().panel_fill.gamma_multiply(alpha),
        Stroke::new(
            0.5,
            ui.visuals()
                .window_stroke
                .color
                .gamma_multiply(0.55 * alpha),
        ),
    );
    response.widget_info(|| {
        egui::WidgetInfo::selected(egui::Role::CheckBox, ui.is_enabled(), *value, "")
    });
    response
}

pub(in crate::ui) fn row(ui: &mut egui::Ui, value: &mut bool, title: &str, subtitle: &str) {
    let start = ui.cursor().min;
    let width = ui.available_width();
    let mut child = ui.new_child(
        egui::UiBuilder::new()
            .max_rect(Rect::from_min_size(
                start + vec2(0.0, 12.0),
                vec2(width - 60.0, 1000.0),
            ))
            .layout(egui::Layout::top_down(egui::Align::Min)),
    );
    child.spacing_mut().item_spacing.y = 0.0;
    text(&mut child, title, 17.0, 24.0, ui.visuals().text_color());
    description(&mut child, subtitle);
    let height = child.min_rect().height().max(48.0) + 24.0;
    let (rect, _) = ui.allocate_exact_size(vec2(width, height), Sense::hover());
    let mut control = ui.new_child(
        egui::UiBuilder::new()
            .max_rect(Rect::from_center_size(
                pos2(rect.right() - 24.0, rect.center().y),
                vec2(48.0, 48.0),
            ))
            .layout(egui::Layout::top_down(egui::Align::Min)),
    );
    toggle(&mut control, value);
    ui.add_space(4.0);
}

pub(in crate::ui) fn choices<T: Copy + PartialEq>(
    ui: &mut egui::Ui,
    value: &mut T,
    choices: &[(T, &str)],
) {
    let width = ui.available_width();
    let (track, response) = ui.allocate_exact_size(vec2(width, 40.0), Sense::click_and_drag());
    let count = choices.len();
    if (response.clicked() || response.dragged())
        && ui.is_enabled()
        && let Some(pointer) = response.interact_pointer_pos()
    {
        let index = ((pointer.x - track.left()) / width * count as f32)
            .floor()
            .clamp(0.0, (count - 1) as f32) as usize;
        *value = choices[index].0;
    }
    let selected = choices.iter().position(|(choice, _)| choice == value);
    let color = ui.visuals().text_color();
    let off = mix(ui.visuals().panel_fill, color, 0.08);
    ui.painter()
        .rect_filled(track, ui.visuals().widgets.inactive.corner_radius, off);
    let slot = width / count as f32;
    if let Some(selected) = selected {
        let position = ui.ctx().animate_value_with_time(
            response.id,
            selected as f32,
            ui.style().animation_time,
        );
        let rect = Rect::from_min_size(
            track.min + vec2(slot * position + 3.0, 3.0),
            vec2(slot - 6.0, 34.0),
        );
        ui.painter().rect(
            rect,
            ui.visuals().widgets.inactive.corner_radius,
            ui.visuals().panel_fill,
            Stroke::new(0.5, ui.visuals().window_stroke.color),
            egui::StrokeKind::Inside,
        );
    }
    let body = egui::TextStyle::Body.resolve(ui.style());
    for (index, (_, label)) in choices.iter().enumerate() {
        let center = pos2(track.left() + slot * (index as f32 + 0.5), track.center().y);
        ui.painter().text(
            center,
            egui::Align2::CENTER_CENTER,
            label,
            body.clone(),
            if selected == Some(index) {
                color
            } else {
                ui.visuals().weak_text_color()
            },
        );
    }
}

pub(in crate::ui) fn choice_row<T: Copy + PartialEq>(
    ui: &mut egui::Ui,
    title: &str,
    subtitle: &str,
    value: &mut T,
    options: &[(T, &str)],
) {
    ui.add_space(12.0);
    text(ui, title, 17.0, 28.0, ui.visuals().text_color());
    if !subtitle.is_empty() {
        ui.add_space(4.0);
        description(ui, subtitle);
    }
    ui.add_space(8.0);
    ui.push_id(title, |ui| choices(ui, value, options));
    ui.add_space(8.0);
}

pub(in crate::ui) fn slider<T: egui::emath::Numeric>(
    ui: &mut egui::Ui,
    value: &mut T,
    range: std::ops::RangeInclusive<T>,
    title: &str,
    subtitle: &str,
) {
    ui.add_space(12.0);
    text(ui, title, 17.0, 24.0, ui.visuals().text_color());
    description(ui, subtitle);
    let (rect, response) =
        ui.allocate_exact_size(vec2(ui.available_width(), 48.0), Sense::click_and_drag());
    let (min, max) = (range.start().to_f64(), range.end().to_f64());
    if (response.clicked() || response.dragged())
        && ui.is_enabled()
        && let Some(pointer) = response.interact_pointer_pos()
    {
        *value = T::from_f64(
            (min + f64::from(((pointer.x - rect.left()) / rect.width()).clamp(0.0, 1.0))
                * (max - min))
                .round(),
        );
    }
    let position = ((value.to_f64() - min) / (max - min)) as f32;
    let active = ui.visuals().hyperlink_color;
    let track = Rect::from_center_size(rect.center(), vec2(rect.width() - 20.0, 16.0));
    ui.painter().rect_filled(
        track,
        8,
        mix(ui.visuals().panel_fill, ui.visuals().text_color(), 0.06),
    );
    let x = rect.left() + rect.width() * position;
    if x - 8.0 > track.left() {
        ui.painter().rect_filled(
            Rect::from_min_max(track.min, pos2(x - 8.0, track.bottom())),
            8,
            active,
        );
    }
    if max - min <= 40.0 {
        for step in min as i32..=max as i32 {
            let px = rect.left()
                + 18.0
                + (rect.width() - 36.0) * ((f64::from(step) - min) / (max - min)) as f32;
            ui.painter().circle_filled(
                pos2(px, track.center().y),
                2.0,
                if f64::from(step) <= value.to_f64() {
                    ui.visuals().panel_fill
                } else {
                    active
                },
            );
        }
    }
    ui.painter().rect_filled(
        Rect::from_center_size(pos2(x, rect.center().y), vec2(4.0, 44.0)),
        2,
        active,
    );
    ui.add_space(4.0);
}

pub(in crate::ui) fn field(
    ui: &mut egui::Ui,
    label: &str,
    value: &mut String,
    hint: &str,
    password: bool,
) -> Response {
    let id = ui.make_persistent_id(label);
    let focused = ui.memory(|memory| memory.has_focus(id));
    let eye_id = id.with("visible");
    let mut visible = ui
        .ctx()
        .data_mut(|data| data.get_temp::<bool>(eye_id))
        .unwrap_or(false);
    let floating = focused || !value.is_empty();
    let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), 48.0), Sense::hover());
    let outline = if focused {
        ui.visuals().hyperlink_color
    } else {
        ui.visuals().weak_text_color()
    };
    ui.painter().rect(
        rect,
        ui.visuals().widgets.inactive.corner_radius,
        ui.visuals().panel_fill,
        Stroke::new(if focused { 2.0 } else { 1.0 }, outline),
        egui::StrokeKind::Inside,
    );
    let body = egui::TextStyle::Body.resolve(ui.style());
    let text_rect = Rect::from_min_max(
        rect.min + vec2(12.0, 10.0),
        rect.max - vec2(if password { 48.0 } else { 12.0 }, 10.0),
    );
    let mut child = ui.new_child(
        egui::UiBuilder::new()
            .max_rect(text_rect)
            .layout(egui::Layout::top_down(egui::Align::Min)),
    );
    let response = child.add(
        egui::TextEdit::singleline(value)
            .id(id)
            .frame(egui::Frame::NONE)
            .font(FontId::new(
                if floating {
                    body.size
                } else {
                    body.size * 17.0 / 15.0
                },
                body.family.clone(),
            ))
            .desired_width(text_rect.width())
            .min_size(text_rect.size())
            .password(password && !visible)
            .char_limit(8192)
            .hint_text(if floating { hint } else { label }),
    );
    if floating {
        let galley = ui.painter().layout_no_wrap(
            label.into(),
            FontId::new(13.0 * body.size / 15.0, body.family),
            outline,
        );
        let position = rect.min + vec2(12.0, -galley.size().y / 2.0);
        ui.painter().rect_filled(
            Rect::from_min_size(position - vec2(4.0, 0.0), galley.size() + vec2(8.0, 0.0)),
            0,
            ui.visuals().panel_fill,
        );
        ui.painter().galley(position, galley, outline);
    }
    if password {
        let eye = Rect::from_center_size(rect.right_center() - vec2(24.0, 0.0), vec2(40.0, 40.0));
        if ui.interact(eye, eye_id, Sense::click()).clicked() {
            visible = !visible;
            ui.ctx().data_mut(|data| data.insert_temp(eye_id, visible));
        }
        let center = eye.center();
        ui.painter().add(egui::Shape::ellipse_stroke(
            center,
            vec2(8.0, 5.0),
            Stroke::new(1.4, ui.visuals().weak_text_color()),
        ));
        ui.painter()
            .circle_filled(center, 2.0, ui.visuals().weak_text_color());
    }
    response
}
