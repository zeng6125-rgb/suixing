use crate::session_tools::ContextReading;
use eframe::egui::{self, Stroke};

pub(super) fn draw_usage_arc(
    ui: &egui::Ui,
    center: egui::Pos2,
    radius: f32,
    reading: &ContextReading,
    accent: egui::Color32,
) {
    if reading.used == 0 {
        return;
    }
    let fraction = (reading.used as f32 / reading.window.max(1) as f32).min(1.0);
    let points = (0..=24)
        .map(|index| {
            let angle = -std::f32::consts::FRAC_PI_2
                + std::f32::consts::TAU * fraction * index as f32 / 24.0;
            center + egui::vec2(angle.cos(), angle.sin()) * radius
        })
        .collect();
    let color = if fraction >= 0.9 {
        ui.visuals().error_fg_color
    } else {
        accent
    };
    ui.painter()
        .add(egui::Shape::line(points, Stroke::new(2.5, color)));
}

pub(super) fn show(ui: &mut egui::Ui, reading: Option<&ContextReading>, model: &str, window: u64) {
    ui.horizontal(|ui| {
        let (rect, _) = ui.allocate_exact_size(egui::vec2(24.0, 24.0), egui::Sense::hover());
        let painter = ui.painter();
        painter.circle_stroke(
            rect.center(),
            9.0,
            Stroke::new(2.0, ui.visuals().weak_text_color()),
        );
        if let Some(reading) = reading {
            draw_usage_arc(
                ui,
                rect.center(),
                9.0,
                reading,
                ui.visuals().hyperlink_color,
            );
        }
        let label = reading.map_or_else(
            || format!("上下文 — / {window}"),
            |reading| {
                format!(
                    "上下文 {} / {}{}",
                    reading.used,
                    reading.window,
                    if reading.estimated { " · 估算" } else { "" }
                )
            },
        );
        ui.menu_button(label, |ui| details(ui, reading, model, window));
    });
}

pub(super) fn details(
    ui: &mut egui::Ui,
    reading: Option<&ContextReading>,
    model: &str,
    window: u64,
) {
    ui.strong("上下文用量");
    if !model.is_empty() {
        ui.label(format!("模型：{model}"));
    }
    if let Some(reading) = reading {
        ui.label(format!("占用：{} / {} token", reading.used, reading.window));
        ui.label(if reading.estimated {
            "最近请求的上下文估算"
        } else {
            "最近请求的服务端报告"
        });
        ui.separator();
        for (label, tokens) in [
            ("系统", reading.system),
            ("工具", reading.tools),
            ("历史", reading.history),
        ] {
            ui.label(format!("{label}：{tokens} token"));
        }
        ui.label("各部分为估算，服务端报告不包含拆分。");
    } else {
        ui.label(format!("上下文窗口：{window} token"));
        ui.label("发送后显示当前模型实际请求的占用。");
    }
}
